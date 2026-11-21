package com.qimian233.ztool.hook.modules.launcher.misc

import android.view.View
import com.google.gson.JsonObject
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.dexindex.base.DexIndexConstants
import com.qimian233.ztool.hook.base.AppHookModule
import com.qimian233.ztool.hook.base.DexIndexStore
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Removes the launcher global-search hot word card and the search box hint word.
 */
class CleanGlobalSearch : AppHookModule() {

    companion object {
        // Android 17 moved the class into globalsearch; the old FQN is the pre-17 fallback.
        private const val TARGET_CLASS = "com.zui.launcher.globalsearch.GlobalSearchView"
        private const val TARGET_CLASS_LEGACY = "com.zui.launcher.GlobalSearchView"

        private val HOT_WORD_INIT_FALLBACKS = listOf("hotWordView", "K0", "T0")
        private val HOT_WORD_DATA_FALLBACKS = listOf("handleWordView", "E0")
    }

    private var noHotWordView = false
    private var noSearchRecommend = false
    private val warnedKeys = mutableSetOf<String>()

    override fun getModuleName(): String = PreferenceKeys.CLEAN_GLOBAL_SEARCH.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.LAUNCHER.packageName)

    override fun handleLoadPackage(param: XposedModuleInterface.PackageLoadedParam) {
        readPreferences()
        if (!noHotWordView && !noSearchRecommend) return

        val module = DexIndexStore.lookup(xposed, ScopeKeys.LAUNCHER.packageName)
            ?.getAsJsonObject(DexIndexConstants.JSON_MODULES)
            ?.getAsJsonObject(DexIndexConstants.ModuleKeys.CLEAN_GLOBAL_SEARCH)

        val indexedClass = readString(module, DexIndexConstants.Keys.CLEAN_SEARCH_CLASS)
        val viewClass = loadGlobalSearchViewClass(param.defaultClassLoader, indexedClass)
        if (viewClass == null) {
            warnOnce("class", "GlobalSearchView not found; global search clean-up skipped")
            return
        }
        if (noHotWordView) {
            installHotWordRemoval(param.defaultClassLoader, viewClass, module)
        }
        if (noSearchRecommend) {
            val indexedHint = readString(module, DexIndexConstants.Keys.HOTWORD_HINT_METHOD)
            installSearchRecommendRemoval(viewClass, indexedHint)
        }
    }

    private fun loadGlobalSearchViewClass(
        classLoader: ClassLoader,
        indexedClass: String?
    ): Class<*>? {
        val candidates = (listOfNotNull(indexedClass) + listOf(TARGET_CLASS, TARGET_CLASS_LEGACY)).distinct()
        for (name in candidates) {
            try {
                return classLoader.loadClass(name)
            } catch (_: Throwable) {
                logger.debug("GlobalSearchView candidate not found: $name")
            }
        }
        return null
    }

    private fun installHotWordRemoval(
        classLoader: ClassLoader,
        viewClass: Class<*>,
        module: JsonObject?
    ) {
        val legacyClass = viewClass.name == TARGET_CLASS_LEGACY
        val indexedHotWordField = readString(module, DexIndexConstants.Keys.CLEAN_SEARCH_HOTWORD_FIELD)
        val hotWordField = if (legacyClass) null else findHotWordField(viewClass, indexedHotWordField)
        if (!legacyClass && hotWordField == null) {
            logger.debug("hotWordView field not found; falling back to blocking hot word methods")
        }

        val indexedInit = readString(module, DexIndexConstants.Keys.HOTWORD_INIT_METHOD)
        val initNames = (listOfNotNull(indexedInit) + HOT_WORD_INIT_FALLBACKS).distinct()
        val initName = hookFirstMatch(viewClass, initNames, "hotword_init", emptyArray(), hotWordField)
        if (initName == null && !legacyClass && hotWordField != null) {
            val onFinishInflate = runCatching {
                findMethod(viewClass, "onFinishInflate")
            }.getOrNull()
            if (onFinishInflate != null) {
                hookWithId(onFinishInflate, "hotword_on_finish_inflate") { chain ->
                    val result = chain.proceed()
                    hideHotWordView(hotWordField, chain.thisObject)
                    result
                }
                logger.info("Hooked onFinishInflate (hot word view kept hidden)")
            } else {
                warnOnce("hotword_init", "No hot word inflation method found; hot word card removal skipped")
            }
        } else if (initName == null) {
            warnOnce("hotword_init", "No hot word inflation method found; hot word card removal skipped")
        }

        val indexedDataName = readString(module, DexIndexConstants.Keys.HOTWORD_DATA_METHOD)
        val indexedDataParams = parseParamTypes(
            classLoader,
            readString(module, DexIndexConstants.Keys.HOTWORD_DATA_PARAMS)
        )
        val dataMethod = resolveDataMethod(viewClass, indexedDataName, indexedDataParams)
        if (dataMethod != null) {
            installHotWordMethodHook(dataMethod, viewClass, "hotword_data", hotWordField)
        } else {
            warnOnce("hotword_data", "No hot word data method found; hot word card removal skipped")
        }
    }

    private fun resolveDataMethod(
        viewClass: Class<*>,
        indexedName: String?,
        indexedParams: Array<Class<*>>?
    ): Method? {
        if (indexedName != null && indexedParams != null) {
            runCatching { viewClass.getDeclaredMethod(indexedName, *indexedParams) }
                .getOrNull()
                ?.let { return it }
        }
        val names = (listOfNotNull(indexedName) + HOT_WORD_DATA_FALLBACKS).distinct()
        for (name in names) {
            runCatching { viewClass.getDeclaredMethod(name, List::class.java) }
                .getOrNull()
                ?.let { return it }
            runCatching { viewClass.getDeclaredMethod(name, viewClass, List::class.java) }
                .getOrNull()
                ?.let { return it }
        }
        return null
    }

    private fun findHotWordField(viewClass: Class<*>, fieldName: String?): Field? {
        val candidates = listOfNotNull(fieldName, "hotWordView").distinct()
        for (name in candidates) {
            try {
                return findField(viewClass, name)
            } catch (_: Throwable) {
                // try next
            }
        }
        return null
    }

    private fun hookFirstMatch(
        viewClass: Class<*>,
        names: List<String>,
        id: String,
        paramTypes: Array<Class<*>>,
        hotWordField: Field?
    ): String? {
        for (name in names) {
            val method = try {
                viewClass.getDeclaredMethod(name, *paramTypes)
            } catch (_: Throwable) {
                logger.debug("Hot word method candidate not found: $name")
                continue
            }
            installHotWordMethodHook(method, viewClass, id, hotWordField)
            return name
        }
        return null
    }

    private fun installHotWordMethodHook(
        method: Method,
        viewClass: Class<*>,
        id: String,
        hotWordField: Field?
    ) {
        if (hotWordField != null) {
            val isStatic = Modifier.isStatic(method.modifiers)
            hookWithId(method, id) { chain ->
                val result = chain.proceed()
                val target = if (isStatic) {
                    chain.args.firstOrNull { viewClass.isInstance(it) }
                } else {
                    chain.thisObject
                }
                hideHotWordView(hotWordField, target)
                result
            }
            logger.info("Hooked hot word method: ${method.name} (view kept hidden)")
        } else {
            hookWithId(method, id) { null }
            logger.info("Blocked hot word method: ${method.name}")
        }
    }

    private fun hideHotWordView(field: Field, instance: Any?) {
        if (instance == null) return
        try {
            val view = field.get(instance) as? View ?: return
            view.visibility = View.GONE
        } catch (_: Throwable) {
            warnOnce("hotword_hide", "Failed to hide hot word view")
        }
    }

    private fun installSearchRecommendRemoval(viewClass: Class<*>, indexedHint: String?) {
        val names = listOfNotNull(indexedHint, "setHotWordHint").distinct()
        val method = names.firstNotNullOfOrNull { name ->
            runCatching { viewClass.getDeclaredMethod(name) }.getOrNull()
        }
        if (method == null) {
            warnOnce("hint", "GlobalSearchView#setHotWordHint not found; search box hint removal skipped")
            return
        }
        hookWithId(method, "set_hot_word_hint") { null }
        logger.info("Hooked ${method.name}")
    }

    private fun readString(module: JsonObject?, key: String): String? =
        module?.get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }

    private fun parseParamTypes(classLoader: ClassLoader, raw: String?): Array<Class<*>>? {
        if (raw == null) return null
        return runCatching {
            raw.split(",").map { resolveType(classLoader, it.trim()) }.toTypedArray()
        }.getOrNull()
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

    private fun warnOnce(key: String, message: String) {
        if (warnedKeys.add(key)) {
            logger.warn(message)
        }
    }

    private fun readPreferences() {
        noHotWordView = try {
            remotePreferences
                .getBoolean(PreferenceKeys.REMOVE_HOT_WORD_VIEW.name, false)
        } catch (_: Throwable) {
            false
        }
        noSearchRecommend = try {
            remotePreferences
                .getBoolean(PreferenceKeys.REMOVE_SEARCH_RECOMMEND.name, false)
        } catch (_: Throwable) {
            false
        }
    }
}
