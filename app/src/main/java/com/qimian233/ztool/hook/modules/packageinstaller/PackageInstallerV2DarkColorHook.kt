package com.qimian233.ztool.hook.modules.packageinstaller

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.VectorDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.RequiresApi
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Fixes missing night-mode Material 3 colors on AOSP PackageInstaller v2 dialogs. */
// See docs/research/packageinstaller_refactor_audit.md.
@SuppressLint("PrivateApi", "DiscouragedApi")
class PackageInstallerV2DarkColorHook : AppHookModule() {

    private data class V2Host(
        val installContentMethod: Method,
        val alertContextField: Field,
        val alertWindowField: Field,
        val alertTitleViewField: Field?,
        val alertMessageViewField: Field?,
        val alertCustomTitleViewField: Field?,
        val applyFilledButtonMethod: Method,
        val applyOutlinedButtonMethod: Method,
        val applyTextButtonMethod: Method,
        val isMaterialDesignEnabledMethod: Method,
        val materialButtonClass: Class<*>,
        val mbGetBackgroundTintList: Method,
        val mbSetBackgroundTintList: Method,
        val mbGetStrokeColor: Method,
        val mbSetStrokeColor: Method,
        val materialShapeDrawableClass: Class<*>,
        val msdSetFillColor: Method,
        val baseProgressIndicatorClass: Class<*>?,
        val bpiSetIndicatorColor: Method?,
        val bpiSetTrackColor: Method?,
    )

    override fun getModuleName(): String = PreferenceKeys.PACKAGE_INSTALLER_V2_DARK_COLOR_FIX.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.PACKAGE_INSTALLER.packageName)

    override fun isEnabled(): Boolean = runCatching {
        val prefs = remotePreferences
        isV2DarkColorFixActive(
            nativeStyle = prefs.getBoolean(
                PreferenceKeys.PACKAGE_INSTALLER_STYLE_HOOK.name,
                PreferenceKeys.PACKAGE_INSTALLER_STYLE_HOOK.default,
            ),
            darkColorFix = prefs.getBoolean(
                PreferenceKeys.PACKAGE_INSTALLER_V2_DARK_COLOR_FIX.name,
                PreferenceKeys.PACKAGE_INSTALLER_V2_DARK_COLOR_FIX.default,
            ),
        )
    }.getOrDefault(false)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val host = runCatching { resolveHost(param.defaultClassLoader) }
            .onFailure { logger.warn("PackageInstaller v2 classes unavailable: ${it.message}") }
            .getOrNull() ?: return

        hookInstallContent(host)
        hookButtonStyling(host)
        logger.info("Successfully installed PackageInstaller v2 dark color hooks")
    }

    private fun resolveHost(loader: ClassLoader): V2Host {
        val alertControllerClass = loader.loadClass(CLASS_ALERT_CONTROLLER)
        val uiUtilClass = loader.loadClass(CLASS_UI_UTIL)
        val packageUtilClass = loader.loadClass(CLASS_PACKAGE_UTIL)
        val materialButtonClass = loader.loadClass(CLASS_MATERIAL_BUTTON)
        val materialShapeDrawableClass = loader.loadClass(CLASS_MATERIAL_SHAPE_DRAWABLE)
        val baseProgressIndicatorClass = runCatching {
            loader.loadClass(CLASS_BASE_PROGRESS_INDICATOR)
        }.getOrNull()

        return V2Host(
            installContentMethod = findMethod(alertControllerClass, "installContent"),
            alertContextField = findField(alertControllerClass, "mContext"),
            alertWindowField = findField(alertControllerClass, "mWindow"),
            alertTitleViewField = runCatching { findField(alertControllerClass, "mTitleView") }.getOrNull(),
            alertMessageViewField = runCatching { findField(alertControllerClass, "mMessageView") }.getOrNull(),
            alertCustomTitleViewField =
                runCatching { findField(alertControllerClass, "mCustomTitleView") }.getOrNull(),
            applyFilledButtonMethod =
                findMethod(uiUtilClass, "applyFilledButtonStyle", Context::class.java, Button::class.java),
            applyOutlinedButtonMethod =
                findMethod(uiUtilClass, "applyOutlinedButtonStyle", Context::class.java, Button::class.java),
            applyTextButtonMethod =
                findMethod(uiUtilClass, "applyTextButtonStyle", Context::class.java, Button::class.java),
            isMaterialDesignEnabledMethod =
                findMethod(packageUtilClass, "isMaterialDesignEnabled", Context::class.java),
            materialButtonClass = materialButtonClass,
            mbGetBackgroundTintList = findMethod(materialButtonClass, "getBackgroundTintList"),
            mbSetBackgroundTintList =
                findMethod(materialButtonClass, "setBackgroundTintList", ColorStateList::class.java),
            mbGetStrokeColor = findMethod(materialButtonClass, "getStrokeColor"),
            mbSetStrokeColor =
                findMethod(materialButtonClass, "setStrokeColor", ColorStateList::class.java),
            materialShapeDrawableClass = materialShapeDrawableClass,
            msdSetFillColor =
                findMethod(materialShapeDrawableClass, "setFillColor", ColorStateList::class.java),
            baseProgressIndicatorClass = baseProgressIndicatorClass,
            bpiSetIndicatorColor = baseProgressIndicatorClass?.let {
                runCatching { findMethod(it, "setIndicatorColor", IntArray::class.java) }.getOrNull()
            },
            bpiSetTrackColor = baseProgressIndicatorClass?.let {
                runCatching { findMethod(it, "setTrackColor", Int::class.javaPrimitiveType) }.getOrNull()
            },
        )
    }

    private fun hookInstallContent(host: V2Host) {
        hookWithId(host.installContentMethod, "pi_v2_dark_install_content") { chain ->
            val result = chain.proceed()
            if (!isEnabled()) return@hookWithId result
            val controller = chain.thisObject ?: return@hookWithId result
            val context = host.alertContextField.get(controller) as? Context ?: return@hookWithId result
            if (!isNightMode(context) || !isMaterialDesignEnabled(host, context)) {
                return@hookWithId result
            }
            val window = host.alertWindowField.get(controller) as? Window ?: return@hookWithId result
            val hostPalette = resolveHostPalette(context) ?: return@hookWithId result
            val darkPalette = resolveDarkPalette(context)

            applyDialogDarkColors(host, controller, context, window, hostPalette, darkPalette)
            result
        }
    }

    private fun hookButtonStyling(host: V2Host) {
        hookButtonStyle(host, host.applyFilledButtonMethod, "pi_v2_dark_filled_button") {
            button, hostPalette, darkPalette ->
            if (hostPalette.primary != darkPalette.primary) {
                host.mbSetBackgroundTintList.invoke(button, ColorStateList.valueOf(darkPalette.primary))
            }
            if (hostPalette.onPrimary != darkPalette.onPrimary) {
                button.setTextColor(ColorStateList.valueOf(darkPalette.onPrimary))
            }
        }

        hookButtonStyle(host, host.applyOutlinedButtonMethod, "pi_v2_dark_outlined_button") {
            button, hostPalette, darkPalette ->
            if (hostPalette.primary != darkPalette.primary) {
                button.setTextColor(ColorStateList.valueOf(darkPalette.primary))
            }
            if (hostPalette.outlineVariant != darkPalette.outlineVariant) {
                host.mbSetStrokeColor.invoke(button, ColorStateList.valueOf(darkPalette.outlineVariant))
            }
        }

        hookButtonStyle(host, host.applyTextButtonMethod, "pi_v2_dark_text_button") {
            button, hostPalette, darkPalette ->
            if (hostPalette.primary != darkPalette.primary) {
                button.setTextColor(ColorStateList.valueOf(darkPalette.primary))
            }
        }
    }

    private fun hookButtonStyle(
        host: V2Host,
        method: Method,
        id: String,
        applyDarkColors: (Button, V2ColorPalette, V2ColorPalette) -> Unit,
    ) {
        hookWithId(method, id) { chain ->
            val result = chain.proceed()
            if (!isEnabled()) return@hookWithId result
            val context = chain.args.getOrNull(0) as? Context ?: return@hookWithId result
            val button = chain.args.getOrNull(1) as? Button ?: return@hookWithId result
            if (!isNightMode(context) ||
                !isMaterialDesignEnabled(host, context) ||
                !host.materialButtonClass.isInstance(button)
            ) {
                return@hookWithId result
            }
            val hostPalette = resolveHostPalette(context) ?: return@hookWithId result
            val darkPalette = resolveDarkPalette(context)
            applyDarkColors(button, hostPalette, darkPalette)
            result
        }
    }

    private fun applyDialogDarkColors(
        host: V2Host,
        controller: Any,
        context: Context,
        window: Window,
        hostPalette: V2ColorPalette,
        darkPalette: V2ColorPalette,
    ) {
        val decorView = window.decorView
        val innerBg = (decorView.background as? InsetDrawable)?.drawable ?: decorView.background
        if (innerBg != null &&
            host.materialShapeDrawableClass.isInstance(innerBg) &&
            hostPalette.surfaceContainerHigh != darkPalette.surfaceContainerHigh
        ) {
            host.msdSetFillColor.invoke(
                innerBg,
                ColorStateList.valueOf(darkPalette.surfaceContainerHigh),
            )
        }

        // Preserve decorView.background (the rounded MaterialShapeDrawable); only clear
        // descendant views that inherited the theme's light android:background ColorDrawable.
        if (decorView is ViewGroup) {
            for (i in 0 until decorView.childCount) {
                remapSubtree(host, decorView.getChildAt(i), hostPalette, darkPalette)
            }
        }

        if (hostPalette.onSurface != darkPalette.onSurface) {
            (host.alertTitleViewField?.get(controller) as? TextView)
                ?.setTextColor(darkPalette.onSurface)
        }
        if (hostPalette.onSurfaceVariant != darkPalette.onSurfaceVariant) {
            (host.alertMessageViewField?.get(controller) as? TextView)
                ?.setTextColor(darkPalette.onSurfaceVariant)
        }

        val pkg = ScopeKeys.PACKAGE_INSTALLER.packageName
        val moreDetailsId = context.resources.getIdentifier("more_details_clickable_layout", "id", pkg)
        if (moreDetailsId != 0 && hostPalette.onSurfaceVariant != darkPalette.onSurfaceVariant) {
            decorView.findViewById<View>(moreDetailsId)?.let { layout ->
                tintDescendantImages(layout, darkPalette.onSurfaceVariant)
            }
        }

        val appIconId = context.resources.getIdentifier("app_icon", "id", pkg)
        val customTitle = host.alertCustomTitleViewField?.get(controller) as? View
        if (customTitle != null && hostPalette.primary != darkPalette.primary) {
            tintVectorImagesExcluding(customTitle, appIconId, darkPalette.primary)
        }
    }

    private fun remapSubtree(
        host: V2Host,
        view: View,
        hostPalette: V2ColorPalette,
        darkPalette: V2ColorPalette,
    ) {
        val bg = view.background
        if (bg is ColorDrawable &&
            shouldClearDialogChildBackground(
                bg.color,
                hostPalette.surfaceContainerHigh,
                darkPalette.surfaceContainerHigh,
            )
        ) {
            view.background = null
        }

        if (host.materialButtonClass.isInstance(view) && view is Button) {
            remapMaterialButton(host, view, hostPalette, darkPalette)
        } else if (view is TextView) {
            val currentColor = view.currentTextColor
            val remapped = remapV2TextColor(currentColor, hostPalette, darkPalette)
            if (remapped != currentColor) {
                view.setTextColor(remapped)
            }
        }

        if (host.baseProgressIndicatorClass?.isInstance(view) == true) {
            if (hostPalette.primary != darkPalette.primary) {
                host.bpiSetIndicatorColor?.invoke(view, intArrayOf(darkPalette.primary))
            }
            if (hostPalette.outlineVariant != darkPalette.outlineVariant) {
                host.bpiSetTrackColor?.invoke(view, darkPalette.outlineVariant)
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                remapSubtree(host, view.getChildAt(i), hostPalette, darkPalette)
            }
        }
    }

    private fun remapMaterialButton(
        host: V2Host,
        button: Button,
        hostPalette: V2ColorPalette,
        darkPalette: V2ColorPalette,
    ) {
        val bgTint = host.mbGetBackgroundTintList.invoke(button) as? ColorStateList
        if (bgTint != null &&
            bgTint.defaultColor == hostPalette.primary &&
            hostPalette.primary != darkPalette.primary
        ) {
            host.mbSetBackgroundTintList.invoke(button, ColorStateList.valueOf(darkPalette.primary))
        }

        val strokeColor = host.mbGetStrokeColor.invoke(button) as? ColorStateList
        if (strokeColor != null &&
            strokeColor.defaultColor == hostPalette.outlineVariant &&
            hostPalette.outlineVariant != darkPalette.outlineVariant
        ) {
            host.mbSetStrokeColor.invoke(button, ColorStateList.valueOf(darkPalette.outlineVariant))
        }

        val currentText = button.currentTextColor
        val remappedText = remapV2ButtonTextColor(currentText, hostPalette, darkPalette)
        if (remappedText != currentText) {
            button.setTextColor(ColorStateList.valueOf(remappedText))
        }
    }

    private fun tintDescendantImages(view: View, color: Int) {
        if (view is ImageView) {
            view.imageTintList = ColorStateList.valueOf(color)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                tintDescendantImages(view.getChildAt(i), color)
            }
        }
    }

    private fun tintVectorImagesExcluding(view: View, excludedId: Int, color: Int) {
        if (view is ImageView && view.id != excludedId && view.drawable is VectorDrawable) {
            view.imageTintList = ColorStateList.valueOf(color)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                tintVectorImagesExcluding(view.getChildAt(i), excludedId, color)
            }
        }
    }

    private fun isNightMode(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun isMaterialDesignEnabled(host: V2Host, context: Context): Boolean = runCatching {
        host.isMaterialDesignEnabledMethod.invoke(null, context) as? Boolean ?: false
    }.getOrDefault(false)

    private fun resolveHostPalette(context: Context): V2ColorPalette? {
        val res = context.resources
        val pkg = ScopeKeys.PACKAGE_INSTALLER.packageName
        val surfaceContainerHighId = res.getIdentifier("surfaceContainerHighColor", "color", pkg)
        if (surfaceContainerHighId == 0) return null

        fun readColor(name: String, fallback: Int): Int {
            val id = res.getIdentifier(name, "color", pkg)
            return if (id != 0) runCatching { context.getColor(id) }.getOrDefault(fallback) else fallback
        }

        return V2ColorPalette(
            surfaceContainerHigh = runCatching { context.getColor(surfaceContainerHighId) }
                .getOrElse { return null },
            primary = readColor("primaryColor", 0),
            onPrimary = readColor("onPrimaryColor", 0),
            onSurface = readColor("onSurfaceColor", 0),
            onSurfaceVariant = readColor("onSurfaceVariantColor", 0),
            outlineVariant = readColor("outlineVariantColor", 0),
        )
    }

    private fun resolveDarkPalette(context: Context): V2ColorPalette =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolveSystemDarkPalette(context)
        } else {
            STATIC_DARK_PALETTE
        }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @SuppressLint("InlinedApi")
    private fun resolveSystemDarkPalette(context: Context): V2ColorPalette {
        fun readSys(resId: Int, fallback: Int): Int =
            runCatching { context.getColor(resId) }.getOrDefault(fallback)

        return V2ColorPalette(
            surfaceContainerHigh = readSys(
                android.R.color.system_surface_container_high_dark,
                FALLBACK_SURFACE_CONTAINER_HIGH_DARK,
            ),
            primary = readSys(android.R.color.system_primary_dark, FALLBACK_PRIMARY_DARK),
            onPrimary = readSys(android.R.color.system_on_primary_dark, FALLBACK_ON_PRIMARY_DARK),
            onSurface = readSys(android.R.color.system_on_surface_dark, FALLBACK_ON_SURFACE_DARK),
            onSurfaceVariant = readSys(
                android.R.color.system_on_surface_variant_dark,
                FALLBACK_ON_SURFACE_VARIANT_DARK,
            ),
            outlineVariant = readSys(
                android.R.color.system_outline_variant_dark,
                FALLBACK_OUTLINE_VARIANT_DARK,
            ),
        )
    }

    private companion object {
        const val CLASS_ALERT_CONTROLLER = "androidx.appcompat.app.AlertController"
        const val CLASS_UI_UTIL = "com.android.packageinstaller.v2.ui.UiUtil"
        const val CLASS_PACKAGE_UTIL = "com.android.packageinstaller.v2.model.PackageUtil"
        const val CLASS_MATERIAL_BUTTON = "com.google.android.material.button.MaterialButton"
        const val CLASS_MATERIAL_SHAPE_DRAWABLE =
            "com.google.android.material.shape.MaterialShapeDrawable"
        const val CLASS_BASE_PROGRESS_INDICATOR =
            "com.google.android.material.progressindicator.BaseProgressIndicator"

        const val FALLBACK_SURFACE_CONTAINER_HIGH_DARK = 0xFF2B2930.toInt()
        const val FALLBACK_PRIMARY_DARK = 0xFFD0BCFF.toInt()
        const val FALLBACK_ON_PRIMARY_DARK = 0xFF381E72.toInt()
        const val FALLBACK_ON_SURFACE_DARK = 0xFFE6E0E9.toInt()
        const val FALLBACK_ON_SURFACE_VARIANT_DARK = 0xFFCAC4D0.toInt()
        const val FALLBACK_OUTLINE_VARIANT_DARK = 0xFF49454F.toInt()

        val STATIC_DARK_PALETTE = V2ColorPalette(
            surfaceContainerHigh = FALLBACK_SURFACE_CONTAINER_HIGH_DARK,
            primary = FALLBACK_PRIMARY_DARK,
            onPrimary = FALLBACK_ON_PRIMARY_DARK,
            onSurface = FALLBACK_ON_SURFACE_DARK,
            onSurfaceVariant = FALLBACK_ON_SURFACE_VARIANT_DARK,
            outlineVariant = FALLBACK_OUTLINE_VARIANT_DARK,
        )
    }
}
