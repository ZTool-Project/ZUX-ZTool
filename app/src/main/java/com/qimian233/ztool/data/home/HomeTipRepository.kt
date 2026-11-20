package com.qimian233.ztool.data.home

import androidx.annotation.StringRes
import com.qimian233.ztool.R
import com.qimian233.ztool.data.hotreload.HotReloadNotice
import com.qimian233.ztool.data.hotreload.HotReloadNoticeBus
import com.qimian233.ztool.data.hotreload.HotReloadTrigger
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

/**
 * Tip shown by the home tip card.
 *
 * @param hotReloadWarning Selects the warning styling instead of the ordinary tip styling.
 */
data class HomeTip(
    @param:StringRes val messageRes: Int,
    val hotReloadWarning: Boolean
)

/** Selects the tip shown on the home card for a launch or an in-app hot reload. */
class HomeTipRepository(
    private val random: Random = Random.Default
) {

    /** In-app hot reload notices observed by the home ViewModel. */
    val pendingHotReload: StateFlow<HotReloadNotice?> = HotReloadNoticeBus.pending

    /** Returns the tip for this app launch, or null when no tip is shown. */
    fun resolveLaunchTip(): HomeTip? {
        if (HotReloadNoticeBus.pending.value?.trigger == HotReloadTrigger.MANUAL) {
            return takeHotReloadTip()
        }
        return randomTip()
    }

    /** Consumes any pending hot reload notice and returns the hot reload warning tip. */
    fun takeHotReloadTip(): HomeTip {
        HotReloadNoticeBus.consume()
        return hotReloadWarningTip()
    }

    private fun hotReloadWarningTip(): HomeTip = HomeTip(
        messageRes = R.string.page_home_tip_hot_reload_warning,
        hotReloadWarning = true
    )

    private fun randomTip(): HomeTip? {
        if (random.nextInt(TIP_ROLL_SPACE) >= TIP_SHOW_PERCENT) return null
        return HomeTip(
            messageRes = TIP_MESSAGES.random(random),
            hotReloadWarning = false
        )
    }

    private companion object {
        const val TIP_SHOW_PERCENT = 5
        const val TIP_ROLL_SPACE = 100

        val TIP_MESSAGES = listOf(
            R.string.page_home_tip_extreme_refresh_rate,
            R.string.page_home_tip_misans_font,
            R.string.page_home_tip_feature_count_power,
            R.string.page_home_tip_boundary_values,
            R.string.page_home_tip_notification_permission,
            R.string.page_home_tip_set_tag,
            R.string.page_home_tip_report_issue,
            R.string.page_home_tip_freeform_priority,
            R.string.page_home_tip_nightly_build,
            R.string.page_home_tip_debug_leak_canary,
            R.string.page_home_tip_release_performance,
            R.string.page_home_tip_pull_request,
            R.string.page_home_tip_crash_log,
            R.string.page_home_tip_not_zuxceiler
        )
    }
}
