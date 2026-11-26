package com.qimian233.ztool.data.systemui

import android.content.Context
import com.qimian233.ztool.data.features.FeatureDestination
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.utils.ModulePreferencesUtils
import com.qimian233.ztool.utils.ScopeUtils

class SystemUiMiscSettingsRepository(context: Context) {
    private val prefsUtils = ModulePreferencesUtils(context)

    fun loadState(): SystemUiMiscSettingsUiState {
        return SystemUiMiscSettingsUiState(
            guestModeController = prefsUtils.loadBooleanSetting(KEY_GUEST_MODE_CONTROLLER, false),
            bypassFaceAuthTimeout = prefsUtils.loadBooleanSetting(KEY_BYPASS_FACE_AUTH_TIMEOUT, false),
            aospScrollCapture = prefsUtils.loadBooleanSetting(KEY_FORCE_LONG_SCREENSHOT_AOSP, false),
            shadeReboundFix = prefsUtils.loadBooleanSetting(KEY_SHADE_REBOUND_FIX, false),
            freeformFullscreenThreshold = prefsUtils.loadBooleanSetting(
                KEY_LAUNCHER_FREEFORM_FULLSCREEN_THRESHOLD,
                false
            ),
            freeformFullscreenWidthPercent = normalizeFreeformFullscreenPercent(
                prefsUtils.loadIntegerSetting(
                    KEY_LAUNCHER_FREEFORM_FULLSCREEN_WIDTH_PERCENT,
                    FREEFORM_FULLSCREEN_PERCENT_DEFAULT
                )
            ),
            freeformFullscreenHeightPercent = normalizeFreeformFullscreenPercent(
                prefsUtils.loadIntegerSetting(
                    KEY_LAUNCHER_FREEFORM_FULLSCREEN_HEIGHT_PERCENT,
                    FREEFORM_FULLSCREEN_PERCENT_DEFAULT
                )
            ),
            freeformBottomEdgeResize = prefsUtils.loadBooleanSetting(
                KEY_SYSTEM_UI_FREEFORM_BOTTOM_EDGE_RESIZE,
                false
            ),
            cornerHandleAutoHide = prefsUtils.loadBooleanSetting(
                KEY_SYSTEM_UI_CORNER_HANDLE_AUTO_HIDE,
                true
            ),
            freeformNoForehead = prefsUtils.loadBooleanSetting(
                KEY_SYSTEM_UI_FREEFORM_NO_FOREHEAD,
                false
            ),
            freeformNonlinearAnim = prefsUtils.loadBooleanSetting(
                KEY_SYSTEM_UI_FREEFORM_NONLINEAR_ANIM,
                false
            ),
            freeformNonlinearAnimOpen = prefsUtils.loadBooleanSetting(
                KEY_SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_OPEN,
                PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_OPEN.default
            ),
            freeformNonlinearAnimClose = prefsUtils.loadBooleanSetting(
                KEY_SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_CLOSE,
                PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_CLOSE.default
            ),
            freeformNonlinearAnimBoundsChange = prefsUtils.loadBooleanSetting(
                KEY_SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_BOUNDS_CHANGE,
                PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_BOUNDS_CHANGE.default
            ),
            freeformNonlinearAnimEdgeSnapBack = prefsUtils.loadBooleanSetting(
                KEY_SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_EDGE_SNAP_BACK,
                PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_EDGE_SNAP_BACK.default
            ),
            freeformNonlinearAnimEdgeDock = prefsUtils.loadBooleanSetting(
                KEY_SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_EDGE_DOCK,
                PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_EDGE_DOCK.default
            ),
            freeformNonlinearAnimBubbleRestore = prefsUtils.loadBooleanSetting(
                KEY_SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_BUBBLE_RESTORE,
                PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_BUBBLE_RESTORE.default
            ),
            freeformHandleMenuAnim = prefsUtils.loadBooleanSetting(
                KEY_SYSTEM_UI_FREEFORM_HANDLE_MENU_ANIM,
                false
            ),
        )
    }

    fun saveGuestModeController(enabled: Boolean) = prefsUtils.saveBooleanSetting(KEY_GUEST_MODE_CONTROLLER, enabled)
    fun saveBypassFaceAuthTimeout(enabled: Boolean) = prefsUtils.saveBooleanSetting(KEY_BYPASS_FACE_AUTH_TIMEOUT, enabled)
    fun saveAospScrollCapture(enabled: Boolean) = prefsUtils.saveBooleanSetting(KEY_FORCE_LONG_SCREENSHOT_AOSP, enabled)
    fun saveShadeReboundFix(enabled: Boolean) = prefsUtils.saveBooleanSetting(KEY_SHADE_REBOUND_FIX, enabled)

    fun saveFreeformFullscreenThreshold(enabled: Boolean) {
        prefsUtils.saveBooleanSetting(KEY_LAUNCHER_FREEFORM_FULLSCREEN_THRESHOLD, enabled)
    }

    fun saveFreeformFullscreenWidthPercent(percent: Int) {
        prefsUtils.saveIntegerSetting(
            KEY_LAUNCHER_FREEFORM_FULLSCREEN_WIDTH_PERCENT,
            normalizeFreeformFullscreenPercent(percent)
        )
    }

    fun saveFreeformFullscreenHeightPercent(percent: Int) {
        prefsUtils.saveIntegerSetting(
            KEY_LAUNCHER_FREEFORM_FULLSCREEN_HEIGHT_PERCENT,
            normalizeFreeformFullscreenPercent(percent)
        )
    }

    fun saveFreeformBottomEdgeResize(enabled: Boolean) {
        prefsUtils.saveBooleanSetting(KEY_SYSTEM_UI_FREEFORM_BOTTOM_EDGE_RESIZE, enabled)
    }

    fun saveCornerHandleAutoHide(enabled: Boolean) {
        prefsUtils.saveBooleanSetting(KEY_SYSTEM_UI_CORNER_HANDLE_AUTO_HIDE, enabled)
    }

    fun saveFreeformNoForehead(enabled: Boolean) {
        prefsUtils.saveBooleanSetting(KEY_SYSTEM_UI_FREEFORM_NO_FOREHEAD, enabled)
    }

    fun saveFreeformNonlinearAnim(enabled: Boolean) {
        prefsUtils.saveBooleanSetting(KEY_SYSTEM_UI_FREEFORM_NONLINEAR_ANIM, enabled)
    }

    fun saveFreeformNonlinearAnimOpen(enabled: Boolean) {
        prefsUtils.saveBooleanSetting(KEY_SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_OPEN, enabled)
    }

    fun saveFreeformNonlinearAnimClose(enabled: Boolean) {
        prefsUtils.saveBooleanSetting(KEY_SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_CLOSE, enabled)
    }

    fun saveFreeformNonlinearAnimBoundsChange(enabled: Boolean) {
        prefsUtils.saveBooleanSetting(KEY_SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_BOUNDS_CHANGE, enabled)
    }

    fun saveFreeformNonlinearAnimEdgeSnapBack(enabled: Boolean) {
        prefsUtils.saveBooleanSetting(KEY_SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_EDGE_SNAP_BACK, enabled)
    }

    fun saveFreeformNonlinearAnimEdgeDock(enabled: Boolean) {
        prefsUtils.saveBooleanSetting(KEY_SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_EDGE_DOCK, enabled)
    }

    fun saveFreeformNonlinearAnimBubbleRestore(enabled: Boolean) {
        prefsUtils.saveBooleanSetting(KEY_SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_BUBBLE_RESTORE, enabled)
    }

    fun saveFreeformHandleMenuAnim(enabled: Boolean) {
        prefsUtils.saveBooleanSetting(KEY_SYSTEM_UI_FREEFORM_HANDLE_MENU_ANIM, enabled)
    }

    fun forceStopScope(): ShellActionResult {
        val scopes = ScopeUtils.getScopes(FeatureDestination.SystemUi)
        return when (val result = ScopeUtils.restartScope(scopes)) {
            is ScopeUtils.RestartResult.Success -> ShellActionResult(success = true, error = "", exitCode = 0)
            is ScopeUtils.RestartResult.PartialSuccess -> ShellActionResult(success = false, error = "Partial failure: ${result.failed.joinToString()}", exitCode = -1)
            is ScopeUtils.RestartResult.Failure -> ShellActionResult(success = false, error = result.message, exitCode = -1)
        }
    }

    companion object {
        private val KEY_GUEST_MODE_CONTROLLER = PreferenceKeys.GUEST_MODE_CONTROLLER.name
        private val KEY_BYPASS_FACE_AUTH_TIMEOUT = PreferenceKeys.BYPASS_FACE_AUTH_TIMEOUT.name
        private val KEY_FORCE_LONG_SCREENSHOT_AOSP = PreferenceKeys.FORCE_LONG_SCREENSHOT_AOSP.name
        private val KEY_SHADE_REBOUND_FIX = PreferenceKeys.SHADE_REBOUND_FIX.name
        private val KEY_LAUNCHER_FREEFORM_FULLSCREEN_THRESHOLD =
            PreferenceKeys.LAUNCHER_FREEFORM_FULLSCREEN_THRESHOLD.name
        private val KEY_LAUNCHER_FREEFORM_FULLSCREEN_WIDTH_PERCENT =
            PreferenceKeys.LAUNCHER_FREEFORM_FULLSCREEN_WIDTH_PERCENT.name
        private val KEY_LAUNCHER_FREEFORM_FULLSCREEN_HEIGHT_PERCENT =
            PreferenceKeys.LAUNCHER_FREEFORM_FULLSCREEN_HEIGHT_PERCENT.name
        private val KEY_SYSTEM_UI_FREEFORM_BOTTOM_EDGE_RESIZE =
            PreferenceKeys.SYSTEM_UI_FREEFORM_BOTTOM_EDGE_RESIZE.name
        private val KEY_SYSTEM_UI_CORNER_HANDLE_AUTO_HIDE =
            PreferenceKeys.SYSTEM_UI_CORNER_HANDLE_AUTO_HIDE.name
        private val KEY_SYSTEM_UI_FREEFORM_NO_FOREHEAD =
            PreferenceKeys.SYSTEM_UI_FREEFORM_NO_FOREHEAD.name
        private val KEY_SYSTEM_UI_FREEFORM_NONLINEAR_ANIM =
            PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM.name
        private val KEY_SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_OPEN =
            PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_OPEN.name
        private val KEY_SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_CLOSE =
            PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_CLOSE.name
        private val KEY_SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_BOUNDS_CHANGE =
            PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_BOUNDS_CHANGE.name
        private val KEY_SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_EDGE_SNAP_BACK =
            PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_EDGE_SNAP_BACK.name
        private val KEY_SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_EDGE_DOCK =
            PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_EDGE_DOCK.name
        private val KEY_SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_BUBBLE_RESTORE =
            PreferenceKeys.SYSTEM_UI_FREEFORM_NONLINEAR_ANIM_BUBBLE_RESTORE.name
        private val KEY_SYSTEM_UI_FREEFORM_HANDLE_MENU_ANIM =
            PreferenceKeys.SYSTEM_UI_FREEFORM_HANDLE_MENU_ANIM.name

        // Fullscreen thresholds are percentages of the current display; 100 % disables
        // that direction and both at 100 % keeps the window out of fullscreen.
        const val FREEFORM_FULLSCREEN_PERCENT_MIN = 50
        const val FREEFORM_FULLSCREEN_PERCENT_MAX = 100
        private const val FREEFORM_FULLSCREEN_PERCENT_DEFAULT = 85
        private const val FREEFORM_FULLSCREEN_PERCENT_STEP = 5

        /** Snaps a slider percentage to the 5 % step and clamps it to the slider range. */
        fun normalizeFreeformFullscreenPercent(percent: Int): Int =
            ((percent + FREEFORM_FULLSCREEN_PERCENT_STEP / 2) / FREEFORM_FULLSCREEN_PERCENT_STEP *
                FREEFORM_FULLSCREEN_PERCENT_STEP)
                .coerceIn(FREEFORM_FULLSCREEN_PERCENT_MIN, FREEFORM_FULLSCREEN_PERCENT_MAX)
    }
}

data class SystemUiMiscSettingsUiState(
    val guestModeController: Boolean = false,
    val bypassFaceAuthTimeout: Boolean = false,
    val aospScrollCapture: Boolean = false,
    val shadeReboundFix: Boolean = false,
    val freeformFullscreenThreshold: Boolean = false,
    val freeformFullscreenWidthPercent: Int = 85,
    val freeformFullscreenHeightPercent: Int = 85,
    val freeformBottomEdgeResize: Boolean = false,
    val cornerHandleAutoHide: Boolean = true,
    val freeformNoForehead: Boolean = false,
    val freeformNonlinearAnim: Boolean = false,
    val freeformNonlinearAnimOpen: Boolean = true,
    val freeformNonlinearAnimClose: Boolean = true,
    val freeformNonlinearAnimBoundsChange: Boolean = true,
    val freeformNonlinearAnimEdgeSnapBack: Boolean = true,
    val freeformNonlinearAnimEdgeDock: Boolean = true,
    val freeformNonlinearAnimBubbleRestore: Boolean = true,
    val freeformHandleMenuAnim: Boolean = false,
    val isRestartProcessing: Boolean = false,
    val showRestartDialog: Boolean = false
)
