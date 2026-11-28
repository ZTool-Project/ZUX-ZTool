package com.qimian233.ztool.hook.modules.resolver

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ShortcutInfo
import android.os.Bundle
import android.service.chooser.ChooserTarget
import com.qimian233.ztool.data.keys.BoolKey
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.data.keys.StringKey
import com.qimian233.ztool.data.resolver.ResolverObservedEntry
import com.qimian233.ztool.data.resolver.ResolverRules
import com.qimian233.ztool.hook.base.AppHookModule
import com.qimian233.ztool.provider.ResolverProbeProvider
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Customizes Open-With, Share, and Direct Share target filtering/ordering in ZuiIntentResolver. */
@Suppress("DEPRECATION")
class ResolverMenuHook : AppHookModule() {

    private val reportExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ZTool-ResolverReport").apply { isDaemon = true }
    }
    private val reportedKeys = ConcurrentHashMap<String, MutableSet<String>>()

    override fun getModuleName(): String = PreferenceKeys.RESOLVER_MENU_ENHANCE.name

    override fun getTargetPackages(): Array<out String> =
        arrayOf(ScopeKeys.ZUI_RESOLVER.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val cl = param.defaultClassLoader
        hookFilterIneligibleActivities(cl)
        hookGetLastChosen(cl)
        hookChooserCallerTargets(cl)
        hookProcessSortedList(cl)
        hookChooserAlphaList(cl)
        hookShortcutSelectionAddServiceResults(cl)
        hookCompleteServiceTargetLoading(cl)
        hookDirectShareRowCount(cl)
    }

    private fun hookFilterIneligibleActivities(cl: ClassLoader) {
        try {
            val controllerClass = cl.loadClass("com.zui.resolver.ResolverListController")
            val filterMethod = findMethod(
                controllerClass,
                "filterIneligibleActivities",
                List::class.java,
                Boolean::class.javaPrimitiveType
            )
            val targetIntentField = findField(controllerClass, "mTargetIntent")
            val contextField = findField(controllerClass, "mContext")
            val pmField = findField(controllerClass, "mpm")

            val infoClass = cl.loadClass("com.zui.resolver.ResolvedComponentInfo")
            val nameField = findField(infoClass, "name")
            val getResolveInfoAt = findMethod(infoClass, "getResolveInfoAt", Int::class.javaPrimitiveType)
            val userHandleField = runCatching { findField(ResolveInfo::class.java, "userHandle") }.getOrNull()

            hookWithId(filterMethod, "resolver_filter_ineligible") { chain ->
                val result = chain.proceed()
                val controller = chain.thisObject ?: return@hookWithId result
                @Suppress("UNCHECKED_CAST")
                val list = chain.args[0] as? MutableList<Any?> ?: return@hookWithId result
                if (list.isEmpty()) return@hookWithId result

                val isShare = isShareController(controller, targetIntentField)
                val bucket = if (isShare) ResolverRules.BUCKET_SHARE else ResolverRules.BUCKET_OPEN
                val ctx = contextField.get(controller) as? Context
                val pm = pmField.get(controller) as? android.content.pm.PackageManager
                val targetIntent = targetIntentField.get(controller) as? Intent

                if (ctx != null) {
                    val observed = list.mapNotNull { item ->
                        item ?: return@mapNotNull null
                        val comp = nameField.get(item) as? ComponentName ?: return@mapNotNull null
                        val ri = runCatching { getResolveInfoAt.invoke(item, 0) as? ResolveInfo }.getOrNull()
                        val label = runCatching {
                            if (ri != null && pm != null) ri.loadLabel(pm).toString() else ""
                        }.getOrDefault("")
                        val userId = runCatching {
                            if (ri != null) userHandleField?.get(ri)?.hashCode() ?: 0 else 0
                        }.getOrDefault(0)
                        ResolverObservedEntry(
                            key = ResolverRules.normalizeComponentKey(comp.packageName, comp.className),
                            packageName = comp.packageName,
                            className = ResolverRules.normalizeClassName(comp.packageName, comp.className),
                            label = label,
                            action = targetIntent?.action.orEmpty(),
                            mimeType = targetIntent?.type.orEmpty(),
                            userId = userId,
                            seenAt = System.currentTimeMillis()
                        )
                    }
                    enqueueObservedReport(ctx, bucket, observed)
                }

                val blocked = readRuleSet(
                    if (isShare) PreferenceKeys.RESOLVER_SHARE_BLOCKED else PreferenceKeys.RESOLVER_OPEN_BLOCKED
                )
                if (blocked.isEmpty()) return@hookWithId result

                val snapshot = ArrayList(list)
                val removed = list.removeIf { item ->
                    val comp = item?.let { nameField.get(it) as? ComponentName } ?: return@removeIf false
                    ResolverRules.isComponentBlocked(comp.packageName, comp.className, blocked)
                }
                if (removed && list.isEmpty()) {
                    list.addAll(snapshot)
                    logger.warn("Blocked list matched every item in $bucket; restored original list")
                }
                result
            }
        } catch (t: Throwable) {
            logger.error("Failed hooking ResolverListController.filterIneligibleActivities", t)
        }
    }

    private fun hookGetLastChosen(cl: ClassLoader) {
        try {
            val controllerClass = cl.loadClass("com.zui.resolver.ResolverListController")
            val getLastChosen = findMethod(controllerClass, "getLastChosen")
            val targetIntentField = findField(controllerClass, "mTargetIntent")

            hookWithId(getLastChosen, "resolver_get_last_chosen") { chain ->
                val result = chain.proceed() as? ResolveInfo ?: return@hookWithId null
                val controller = chain.thisObject ?: return@hookWithId result
                if (isShareController(controller, targetIntentField)) return@hookWithId result

                val ai = result.activityInfo ?: return@hookWithId result
                val blocked = readRuleSet(PreferenceKeys.RESOLVER_OPEN_BLOCKED)
                if (ResolverRules.isComponentBlocked(ai.packageName, ai.name, blocked)) {
                    return@hookWithId null
                }
                val pinned = readRuleList(PreferenceKeys.RESOLVER_OPEN_PINNED)
                if (pinned.isNotEmpty() && ResolverRules.componentPinIndex(ai.packageName, ai.name, pinned) != 0) {
                    return@hookWithId null
                }
                result
            }
        } catch (t: Throwable) {
            logger.error("Failed hooking ResolverListController.getLastChosen", t)
        }
    }

    private fun hookChooserCallerTargets(cl: ClassLoader) {
        try {
            val chooserAdapterClass = cl.loadClass("com.zui.resolver.ChooserListAdapter")
            val ctor = chooserAdapterClass.declaredConstructors.maxByOrNull { it.parameterCount }
                ?: throw NoSuchMethodException("ChooserListAdapter constructor not found")
            val callerTargetsField = findField(chooserAdapterClass, "mCallerTargets")
            val contextField = findField(chooserAdapterClass, "mContext")
            val getResolvedComponentName = findMethod(
                cl.loadClass("com.zui.resolver.chooser.DisplayResolveInfo"),
                "getResolvedComponentName"
            )
            val getDisplayLabel = findMethod(
                cl.loadClass("com.zui.resolver.chooser.DisplayResolveInfo"),
                "getDisplayLabel"
            )

            hookWithId(ctor, "chooser_filter_caller_targets") { chain ->
                val result = chain.proceed()
                val adapter = chain.thisObject ?: return@hookWithId result
                @Suppress("UNCHECKED_CAST")
                val callerTargets = callerTargetsField.get(adapter) as? MutableList<Any?> ?: return@hookWithId result
                if (callerTargets.isEmpty()) return@hookWithId result

                val ctx = contextField.get(adapter) as? Context
                if (ctx != null) {
                    val observed = callerTargets.mapNotNull { item ->
                        item ?: return@mapNotNull null
                        val comp = getResolvedComponentName.invoke(item) as? ComponentName ?: return@mapNotNull null
                        val label = (getDisplayLabel.invoke(item) as? CharSequence)?.toString().orEmpty()
                        ResolverObservedEntry(
                            key = ResolverRules.normalizeComponentKey(comp.packageName, comp.className),
                            packageName = comp.packageName,
                            className = ResolverRules.normalizeClassName(comp.packageName, comp.className),
                            label = label,
                            action = Intent.ACTION_SEND,
                            seenAt = System.currentTimeMillis()
                        )
                    }
                    enqueueObservedReport(ctx, ResolverRules.BUCKET_SHARE, observed)
                }

                val blocked = readRuleSet(PreferenceKeys.RESOLVER_SHARE_BLOCKED)
                if (blocked.isEmpty()) return@hookWithId result
                callerTargets.removeIf { item ->
                    val comp = item?.let { getResolvedComponentName.invoke(it) as? ComponentName }
                        ?: return@removeIf false
                    ResolverRules.isComponentBlocked(comp.packageName, comp.className, blocked)
                }
                result
            }
        } catch (t: Throwable) {
            logger.error("Failed hooking ChooserListAdapter constructor", t)
        }
    }

    private fun hookProcessSortedList(cl: ClassLoader) {
        try {
            val adapterClass = cl.loadClass("com.zui.resolver.ResolverListAdapter")
            val processSortedList = findMethod(
                adapterClass,
                "processSortedList",
                List::class.java,
                Boolean::class.javaPrimitiveType
            )
            val displayListField = findField(adapterClass, "mDisplayList")
            val lastChosenField = findField(adapterClass, "mLastChosen")
            val lastChosenPosField = findField(adapterClass, "mLastChosenPosition")
            val otherProfileField = findField(adapterClass, "mOtherProfile")

            val driClass = cl.loadClass("com.zui.resolver.chooser.DisplayResolveInfo")
            val getResolvedComponentName = findMethod(driClass, "getResolvedComponentName")
            val getResolveInfo = findMethod(driClass, "getResolveInfo")
            val setPinned = findMethod(driClass, "setPinned", Boolean::class.javaPrimitiveType)

            hookWithId(processSortedList, "resolver_process_sorted_list") { chain ->
                val result = chain.proceed()
                val adapter = chain.thisObject ?: return@hookWithId result
                @Suppress("UNCHECKED_CAST")
                val displayList = displayListField.get(adapter) as? MutableList<Any?> ?: return@hookWithId result
                if (displayList.isEmpty()) return@hookWithId result

                val isShare = adapter.javaClass.name == "com.zui.resolver.ChooserListAdapter"
                val pinned = readRuleList(
                    if (isShare) PreferenceKeys.RESOLVER_SHARE_PINNED else PreferenceKeys.RESOLVER_OPEN_PINNED
                )
                if (pinned.isEmpty()) return@hookWithId result

                val reordered = stableSortByPin(displayList) { item ->
                    val comp = item?.let { getResolvedComponentName.invoke(it) as? ComponentName }
                        ?: return@stableSortByPin Int.MAX_VALUE
                    val idx = ResolverRules.componentPinIndex(comp.packageName, comp.className, pinned)
                    if (isShare && idx != Int.MAX_VALUE) {
                        runCatching { setPinned.invoke(item, true) }
                    }
                    idx
                }
                displayList.clear()
                displayList.addAll(reordered)

                if (!isShare) {
                    recomputeLastChosenPosition(
                        adapter,
                        displayList,
                        lastChosenField,
                        lastChosenPosField,
                        otherProfileField,
                        getResolveInfo
                    )
                }
                result
            }
        } catch (t: Throwable) {
            logger.error("Failed hooking ResolverListAdapter.processSortedList", t)
        }
    }

    private fun hookChooserAlphaList(cl: ClassLoader) {
        try {
            val asyncClass = cl.loadClass($$"com.zui.resolver.ChooserListAdapter$2")
            val onPostExecute = findMethod(asyncClass, "onPostExecute", List::class.java)
            val driClass = cl.loadClass("com.zui.resolver.chooser.DisplayResolveInfo")
            val getResolvedComponentName = findMethod(driClass, "getResolvedComponentName")
            val getAllDisplayTargets = findMethod(driClass, "getAllDisplayTargets")
            val setPinned = findMethod(driClass, "setPinned", Boolean::class.javaPrimitiveType)

            hookWithId(onPostExecute, "chooser_alpha_list_pin") { chain ->
                @Suppress("UNCHECKED_CAST")
                val list = chain.args[0] as? MutableList<Any?>
                val pinned = readRuleList(PreferenceKeys.RESOLVER_SHARE_PINNED)
                if (list != null && list.isNotEmpty() && pinned.isNotEmpty()) {
                    val reordered = stableSortByPin(list) { item ->
                        if (item == null) return@stableSortByPin Int.MAX_VALUE
                        var bestIdx = Int.MAX_VALUE
                        @Suppress("UNCHECKED_CAST")
                        val targets = runCatching { getAllDisplayTargets.invoke(item) as? List<Any?> }.getOrNull()
                        if (!targets.isNullOrEmpty()) {
                            for (t in targets) {
                                val comp = t?.let { getResolvedComponentName.invoke(it) as? ComponentName } ?: continue
                                val idx = ResolverRules.componentPinIndex(comp.packageName, comp.className, pinned)
                                if (idx < bestIdx) bestIdx = idx
                            }
                        } else {
                            val comp = getResolvedComponentName.invoke(item) as? ComponentName
                            if (comp != null) {
                                bestIdx = ResolverRules.componentPinIndex(comp.packageName, comp.className, pinned)
                            }
                        }
                        if (bestIdx != Int.MAX_VALUE) {
                            runCatching { setPinned.invoke(item, true) }
                        }
                        bestIdx
                    }
                    list.clear()
                    list.addAll(reordered)
                }
                chain.proceed()
            }
        } catch (t: Throwable) {
            logger.error("Failed hooking ChooserListAdapter$2.onPostExecute", t)
        }
    }

    private fun hookShortcutSelectionAddServiceResults(cl: ClassLoader) {
        try {
            val logicClass = cl.loadClass("com.zui.resolver.ShortcutSelectionLogic")
            val addMethod = findMethod(
                logicClass,
                "addServiceResults",
                cl.loadClass("com.zui.resolver.chooser.DisplayResolveInfo"),
                Float::class.javaPrimitiveType,
                List::class.java,
                Boolean::class.javaPrimitiveType,
                Map::class.java,
                Map::class.java,
                Context::class.java,
                Intent::class.java,
                Intent::class.java,
                Int::class.javaPrimitiveType,
                List::class.java
            )
            val maxPerAppField = findField(logicClass, "mMaxShortcutTargetsPerApp")

            hookWithId(addMethod, "chooser_direct_share_add") { chain ->
                val rawTargets = chain.args[2] as? List<*> ?: return@hookWithId chain.proceed()
                val isShortcut = chain.args[3] as? Boolean ?: false
                @Suppress("UNCHECKED_CAST")
                val shortcutMap = chain.args[4] as? Map<ChooserTarget, ShortcutInfo>
                val ctx = chain.args[6] as? Context

                if (ctx != null && rawTargets.isNotEmpty()) {
                    val observed = rawTargets.mapNotNull { obj ->
                        val ct = obj as? ChooserTarget ?: return@mapNotNull null
                        val info = if (isShortcut) shortcutMap?.get(ct) else null
                        val sid = extractShortcutId(ct, info) ?: return@mapNotNull null
                        val comp = ct.componentName ?: return@mapNotNull null
                        val label = (info?.shortLabel ?: ct.title)?.toString().orEmpty()
                        ResolverObservedEntry(
                            key = ResolverRules.directShareKey(comp.packageName, sid),
                            packageName = comp.packageName,
                            className = comp.className,
                            label = label,
                            shortcutId = sid,
                            seenAt = System.currentTimeMillis()
                        )
                    }
                    enqueueObservedReport(ctx, ResolverRules.BUCKET_DIRECT_SHARE, observed)
                }

                if (readBoolean(PreferenceKeys.RESOLVER_DIRECT_SHARE_HIDE_ALL)) {
                    return@hookWithId false
                }

                val blocked = readRuleSet(PreferenceKeys.RESOLVER_DIRECT_SHARE_BLOCKED)
                val pinned = readRuleList(PreferenceKeys.RESOLVER_DIRECT_SHARE_PINNED)

                val filtered = rawTargets.filter { obj ->
                    val ct = obj as? ChooserTarget ?: return@filter false
                    val comp = ct.componentName ?: return@filter true
                    val info = if (isShortcut) shortcutMap?.get(ct) else null
                    val sid = extractShortcutId(ct, info).orEmpty()
                    !ResolverRules.isDirectShareBlocked(comp.packageName, sid, blocked)
                }
                if (filtered.isEmpty()) {
                    return@hookWithId false
                }

                val reordered = if (pinned.isNotEmpty()) {
                    stableSortByPin(filtered) { obj ->
                        val ct = obj as? ChooserTarget ?: return@stableSortByPin Int.MAX_VALUE
                        val comp = ct.componentName ?: return@stableSortByPin Int.MAX_VALUE
                        val info = if (isShortcut) shortcutMap?.get(ct) else null
                        val sid = extractShortcutId(ct, info).orEmpty()
                        ResolverRules.directSharePinIndex(comp.packageName, sid, pinned)
                    }
                } else {
                    filtered
                }

                val pinnedCount = if (pinned.isEmpty()) 0 else reordered.count { obj ->
                    val ct = obj as? ChooserTarget ?: return@count false
                    val comp = ct.componentName ?: return@count false
                    val info = if (isShortcut) shortcutMap?.get(ct) else null
                    val sid = extractShortcutId(ct, info).orEmpty()
                    ResolverRules.directSharePinIndex(comp.packageName, sid, pinned) != Int.MAX_VALUE
                }

                val argsCopy = chain.args.toTypedArray()
                argsCopy[2] = ArrayList(reordered)

                val instance = chain.thisObject
                if (instance != null && isShortcut && pinnedCount > 0) {
                    val originalMax = maxPerAppField.getInt(instance)
                    if (pinnedCount > originalMax) {
                        maxPerAppField.setInt(instance, pinnedCount)
                        return@hookWithId try {
                            chain.proceed(argsCopy)
                        } finally {
                            maxPerAppField.setInt(instance, originalMax)
                        }
                    }
                }
                chain.proceed(argsCopy)
            }
        } catch (t: Throwable) {
            logger.error("Failed hooking ShortcutSelectionLogic.addServiceResults", t)
        }
    }

    private fun hookCompleteServiceTargetLoading(cl: ClassLoader) {
        try {
            val adapterClass = cl.loadClass("com.zui.resolver.ChooserListAdapter")
            val completeMethod = findMethod(adapterClass, "completeServiceTargetLoading")
            val serviceTargetsField = findField(adapterClass, "mServiceTargets")

            val targetInfoClass = cl.loadClass("com.zui.resolver.chooser.TargetInfo")
            val isSelectable = findMethod(targetInfoClass, "isSelectableTargetInfo")
            val getShortcutInfo = findMethod(targetInfoClass, "getDirectShareShortcutInfo")
            val getChooserTargetComponentName = findMethod(targetInfoClass, "getChooserTargetComponentName")
            val getResolvedComponentName = findMethod(targetInfoClass, "getResolvedComponentName")
            val selectableClass = cl.loadClass("com.zui.resolver.chooser.SelectableTargetInfo")
            val extrasField = findField(selectableClass, "mChooserTargetIntentExtras")

            hookWithId(completeMethod, "chooser_direct_share_finalize") { chain ->
                val result = chain.proceed()
                val adapter = chain.thisObject ?: return@hookWithId result
                @Suppress("UNCHECKED_CAST")
                val targets = serviceTargetsField.get(adapter) as? MutableList<Any?> ?: return@hookWithId result
                if (targets.isEmpty()) return@hookWithId result

                val pinned = readRuleList(PreferenceKeys.RESOLVER_DIRECT_SHARE_PINNED)
                if (pinned.isEmpty()) return@hookWithId result

                val reordered = stableSortByPin(targets) { item ->
                    if (item == null || isSelectable.invoke(item) != true) return@stableSortByPin Int.MAX_VALUE
                    val comp = (getChooserTargetComponentName.invoke(item) as? ComponentName)
                        ?: (getResolvedComponentName.invoke(item) as? ComponentName)
                        ?: return@stableSortByPin Int.MAX_VALUE
                    val shortcutInfo = getShortcutInfo.invoke(item) as? ShortcutInfo
                    val sid = shortcutInfo?.id ?: runCatching {
                        val bundle = if (selectableClass.isInstance(item)) extrasField.get(item) as? Bundle else null
                        bundle?.getString("android.intent.extra.shortcut.ID")
                    }.getOrNull().orEmpty()
                    ResolverRules.directSharePinIndex(comp.packageName, sid, pinned)
                }
                targets.clear()
                targets.addAll(reordered)
                runCatching {
                    findMethod(adapterClass, "notifyDataSetChanged").invoke(adapter)
                }
                result
            }
        } catch (t: Throwable) {
            logger.error("Failed hooking ChooserListAdapter.completeServiceTargetLoading", t)
        }
    }

    private fun hookDirectShareRowCount(cl: ClassLoader) {
        try {
            val gridAdapterClass = cl.loadClass("com.zui.resolver.grid.ChooserGridAdapter")
            val getServiceRowCount = findMethod(gridAdapterClass, "getServiceTargetRowCount")
            hookWithId(getServiceRowCount, "chooser_direct_share_hide_row") { chain ->
                if (readBoolean(PreferenceKeys.RESOLVER_DIRECT_SHARE_HIDE_ALL)) {
                    return@hookWithId 0
                }
                chain.proceed()
            }
        } catch (t: Throwable) {
            logger.error("Failed hooking ChooserGridAdapter.getServiceTargetRowCount", t)
        }
    }

    private fun isShareController(controller: Any, targetIntentField: Field): Boolean {
        if (controller.javaClass.name == "com.zui.resolver.ChooserListController") return true
        val intent = runCatching { targetIntentField.get(controller) as? Intent }.getOrNull() ?: return false
        val action = intent.action
        return action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE
    }

    private fun extractShortcutId(target: ChooserTarget, shortcutInfo: ShortcutInfo?): String? {
        val fromInfo = shortcutInfo?.id
        if (!fromInfo.isNullOrBlank()) return fromInfo
        val extras = runCatching { target.intentExtras }.getOrNull()
        val fromExtra = extras?.getString("android.intent.extra.shortcut.ID")
        return fromExtra?.takeIf { it.isNotBlank() }
    }

    private fun recomputeLastChosenPosition(
        adapter: Any,
        displayList: List<Any?>,
        lastChosenField: Field,
        lastChosenPosField: Field,
        otherProfileField: Field,
        getResolveInfo: Method
    ) {
        if (otherProfileField.get(adapter) != null) {
            lastChosenPosField.setInt(adapter, -1)
            return
        }
        val lastChosen = lastChosenField.get(adapter) as? ResolveInfo
        if (lastChosen == null) {
            lastChosenPosField.setInt(adapter, -1)
            return
        }
        val targetAi = lastChosen.activityInfo
        val newIndex = displayList.indexOfFirst { item ->
            val ri = item?.let { getResolveInfo.invoke(it) as? ResolveInfo } ?: return@indexOfFirst false
            val ai = ri.activityInfo ?: return@indexOfFirst false
            ai.packageName == targetAi.packageName && ai.name == targetAi.name
        }
        lastChosenPosField.setInt(adapter, newIndex)
    }

    private fun <T> stableSortByPin(items: List<T>, pinIndexOf: (T) -> Int): List<T> {
        data class IndexedItem<T>(val item: T, val pinIndex: Int, val originalOrder: Int)
        return items.mapIndexed { i, item -> IndexedItem(item, pinIndexOf(item), i) }
            .sortedWith(compareBy<IndexedItem<T>> { it.pinIndex }.thenBy { it.originalOrder })
            .map { it.item }
    }

    private fun enqueueObservedReport(
        context: Context,
        bucket: String,
        entries: List<ResolverObservedEntry>
    ) {
        if (entries.isEmpty()) return
        val seenSet = reportedKeys.computeIfAbsent(bucket) { ConcurrentHashMap.newKeySet() }
        val delta = entries.filter { seenSet.add(it.key) }
        if (delta.isEmpty()) return

        val appContext = context.applicationContext ?: context
        val payload = ResolverRules.encodeObservedEntries(delta)
        reportExecutor.execute {
            try {
                val extras = Bundle().apply {
                    putString(ResolverProbeProvider.EXTRA_BUCKET, bucket)
                    putString(ResolverProbeProvider.EXTRA_ENTRIES_JSON, payload)
                }
                appContext.contentResolver.call(
                    ResolverProbeProvider.URI,
                    ResolverProbeProvider.METHOD_REPORT_TARGETS,
                    null,
                    extras
                )
            } catch (t: Throwable) {
                delta.forEach { seenSet.remove(it.key) }
                logger.debug("Observed report failed for bucket=$bucket: ${t.message}")
            }
        }
    }

    private fun readBoolean(key: BoolKey): Boolean =
        remotePreferences.getBoolean(key.name, key.default)

    private fun readRuleList(key: StringKey): List<String> =
        ResolverRules.decodeList(remotePreferences.getString(key.name, key.default))

    private fun readRuleSet(key: StringKey): Set<String> =
        readRuleList(key).toHashSet()
}
