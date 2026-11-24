package com.qimian233.ztool.hook.base

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Log
import com.qimian233.ztool.hook.HookInit
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap

/** Loads ZTool package resources across host processes using libxposed application metadata. */
@SuppressLint("DiscouragedApi")
object ModuleResources {

    private const val TAG = "ModuleResources"
    const val MODULE_PACKAGE = "com.qimian233.ztool"

    private class CachedEntry(
        val resources: Resources,
        val hostLocaleTag: String
    )

    @Volatile
    private var cached: CachedEntry? = null
    private val warnedMissing = ConcurrentHashMap.newKeySet<String>()

    /** Returns ZTool [Resources] matching the current configuration of [hostContext], or null on failure. */
    fun load(
        hostContext: Context,
        xposed: XposedInterface? = HookInit.getXposedInterface(),
        logger: ModuleLog? = null
    ): Resources? {
        if (hostContext.packageName == MODULE_PACKAGE) {
            return hostContext.resources
        }
        val hostLocaleTag = localeTagsOf(hostContext.resources)
        val existing = cached
        if (existing != null) {
            if (existing.hostLocaleTag == hostLocaleTag) {
                return existing.resources
            }
            if (localeTagsOf(existing.resources) == hostLocaleTag) {
                cached = CachedEntry(existing.resources, hostLocaleTag)
                return existing.resources
            }
        }
        synchronized(this) {
            val current = cached
            if (current != null) {
                if (current.hostLocaleTag == hostLocaleTag) {
                    return current.resources
                }
                if (localeTagsOf(current.resources) == hostLocaleTag) {
                    cached = CachedEntry(current.resources, hostLocaleTag)
                    return current.resources
                }
            }
            val fresh = loadFresh(hostContext, xposed, logger)
            if (fresh != null) {
                cached = CachedEntry(fresh, hostLocaleTag)
                return fresh
            }
            return current?.resources
        }
    }

    /** Resolves a ZTool string by [resourceName] from [resources], or returns [fallback]. */
    fun getString(
        resources: Resources?,
        resourceName: String,
        fallback: String,
        logger: ModuleLog? = null
    ): String {
        if (resources == null) return fallback
        return try {
            val resId = resources.getIdentifier(resourceName, "string", MODULE_PACKAGE)
            if (resId != 0) {
                resources.getString(resId)
            } else {
                warnMissingOnce("string/$resourceName", logger)
                fallback
            }
        } catch (t: Throwable) {
            warnMissingOnce("string/$resourceName:${t.message}", logger)
            fallback
        }
    }

    /** Resolves a ZTool string by [resourceName] using [hostContext], or returns [fallback]. */
    fun getString(
        hostContext: Context,
        resourceName: String,
        fallback: String,
        xposed: XposedInterface? = HookInit.getXposedInterface(),
        logger: ModuleLog? = null
    ): String = getString(load(hostContext, xposed, logger), resourceName, fallback, logger)

    /** Resolves and optionally tints a ZTool drawable by [resourceName] using [hostContext]. */
    fun getDrawable(
        hostContext: Context,
        resourceName: String,
        tint: Int? = null,
        xposed: XposedInterface? = HookInit.getXposedInterface(),
        logger: ModuleLog? = null
    ): Drawable? {
        val resources = load(hostContext, xposed, logger) ?: return null
        return try {
            val resId = resources.getIdentifier(resourceName, "drawable", MODULE_PACKAGE)
            if (resId == 0) {
                warnMissingOnce("drawable/$resourceName", logger)
                return null
            }
            resources.getDrawable(resId, null)
                ?.mutate()
                ?.apply { if (tint != null) setTint(tint) }
        } catch (t: Throwable) {
            warnMissingOnce("drawable/$resourceName:${t.message}", logger)
            null
        }
    }

    private fun loadFresh(
        hostContext: Context,
        xposed: XposedInterface?,
        logger: ModuleLog?
    ): Resources? {
        if (xposed != null) {
            try {
                return hostContext.packageManager
                    .getResourcesForApplication(xposed.moduleApplicationInfo)
            } catch (t: Throwable) {
                logger?.debug("getResourcesForApplication(moduleApplicationInfo) failed: ${t.message}")
            }
        }
        return try {
            hostContext.createPackageContext(
                MODULE_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY
            ).resources
        } catch (t: Throwable) {
            val msg = "Failed to load ZTool resources: ${t.message}"
            if (logger != null) logger.warn(msg) else Log.w(TAG, msg)
            null
        }
    }

    private fun localeTagsOf(resources: Resources): String =
        runCatching { resources.configuration.locales.toLanguageTags() }.getOrDefault("")

    private fun warnMissingOnce(key: String, logger: ModuleLog?) {
        if (!warnedMissing.add(key)) return
        val msg = "Missing ZTool resource: $key"
        if (logger != null) logger.warn(msg) else Log.w(TAG, msg)
    }
}
