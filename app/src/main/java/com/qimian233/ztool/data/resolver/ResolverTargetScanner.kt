package com.qimian233.ztool.data.resolver

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import com.qimian233.ztool.EnhancedShellExecutor
import com.qimian233.ztool.data.keys.ScopeKeys

enum class CandidateSource {
    Observed,
    ActiveProbe,
    RootDumpsys,
    SavedRule
}

data class ResolverCandidate(
    val key: String,
    val packageName: String,
    val className: String,
    val appLabel: String,
    val entryLabel: String,
    val shortcutId: String = "",
    val recentlySeen: Boolean = false,
    val source: CandidateSource = CandidateSource.ActiveProbe
)

data class ResolverScanResult(
    val openTargets: List<ResolverCandidate> = emptyList(),
    val shareTargets: List<ResolverCandidate> = emptyList(),
    val directShareTargets: List<ResolverCandidate> = emptyList()
)

/** Discovers Open-With, Share, and Direct Share candidates from PM probes, hook cache, and root dumpsys. */
class ResolverTargetScanner(
    private val context: Context,
    private val observedStore: ResolverObservedStore = ResolverObservedStore(context),
    private val shellExecutor: EnhancedShellExecutor = EnhancedShellExecutor.getInstance()
) {
    private val pm: PackageManager = context.packageManager

    fun scanAll(
        savedOpenKeys: List<String> = emptyList(),
        savedShareKeys: List<String> = emptyList(),
        savedDirectShareKeys: List<String> = emptyList(),
        forceRootEnrichment: Boolean = false
    ): ResolverScanResult {
        val observed = observedStore.readAll()

        val activeOpen = queryOpenProbeMatrix()
        val activeShare = queryShareProbeMatrix()

        var dumpsysOpen: List<ResolverObservedEntry> = emptyList()
        var dumpsysShare: List<ResolverObservedEntry> = emptyList()
        if (forceRootEnrichment || activeOpen.isEmpty() || activeShare.isEmpty()) {
            val res = shellExecutor.executeRootCommand("dumpsys package resolvers", 15)
            if (res.isSuccess && res.output.isNotBlank()) {
                val parsed = parseDumpsysPackageResolvers(res.output)
                dumpsysOpen = parsed.first
                dumpsysShare = parsed.second
            }
        }

        val directShareSourceEntries = if (forceRootEnrichment || observed.directShare.isEmpty()) {
            val res = shellExecutor.executeRootCommand("dumpsys shortcut", 15)
            val parsed = if (res.isSuccess && res.output.isNotBlank()) {
                parseDumpsysShortcut(res.output)
            } else {
                emptyList()
            }
            parsed
        } else {
            emptyList()
        }

        val openList = mergeCandidates(
            observedEntries = observed.open,
            activeCandidates = activeOpen,
            dumpsysEntries = dumpsysOpen,
            savedKeys = savedOpenKeys,
            isDirectShare = false
        )
        val shareList = mergeCandidates(
            observedEntries = observed.share,
            activeCandidates = activeShare,
            dumpsysEntries = dumpsysShare,
            savedKeys = savedShareKeys,
            isDirectShare = false
        )
        val directList = mergeCandidates(
            observedEntries = observed.directShare,
            activeCandidates = emptyList(),
            dumpsysEntries = directShareSourceEntries,
            savedKeys = savedDirectShareKeys,
            isDirectShare = true
        )

        return ResolverScanResult(
            openTargets = openList,
            shareTargets = shareList,
            directShareTargets = directList
        )
    }

    private fun queryOpenProbeMatrix(): List<ResolverCandidate> {
        val intents = buildList {
            add(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
            )
            for (mime in OPEN_MIME_PROBES) {
                add(Intent(Intent.ACTION_VIEW).apply { setType(mime) })
            }
            add(Intent(Intent.ACTION_EDIT).apply { setType("image/*") })
        }
        return queryIntentList(intents)
    }

    private fun queryShareProbeMatrix(): List<ResolverCandidate> {
        val intents = buildList {
            for (mime in SHARE_MIME_PROBES) {
                add(Intent(Intent.ACTION_SEND).apply { setType(mime) })
            }
            for (mime in SHARE_MULTI_MIME_PROBES) {
                add(Intent(Intent.ACTION_SEND_MULTIPLE).apply { setType(mime) })
            }
        }
        return queryIntentList(intents)
    }

    private fun queryIntentList(intents: List<Intent>): List<ResolverCandidate> {
        val result = LinkedHashMap<String, ResolverCandidate>()
        val flags = PackageManager.ResolveInfoFlags.of(
            (PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_ALL).toLong()
        )
        val appLabelCache = HashMap<String, String>()

        for (intent in intents) {
            val resolved: List<ResolveInfo> = runCatching {
                pm.queryIntentActivities(intent, flags)
            }.getOrDefault(emptyList())

            for (ri in resolved) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName ?: continue
                if (pkg == ScopeKeys.ZUI_RESOLVER.packageName || pkg == "android") continue
                val fullCls = ResolverRules.normalizeClassName(pkg, ai.name ?: "")
                val key = ResolverRules.normalizeComponentKey(pkg, fullCls)
                if (key in result) continue

                val appLabel = appLabelCache.getOrPut(pkg) { loadAppLabel(pkg) }
                val entryLabel = runCatching { ri.loadLabel(pm).toString() }
                    .getOrDefault("")
                    .ifBlank { appLabel }
                result[key] = ResolverCandidate(
                    key = key,
                    packageName = pkg,
                    className = fullCls,
                    appLabel = appLabel,
                    entryLabel = entryLabel,
                    recentlySeen = false,
                    source = CandidateSource.ActiveProbe
                )
            }
        }
        return result.values.toList()
    }

    private fun mergeCandidates(
        observedEntries: List<ResolverObservedEntry>,
        activeCandidates: List<ResolverCandidate>,
        dumpsysEntries: List<ResolverObservedEntry>,
        savedKeys: List<String>,
        isDirectShare: Boolean
    ): List<ResolverCandidate> {
        val map = LinkedHashMap<String, ResolverCandidate>()
        val appLabelCache = HashMap<String, String>()

        fun appLabelFor(pkg: String): String =
            appLabelCache.getOrPut(pkg) { loadAppLabel(pkg) }

        for (entry in observedEntries) {
            if (entry.packageName == ScopeKeys.ZUI_RESOLVER.packageName || entry.packageName == "android") {
                continue
            }
            val appLabel = appLabelFor(entry.packageName)
            map[entry.key] = ResolverCandidate(
                key = entry.key,
                packageName = entry.packageName,
                className = entry.className,
                appLabel = appLabel,
                entryLabel = entry.label.ifBlank { appLabel },
                shortcutId = entry.shortcutId,
                recentlySeen = true,
                source = CandidateSource.Observed
            )
        }

        for (candidate in activeCandidates) {
            if (candidate.key !in map) {
                map[candidate.key] = candidate
            }
        }

        for (entry in dumpsysEntries) {
            if (entry.packageName == ScopeKeys.ZUI_RESOLVER.packageName || entry.packageName == "android") {
                continue
            }
            if (entry.key !in map) {
                val appLabel = appLabelFor(entry.packageName)
                map[entry.key] = ResolverCandidate(
                    key = entry.key,
                    packageName = entry.packageName,
                    className = entry.className,
                    appLabel = appLabel,
                    entryLabel = entry.label.ifBlank { entry.shortcutId.ifBlank { appLabel } },
                    shortcutId = entry.shortcutId,
                    recentlySeen = false,
                    source = CandidateSource.RootDumpsys
                )
            }
        }

        for (rawKey in savedKeys) {
            if (rawKey.isBlank() || rawKey in map) continue
            val (pkg, tail) = if (isDirectShare) {
                val idx = rawKey.indexOf('|')
                if (idx > 0) rawKey.substring(0, idx) to rawKey.substring(idx + 1) else rawKey to ""
            } else {
                val idx = rawKey.indexOf('/')
                if (idx > 0) rawKey.substring(0, idx) to rawKey.substring(idx + 1) else rawKey to ""
            }
            val appLabel = appLabelFor(pkg)
            val entryLabel = when {
                tail == "*" || tail.isEmpty() -> appLabel
                isDirectShare -> tail
                else -> tail.substringAfterLast('.')
            }
            map[rawKey] = ResolverCandidate(
                key = rawKey,
                packageName = pkg,
                className = if (isDirectShare) "" else tail,
                appLabel = appLabel,
                entryLabel = entryLabel,
                shortcutId = if (isDirectShare) tail else "",
                recentlySeen = false,
                source = CandidateSource.SavedRule
            )
        }

        return map.values.sortedWith(
            compareByDescending<ResolverCandidate> { it.recentlySeen }
                .thenBy { it.appLabel.lowercase() }
                .thenBy { it.entryLabel.lowercase() }
        )
    }

    private fun loadAppLabel(packageName: String): String {
        return runCatching {
            val info = pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            pm.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }

    companion object {
        private val OPEN_MIME_PROBES = listOf(
            "image/jpeg",
            "image/*",
            "video/mp4",
            "audio/mpeg",
            "text/plain",
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "application/zip",
            "application/vnd.android.package-archive",
            "application/octet-stream",
            "*/*"
        )

        private val SHARE_MIME_PROBES = listOf(
            "*/*",
            "text/plain",
            "image/*",
            "video/*",
            "audio/*",
            "application/pdf",
            "application/octet-stream"
        )

        private val SHARE_MULTI_MIME_PROBES = listOf(
            "*/*",
            "image/*",
            "video/*"
        )

        private val PACKAGE_LINE_REGEX = Regex("""^\s*Package:\s+(\S+)""")
        private val SHORTCUT_HEADER_REGEX = Regex("""^\s*ShortcutInfo\s+\{id=([^,}]+)""")
        private val ACTIVITY_COMP_REGEX = Regex("""ComponentInfo\{([^/}]+)/([^}]+)\}""")
        private val CATEGORIES_REGEX = Regex("""categories=\{([^}]*)\}""")
        private val COMPONENT_ENTRY_REGEX = Regex("""^\s+[0-9a-fA-F]+\s+([a-zA-Z0-9_.-]+/[a-zA-Z0-9_.$-]+)(?:\s|$)""")

        /** Parses `dumpsys package resolvers` Activity Resolver Table into (open, share) entries. */
        fun parseDumpsysPackageResolvers(output: String): Pair<List<ResolverObservedEntry>, List<ResolverObservedEntry>> {
            val openMap = LinkedHashMap<String, ResolverObservedEntry>()
            val shareMap = LinkedHashMap<String, ResolverObservedEntry>()

            var inActivityTable = false
            var currentAction = ""

            for (rawLine in output.lineSequence()) {
                val trimmed = rawLine.trim()
                if (trimmed == "Activity Resolver Table:") {
                    inActivityTable = true
                    currentAction = ""
                    continue
                }
                if (inActivityTable && (
                        trimmed == "Receiver Resolver Table:" ||
                            trimmed == "Service Resolver Table:" ||
                            trimmed == "Provider Resolver Table:"
                        )
                ) {
                    break
                }
                if (!inActivityTable) continue

                if (trimmed.endsWith(":") && !trimmed.contains(' ') && !trimmed.contains('/')) {
                    currentAction = trimmed.dropLast(1)
                    continue
                }

                val isOpenAction = currentAction == Intent.ACTION_VIEW || currentAction == Intent.ACTION_EDIT
                val isShareAction = currentAction == Intent.ACTION_SEND || currentAction == Intent.ACTION_SEND_MULTIPLE
                if (!isOpenAction && !isShareAction) continue

                val match = COMPONENT_ENTRY_REGEX.find(rawLine) ?: continue
                val rawComp = match.groupValues[1]
                val slash = rawComp.indexOf('/')
                if (slash <= 0) continue
                val pkg = rawComp.substring(0, slash)
                val cls = ResolverRules.normalizeClassName(pkg, rawComp.substring(slash + 1))
                val key = ResolverRules.normalizeComponentKey(pkg, cls)
                val entry = ResolverObservedEntry(
                    key = key,
                    packageName = pkg,
                    className = cls,
                    label = cls.substringAfterLast('.'),
                    action = currentAction
                )
                if (isOpenAction) {
                    openMap.putIfAbsent(key, entry)
                } else {
                    shareMap.putIfAbsent(key, entry)
                }
            }
            return openMap.values.toList() to shareMap.values.toList()
        }

        /** Parses `dumpsys shortcut` for conversation/share-target shortcuts. */
        fun parseDumpsysShortcut(output: String): List<ResolverObservedEntry> {
            val results = LinkedHashMap<String, ResolverObservedEntry>()

            var currentPkg = ""
            var inShortcut = false
            var shortcutId = ""
            var shortcutPkg = ""
            var shortcutClass = ""
            var shortLabel = ""
            var longLabel = ""
            var categories: Set<String> = emptySet()

            fun flushShortcut() {
                if (!inShortcut) return
                val pkg = shortcutPkg.ifBlank { currentPkg }
                if (pkg.isNotBlank() && shortcutId.isNotBlank() && categories.isNotEmpty()) {
                    val key = ResolverRules.directShareKey(pkg, shortcutId)
                    val label = shortLabel.ifBlank { longLabel }.ifBlank { shortcutId }
                    results.putIfAbsent(
                        key,
                        ResolverObservedEntry(
                            key = key,
                            packageName = pkg,
                            className = shortcutClass,
                            label = label,
                            shortcutId = shortcutId
                        )
                    )
                }
                inShortcut = false
                shortcutId = ""
                shortcutPkg = ""
                shortcutClass = ""
                shortLabel = ""
                longLabel = ""
                categories = emptySet()
            }

            for (rawLine in output.lineSequence()) {
                val pkgMatch = PACKAGE_LINE_REGEX.find(rawLine)
                if (pkgMatch != null) {
                    flushShortcut()
                    currentPkg = pkgMatch.groupValues[1].trim()
                    continue
                }

                val headerMatch = SHORTCUT_HEADER_REGEX.find(rawLine)
                if (headerMatch != null) {
                    flushShortcut()
                    inShortcut = true
                    shortcutId = headerMatch.groupValues[1].trim()
                    continue
                }

                if (!inShortcut) continue

                val trimmed = rawLine.trim()
                when {
                    trimmed.startsWith("packageName=") -> {
                        shortcutPkg = trimmed.substringAfter("packageName=").trim()
                    }
                    trimmed.startsWith("activity=") -> {
                        val compMatch = ACTIVITY_COMP_REGEX.find(trimmed)
                        if (compMatch != null) {
                            shortcutClass = ResolverRules.normalizeClassName(
                                compMatch.groupValues[1],
                                compMatch.groupValues[2]
                            )
                        }
                    }
                    trimmed.startsWith("shortLabel=") -> {
                        shortLabel = extractLabelValue(trimmed, "shortLabel=")
                    }
                    trimmed.startsWith("longLabel=") -> {
                        longLabel = extractLabelValue(trimmed, "longLabel=")
                    }
                    trimmed.startsWith("categories=") -> {
                        val catMatch = CATEGORIES_REGEX.find(trimmed)
                        categories = if (catMatch != null) {
                            catMatch.groupValues[1].split(',')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .toSet()
                        } else {
                            emptySet()
                        }
                    }
                }
            }
            flushShortcut()
            return results.values.toList()
        }

        private fun extractLabelValue(line: String, prefix: String): String {
            val raw = line.substringAfter(prefix)
            val beforeResId = raw.substringBefore(", resId=").trim()
            return if (beforeResId == "null") "" else beforeResId
        }
    }
}
