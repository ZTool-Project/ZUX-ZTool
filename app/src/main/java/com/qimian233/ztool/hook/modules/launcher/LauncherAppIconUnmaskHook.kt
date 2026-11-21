package com.qimian233.ztool.hook.modules.launcher

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import com.google.gson.JsonObject
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.dexindex.base.DexIndexConstants
import com.qimian233.ztool.hook.base.AppHookModule
import com.qimian233.ztool.hook.base.DexIndexStore
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unmasks launcher app icons by redrawing [AdaptiveIconDrawable] foreground/background at full bleed.
 *
 * Hooks BaseIconFactory#createBadgedIconBitmap and createBadgedIconBitmapZui, skipping dynamic
 * BitmapInfo.Extender icons unless LAUNCHER_APP_ICON_UNMASK_DYNAMIC is enabled.
 */
@SuppressLint("PrivateApi")
class LauncherAppIconUnmaskHook : AppHookModule() {

    override fun getModuleName(): String = PreferenceKeys.LAUNCHER_APP_ICON_UNMASK.name

    @Volatile
    private var includeDynamicIcons = false

    override fun getTargetPackages(): Array<String> = arrayOf(TARGET_PACKAGE)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        if (TARGET_PACKAGE != param.packageName) return
        val cl = param.defaultClassLoader
        includeDynamicIcons = remotePreferences.getBoolean(
            PreferenceKeys.LAUNCHER_APP_ICON_UNMASK_DYNAMIC.name,
            PreferenceKeys.LAUNCHER_APP_ICON_UNMASK_DYNAMIC.default
        )

        val module = DexIndexStore.lookup(xposed, ScopeKeys.LAUNCHER.packageName)
            ?.getAsJsonObject(DexIndexConstants.JSON_MODULES)
            ?.getAsJsonObject(DexIndexConstants.ModuleKeys.LAUNCHER_APP_ICON_UNMASK)

        val factoryClassName = readString(module, DexIndexConstants.Keys.UNMASK_FACTORY_CLASS)
            ?: DEFAULT_FACTORY_CLASS
        val optionsClassName = readString(module, DexIndexConstants.Keys.UNMASK_OPTIONS_CLASS)
            ?: DEFAULT_OPTIONS_CLASS
        val factoryClass = try {
            cl.loadClass(factoryClassName)
        } catch (t: Throwable) {
            logger.warn("Failed to load BaseIconFactory: ${t.message}")
            return
        }
        val optionsClass = try {
            cl.loadClass(optionsClassName)
        } catch (t: Throwable) {
            logger.warn("Failed to load IconOptions: ${t.message}")
            return
        }

        val handles = resolveHandles(cl, factoryClass, module)
        if (handles == null) {
            logger.warn("Failed to resolve BitmapInfo handles for icon unmask")
            return
        }

        val badgedParams = readParams(cl, module, DexIndexConstants.Keys.UNMASK_BADGED_PARAMS)
            ?: arrayOf(Drawable::class.java, optionsClass)
        val badgedName = readString(module, DexIndexConstants.Keys.UNMASK_BADGED_METHOD)
            ?: "createBadgedIconBitmap"
        try {
            val method = findMethod(factoryClass, badgedName, *badgedParams)
            installBadgedHook(method, "launcher_icon_unmask_badged", handles)
            logger.info("Hooked $factoryClassName#$badgedName for icon unmask.")
        } catch (t: Throwable) {
            logger.warn("Failed to hook $badgedName: ${t.message}")
        }

        val badgedZuiName = readString(module, DexIndexConstants.Keys.UNMASK_BADGED_ZUI_METHOD)
            ?: "createBadgedIconBitmapZui"
        runCatching { findMethod(factoryClass, badgedZuiName, *badgedParams) }
            .onSuccess { method ->
                installBadgedHook(method, "launcher_icon_unmask_badged_zui", handles)
                logger.info("Hooked $factoryClassName#$badgedZuiName for icon unmask.")
            }
            .onFailure {
                logger.debug("createBadgedIconBitmapZui absent, skipped: ${it.message}")
            }
    }

    private fun installBadgedHook(method: Method, hookId: String, handles: IconUnmaskHandles) {
        hookWithId(method, hookId) { chain ->
            val result = chain.proceed()
            try {
                val input = chain.args[0] as? AdaptiveIconDrawable
                if (input != null) {
                    val replaced = rebuildUnmasked(chain.thisObject, input, result, handles)
                    if (replaced != null) {
                        if (logCounter.getAndIncrement() < LIMIT) {
                            logger.info(
                                "[unmask] rebuilt unmasked icon: in=${input.javaClass.simpleName} " +
                                    "bitmapSize=${replaced.second}"
                            )
                        }
                        return@hookWithId replaced.first
                    }
                }
            } catch (t: Throwable) {
                logger.warn("[unmask] rebuild failed, keep original: ${t.message}")
            }
            result
        }
    }

    private fun resolveHandles(
        cl: ClassLoader,
        factoryClass: Class<*>,
        module: JsonObject?
    ): IconUnmaskHandles? {
        val bitmapInfoClassName = readString(module, DexIndexConstants.Keys.UNMASK_BITMAP_INFO_CLASS)
            ?: DEFAULT_BITMAP_INFO_CLASS
        val bitmapInfoClass = runCatching { cl.loadClass(bitmapInfoClassName) }.getOrNull()
            ?: return null

        val extenderClassName = readString(module, DexIndexConstants.Keys.UNMASK_EXTENDER_CLASS)
            ?: DEFAULT_EXTENDER_CLASS
        val extenderClass = runCatching { cl.loadClass(extenderClassName) }.getOrNull()

        val sizeField = tryField(factoryClass, readString(module, DexIndexConstants.Keys.UNMASK_SIZE_FIELD) ?: "iconBitmapSize")
        val sizeMethod = tryMethod(factoryClass, readString(module, DexIndexConstants.Keys.UNMASK_SIZE_METHOD) ?: "getIconBitmapSize")

        val bitmapIconField = tryField(bitmapInfoClass, readString(module, DexIndexConstants.Keys.UNMASK_BITMAP_ICON_FIELD) ?: "icon")
        val colorField = tryField(bitmapInfoClass, readString(module, DexIndexConstants.Keys.UNMASK_COLOR_FIELD) ?: "color")
        val flagsField = tryField(bitmapInfoClass, readString(module, DexIndexConstants.Keys.UNMASK_FLAGS_FIELD) ?: "flags")
        val creationFlagsField = tryField(
            bitmapInfoClass,
            readString(module, DexIndexConstants.Keys.UNMASK_CREATION_FLAGS_FIELD) ?: "creationFlags"
        )
        val legacyOfMethod = tryDeclaredMethod(
            bitmapInfoClass,
            "of",
            Bitmap::class.java,
            Int::class.javaPrimitiveType
        )

        val atLeastT = runCatching {
            cl.loadClass(DEFAULT_ICON_PROVIDER_CLASS).getField("ATLEAST_T").getBoolean(null)
        }.getOrDefault(false)

        val themeControllerField = tryField(
            factoryClass,
            readString(module, DexIndexConstants.Keys.UNMASK_THEME_CONTROLLER_FIELD) ?: "themeController"
        )
        val themeControllerMethod = tryMethod(
            factoryClass,
            readString(module, DexIndexConstants.Keys.UNMASK_THEME_CONTROLLER_METHOD) ?: "getThemeController"
        )
        val controllerClass = readString(module, DexIndexConstants.Keys.UNMASK_THEME_CONTROLLER_CLASS)
            ?.let { runCatching { cl.loadClass(it) }.getOrNull() }
            ?: themeControllerField?.type
            ?: themeControllerMethod?.returnType
            ?: runCatching { cl.loadClass(DEFAULT_THEME_CONTROLLER_CLASS) }.getOrNull()

        val createThemedName = readString(module, DexIndexConstants.Keys.UNMASK_CREATE_THEMED_METHOD)
            ?: "createThemedBitmap"
        val indexedThemedParams = readParams(cl, module, DexIndexConstants.Keys.UNMASK_CREATE_THEMED_PARAMS)
        val createThemedMethod = if (controllerClass != null) {
            resolveCreateThemedMethod(cl, controllerClass, createThemedName, indexedThemedParams, bitmapInfoClass, factoryClass)
        } else {
            null
        }

        val themedBitmapClassName = readString(module, DexIndexConstants.Keys.UNMASK_THEMED_BITMAP_CLASS)
            ?: DEFAULT_THEMED_BITMAP_CLASS
        val themedBitmapClass = runCatching { cl.loadClass(themedBitmapClassName) }.getOrNull()
        val themedBitmapField = tryField(
            bitmapInfoClass,
            readString(module, DexIndexConstants.Keys.UNMASK_THEMED_BITMAP_FIELD) ?: "themedBitmap"
        )
        val themedBitmapSetter = themedBitmapClass?.let {
            tryMethod(
                bitmapInfoClass,
                readString(module, DexIndexConstants.Keys.UNMASK_THEMED_BITMAP_SETTER) ?: "setThemedBitmap",
                it
            )
        }

        return IconUnmaskHandles(
            bitmapInfoClass = bitmapInfoClass,
            extenderClass = extenderClass,
            sizeField = sizeField,
            sizeMethod = sizeMethod,
            bitmapIconField = bitmapIconField,
            colorField = colorField,
            flagsField = flagsField,
            creationFlagsField = creationFlagsField,
            legacyOfMethod = legacyOfMethod,
            atLeastT = atLeastT,
            themeControllerField = themeControllerField,
            themeControllerMethod = themeControllerMethod,
            createThemedMethod = createThemedMethod,
            themedBitmapField = themedBitmapField,
            themedBitmapSetter = themedBitmapSetter
        )
    }

    private fun resolveCreateThemedMethod(
        cl: ClassLoader,
        controllerClass: Class<*>,
        methodName: String,
        indexedParams: Array<Class<*>>?,
        bitmapInfoClass: Class<*>,
        factoryClass: Class<*>
    ): Method? {
        if (indexedParams != null) {
            tryMethod(controllerClass, methodName, *indexedParams)?.let { return it }
        }
        tryMethod(
            controllerClass,
            methodName,
            AdaptiveIconDrawable::class.java,
            bitmapInfoClass,
            factoryClass
        )?.let { return it }
        val sourceHintClass = runCatching { cl.loadClass(DEFAULT_SOURCE_HINT_CLASS) }.getOrNull()
            ?: return null
        return tryMethod(
            controllerClass,
            methodName,
            AdaptiveIconDrawable::class.java,
            bitmapInfoClass,
            factoryClass,
            sourceHintClass
        )
    }

    private fun rebuildUnmasked(
        factory: Any?,
        input: AdaptiveIconDrawable,
        result: Any?,
        handles: IconUnmaskHandles
    ): Pair<Any, Int>? {
        if (factory == null || result == null) return null
        if (!includeDynamicIcons &&
            (handles.extenderClass?.isInstance(input) == true || result.javaClass != handles.bitmapInfoClass)
        ) {
            return null
        }

        val size = handles.sizeField?.getInt(factory)
            ?: (handles.sizeMethod?.invoke(factory) as? Int)
            ?: return null
        if (size <= 0) return null

        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val bg = input.background
        val fg = input.foreground
        val bounds = Rect(0, 0, size, size)
        if (bg != null) {
            bg.bounds = bounds
            bg.draw(canvas)
        }
        if (fg != null) {
            val pad = (size * (1f - ADAPTIVE_VISIBLE_RATIO) / 2f).toInt()
            val fgBounds = Rect(-pad, -pad, size + pad, size + pad)
            fg.bounds = fgBounds
            fg.draw(canvas)
        }

        val targetInfo = if (handles.legacyOfMethod != null) {
            val color = handles.colorField?.getInt(result) ?: return null
            val newInfo = handles.legacyOfMethod.invoke(null, bitmap, color) ?: return null
            handles.flagsField?.let { it.setInt(newInfo, it.getInt(result)) }
            handles.creationFlagsField?.let { it.setInt(newInfo, it.getInt(result)) }
            newInfo
        } else {
            val iconField = handles.bitmapIconField ?: return null
            iconField.set(result, bitmap)
            result
        }

        restoreThemedBitmap(factory, input, targetInfo, handles)
        return Pair(targetInfo, size)
    }

    private fun restoreThemedBitmap(
        factory: Any,
        input: AdaptiveIconDrawable,
        targetInfo: Any,
        handles: IconUnmaskHandles
    ) {
        if (!handles.atLeastT) return
        val createMethod = handles.createThemedMethod ?: return
        try {
            val controller = handles.themeControllerField?.get(factory)
                ?: handles.themeControllerMethod?.invoke(factory)
                ?: return
            val themed = when (createMethod.parameterCount) {
                3 -> createMethod.invoke(controller, input, targetInfo, factory)
                4 -> createMethod.invoke(controller, input, targetInfo, factory, null)
                else -> null
            } ?: return
            if (handles.themedBitmapField != null) {
                handles.themedBitmapField.set(targetInfo, themed)
            } else {
                handles.themedBitmapSetter?.invoke(targetInfo, themed)
            }
        } catch (t: Throwable) {
            logger.debug("[unmask] themed bitmap restore skipped: ${t.message}")
        }
    }

    private fun tryField(owner: Class<*>, name: String): Field? =
        runCatching { findField(owner, name) }.getOrNull()

    private fun tryMethod(owner: Class<*>, name: String, vararg params: Class<*>?): Method? =
        runCatching { findMethod(owner, name, *params) }.getOrNull()

    private fun tryDeclaredMethod(owner: Class<*>, name: String, vararg params: Class<*>?): Method? =
        runCatching { owner.getDeclaredMethod(name, *params).apply { isAccessible = true } }.getOrNull()

    private fun readString(module: JsonObject?, key: String): String? =
        module?.get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }

    private fun readParams(
        classLoader: ClassLoader,
        module: JsonObject?,
        key: String
    ): Array<Class<*>>? {
        val raw = readString(module, key) ?: return null
        return runCatching {
            raw.split(",").map { resolveType(classLoader, it.trim()) }.toTypedArray()
        }.getOrNull()
    }

    private fun resolveType(classLoader: ClassLoader, name: String): Class<*> = when (name) {
        "boolean" -> Boolean::class.javaPrimitiveType!!
        "byte" -> Byte::class.javaPrimitiveType!!
        "short" -> Short::class.javaPrimitiveType!!
        "int" -> Int::class.javaPrimitiveType!!
        "long" -> Long::class.javaPrimitiveType!!
        "float" -> Float::class.javaPrimitiveType!!
        "double" -> Double::class.javaPrimitiveType!!
        "char" -> Char::class.javaPrimitiveType!!
        "void" -> Void.TYPE
        else -> if (name.endsWith("[]")) {
            val element = resolveType(classLoader, name.removeSuffix("[]"))
            java.lang.reflect.Array.newInstance(element, 0).javaClass
        } else {
            classLoader.loadClass(name)
        }
    }

    private class IconUnmaskHandles(
        val bitmapInfoClass: Class<*>,
        val extenderClass: Class<*>?,
        val sizeField: Field?,
        val sizeMethod: Method?,
        val bitmapIconField: Field?,
        val colorField: Field?,
        val flagsField: Field?,
        val creationFlagsField: Field?,
        val legacyOfMethod: Method?,
        val atLeastT: Boolean,
        val themeControllerField: Field?,
        val themeControllerMethod: Method?,
        val createThemedMethod: Method?,
        val themedBitmapField: Field?,
        val themedBitmapSetter: Method?
    )

    companion object {
        private val TARGET_PACKAGE = ScopeKeys.LAUNCHER.packageName
        private const val DEFAULT_FACTORY_CLASS = "com.android.launcher3.icons.BaseIconFactory"
        private const val DEFAULT_OPTIONS_CLASS = $$"com.android.launcher3.icons.BaseIconFactory$IconOptions"
        private const val DEFAULT_BITMAP_INFO_CLASS = "com.android.launcher3.icons.BitmapInfo"
        private const val DEFAULT_EXTENDER_CLASS = $$"com.android.launcher3.icons.BitmapInfo$Extender"
        private const val DEFAULT_ICON_PROVIDER_CLASS = "com.android.launcher3.icons.IconProvider"
        private const val DEFAULT_THEME_CONTROLLER_CLASS = "com.android.launcher3.icons.mono.MonoIconThemeController"
        private const val DEFAULT_THEMED_BITMAP_CLASS = "com.android.launcher3.icons.ThemedBitmap"
        private const val DEFAULT_SOURCE_HINT_CLASS = "com.android.launcher3.icons.SourceHint"

        /** AdaptiveIcon visible-area ratio: 66dp visible in 72dp grid; full bleed scales foreground by its inverse. */
        private const val ADAPTIVE_VISIBLE_RATIO = 66f / 72f

        private const val LIMIT = 40
        private val logCounter = AtomicInteger(0)
    }
}
