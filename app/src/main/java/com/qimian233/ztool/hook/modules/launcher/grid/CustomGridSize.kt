package com.qimian233.ztool.hook.modules.launcher.grid

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Constructor

/** Overrides launcher GridOption row and column counts with user-configured values. */
@SuppressLint("PrivateApi")
class CustomGridSize : AppHookModule() {

    override fun getModuleName(): String = PreferenceKeys.CUSTOM_GRID_SIZE.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.LAUNCHER.packageName)

    @Throws(Throwable::class)
    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        logger.info("Load CustomGridSize!")
        val prefs = remotePreferences
        val customRows = prefs.getInt(
            PreferenceKeys.CUSTOM_LAUNCHER_ROW.name,
            PreferenceKeys.CUSTOM_LAUNCHER_ROW.default
        )
        val customColumns = prefs.getInt(
            PreferenceKeys.CUSTOM_LAUNCHER_COLUMN.name,
            PreferenceKeys.CUSTOM_LAUNCHER_COLUMN.default
        )
        try {
            val gridOptionClass = classLoader.loadClass(
                $$"com.android.launcher3.InvariantDeviceProfile$GridOption"
            )
            val ctor = resolveGridOptionConstructor(gridOptionClass, classLoader)
            val numColsField = findField(gridOptionClass, "numColumns")
            val numRowsField = findField(gridOptionClass, "numRows")

            // High priority: grid dimensions must be applied before other GridOption-
            // or DeviceProfile-dependent hooks (e.g. LauncherWideGridHook) recompute
            // layout values from them on the next setInsets pass.
            hookWithId(ctor, "ctor", XposedInterface.PRIORITY_HIGHEST) { chain ->
                chain.proceed()
                try {
                    val thisObject = chain.thisObject
                    numColsField.setInt(thisObject, customColumns)
                    numRowsField.setInt(thisObject, customRows)
                    logger.debug("GridOption config modded to ${customColumns}x$customRows")
                } catch (e: Throwable) {
                    logger.error("Failed to update GridOption fields", e)
                }
                null
            }
        } catch (th: Throwable) {
            logger.error("Failed to hook GridOption!", th)
        }
    }

    private fun resolveGridOptionConstructor(
        gridOptionClass: Class<*>,
        classLoader: ClassLoader
    ): Constructor<*> {
        val candidates = listOf(
            arrayOf(
                Context::class.java,
                "com.android.launcher3.util.XmlElement",
                "com.android.launcher3.display.LauncherDisplayInfo"
            ),
            arrayOf(
                Context::class.java,
                AttributeSet::class.java,
                $$"com.android.launcher3.util.DisplayController$Info"
            ),
            arrayOf(
                Context::class.java,
                AttributeSet::class.java
            )
        )
        for (spec in candidates) {
            try {
                val paramTypes = spec.map { entry ->
                    when (entry) {
                        is Class<*> -> entry
                        is String -> classLoader.loadClass(entry)
                        else -> throw IllegalArgumentException("Unexpected param spec: $entry")
                    }
                }.toTypedArray()
                return gridOptionClass.getDeclaredConstructor(*paramTypes)
            } catch (_: Throwable) {
            }
        }
        return gridOptionClass.declaredConstructors.firstOrNull {
            it.parameterCount >= 2 && it.parameterTypes[0] == Context::class.java
        } ?: throw NoSuchMethodException("No compatible GridOption constructor in $gridOptionClass")
    }
}
