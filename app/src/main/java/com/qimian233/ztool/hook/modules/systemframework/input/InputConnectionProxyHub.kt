package com.qimian233.ztool.hook.modules.systemframework.input

import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.view.inputmethod.EditorInfo
import com.qimian233.ztool.hook.base.ModuleLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

// See docs/research/input_connection_system_server_proxy.md.

/**
 * Shared hub coordinating IRemoteInputConnection and IInputMethodSession Binder
 * proxies inside system_server for registered InputConnectionInterceptor modules.
 */
object InputConnectionProxyHub {

    private const val START_INPUT_HOOK_ID = "imms_input_connection_proxy"
    private const val BIND_RESULT_HOOK_ID = "imms_input_bind_result_session_proxy"
    private const val INVOKER_CLASS = "com.android.server.inputmethod.IInputMethodInvoker"
    private const val BIND_RESULT_CLASS = "com.android.internal.inputmethod.InputBindResult"

    private val interceptors = ConcurrentHashMap<String, InputConnectionInterceptor>()
    private val proxyMapLock = Any()
    private val icProxyMap = WeakHashMap<IBinder, WeakReference<InputConnectionProxyBinder>>()
    private val sessionProxyMap =
        WeakHashMap<IBinder, WeakReference<InputMethodSessionProxyBinder>>()

    @Volatile
    private var activeSessionRef: WeakReference<InputConnectionSession>? = null

    @Volatile
    private var installed = false

    fun registerInterceptor(interceptor: InputConnectionInterceptor) {
        interceptors[interceptor.id] = interceptor
    }

    fun hasActiveInterceptor(): Boolean =
        interceptors.values.any { runCatching { it.isActive() }.getOrDefault(false) }

    fun activeInterceptors(): List<InputConnectionInterceptor> =
        interceptors.values
            .filter { runCatching { it.isActive() }.getOrDefault(false) }
            .sortedByDescending { it.priority }

    fun findProxyForRealBinder(realBinder: IBinder): InputConnectionProxyBinder? =
        synchronized(proxyMapLock) { icProxyMap[realBinder]?.get() }

    fun getActiveSession(): InputConnectionSession? = activeSessionRef?.get()

    /** Installs the dual-channel system_server hooks once per module ClassLoader generation. */
    @Synchronized
    fun ensureInstalled(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        logger: ModuleLog
    ): Boolean {
        if (installed) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            logger.info("InputConnectionProxyHub requires API 33+, skipped")
            return false
        }

        val spec = InputConnectionWireSpec.resolve(classLoader)
        if (spec == null) {
            logger.warn("InputConnectionWireSpec unavailable, skipping proxy installation")
            return false
        }

        val startInputHandle = installStartInputHook(xposed, classLoader, spec, logger) ?: return false
        val bindResultHandle = try {
            installBindResultHook(xposed, classLoader, spec, logger)
        } catch (t: Throwable) {
            startInputHandle.unhook()
            logger.error("Failed installing InputBindResult hook", t)
            return false
        }
        if (bindResultHandle == null) {
            startInputHandle.unhook()
            return false
        }

        installed = true
        logger.info("InputConnectionProxyHub dual-channel hooks installed")
        return true
    }

    private fun installStartInputHook(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        spec: InputConnectionWireSpec,
        logger: ModuleLog
    ): HookHandle? {
        return try {
            val invokerClass = classLoader.loadClass(INVOKER_CLASS)
            val startInputMethod = invokerClass.declaredMethods.firstOrNull {
                it.name == "startInput" && spec.remoteIcClass in it.parameterTypes
            } ?: run {
                logger.error("$INVOKER_CLASS.startInput not found")
                return null
            }
            val icParamIndex = startInputMethod.parameterTypes.indexOf(spec.remoteIcClass)
            val editorInfoParamIndex = startInputMethod.parameterTypes.indexOf(EditorInfo::class.java)

            hookWithStableId(xposed, startInputMethod, START_INPUT_HOOK_ID) { chain ->
                if (!hasActiveInterceptor()) return@hookWithStableId chain.proceed()
                val rawIc = chain.args.getOrNull(icParamIndex) as? IInterface
                    ?: return@hookWithStableId chain.proceed()
                val realBinder = rawIc.asBinder()
                if (realBinder is Binder) return@hookWithStableId chain.proceed()

                val editorInfo = if (editorInfoParamIndex >= 0) {
                    chain.args.getOrNull(editorInfoParamIndex) as? EditorInfo
                } else {
                    null
                }
                val proxyBinder = getOrCreateIcProxy(realBinder, editorInfo, spec, logger)
                activeSessionRef = WeakReference(proxyBinder.session)
                notifySessionStarted(proxyBinder.session, logger)

                val args = chain.args.toMutableList()
                args[icParamIndex] = spec.asRemoteInputConnection(proxyBinder)
                chain.proceed(args.toTypedArray())
            }
        } catch (t: Throwable) {
            logger.error("Failed installing startInput hook", t)
            null
        }
    }

    private fun installBindResultHook(
        xposed: XposedInterface,
        classLoader: ClassLoader,
        spec: InputConnectionWireSpec,
        logger: ModuleLog
    ): HookHandle? {
        return try {
            val bindResultClass = classLoader.loadClass(BIND_RESULT_CLASS)
            val bindResultCtor = bindResultClass.declaredConstructors.firstOrNull {
                spec.inputMethodSessionClass in it.parameterTypes
            } ?: run {
                logger.error("$BIND_RESULT_CLASS constructor with IInputMethodSession not found")
                return null
            }
            val sessionParamIndex =
                bindResultCtor.parameterTypes.indexOf(spec.inputMethodSessionClass)

            hookWithStableId(xposed, bindResultCtor, BIND_RESULT_HOOK_ID) { chain ->
                if (!hasActiveInterceptor()) return@hookWithStableId chain.proceed()
                val rawSession = chain.args.getOrNull(sessionParamIndex) as? IInterface
                    ?: return@hookWithStableId chain.proceed()
                val realSessionBinder = rawSession.asBinder()
                if (realSessionBinder is Binder) return@hookWithStableId chain.proceed()

                val proxySessionBinder = getOrCreateSessionProxy(realSessionBinder, spec, logger)
                val args = chain.args.toMutableList()
                args[sessionParamIndex] = spec.asInputMethodSession(proxySessionBinder)
                chain.proceed(args.toTypedArray())
            }
        } catch (t: Throwable) {
            logger.error("Failed installing InputBindResult hook", t)
            null
        }
    }

    private fun getOrCreateIcProxy(
        realBinder: IBinder,
        editorInfo: EditorInfo?,
        spec: InputConnectionWireSpec,
        logger: ModuleLog
    ): InputConnectionProxyBinder = synchronized(proxyMapLock) {
        val existing = icProxyMap[realBinder]?.get()
        if (existing != null) {
            existing.session.updateFromEditorInfo(editorInfo)
            return@synchronized existing
        }
        val session = InputConnectionSession(realBinder, editorInfo)
        InputConnectionProxyBinder(session, spec, this, logger).also {
            icProxyMap[realBinder] = WeakReference(it)
        }
    }

    private fun getOrCreateSessionProxy(
        realSessionBinder: IBinder,
        spec: InputConnectionWireSpec,
        logger: ModuleLog
    ): InputMethodSessionProxyBinder = synchronized(proxyMapLock) {
        val existing = sessionProxyMap[realSessionBinder]?.get()
        if (existing != null) return@synchronized existing
        InputMethodSessionProxyBinder(realSessionBinder, spec, this, logger).also {
            sessionProxyMap[realSessionBinder] = WeakReference(it)
        }
    }

    private fun notifySessionStarted(session: InputConnectionSession, logger: ModuleLog) {
        for (interceptor in activeInterceptors()) {
            runCatching { interceptor.onSessionStarted(session) }
                .onFailure { logger.warn("Interceptor ${interceptor.id} start failed: ${it.message}") }
        }
    }

    private fun hookWithStableId(
        xposed: XposedInterface,
        target: Executable,
        id: String,
        hooker: Hooker
    ): HookHandle {
        val builder = xposed.hook(target)
        if (xposed.apiVersion >= XposedInterface.API_102) {
            builder.setId(id)
        }
        return builder
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept(hooker)
    }
}
