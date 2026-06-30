package site.anzz.childkiosk.util.filter

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

data class FilterPerfDiagnosticSnapshot(
    val snapshot: FilterPerfSnapshot,
    val updatedAt: Long,
    val processName: String
)

object FilterRepository {
    private const val PREFS_NAME = "kiosk_filter_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PRESET = "preset"
    private const val KEY_SUBSCRIPTIONS = "subscriptions"
    private const val KEY_CUSTOM_RULES = "custom_rules"
    private const val KEY_SITE_OVERRIDES = "site_overrides"
    private const val KEY_EVENTS = "events"
    private const val RULE_DIR = "filter_subscriptions"
    private const val EVENTS_FILE = "filter_events.json"
    private const val EVENTS_CLEARED_FILE = "filter_events_cleared_at.txt"
    private const val PERF_SNAPSHOT_FILE = "filter_perf_snapshot.json"
    private const val DIAGNOSTICS_RESET_FILE = "filter_diagnostics_reset_at.txt"
    private const val MAX_EVENTS = 200
    private const val PERF_SNAPSHOT_WRITE_INTERVAL_MS = 2_000L
    private const val DIAGNOSTICS_RESET_CHECK_INTERVAL_MS = 500L

    private val engineCache = object : LinkedHashMap<String, FilterEngine>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FilterEngine>?): Boolean {
            return size > 4
        }
    }
    private val eventLock = Any()
    private val perfSnapshotLock = Any()
    private val eventGeneration = AtomicLong(0L)
    private val lastPerfSnapshotWriteAt = AtomicLong(0L)
    private val lastDiagnosticsResetAppliedAt = AtomicLong(0L)
    private val lastDiagnosticsResetCheckAt = AtomicLong(0L)
    private val eventExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ChildKioskFilterEvents").apply { isDaemon = true }
    }
    private val perfSnapshotExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ChildKioskFilterPerfSnapshot").apply { isDaemon = true }
    }

    fun getSettings(context: Context): FilterSettings {
        val prefs = prefs(context)
        val preset = FilterPreset.fromStorage(prefs.getString(KEY_PRESET, FilterPreset.STANDARD_CHILD.storageValue))
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val subscriptionOverrides = readSubscriptionOverrides(prefs)
        val defaultSubscriptions = FilterCatalog.defaultSubscriptionsFor(preset)
        val subscriptions = defaultSubscriptions.map { subscription ->
            val override = subscriptionOverrides[subscription.id]
            if (override == null) {
                subscription
            } else {
                subscription.copy(
                    enabled = override.optBoolean("enabled", subscription.enabled),
                    lastUpdatedAt = override.optLong("lastUpdatedAt", subscription.lastUpdatedAt),
                    ruleCount = override.optInt("ruleCount", subscription.ruleCount),
                    enabledRuleCount = override.optInt("enabledRuleCount", subscription.enabledRuleCount),
                    unsupportedCount = override.optInt("unsupportedCount", subscription.unsupportedCount),
                    lastError = override.optString("lastError", subscription.lastError)
                )
            }
        } + subscriptionOverrides.values
            .filter { json -> FilterCatalog.builtInSubscriptions.none { it.id == json.optString("id") } }
            .mapNotNull { json -> FilterSubscription.fromJson(json) }
        return FilterSettings(
            enabled = enabled,
            preset = preset,
            customRules = prefs.getString(KEY_CUSTOM_RULES, "") ?: "",
            subscriptions = subscriptions,
            siteOverrides = readSiteOverrides(prefs)
        )
    }

    fun getRuntimeSnapshot(context: Context): FilterRuntimeSnapshot {
        return getSettings(context).toRuntimeSnapshot()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        invalidate()
    }

    fun setPreset(context: Context, preset: FilterPreset) {
        val existing = getSettings(context).subscriptions.associateBy { it.id }
        val defaults = FilterCatalog.defaultSubscriptionsFor(preset)
        val next = defaults.map { subscription ->
            existing[subscription.id]?.let { old ->
                subscription.copy(
                    lastUpdatedAt = old.lastUpdatedAt,
                    ruleCount = old.ruleCount,
                    enabledRuleCount = old.enabledRuleCount,
                    unsupportedCount = old.unsupportedCount,
                    lastError = old.lastError
                )
            } ?: subscription
        }
        val subscriptionsJson = JSONArray(next.map { it.toJson() })
        prefs(context).edit()
            .putString(KEY_PRESET, preset.storageValue)
            .putString(KEY_SUBSCRIPTIONS, subscriptionsJson.toString())
            .apply()
        invalidate()
    }

    fun setSubscriptionEnabled(context: Context, id: String, enabled: Boolean) {
        val current = getSettings(context).subscriptions
        val next = current.map { if (it.id == id) it.copy(enabled = enabled) else it }
        prefs(context).edit()
            .putString(KEY_PRESET, FilterPreset.CUSTOM.storageValue)
            .putString(KEY_SUBSCRIPTIONS, JSONArray(next.map { it.toJson() }).toString())
            .apply()
        invalidate()
    }

    fun addCustomSubscription(context: Context, title: String, url: String): FilterSubscription {
        val cleanUrl = normalizeSubscriptionUrl(url)
        require(cleanUrl.startsWith("https://")) { "仅支持 HTTPS 订阅 URL" }
        val subscription = FilterCatalog.customSubscription(title.trim(), cleanUrl)
        val next = getSettings(context).subscriptions
            .filterNot { it.id == subscription.id }
            .plus(subscription)
        prefs(context).edit()
            .putString(KEY_PRESET, FilterPreset.CUSTOM.storageValue)
            .putString(KEY_SUBSCRIPTIONS, JSONArray(next.map { it.toJson() }).toString())
            .apply()
        invalidate()
        return subscription
    }

    fun removeCustomSubscription(context: Context, id: String) {
        if (FilterCatalog.builtInSubscriptions.any { it.id == id }) return
        val next = getSettings(context).subscriptions.filterNot { it.id == id }
        prefs(context).edit()
            .putString(KEY_SUBSCRIPTIONS, JSONArray(next.map { it.toJson() }).toString())
            .apply()
        subscriptionFile(context, id).delete()
        invalidate()
    }

    fun updateSubscription(context: Context, id: String): FilterSubscription {
        val current = getSettings(context).subscriptions
        val target = current.firstOrNull { it.id == id } ?: error("Unknown subscription: $id")
        if (!target.subscriptionUrl.startsWith("https://") && !target.subscriptionUrl.startsWith("local://")) {
            error("仅支持 HTTPS 订阅")
        }
        if (target.subscriptionUrl.startsWith("local://")) {
            return target.copy(
                lastUpdatedAt = System.currentTimeMillis(),
                lastError = ""
            ).also { updated ->
                saveSubscriptions(context, current.map { if (it.id == id) updated else it })
            }
        }
        val normalizedUrl = normalizeSubscriptionUrl(target.subscriptionUrl)
        val text = downloadRules(normalizedUrl)
        val report = FilterEngine.build(listOf(FilterRuleSource(target.id, target.title, text))).report
        subscriptionFile(context, target.id).apply {
            parentFile?.mkdirs()
            writeText(text)
        }
        val updated = target.copy(
            ruleCount = report.ruleCount,
            enabledRuleCount = report.enabledRuleCount,
            unsupportedCount = report.unsupportedRuleCount,
            lastUpdatedAt = System.currentTimeMillis(),
            lastError = report.errors.firstOrNull().orEmpty(),
            subscriptionUrl = normalizedUrl
        )
        saveSubscriptions(context, current.map { if (it.id == id) updated else it })
        invalidate()
        return updated
    }

    fun setCustomRules(context: Context, rules: String) {
        prefs(context).edit()
            .putString(KEY_PRESET, FilterPreset.CUSTOM.storageValue)
            .putString(KEY_CUSTOM_RULES, rules)
            .apply()
        invalidate()
    }

    fun setSiteOverride(context: Context, override: SiteFilterOverride) {
        val overrides = getSettings(context).siteOverrides
            .filterNot { it.host == override.host }
            .plus(override)
            .filterNot { !it.networkDisabled && !it.cosmeticDisabled && !it.scriptletDisabled && it.temporaryAllowUntil <= 0L }
        prefs(context).edit()
            .putString(KEY_SITE_OVERRIDES, JSONArray(overrides.map { it.toJson() }).toString())
            .apply()
        invalidate()
    }

    fun removeSiteOverride(context: Context, host: String) {
        val normalized = host.normalizeHost()
        val overrides = getSettings(context).siteOverrides.filterNot { it.host == normalized }
        prefs(context).edit()
            .putString(KEY_SITE_OVERRIDES, JSONArray(overrides.map { it.toJson() }).toString())
            .apply()
        invalidate()
    }

    fun getEngine(context: Context, snapshot: FilterRuntimeSnapshot? = null): FilterEngine {
        val runtimeSnapshot = snapshot ?: getRuntimeSnapshot(context)
        if (!runtimeSnapshot.enabled) return FilterEngine.EMPTY
        val cacheKey = engineCacheKey(runtimeSnapshot)
        synchronized(engineCache) {
            engineCache[cacheKey]?.let { return it }
        }
        val engine = buildEngine(context, runtimeSnapshot)
        synchronized(engineCache) {
            engineCache[cacheKey] = engine
        }
        return engine
    }

    fun getEngine(snapshot: FilterRuntimeSnapshot): FilterEngine {
        if (!snapshot.enabled) return FilterEngine.EMPTY
        val cacheKey = engineCacheKey(snapshot)
        synchronized(engineCache) {
            engineCache[cacheKey]?.let { return it }
        }
        val engine = buildEngine(null, snapshot)
        synchronized(engineCache) {
            engineCache[cacheKey] = engine
        }
        return engine
    }

    fun getCachedEngine(snapshot: FilterRuntimeSnapshot): FilterEngine? {
        if (!snapshot.enabled) return FilterEngine.EMPTY
        val cacheKey = engineCacheKey(snapshot)
        return synchronized(engineCache) {
            engineCache[cacheKey]
        }
    }

    fun siteOverrideFor(snapshot: FilterRuntimeSnapshot, host: String): SiteFilterOverride? {
        if (snapshot.siteOverrides.isEmpty()) return null
        val normalized = host.normalizeHost()
        return snapshot.siteOverrides.firstOrNull { isSameOrSubdomain(normalized, it.host) }
    }

    fun recordEvent(context: Context, event: FilterEvent) {
        val appContext = context.applicationContext
        val generation = eventGeneration.get()
        eventExecutor.execute {
            runCatching {
                if (event.timestamp <= readEventsClearedAt(appContext)) return@runCatching
                synchronized(eventLock) {
                    if (generation != eventGeneration.get()) return@synchronized
                    if (event.timestamp <= readEventsClearedAt(appContext)) return@synchronized
                    val events = readRecentEvents(appContext).toMutableList()
                    events.add(0, event)
                    writeRecentEvents(appContext, events.take(MAX_EVENTS))
                }
            }
        }
    }

    fun getRecentEvents(context: Context): List<FilterEvent> {
        return synchronized(eventLock) {
            readRecentEvents(context)
        }
    }

    fun maybeRecordPerfSnapshot(
        context: Context,
        runtimeSnapshot: FilterRuntimeSnapshot,
        engine: FilterEngine,
        force: Boolean = false
    ) {
        if (!runtimeSnapshot.enabled) return
        applyPendingDiagnosticsReset(context, engine)
        val now = System.currentTimeMillis()
        if (!force) {
            val previous = lastPerfSnapshotWriteAt.get()
            if (now - previous < PERF_SNAPSHOT_WRITE_INTERVAL_MS) return
            if (!lastPerfSnapshotWriteAt.compareAndSet(previous, now)) return
        } else {
            lastPerfSnapshotWriteAt.set(now)
        }
        val appContext = context.applicationContext
        val engineKey = engineCacheKey(runtimeSnapshot)
        if (force) {
            applyPendingDiagnosticsReset(appContext, engine, force = true)
            writePerfSnapshot(
                context = appContext,
                engineKey = engineKey,
                snapshot = engine.perfSnapshot(),
                updatedAt = System.currentTimeMillis()
            )
            return
        }
        perfSnapshotExecutor.execute {
            runCatching {
                applyPendingDiagnosticsReset(appContext, engine, force = true)
                writePerfSnapshot(
                    context = appContext,
                    engineKey = engineKey,
                    snapshot = engine.perfSnapshot(),
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
    }

    fun getLatestPerfSnapshot(
        context: Context,
        runtimeSnapshot: FilterRuntimeSnapshot
    ): FilterPerfDiagnosticSnapshot? {
        if (!runtimeSnapshot.enabled) return null
        val expectedKey = engineCacheKey(runtimeSnapshot)
        val json = readPerfSnapshotJson(context) ?: return null
        if (json.optString("engineKey") != expectedKey) return null
        val updatedAt = json.optLong("updatedAt", 0L)
        if (updatedAt <= readDiagnosticsResetAt(context.applicationContext)) return null
        val snapshot = perfSnapshotFromJson(json.optJSONObject("snapshot") ?: return null) ?: return null
        return FilterPerfDiagnosticSnapshot(
            snapshot = snapshot,
            updatedAt = updatedAt,
            processName = json.optString("processName", "")
        )
    }

    fun clearEvents(context: Context) {
        eventGeneration.incrementAndGet()
        synchronized(eventLock) {
            writeEventsClearedAt(context, System.currentTimeMillis())
            prefs(context).edit().remove(KEY_EVENTS).apply()
            eventsAtomicFile(context).delete()
        }
    }

    fun resetDiagnostics(context: Context) {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        eventGeneration.incrementAndGet()
        synchronized(engineCache) {
            engineCache.values.forEach { it.resetDiagnostics() }
        }
        synchronized(eventLock) {
            writeEventsClearedAt(appContext, now)
            prefs(appContext).edit().remove(KEY_EVENTS).apply()
            eventsAtomicFile(appContext).delete()
        }
        synchronized(perfSnapshotLock) {
            perfSnapshotAtomicFile(appContext).delete()
            writeDiagnosticsResetAt(appContext, now)
        }
        lastPerfSnapshotWriteAt.set(0L)
        lastDiagnosticsResetAppliedAt.set(now)
        lastDiagnosticsResetCheckAt.set(now)
    }

    fun applyPendingDiagnosticsReset(
        context: Context,
        engine: FilterEngine,
        force: Boolean = false
    ): Boolean {
        val now = System.currentTimeMillis()
        if (!force) {
            val previousCheck = lastDiagnosticsResetCheckAt.get()
            if (now - previousCheck < DIAGNOSTICS_RESET_CHECK_INTERVAL_MS) return false
            if (!lastDiagnosticsResetCheckAt.compareAndSet(previousCheck, now)) return false
        } else {
            lastDiagnosticsResetCheckAt.set(now)
        }
        val resetAt = readDiagnosticsResetAt(context.applicationContext)
        if (resetAt <= 0L) return false
        val previous = lastDiagnosticsResetAppliedAt.get()
        if (resetAt <= previous) return false
        if (lastDiagnosticsResetAppliedAt.compareAndSet(previous, resetAt)) {
            engine.resetDiagnostics()
            lastPerfSnapshotWriteAt.set(0L)
            return true
        }
        return false
    }

    fun validateCustomRules(rules: String): FilterBuildReport {
        return FilterEngine.build(listOf(FilterRuleSource("custom", "自定义规则", rules))).report
    }

    fun invalidate() {
        synchronized(engineCache) {
            engineCache.clear()
        }
    }

    private fun buildEngine(context: Context?, snapshot: FilterRuntimeSnapshot): FilterEngine {
        val ids = snapshot.enabledSubscriptionIds.toSet()
        val sources = mutableListOf<FilterRuleSource>()
        val subscriptionsById = buildMap {
            FilterCatalog.builtInSubscriptions.forEach { put(it.id, it) }
            snapshot.subscriptions.forEach { subscription -> put(subscription.id, subscription) }
            if (snapshot.subscriptions.isEmpty()) {
                context?.let { getSettings(it).subscriptions.forEach { subscription -> put(subscription.id, subscription) } }
            }
        }
        ids.mapNotNull { subscriptionsById[it] }.forEach { subscription ->
            val cached = context?.let { readCachedRules(it, subscription.id) }
            sources += FilterRuleSource(
                subscription.id,
                subscription.title,
                cached ?: subscription.bundledRules
            )
        }
        if (snapshot.customRules.isNotBlank()) {
            sources += FilterRuleSource("custom", "自定义规则", snapshot.customRules)
        }
        return FilterEngine.build(sources)
    }

    private fun engineCacheKey(snapshot: FilterRuntimeSnapshot): String {
        return buildString {
            appendField(snapshot.enabled.toString())
            appendField(snapshot.preset)
            appendField(snapshot.customRules)
            snapshot.enabledSubscriptionIds.forEach { appendField(it) }
            append('|')
            snapshot.siteOverrides.forEach { override ->
                appendField(override.host)
                appendField(override.networkDisabled.toString())
                appendField(override.cosmeticDisabled.toString())
                appendField(override.scriptletDisabled.toString())
                appendField(override.temporaryAllowUntil.toString())
            }
            append('|')
            snapshot.subscriptions.forEach { subscription ->
                appendField(subscription.id)
                appendField(subscription.title)
                appendField(subscription.category)
                appendField(subscription.homepageUrl)
                appendField(subscription.subscriptionUrl)
                appendField(subscription.enabled.toString())
                appendField(subscription.lastUpdatedAt.toString())
                appendField(subscription.ruleCount.toString())
                appendField(subscription.enabledRuleCount.toString())
                appendField(subscription.unsupportedCount.toString())
                appendField(subscription.lastError)
            }
        }
    }

    private fun StringBuilder.appendField(value: String) {
        append(value.length)
        append(':')
        append(value)
        append(';')
    }

    private fun saveSubscriptions(context: Context, subscriptions: List<FilterSubscription>) {
        prefs(context).edit()
            .putString(KEY_SUBSCRIPTIONS, JSONArray(subscriptions.map { it.toJson() }).toString())
            .apply()
    }

    private fun readCachedRules(context: Context, id: String): String? {
        val file = subscriptionFile(context, id)
        return if (file.isFile) runCatching { file.readText() }.getOrNull() else null
    }

    private fun subscriptionFile(context: Context, id: String): File {
        val safeName = id.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(File(context.applicationContext.filesDir, RULE_DIR), "$safeName.txt")
    }

    private fun readRecentEvents(context: Context): List<FilterEvent> {
        val raw = readEventsRaw(context) ?: prefs(context).getString(KEY_EVENTS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val clearedAt = readEventsClearedAt(context)
        return buildList {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { json ->
                    val event = FilterEvent.fromJson(json)
                    if (event.timestamp > clearedAt) {
                        add(event)
                    }
                }
            }
        }
    }

    private fun readEventsRaw(context: Context): String? {
        val file = eventsFile(context)
        if (!file.isFile) return null
        return runCatching {
            String(eventsAtomicFile(context).readFully(), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun writeRecentEvents(context: Context, events: List<FilterEvent>) {
        val file = eventsFile(context)
        file.parentFile?.mkdirs()
        val atomicFile = AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            val clearedAt = readEventsClearedAt(context)
            val text = JSONArray(events.filter { it.timestamp > clearedAt }.map { it.toJson() }).toString()
            output.write(text.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
            prefs(context).edit().remove(KEY_EVENTS).apply()
        } catch (e: Exception) {
            atomicFile.failWrite(output)
            throw e
        }
    }

    private fun eventsFile(context: Context): File {
        return File(context.applicationContext.filesDir, EVENTS_FILE)
    }

    private fun eventsAtomicFile(context: Context): AtomicFile {
        return AtomicFile(eventsFile(context))
    }

    private fun readEventsClearedAt(context: Context): Long {
        val file = eventsClearedFile(context)
        if (!file.isFile) return 0L
        return runCatching {
            String(eventsClearedAtomicFile(context).readFully(), Charsets.UTF_8).trim().toLong()
        }.getOrDefault(0L)
    }

    private fun writeEventsClearedAt(context: Context, timestamp: Long) {
        val file = eventsClearedFile(context)
        file.parentFile?.mkdirs()
        val atomicFile = AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            output.write(timestamp.toString().toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (e: Exception) {
            atomicFile.failWrite(output)
            throw e
        }
    }

    private fun eventsClearedFile(context: Context): File {
        return File(context.applicationContext.filesDir, EVENTS_CLEARED_FILE)
    }

    private fun eventsClearedAtomicFile(context: Context): AtomicFile {
        return AtomicFile(eventsClearedFile(context))
    }

    private fun writePerfSnapshot(
        context: Context,
        engineKey: String,
        snapshot: FilterPerfSnapshot,
        updatedAt: Long
    ) {
        runCatching {
            synchronized(perfSnapshotLock) {
                val file = perfSnapshotFile(context)
                file.parentFile?.mkdirs()
                val atomicFile = AtomicFile(file)
                val output = atomicFile.startWrite()
                try {
                    val json = JSONObject()
                        .put("engineKey", engineKey)
                        .put("updatedAt", updatedAt)
                        .put("processName", currentProcessName())
                        .put("snapshot", perfSnapshotToJson(snapshot))
                    output.write(json.toString().toByteArray(Charsets.UTF_8))
                    atomicFile.finishWrite(output)
                } catch (e: Exception) {
                    atomicFile.failWrite(output)
                    throw e
                }
            }
        }
    }

    private fun readPerfSnapshotJson(context: Context): JSONObject? {
        val file = perfSnapshotFile(context)
        if (!file.isFile) return null
        return runCatching {
            JSONObject(String(perfSnapshotAtomicFile(context).readFully(), Charsets.UTF_8))
        }.getOrNull()
    }

    private fun perfSnapshotFile(context: Context): File {
        return File(context.applicationContext.filesDir, PERF_SNAPSHOT_FILE)
    }

    private fun perfSnapshotAtomicFile(context: Context): AtomicFile {
        return AtomicFile(perfSnapshotFile(context))
    }

    private fun readDiagnosticsResetAt(context: Context): Long {
        val file = diagnosticsResetFile(context)
        if (!file.isFile) return 0L
        return runCatching {
            String(diagnosticsResetAtomicFile(context).readFully(), Charsets.UTF_8).trim().toLong()
        }.getOrDefault(0L)
    }

    private fun writeDiagnosticsResetAt(context: Context, timestamp: Long) {
        val file = diagnosticsResetFile(context)
        file.parentFile?.mkdirs()
        val atomicFile = AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            output.write(timestamp.toString().toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (e: Exception) {
            atomicFile.failWrite(output)
            throw e
        }
    }

    private fun diagnosticsResetFile(context: Context): File {
        return File(context.applicationContext.filesDir, DIAGNOSTICS_RESET_FILE)
    }

    private fun diagnosticsResetAtomicFile(context: Context): AtomicFile {
        return AtomicFile(diagnosticsResetFile(context))
    }

    private fun perfSnapshotToJson(snapshot: FilterPerfSnapshot): JSONObject {
        return JSONObject()
            .put("buildDurationMs", snapshot.buildDurationMs)
            .put("decisionCount", snapshot.decisionCount)
            .put("cacheHitCount", snapshot.cacheHitCount)
            .put("cacheMissCount", snapshot.cacheMissCount)
            .put("normalizedCacheHitCount", snapshot.normalizedCacheHitCount)
            .put("normalizedCacheStoreCount", snapshot.normalizedCacheStoreCount)
            .put("normalizedCacheBypassCount", snapshot.normalizedCacheBypassCount)
            .put("candidateEvaluationCount", snapshot.candidateEvaluationCount)
            .put("regexEvaluationCount", snapshot.regexEvaluationCount)
            .put("cosmeticCallCount", snapshot.cosmeticCallCount)
            .put("scriptletCallCount", snapshot.scriptletCallCount)
            .put("generatedCssBytes", snapshot.generatedCssBytes)
            .put("generatedScriptletBytes", snapshot.generatedScriptletBytes)
            .put("shouldBlockDurationMicros", perfSampleStatsToJson(snapshot.shouldBlockDurationMicros))
            .put("shouldBlockParseDurationMicros", perfSampleStatsToJson(snapshot.shouldBlockParseDurationMicros))
            .put("shouldBlockEngineDurationMicros", perfSampleStatsToJson(snapshot.shouldBlockEngineDurationMicros))
            .put("shouldBlockEventDurationMicros", perfSampleStatsToJson(snapshot.shouldBlockEventDurationMicros))
            .put("shouldBlockSnapshotDurationMicros", perfSampleStatsToJson(snapshot.shouldBlockSnapshotDurationMicros))
            .put("decisionDurationMicros", perfSampleStatsToJson(snapshot.decisionDurationMicros))
            .put("candidateEvaluationsPerDecision", perfSampleStatsToJson(snapshot.candidateEvaluationsPerDecision))
            .put("cosmeticDurationMicros", perfSampleStatsToJson(snapshot.cosmeticDurationMicros))
            .put("scriptletDurationMicros", perfSampleStatsToJson(snapshot.scriptletDurationMicros))
            .put("importantIndex", indexStatsToJson(snapshot.importantIndex))
            .put("exceptionIndex", indexStatsToJson(snapshot.exceptionIndex))
            .put("blockingIndex", indexStatsToJson(snapshot.blockingIndex))
            .put("removeParamIndex", indexStatsToJson(snapshot.removeParamIndex))
            .put("slowShouldBlockSamples", JSONArray(snapshot.slowShouldBlockSamples.map { slowShouldBlockSampleToJson(it) }))
    }

    private fun perfSnapshotFromJson(json: JSONObject): FilterPerfSnapshot? {
        return runCatching {
            FilterPerfSnapshot(
                buildDurationMs = json.optLong("buildDurationMs", 0L),
                decisionCount = json.optLong("decisionCount", 0L),
                cacheHitCount = json.optLong("cacheHitCount", 0L),
                cacheMissCount = json.optLong("cacheMissCount", 0L),
                normalizedCacheHitCount = json.optLong("normalizedCacheHitCount", 0L),
                normalizedCacheStoreCount = json.optLong("normalizedCacheStoreCount", 0L),
                normalizedCacheBypassCount = json.optLong("normalizedCacheBypassCount", 0L),
                candidateEvaluationCount = json.optLong("candidateEvaluationCount", 0L),
                regexEvaluationCount = json.optLong("regexEvaluationCount", 0L),
                cosmeticCallCount = json.optLong("cosmeticCallCount", 0L),
                scriptletCallCount = json.optLong("scriptletCallCount", 0L),
                generatedCssBytes = json.optLong("generatedCssBytes", 0L),
                generatedScriptletBytes = json.optLong("generatedScriptletBytes", 0L),
                shouldBlockDurationMicros = perfSampleStatsFromJson(json.optJSONObject("shouldBlockDurationMicros")),
                shouldBlockParseDurationMicros = perfSampleStatsFromJson(json.optJSONObject("shouldBlockParseDurationMicros")),
                shouldBlockEngineDurationMicros = perfSampleStatsFromJson(json.optJSONObject("shouldBlockEngineDurationMicros")),
                shouldBlockEventDurationMicros = perfSampleStatsFromJson(json.optJSONObject("shouldBlockEventDurationMicros")),
                shouldBlockSnapshotDurationMicros = perfSampleStatsFromJson(json.optJSONObject("shouldBlockSnapshotDurationMicros")),
                decisionDurationMicros = perfSampleStatsFromJson(json.optJSONObject("decisionDurationMicros")),
                candidateEvaluationsPerDecision = perfSampleStatsFromJson(json.optJSONObject("candidateEvaluationsPerDecision")),
                cosmeticDurationMicros = perfSampleStatsFromJson(json.optJSONObject("cosmeticDurationMicros")),
                scriptletDurationMicros = perfSampleStatsFromJson(json.optJSONObject("scriptletDurationMicros")),
                importantIndex = indexStatsFromJson(json.optJSONObject("importantIndex")),
                exceptionIndex = indexStatsFromJson(json.optJSONObject("exceptionIndex")),
                blockingIndex = indexStatsFromJson(json.optJSONObject("blockingIndex")),
                removeParamIndex = indexStatsFromJson(json.optJSONObject("removeParamIndex")),
                slowShouldBlockSamples = slowShouldBlockSamplesFromJson(json.optJSONArray("slowShouldBlockSamples"))
            )
        }.getOrNull()
    }

    private fun slowShouldBlockSampleToJson(sample: FilterSlowShouldBlockSample): JSONObject {
        return JSONObject()
            .put("timestamp", sample.timestamp)
            .put("durationMicros", sample.durationMicros)
            .put("parseMicros", sample.parseMicros)
            .put("engineMicros", sample.engineMicros)
            .put("eventMicros", sample.eventMicros)
            .put("snapshotMicros", sample.snapshotMicros)
            .put("resourceType", sample.resourceType)
            .put("action", sample.action)
            .put("url", sample.url)
            .put("ruleText", sample.ruleText)
            .put("cacheStatus", sample.cacheStatus)
            .put("candidateCount", sample.candidateCount)
    }

    private fun slowShouldBlockSamplesFromJson(array: JSONArray?): List<FilterSlowShouldBlockSample> {
        array ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val json = array.optJSONObject(i) ?: continue
                add(
                    FilterSlowShouldBlockSample(
                        timestamp = json.optLong("timestamp", 0L),
                        durationMicros = json.optLong("durationMicros", 0L),
                        parseMicros = json.optLong("parseMicros", 0L),
                        engineMicros = json.optLong("engineMicros", 0L),
                        eventMicros = json.optLong("eventMicros", 0L),
                        snapshotMicros = json.optLong("snapshotMicros", 0L),
                        resourceType = json.optString("resourceType"),
                        action = json.optString("action"),
                        url = json.optString("url"),
                        ruleText = json.optString("ruleText"),
                        cacheStatus = json.optString("cacheStatus"),
                        candidateCount = json.optInt("candidateCount", 0)
                    )
                )
            }
        }
    }

    private fun perfSampleStatsToJson(stats: FilterPerfSampleStats): JSONObject {
        return JSONObject()
            .put("sampleCount", stats.sampleCount)
            .put("p50", stats.p50)
            .put("p95", stats.p95)
            .put("p99", stats.p99)
            .put("max", stats.max)
    }

    private fun perfSampleStatsFromJson(json: JSONObject?): FilterPerfSampleStats {
        json ?: return FilterPerfSampleStats(sampleCount = 0, p50 = 0L, p95 = 0L, p99 = 0L, max = 0L)
        return FilterPerfSampleStats(
            sampleCount = json.optInt("sampleCount", 0),
            p50 = json.optLong("p50", 0L),
            p95 = json.optLong("p95", 0L),
            p99 = json.optLong("p99", 0L),
            max = json.optLong("max", 0L)
        )
    }

    private fun indexStatsToJson(stats: FilterIndexStats): JSONObject {
        return JSONObject()
            .put("tokenBucketCount", stats.tokenBucketCount)
            .put("indexedRuleCount", stats.indexedRuleCount)
            .put("universalRuleCount", stats.universalRuleCount)
    }

    private fun indexStatsFromJson(json: JSONObject?): FilterIndexStats {
        json ?: return FilterIndexStats(tokenBucketCount = 0, indexedRuleCount = 0, universalRuleCount = 0)
        return FilterIndexStats(
            tokenBucketCount = json.optInt("tokenBucketCount", 0),
            indexedRuleCount = json.optInt("indexedRuleCount", 0),
            universalRuleCount = json.optInt("universalRuleCount", 0)
        )
    }

    private fun currentProcessName(): String {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            android.app.Application.getProcessName()
        } else {
            ""
        }
    }

    internal fun normalizeSubscriptionUrl(url: String): String {
        val trimmed = url.trim()
        val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return trimmed
        if (!uri.scheme.equals("https", ignoreCase = true)) return trimmed
        val host = uri.host.orEmpty().lowercase(java.util.Locale.US)
        if (host != "github.com") return trimmed
        val pathParts = uri.path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
        if (pathParts.size < 5 || pathParts[2] != "blob") return trimmed
        val owner = pathParts[0]
        val repo = pathParts[1]
        val branch = pathParts[3]
        val filePath = pathParts.drop(4).joinToString("/")
        return "https://raw.githubusercontent.com/$owner/$repo/$branch/$filePath"
    }

    private fun downloadRules(url: String): String {
        val normalizedUrl = normalizeSubscriptionUrl(url)
        val connection = (URL(normalizedUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "ChildKioskBrowser/AdblockSubscription")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) error("订阅下载失败: HTTP $code")
            val contentLength = connection.contentLengthLong
            if (contentLength > 15L * 1024L * 1024L) error("订阅超过 15MB 限制")
            connection.inputStream.bufferedReader().use { reader ->
                val text = reader.readText()
                if (text.length > 15 * 1024 * 1024) error("订阅超过 15MB 限制")
                text
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readSubscriptionOverrides(prefs: SharedPreferences): Map<String, JSONObject> {
        val raw = prefs.getString(KEY_SUBSCRIPTIONS, null) ?: return emptyMap()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyMap()
        return buildMap {
            for (i in 0 until array.length()) {
                val json = array.optJSONObject(i) ?: continue
                val id = json.optString("id")
                if (id.isNotBlank()) put(id, json)
            }
        }
    }

    private fun readSiteOverrides(prefs: SharedPreferences): List<SiteFilterOverride> {
        val raw = prefs.getString(KEY_SITE_OVERRIDES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull()
        return array.toSiteOverrideList()
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

}
