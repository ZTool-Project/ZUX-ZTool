package com.qimian233.ztool.hook.modules.launcher.grid

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
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
import kotlin.math.roundToInt

/**
 * Big folder icon and background geometric alignment (com.zui.launcher).
 *
 * Fixes the background/grid mismatch, the edge-hugging child grid and the label offset.
 * See docs/research/zui_vs_oplus_launcher_grid_and_big_folder.md (section 4).
 */
@SuppressLint("PrivateApi")
class BigFolderAlignHook : AppHookModule() {

    override fun getModuleName(): String = PreferenceKeys.LAUNCHER_BIG_FOLDER_ALIGN.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.LAUNCHER.packageName)

    /** Horizontal background alignment target; see backgroundInsetX. */
    private val backgroundAlign = BackgroundAlign.HOST

    /** Big-folder background alignment choices. */
    private enum class BackgroundAlign { HOST, SMALL_FOLDER, ICON_BOX }

    private var coreReady = false
    private var ruleReady = false
    private var bfcReady = false
    private var folderIconReady = false

    // PreviewBackground
    private lateinit var pbWidth: Field
    private lateinit var pbOffsetX: Field
    private lateinit var pbOffsetY: Field
    private lateinit var pbPreviewSizeY: Field
    private lateinit var pbSpanX: Field
    private lateinit var pbSpanY: Field
    private lateinit var pbSetup: Method
    private var pbComputeWh: Method? = null
    private var pbIsUpdate: Method? = null

    // ActivityContext / DeviceProfile / InvariantDeviceProfile
    private lateinit var activityContextGetDeviceProfile: Method
    private lateinit var dpInv: Field
    private lateinit var invNumColumns: Field
    private lateinit var dpGetCellLayoutWidth: Method
    private var dpUpdateIconSize: Method? = null

    // BigFolderConfig
    private lateinit var bfcChildIconScale: Field
    private lateinit var bfcGetChildCount: Method
    private var bfcGetHGap: Method? = null
    private var bfcGetVGap: Method? = null

    // ClippedFolderIconLayoutRule
    private lateinit var ruleCols: Field
    private lateinit var ruleRows: Field
    private lateinit var ruleIconSize: Field
    private lateinit var ruleBgWidth: Field
    private lateinit var ruleBgHeight: Field
    private lateinit var ruleCompute: Method
    private var ruleComputeIs9Args = false
    private var ruleParamsTransX: Field? = null
    private var ruleParamsTransY: Field? = null
    private lateinit var ruleGetOffsetX: Method
    private lateinit var ruleGetOffsetY: Method
    private var ruleOffsetFloatFirst = false
    private lateinit var ruleScaleForItem: Method
    private var ruleScaleParamCount = 2

    // FolderIcon
    private var folderLabel: Field? = null
    private lateinit var fiActivityContext: Field
    private lateinit var fiInfo: Field
    private lateinit var fiSpanX: Field
    private lateinit var fiSpanY: Field
    private lateinit var fiLabelMethod: Method

    // ── Hand tuning: six independent knobs in launcher-local px, see docs/research/big_folder_tuning.md ──
    @Volatile
    private var tuneGapH = PreferenceKeys.LAUNCHER_BIG_FOLDER_TUNE_GAP_H.default

    @Volatile
    private var tuneGapV = PreferenceKeys.LAUNCHER_BIG_FOLDER_TUNE_GAP_V.default

    @Volatile
    private var tuneBgX = PreferenceKeys.LAUNCHER_BIG_FOLDER_TUNE_BG_X.default

    @Volatile
    private var tuneBgY = PreferenceKeys.LAUNCHER_BIG_FOLDER_TUNE_BG_Y.default

    @Volatile
    private var tuneShiftX = PreferenceKeys.LAUNCHER_BIG_FOLDER_TUNE_SHIFT_X.default

    @Volatile
    private var tuneShiftY = PreferenceKeys.LAUNCHER_BIG_FOLDER_TUNE_SHIFT_Y.default

    @Throws(Throwable::class)
    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        logger.debug("BigFolderAlign installing")
        refreshTuning()

        val module = DexIndexStore.lookup(xposed, ScopeKeys.LAUNCHER.packageName)
            ?.getAsJsonObject(DexIndexConstants.JSON_MODULES)
            ?.getAsJsonObject(DexIndexConstants.ModuleKeys.LAUNCHER_BIG_FOLDER_ALIGN)

        if (!LauncherGridMetrics.install(classLoader, logger, xposed)) {
            logger.error("BigFolderAlign: shared grid metrics unavailable, disabled")
            return
        }
        if (!resolveCoreRefs(classLoader, module)) return
        ruleReady = resolveRuleRefs(classLoader, module)
        bfcReady = resolveBfcRefs(classLoader, module)
        folderIconReady = resolveFolderIconRefs(classLoader, module)

        hookSetupGeometry()
        if (bfcReady) hookChildCountRewrite()
        if (ruleReady) hookChildGridRule()
        if (bfcReady && bfcGetHGap != null && bfcGetVGap != null) {
            hookFolderGaps()
        } else if (bfcReady && ruleReady) {
            hookOffsetGaps()
        }
        if (folderIconReady) hookFolderLabel()
        if (pbComputeWh != null) hookAvailableWh()
        if (pbIsUpdate != null) hookIsUpdatePreviewSize()
        if (dpUpdateIconSize != null) hookDeviceProfileTelemetry()
    }

    /** Re-reads the six hand tuning values; a scope restart is the reliable refresh. */
    private fun refreshTuning() {
        try {
            val prefs = remotePreferences
            val gapH = prefs.getInt(
                PreferenceKeys.LAUNCHER_BIG_FOLDER_TUNE_GAP_H.name,
                PreferenceKeys.LAUNCHER_BIG_FOLDER_TUNE_GAP_H.default
            )
            val gapV = prefs.getInt(
                PreferenceKeys.LAUNCHER_BIG_FOLDER_TUNE_GAP_V.name,
                PreferenceKeys.LAUNCHER_BIG_FOLDER_TUNE_GAP_V.default
            )
            val bgX = prefs.getInt(
                PreferenceKeys.LAUNCHER_BIG_FOLDER_TUNE_BG_X.name,
                PreferenceKeys.LAUNCHER_BIG_FOLDER_TUNE_BG_X.default
            )
            val bgY = prefs.getInt(
                PreferenceKeys.LAUNCHER_BIG_FOLDER_TUNE_BG_Y.name,
                PreferenceKeys.LAUNCHER_BIG_FOLDER_TUNE_BG_Y.default
            )
            val shiftX = prefs.getInt(
                PreferenceKeys.LAUNCHER_BIG_FOLDER_TUNE_SHIFT_X.name,
                PreferenceKeys.LAUNCHER_BIG_FOLDER_TUNE_SHIFT_X.default
            )
            val shiftY = prefs.getInt(
                PreferenceKeys.LAUNCHER_BIG_FOLDER_TUNE_SHIFT_Y.name,
                PreferenceKeys.LAUNCHER_BIG_FOLDER_TUNE_SHIFT_Y.default
            )
            val changed = gapH != tuneGapH || gapV != tuneGapV || bgX != tuneBgX ||
                bgY != tuneBgY || shiftX != tuneShiftX || shiftY != tuneShiftY
            tuneGapH = gapH
            tuneGapV = gapV
            tuneBgX = bgX
            tuneBgY = bgY
            tuneShiftX = shiftX
            tuneShiftY = shiftY
            if (changed) {
                logger.info(
                    "BigFolderAlign tune: gapH=$gapH gapV=$gapV bgX=$bgX bgY=$bgY " +
                        "shiftX=$shiftX shiftY=$shiftY"
                )
            }
        } catch (t: Throwable) {
            logger.debug("tuning read failed, keeping previous values: $t")
        }
    }

    /** True when any hand tuning value is off its neutral value (log marker). */
    private fun tuningActive(): Boolean = tuneGapH != 0 || tuneGapV != 0 ||
        tuneBgX != 0 || tuneBgY != 0 || tuneShiftX != 0 || tuneShiftY != 0

    /** Core group: PreviewBackground / ActivityContext / DeviceProfile / InvariantDeviceProfile. */
    private fun resolveCoreRefs(classLoader: ClassLoader, module: JsonObject?): Boolean {
        return try {
            val pb = classLoader.loadClass("com.android.launcher3.folder.PreviewBackground")
            val activityContextClass = classLoader.loadClass("com.android.launcher3.views.ActivityContext")
            pbSpanX = findField(pb, "spanX")
            pbSpanY = findField(pb, "spanY")
            pbWidth = resolveFirstField(
                pb,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_PB_PREVIEW_SIZE_FIELD),
                "previewSize",
                "o"
            )
            pbOffsetX = resolveFirstField(
                pb,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_PB_OFFSET_X_FIELD),
                "basePreviewOffsetX",
                "p"
            )
            pbOffsetY = resolveFirstField(
                pb,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_PB_OFFSET_Y_FIELD),
                "basePreviewOffsetY",
                "q"
            )
            pbPreviewSizeY = findField(
                pb,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_PB_PREVIEW_SIZE_Y_FIELD)
                    ?: "previewSizeY"
            )
            pbSetup = findMethod(
                pb, "setup",
                Context::class.java, activityContextClass, View::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            val computeWhName = readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_PB_COMPUTE_WH_METHOD)
            pbComputeWh = if (computeWhName != null || module == null) {
                runCatching {
                    findMethod(
                        pb, computeWhName ?: "computeBigFolderAvaliableWh",
                        activityContextClass, Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType, IntArray::class.java
                    )
                }.getOrNull()
            } else {
                null
            }
            val isUpdateName = readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_PB_IS_UPDATE_METHOD)
            pbIsUpdate = if (isUpdateName != null || module == null) {
                runCatching {
                    findMethod(pb, isUpdateName ?: "isUpdatePreviewSize", activityContextClass)
                }.getOrNull()
            } else {
                null
            }

            activityContextGetDeviceProfile = findMethod(activityContextClass, "getDeviceProfile")

            val dp = classLoader.loadClass("com.android.launcher3.DeviceProfile")
            dpInv = findField(dp, "inv")
            invNumColumns = findField(dpInv.type, "numColumns")
            dpGetCellLayoutWidth = findMethod(dp, "getCellLayoutWidth")
            val updateIconSizeName = readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_DP_UPDATE_ICON_SIZE_METHOD)
            dpUpdateIconSize = if (updateIconSizeName != null || module == null) {
                runCatching {
                    findMethod(dp, updateIconSizeName ?: "updateIconSize", Float::class.javaPrimitiveType, Context::class.java)
                }.getOrNull()
            } else {
                null
            }
            coreReady = true
            true
        } catch (t: Throwable) {
            logger.error("resolve core refs failed, BigFolderAlign disabled", t)
            false
        }
    }

    /** Rule group: ClippedFolderIconLayoutRule (child icon grid positioning). */
    private fun resolveRuleRefs(classLoader: ClassLoader, module: JsonObject?): Boolean {
        return try {
            val rule = classLoader.loadClass("com.android.launcher3.folder.ClippedFolderIconLayoutRule")
            ruleCols = resolveFirstField(
                rule,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_RULE_COLS_FIELD),
                "iconColCount",
                "j"
            )
            ruleRows = resolveFirstField(
                rule,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_RULE_ROWS_FIELD),
                "iconRowCount",
                "g"
            )
            ruleIconSize = resolveFirstField(
                rule,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_RULE_ICON_SIZE_FIELD),
                "mIconSize",
                "d"
            )
            ruleBgWidth = resolveFirstField(
                rule,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_RULE_BG_WIDTH_FIELD),
                "mAvailableSpace",
                "b"
            )
            ruleBgHeight = resolveFirstField(
                rule,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_RULE_BG_HEIGHT_FIELD),
                "mAvailableSpaceY",
                "i"
            )

            val i = Int::class.javaPrimitiveType
            val f = Float::class.javaPrimitiveType
            val b = Boolean::class.javaPrimitiveType

            val computeName = readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_RULE_COMPUTE_METHOD)
            val computeParams = readParams(classLoader, module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_RULE_COMPUTE_PARAMS)
            if (computeName != null && computeParams != null) {
                ruleCompute = findMethod(rule, computeName, *computeParams)
            } else {
                val paramsClass = runCatching {
                    classLoader.loadClass("com.android.launcher3.folder.PreviewItemDrawingParams")
                }.getOrNull()
                ruleCompute = paramsClass?.let {
                    runCatching {
                        findMethod(rule, computeName ?: "computePreviewItemDrawingParams", i, i, it, b, i, i, i, i, i)
                    }.getOrNull()
                } ?: findMethod(rule, computeName ?: "c", i, i, FloatArray::class.java, i, i, i, i, i)
            }
            ruleComputeIs9Args = ruleCompute.parameterCount == 9
            if (ruleComputeIs9Args) {
                val paramsClass = ruleCompute.parameterTypes[2]
                ruleParamsTransX = findField(paramsClass, "transX")
                ruleParamsTransY = findField(paramsClass, "transY")
            }

            val offsetXName = readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_RULE_OFFSET_X_METHOD) ?: "getOffsetX"
            val offsetYName = readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_RULE_OFFSET_Y_METHOD) ?: "getOffsetY"
            val offsetParams = readParams(classLoader, module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_RULE_OFFSET_PARAMS)
            if (offsetParams != null) {
                ruleGetOffsetX = findMethod(rule, offsetXName, *offsetParams)
                ruleGetOffsetY = findMethod(rule, offsetYName, *offsetParams)
                ruleOffsetFloatFirst = offsetParams.firstOrNull() == f
            } else {
                val pairFloatFirst = runCatching {
                    findMethod(rule, offsetXName, f, f, i, i, i, i, i) to
                        findMethod(rule, offsetYName, f, f, i, i, i, i, i)
                }.getOrNull()
                if (pairFloatFirst != null) {
                    ruleGetOffsetX = pairFloatFirst.first
                    ruleGetOffsetY = pairFloatFirst.second
                    ruleOffsetFloatFirst = true
                } else {
                    ruleGetOffsetX = findMethod(rule, offsetXName, i, i, i, f, f, i, i)
                    ruleGetOffsetY = findMethod(rule, offsetYName, i, i, i, f, f, i, i)
                    ruleOffsetFloatFirst = false
                }
            }

            val scaleName = readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_RULE_SCALE_METHOD)
            val scaleParams = readParams(classLoader, module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_RULE_SCALE_PARAMS)
            ruleScaleForItem = if (scaleName != null && scaleParams != null) {
                findMethod(rule, scaleName, *scaleParams)
            } else {
                runCatching { findMethod(rule, scaleName ?: "zuiScaleForItem", b) }.getOrNull()
                    ?: runCatching { findMethod(rule, scaleName ?: "scaleForItem", i, b) }.getOrNull()
                    ?: findMethod(rule, scaleName ?: "scaleForItem", i, Boolean::class.java)
            }
            ruleScaleParamCount = ruleScaleForItem.parameterCount
            true
        } catch (t: Throwable) {
            logger.error("resolve rule refs failed, child grid hook skipped", t)
            false
        }
    }

    /** Style sheet group: BigFolderConfig (child grid/gap convergence point). */
    private fun resolveBfcRefs(classLoader: ClassLoader, module: JsonObject?): Boolean {
        return try {
            val bfcClassName = readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_BFC_CLASS)
                ?: "com.zui.launcher.folder.bigfolder.BigFolderConfig"
            val bfc = classLoader.loadClass(bfcClassName)
            bfcChildIconScale = resolveFirstField(
                bfc,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_BFC_CHILD_SCALE_FIELD),
                "CHILD_ICON_SCALE"
            )
            val childCountName = readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_BFC_CHILD_COUNT_METHOD)
                ?: "getBigFolderIconChildCount"
            bfcGetChildCount = findMethod(
                bfc, childCountName,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java
            )
            val hGapName = readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_BFC_HGAP_METHOD)
            val vGapName = readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_BFC_VGAP_METHOD)
            bfcGetHGap = if (hGapName != null || module == null) {
                runCatching {
                    findMethod(bfc, hGapName ?: "getBigFolderIconHGap", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                }.getOrNull()
            } else {
                null
            }
            bfcGetVGap = if (vGapName != null || module == null) {
                runCatching {
                    findMethod(bfc, vGapName ?: "getBigFolderIconVGap", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                }.getOrNull()
            } else {
                null
            }
            true
        } catch (t: Throwable) {
            logger.error("resolve BigFolderConfig refs failed, child grid/gap hooks skipped", t)
            false
        }
    }

    /** FolderIcon group: label field resolved by DexIndex, candidate names, or BubbleTextView scan. */
    private fun resolveFolderIconRefs(classLoader: ClassLoader, module: JsonObject?): Boolean {
        return try {
            val fi = classLoader.loadClass("com.android.launcher3.folder.FolderIcon")
            fiActivityContext = resolveFirstField(
                fi,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_ACTIVITY_FIELD),
                "mActivity",
                "b"
            )
            fiInfo = findField(
                fi,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_INFO_FIELD) ?: "mInfo"
            )
            fiSpanX = findField(fiInfo.type, "spanX")
            fiSpanY = findField(fiInfo.type, "spanY")

            val labelCandidates = buildList {
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_LABEL_FIELD)?.let { add(it) }
                add("mFolderName")
                add("folderBubbleTextView")
                add("e")
            }
            folderLabel = labelCandidates.firstNotNullOfOrNull { name ->
                runCatching { findField(fi, name) }.getOrNull()
            }
            if (folderLabel == null) {
                val bubbleClass = classLoader.loadClass("com.android.launcher3.BubbleTextView")
                folderLabel = fi.declaredFields
                    .firstOrNull { bubbleClass.isAssignableFrom(it.type) }
                    ?.apply { isAccessible = true }
            }
            if (folderLabel == null) {
                logger.error("FolderIcon label field unresolved, label hook skipped")
                return false
            }
            val labelMethodName = readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_LABEL_METHOD)
            fiLabelMethod = if (labelMethodName != null) {
                findMethod(fi, labelMethodName)
            } else {
                listOf("setFolderNameMargin", "z").firstNotNullOfOrNull { candidate ->
                    runCatching { findMethod(fi, candidate) }.getOrNull()
                } ?: throw NoSuchMethodException("FolderIcon setFolderNameMargin/z not found")
            }
            true
        } catch (t: Throwable) {
            logger.error("resolve FolderIcon refs failed, label hook skipped", t)
            false
        }
    }

    private fun hookSetupGeometry() {
        hookWithId(pbSetup, "big_folder_align_setup") { chain ->
            chain.proceed()
            try {
                applyAlignedGeometry(chain.thisObject, chain.args.getOrNull(1), chain.args.getOrNull(2))
            } catch (t: Throwable) {
                logger.error("applyAlignedGeometry failed", t)
            }
            null
        }
        logger.debug("hooked PreviewBackground.setup")
    }

    private fun applyAlignedGeometry(pb: Any, activityContext: Any?, folderIconView: Any?) {
        if (!coreReady || activityContext == null) return
        // Pick up slider changes without a restart whenever a folder is laid out again.
        refreshTuning()
        val spanX = pbSpanX.getInt(pb)
        val spanY = pbSpanY.getInt(pb)
        // Same semantics as the host BigFolderConfig.isBigFolder
        if (spanX <= 1 && spanY <= 1) return

        val dp = activityContextGetDeviceProfile.invoke(activityContext) ?: return
        // The live page carries the measured cell; the profile values are the fallback.
        val page = LauncherGridMetrics.pageOf(folderIconView as? View)
        val metrics = LauncherGridMetrics.fromPage(page, dp) ?: return
        // Static host methods (gap queries) have no page reference; they read this snapshot.
        LauncherGridMetrics.publish(metrics)

        val inset = backgroundInsetX(metrics)
        val newWidth = backgroundWidth(metrics, spanX)
        val oldWidth = pbWidth.getInt(pb)
        val oldOffsetX = pbOffsetX.getInt(pb)
        pbWidth.setInt(pb, newWidth)
        // Symmetric narrowing keeps the background centred on the same cell box.
        pbOffsetX.setInt(pb, inset + tuneBgX)

        // Background top/bottom edges align with the graphic edges of the top/bottom icon rows.
        val artInset = artInsetPx(metrics)
        val newOffsetY = metrics.rowInset + artInset + tuneBgY
        val newPreviewSizeY = backgroundHeight(metrics, spanY)
        val oldOffsetY = pbOffsetY.getInt(pb)
        val oldPreviewSizeY = pbPreviewSizeY.getInt(pb)
        if (newPreviewSizeY > 0) {
            pbOffsetY.setInt(pb, newOffsetY)
            pbPreviewSizeY.setInt(pb, newPreviewSizeY)
        }

        // Telemetry: label topMargin vs FolderIcon's own paddingTop (visual label position = their sum)
        var labelTopMargin = -1
        var iconPaddingTop = -1
        try {
            val f = folderLabel
            if (folderIconView != null && f != null) {
                labelTopMargin = ((f.get(folderIconView) as View).layoutParams
                    as ViewGroup.MarginLayoutParams).topMargin
            }
            iconPaddingTop = (folderIconView as? View)?.paddingTop ?: -1
        } catch (_: Throwable) {
        }

        logger.debug(
            "BigFolderAlign span=${spanX}x${spanY}" +
                " width $oldWidth->$newWidth offsetX $oldOffsetX->$inset" +
                " offsetY $oldOffsetY->$newOffsetY previewY $oldPreviewSizeY->${pbPreviewSizeY.getInt(pb)}" +
                " artInset=$artInset" +
                " cellW=${metrics.cellWidth} nominalW=${metrics.nominalCellWidth}" +
                " border=${metrics.borderX},${metrics.borderY} measured=${metrics.measured}" +
                " cellH=${metrics.cellHeight} rowInset=${metrics.rowInset}" +
                " profileContent=${metrics.profileContentHeightPx}" +
                " folderIcon=${metrics.folderIconSizePx} icon=${metrics.iconSizePx}" +
                " widgetPadL=${metrics.widgetPaddingLeft} widgetPadT=${metrics.widgetPaddingTop}" +
                " bgBottom=${pbOffsetY.getInt(pb) + pbPreviewSizeY.getInt(pb)}" +
                (if (tuningActive()) " tune=gapH$tuneGapH,gapV$tuneGapV," +
                    "bgX$tuneBgX,bgY$tuneBgY," +
                    "shiftX$tuneShiftX,shiftY$tuneShiftY" else "") +
                " labelTop=$labelTopMargin iconPadT=$iconPaddingTop"
        )
    }

    /**
     * Transparent margin around the icon artwork inside the icon box.
     *
     * ART_INSET_RATIO covers platforms whose profile reports no widget padding.
     */
    private fun artInsetPx(metrics: LauncherGridMetrics.Metrics): Int =
        if (metrics.widgetPaddingTop > 0) metrics.widgetPaddingTop
        else (metrics.iconSizePx * ART_INSET_RATIO).roundToInt()

    /**
     * Horizontal inset of the background inside its cell box.
     */
    private fun backgroundInsetX(metrics: LauncherGridMetrics.Metrics): Int =
        (when (backgroundAlign) {
            BackgroundAlign.HOST ->
                if (metrics.widgetPaddingLeft > 0) metrics.widgetPaddingLeft
                else metrics.insetToSmallFolder
            BackgroundAlign.SMALL_FOLDER -> metrics.insetToSmallFolder
            BackgroundAlign.ICON_BOX -> metrics.insetToIcon
        }).coerceAtLeast(0)

    /** Background width before hand tuning, and the reference the child gap is solved from. */
    private fun backgroundBaseWidth(metrics: LauncherGridMetrics.Metrics, spanX: Int): Int =
        spanX * metrics.cellWidth + (spanX - 1) * metrics.borderX - 2 * backgroundInsetX(metrics)

    /** Background width actually written: the hand tuning narrows it symmetrically. */
    private fun backgroundWidth(metrics: LauncherGridMetrics.Metrics, spanX: Int): Int =
        backgroundBaseWidth(metrics, spanX) - 2 * tuneBgX

    /** Background height before hand tuning, and the reference the child gap is solved from. */
    private fun backgroundBaseHeight(metrics: LauncherGridMetrics.Metrics, spanY: Int): Int =
        (spanY - 1) * metrics.cellPitchY + metrics.iconSizePx - 2 * artInsetPx(metrics)

    /** Background height actually written: the hand tuning grows (negative) or shrinks it. */
    private fun backgroundHeight(metrics: LauncherGridMetrics.Metrics, spanY: Int): Int =
        backgroundBaseHeight(metrics, spanY) - 2 * tuneBgY

    /** Style sheet convergence rewrite: one rewrite drives layout, preview count, hit-testing and drop capacity. */
    private fun hookChildCountRewrite() {
        hookWithId(bfcGetChildCount, "big_folder_align_child_count") { chain ->
            val result = chain.proceed()
            try {
                val spanX = chain.args[0] as Int
                val spanY = chain.args[1] as Int
                val out = chain.args[2] as IntArray?
                val key = "${spanX}x$spanY"
                if (out != null) {
                    stockGrids[key] = intArrayOf(out[0], out[1])
                }
                val rewrite = intArrayOf(
                    if (spanX <= 1) 1 else CHILD_COLS,
                    if (spanY <= 1) 1 else CHILD_ROWS
                )
                out?.set(0, rewrite[0])
                out?.set(1, rewrite[1])
                rewrittenGrids[key] = rewrite
                val newCount = rewrite[0] * rewrite[1]
                if ((result as Int) != newCount && loggedSpans.add(key)) {
                    logger.debug(
                        "childCount($key) $result -> $newCount (cols=${rewrite[0]} rows=${rewrite[1]})"
                    )
                }
                return@hookWithId newCount
            } catch (t: Throwable) {
                logger.error("childCount rewrite failed", t)
            }
            result
        }
        logger.debug("hooked getBigFolderIconChildCount")
    }

    /** Rewrites child grid positioning to use the generic centered getOffsetX/getOffsetY formula. */
    private fun hookChildGridRule() {
        hookWithId(ruleCompute, "big_folder_align_child_grid") { chain ->
            val result = chain.proceed()
            try {
                val args = chain.args
                val index = args[0] as Int
                val curNumItems = args[1] as Int
                val spanX: Int
                val spanY: Int
                val availW: Int
                val availH: Int
                val padTop: Int
                if (ruleComputeIs9Args) {
                    spanX = args[4] as Int
                    spanY = args[5] as Int
                    availW = args[6] as Int
                    availH = args[7] as Int
                    padTop = args[8] as Int
                } else {
                    spanX = args[3] as Int
                    spanY = args[4] as Int
                    availW = args[5] as Int
                    availH = args[6] as Int
                    padTop = args[7] as Int
                }
                // ENTER/EXIT(-2/-3) go through the animation-specific path; leave non-big-folders untouched
                if (index < 0 || (spanX <= 1 && spanY <= 1)) {
                    return@hookWithId if (ruleComputeIs9Args) result else null
                }
                val rule = chain.thisObject
                val cols = ruleCols.getInt(rule)
                val rows = ruleRows.getInt(rule)
                val iconSize = ruleIconSize.getFloat(rule)
                val scale = if (ruleScaleParamCount == 1) {
                    ruleScaleForItem.invoke(rule, true) as Float
                } else {
                    ruleScaleForItem.invoke(rule, maxOf(curNumItems, 3), true) as Float
                }
                val halfIcon = iconSize * scale / 2f
                // The host centers the child grid in the background; hand tuning shifts icons only.
                val centerX =
                    (if (availW == -1) ruleBgWidth.getFloat(rule) / 2f else availW / 2f) + tuneShiftX
                val centerY =
                    (if (availH == -1) ruleBgHeight.getFloat(rule) / 2f else availH / 2f) + padTop + tuneShiftY

                val newX = invokeOffset(ruleGetOffsetX, rule, index, cols, rows, centerX, halfIcon, spanX, spanY)
                val newY = invokeOffset(ruleGetOffsetY, rule, index, cols, rows, centerY, halfIcon, spanX, spanY)

                if (ruleComputeIs9Args) {
                    val drawingParams = result ?: args[2]
                    if (drawingParams != null) {
                        ruleParamsTransX?.setFloat(drawingParams, newX)
                        ruleParamsTransY?.setFloat(drawingParams, newY)
                    }
                    logger.debug("childGrid idx=$index -> ($newX, $newY)")
                    return@hookWithId result
                } else {
                    val out = args[2] as FloatArray
                    out[0] = newX
                    out[1] = newY
                    logger.debug("childGrid idx=$index -> ($newX, $newY)")
                }
            } catch (t: Throwable) {
                logger.error("child grid rewrite failed", t)
            }
            if (ruleComputeIs9Args) result else null
        }
        logger.debug("hooked ClippedFolderIconLayoutRule computePreviewItemDrawingParams")
    }

    private fun invokeOffset(
        method: Method,
        rule: Any?,
        index: Int,
        cols: Int,
        rows: Int,
        center: Float,
        halfIcon: Float,
        spanX: Int,
        spanY: Int
    ): Float {
        val target = if (Modifier.isStatic(method.modifiers)) null else rule
        return if (ruleOffsetFloatFirst) {
            method.invoke(target, center, halfIcon, index, cols, rows, spanX, spanY) as Float
        } else {
            method.invoke(target, index, cols, rows, center, halfIcon, spanX, spanY) as Float
        }
    }

    /**
     * Pre-18.6 gap rewrite via BigFolderConfig.getBigFolderIconHGap/VGap.
     */
    private fun hookFolderGaps() {
        val hGap = bfcGetHGap ?: return
        val vGap = bfcGetVGap ?: return
        for ((method, isH) in listOf(hGap to true, vGap to false)) {
            val id = "big_folder_align_${if (isH) "h" else "v"}gap"
            hookWithId(method, id) { chain ->
                val result = chain.proceed()
                try {
                    val spanX = chain.args[0] as Int
                    val spanY = chain.args[1] as Int
                    if (spanX <= 1 && spanY <= 1) return@hookWithId result
                    val spanKey = "${spanX}x$spanY"
                    val grid = rewrittenGrids[spanKey] ?: stockGrids[spanKey]
                        ?: return@hookWithId result
                    val metrics = LauncherGridMetrics.cached ?: return@hookWithId result
                    if (metrics.cellWidth <= 0) return@hookWithId result
                    val n = if (isH) grid[0] else grid[1]
                    if (n <= 1) return@hookWithId 0f
                    val bgAxis = if (isH) backgroundBaseWidth(metrics, spanX)
                    else backgroundBaseHeight(metrics, spanY)
                    val childSize = metrics.folderIconSizePx * bfcChildIconScale.getFloat(null)
                    val delta = if (isH) tuneGapH else tuneGapV
                    val newGap = maxOf(0f, (bgAxis - n * childSize) / (n + 1) + delta)
                    val logKey = "${if (isH) "h" else "v"}Gap$spanKey"
                    if (loggedSpans.add(logKey) && newGap != (result as Float)) {
                        logger.debug(
                            "${if (isH) "hGap" else "vGap"}($spanX,$spanY) $result -> $newGap " +
                                "(n=$n bg=$bgAxis child=$childSize delta=$delta)"
                        )
                    }
                    return@hookWithId newGap
                } catch (t: Throwable) {
                    logger.error("gap rewrite failed", t)
                    result
                }
            }
        }
        logger.debug("hooked getBigFolderIconHGap/VGap")
    }

    /**
     * 18.6.0.1116+ gap rewrite when BigFolderConfig HGap/VGap methods are removed:
     * hooks ClippedFolderIconLayoutRule.getOffsetX/Y directly using pixel-space gaps.
     */
    private fun hookOffsetGaps() {
        for ((method, isH) in listOf(ruleGetOffsetX to true, ruleGetOffsetY to false)) {
            val id = "big_folder_align_offset_${if (isH) "x" else "y"}"
            hookWithId(method, id) { chain ->
                val result = chain.proceed()
                try {
                    val args = chain.args
                    val center: Float
                    val halfIcon: Float
                    val index: Int
                    val cols: Int
                    val rows: Int
                    val spanX: Int
                    val spanY: Int
                    if (ruleOffsetFloatFirst) {
                        center = args[0] as Float
                        halfIcon = args[1] as Float
                        index = args[2] as Int
                        cols = args[3] as Int
                        rows = args[4] as Int
                        spanX = args[5] as Int
                        spanY = args[6] as Int
                    } else {
                        index = args[0] as Int
                        cols = args[1] as Int
                        rows = args[2] as Int
                        center = args[3] as Float
                        halfIcon = args[4] as Float
                        spanX = args[5] as Int
                        spanY = args[6] as Int
                    }
                    if (index < 0 || cols <= 0 || rows <= 0 || (spanX <= 1 && spanY <= 1)) {
                        return@hookWithId result
                    }
                    val metrics = LauncherGridMetrics.cached ?: return@hookWithId result
                    if (metrics.cellWidth <= 0) return@hookWithId result
                    val n = if (isH) cols else rows
                    val gap = if (n <= 1) {
                        0f
                    } else {
                        val bgAxis = if (isH) backgroundBaseWidth(metrics, spanX)
                        else backgroundBaseHeight(metrics, spanY)
                        val childSize = metrics.folderIconSizePx * bfcChildIconScale.getFloat(null)
                        val delta = if (isH) tuneGapH else tuneGapV
                        maxOf(0f, (bgAxis - n * childSize) / (n + 1) + delta)
                    }
                    val cellIdx = if (isH) (index % cols) else (index / cols)
                    val offset = center - halfIcon + (cellIdx - (n - 1) / 2f) * (2f * halfIcon + gap)
                    val logKey = "${if (isH) "h" else "v"}OffsetGap${spanX}x$spanY"
                    if (loggedSpans.add(logKey)) {
                        logger.debug(
                            "${if (isH) "hOffsetGap" else "vOffsetGap"}($spanX,$spanY) n=$n gap=$gap " +
                                "result=$result -> $offset"
                        )
                    }
                    return@hookWithId offset
                } catch (t: Throwable) {
                    logger.error("offset gap rewrite failed", t)
                    result
                }
            }
        }
        logger.debug("hooked ClippedFolderIconLayoutRule.getOffsetX/Y")
    }

    private fun hookFolderLabel() {
        val labelField = folderLabel ?: return
        hookWithId(fiLabelMethod, "big_folder_align_label") { chain ->
            try {
                val icon = chain.thisObject
                val info = fiInfo.get(icon)
                    ?: return@hookWithId chain.proceed()
                val spanX = fiSpanX.getInt(info)
                val spanY = fiSpanY.getInt(info)
                val activityContext = fiActivityContext.get(icon)
                    ?: return@hookWithId chain.proceed()
                val dp = activityContextGetDeviceProfile.invoke(activityContext)
                    ?: return@hookWithId chain.proceed()
                val iconSizePx = LauncherGridMetrics.iconSizePx(dp)
                    ?: return@hookWithId chain.proceed()
                val drawablePadding = LauncherGridMetrics.iconDrawablePaddingPx(dp)
                    ?: return@hookWithId chain.proceed()
                val topMargin: Int
                if (spanX <= 1 && spanY <= 1) {
                    // Small folder branch: replicate the stock formula
                    topMargin = iconSizePx + drawablePadding
                } else {
                    val metrics = LauncherGridMetrics.fromPage(
                        LauncherGridMetrics.pageOf(icon as? View), dp
                    ) ?: return@hookWithId chain.proceed()
                    topMargin = (spanY - 1) * metrics.cellPitchY +
                        metrics.rowInset + iconSizePx + drawablePadding
                }
                val label = labelField.get(icon) as View
                val lp = label.layoutParams as ViewGroup.MarginLayoutParams
                if (lp.topMargin != topMargin) {
                    lp.topMargin = topMargin
                    label.requestLayout()
                    logger.debug("label topMargin -> $topMargin (span=${spanX}x${spanY})")
                }
                null
            } catch (t: Throwable) {
                logger.error("label align failed, fallback to stock method", t)
                chain.proceed()
            }
        }
        logger.debug("hooked FolderIcon label alignment")
    }

    private fun hookAvailableWh() {
        val method = pbComputeWh ?: return
        hookWithId(method, "big_folder_align_available_wh") { chain ->
            chain.proceed()
            try {
                val args = chain.args
                val wh = args[3] as IntArray
                val holder = args[0] ?: return@hookWithId null
                val dp = activityContextGetDeviceProfile.invoke(holder) ?: return@hookWithId null
                val metrics = LauncherGridMetrics.cached
                    ?: LauncherGridMetrics.fromPage(null, dp)
                    ?: return@hookWithId null
                val spanX = args[1] as Int
                if (spanX > 1) {
                    val old = wh[0]
                    wh[0] = backgroundWidth(metrics, spanX)
                    logger.debug("availableWh spanX=$spanX width $old -> ${wh[0]} (h=${wh[1]})")
                }
            } catch (t: Throwable) {
                logger.error("availableWh adjust failed", t)
            }
            null
        }
        logger.debug("hooked computeBigFolderAvaliableWh")
    }

    private fun hookIsUpdatePreviewSize() {
        val method = pbIsUpdate ?: return
        hookWithId(method, "big_folder_align_is_update") { chain ->
            val result = chain.proceed()
            try {
                val pb = chain.thisObject
                logger.debug(
                    "isUpdatePreviewSize -> $result span=${pbSpanX.getInt(pb)}x${pbSpanY.getInt(pb)}" +
                        " width=${pbWidth.getInt(pb)} offsetX=${pbOffsetX.getInt(pb)}"
                )
            } catch (_: Throwable) {
            }
            result
        }
        logger.debug("hooked isUpdatePreviewSize")
    }

    private fun hookDeviceProfileTelemetry() {
        val method = dpUpdateIconSize ?: return
        hookWithId(method, "big_folder_align_dp_log") { chain ->
            chain.proceed()
            try {
                val dp = chain.thisObject
                val inv = dpInv.get(dp)
                val padding = LauncherGridMetrics.cellLayoutPaddingPx(dp)
                val borderSpace = LauncherGridMetrics.cellLayoutBorderSpacePx(dp)
                val widgetPadding = LauncherGridMetrics.widgetPadding(dp)
                val metrics = LauncherGridMetrics.fromPage(null, dp)
                logger.debug(
                    "DeviceProfile cols=${invNumColumns.getInt(inv)}" +
                        " cellLayoutW=${dpGetCellLayoutWidth.invoke(dp)}" +
                        " pad=${padding?.left}x${padding?.top}" +
                        " gap=${borderSpace?.x}x${borderSpace?.y}" +
                        " iconSize=${LauncherGridMetrics.iconSizePx(dp)}" +
                        " folderIcon=${LauncherGridMetrics.folderIconSizePx(dp)}" +
                        " folderOffsetY=${LauncherGridMetrics.folderIconOffsetYPx(dp)}" +
                        " widgetPad=${widgetPadding?.left},${widgetPadding?.top}" +
                        " cellW=${metrics?.cellWidth} cellH=${metrics?.cellHeight}" +
                        " nominalW=${metrics?.nominalCellWidth}" +
                        " content=${metrics?.contentHeightPx}" +
                        " profileContent=${metrics?.profileContentHeightPx}"
                )
            } catch (t: Throwable) {
                logger.debug("DeviceProfile telemetry failed: $t")
            }
            null
        }
        logger.debug("hooked DeviceProfile.updateIconSize (telemetry)")
    }

    private fun resolveFirstField(owner: Class<*>, indexed: String?, vararg fallbacks: String): Field {
        if (indexed != null) return findField(owner, indexed)
        for (name in fallbacks) {
            runCatching { return findField(owner, name) }
        }
        throw NoSuchFieldException("${fallbacks.joinToString()} in $owner")
    }

    private fun readString(module: JsonObject?, key: String): String? =
        module?.get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }

    private fun readParams(
        classLoader: ClassLoader,
        module: JsonObject?,
        key: String
    ): Array<Class<*>?>? {
        val raw = readString(module, key) ?: return null
        return raw.split(",").map { resolveType(classLoader, it.trim()) }.toTypedArray()
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

    companion object {
        /** Child icon grid of a multi-cell big-folder axis: CHILD_COLS per row x CHILD_ROWS rows. */
        private const val CHILD_COLS = 3
        private const val CHILD_ROWS = 3

        /** Transparent margin ratio around the artwork inside the icon box; fallback only (measured ~21px in a 190px box). */
        private const val ART_INSET_RATIO = 0.11f

        /** One-shot log dedup (childCount/gap calls are very frequent; each key is logged once). */
        private val loggedSpans: MutableSet<String> =
            java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

        /** Learned stock child grids (span -> [cols, rows]), kept for telemetry only. */
        private val stockGrids = java.util.concurrent.ConcurrentHashMap<String, IntArray>()

        /** Spans with rewritten child grids (span -> [cols, rows]); gap recomputation reads them. */
        private val rewrittenGrids = java.util.concurrent.ConcurrentHashMap<String, IntArray>()
    }
}
