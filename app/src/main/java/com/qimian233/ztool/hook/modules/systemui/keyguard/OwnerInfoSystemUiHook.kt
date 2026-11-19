package com.qimian233.ztool.hook.modules.systemui.keyguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.ViewGroup
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/** Displays cached network quotes through the keyguard owner's native reader. */
class OwnerInfoSystemUiHook : AppHookModule() {
    private val cache = OwnerInfoQuoteCache()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val screenReceiverRegistered = AtomicBoolean(false)
    private data class Target(val controller: WeakReference<Any>, val lockPatternUtils: WeakReference<Any>)
    @Volatile private var target: Target? = null
    @Volatile private var powerManager: PowerManager? = null
    private lateinit var lockPatternUtilsField: Field
    private lateinit var visibleField: Field
    private lateinit var dozingField: Field
    private lateinit var areaField: Field
    private lateinit var keyguardStateField: Field
    private lateinit var showingField: Field
    private lateinit var currentUser: Method
    private lateinit var refresh: Method

    override fun getModuleName(): String = PreferenceKeys.AUTO_OWNER_INFO.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_UI.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val loader = param.defaultClassLoader
        val controllerClass = loader.loadClass("com.android.systemui.statusbar.KeyguardIndicationController")
        val utilsClass = loader.loadClass("com.android.internal.widget.LockPatternUtils")
        lockPatternUtilsField = findField(controllerClass, "mLockPatternUtils")
        visibleField = findField(controllerClass, "mVisible")
        dozingField = findField(controllerClass, "mDozing")
        areaField = findField(controllerClass, "mIndicationArea")
        keyguardStateField = findField(controllerClass, "mKeyguardStateController")
        showingField = findField(loader.loadClass(
            "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl"), "mShowing")
        currentUser = findMethod(controllerClass, "getCurrentUser")
        refresh = try {
            findMethod(controllerClass, "updateDeviceEntryIndication", Boolean::class.javaPrimitiveType)
        } catch (_: NoSuchMethodException) {
            findMethod(controllerClass, "updateIndication", Boolean::class.javaPrimitiveType)
        }

        hookWithId(refresh, "owner_info_capture_refresh") { chain ->
            capture(chain.thisObject)
            chain.proceed()
        }
        hookWithId(findMethod(controllerClass, "setIndicationArea", ViewGroup::class.java),
            "owner_info_capture_area") { chain ->
            val result = chain.proceed()
            capture(chain.thisObject, chain.args[0] as? ViewGroup)
            result
        }

        hookWithId(findMethod(utilsClass, "getDeviceOwnerInfo"), "owner_info_device_read") { chain ->
            val config = readerConfig(chain.thisObject)
            if (config == null || !config.deviceOwner) return@hookWithId chain.proceed()
            readQuote(config) ?: chain.proceed()
        }
        hookWithId(findMethod(utilsClass, "isOwnerInfoEnabled", Int::class.javaPrimitiveType),
            "owner_info_enabled_read") { chain ->
            val config = readerConfig(chain.thisObject, chain.args[0] as Int)
            if (config == null || config.deviceOwner) return@hookWithId chain.proceed()
            if (readQuote(config) != null) true else chain.proceed()
        }
        hookWithId(findMethod(utilsClass, "getOwnerInfo", Int::class.javaPrimitiveType),
            "owner_info_personal_read") { chain ->
            val config = readerConfig(chain.thisObject, chain.args[0] as Int)
            if (config == null || config.deviceOwner) return@hookWithId chain.proceed()
            readQuote(config) ?: chain.proceed()
        }
        logger.info("Installed instance-scoped SystemUI owner-info readers")
    }

    private fun capture(controller: Any?, area: ViewGroup? = null) {
        if (controller == null) return
        target = Target(WeakReference(controller), WeakReference(lockPatternUtilsField.get(controller)))
        if (powerManager != null && screenReceiverRegistered.get()) return
        val indicationArea = area ?: (areaField.get(controller) as? ViewGroup)
        val appContext = indicationArea?.context?.applicationContext ?: indicationArea?.context
        if (appContext != null) {
            ensureScreenStateReceiver(appContext)
        }
    }

    private fun ensureScreenStateReceiver(context: Context) {
        if (powerManager == null) {
            powerManager = context.getSystemService(PowerManager::class.java)
        }
        if (!screenReceiverRegistered.compareAndSet(false, true)) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> cache.onScreenOff()
                    Intent.ACTION_SCREEN_ON -> cache.onScreenOn(SystemClock.elapsedRealtime())
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            if (powerManager?.isInteractive == false) {
                cache.onScreenOff()
            }
        } catch (error: Exception) {
            screenReceiverRegistered.set(false)
            logger.warn("Failed to register owner-info screen state receiver: ${error.message}")
        }
    }

    private fun readerConfig(instance: Any?, userId: Int? = null): OwnerInfoQuoteConfig? {
        val current = target ?: return null
        if (instance == null || instance !== current.lockPatternUtils.get()) return null
        val controller = current.controller.get() ?: return null
        val config = configuration(controller) ?: return null
        return config.takeIf { userId == null || userId == it.userId }
    }

    private fun configuration(controller: Any): OwnerInfoQuoteConfig? {
        if (!isEnabled()) return null
        val prefs = remotePreferences
        return OwnerInfoQuoteConfig(
            userId = currentUser.invoke(controller) as Int,
            apiUrl = prefs.getString(PreferenceKeys.API_URL.name, PreferenceKeys.API_URL.default)
                .orEmpty().ifBlank { PreferenceKeys.API_URL.default },
            regex = prefs.getString(PreferenceKeys.REGULAR.name, PreferenceKeys.REGULAR.default)
                .orEmpty().ifBlank { PreferenceKeys.REGULAR.default },
            deviceOwner = prefs.getBoolean(PreferenceKeys.OWNER_INFO_OVERRIDE_DEVICE.name,
                PreferenceKeys.OWNER_INFO_OVERRIDE_DEVICE.default)
        )
    }

    private fun readQuote(config: OwnerInfoQuoteConfig): String? {
        val isInteractive = powerManager?.isInteractive
            ?: (target?.controller?.get()?.let { !dozingField.getBoolean(it) } ?: true)
        val read = cache.read(config, SystemClock.elapsedRealtime(), isInteractive)
        read.request?.let { request ->
            Thread({
                val content = try {
                    fetchOwnerInfoQuote(request.config)
                } catch (error: Exception) {
                    logger.warn("Owner-info quote request failed: ${error.message}")
                    null
                }
                mainHandler.post {
                    try {
                        val controller = target?.controller?.get()
                        val currentConfig = controller?.let(::configuration)
                        val changed = cache.complete(request, content, SystemClock.elapsedRealtime(), currentConfig)
                        if (changed && controller != null && visibleField.getBoolean(controller) &&
                            !dozingField.getBoolean(controller) &&
                            showingField.getBoolean(keyguardStateField.get(controller)) &&
                            (areaField.get(controller) as? ViewGroup)?.isAttachedToWindow == true
                        ) {
                            refresh.invoke(controller, false)
                        }
                    } catch (error: Exception) {
                        cache.complete(request, null, SystemClock.elapsedRealtime(), null)
                        logger.error("Failed to refresh cached owner info", error)
                    }
                }
            }, "ZTool-OwnerInfo").apply { isDaemon = true }.start()
        }
        return read.content
    }
}
