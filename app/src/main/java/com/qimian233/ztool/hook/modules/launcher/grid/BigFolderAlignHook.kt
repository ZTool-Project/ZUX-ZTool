package com.qimian233.ztool.hook.modules.launcher.grid

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
import java.util.Locale

/**
 * Aligns square big folders (`spanX == spanY >= 2`) to a centered square blur
 * background, proportional 3x3 inner grid, and 10+ ninth-slot diagonal stack.
 */
@SuppressLint("PrivateApi")
class BigFolderAlignHook : AppHookModule() {

    override fun getModuleName(): String = PreferenceKeys.LAUNCHER_BIG_FOLDER_ALIGN.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.LAUNCHER.packageName)

    private var coreReady = false
    private var ruleReady = false
    private var bfcReady = false
    private var folderIconReady = false
    private var pimReady = false

    // PreviewBackground
    private lateinit var pbSpanX: Field
    private lateinit var pbSpanY: Field
    private lateinit var pbPreviewSize: Field
    private lateinit var pbPreviewSizeY: Field
    private lateinit var pbOffsetX: Field
    private lateinit var pbOffsetY: Field
    private lateinit var pbSetup: Method

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
    private var fiNameTopMargin: Field? = null
    private var fiLabelMethod: Method? = null
    private var fiPadTopMethod: Method? = null

    // PreviewItemManager
    private lateinit var pimIcon: Field
    private lateinit var pimFirstPage: Field
    private lateinit var pimDrawParams: Method
    private lateinit var pimCheckClick: Method

    private val suppressStackingForNine = ThreadLocal.withInitial { false }

    @Throws(Throwable::class)
    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        logger.debug("BigFolderAlign installing")

        val module = DexIndexStore.lookup(xposed, ScopeKeys.LAUNCHER.packageName)
            ?.getAsJsonObject(DexIndexConstants.JSON_MODULES)
            ?.getAsJsonObject(DexIndexConstants.ModuleKeys.LAUNCHER_BIG_FOLDER_ALIGN)

        coreReady = resolveCoreRefs(classLoader, module)
        if (!coreReady) return
        ruleReady = resolveRuleRefs(classLoader, module)
        bfcReady = resolveBfcRefs(classLoader, module)
        folderIconReady = resolveFolderIconRefs(classLoader, module)
        pimReady = resolvePreviewItemManagerRefs(classLoader, module)

        hookSetupGeometry()
        if (bfcReady) hookChildCountRewrite()
        if (ruleReady) hookChildGridRule()
        if (bfcReady && folderIconReady && pimReady) hookNineItemStackThreshold()
        if (folderIconReady) hookFolderLabelAndPadding()
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
            pbSetup = findMethod(
                pb, "setup",
                Context::class.java, activityContextClass, View::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
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
            fiNameTopMargin = resolveOptionalField(
                fi,
                readString(module, DexIndexConstants.Keys.BIG_FOLDER_ALIGN_FI_NAME_TOP_MARGIN_FIELD),
                "mFolderNameTopMargin"
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

    private fun hookSetupGeometry() {
        hookWithId(pbSetup, "big_folder_align_setup") { chain ->
            chain.proceed()
            try {
                val pb = chain.thisObject ?: return@hookWithId null
                val spanX = pbSpanX.getInt(pb)
                val spanY = pbSpanY.getInt(pb)
                if (!isSquareBigFolder(spanX, spanY)) return@hookWithId null

                val stockOffsetX = pbOffsetX.getInt(pb).coerceAtLeast(0)
                val stockOffsetY = pbOffsetY.getInt(pb).coerceAtLeast(0)
                val stockW = pbPreviewSize.getInt(pb)
                val stockH = pbPreviewSizeY.getInt(pb)
                val view = chain.args.getOrNull(2) as? View
                val slotW = view?.measuredWidth?.takeIf { it > 0 } ?: (stockW + stockOffsetX * 2)
                val slotH = view?.measuredHeight?.takeIf { it > 0 } ?: (stockH + stockOffsetY * 2)

                val availW = (slotW - stockOffsetX * 2).coerceAtLeast(1)
                val availH = (slotH - stockOffsetY * 2).coerceAtLeast(1)
                val side = minOf(availW, availH)
                val offsetX = (slotW - side) / 2
                val offsetY = (slotH - side) / 2

                pbPreviewSize.setInt(pb, side)
                pbPreviewSizeY.setInt(pb, side)
                pbOffsetX.setInt(pb, offsetX)
                pbOffsetY.setInt(pb, offsetY)

                val logKey = "${spanX}x$spanY:${slotW}x$slotH->$side"
                if (loggedGeometry.add(logKey)) {
                    logger.debug(
                        "BigFolderAlign setup span=${spanX}x$spanY slot=${slotW}x$slotH " +
                            "stock=${stockW}x$stockH side=$side offset=($offsetX,$offsetY)"
                    )
                }
            } catch (t: Throwable) {
                logger.error("PreviewBackground.setup rewrite failed", t)
            }
            null
        }
        logger.debug("hooked PreviewBackground.setup")
    }

    private fun hookChildCountRewrite() {
        hookWithId(bfcGetChildCount, "big_folder_align_child_count") { chain ->
            try {
                val spanX = chain.args[0] as Int
                val spanY = chain.args[1] as Int
                if (!isSquareBigFolder(spanX, spanY)) return@hookWithId chain.proceed()
                val out = chain.args[2] as IntArray?
                if (suppressStackingForNine.get() == true) {
                    out?.set(0, GRID_CAPACITY + 1)
                    out?.set(1, 1)
                    return@hookWithId GRID_CAPACITY + 1
                }
                out?.set(0, CHILD_COLS)
                out?.set(1, CHILD_ROWS)
                return@hookWithId GRID_CAPACITY
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
                if (index < 0 || !isBigFolder || !isSquareBigFolder(spanX, spanY)) {
                    return@hookWithId if (ruleComputeIs9Args) result else null
                }

                val rule = chain.thisObject
                val boxW = if (availW > 0) availW.toFloat() else ruleBgWidth.getFloat(rule)
                val boxH = if (availH > 0) availH.toFloat() else ruleBgHeight.getFloat(rule)
                val side = minOf(boxW, boxH)
                val iconSize = ruleIconSize.getFloat(rule)
                if (side <= 0f || iconSize <= 0f) {
                    return@hookWithId if (ruleComputeIs9Args) result else null
                }

                val outerPad = side * OUTER_PAD_RATIO
                val headerH = if (padTop > 0) (padTop * 2f).coerceIn(0f, side * MAX_HEADER_RATIO) else 0f
                val gridTop = maxOf(outerPad, headerH)
                val gridBottom = side - outerPad
                val availGridH = (gridBottom - gridTop).coerceAtLeast(side * MIN_GRID_HEIGHT_RATIO)
                val gap = availGridH * (GAP_RATIO / (1f - 2f * OUTER_PAD_RATIO))
                val iconPx = minOf(
                    (availGridH - (CHILD_ROWS - 1) * gap) / CHILD_ROWS,
                    (side - 2f * outerPad - (CHILD_COLS - 1) * gap) / CHILD_COLS
                ).coerceAtLeast(1f)
                val gridExtent = CHILD_COLS * iconPx + (CHILD_COLS - 1) * gap
                val gridLeft = (boxW - gridExtent) / 2f
                val gridStartY = (boxH - side) / 2f + gridTop + (availGridH - gridExtent) / 2f
                val scale = iconPx / iconSize

                // Indices >= 9 are ZUI-native stacked items sharing the bottom-right 9th slot (index 8).
                val clampedIdx = if (index >= GRID_CAPACITY) GRID_CAPACITY - 1 else index
                val rawCol = clampedIdx % CHILD_COLS
                val isRtl = TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) == View.LAYOUT_DIRECTION_RTL
                val col = if (isRtl) (CHILD_COLS - 1 - rawCol) else rawCol
                val row = clampedIdx / CHILD_COLS
                val newX = gridLeft + col * (iconPx + gap)
                val newY = gridStartY + row * (iconPx + gap)

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

    private fun hookNineItemStackThreshold() {
        hookWithId(pimDrawParams, "big_folder_align_draw_params") { chain ->
            val icon = runCatching { pimIcon.get(chain.thisObject) }.getOrNull()
            val list = chain.args.getOrNull(1) as? List<*>
            if (isSquareFolderIcon(icon) && list?.size == GRID_CAPACITY) {
                suppressStackingForNine.set(true)
                try {
                    return@hookWithId chain.proceed()
                } finally {
                    suppressStackingForNine.remove()
                }
            }
            chain.proceed()
        }

        hookWithId(pimCheckClick, "big_folder_align_check_click") { chain ->
            val icon = chain.args.getOrNull(0)
            val list = runCatching { pimFirstPage.get(chain.thisObject) as? List<*> }.getOrNull()
            if (isSquareFolderIcon(icon) && list?.size == GRID_CAPACITY) {
                suppressStackingForNine.set(true)
                try {
                    return@hookWithId chain.proceed()
                } finally {
                    suppressStackingForNine.remove()
                }
            }
            chain.proceed()
        }
        logger.debug("hooked PreviewItemManager 9-item threshold")
    }

    private fun hookFolderLabelAndPadding() {
        fiPadTopMethod?.let { padTopMethod ->
            hookWithId(padTopMethod, "big_folder_align_pad_top") { chain ->
                val icon = chain.thisObject
                if (icon != null && isSquareFolderIcon(icon) && !isLabelVisible(icon)) {
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
                try {
                    val icon = chain.thisObject ?: return@hookWithId null
                    if (!isSquareFolderIcon(icon)) return@hookWithId null
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

    private fun isSquareBigFolder(spanX: Int, spanY: Int): Boolean = spanX == spanY && spanX >= 2

    private fun isSquareFolderIcon(icon: Any?): Boolean {
        if (icon == null || !folderIconReady) return false
        val info = fiInfo.get(icon) ?: return false
        return isSquareBigFolder(fiSpanX.getInt(info), fiSpanY.getInt(info))
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
        private const val CHILD_COLS = 3
        private const val CHILD_ROWS = 3
        private const val GRID_CAPACITY = CHILD_COLS * CHILD_ROWS

        // Proportional 3x3 layout parameters inside the square background.
        private const val OUTER_PAD_RATIO = 0.085f
        private const val GAP_RATIO = 0.055f
        private const val MAX_HEADER_RATIO = 0.30f
        private const val MIN_GRID_HEIGHT_RATIO = 0.45f

        private val loggedGeometry: MutableSet<String> =
            java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    }
}
