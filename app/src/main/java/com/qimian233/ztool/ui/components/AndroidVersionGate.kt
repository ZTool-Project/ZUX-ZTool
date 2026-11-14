package com.qimian233.ztool.ui.components

/**
 * Returns whether [sdkInt] is inside the inclusive [minSdkVersion]..[maxSdkVersion]
 * bounds. A null bound is unbounded; inverted bounds support nothing.
 */
internal fun isAndroidVersionSupported(
    sdkInt: Int,
    minSdkVersion: Int?,
    maxSdkVersion: Int?
): Boolean {
    if (minSdkVersion != null && sdkInt < minSdkVersion) return false
    if (maxSdkVersion != null && sdkInt > maxSdkVersion) return false
    return true
}
