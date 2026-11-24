package com.qimian233.ztool.hook.modules.systemui.qs

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/** Swaps the brightness and media-volume sliders in SystemUI's ToggleSliderView. */
@SuppressLint("PrivateApi")
class SwapSlidersHook : AppHookModule() {

    override fun getModuleName(): String = PreferenceKeys.SWAP_SLIDERS_POSITION.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_UI.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val ctor = param.defaultClassLoader
            .loadClass(TOGGLE_SLIDER_VIEW_CLASS)
            .getDeclaredConstructor(
                Context::class.java,
                AttributeSet::class.java,
                Int::class.javaPrimitiveType
            )
        hookWithId(ctor, "swap_sliders_ctor", priority = XposedInterface.PRIORITY_LOWEST) { chain ->
            chain.proceed()
            swapSliderRoots(chain.thisObject)
            null
        }
    }

    private fun swapSliderRoots(sliderView: Any) {
        try {
            val clazz = sliderView.javaClass
            val fromType = findField(clazz, "mFromType").getInt(sliderView)
            if (fromType != FROM_TYPE_HORIZONTAL && fromType != FROM_TYPE_VERTICAL) {
                return
            }

            val allSliderRoot = findField(clazz, "mAllSliderRoot").get(sliderView) as? LinearLayout ?: return
            val volumeRoot = findField(clazz, "mVolumeSliderRoot").get(sliderView) as? View ?: return
            val brightnessRoot = findField(clazz, "mBrightnessSliderRoot").get(sliderView) as? View ?: return
            val divider = findField(clazz, "mToggleDivider").get(sliderView) as? View

            val volumeIndex = allSliderRoot.indexOfChild(volumeRoot)
            val brightnessIndex = allSliderRoot.indexOfChild(brightnessRoot)
            if (volumeIndex < 0 || brightnessIndex < 0 || volumeIndex >= brightnessIndex) {
                return
            }

            val brightnessParams = brightnessRoot.layoutParams
            val volumeParams = volumeRoot.layoutParams
            val dividerIndex = divider?.let { allSliderRoot.indexOfChild(it) } ?: -1
            val hasDivider = divider != null && dividerIndex >= 0
            val dividerParams = if (hasDivider) divider.layoutParams else null

            allSliderRoot.removeView(brightnessRoot)
            if (hasDivider) {
                allSliderRoot.removeView(divider)
            }
            allSliderRoot.removeView(volumeRoot)

            var insertAt = volumeIndex.coerceAtMost(allSliderRoot.childCount)
            allSliderRoot.addView(brightnessRoot, insertAt++, brightnessParams)
            if (hasDivider) {
                allSliderRoot.addView(divider, insertAt++, dividerParams)
            }
            allSliderRoot.addView(volumeRoot, insertAt, volumeParams)
        } catch (t: Throwable) {
            logger.error("Failed to swap control-center sliders", t)
        }
    }

    companion object {
        private const val TOGGLE_SLIDER_VIEW_CLASS = "com.android.systemui.settings.ToggleSliderView"
        private const val FROM_TYPE_HORIZONTAL = 2
        private const val FROM_TYPE_VERTICAL = 3
    }
}
