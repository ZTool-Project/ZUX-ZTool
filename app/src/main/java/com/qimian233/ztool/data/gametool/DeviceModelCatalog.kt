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

    /** Models of the installed game service, sorted with named devices first. */
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
                        marketingName = MARKETING_NAME_RES[field.name]?.let(context::getString),
                    )
                }
                .sortedWith(compareBy({ it.marketingName == null }, { it.marketingName ?: it.code }))
                .toList()
            Log.d(TAG, "probed ${models.size} models from $apkPath")
            models
        } catch (t: Throwable) {
            Log.w(TAG, "model table probe failed for $apkPath", t)
            emptyList()
        }
    }

    /** Host constant name → product name resource; unlisted constants fall back to the code. */
    private val MARKETING_NAME_RES: Map<String, Int> = mapOf(
        "APEX" to R.string.game_tool_device_model_disguise_apex_label,
        "ASPHALT_PRC" to R.string.game_tool_device_model_disguise_asphalt_prc_label,
        "BALDUR_PRC" to R.string.game_tool_device_model_disguise_baldur_prc_label,
        "BERYL" to R.string.game_tool_device_model_disguise_beryl_label,
        "CAVA_5G_COMM" to R.string.game_tool_device_model_disguise_cava_5g_comm_label,
        "CAVA_WIFI_COMM" to R.string.game_tool_device_model_disguise_cava_wifi_comm_label,
        "CAVA_WIFI_CONS" to R.string.game_tool_device_model_disguise_cava_wifi_cons_label,
        "DIABLO" to R.string.game_tool_device_model_disguise_diablo_label,
        "DOOM" to R.string.game_tool_device_model_disguise_doom_label,
        "ELDEN_PRC" to R.string.game_tool_device_model_disguise_elden_prc_label,
        "HALO" to R.string.game_tool_device_model_disguise_halo_label,
        "INCEPTION_LTE_PRC" to R.string.game_tool_device_model_disguise_inception_lte_prc_label,
        "INCEPTION_LTE_ROW" to R.string.game_tool_device_model_disguise_inception_lte_row_label,
        "INCEPTION_WIFI_PRC" to R.string.game_tool_device_model_disguise_inception_wifi_prc_label,
        "INCEPTION_WIFI_ROW" to R.string.game_tool_device_model_disguise_inception_wifi_row_label,
        "KIRBY_PRC" to R.string.game_tool_device_model_disguise_kirby_prc_label,
        "LAPIS_PRC" to R.string.game_tool_device_model_disguise_lapis_prc_label,
        "MALBEC" to R.string.game_tool_device_model_disguise_malbec_label,
        "MALIBU" to R.string.game_tool_device_model_disguise_malibu_label,
        "MALIBU_LTE" to R.string.game_tool_device_model_disguise_malibu_lte_label,
        "MOBA" to R.string.game_tool_device_model_disguise_moba_label,
        "MOJITO" to R.string.game_tool_device_model_disguise_mojito_label,
        "OLIVINE" to R.string.game_tool_device_model_disguise_olivine_label,
        "ONYX" to R.string.game_tool_device_model_disguise_onyx_label,
        "P8" to R.string.game_tool_device_model_disguise_p8_label,
        "PERIDOT" to R.string.game_tool_device_model_disguise_peridot_label,
        "SYCAMORE" to R.string.game_tool_device_model_disguise_sycamore_label,
        "SYCAMORE_5G" to R.string.game_tool_device_model_disguise_sycamore_5g_label,
        "TENET" to R.string.game_tool_device_model_disguise_tenet_label,
        "TOPAZ" to R.string.game_tool_device_model_disguise_topaz_label,
        "WUJI_PRC" to R.string.game_tool_device_model_disguise_wuji_prc_label,
        "ZHIYU" to R.string.game_tool_device_model_disguise_zhiyu_label,
    )
}
