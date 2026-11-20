package com.qimian233.ztool.hook.modules.systemframework

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.ModuleLog
import com.qimian233.ztool.hook.base.SystemHookModule
import com.qimian233.ztool.hook.modules.systemframework.input.AiInputCommandRules
import com.qimian233.ztool.hook.modules.systemframework.input.AiInputCommandRules.CommandEvaluation
import com.qimian233.ztool.hook.modules.systemframework.input.CommitKind
import com.qimian233.ztool.hook.modules.systemframework.input.InputConnectionInterceptor
import com.qimian233.ztool.hook.modules.systemframework.input.InputConnectionProxyHub
import com.qimian233.ztool.hook.modules.systemframework.input.InputConnectionSession
import com.qimian233.ztool.hook.modules.systemframework.input.InputConnectionWireSpec
import com.qimian233.ztool.hook.modules.systemframework.input.RemoteInputConnectionClient
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy

// See docs/research/input_connection_system_server_proxy.md.

/**
 * Forces AI global input features in system_server and detects custom AI triggers
 * across all IMEs and apps via the shared InputConnection Binder proxy.
 */
@SuppressLint("PrivateApi")
class AiInputExpand : SystemHookModule(), InputConnectionInterceptor {

    override val id: String = INTERCEPTOR_ID
    override val priority: Int = 50

    private val configLock = Any()

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var lastRawSigns: String? = null

    @Volatile
    private var parsedTriggers: AiInputCommandRules.ParsedTriggers =
        AiInputCommandRules.parseEffectiveTriggers("")

    @Volatile
    private var snapshotLoadedAt: Long = -SNAPSHOT_REFRESH_INTERVAL_MS

    @Volatile
    private var nativeAppAiEnabled: Boolean = true

    private var icClient: RemoteInputConnectionClient? = null
    private var immsHandles: ImmsAiHandles? = null

    private val workerLock = Any()
    private var workerHandler: Handler? = null

    private val taskLock = Any()
    private var activeTask: AiSessionTask? = null

    override fun getModuleName(): String = PreferenceKeys.AI_INPUT_EXPAND.name

    override fun getTargetPackages(): Array<out String> =
        arrayOf(ScopeKeys.SYSTEM_SERVER.packageName)

    override fun handleSystemServerStarting(param: SystemServerStartingParam) {
        refreshSnapshotIfStale(force = true)

        val classLoader = param.classLoader
        hookLgsiFeatures(classLoader)

        InputConnectionProxyHub.registerInterceptor(this)
        if (!InputConnectionProxyHub.ensureInstalled(xposed, classLoader, logger)) {
            logger.warn("AiInputExpand skipped trigger proxy: InputConnectionProxyHub unavailable")
            return
        }

        val spec = InputConnectionWireSpec.resolve(classLoader)
        if (spec != null) {
            icClient = RemoteInputConnectionClient.create(classLoader, spec, logger)
        }
        immsHandles = ImmsAiHandles.resolve(classLoader, logger)
        installImmsOwnershipHooks(classLoader)
        logger.info("AiInputExpand initialized in system_server, triggers=${parsedTriggers.normalizedTriggers}")
    }

    override fun isActive(): Boolean {
        refreshSnapshotIfStale()
        return enabled
    }

    override fun onTextCommitted(
        session: InputConnectionSession,
        text: CharSequence,
        kind: CommitKind
    ) {
        val triggersSnapshot = parsedTriggers
        if (!isActive() || session.isPasswordField || text.isEmpty()) return
        if (!AiInputCommandRules.mightEndWithTrigger(text, triggersSnapshot)) return

        val client = icClient ?: return
        getWorkerHandler().post {
            evaluateCommittedTrigger(client, session, triggersSnapshot, kind)
        }
    }

    private fun evaluateCommittedTrigger(
        client: RemoteInputConnectionClient,
        session: InputConnectionSession,
        triggers: AiInputCommandRules.ParsedTriggers,
        kind: CommitKind
    ) {
        if (!session.realBinder.isBinderAlive) return
        val beforeText = client.getTextBeforeCursor(
            session.realBinder,
            AiInputCommandRules.MAX_LOOKBACK_CHARS,
            0
        ) ?: return

        val eval = AiInputCommandRules.evaluateBeforeText(beforeText, triggers)
        val handles = immsHandles ?: return
        when (eval) {
            is CommandEvaluation.NoMatch -> return
            is CommandEvaluation.StartOpened -> {
                if (shouldDeferToAppNative(kind, eval.normalizedTrigger)) return
                handles.notifyStartAiTask()
            }
            is CommandEvaluation.InvalidTooLong -> {
                if (shouldDeferToAppNative(kind, eval.normalizedTrigger)) return
                handles.notifyInvalidAiCommand()
            }
            is CommandEvaluation.ExecuteCommand -> {
                if (shouldDeferToAppNative(kind, eval.normalizedTrigger)) return
                val pkg = session.packageName
                if (pkg != null && pkg in AiInputCommandRules.BLACKLIST_PACKAGES) {
                    handles.notifyAiBlacklistHit()
                    return
                }
                val beforeContext = if (pkg in AiInputCommandRules.DOCUMENT_LIST_PACKAGES) {
                    beforeText.subSequence(0, eval.beforeContextEndOffset).toString()
                } else {
                    ""
                }
                val afterContext = if (pkg in AiInputCommandRules.DOCUMENT_LIST_PACKAGES) {
                    client.getTextAfterCursor(
                        session.realBinder,
                        AiInputCommandRules.AI_COMMAND_MAX_LENGTH,
                        0
                    )?.toString().orEmpty()
                } else {
                    ""
                }
                startAiGenerationTask(
                    client = client,
                    handles = handles,
                    session = session,
                    command = eval.command,
                    beforeContext = beforeContext,
                    afterContext = afterContext,
                    rawSpanLength = eval.rawSpanLength
                )
            }
        }
    }

    private fun shouldDeferToAppNative(kind: CommitKind, normalizedTrigger: String): Boolean =
        nativeAppAiEnabled &&
            kind == CommitKind.COMMIT_TEXT &&
            AiInputCommandRules.isOfficialDefaultTrigger(normalizedTrigger)

    private fun startAiGenerationTask(
        client: RemoteInputConnectionClient,
        handles: ImmsAiHandles,
        session: InputConnectionSession,
        command: String,
        beforeContext: String,
        afterContext: String,
        rawSpanLength: Int
    ) {
        val handler = getWorkerHandler()
        val task = AiSessionTask(
            sessionBinder = session.realBinder,
            packageName = session.packageName,
            rawSpanLength = rawSpanLength
        )
        val callback = handles.createRemoteCallback(handler) { result ->
            handleAiChunkResult(client, task, result, handler)
        }

        synchronized(taskLock) {
            activeTask?.cancelPendingFlushes(handler)
            activeTask = task
        }
        handles.requestAiTaskGenerate(command, beforeContext, afterContext, callback)
    }

    private fun handleAiChunkResult(
        client: RemoteInputConnectionClient,
        task: AiSessionTask,
        bundle: android.os.Bundle,
        handler: Handler
    ) {
        if (!isCurrentTask(task) || task.abortedByDelete) return
        val targetBinder = resolveTargetBinder(task) ?: return

        if (task.needsInitialDelete) {
            task.needsInitialDelete = false
            client.deletePromptSpan(targetBinder, task.rawSpanLength)
            if (task.packageName in AiInputCommandRules.SPECIAL_LIST_PACKAGES) {
                task.aiStartPos = client.getCursorPosSafely(targetBinder)
            }
            // Wait briefly so the host editor settles selection after deleting the prompt.
            task.commitReadyAtMs = SystemClock.elapsedRealtime() + POST_DELETE_COMMIT_DELAY_MS
        }

        val content = bundle.getString("content")
        val status = bundle.getInt("status")
        if (!content.isNullOrEmpty()) {
            task.buffer.append(content)
        }

        if (task.packageName in AiInputCommandRules.WPS_THROTTLE_PACKAGES) {
            if (status == STATUS_FINISHED) {
                task.cancelPendingFlushes(handler)
                flushBufferedContent(client, task)
            } else if (!task.wpsFlushScheduled) {
                task.wpsFlushScheduled = true
                val runnable = Runnable {
                    task.wpsFlushScheduled = false
                    if (isCurrentTask(task) && !task.abortedByDelete) {
                        flushBufferedContent(client, task)
                    }
                }
                task.wpsFlushRunnable = runnable
                handler.postDelayed(runnable, WPS_THROTTLE_INTERVAL_MS)
            }
            return
        }

        val delay = (task.commitReadyAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        if (delay == 0L) {
            flushBufferedContent(client, task)
        } else if (!task.normalFlushScheduled) {
            task.normalFlushScheduled = true
            val runnable = Runnable {
                task.normalFlushScheduled = false
                if (isCurrentTask(task) && !task.abortedByDelete) {
                    flushBufferedContent(client, task)
                }
            }
            task.normalFlushRunnable = runnable
            handler.postDelayed(runnable, delay)
        }
    }

    private fun flushBufferedContent(client: RemoteInputConnectionClient, task: AiSessionTask) {
        if (task.buffer.isEmpty()) return
        val text = task.buffer.toString()
        task.buffer.setLength(0)
        val targetBinder = resolveTargetBinder(task) ?: return
        client.commitAiText(targetBinder, text)
    }

    private fun resolveTargetBinder(task: AiSessionTask): IBinder? {
        if (task.sessionBinder.isBinderAlive) return task.sessionBinder
        val active = InputConnectionProxyHub.getActiveSession() ?: return null
        return if (active.packageName == task.packageName && active.realBinder.isBinderAlive) {
            active.realBinder
        } else {
            null
        }
    }

    private fun isCurrentTask(task: AiSessionTask): Boolean =
        synchronized(taskLock) { activeTask === task }

    private fun hookLgsiFeatures(classLoader: ClassLoader) {
        val featureClass = try {
            classLoader.loadClass(LGSI_FEATURES_CLASS)
        } catch (_: ClassNotFoundException) {
            nativeAppAiEnabled = false
            return
        }

        try {
            val enabledStr = featureClass.getDeclaredMethod("enabled", String::class.java)
            nativeAppAiEnabled = runCatching {
                enabledStr.invoke(null, "AIInput") as? Boolean
            }.getOrNull() ?: true
            hookWithId(enabledStr, LGSI_STR_HOOK_ID) { chain ->
                val feature = chain.args.getOrNull(0) as? String
                if (isActive() && feature in FORCED_LGSI_FEATURES) {
                    true
                } else {
                    chain.proceed()
                }
            }
        } catch (_: NoSuchMethodException) {
            nativeAppAiEnabled = false
        }

        try {
            val enabledInt = featureClass.getDeclaredMethod("enabled", Int::class.javaPrimitiveType)
            hookWithId(enabledInt, LGSI_INT_HOOK_ID) { chain ->
                if (isActive()) true else chain.proceed()
            }
        } catch (_: NoSuchMethodException) {
            // Android 17 removed the integer overload; string overload covers it.
        }
    }

    private fun installImmsOwnershipHooks(classLoader: ClassLoader) {
        val immsClass = runCatching {
            classLoader.loadClass(IMMS_CLASS)
        }.getOrNull() ?: return

        runCatching {
            val remoteCallbackClass = classLoader.loadClass(REMOTE_CALLBACK_CLASS)
            val requestGenMethod = immsClass.getMethod(
                "requestAITaskGenerate",
                String::class.java,
                String::class.java,
                String::class.java,
                remoteCallbackClass
            )
            hookWithId(requestGenMethod, NATIVE_GENERATE_HOOK_ID) { chain ->
                // External app IPC calls own the current AI session; do not intercept their delete.
                synchronized(taskLock) { activeTask = null }
                chain.proceed()
            }
        }

        runCatching {
            val deleteMethod = immsClass.getDeclaredMethod(
                "deleteAIInputText",
                Int::class.javaPrimitiveType
            )
            hookWithId(deleteMethod, DELETE_AI_TEXT_HOOK_ID) { chain ->
                if (!isActive()) return@hookWithId chain.proceed()
                val task = synchronized(taskLock) { activeTask }
                    ?: return@hookWithId chain.proceed()
                val handles = immsHandles ?: return@hookWithId chain.proceed()
                val client = icClient ?: return@hookWithId chain.proceed()
                val resultLen = handles.getAiResultLength()
                if (resultLen <= 0) return@hookWithId chain.proceed()

                val targetBinder = resolveTargetBinder(task)
                    ?: return@hookWithId chain.proceed()
                val startPos = if (task.packageName in AiInputCommandRules.SPECIAL_LIST_PACKAGES) {
                    task.aiStartPos
                } else {
                    -1
                }
                task.needsInitialDelete = false
                task.buffer.setLength(0)
                task.commitReadyAtMs = SystemClock.elapsedRealtime() + POST_DELETE_COMMIT_DELAY_MS
                getWorkerHandler().post {
                    client.deleteRangeOrSurrounding(targetBinder, startPos, resultLen)
                }
                handles.resetAiResultLength()
                null
            }
        }
    }

    private fun getWorkerHandler(): Handler {
        workerHandler?.let { return it }
        synchronized(workerLock) {
            workerHandler?.let { return it }
            val thread = HandlerThread("ZTool-AiInputWorker").apply { start() }
            return Handler(thread.looper).also { workerHandler = it }
        }
    }

    private fun refreshSnapshotIfStale(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - snapshotLoadedAt <= SNAPSHOT_REFRESH_INTERVAL_MS) return
        synchronized(configLock) {
            val current = SystemClock.elapsedRealtime()
            if (!force && current - snapshotLoadedAt <= SNAPSHOT_REFRESH_INTERVAL_MS) return
            try {
                val prefs = remotePreferences
                enabled = prefs.getBoolean(
                    PreferenceKeys.AI_INPUT_EXPAND.name,
                    PreferenceKeys.AI_INPUT_EXPAND.default
                )
                val rawSigns = prefs.getString(
                    PreferenceKeys.AI_INPUT_EXPAND_SIGNS.name,
                    PreferenceKeys.AI_INPUT_EXPAND_SIGNS.default
                ) ?: PreferenceKeys.AI_INPUT_EXPAND_SIGNS.default
                if (rawSigns != lastRawSigns) {
                    parsedTriggers = AiInputCommandRules.parseEffectiveTriggers(rawSigns)
                    lastRawSigns = rawSigns
                }
            } catch (_: Throwable) {
                // Keep last known snapshot when remote preferences are temporarily unreachable.
            }
            snapshotLoadedAt = current
        }
    }

    private class AiSessionTask(
        val sessionBinder: IBinder,
        val packageName: String?,
        val rawSpanLength: Int
    ) {
        val buffer = StringBuilder()

        @Volatile
        var needsInitialDelete = true

        @Volatile
        var abortedByDelete = false

        @Volatile
        var aiStartPos = -1

        @Volatile
        var commitReadyAtMs = 0L

        @Volatile
        var wpsFlushScheduled = false

        @Volatile
        var normalFlushScheduled = false

        var wpsFlushRunnable: Runnable? = null
        var normalFlushRunnable: Runnable? = null

        fun cancelPendingFlushes(handler: Handler) {
            wpsFlushRunnable?.let(handler::removeCallbacks)
            normalFlushRunnable?.let(handler::removeCallbacks)
            wpsFlushScheduled = false
            normalFlushScheduled = false
        }
    }

    private class ImmsAiHandles(
        private val sInstanceField: Field,
        private val mServiceField: Field,
        private val mZuiServiceField: Field,
        private val mCurrentImeUserIdField: Field,
        private val getUserDataMethod: Method,
        private val mCurClientField: Field,
        private val mUidField: Field,
        private val mAiCommandField: Field,
        private val mBeforeAiCommandField: Field,
        private val mAfterAiCommandField: Field,
        private val mAiCallbackField: Field,
        private val mAiCallingUidField: Field,
        private val delegateRequestGenerateMethod: Method,
        private val delegateNotifyStartMethod: Method,
        private val delegateNotifyInvalidMethod: Method,
        private val delegateNotifyBlacklistMethod: Method,
        private val delegateGetResultLengthMethod: Method,
        private val delegateResetResultLengthMethod: Method,
        private val remoteCallbackListenerClass: Class<*>,
        private val remoteCallbackCtor: Constructor<*>
    ) {
        fun createRemoteCallback(handler: Handler, onResult: (Bundle) -> Unit): Any {
            val listener = Proxy.newProxyInstance(
                remoteCallbackListenerClass.classLoader,
                arrayOf(remoteCallbackListenerClass)
            ) { proxy, method, args ->
                when (method.name) {
                    "onResult" -> {
                        val bundle = args?.firstOrNull() as? Bundle
                        if (bundle != null) {
                            onResult(bundle)
                        }
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "ZToolRemoteCallbackListener"
                    else -> null
                }
            }
            return remoteCallbackCtor.newInstance(listener, handler)
        }
        private fun currentDelegate(): Any? = runCatching { sInstanceField.get(null) }.getOrNull()

        fun notifyStartAiTask() {
            runCatching { currentDelegate()?.let { delegateNotifyStartMethod.invoke(it) } }
        }

        fun notifyInvalidAiCommand() {
            runCatching { currentDelegate()?.let { delegateNotifyInvalidMethod.invoke(it) } }
        }

        fun notifyAiBlacklistHit() {
            runCatching { currentDelegate()?.let { delegateNotifyBlacklistMethod.invoke(it) } }
        }

        fun getAiResultLength(): Int = runCatching {
            val delegate = currentDelegate() ?: return 0
            (delegateGetResultLengthMethod.invoke(delegate) as? Int) ?: 0
        }.getOrDefault(0)

        fun resetAiResultLength() {
            runCatching { currentDelegate()?.let { delegateResetResultLengthMethod.invoke(it) } }
        }

        fun requestAiTaskGenerate(
            command: String,
            before: String,
            after: String,
            callback: Any
        ) {
            runCatching {
                val delegate = currentDelegate() ?: return
                // Ensure mZuiService exists before requesting generation.
                mZuiServiceField.get(delegate) ?: return
                val imms = mServiceField.get(delegate) ?: return
                val userId = mCurrentImeUserIdField.getInt(imms)
                val userData = getUserDataMethod.invoke(imms, userId)
                val curClient = userData?.let { mCurClientField.get(it) }
                val clientUid = curClient?.let { mUidField.getInt(it) } ?: Process.SYSTEM_UID

                // Sync IMMS regenerate state so assistant Regenerate reuses our callback.
                mAiCommandField.set(imms, command)
                mBeforeAiCommandField.set(imms, before)
                mAfterAiCommandField.set(imms, after)
                mAiCallbackField.set(imms, callback)
                mAiCallingUidField.setInt(imms, clientUid)

                delegateRequestGenerateMethod.invoke(
                    delegate,
                    command,
                    before,
                    after,
                    callback,
                    clientUid
                )
            }
        }

        companion object {
            fun resolve(classLoader: ClassLoader, logger: ModuleLog): ImmsAiHandles? = runCatching {
                val delegateClass = classLoader.loadClass(DELEGATE_CLASS)
                val immsClass = classLoader.loadClass(IMMS_CLASS)
                val userDataClass = classLoader.loadClass(USER_DATA_CLASS)
                val clientStateClass = classLoader.loadClass(CLIENT_STATE_CLASS)
                val remoteCallbackClass = classLoader.loadClass(REMOTE_CALLBACK_CLASS)
                val listenerClass = classLoader.loadClass(REMOTE_CALLBACK_LISTENER_CLASS)
                val callbackCtor = remoteCallbackClass.getConstructor(
                    listenerClass,
                    Handler::class.java
                )

                ImmsAiHandles(
                    sInstanceField = delegateClass.getField("sInstance"),
                    mServiceField = delegateClass.getField("mService"),
                    mZuiServiceField = delegateClass.getField("mZuiService"),
                    mCurrentImeUserIdField = immsClass.getField("mCurrentImeUserId"),
                    getUserDataMethod = immsClass.getMethod(
                        "getUserData",
                        Int::class.javaPrimitiveType
                    ),
                    mCurClientField = userDataClass.getField("mCurClient"),
                    mUidField = clientStateClass.getField("mUid"),
                    mAiCommandField = immsClass.getField("mAICommand"),
                    mBeforeAiCommandField = immsClass.getField("mBeforeAICommand"),
                    mAfterAiCommandField = immsClass.getField("mAfterAICommand"),
                    mAiCallbackField = immsClass.getField("mAICallback"),
                    mAiCallingUidField = immsClass.getField("mAICallingUid"),
                    delegateRequestGenerateMethod = delegateClass.getMethod(
                        "requestAITaskGenerate",
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        remoteCallbackClass,
                        Int::class.javaPrimitiveType
                    ),
                    delegateNotifyStartMethod = delegateClass.getMethod("notifyStartAITask"),
                    delegateNotifyInvalidMethod = delegateClass.getMethod("notifyInvalidAICommand"),
                    delegateNotifyBlacklistMethod = delegateClass.getMethod("notifyAIBlacklistHit"),
                    delegateGetResultLengthMethod = delegateClass.getMethod("getAIResultLength"),
                    delegateResetResultLengthMethod = delegateClass.getMethod("resetAIResultLength"),
                    remoteCallbackListenerClass = listenerClass,
                    remoteCallbackCtor = callbackCtor
                )
            }.onFailure {
                logger.warn("ImmsAiHandles unavailable: ${it.javaClass.simpleName}")
            }.getOrNull()
        }
    }

    companion object {
        private const val INTERCEPTOR_ID = "ai_input_expand"
        private const val SNAPSHOT_REFRESH_INTERVAL_MS = 2_000L
        private const val POST_DELETE_COMMIT_DELAY_MS = 300L
        private const val WPS_THROTTLE_INTERVAL_MS = 150L
        private const val STATUS_FINISHED = 2

        private const val LGSI_FEATURES_CLASS = "com.lgsi.config.LgsiFeatures"
        private const val DELEGATE_CLASS =
            "com.android.server.inputmethod.InputMethodManagerServiceDelegate"
        private const val IMMS_CLASS =
            "com.android.server.inputmethod.InputMethodManagerService"
        private const val USER_DATA_CLASS =
            "com.android.server.inputmethod.UserData"
        private const val CLIENT_STATE_CLASS =
            "com.android.server.inputmethod.ClientState"
        private const val REMOTE_CALLBACK_CLASS = "android.os.RemoteCallback"
        private const val REMOTE_CALLBACK_LISTENER_CLASS =
            "android.os.RemoteCallback\$OnResultListener"

        private const val LGSI_STR_HOOK_ID = "ai_input_lgsi_enabled_str"
        private const val LGSI_INT_HOOK_ID = "ai_input_lgsi_enabled_int"
        private const val NATIVE_GENERATE_HOOK_ID = "ai_input_native_generate"
        private const val DELETE_AI_TEXT_HOOK_ID = "ai_input_delete_text"

        private val FORCED_LGSI_FEATURES = setOf("AIInput", "ZuiIMMS", "ZuiIms")
    }
}
