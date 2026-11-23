package com.qimian233.ztool.hook.modules.launcher.grid

import android.animation.AnimatorSet
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Aligns square (`3x3`, 10+ stack), strip (`1x2` 3x1 / `2x1` 1x3, 4+ stack), and
 * wide `2x3` (`3x4`, 13+ stack) big folders to matched backgrounds and proportional grids.
 */
@SuppressLint("PrivateApi")
class BigFolderAlignHook : AppHookModule() {

    private enum class StyleKind { SQUARE, STRIP_1X2, STRIP_2X1, WIDE_3X2 }

    private data class FolderStyleSpec(val kind: StyleKind, val cols: Int, val rows: Int) {
        val capacity: Int get() = cols * rows
    }

    private class GridLayout(
        val iconPx: Float,
        val gapX: Float,
        val gapY: Float,
        val gridLeft: Float,
        val gridStartY: Float
    )

    private data class PreResizeBgSnapshot(
        val beforeSpanX: Int,
        val beforeSpanY: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    override fun getModuleName(): String = PreferenceKeys.LAUNCHER_BIG_FOLDER_ALIGN.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.LAUNCHER.packageName)

    private var squareStyleEnabled = false
    private var stripStyleEnabled = false
    private var wideStyleEnabled = false

    private var coreReady = false
    private var ruleReady = false
    private var bfcReady = false
    private var folderIconReady = false
    private var pimReady = false
    private var famReady = false
    private var resizeReady = false

    // PreviewBackground
    private lateinit var pbSpanX: Field
    private lateinit var pbSpanY: Field
    private lateinit var pbPreviewSize: Field
    private lateinit var pbPreviewSizeY: Field
    private lateinit var pbOffsetX: Field
    private lateinit var pbOffsetY: Field
    private lateinit var pbSetup: Method
    private var pbCellHeightBig: Field? = null
    private var acGetDeviceProfile: Method? = null

    // ClippedFolderIconLayoutRule & PreviewItemDrawingParams
    private lateinit var ruleIconSize: Field
    private lateinit var ruleBgWidth: Field
    private lateinit var ruleBgHeight: Field
    private lateinit var ruleCompute: Method
    private var ruleComputeIs9Args = false
    private var ruleParamsTransX: Field? = null
    private var ruleParamsTransY: Field? = null
    private var ruleParamsScale: Field? = null

    // BigFolderConfig
    private lateinit var bfcGetChildCount: Method

    // FolderIcon
    private lateinit var fiInfo: Field
    private lateinit var fiSpanX: Field
    private lateinit var fiSpanY: Field
    private var fiBg: Field? = null
    private var fiLabel: Field? = null
    private var fiActivity: Field? = null
    private var fiNameTopMargin: Field? = null
    private var fiLayoutRule: Field? = null
    private var fiPreviewItemMgr: Field? = null
    private var fiLabelMethod: Method? = null
    private var fiPadTopMethod: Method? = null

    // PreviewItemManager
    private lateinit var pimIcon: Field
    private lateinit var pimFirstPage: Field
    private lateinit var pimDrawParams: Method
    private lateinit var pimCheckClick: Method
    private var pimIconSize: Field? = null

    // FolderAnimationManager
    private lateinit var famFolderIcon: Field
    private lateinit var famTmpParams: Field
    private lateinit var famComputeBigAnim: Method

    // Resize transition
    private lateinit var fiOnBeforeResize: Method
    private lateinit var fiOnAfterResize: Method
    private var fiUpdateBgBlur: Method? = null
    private var fiTransformDuration: Field? = null
    private var fiTransformInterpolator: Field? = null
    private lateinit var pbCellWidthBig: Field
    private lateinit var pbPreRect: Field
    private lateinit var pbDstRect: Field
    private lateinit var pbOffsetL: Field
    private lateinit var pbOffsetT: Field
    private lateinit var pbOffsetR: Field
    private lateinit var pbOffsetB: Field
    private var pbUseBeforeBgBt: Field? = null
    private lateinit var pimPreParams: Field
    private lateinit var pimOffsetX: Field
    private lateinit var pimOffsetY: Field
    private lateinit var pimDstWidth: Field
    private lateinit var pimDstHeight: Field
    private var pimHandleAnim: Field? = null
    private lateinit var paramStartTransX: Field
    private lateinit var paramStartTransY: Field
    private lateinit var paramOffsetTransX: Field
    private lateinit var paramOffsetTransY: Field
    private lateinit var paramResizeTransX: Field
    private lateinit var paramResizeTransY: Field
    private lateinit var paramResizeScale: Field

    private val suppressStackingForExactCapacity = ThreadLocal.withInitial { false }
    private val preResizeBgSnapshots = WeakHashMap<Any, PreResizeBgSnapshot>()
    private val activeScaleAnimators = WeakHashMap<Any, ValueAnimator>()

    @Throws(Throwable::class)
    override fun handleLoadPackage(param: PackageLoadedParam) {
        val prefs = runCatching { xposed.getRemotePreferences("xposed_module_config") }.getOrNull()
        squareStyleEnabled = runCatching {
            prefs?.getBoolean(
                PreferenceKeys.LAUNCHER_BIG_FOLDER_SQUARE_STYLE.name,
                PreferenceKeys.LAUNCHER_BIG_FOLDER_SQUARE_STYLE.default
            )
        }.getOrNull() ?: PreferenceKeys.LAUNCHER_BIG_FOLDER_SQUARE_STYLE.default
        stripStyleEnabled = runCatching {
            prefs?.getBoolean(
                PreferenceKeys.LAUNCHER_BIG_FOLDER_STRIP_STYLE.name,
                PreferenceKeys.LAUNCHER_BIG_FOLDER_STRIP_STYLE.default
            )
        }.getOrNull() ?: PreferenceKeys.LAUNCHER_BIG_FOLDER_STRIP_STYLE.default
        wideStyleEnabled = runCatching {
            prefs?.getBoolean(
                PreferenceKeys.LAUNCHER_BIG_FOLDER_WIDE_STYLE.name,
                PreferenceKeys.LAUNCHER_BIG_FOLDER_WIDE_STYLE.default
            )
        }.getOrNull() ?: PreferenceKeys.LAUNCHER_BIG_FOLDER_WIDE_STYLE.default
        if (!squareStyleEnabled && !stripStyleEnabled && !wideStyleEnabled) {
            logger.debug("BigFolderAlign: all folder style sub-features disabled, skipping")
            return
        }

        val classLoader = param.defaultClassLoader
        LauncherGridMetrics.install(classLoader, logger, xposed)
        logger.debug("BigFolderAlign installing (square=$squareStyleEnabled strip=$stripStyleEnabled wide=$wideStyleEnabled)")

        val module = DexIndexStore.lookup(xposed, ScopeKeys.LAUNCHER.packageName)
            ?.getAsJsonObject(DexIndexConstants.JSON_MODULES)
            ?.getAsJsonObject(DexIndexConstants.ModuleKeys.LAUNCHER_BIG_FOLDER_ALIGN)

        coreReady = resolveCoreRefs(classLoader, module)
        if (!coreReady) return
        ruleReady = resolveRuleRefs(classLoader, module)
        bfcReady = resolveBfcRefs(classLoader, module)
        folderIconReady = resolveFolderIconRefs(classLoader, module)
        pimReady = resolvePreviewItemManagerRefs(classLoader, module)
        famReady = resolveFamRefs(classLoader, module)
        resizeReady = resolveResizeRefs(classLoader)

        hookSetupGeometry()
        if (bfcReady) hookChildCountRewrite()
        if (ruleReady) hookChildGridRule()
        if (bfcReady && folderIconReady && pimReady) hookExactCapacityStackThreshold()
        if (folderIconReady) hookFolderLabelAndPadding()
        if (ruleReady && folderIconReady && famReady && ruleParamsScale != null) hookBigFolderItemAnim()
        if (resizeReady) hookResizeAnimation()
    }

    private fun resolveCoreRefs(classLoader: ClassLoader, module: JsonObject?): Boolean {
        return try {
            val pb = classLoader.loadClass("com.android.launcher3.folder.PreviewBackground")
            val activityContextClass = classLoader.loadClass("com.android.launcher3.views.ActivityContext")
            pbSpanX = findField(pb, "spanX")
            pbSpanY = findField(pb, "spanY")
            pbPreviewSize = resolveFirstField(
                pb,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_PB_PREVIEW_SIZE_FIELD),
                "previewSize",
                "o"
            )
            pbPreviewSizeY = findField(
                pb,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_PB_PREVIEW_SIZE_Y_FIELD)
                    ?: "previewSizeY"
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
            pbCellHeightBig = runCatching { findField(pb, "cellHeightBig") }.getOrNull()
            pbSetup = findMethod(
                pb, "setup",
                Context::class.java, activityContextClass, View::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            acGetDeviceProfile = runCatching {
                activityContextClass.getMethod("getDeviceProfile")
            }.getOrNull()
            true
        } catch (t: Throwable) {
            logger.error("resolve core refs failed, BigFolderAlign disabled", t)
            false
        }
    }

    private fun resolveRuleRefs(classLoader: ClassLoader, module: JsonObject?): Boolean {
        return try {
            val rule = classLoader.loadClass("com.android.launcher3.folder.ClippedFolderIconLayoutRule")
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
                        findMethod(
                            rule,
                            computeName ?: "computePreviewItemDrawingParams",
                            i, i, it, b, i, i, i, i, i
                        )
                    }.getOrNull()
                } ?: findMethod(rule, computeName ?: "c", i, i, FloatArray::class.java, i, i, i, i, i)
            }
            ruleComputeIs9Args = ruleCompute.parameterCount == 9
            if (ruleComputeIs9Args) {
                val paramsClass = ruleCompute.parameterTypes[2]
                ruleParamsTransX = findField(paramsClass, "transX")
                ruleParamsTransY = findField(paramsClass, "transY")
                ruleParamsScale = findField(paramsClass, "scale")
            }
            true
        } catch (t: Throwable) {
            logger.error("resolve rule refs failed, child grid hook skipped", t)
            false
        }
    }

    private fun resolveBfcRefs(classLoader: ClassLoader, module: JsonObject?): Boolean {
        return try {
            val bfcClassName = readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_BFC_CLASS)
                ?: "com.zui.launcher.folder.bigfolder.BigFolderConfig"
            val bfc = classLoader.loadClass(bfcClassName)
            val childCountName = readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_BFC_CHILD_COUNT_METHOD)
                ?: "getBigFolderIconChildCount"
            bfcGetChildCount = findMethod(
                bfc, childCountName,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java
            )
            true
        } catch (t: Throwable) {
            logger.error("resolve BigFolderConfig refs failed, child grid hook skipped", t)
            false
        }
    }

    private fun resolveFolderIconRefs(classLoader: ClassLoader, module: JsonObject?): Boolean {
        return try {
            val fi = classLoader.loadClass("com.android.launcher3.folder.FolderIcon")
            fiInfo = findField(
                fi,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_INFO_FIELD) ?: "mInfo"
            )
            fiSpanX = findField(fiInfo.type, "spanX")
            fiSpanY = findField(fiInfo.type, "spanY")
            fiBg = resolveOptionalField(
                fi,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_BG_FIELD),
                "mBackground"
            )
            fiLabel = resolveLabelField(classLoader, fi, module)
            fiActivity = runCatching { findField(fi, "mActivity") }.getOrNull()
                ?: runCatching {
                    val ac = classLoader.loadClass("com.android.launcher3.views.ActivityContext")
                    fi.declaredFields.firstOrNull { ac.isAssignableFrom(it.type) }
                        ?.apply { isAccessible = true }
                }.getOrNull()
            fiNameTopMargin = resolveOptionalField(
                fi,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_NAME_TOP_MARGIN_FIELD),
                "mFolderNameTopMargin"
            )
            fiLayoutRule = resolveOptionalField(
                fi,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_LAYOUT_RULE_FIELD),
                "mPreviewLayoutRule"
            )
            fiPreviewItemMgr = resolveOptionalField(
                fi,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_PIM_FIELD),
                "mPreviewItemManager"
            )
            fiLabelMethod = resolveOptionalMethod(
                fi,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_LABEL_METHOD),
                listOf("setFolderNameMargin", "z")
            )
            fiPadTopMethod = resolveOptionalMethod(
                fi,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_PAD_TOP_METHOD),
                listOf("getBigFolderItemPaddingTop")
            )
            true
        } catch (t: Throwable) {
            logger.error("resolve FolderIcon refs failed, label sync skipped", t)
            false
        }
    }

    private fun resolvePreviewItemManagerRefs(classLoader: ClassLoader, module: JsonObject?): Boolean {
        return try {
            val pim = classLoader.loadClass("com.android.launcher3.folder.PreviewItemManager")
            val fi = classLoader.loadClass("com.android.launcher3.folder.FolderIcon")
            pimIcon = resolveFirstField(
                pim,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_PIM_ICON_FIELD),
                "mIcon"
            )
            pimFirstPage = resolveFirstField(
                pim,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_PIM_FIRST_PAGE_FIELD),
                "mFirstPageParams"
            )
            pimIconSize = resolveOptionalField(
                pim,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_PIM_ICON_SIZE_FIELD),
                "mIconSize"
            )
            val drawParamsName = readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_PIM_DRAW_PARAMS_METHOD)
                ?: "drawParams"
            pimDrawParams = findMethod(
                pim,
                drawParamsName,
                Canvas::class.java,
                ArrayList::class.java,
                PointF::class.java,
                Boolean::class.javaPrimitiveType,
                Path::class.java,
                Boolean::class.javaPrimitiveType
            )
            val checkClickName = readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_PIM_CHECK_CLICK_METHOD)
                ?: "checkClickFolderIconItem"
            pimCheckClick = findMethod(
                pim,
                checkClickName,
                fi,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Rect::class.java,
                Int::class.javaPrimitiveType
            )
            true
        } catch (t: Throwable) {
            logger.error("resolve PreviewItemManager refs failed, 9-item unstack skipped", t)
            false
        }
    }

    private fun resolveFamRefs(classLoader: ClassLoader, module: JsonObject?): Boolean {
        return try {
            val fam = classLoader.loadClass("com.android.launcher3.folder.FolderAnimationManager")
            famFolderIcon = resolveFirstField(
                fam,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FAM_ICON_FIELD),
                "mFolderIcon"
            )
            famTmpParams = resolveFirstField(
                fam,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FAM_TMP_PARAMS_FIELD),
                "mTmpParams"
            )
            val methodName = readString(
                module,
                DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FAM_COMPUTE_BIG_ANIM_METHOD
            )
            val params = readParams(
                classLoader,
                module,
                DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FAM_COMPUTE_BIG_ANIM_PARAMS
            )
            famComputeBigAnim = if (methodName != null && params != null) {
                findMethod(fam, methodName, *params)
            } else {
                fam.declaredMethods.singleOrNull { m ->
                    !Modifier.isStatic(m.modifiers) &&
                        m.returnType == Void.TYPE &&
                        m.parameterCount == 15 &&
                        m.parameterTypes[8] == Float::class.javaPrimitiveType &&
                        m.parameterTypes[10] == Float::class.javaPrimitiveType &&
                        m.parameterTypes[14] == AnimatorSet::class.java
                }?.apply { isAccessible = true }
                    ?: throw NoSuchMethodException("FolderAnimationManager.computeBigFolderItemAnim")
            }
            true
        } catch (t: Throwable) {
            logger.error("resolve FolderAnimationManager refs failed, item anim hook skipped", t)
            false
        }
    }

    private fun resolveResizeRefs(classLoader: ClassLoader): Boolean {
        if (!folderIconReady || !pimReady || fiBg == null || fiPreviewItemMgr == null) {
            return false
        }
        return try {
            val fi = classLoader.loadClass("com.android.launcher3.folder.FolderIcon")
            fiOnBeforeResize = findMethod(
                fi,
                "onBeforeResize",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            fiOnAfterResize = findMethod(fi, "onAfterResize")
            fiUpdateBgBlur = resolveOptionalMethod(fi, null, listOf("updateBgBlur"))
            fiTransformDuration = resolveOptionalField(fi, null, "TRANSFORM_DURATION")
            fiTransformInterpolator = resolveOptionalField(fi, null, "transformInterpolator")

            val pb = classLoader.loadClass("com.android.launcher3.folder.PreviewBackground")
            pbCellWidthBig = findField(pb, "cellWidthBig")
            pbCellHeightBig = pbCellHeightBig ?: findField(pb, "cellHeightBig")
            pbPreRect = findField(pb, "preFolderIconRect")
            pbDstRect = findField(pb, "dstFolderIconRect")
            pbOffsetL = findField(pb, "offsetL")
            pbOffsetT = findField(pb, "offsetT")
            pbOffsetR = findField(pb, "offsetR")
            pbOffsetB = findField(pb, "offsetB")
            pbUseBeforeBgBt = resolveOptionalField(pb, null, "useBeforeBgBt")

            val pim = classLoader.loadClass("com.android.launcher3.folder.PreviewItemManager")
            pimPreParams = findField(pim, "prePreviewItemParams")
            pimOffsetX = findField(pim, "offsetX")
            pimOffsetY = findField(pim, "offsetY")
            pimDstWidth = findField(pim, "dstFolderIconWidth")
            pimDstHeight = findField(pim, "dstFolderIconHeight")
            pimHandleAnim = resolveOptionalField(pim, null, "handleAnim")

            val paramsClass = if (ruleReady && ruleComputeIs9Args) {
                ruleCompute.parameterTypes[2]
            } else {
                classLoader.loadClass("com.android.launcher3.folder.PreviewItemDrawingParams")
            }
            paramStartTransX = findField(paramsClass, "startTransX")
            paramStartTransY = findField(paramsClass, "startTransY")
            paramOffsetTransX = findField(paramsClass, "offsetTransX")
            paramOffsetTransY = findField(paramsClass, "offsetTransY")
            paramResizeTransX = ruleParamsTransX ?: findField(paramsClass, "transX")
            paramResizeTransY = ruleParamsTransY ?: findField(paramsClass, "transY")
            paramResizeScale = ruleParamsScale ?: findField(paramsClass, "scale")
            true
        } catch (t: Throwable) {
            logger.error("resolve resize refs failed, resize anim hook skipped", t)
            false
        }
    }

    private fun hookSetupGeometry() {
        hookWithId(pbSetup, "big_folder_align_setup") { chain ->
            chain.proceed()
            try {
                val pb = chain.thisObject ?: return@hookWithId null
                val spanX = pbSpanX.getInt(pb)
                val spanY = pbSpanY.getInt(pb)
                val spec = resolveSpec(spanX, spanY) ?: return@hookWithId null

                val externalTitle = BigFolderExternalTitleSupport.isActive
                val stockOffsetX = pbOffsetX.getInt(pb).coerceAtLeast(0)
                val stockOffsetY = pbOffsetY.getInt(pb).coerceAtLeast(0)
                val stockW = pbPreviewSize.getInt(pb)
                val stockH = pbPreviewSizeY.getInt(pb)
                val view = chain.args.getOrNull(2) as? View
                // setupLp updates layoutParams before onAfterResize recomputes; measuredWidth is still stale.
                val slotW = view?.layoutParams?.width?.takeIf { it > 0 }
                    ?: view?.measuredWidth?.takeIf { it > 0 }
                    ?: (stockW + stockOffsetX * 2)
                val fallbackSlotH = pbCellHeightBig?.let {
                    runCatching { it.getInt(pb) * spanY }.getOrNull()
                }?.takeIf { it > 0 }
                val slotH = view?.layoutParams?.height?.takeIf { it > 0 }
                    ?: view?.measuredHeight?.takeIf { it > 0 }
                    ?: fallbackSlotH
                    ?: (stockH + stockOffsetY * 2)

                val context = chain.args.getOrNull(0) as? Context
                val dp = chain.args.getOrNull(1)?.let {
                    runCatching { acGetDeviceProfile?.invoke(it) }.getOrNull()
                }
                val density = context?.resources?.displayMetrics?.density
                    ?: view?.resources?.displayMetrics?.density
                    ?: 1f
                val verticalRegion = BigFolderExternalTitleSupport.computeVerticalRegion(
                    dp = dp,
                    page = LauncherGridMetrics.pageOf(view),
                    slotH = slotH,
                    spanY = spanY,
                    stockOffsetY = stockOffsetY,
                    density = density,
                    reserveBottomLabel = externalTitle
                )

                val (bgW, bgH) = computeBackgroundSize(
                    spec = spec,
                    slotW = slotW,
                    slotH = slotH,
                    stockOffsetX = stockOffsetX,
                    stockOffsetY = stockOffsetY,
                    availHOverride = verticalRegion.regionH,
                    twoRowAvailHOverride = verticalRegion.twoRowRegionH
                )
                val offsetX = (slotW - bgW) / 2
                val offsetY = verticalRegion.regionTop + (verticalRegion.regionH - bgH) / 2

                pbPreviewSize.setInt(pb, bgW)
                pbPreviewSizeY.setInt(pb, bgH)
                pbOffsetX.setInt(pb, offsetX)
                pbOffsetY.setInt(pb, offsetY)

                val logKey = "${spanX}x$spanY:${slotW}x$slotH->${bgW}x$bgH"
                if (loggedGeometry.add(logKey)) {
                    logger.debug(
                        "BigFolderAlign setup span=${spanX}x$spanY slot=${slotW}x$slotH " +
                            "stock=${stockW}x$stockH bg=${bgW}x$bgH offset=($offsetX,$offsetY)"
                    )
                }
            } catch (t: Throwable) {
                logger.error("PreviewBackground.setup rewrite failed", t)
            }
            null
        }
        logger.debug("hooked PreviewBackground.setup")
    }

    private fun computeBackgroundSize(
        spec: FolderStyleSpec,
        slotW: Int,
        slotH: Int,
        stockOffsetX: Int,
        stockOffsetY: Int,
        availHOverride: Int? = null,
        twoRowAvailHOverride: Int? = null
    ): Pair<Int, Int> {
        val availW = (slotW - stockOffsetX * 2).coerceAtLeast(1)
        val availH = availHOverride ?: (slotH - stockOffsetY * 2).coerceAtLeast(1)
        return when (spec.kind) {
            StyleKind.SQUARE -> {
                val side = minOf(availW, availH)
                side to side
            }
            StyleKind.STRIP_1X2 -> {
                // Match the optimized 2x2 square side on the vertical long edge.
                val side2x2 = minOf(availH, (slotW * 2 - stockOffsetX * 2).coerceAtLeast(1))
                availW to side2x2
            }
            StyleKind.STRIP_2X1 -> {
                // Match the optimized 2x2 square side on the horizontal long edge.
                val side2x2 = minOf(
                    availW,
                    twoRowAvailHOverride ?: (slotH * 2 - stockOffsetY * 2).coerceAtLeast(1)
                )
                side2x2 to availH
            }
            StyleKind.WIDE_3X2 -> {
                // Match the optimized 2x2 square height while keeping full 3-column width.
                val side2x2 = minOf(availH, ((slotW * 2) / 3 - stockOffsetX * 2).coerceAtLeast(1))
                availW to side2x2
            }
        }
    }

    private fun hookChildCountRewrite() {
        hookWithId(bfcGetChildCount, "big_folder_align_child_count") { chain ->
            try {
                val spanX = chain.args[0] as Int
                val spanY = chain.args[1] as Int
                val spec = resolveSpec(spanX, spanY) ?: return@hookWithId chain.proceed()
                val out = chain.args[2] as IntArray?
                if (suppressStackingForExactCapacity.get() == true) {
                    out?.set(0, spec.capacity + 1)
                    out?.set(1, 1)
                    return@hookWithId spec.capacity + 1
                }
                out?.set(0, spec.cols)
                out?.set(1, spec.rows)
                return@hookWithId spec.capacity
            } catch (t: Throwable) {
                logger.error("childCount rewrite failed", t)
                return@hookWithId chain.proceed()
            }
        }
        logger.debug("hooked getBigFolderIconChildCount")
    }

    private fun hookChildGridRule() {
        hookWithId(ruleCompute, "big_folder_align_child_grid") { chain ->
            val result = chain.proceed()
            try {
                val args = chain.args
                val index = args[0] as Int
                val isBigFolder: Boolean
                val spanX: Int
                val spanY: Int
                val availW: Int
                val availH: Int
                val padTop: Int
                if (ruleComputeIs9Args) {
                    isBigFolder = args[3] as Boolean
                    spanX = args[4] as Int
                    spanY = args[5] as Int
                    availW = args[6] as Int
                    availH = args[7] as Int
                    padTop = args[8] as Int
                } else {
                    isBigFolder = true
                    spanX = args[3] as Int
                    spanY = args[4] as Int
                    availW = args[5] as Int
                    availH = args[6] as Int
                    padTop = args[7] as Int
                }
                val spec = if (index >= 0 && isBigFolder) resolveSpec(spanX, spanY) else null
                if (spec == null) {
                    return@hookWithId if (ruleComputeIs9Args) result else null
                }

                val rule = chain.thisObject
                val boxW = if (availW > 0) availW.toFloat() else ruleBgWidth.getFloat(rule)
                val boxH = if (availH > 0) availH.toFloat() else ruleBgHeight.getFloat(rule)
                val iconSize = ruleIconSize.getFloat(rule)
                if (boxW <= 0f || boxH <= 0f || iconSize <= 0f) {
                    return@hookWithId if (ruleComputeIs9Args) result else null
                }

                val layout = computeGridLayout(spec, boxW, boxH, padTop)
                val scale = layout.iconPx / iconSize

                // Indices >= capacity stack in the final slot (bottom-right, bottom, or END side).
                val clampedIdx = if (index >= spec.capacity) spec.capacity - 1 else index
                val rawCol = clampedIdx % spec.cols
                val isRtl = TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) == View.LAYOUT_DIRECTION_RTL
                val col = if (isRtl) (spec.cols - 1 - rawCol) else rawCol
                val row = clampedIdx / spec.cols
                val newX = layout.gridLeft + col * (layout.iconPx + layout.gapX)
                val newY = layout.gridStartY + row * (layout.iconPx + layout.gapY)

                if (ruleComputeIs9Args) {
                    val drawingParams = result ?: args[2]
                    if (drawingParams != null) {
                        ruleParamsTransX?.setFloat(drawingParams, newX)
                        ruleParamsTransY?.setFloat(drawingParams, newY)
                        ruleParamsScale?.setFloat(drawingParams, scale)
                    }
                    return@hookWithId result
                } else {
                    val out = args[2] as FloatArray
                    out[0] = newX
                    out[1] = newY
                }
            } catch (t: Throwable) {
                logger.error("child grid rewrite failed", t)
            }
            if (ruleComputeIs9Args) result else null
        }
        logger.debug("hooked ClippedFolderIconLayoutRule computePreviewItemDrawingParams")
    }

    private fun computeGridLayout(spec: FolderStyleSpec, boxW: Float, boxH: Float, padTop: Int): GridLayout {
        return when (spec.kind) {
            StyleKind.SQUARE -> {
                val side = minOf(boxW, boxH)
                val outerPad = side * OUTER_PAD_RATIO
                val headerH = if (padTop > 0) (padTop * 2f).coerceIn(0f, side * MAX_HEADER_RATIO) else 0f
                val gridTop = maxOf(outerPad, headerH)
                val gridBottom = side - outerPad
                val availGridH = (gridBottom - gridTop).coerceAtLeast(side * MIN_GRID_HEIGHT_RATIO)
                val gap = availGridH * (GAP_RATIO / (1f - 2f * OUTER_PAD_RATIO))
                val iconPx = minOf(
                    (availGridH - (spec.rows - 1) * gap) / spec.rows,
                    (side - 2f * outerPad - (spec.cols - 1) * gap) / spec.cols
                ).coerceAtLeast(1f)
                val gridExtent = spec.cols * iconPx + (spec.cols - 1) * gap
                GridLayout(
                    iconPx = iconPx,
                    gapX = gap,
                    gapY = gap,
                    gridLeft = (boxW - gridExtent) / 2f,
                    gridStartY = (boxH - side) / 2f + gridTop + (availGridH - gridExtent) / 2f
                )
            }
            StyleKind.STRIP_1X2 -> {
                val shortSide = minOf(boxW, boxH)
                val minOuterPad = shortSide * STRIP_OUTER_PAD_RATIO
                val headerH = if (padTop > 0) (padTop * 2f).coerceIn(0f, boxH * MAX_HEADER_RATIO) else 0f
                val gridTop = maxOf(minOuterPad, headerH)
                val availGridH = (boxH - minOuterPad - gridTop).coerceAtLeast(boxH * MIN_GRID_HEIGHT_RATIO)
                val minGap = boxH * STRIP_MIN_GAP_RATIO
                val maxByShort = (boxW - 2f * minOuterPad).coerceAtLeast(1f)
                val maxByLong = ((availGridH - (spec.rows - 1) * minGap) / spec.rows).coerceAtLeast(1f)
                val iconPx = minOf(maxByShort, maxByLong).coerceAtLeast(1f)
                val maxGap = ((availGridH - spec.rows * iconPx) / (spec.rows - 1)).coerceAtLeast(0f)
                val spanH = if (padTop > 0) (boxH - gridTop) else boxH
                val evenGap = ((spanH - spec.rows * iconPx) / (spec.rows + 1)).coerceAtLeast(0f)
                val gapY = evenGap.coerceIn(minGap.coerceAtMost(maxGap), maxGap)
                val gridH = spec.rows * iconPx + (spec.rows - 1) * gapY
                val gridStartY = if (padTop > 0) gridTop + (availGridH - gridH) / 2f else (boxH - gridH) / 2f
                GridLayout(
                    iconPx = iconPx,
                    gapX = 0f,
                    gapY = gapY,
                    gridLeft = (boxW - iconPx) / 2f,
                    gridStartY = gridStartY
                )
            }
            StyleKind.STRIP_2X1 -> {
                val shortSide = minOf(boxW, boxH)
                val minOuterPad = shortSide * STRIP_OUTER_PAD_RATIO
                val headerH = if (padTop > 0) (padTop * 2f).coerceIn(0f, boxH * STRIP_2X1_MAX_HEADER_RATIO) else 0f
                val gridTop = maxOf(minOuterPad, headerH)
                val availGridH = (boxH - minOuterPad - gridTop).coerceAtLeast(boxH * MIN_GRID_HEIGHT_RATIO)
                val availGridW = (boxW - 2f * minOuterPad).coerceAtLeast(1f)
                val minGap = boxW * STRIP_MIN_GAP_RATIO
                val maxByShort = availGridH.coerceAtLeast(1f)
                val maxByLong = ((availGridW - (spec.cols - 1) * minGap) / spec.cols).coerceAtLeast(1f)
                val iconPx = minOf(maxByShort, maxByLong).coerceAtLeast(1f)
                val maxGap = ((availGridW - spec.cols * iconPx) / (spec.cols - 1)).coerceAtLeast(0f)
                val evenGap = ((boxW - spec.cols * iconPx) / (spec.cols + 1)).coerceAtLeast(0f)
                val gapX = evenGap.coerceIn(minGap.coerceAtMost(maxGap), maxGap)
                val gridW = spec.cols * iconPx + (spec.cols - 1) * gapX
                GridLayout(
                    iconPx = iconPx,
                    gapX = gapX,
                    gapY = 0f,
                    gridLeft = (boxW - gridW) / 2f,
                    gridStartY = gridTop + (availGridH - iconPx) / 2f
                )
            }
            StyleKind.WIDE_3X2 -> {
                val outerPad = boxH * OUTER_PAD_RATIO
                val headerH = if (padTop > 0) (padTop * 2f).coerceIn(0f, boxH * MAX_HEADER_RATIO) else 0f
                val gridTop = maxOf(outerPad, headerH)
                val availGridH = (boxH - outerPad - gridTop).coerceAtLeast(boxH * MIN_GRID_HEIGHT_RATIO)
                val gapY = availGridH * (GAP_RATIO / (1f - 2f * OUTER_PAD_RATIO))
                val maxByH = ((availGridH - (spec.rows - 1) * gapY) / spec.rows).coerceAtLeast(1f)
                val availGridW = (boxW - 2f * outerPad).coerceAtLeast(1f)
                val maxByW = ((availGridW - (spec.cols - 1) * gapY) / spec.cols).coerceAtLeast(1f)
                val iconPx = minOf(maxByH, maxByW).coerceAtLeast(1f)
                val maxGapX = ((availGridW - spec.cols * iconPx) / (spec.cols - 1)).coerceAtLeast(0f)
                val evenGapX = ((boxW - spec.cols * iconPx) / (spec.cols + 1)).coerceAtLeast(0f)
                val gapX = evenGapX.coerceIn(gapY.coerceAtMost(maxGapX), maxGapX)
                val gridW = spec.cols * iconPx + (spec.cols - 1) * gapX
                val gridH = spec.rows * iconPx + (spec.rows - 1) * gapY
                GridLayout(
                    iconPx = iconPx,
                    gapX = gapX,
                    gapY = gapY,
                    gridLeft = (boxW - gridW) / 2f,
                    gridStartY = gridTop + (availGridH - gridH) / 2f
                )
            }
        }
    }

    private fun hookExactCapacityStackThreshold() {
        hookWithId(pimDrawParams, "big_folder_align_draw_params") { chain ->
            val icon = runCatching { pimIcon.get(chain.thisObject) }.getOrNull()
            val list = chain.args.getOrNull(1) as? List<*>
            val spec = resolveIconSpec(icon)
            if (spec != null && list?.size == spec.capacity) {
                suppressStackingForExactCapacity.set(true)
                try {
                    return@hookWithId chain.proceed()
                } finally {
                    suppressStackingForExactCapacity.remove()
                }
            }
            chain.proceed()
        }

        hookWithId(pimCheckClick, "big_folder_align_check_click") { chain ->
            val icon = chain.args.getOrNull(0)
            val list = runCatching { pimFirstPage.get(chain.thisObject) as? List<*> }.getOrNull()
            val spec = resolveIconSpec(icon)
            if (spec != null && list?.size == spec.capacity) {
                suppressStackingForExactCapacity.set(true)
                try {
                    return@hookWithId chain.proceed()
                } finally {
                    suppressStackingForExactCapacity.remove()
                }
            }
            chain.proceed()
        }
        logger.debug("hooked PreviewItemManager exact-capacity threshold")
    }

    private fun hookFolderLabelAndPadding() {
        fiPadTopMethod?.let { padTopMethod ->
            hookWithId(padTopMethod, "big_folder_align_pad_top") { chain ->
                val icon = chain.thisObject
                if (icon != null && resolveIconSpec(icon) != null && !isLabelVisible(icon)) {
                    return@hookWithId 0
                }
                chain.proceed()
            }
            logger.debug("hooked FolderIcon.getBigFolderItemPaddingTop")
        }

        val labelMethod = fiLabelMethod
        val labelField = fiLabel
        val bgField = fiBg
        val topMarginField = fiNameTopMargin
        if (labelMethod != null && labelField != null && bgField != null && topMarginField != null) {
            hookWithId(labelMethod, "big_folder_align_label") { chain ->
                chain.proceed()
                if (BigFolderExternalTitleSupport.isActive) return@hookWithId null
                try {
                    val icon = chain.thisObject ?: return@hookWithId null
                    if (resolveIconSpec(icon) == null) return@hookWithId null
                    val bg = bgField.get(icon) ?: return@hookWithId null
                    val desiredTop = pbOffsetY.getInt(bg) + topMarginField.getInt(icon)
                    val label = labelField.get(icon) as? View ?: return@hookWithId null
                    val lp = label.layoutParams as? ViewGroup.MarginLayoutParams ?: return@hookWithId null
                    if (lp.topMargin != desiredTop) {
                        lp.topMargin = desiredTop
                        label.requestLayout()
                    }
                } catch (t: Throwable) {
                    logger.error("FolderIcon label margin sync failed", t)
                }
                null
            }
            logger.debug("hooked FolderIcon.setFolderNameMargin")
        }
    }

    private fun hookBigFolderItemAnim() {
        hookWithId(famComputeBigAnim, "big_folder_align_item_anim") { chain ->
            try {
                val fam = chain.thisObject ?: return@hookWithId chain.proceed()
                val icon = famFolderIcon.get(fam)
                val spec = resolveIconSpec(icon) ?: return@hookWithId chain.proceed()

                val tmpParams = famTmpParams.get(fam) ?: return@hookWithId chain.proceed()
                val hookedScale = ruleParamsScale?.getFloat(tmpParams) ?: 0f
                if (hookedScale <= 0f) return@hookWithId chain.proceed()

                val args: Array<Any?> = chain.args.toTypedArray()
                val newScaleFolder = args[10] as Float
                val intrinsicSize = fiLayoutRule?.get(icon)?.let { ruleIconSize.getFloat(it) } ?: 0f
                val childIconSize = fiPreviewItemMgr?.get(icon)?.let { pimIconSize?.getInt(it) } ?: 0
                val sizeRatio = if (intrinsicSize > 0f && childIconSize > 0) {
                    intrinsicSize / childIconSize.toFloat()
                } else {
                    1f
                }
                args[8] = hookedScale * sizeRatio * newScaleFolder

                // Disable end-slot close shrinkage/fade when item count matches grid capacity.
                val animItemCount = args[1] as Int
                val stackThreshold = args[2] as Int
                if (animItemCount == spec.capacity && stackThreshold == spec.capacity) {
                    args[2] = spec.capacity + 1
                }
                return@hookWithId chain.proceed(args)
            } catch (t: Throwable) {
                logger.error("computeBigFolderItemAnim override failed", t)
                return@hookWithId chain.proceed()
            }
        }
        logger.debug("hooked FolderAnimationManager.computeBigFolderItemAnim")
    }

    private fun hookResizeAnimation() {
        hookWithId(fiOnBeforeResize, "big_folder_align_before_resize") { chain ->
            chain.proceed()
            try {
                val icon = chain.thisObject ?: return@hookWithId null
                val dstSpanX = chain.args[0] as Int
                val dstSpanY = chain.args[1] as Int
                val info = fiInfo.get(icon) ?: return@hookWithId null
                val bg = fiBg?.get(icon) ?: return@hookWithId null
                val pim = fiPreviewItemMgr?.get(icon) ?: return@hookWithId null

                val beforeSpanX = fiSpanX.getInt(info)
                val beforeSpanY = fiSpanY.getInt(info)
                val preSlot = pbPreRect.get(bg) as? Rect ?: return@hookWithId null

                val preBgW = pbPreviewSize.getInt(bg)
                val preBgH = if (beforeSpanX > 1 || beforeSpanY > 1) {
                    pbPreviewSizeY.getInt(bg)
                } else {
                    preBgW
                }
                val preLeft = preSlot.left + pbOffsetX.getInt(bg)
                val preTop = preSlot.top + pbOffsetY.getInt(bg)
                val preRight = preLeft + preBgW
                val preBottom = preTop + preBgH
                preResizeBgSnapshots[icon] = PreResizeBgSnapshot(
                    beforeSpanX = beforeSpanX,
                    beforeSpanY = beforeSpanY,
                    left = preLeft,
                    top = preTop,
                    right = preRight,
                    bottom = preBottom
                )

                val dstSpec = resolveSpec(dstSpanX, dstSpanY) ?: return@hookWithId null
                val stockDstW = pimDstWidth.getInt(pim)
                val stockDstH = pimDstHeight.getInt(pim)
                val cellW = pbCellWidthBig.getInt(bg)
                val cellH = pbCellHeightBig?.getInt(bg) ?: 0
                val dstSlotW = (cellW * dstSpanX).takeIf { it > 0 } ?: stockDstW
                val dstSlotH = (cellH * dstSpanY).takeIf { it > 0 } ?: stockDstH
                val stockOffsetX = ((dstSlotW - stockDstW) / 2).coerceAtLeast(0)
                val stockOffsetY = ((dstSlotH - stockDstH) / 2).coerceAtLeast(0)
                val iconView = icon as? View
                val dp = fiActivity?.let { runCatching { it.get(icon) }.getOrNull() }
                    ?.let { runCatching { acGetDeviceProfile?.invoke(it) }.getOrNull() }
                val dstRegion = BigFolderExternalTitleSupport.computeVerticalRegion(
                    dp = dp,
                    page = LauncherGridMetrics.pageOf(iconView),
                    slotH = dstSlotH,
                    spanY = dstSpanY,
                    stockOffsetY = stockOffsetY,
                    density = iconView?.resources?.displayMetrics?.density ?: 1f,
                    reserveBottomLabel = BigFolderExternalTitleSupport.isActive
                )
                val (dstBgW, dstBgH) = computeBackgroundSize(
                    dstSpec,
                    dstSlotW,
                    dstSlotH,
                    stockOffsetX,
                    stockOffsetY,
                    availHOverride = dstRegion.regionH,
                    twoRowAvailHOverride = dstRegion.twoRowRegionH
                )
                pimDstWidth.setInt(pim, dstBgW)
                pimDstHeight.setInt(pim, dstBgH)
            } catch (t: Throwable) {
                logger.error("FolderIcon.onBeforeResize override failed", t)
            }
            null
        }

        hookWithId(fiOnAfterResize, "big_folder_align_after_resize") { chain ->
            chain.proceed()
            try {
                val icon = chain.thisObject ?: return@hookWithId null
                val pre = preResizeBgSnapshots.remove(icon) ?: return@hookWithId null
                val info = fiInfo.get(icon) ?: return@hookWithId null
                val bg = fiBg?.get(icon) ?: return@hookWithId null
                val pim = fiPreviewItemMgr?.get(icon) ?: return@hookWithId null

                val dstSpanX = fiSpanX.getInt(info)
                val dstSpanY = fiSpanY.getInt(info)
                if (resolveSpec(pre.beforeSpanX, pre.beforeSpanY) == null &&
                    resolveSpec(dstSpanX, dstSpanY) == null
                ) {
                    return@hookWithId null
                }

                val dstSlot = pbDstRect.get(bg) as? Rect ?: return@hookWithId null
                val dstBgW = pbPreviewSize.getInt(bg)
                val dstBgH = if (dstSpanX > 1 || dstSpanY > 1) {
                    pbPreviewSizeY.getInt(bg)
                } else {
                    dstBgW
                }
                val dstLeft = dstSlot.left + pbOffsetX.getInt(bg)
                val dstTop = dstSlot.top + pbOffsetY.getInt(bg)
                val dstRight = dstLeft + dstBgW
                val dstBottom = dstTop + dstBgH

                val newOffsetL = pre.left - dstLeft
                val newOffsetT = pre.top - dstTop
                val newOffsetR = pre.right - dstRight
                val newOffsetB = pre.bottom - dstBottom

                pbOffsetL.setInt(bg, newOffsetL)
                pbOffsetT.setInt(bg, newOffsetT)
                pbOffsetR.setInt(bg, newOffsetR)
                pbOffsetB.setInt(bg, newOffsetB)
                pimOffsetX.setInt(pim, newOffsetL)
                pimOffsetY.setInt(pim, newOffsetT)

                if (newOffsetL > 0 || newOffsetT > 0 || newOffsetR < 0 || newOffsetB < 0) {
                    pbUseBeforeBgBt?.setBoolean(bg, false)
                }

                (fiLabel?.get(icon) as? View)?.let { label ->
                    if (BigFolderExternalTitleSupport.isActive) {
                        label.translationY = newOffsetB.toFloat()
                        label.translationX = (newOffsetL + newOffsetR) / 2f
                    } else {
                        label.translationY = newOffsetT.toFloat()
                        label.translationX = when {
                            newOffsetL != 0 -> newOffsetL / 2f
                            newOffsetR != 0 -> newOffsetR / 2f
                            else -> 0f
                        }
                    }
                }

                val preParams = pimPreParams.get(pim) as? List<*>
                val firstParams = pimFirstPage.get(pim) as? List<*>
                activeScaleAnimators.remove(icon)?.cancel()
                if (!preParams.isNullOrEmpty() && !firstParams.isNullOrEmpty()) {
                    val startScales = FloatArray(preParams.size)
                    val targetScales = FloatArray(preParams.size)
                    var scaleChanges = false
                    for (i in preParams.indices) {
                        val p = preParams[i] ?: continue
                        val target = firstParams[minOf(i, firstParams.lastIndex)] ?: continue
                        val startX = paramStartTransX.getFloat(p)
                        val startY = paramStartTransY.getFloat(p)
                        val targetX = paramResizeTransX.getFloat(target)
                        val targetY = paramResizeTransY.getFloat(target)
                        paramOffsetTransX.setFloat(p, targetX - startX)
                        paramOffsetTransY.setFloat(p, targetY - startY)
                        paramResizeTransX.setFloat(p, startX + newOffsetL)
                        paramResizeTransY.setFloat(p, startY + newOffsetT)

                        val startScale = paramResizeScale.getFloat(p)
                        val targetScale = paramResizeScale.getFloat(target)
                        startScales[i] = startScale
                        targetScales[i] = targetScale
                        if (abs(targetScale - startScale) > 0.001f) {
                            scaleChanges = true
                        }
                    }

                    if (scaleChanges) {
                        val animParams = ArrayList(preParams)
                        val duration = runCatching {
                            fiTransformDuration?.getInt(null)?.takeIf { it > 0 }?.toLong()
                        }.getOrNull() ?: DEFAULT_RESIZE_DURATION_MS
                        val interp = runCatching {
                            fiTransformInterpolator?.get(icon) as? TimeInterpolator
                        }.getOrNull()
                        val iconView = icon as? View
                        val scaleAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                            setDuration(duration)
                            if (interp != null) interpolator = interp
                            addUpdateListener { anim ->
                                val running = pimHandleAnim?.let {
                                    runCatching { it.getBoolean(pim) }.getOrNull()
                                }
                                if (running == false) {
                                    anim.cancel()
                                    return@addUpdateListener
                                }
                                val frac = anim.animatedValue as Float
                                for (i in animParams.indices) {
                                    val p = animParams[i] ?: continue
                                    val scale = startScales[i] + (targetScales[i] - startScales[i]) * frac
                                    runCatching { paramResizeScale.setFloat(p, scale) }
                                }
                                iconView?.invalidate()
                            }
                        }
                        activeScaleAnimators[icon] = scaleAnim
                        scaleAnim.start()
                    }
                }

                runCatching { fiUpdateBgBlur?.invoke(icon) }
                (icon as? View)?.invalidate()
            } catch (t: Throwable) {
                logger.error("FolderIcon.onAfterResize override failed", t)
            }
            null
        }
        logger.debug("hooked FolderIcon resize transitions")
    }

    private fun resolveSpec(spanX: Int, spanY: Int): FolderStyleSpec? = when {
        squareStyleEnabled && spanX == spanY && spanX >= 2 -> SQUARE_SPEC
        stripStyleEnabled && spanX == 1 && spanY == 2 -> STRIP_1X2_SPEC
        stripStyleEnabled && spanX == 2 && spanY == 1 -> STRIP_2X1_SPEC
        wideStyleEnabled && spanX == 3 && spanY == 2 -> WIDE_3X2_SPEC
        else -> null
    }

    private fun resolveIconSpec(icon: Any?): FolderStyleSpec? {
        if (icon == null || !folderIconReady) return null
        val info = fiInfo.get(icon) ?: return null
        return resolveSpec(fiSpanX.getInt(info), fiSpanY.getInt(info))
    }

    private fun isLabelVisible(icon: Any): Boolean {
        val label = fiLabel?.get(icon) as? View ?: return false
        if (label.visibility != View.VISIBLE || label.alpha <= 0.01f) return false
        if (label is TextView && label.text.isNullOrEmpty()) return false
        return true
    }

    private fun resolveLabelField(classLoader: ClassLoader, fi: Class<*>, module: JsonObject?): Field? {
        val candidates = buildList {
            readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_LABEL_FIELD)?.let { add(it) }
            add("mFolderName")
            add("folderBubbleTextView")
            add("e")
        }
        candidates.firstNotNullOfOrNull { name ->
            runCatching { findField(fi, name) }.getOrNull()
        }?.let { return it }

        val bubbleClass = runCatching {
            classLoader.loadClass("com.android.launcher3.BubbleTextView")
        }.getOrNull() ?: return null
        return fi.declaredFields
            .firstOrNull { bubbleClass.isAssignableFrom(it.type) }
            ?.apply { isAccessible = true }
    }

    private fun resolveFirstField(owner: Class<*>, indexed: String?, vararg fallbacks: String): Field {
        if (indexed != null) return findField(owner, indexed)
        for (name in fallbacks) {
            runCatching { return findField(owner, name) }
        }
        throw NoSuchFieldException("${fallbacks.joinToString()} in $owner")
    }

    private fun resolveOptionalField(owner: Class<*>, indexed: String?, vararg fallbacks: String): Field? {
        if (indexed != null) return runCatching { findField(owner, indexed) }.getOrNull()
        for (name in fallbacks) {
            runCatching { return findField(owner, name) }
        }
        return null
    }

    private fun resolveOptionalMethod(owner: Class<*>, indexed: String?, fallbacks: List<String>): Method? {
        if (indexed != null) return runCatching { findMethod(owner, indexed) }.getOrNull()
        for (name in fallbacks) {
            runCatching { return findMethod(owner, name) }
        }
        return null
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
        private val SQUARE_SPEC = FolderStyleSpec(StyleKind.SQUARE, 3, 3)
        private val STRIP_1X2_SPEC = FolderStyleSpec(StyleKind.STRIP_1X2, 1, 3)
        private val STRIP_2X1_SPEC = FolderStyleSpec(StyleKind.STRIP_2X1, 3, 1)
        private val WIDE_3X2_SPEC = FolderStyleSpec(StyleKind.WIDE_3X2, 4, 3)

        // Proportional 3x3 layout parameters inside the square background.
        private const val OUTER_PAD_RATIO = 0.0475f
        private const val GAP_RATIO = 0.025f
        private const val MAX_HEADER_RATIO = 0.30f
        private const val MIN_GRID_HEIGHT_RATIO = 0.45f

        // 0.075 edge padding leaves 0.85 * shortSide for the strip icon diameter.
        private const val STRIP_OUTER_PAD_RATIO = 0.075f
        private const val STRIP_MIN_GAP_RATIO = 0.025f
        private const val STRIP_2X1_MAX_HEADER_RATIO = 0.36f
        private const val DEFAULT_RESIZE_DURATION_MS = 300L

        private val loggedGeometry: MutableSet<String> =
            java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    }
}
