package com.qimian233.ztool.hook.modules.launcher.dockbar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.google.gson.JsonObject
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.dexindex.base.DexIndexConstants
import com.qimian233.ztool.hook.base.AppHookModule
import com.qimian233.ztool.hook.base.DexIndexStore
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Removes the dock background (plate, border and split line) while keeping the dock icons.
 *
 * The background is two layers - ZuiHotseat paints/views and a window blur region - so two hook
 * points are used. Field and class names come from the offline index plus runtime predicates.
 */
@SuppressLint("PrivateApi")
class DisableDockBackground : AppHookModule() {

    override fun getModuleName(): String = PreferenceKeys.DISABLE_DOCK_BACKGROUND.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.LAUNCHER.packageName)

    @Throws(Throwable::class)
    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        val module = DexIndexStore.lookup(xposed, ScopeKeys.LAUNCHER.packageName)
            ?.getAsJsonObject(DexIndexConstants.JSON_MODULES)
            ?.getAsJsonObject(DexIndexConstants.ModuleKeys.DISABLE_DOCK_BACKGROUND)

        val paintHints = parseDockPaintFieldHints(module)
        val splitLineViewFieldName = readString(module, DexIndexConstants.Keys.DOCK_SPLIT_LINE_VIEW_FIELD)
        val blurProviderClassName = readString(module, DexIndexConstants.Keys.DOCK_BLUR_PROVIDER_CLASS)
        val addBlurMethodName = readString(module, DexIndexConstants.Keys.DOCK_ADD_BLUR_METHOD)

        hookBackgroundPaints(classLoader, paintHints, splitLineViewFieldName)
        hookBlurProvider(classLoader, blurProviderClassName, addBlurMethodName)
    }

    /** Layer 1: zero the dock's paints, shadow shader, and split-line view once constructed. */
    private fun hookBackgroundPaints(
        classLoader: ClassLoader,
        hints: Set<String>,
        splitLineViewFieldName: String?
    ) {
        val zuiHotseat = try {
            classLoader.loadClass(ZUI_HOTSEAT_CLASS)
        } catch (t: Throwable) {
            logger.error("DisableDockBackground: ZuiHotseat not found", t)
            return
        }
        val constructor = try {
            zuiHotseat.getDeclaredConstructor(
                Context::class.java,
                AttributeSet::class.java,
                Int::class.javaPrimitiveType
            )
        } catch (t: Throwable) {
            logger.error("DisableDockBackground: ZuiHotseat constructor not found", t)
            return
        }
        val paintFields = zuiHotseat.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && Paint::class.java.isAssignableFrom(it.type) }
            .onEach { it.isAccessible = true }
        val splitLineViewField = splitLineViewFieldName?.let { name ->
            runCatching { findField(zuiHotseat, name) }.getOrNull()
        }

        hookWithId(constructor, "dock_background_paints") { chain ->
            chain.proceed()
            val instance = chain.thisObject ?: return@hookWithId null
            try {
                clearBackgroundPaints(instance, paintFields, hints, splitLineViewField)
            } catch (t: Throwable) {
                logger.error("DisableDockBackground: paint resolution failed", t)
            }
            null
        }
        logger.debug("DisableDockBackground: paint hook installed")
    }

    /** Layer 2: never register the hotseat as a launcher blur-area provider. */
    private fun hookBlurProvider(
        classLoader: ClassLoader,
        blurProviderClassName: String?,
        addBlurMethodName: String?
    ) {
        val targetProviderName = blurProviderClassName ?: BLUR_AREA_PROVIDER_CLASS
        val add = try {
            val launcher = classLoader.loadClass(LAUNCHER_CLASS)
            val provider = classLoader.loadClass(targetProviderName)
            val hotseat = classLoader.loadClass(HOTSEAT_CLASS)
            resolveAddBlurAreaProvider(launcher, provider, addBlurMethodName) to hotseat
        } catch (t: Throwable) {
            logger.error("DisableDockBackground: addBlurAreaProvider($targetProviderName) not resolved", t)
            return
        }
        hookWithId(add.first, "dock_blur_provider") { chain ->
            if (add.second.isInstance(chain.args[0])) null else chain.proceed()
        }
        logger.debug("DisableDockBackground: blur provider hook installed ($targetProviderName)")
    }

    private fun clearBackgroundPaints(
        instance: Any,
        paintFields: List<Field>,
        hints: Set<String>,
        splitLineViewField: Field?
    ) {
        val paints = paintFields.mapNotNull { field ->
            (field.get(instance) as? Paint)?.let { field to it }
        }
        val splitLineView = splitLineViewField?.let {
            runCatching { it.get(instance) as? View }.getOrNull()
        }

        if (splitLineView != null) {
            splitLineView.alpha = 0f
            splitLineView.setBackgroundColor(Color.TRANSPARENT)
            logger.debug("DisableDockBackground: cleared split-line View ${splitLineViewField.name}")
        }

        if (paints.isEmpty()) {
            logger.warn("DisableDockBackground: no Paint fields on ${instance.javaClass.name}")
            return
        }

        val border = paints.singleOrNull { (_, p) ->
            p.style == Paint.Style.STROKE && p.strokeWidth > 0f
        }
        val shadow = paints.singleOrNull { (_, p) ->
            p.style == Paint.Style.STROKE && p.strokeWidth == 0f
        }
        val splitLinePaint = paints.singleOrNull { (_, p) ->
            p.style == Paint.Style.FILL && p.strokeWidth > 0f
        }
        val fills = paints.filter { (_, p) ->
            p.style == Paint.Style.FILL && p.strokeWidth == 0f
        }
        val fill = fills.singleOrNull { (f, _) -> f.name in hints } ?: fills.singleOrNull()

        val hasSplitLine = splitLinePaint != null || splitLineView != null
        val resolved = if (fill != null && border != null && hasSplitLine) {
            listOfNotNull(fill, border, splitLinePaint, shadow).distinctBy { it.first.name }
        } else {
            null
        }

        if (resolved != null) {
            logger.debug(
                "DisableDockBackground: resolved fill=${fill?.first?.name} " +
                    "border=${border?.first?.name} splitLine=${splitLinePaint?.first?.name ?: splitLineViewField?.name} " +
                    "shadow=${shadow?.first?.name}"
            )
        } else {
            logger.warn(
                "DisableDockBackground: ambiguous paint set, clearing all ${paints.size} fields " +
                    "(hints=$hints border=${border?.first?.name} splitLine=${splitLinePaint?.first?.name} " +
                    "splitLineView=${splitLineViewField?.name} shadow=${shadow?.first?.name} " +
                    "fill=${fill?.first?.name} fillCandidates=${fills.map { it.first.name }})"
            )
            for ((field, paint) in paints) {
                logger.warn("DisableDockBackground: candidate ${describe(field, paint, hints)}")
            }
        }

        for ((field, paint) in (resolved ?: paints)) {
            paint.alpha = 0
            // 16-pass shadow resets color/alpha every dispatchDraw; a transparent Shader keeps output 0x00000000.
            if (paint.style == Paint.Style.STROKE && paint.strokeWidth == 0f) {
                paint.shader = LinearGradient(
                    0f,
                    0f,
                    1f,
                    1f,
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
            }
            logger.debug("DisableDockBackground: cleared ${field.name}")
        }
    }

    private fun describe(field: Field, paint: Paint, hints: Set<String>): String =
        "${field.name} style=${paint.style} strokeWidth=${paint.strokeWidth} " +
            "color=#${Integer.toHexString(paint.color)} hinted=${field.name in hints}"

    @Throws(NoSuchMethodException::class)
    private fun resolveAddBlurAreaProvider(
        launcher: Class<*>,
        provider: Class<*>,
        indexedMethodName: String?
    ): Method {
        val names = listOfNotNull(indexedMethodName, "addBlurAreaProvider").distinct()
        for (name in names) {
            try {
                return findMethod(launcher, name, provider)
            } catch (_: NoSuchMethodException) {
                // try next candidate
            }
        }
        return launcher.declaredMethods.singleOrNull { candidate ->
            !Modifier.isStatic(candidate.modifiers) &&
                candidate.returnType == Void.TYPE &&
                candidate.parameterTypes.size == 1 &&
                candidate.parameterTypes[0] == provider
        }?.apply { isAccessible = true }
            ?: throw NoSuchMethodException("addBlurAreaProvider(${provider.name}) in ${launcher.name}")
    }

    private fun parseDockPaintFieldHints(module: JsonObject?): Set<String> {
        val raw = readString(module, DexIndexConstants.Keys.DOCK_PAINT_FIELDS)
        if (raw == null) {
            logger.warn(
                "DisableDockBackground: offline index has no " +
                    "'${DexIndexConstants.Keys.DOCK_PAINT_FIELDS}' entry (missing or stale); " +
                    "structural resolution only"
            )
            return emptySet()
        }
        val hints = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        logger.debug("DisableDockBackground: index hints = $hints (raw='$raw')")
        return hints
    }

    private fun readString(module: JsonObject?, key: String): String? =
        module?.get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }

    private companion object {
        const val ZUI_HOTSEAT_CLASS = "com.zui.launcher.uiextend.ZuiHotseat"
        const val HOTSEAT_CLASS = "com.android.launcher3.Hotseat"
        const val LAUNCHER_CLASS = "com.android.launcher3.Launcher"
        const val BLUR_AREA_PROVIDER_CLASS = "com.zui.launcher.blur.LauncherBlurAreaProvider"
    }
}
