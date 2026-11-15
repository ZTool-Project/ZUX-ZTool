package com.qimian233.ztool.dexindex.base

/** Shared host class paths in firmware preference order. */
object SystemUiDexTargets {
    val CHARGING_CONTROLLERS = listOf(
        "com.android.keyguard.charge.ChargingAnimationController",
        "com.android.keyguard.lockscreen.charge.ChargingAnimationController",
    )
    val CHARGING_ACTIVITIES = listOf(
        "com.android.keyguard.charge.ChargingActivity",
        "com.android.keyguard.lockscreen.charge.ChargingActivity",
    )
    val CHARGING_VIDEO_VIEWS = listOf(
        "com.android.keyguard.charge.ChargingVideoView",
        "com.android.keyguard.lockscreen.charge.ChargingVideoView",
    )
    val NETWORK_SPEED_VIEWS = listOf(
        "com.android.systemui.statusbar.view.NetworkSpeedView",
        "com.android.systemui.zui.NetworkSpeedView",
    )
    const val TOGGLE_SLIDER_VIEW = "com.android.systemui.settings.ToggleSliderView"

    fun loadFirstClass(loader: ClassLoader, names: List<String>): Class<*> {
        for (name in names) {
            try {
                return loader.loadClass(name)
            } catch (_: ClassNotFoundException) {
                continue
            }
        }
        throw ClassNotFoundException(names.joinToString())
    }
}
