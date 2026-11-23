package com.qimian233.ztool.hook.modules.launcher.grid

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.gson.JsonObject
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.dexindex.base.DexIndexConstants
import com.qimian233.ztool.hook.base.AppHookModule
import com.qimian233.ztool.hook.base.DexIndexStore
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * Vertical envelope and label-gap math shared with `BigFolderAlignHook`.
 *
 * Keeps collapsed big-folder backgrounds within the bottom row's visual icon bounds
 * so an external bottom label fits cleanly beneath them without overlapping neighboring rows.
 * See docs/research/launcher_big_folder_external_title.md.
 */
internal object BigFolderExternalTitleSupport {

    private const val FALLBACK_LABEL_GAP_DP = 8f
    private const val FALLBACK_TEXT_HEIGHT_DP = 16f
    private const val FALLBACK_LABEL_BAND_DP = 26f

    @Volatile
    var isActive: Boolean = false

    internal data class VerticalRegion(
        val regionTop: Int,
        val regionBottom: Int,
        val labelGap: Int,
        val twoRowRegionH: Int
    ) {
        val regionH: Int get() = (regionBottom - regionTop).coerceAtLeast(1)
    }

    /** Gap from a solid folder background bottom edge to the top of its external label. */
    internal fun computeLabelGap(dp: Any?, density: Float): Int {
        val iconSizePx = LauncherGridMetrics.iconSizePx(dp)
        val drawablePaddingPx = LauncherGridMetrics.iconDrawablePaddingPx(dp)
        if (iconSizePx != null && iconSizePx > 0 && drawablePaddingPx != null) {
            val folderSize = LauncherGridMetrics.folderIconSizePx(dp)
                ?.takeIf { it in 1..iconSizePx } ?: iconSizePx
            val folderOffsetY = LauncherGridMetrics.folderIconOffsetYPx(dp)
                ?.takeIf { it >= 0 && it + folderSize <= iconSizePx }
                ?: ((iconSizePx - folderSize) / 2).coerceAtLeast(0)
            return ((iconSizePx + drawablePaddingPx) - (folderOffsetY + folderSize))
                .coerceAtLeast(drawablePaddingPx.coerceAtLeast(1))
        }
        return (density * FALLBACK_LABEL_GAP_DP).toInt().coerceAtLeast(1)
    }

    /** Computes the background vertical envelope `[regionTop, regionBottom]` for a folder slot. */
    internal fun computeVerticalRegion(
        dp: Any?,
        page: View?,
        slotH: Int,
        spanY: Int,
        stockOffsetY: Int,
        density: Float,
        reserveBottomLabel: Boolean = true
    ): VerticalRegion {
        val metrics = LauncherGridMetrics.fromPage(page, dp) ?: LauncherGridMetrics.cached
        val iconSizePx = metrics?.iconSizePx ?: LauncherGridMetrics.iconSizePx(dp) ?: 0
        val drawablePaddingPx =
            metrics?.iconDrawablePaddingPx ?: LauncherGridMetrics.iconDrawablePaddingPx(dp)
        val folderSize = metrics?.folderIconSizePx?.takeIf { it > 0 }
            ?: LauncherGridMetrics.folderIconSizePx(dp)
        val folderOffsetY = LauncherGridMetrics.folderIconOffsetYPx(dp)
        val textHeightPx = LauncherGridMetrics.textHeightPx(dp)

        if (iconSizePx > 0) {
            val effectiveFolderSize = folderSize?.takeIf { it in 1..iconSizePx } ?: iconSizePx
            val effectiveOffsetY = folderOffsetY
                ?.takeIf { it >= 0 && it + effectiveFolderSize <= iconSizePx }
                ?: ((iconSizePx - effectiveFolderSize) / 2).coerceAtLeast(0)
            val labelGap = computeLabelGap(dp, density)
            val textH = textHeightPx ?: (density * FALLBACK_TEXT_HEIGHT_DP).toInt()
            val contentH = metrics?.contentHeightPx
                ?: (iconSizePx + (drawablePaddingPx ?: 0) + textH)
            val safeSpanY = spanY.coerceAtLeast(1)
            val cellH = metrics?.cellHeight?.takeIf { it > 0 } ?: (slotH / safeSpanY)
            val borderY = metrics?.borderY ?: 0
            val cellPadTop = LauncherGridMetrics.cellYPaddingPx(dp)?.takeIf { it >= 0 }
                ?: ((cellH - contentH) / 2).coerceAtLeast(0)

            val lastRowTop = (slotH - cellH).coerceAtLeast(0)
            val iconVisualTop = (cellPadTop + effectiveOffsetY).coerceAtLeast(0)
            val iconVisualBottom = lastRowTop + iconVisualTop + effectiveFolderSize
            val maxBgBottom = if (reserveBottomLabel) {
                (slotH - textH - labelGap).coerceAtLeast(iconVisualTop + 1)
            } else {
                slotH.coerceAtLeast(iconVisualTop + 1)
            }
            val regionTop = iconVisualTop.coerceAtMost(maxBgBottom - 1).coerceAtLeast(0)
            val regionBottom = iconVisualBottom.coerceAtMost(maxBgBottom).coerceAtLeast(regionTop + 1)

            val cellPitchY = (cellH + borderY).coerceAtLeast(cellH)
            val twoRowSlotH = cellH * 2 + borderY
            val twoRowMaxBottom = if (reserveBottomLabel) {
                (twoRowSlotH - textH - labelGap).coerceAtLeast(regionTop + 1)
            } else {
                twoRowSlotH.coerceAtLeast(regionTop + 1)
            }
            val twoRowBottom = (cellPitchY + iconVisualTop + effectiveFolderSize)
                .coerceAtMost(twoRowMaxBottom)
            val twoRowRegionH = (twoRowBottom - regionTop).coerceAtLeast(1)
            return VerticalRegion(regionTop, regionBottom, labelGap, twoRowRegionH)
        }

        val labelGap = (density * FALLBACK_LABEL_GAP_DP).toInt().coerceAtLeast(1)
        val regionTop = stockOffsetY.coerceAtLeast(0)
        if (!reserveBottomLabel) {
            val regionBottom = (slotH - regionTop).coerceAtLeast(regionTop + 1)
            val twoRowH = (slotH * 2 / spanY.coerceAtLeast(1) - regionTop * 2).coerceAtLeast(1)
            return VerticalRegion(regionTop, regionBottom, labelGap, twoRowH)
        }
        val labelBand = (density * FALLBACK_LABEL_BAND_DP).toInt().coerceAtLeast(labelGap + 1)
        val regionBottom = (slotH - labelBand).coerceAtLeast(regionTop + 1)
        val twoRowH = (slotH * 2 / spanY.coerceAtLeast(1) - labelBand - regionTop).coerceAtLeast(1)
        return VerticalRegion(regionTop, regionBottom, labelGap, twoRowH)
    }
}

/**
 * Moves collapsed big-folder titles outside beneath the folder background as a
 * standard BubbleTextView matching 1x1 small folders and desktop app icons.
 * See docs/research/launcher_big_folder_external_title.md.
 */
@SuppressLint("PrivateApi")
class BigFolderExternalTitleHook : AppHookModule() {

    private data class PreResizeBgSnapshot(
        val beforeSpanX: Int,
        val beforeSpanY: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    override fun getModuleName(): String = PreferenceKeys.LAUNCHER_BIG_FOLDER_EXTERNAL_TITLE.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.LAUNCHER.packageName)

    private lateinit var pbSpanX: Field
    private lateinit var pbSpanY: Field
    private lateinit var pbPreviewSize: Field
    private lateinit var pbPreviewSizeY: Field
    private lateinit var pbOffsetX: Field
    private lateinit var pbOffsetY: Field
    private lateinit var pbSetup: Method
    private var pbCellHeightBig: Field? = null
    private lateinit var acGetDeviceProfile: Method

    private lateinit var fiInfo: Field
    private lateinit var fiSpanX: Field
    private lateinit var fiSpanY: Field
    private var fiBg: Field? = null
    private var fiLabel: Field? = null
    private var fiActivity: Field? = null
    private var fiPreviewItemMgr: Field? = null
    private var fiLabelMethod: Method? = null
    private var fiPadTopMethod: Method? = null
    private var fiSetNameSizeMethod: Method? = null
    private var fiSetNameScaleMethod: Method? = null
    private var fiSetIconMarginMethod: Method? = null
    private var fiSetNameColorMethod: Method? = null
    private var fiUpdateBgBlur: Method? = null

    // Optional resize-transition members
    private var fiOnBeforeResize: Method? = null
    private var fiOnAfterResize: Method? = null
    private lateinit var pbPreRect: Field
    private lateinit var pbDstRect: Field
    private lateinit var pbOffsetL: Field
    private lateinit var pbOffsetT: Field
    private lateinit var pbOffsetR: Field
    private lateinit var pbOffsetB: Field
    private var pbUseBeforeBgBt: Field? = null
    private lateinit var pimOffsetX: Field
    private lateinit var pimOffsetY: Field
    private lateinit var pimDstHeight: Field
    private var resizeLabelOnUpdate: Method? = null
    private var resizeLabelPbField: Field? = null
    private var resizeLabelFiField: Field? = null

    // Optional open/close title fade members
    private var famCreateAnimatorSet: Method? = null
    private var famFolderField: Field? = null
    private var folderNameAnimFlagField: Field? = null
    private var folderNameDelegateField: Field? = null

    private val preResizeBgSnapshots = WeakHashMap<Any, PreResizeBgSnapshot>()

    @Throws(Throwable::class)
    override fun handleLoadPackage(param: PackageLoadedParam) {
        val prefs = runCatching { xposed.getRemotePreferences(PREFS_NAME) }.getOrNull()
        val noLabelMode = runCatching {
            prefs?.getBoolean(
                PreferenceKeys.LAUNCHER_NO_LABEL_MODE.name,
                PreferenceKeys.LAUNCHER_NO_LABEL_MODE.default
            )
        }.getOrNull() ?: PreferenceKeys.LAUNCHER_NO_LABEL_MODE.default
        if (noLabelMode) {
            BigFolderExternalTitleSupport.isActive = false
            logger.debug("BigFolderExternalTitle: LauncherNoLabelMode is enabled, skipping")
            return
        }

        val classLoader = param.defaultClassLoader
        LauncherGridMetrics.install(classLoader, logger, xposed)

        val modules = DexIndexStore.lookup(xposed, ScopeKeys.LAUNCHER.packageName)
            ?.getAsJsonObject(DexIndexConstants.JSON_MODULES)
        val extModule = modules?.getAsJsonObject(DexIndexConstants.ModuleKeys.LAUNCHER_BIG_FOLDER_EXTERNAL_TITLE)
        val alignModule = modules?.getAsJsonObject(DexIndexConstants.ModuleKeys.LAUNCHER_BIG_FOLDER_ALIGN)
        val noLabelModule = modules?.getAsJsonObject(DexIndexConstants.ModuleKeys.LAUNCHER_NO_LABEL_MODE)

        if (!resolveCoreRefs(classLoader, alignModule)) {
            BigFolderExternalTitleSupport.isActive = false
            return
        }
        if (!resolveFolderIconRefs(classLoader, extModule, alignModule)) {
            BigFolderExternalTitleSupport.isActive = false
            return
        }
        val resizeReady = resolveResizeRefs(classLoader, extModule, alignModule)
        val animReady = resolveAnimRefs(classLoader, noLabelModule)

        BigFolderExternalTitleSupport.isActive = true

        hookPreviewBackgroundSetup()
        hookFolderIconLabelStyling()
        hookFolderIconLabelMargin()
        if (animReady) hookTitleFadeAnim()
        if (resizeReady) hookResizeTransitions()
        logger.info("BigFolderExternalTitleHook installed (resize=$resizeReady anim=$animReady)")
    }

    private fun resolveCoreRefs(classLoader: ClassLoader, alignModule: JsonObject?): Boolean {
        return try {
            val pb = classLoader.loadClass("com.android.launcher3.folder.PreviewBackground")
            val ac = classLoader.loadClass("com.android.launcher3.views.ActivityContext")
            pbSpanX = findField(pb, "spanX")
            pbSpanY = findField(pb, "spanY")
            pbPreviewSize = resolveFirstField(
                pb,
                readString(alignModule, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_PB_PREVIEW_SIZE_FIELD),
                "previewSize",
                "o"
            )
            pbPreviewSizeY = findField(
                pb,
                readString(alignModule, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_PB_PREVIEW_SIZE_Y_FIELD)
                    ?: "previewSizeY"
            )
            pbOffsetX = resolveFirstField(
                pb,
                readString(alignModule, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_PB_OFFSET_X_FIELD),
                "basePreviewOffsetX",
                "p"
            )
            pbOffsetY = resolveFirstField(
                pb,
                readString(alignModule, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_PB_OFFSET_Y_FIELD),
                "basePreviewOffsetY",
                "q"
            )
            pbCellHeightBig = runCatching { findField(pb, "cellHeightBig") }.getOrNull()
            pbSetup = findMethod(
                pb,
                "setup",
                Context::class.java,
                ac,
                View::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            acGetDeviceProfile = ac.getMethod("getDeviceProfile")
            true
        } catch (t: Throwable) {
            logger.error("resolve core refs failed, BigFolderExternalTitle disabled", t)
            false
        }
    }

    private fun resolveFolderIconRefs(
        classLoader: ClassLoader,
        extModule: JsonObject?,
        alignModule: JsonObject?
    ): Boolean {
        return try {
            val fi = classLoader.loadClass("com.android.launcher3.folder.FolderIcon")
            fiInfo = resolveFirstField(
                fi,
                readString(alignModule, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_INFO_FIELD),
                "mInfo"
            )
            fiSpanX = findField(fiInfo.type, "spanX")
            fiSpanY = findField(fiInfo.type, "spanY")
            fiBg = resolveOptionalField(
                fi,
                readString(alignModule, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_BG_FIELD),
                "mBackground"
            )
            fiPreviewItemMgr = resolveOptionalField(
                fi,
                readString(alignModule, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_PIM_FIELD),
                "mPreviewItemManager"
            )
            fiLabel = resolveFieldByType(
                fi,
                readString(alignModule, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_LABEL_FIELD),
                "com.android.launcher3.BubbleTextView",
                classLoader,
                "mFolderName",
                "folderBubbleTextView",
                "e"
            )
            fiActivity = resolveFieldByType(
                fi,
                readString(extModule, DexIndexConstants.Keys.BIG_FOLDER_EXT_TITLE_FI_ACTIVITY_FIELD),
                "com.android.launcher3.views.ActivityContext",
                classLoader,
                "mActivity"
            )
            fiLabelMethod = resolveOptionalMethod(
                fi,
                readString(alignModule, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_LABEL_METHOD),
                listOf("setFolderNameMargin", "z")
            )
            fiPadTopMethod = resolveOptionalMethod(
                fi,
                readString(alignModule, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_PAD_TOP_METHOD),
                listOf("getBigFolderItemPaddingTop")
            )
            fiSetNameSizeMethod = resolveOptionalMethod(
                fi,
                readString(extModule, DexIndexConstants.Keys.BIG_FOLDER_EXT_TITLE_FI_SET_NAME_SIZE_METHOD),
                listOf("setNameSize")
            )
            fiSetNameScaleMethod = resolveOptionalMethod(
                fi,
                readString(extModule, DexIndexConstants.Keys.BIG_FOLDER_EXT_TITLE_FI_SET_NAME_SCALE_METHOD),
                listOf("setFolderNameScale")
            )
            fiSetIconMarginMethod = resolveOptionalMethod(
                fi,
                readString(extModule, DexIndexConstants.Keys.BIG_FOLDER_EXT_TITLE_FI_SET_ICON_MARGIN_METHOD),
                listOf("setFolderIconMargin")
            )
            fiSetNameColorMethod = resolveOptionalMethodWithParams(
                fi,
                readString(extModule, DexIndexConstants.Keys.BIG_FOLDER_EXT_TITLE_FI_SET_NAME_COLOR_METHOD),
                listOf("setFolderNameTextColor"),
                Int::class.javaPrimitiveType
            )
            fiUpdateBgBlur = resolveOptionalMethod(fi, null, listOf("updateBgBlur"))
            true
        } catch (t: Throwable) {
            logger.error("resolve FolderIcon refs failed, BigFolderExternalTitle disabled", t)
            false
        }
    }

    private fun resolveResizeRefs(
        classLoader: ClassLoader,
        extModule: JsonObject?,
        alignModule: JsonObject?
    ): Boolean {
        if (fiBg == null || fiPreviewItemMgr == null) return false
        return try {
            val fi = classLoader.loadClass("com.android.launcher3.folder.FolderIcon")
            fiOnBeforeResize = findMethod(
                fi,
                "onBeforeResize",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            fiOnAfterResize = findMethod(fi, "onAfterResize")

            val pb = classLoader.loadClass("com.android.launcher3.folder.PreviewBackground")
            pbPreRect = findField(pb, "preFolderIconRect")
            pbDstRect = findField(pb, "dstFolderIconRect")
            pbOffsetL = findField(pb, "offsetL")
            pbOffsetT = findField(pb, "offsetT")
            pbOffsetR = findField(pb, "offsetR")
            pbOffsetB = findField(pb, "offsetB")
            pbUseBeforeBgBt = runCatching { findField(pb, "useBeforeBgBt") }.getOrNull()

            val pimClass = fiPreviewItemMgr?.type
                ?: classLoader.loadClass("com.android.launcher3.folder.PreviewItemManager")
            pimOffsetX = findField(pimClass, "offsetX")
            pimOffsetY = findField(pimClass, "offsetY")
            pimDstHeight = findField(pimClass, "dstFolderIconHeight")

            val listenerName = readString(
                extModule,
                DexIndexConstants.Keys.BIG_FOLDER_EXT_TITLE_RESIZE_LABEL_LISTENER_CLASS
            ) ?: "com.android.launcher3.folder.y1"
            val listenerClass = runCatching { classLoader.loadClass(listenerName) }.getOrNull()
            if (listenerClass != null) {
                resizeLabelOnUpdate = runCatching {
                    findMethod(listenerClass, "onAnimationUpdate", ValueAnimator::class.java)
                }.getOrNull()
                resizeLabelPbField = resolveFieldByType(
                    listenerClass,
                    readString(extModule, DexIndexConstants.Keys.BIG_FOLDER_EXT_TITLE_RESIZE_LABEL_PB_FIELD),
                    pb.name,
                    classLoader
                )
                resizeLabelFiField = resolveFieldByType(
                    listenerClass,
                    readString(extModule, DexIndexConstants.Keys.BIG_FOLDER_EXT_TITLE_RESIZE_LABEL_FI_FIELD),
                    fi.name,
                    classLoader
                )
            }
            if (alignModule == null) {
                logger.debug("BigFolderExternalTitle: resize resolved with fallback names")
            }
            true
        } catch (t: Throwable) {
            logger.warn("resolve resize refs failed, resize label tracking skipped: ${t.message}")
            false
        }
    }

    private fun resolveAnimRefs(classLoader: ClassLoader, noLabelModule: JsonObject?): Boolean {
        return try {
            val famClass = classLoader.loadClass("com.android.launcher3.folder.FolderAnimationManager")
            val folderClass = classLoader.loadClass("com.android.launcher3.folder.Folder")
            val createSetName =
                readString(noLabelModule, DexIndexConstants.Keys.NO_LABEL_FOLDER_ANIM_CREATE_SET_METHOD)
                    ?: "createAnimatorSet"
            famCreateAnimatorSet = findMethod(
                famClass,
                createSetName,
                Boolean::class.javaPrimitiveType
            )
            folderNameAnimFlagField = runCatching {
                findField(
                    folderClass,
                    readString(noLabelModule, DexIndexConstants.Keys.NO_LABEL_FOLDER_NAME_ANIM_FLAG_FIELD)
                        ?: "ENBALE_NAMR_ANIM"
                )
            }.getOrNull() ?: return false
            famFolderField = runCatching {
                findField(
                    famClass,
                    readString(noLabelModule, DexIndexConstants.Keys.NO_LABEL_FOLDER_ANIM_FOLDER_FIELD)
                        ?: "mFolder"
                )
            }.getOrNull()
            folderNameDelegateField = runCatching {
                findField(
                    folderClass,
                    readString(noLabelModule, DexIndexConstants.Keys.NO_LABEL_FOLDER_NAME_DELEGATE_FIELD)
                        ?: "mFolderNameAnimDelegate"
                )
            }.getOrNull()
            true
        } catch (t: Throwable) {
            logger.warn("resolve FolderAnimationManager refs failed, title fade hook skipped: ${t.message}")
            false
        }
    }

    private fun hookPreviewBackgroundSetup() {
        hookWithId(pbSetup, "big_folder_ext_title_pb_setup", INNER_PRIORITY) { chain ->
            chain.proceed()
            try {
                val pb = chain.thisObject ?: return@hookWithId null
                val spanX = pbSpanX.getInt(pb)
                val spanY = pbSpanY.getInt(pb)
                if (!(spanX > 1 || spanY > 1)) return@hookWithId null

                val context = chain.args.getOrNull(0) as? Context
                val activityContext = chain.args.getOrNull(1)
                val view = chain.args.getOrNull(2) as? View
                val stockOffsetY = pbOffsetY.getInt(pb).coerceAtLeast(0)
                val stockH = pbPreviewSizeY.getInt(pb)
                val fallbackSlotH = pbCellHeightBig?.let {
                    runCatching { it.getInt(pb) * spanY }.getOrNull()
                }?.takeIf { it > 0 }
                val slotH = view?.layoutParams?.height?.takeIf { it > 0 }
                    ?: view?.measuredHeight?.takeIf { it > 0 }
                    ?: fallbackSlotH
                    ?: (stockH + stockOffsetY * 2)

                val dp = activityContext?.let { runCatching { acGetDeviceProfile.invoke(it) }.getOrNull() }
                val page = LauncherGridMetrics.pageOf(view)
                val density = context?.resources?.displayMetrics?.density
                    ?: view?.resources?.displayMetrics?.density
                    ?: 1f
                val region = BigFolderExternalTitleSupport.computeVerticalRegion(
                    dp = dp,
                    page = page,
                    slotH = slotH,
                    spanY = spanY,
                    stockOffsetY = stockOffsetY,
                    density = density
                )
                pbOffsetY.setInt(pb, region.regionTop)
                pbPreviewSizeY.setInt(pb, region.regionH)
            } catch (t: Throwable) {
                logger.error("PreviewBackground.setup external-title adjust failed", t)
            }
            null
        }
    }

    private fun hookFolderIconLabelStyling() {
        fiPadTopMethod?.let { method ->
            hookWithId(method, "big_folder_ext_title_pad_top", INNER_PRIORITY) { chain ->
                val icon = chain.thisObject
                if (icon != null && isBigFolder(icon)) {
                    return@hookWithId 0
                }
                chain.proceed()
            }
        }

        fiSetNameSizeMethod?.let { method ->
            hookWithId(method, "big_folder_ext_title_name_size") { chain ->
                val icon = chain.thisObject
                if (icon == null || !isBigFolder(icon)) return@hookWithId chain.proceed()
                try {
                    val label = fiLabel?.get(icon) as? TextView ?: return@hookWithId chain.proceed()
                    val dp = getDeviceProfileFromIcon(icon)
                    val targetPx = LauncherGridMetrics.iconTextSizePx(dp)
                    if (targetPx != null && targetPx > 0) {
                        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, targetPx.toFloat())
                        label.scaleX = 1f
                        label.scaleY = 1f
                        return@hookWithId null
                    }
                } catch (t: Throwable) {
                    logger.error("FolderIcon.setNameSize override failed", t)
                }
                chain.proceed()
            }
        }

        fiSetNameScaleMethod?.let { method ->
            hookWithId(method, "big_folder_ext_title_name_scale") { chain ->
                val icon = chain.thisObject
                if (icon == null || !isBigFolder(icon)) return@hookWithId chain.proceed()
                try {
                    (fiLabel?.get(icon) as? View)?.let { label ->
                        label.scaleX = 1f
                        label.scaleY = 1f
                        return@hookWithId null
                    }
                } catch (t: Throwable) {
                    logger.error("FolderIcon.setFolderNameScale override failed", t)
                }
                chain.proceed()
            }
        }

        fiSetIconMarginMethod?.let { method ->
            hookWithId(method, "big_folder_ext_title_icon_margin") { chain ->
                val icon = chain.thisObject
                if (icon == null || !isBigFolder(icon)) return@hookWithId chain.proceed()
                try {
                    val label = fiLabel?.get(icon) as? View ?: return@hookWithId chain.proceed()
                    val lp = label.layoutParams as? ViewGroup.MarginLayoutParams
                        ?: return@hookWithId chain.proceed()
                    if (lp.leftMargin != 0 || lp.rightMargin != 0) {
                        lp.leftMargin = 0
                        lp.rightMargin = 0
                        label.layoutParams = lp
                    }
                    return@hookWithId null
                } catch (t: Throwable) {
                    logger.error("FolderIcon.setFolderIconMargin override failed", t)
                }
                chain.proceed()
            }
        }

        fiSetNameColorMethod?.let { method ->
            hookWithId(method, "big_folder_ext_title_name_color") { chain ->
                val icon = chain.thisObject
                if (icon == null || !isBigFolder(icon)) return@hookWithId chain.proceed()
                try {
                    val color = chain.args.getOrNull(0) as? Int ?: return@hookWithId chain.proceed()
                    val label = fiLabel?.get(icon) as? TextView ?: return@hookWithId chain.proceed()
                    label.setTextColor(color)
                    label.invalidate()
                    return@hookWithId null
                } catch (t: Throwable) {
                    logger.error("FolderIcon.setFolderNameTextColor override failed", t)
                }
                chain.proceed()
            }
        }
    }

    private fun hookFolderIconLabelMargin() {
        val labelMethod = fiLabelMethod ?: return
        val labelField = fiLabel ?: return
        val bgField = fiBg ?: return

        hookWithId(labelMethod, "big_folder_ext_title_name_margin", INNER_PRIORITY) { chain ->
            chain.proceed()
            try {
                val icon = chain.thisObject ?: return@hookWithId null
                if (!isBigFolder(icon)) return@hookWithId null
                (icon as? ViewGroup)?.let { group ->
                    if (group.clipChildren) group.clipChildren = false
                }
                val label = labelField.get(icon) as? View ?: return@hookWithId null
                label.scaleX = 1f
                label.scaleY = 1f
                val bg = bgField.get(icon) ?: return@hookWithId null
                val bgH = pbPreviewSizeY.getInt(bg)
                if (bgH <= 0) return@hookWithId null

                val bgBottom = pbOffsetY.getInt(bg) + bgH
                val dp = getDeviceProfileFromIcon(icon)
                val density = (icon as? View)?.resources?.displayMetrics?.density ?: 1f
                val labelGap = BigFolderExternalTitleSupport.computeLabelGap(dp, density)
                val padTop = (icon as? View)?.paddingTop ?: 0
                val desiredTop = (bgBottom + labelGap - padTop).coerceAtLeast(0)

                val lp = label.layoutParams as? ViewGroup.MarginLayoutParams ?: return@hookWithId null
                if (lp.topMargin != desiredTop || lp.leftMargin != 0 || lp.rightMargin != 0) {
                    lp.topMargin = desiredTop
                    lp.leftMargin = 0
                    lp.rightMargin = 0
                    label.requestLayout()
                }
            } catch (t: Throwable) {
                logger.error("FolderIcon.setFolderNameMargin external sync failed", t)
            }
            null
        }
    }

    private fun hookTitleFadeAnim() {
        val createSet = famCreateAnimatorSet ?: return
        val flagField = folderNameAnimFlagField ?: return
        hookWithId(createSet, "big_folder_ext_title_fade_anim") { chain ->
            runCatching {
                if (flagField.getBoolean(null)) {
                    flagField.setBoolean(null, false)
                }
            }
            val result = chain.proceed()
            if (result is AnimatorSet && chain.args.firstOrNull() == false) {
                runCatching {
                    val folder = famFolderField?.get(chain.thisObject)
                    val delegate = folder?.let { folderNameDelegateField?.get(it) as? View }
                    if (delegate != null) {
                        val fadeOut = ObjectAnimator.ofFloat(delegate, View.ALPHA, 1f, 0f).apply {
                            duration = FOLDER_NAME_CLOSE_FADE_MS
                        }
                        result.play(fadeOut)
                    }
                }
            }
            result
        }
    }

    private fun hookResizeTransitions() {
        val beforeMethod = fiOnBeforeResize ?: return
        val afterMethod = fiOnAfterResize ?: return
        val bgField = fiBg ?: return
        val pimField = fiPreviewItemMgr ?: return

        hookWithId(beforeMethod, "big_folder_ext_title_before_resize", INNER_PRIORITY) { chain ->
            chain.proceed()
            try {
                val icon = chain.thisObject ?: return@hookWithId null
                val dstSpanX = chain.args[0] as Int
                val dstSpanY = chain.args[1] as Int
                val info = fiInfo.get(icon) ?: return@hookWithId null
                val bg = bgField.get(icon) ?: return@hookWithId null
                val pim = pimField.get(icon) ?: return@hookWithId null

                val beforeSpanX = fiSpanX.getInt(info)
                val beforeSpanY = fiSpanY.getInt(info)
                val preSlot = pbPreRect.get(bg) as? Rect ?: return@hookWithId null
                val beforeIsBig = beforeSpanX > 1 || beforeSpanY > 1
                val preBgW = pbPreviewSize.getInt(bg)
                // Small 1x1 PreviewBackground keeps previewSizeY at 0 and uses previewSize as both axes.
                val preBgH = if (beforeIsBig) pbPreviewSizeY.getInt(bg) else preBgW
                val preLeft = preSlot.left + pbOffsetX.getInt(bg)
                val preTop = preSlot.top + pbOffsetY.getInt(bg)
                preResizeBgSnapshots[icon] = PreResizeBgSnapshot(
                    beforeSpanX = beforeSpanX,
                    beforeSpanY = beforeSpanY,
                    left = preLeft,
                    top = preTop,
                    right = preLeft + preBgW,
                    bottom = preTop + preBgH
                )

                if (!(dstSpanX > 1 || dstSpanY > 1)) return@hookWithId null
                val cellH = pbCellHeightBig?.let { runCatching { it.getInt(bg) }.getOrNull() } ?: 0
                val stockDstH = pimDstHeight.getInt(pim)
                val dstSlotH = (cellH * dstSpanY).takeIf { it > 0 } ?: stockDstH
                val stockOffsetY = ((dstSlotH - stockDstH) / 2).coerceAtLeast(0)
                val dp = getDeviceProfileFromIcon(icon)
                val iconView = icon as? View
                val dstRegion = BigFolderExternalTitleSupport.computeVerticalRegion(
                    dp = dp,
                    page = LauncherGridMetrics.pageOf(iconView),
                    slotH = dstSlotH,
                    spanY = dstSpanY,
                    stockOffsetY = stockOffsetY,
                    density = iconView?.resources?.displayMetrics?.density ?: 1f
                )
                pimDstHeight.setInt(pim, dstRegion.regionH)
            } catch (t: Throwable) {
                logger.error("FolderIcon.onBeforeResize external-title hook failed", t)
            }
            null
        }

        hookWithId(afterMethod, "big_folder_ext_title_after_resize", INNER_PRIORITY) { chain ->
            chain.proceed()
            try {
                val icon = chain.thisObject ?: return@hookWithId null
                val pre = preResizeBgSnapshots.remove(icon) ?: return@hookWithId null
                val info = fiInfo.get(icon) ?: return@hookWithId null
                val dstSpanX = fiSpanX.getInt(info)
                val dstSpanY = fiSpanY.getInt(info)
                val preIsBig = pre.beforeSpanX > 1 || pre.beforeSpanY > 1
                val dstIsBig = dstSpanX > 1 || dstSpanY > 1
                if (!preIsBig && !dstIsBig) return@hookWithId null

                val bg = bgField.get(icon) ?: return@hookWithId null
                val pim = pimField.get(icon) ?: return@hookWithId null
                val dstSlot = pbDstRect.get(bg) as? Rect ?: return@hookWithId null
                val dstBgW = pbPreviewSize.getInt(bg)
                val dstBgH = if (dstIsBig) pbPreviewSizeY.getInt(bg) else dstBgW
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
                    label.translationY = newOffsetB.toFloat()
                    label.translationX = (newOffsetL + newOffsetR) / 2f
                }

                runCatching { fiUpdateBgBlur?.invoke(icon) }
                (icon as? View)?.invalidate()
            } catch (t: Throwable) {
                logger.error("FolderIcon.onAfterResize external-title hook failed", t)
            }
            null
        }

        val onUpdate = resizeLabelOnUpdate
        val pbField = resizeLabelPbField
        val fiField = resizeLabelFiField
        if (onUpdate != null && pbField != null && fiField != null) {
            hookWithId(onUpdate, "big_folder_ext_title_resize_label_anim") { chain ->
                try {
                    val anim = chain.args.getOrNull(0) as? ValueAnimator
                    val pb = pbField.get(chain.thisObject)
                    val icon = fiField.get(chain.thisObject)
                    val label = icon?.let { fiLabel?.get(it) as? View }
                    if (anim != null && pb != null && label != null) {
                        val frac = (anim.animatedValue as? Float) ?: return@hookWithId chain.proceed()
                        val offsetB = pbOffsetB.getInt(pb)
                        val offsetL = pbOffsetL.getInt(pb)
                        val offsetR = pbOffsetR.getInt(pb)
                        label.translationY = offsetB * frac
                        label.translationX = (offsetL + offsetR) * frac / 2f
                        (icon as? View)?.invalidate()
                        return@hookWithId null
                    }
                } catch (t: Throwable) {
                    logger.error("resize label animator override failed", t)
                }
                chain.proceed()
            }
        }
    }

    private fun isBigFolder(icon: Any): Boolean {
        val info = runCatching { fiInfo.get(icon) }.getOrNull() ?: return false
        return fiSpanX.getInt(info) > 1 || fiSpanY.getInt(info) > 1
    }

    private fun getDeviceProfileFromIcon(icon: Any): Any? {
        val activity = fiActivity?.let { runCatching { it.get(icon) }.getOrNull() } ?: return null
        return runCatching { acGetDeviceProfile.invoke(activity) }.getOrNull()
    }

    private fun resolveFieldByType(
        owner: Class<*>,
        indexed: String?,
        typeName: String,
        classLoader: ClassLoader,
        vararg fallbacks: String
    ): Field? {
        if (indexed != null) {
            runCatching { return findField(owner, indexed) }
        }
        for (name in fallbacks) {
            runCatching { return findField(owner, name) }
        }
        val targetType = runCatching { classLoader.loadClass(typeName) }.getOrNull() ?: return null
        return owner.declaredFields
            .firstOrNull { targetType.isAssignableFrom(it.type) }
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

    private fun resolveOptionalMethodWithParams(
        owner: Class<*>,
        indexed: String?,
        fallbacks: List<String>,
        vararg paramTypes: Class<*>?
    ): Method? {
        if (indexed != null) return runCatching { findMethod(owner, indexed, *paramTypes) }.getOrNull()
        for (name in fallbacks) {
            runCatching { return findMethod(owner, name, *paramTypes) }.getOrNull()
        }
        return null
    }

    private fun readString(module: JsonObject?, key: String): String? =
        module?.get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }

    private companion object {
        // Lower than PRIORITY_DEFAULT so BigFolderAlignHook's outer wrapper runs cleanly around us.
        private const val INNER_PRIORITY = XposedInterface.PRIORITY_DEFAULT - 10

        // Matches FolderAnimationManager.mTextCloseDuration.
        private const val FOLDER_NAME_CLOSE_FADE_MS = 120L
    }
}
