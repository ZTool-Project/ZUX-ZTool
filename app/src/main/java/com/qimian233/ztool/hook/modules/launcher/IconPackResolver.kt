package com.qimian233.ztool.hook.modules.launcher

import android.content.Context
import android.content.pm.ComponentInfo
import android.content.pm.PackageItemInfo
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Build
import org.xmlpull.v1.XmlPullParser
import java.util.Calendar
import java.util.Locale

/**
 * Appfilter-backed icon source for one installed ADW / CandyBar icon pack.
 *
 * The pack is parsed once per launcher process, on first use. The pack version
 * code is part of [stateSuffix], so changing the pack invalidates the host icon
 * cache. Any failure degrades to "no icon" and the host keeps its own icon.
 */
internal class IconPackResolver(private val packPackage: String) {

    private val lock = Any()

    @Volatile
    private var ready = false

    private var resources: Resources? = null
    private var componentIcons: HashMap<String, IconEntry>? = null
    private var packageIcons: HashMap<String, IconEntry>? = null
    private var suffix: String = ""

    /** Icon drawable for [info], or null when this pack has no matching entry. */
    fun resolve(context: Context?, info: PackageItemInfo?, density: Int): Drawable? {
        if (context == null || info == null || density <= 0) return null
        if (!ready) ensureLoaded(context)
        val packResources = resources ?: return null
        val entry = lookup(info) ?: return null
        return loadDrawable(packResources, entry, density)
    }

    /** Icon-cache state suffix; empty until the pack loads successfully. */
    fun stateSuffix(context: Context?): String {
        if (context == null) return ""
        if (!ready) ensureLoaded(context)
        return suffix
    }

    private fun ensureLoaded(context: Context) {
        synchronized(lock) {
            if (ready) return
            try {
                val packageManager = context.packageManager
                val packageInfo = packageManager.getPackageInfo(packPackage, 0)
                @Suppress("DEPRECATION")
                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    packageInfo.versionCode.toLong()
                }
                val packResources = packageManager.getResourcesForApplication(packPackage)
                val filterId = packResources.getIdentifier(APPFILTER_NAME, APPFILTER_TYPE, packPackage)

                if (filterId != 0) {
                    val parser = packResources.getXml(filterId)
                    try {
                        parse(parser)
                    } finally {
                        runCatching { parser.close() }
                    }
                }
                resources = packResources
                suffix = "|$packPackage:$versionCode"
            } catch (t: Throwable) {
                resources = null
                componentIcons = null
                packageIcons = null
                suffix = ""
            } finally {
                ready = true
            }
        }
    }

    private fun parse(parser: XmlPullParser) {
        val components = HashMap<String, IconEntry>()
        val packages = HashMap<String, IconEntry>()
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> addEntry(
                        components = components,
                        packages = packages,
                        rawComponent = parser.getAttributeValue(null, "component"),
                        drawableName = parser.getAttributeValue(null, "drawable"),
                        calendarPrefix = null
                    )
                    "calendar" -> addEntry(
                        components = components,
                        packages = packages,
                        rawComponent = parser.getAttributeValue(null, "component"),
                        drawableName = null,
                        calendarPrefix = parser.getAttributeValue(null, "prefix")
                    )
                }
            }
            event = parser.next()
        }
        componentIcons = components
        packageIcons = packages
    }

    private fun addEntry(
        components: MutableMap<String, IconEntry>,
        packages: MutableMap<String, IconEntry>,
        rawComponent: String?,
        drawableName: String?,
        calendarPrefix: String?
    ) {
        val key = normalizeComponent(rawComponent) ?: return
        val entry = when {
            !calendarPrefix.isNullOrBlank() -> IconEntry(null, calendarPrefix)
            !drawableName.isNullOrBlank() -> IconEntry(drawableName, null)
            else -> return
        }
        components[key] = entry
        packages.putIfAbsent(key.substringBefore('/'), entry)
    }

    private fun lookup(info: PackageItemInfo): IconEntry? {
        val components = componentIcons ?: return null
        val packageName = info.packageName ?: return null
        val className = (info as? ComponentInfo)?.name
        if (!className.isNullOrEmpty()) {
            val key = if (className.startsWith(".")) packageName + className else "$packageName/$className"
            components[key]?.let { return it }
        }
        return packageIcons?.get(packageName)
    }

    private fun loadDrawable(resources: Resources, entry: IconEntry, density: Int): Drawable? {
        val name = entry.calendarPrefix?.let { calendarDrawableName(resources, it) }
            ?: entry.drawableName
            ?: return null
        val id = resources.getIdentifier(name, DRAWABLE_TYPE, packPackage)
        if (id == 0) return null
        return try {
            resources.getDrawableForDensity(id, density, null)
        } catch (t: Throwable) {
            null
        }
    }

    private fun calendarDrawableName(resources: Resources, prefix: String): String? {
        val day = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        val plain = prefix + day
        if (resources.getIdentifier(plain, DRAWABLE_TYPE, packPackage) != 0) return plain
        val padded = prefix + String.format(Locale.US, "%02d", day)
        return if (resources.getIdentifier(padded, DRAWABLE_TYPE, packPackage) != 0) padded else null
    }

    private fun normalizeComponent(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var value = raw.trim()
        val braceStart = value.indexOf('{')
        val braceEnd = value.lastIndexOf('}')
        if (braceStart >= 0 && braceEnd > braceStart) {
            value = value.substring(braceStart + 1, braceEnd)
        }
        val slash = value.indexOf('/')
        if (slash <= 0 || slash >= value.length - 1) return null
        val packageName = value.substring(0, slash)
        var className = value.substring(slash + 1).trim()
        if (className.isEmpty()) return null
        className = when {
            className.startsWith(".") -> packageName + className
            !className.contains('.') -> "$packageName.$className"
            else -> className
        }
        return "$packageName/$className"
    }

    private class IconEntry(val drawableName: String?, val calendarPrefix: String?)

    private companion object {
        const val APPFILTER_NAME = "appfilter"
        const val APPFILTER_TYPE = "xml"
        const val DRAWABLE_TYPE = "drawable"
    }
}
