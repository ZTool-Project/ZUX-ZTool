package com.qimian233.ztool.data.gametool

import android.content.Context
import android.util.Log
import com.qimian233.ztool.R
import com.qimian233.ztool.data.keys.ScopeKeys
import dalvik.system.PathClassLoader
import java.lang.reflect.Modifier

/**
 * One entry of the game service's model table.
 *
 * @property code the `Build.MODEL` value compared by `DeviceUtils.isModel`
 * @property fieldName the host constant name, e.g. `ELDEN_PRC`
 * @property marketingName user-facing product name, null when the host has no documented name
 */
data class DeviceModel(
    val code: String,
    val fieldName: String,
    val marketingName: String? = null,
) {
    val label: String
        get() = marketingName?.let { "$code · $it" } ?: code
}

/** One host model constant paired with its product-name resource. */
private data class ModelSpec(val fieldName: String, val nameRes: Int)

/**
 * Reads the game service's model table by loading its `DeviceUtils` and reflecting
 * its `static final String` fields; the values live in dex `static_values`, so the
 * scan does not depend on how R8 laid the constants out. Empty list on any failure.
 */
object DeviceModelCatalog {

    private const val DEVICE_UTILS_CLASS = "com.zui.util.DeviceUtils"
    private const val TAG = "DeviceModelCatalog"

    private val lock = Any()
    private var cachedApkPath: String? = null
    private var cachedStamp: Long = -1L
    private var cachedModels: List<DeviceModel> = emptyList()

    /** Models of the installed game service in display order; unnamed constants go last. */
    fun load(context: Context): List<DeviceModel> = synchronized(lock) {
        val info = runCatching {
            context.packageManager.getApplicationInfo(ScopeKeys.GAME_SERVICE.packageName, 0)
        }.getOrNull() ?: return emptyList()

        val stamp = runCatching {
            context.packageManager.getPackageInfo(ScopeKeys.GAME_SERVICE.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        if (info.sourceDir == cachedApkPath && stamp == cachedStamp) return cachedModels

        val models = probe(context, info.sourceDir)
        cachedApkPath = info.sourceDir
        cachedStamp = stamp
        cachedModels = models
        return models
    }

    private fun probe(context: Context, apkPath: String): List<DeviceModel> {
        return try {
            val loader = PathClassLoader(apkPath, DeviceModelCatalog::class.java.classLoader)
            val hostClass = Class.forName(DEVICE_UTILS_CLASS, false, loader)
            val models = hostClass.declaredFields.asSequence()
                .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
                .mapNotNull { field ->
                    field.isAccessible = true
                    val value = runCatching { field.get(null) as? String }.getOrNull()
                    // The only non-model String constant in the class is the debug property.
                    if (value.isNullOrBlank() || value.startsWith("persist.")) return@mapNotNull null
                    DeviceModel(
                        code = value,
                        fieldName = field.name,
                        marketingName = MODEL_SPECS_BY_FIELD[field.name]?.let { context.getString(it.nameRes) },
                    )
                }
                .sortedWith(
                    compareBy(
                        { it.marketingName == null },
                        { MODEL_DISPLAY_RANK[it.fieldName] ?: Int.MAX_VALUE },
                        { it.code },
                    )
                )
                .toList()
            Log.d(TAG, "probed ${models.size} models from $apkPath")
            models
        } catch (t: Throwable) {
            Log.w(TAG, "model table probe failed for $apkPath", t)
            emptyList()
        }
    }

    /**
     * Host constants in dropdown display order: Y900, Y700 WuJi, Y700, YOGA, Xiaoxin Pad Pro GT,
     * Xiaoxin Pad Pro, Xiaoxin Pad, Zhaoyang, phones. Series run newest/largest first, and the
     * cellular SKU precedes the Wi-Fi SKU of the same size. Constants absent here fall to the end.
     */
    private val MODEL_SPECS: List<ModelSpec> = listOf(
        // Legion Y900: 1st gen (14.5") then the 2026 13"/11" pair.
        ModelSpec("MOJITO", R.string.game_tool_device_model_disguise_mojito_label),
        ModelSpec("BERYL", R.string.game_tool_device_model_disguise_beryl_label),
        ModelSpec("INCEPTION_LTE_PRC", R.string.game_tool_device_model_disguise_inception_lte_prc_label),
        ModelSpec("INCEPTION_LTE_ROW", R.string.game_tool_device_model_disguise_inception_lte_row_label),
        ModelSpec("INCEPTION_WIFI_PRC", R.string.game_tool_device_model_disguise_inception_wifi_prc_label),
        ModelSpec("INCEPTION_WIFI_ROW", R.string.game_tool_device_model_disguise_inception_wifi_row_label),
        // Legion Y700 WuJi.
        ModelSpec("WUJI_PRC", R.string.game_tool_device_model_disguise_wuji_prc_label),
        // Legion Y700: 5th gen down to 1st gen.
        ModelSpec("BALDUR_PRC", R.string.game_tool_device_model_disguise_baldur_prc_label),
        ModelSpec("ELDEN_PRC", R.string.game_tool_device_model_disguise_elden_prc_label),
        ModelSpec("KIRBY_PRC", R.string.game_tool_device_model_disguise_kirby_prc_label),
        ModelSpec("ASPHALT_PRC", R.string.game_tool_device_model_disguise_asphalt_prc_label),
        ModelSpec("P8", R.string.game_tool_device_model_disguise_p8_label),
        // YOGA Pad Pro: 14.5" then the 12.7" AI model.
        ModelSpec("TENET", R.string.game_tool_device_model_disguise_tenet_label),
        ModelSpec("LAPIS_PRC", R.string.game_tool_device_model_disguise_lapis_prc_label),
        // Xiaoxin Pad Pro GT: 13" then 11.1".
        ModelSpec("ZHIYU", R.string.game_tool_device_model_disguise_zhiyu_label),
        ModelSpec("TOPAZ", R.string.game_tool_device_model_disguise_topaz_label),
        // Xiaoxin Pad Pro: 13", 12.7", then the 2022 11.2" pair.
        ModelSpec("MALBEC", R.string.game_tool_device_model_disguise_malbec_label),
        ModelSpec("PERIDOT", R.string.game_tool_device_model_disguise_peridot_label),
        ModelSpec("OLIVINE", R.string.game_tool_device_model_disguise_olivine_label),
        ModelSpec("ONYX", R.string.game_tool_device_model_disguise_onyx_label),
        // Xiaoxin Pad: 12.1" pair, then 11" pair, cellular first.
        ModelSpec("MALIBU_LTE", R.string.game_tool_device_model_disguise_malibu_lte_label),
        ModelSpec("MALIBU", R.string.game_tool_device_model_disguise_malibu_label),
        ModelSpec("CAVA_WIFI_CONS", R.string.game_tool_device_model_disguise_cava_wifi_cons_label),
        ModelSpec("SYCAMORE_5G", R.string.game_tool_device_model_disguise_sycamore_5g_label),
        ModelSpec("SYCAMORE", R.string.game_tool_device_model_disguise_sycamore_label),
        // Zhaoyang commercial tablets: K12C pair, then K11.
        ModelSpec("CAVA_5G_COMM", R.string.game_tool_device_model_disguise_cava_5g_comm_label),
        ModelSpec("CAVA_WIFI_COMM", R.string.game_tool_device_model_disguise_cava_wifi_comm_label),
        ModelSpec("APEX", R.string.game_tool_device_model_disguise_apex_label),
        // Legion phones: Y70, Y90, Duel 2, Duel.
        ModelSpec("HALO", R.string.game_tool_device_model_disguise_halo_label),
        ModelSpec("DIABLO", R.string.game_tool_device_model_disguise_diablo_label),
        ModelSpec("DOOM", R.string.game_tool_device_model_disguise_doom_label),
        ModelSpec("MOBA", R.string.game_tool_device_model_disguise_moba_label),
    )

    private val MODEL_SPECS_BY_FIELD: Map<String, ModelSpec> = MODEL_SPECS.associateBy { it.fieldName }

    private val MODEL_DISPLAY_RANK: Map<String, Int> =
        MODEL_SPECS.withIndex().associate { (index, spec) -> spec.fieldName to index }
}
