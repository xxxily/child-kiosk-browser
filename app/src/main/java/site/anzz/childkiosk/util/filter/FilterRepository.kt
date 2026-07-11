package site.anzz.childkiosk.util.filter

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import site.anzz.childkiosk.util.KioskPrefs
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicLong

data class FilterPerfDiagnosticSnapshot(
    val snapshot: FilterPerfSnapshot,
    val updatedAt: Long,
    val processName: String
)

object FilterRepository {
    internal const val MAX_CUSTOM_RULE_BYTES = 128 * 1024
    private const val PREFS_NAME = "kiosk_filter_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PRESET = "preset"
    private const val KEY_SUBSCRIPTIONS = "subscriptions"
    private const val KEY_CUSTOM_RULES = "custom_rules"
    private const val KEY_SITE_OVERRIDES = "site_overrides"
    private const val KEY_EVENTS = "events"
    private const val EVENTS_FILE = "filter_events.json"
    private const val EVENTS_CLEARED_FILE = "filter_events_cleared_at.txt"
    private const val PERF_SNAPSHOT_FILE = "filter_perf_snapshot.json"
    private const val DIAGNOSTICS_RESET_FILE = "filter_diagnostics_reset_at.txt"
    private const val MAX_EVENTS = 200
    private const val MAX_ENABLED_RAW_RULE_BYTES = 60L * 1024L * 1024L
    private const val LARGE_LIST_RULE_THRESHOLD = 1_000
    private const val MIN_LARGE_LIST_RETAIN_PERCENT = 20
    private const val PERF_SNAPSHOT_WRITE_INTERVAL_MS = 2_000L
    private const val DIAGNOSTICS_RESET_CHECK_INTERVAL_MS = 500L

    private val engineCache = object : LinkedHashMap<String, FilterEngine>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FilterEngine>?): Boolean {
            return size > 4
        }
    }
    private val engineBuilds = ConcurrentHashMap<String, FutureTask<FilterEngine>>()
    private val mutationLock = Any()
    private val subscriptionIdentityEpochs = mutableMapOf<String, Long>()
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

    fun getSettings(context: Context): FilterSettings = synchronized(mutationLock) {
        readSettingsLocked(context)
    }

    private fun readSettingsLocked(context: Context): FilterSettings {
        val prefs = prefs(context)
        val preset = FilterPreset.fromStorage(prefs.getString(KEY_PRESET, FilterPreset.STANDARD_CHILD.storageValue))
        val legacyEnabled = prefs.getBoolean(KEY_ENABLED, false)
        val enabled = KioskPrefs.getOrMigrateLimitAdBlockEnabled(context, legacyEnabled)
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
                    lastError = override.optString("lastError", subscription.lastError),
                    contentGeneration = override.optString("contentGeneration", subscription.contentGeneration)
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
        synchronized(mutationLock) {
            if (enabled) ensureEnabledRawBudget(context, readSettingsLocked(context))
            KioskPrefs.setLimitAdBlockEnabled(context, enabled)
            commitOrThrow(prefs(context).edit().putBoolean(KEY_ENABLED, enabled))
        }
        invalidate()
    }

    fun setPreset(context: Context, preset: FilterPreset) {
        synchronized(mutationLock) {
            val current = readSettingsLocked(context)
            val currentById = current.subscriptions.associateBy { it.id }
            val builtInIds = FilterCatalog.builtInSubscriptions.mapTo(mutableSetOf()) { it.id }
            val next = if (preset == FilterPreset.CUSTOM) {
                current.subscriptions
            } else {
                val defaultsById = FilterCatalog.defaultSubscriptionsFor(preset).associateBy { it.id }
                val builtIns = FilterCatalog.builtInSubscriptions.map { catalogSubscription ->
                    val defaultSubscription = defaultsById.getValue(catalogSubscription.id)
                    currentById[catalogSubscription.id]
                        ?.let { existing -> defaultSubscription.withStoredMetadata(existing) }
                        ?: defaultSubscription
                }
                builtIns + current.subscriptions.filterNot { it.id in builtInIds }
            }
            ensureEnabledRawBudget(context, current.copy(preset = preset, subscriptions = next))
            commitOrThrow(
                prefs(context).edit()
                    .putString(KEY_PRESET, preset.storageValue)
                    .putString(KEY_SUBSCRIPTIONS, JSONArray(next.map { it.toJson() }).toString())
            )
        }
        invalidate()
    }

    fun setSubscriptionEnabled(context: Context, id: String, enabled: Boolean) {
        synchronized(mutationLock) {
            val settings = readSettingsLocked(context)
            require(settings.subscriptions.any { it.id == id }) { "Unknown subscription: $id" }
            val next = settings.subscriptions.map { if (it.id == id) it.copy(enabled = enabled) else it }
            ensureEnabledRawBudget(context, settings.copy(subscriptions = next))
            commitOrThrow(
                prefs(context).edit()
                    .putString(KEY_PRESET, FilterPreset.CUSTOM.storageValue)
                    .putString(KEY_SUBSCRIPTIONS, JSONArray(next.map { it.toJson() }).toString())
            )
        }
        invalidate()
    }

    fun addCustomSubscription(context: Context, title: String, url: String): FilterSubscription {
        val cleanUrl = normalizeSubscriptionUrl(url)
        val cleanUri = runCatching { java.net.URI(cleanUrl) }.getOrNull()
        require(
            cleanUri?.scheme.equals("https", ignoreCase = true) &&
                !cleanUri?.host.isNullOrBlank() &&
                cleanUri?.userInfo == null
        ) { "仅支持有效的 HTTPS 订阅 URL" }
        val proposed = FilterCatalog.customSubscription(title.trim(), cleanUrl)
        val subscription = synchronized(mutationLock) {
            val settings = readSettingsLocked(context)
            val existing = settings.subscriptions.firstOrNull { it.id == proposed.id }
            val resolved = existing?.copy(
                title = proposed.title,
                category = proposed.category,
                homepageUrl = proposed.homepageUrl,
                subscriptionUrl = proposed.subscriptionUrl,
                enabled = true
            ) ?: proposed
            val next = settings.subscriptions.filterNot { it.id == resolved.id }.plus(resolved)
            ensureEnabledRawBudget(context, settings.copy(subscriptions = next))
            commitOrThrow(
                prefs(context).edit()
                    .putString(KEY_PRESET, FilterPreset.CUSTOM.storageValue)
                    .putString(KEY_SUBSCRIPTIONS, JSONArray(next.map { it.toJson() }).toString())
            )
            if (existing == null) bumpSubscriptionIdentityEpochLocked(resolved.id)
            resolved
        }
        invalidate()
        return subscription
    }

    fun removeCustomSubscription(context: Context, id: String) {
        if (FilterCatalog.builtInSubscriptions.any { it.id == id }) return
        synchronized(mutationLock) {
            val current = readSettingsLocked(context).subscriptions
            if (current.none { it.id == id }) return
            val next = current.filterNot { it.id == id }
            commitOrThrow(
                prefs(context).edit()
                    .putString(KEY_SUBSCRIPTIONS, JSONArray(next.map { it.toJson() }).toString())
            )
            bumpSubscriptionIdentityEpochLocked(id)
            FilterSubscriptionStore(context).deleteSubscription(id)
        }
        invalidate()
    }

    fun updateSubscription(context: Context, id: String): FilterSubscription {
        val token = synchronized(mutationLock) {
            val target = readSettingsLocked(context).subscriptions.firstOrNull { it.id == id }
                ?: error("Unknown subscription: $id")
            SubscriptionUpdateToken(
                subscription = target,
                normalizedUrl = normalizeSubscriptionUrl(target.subscriptionUrl),
                identityEpoch = subscriptionIdentityEpochs[id] ?: 0L
            )
        }
        val target = token.subscription
        val targetScheme = runCatching { java.net.URI(target.subscriptionUrl).scheme }.getOrNull()
        if (
            !targetScheme.equals("https", ignoreCase = true) &&
            !targetScheme.equals("local", ignoreCase = true)
        ) {
            error("仅支持 HTTPS 订阅")
        }
        if (targetScheme.equals("local", ignoreCase = true)) {
            return synchronized(mutationLock) {
                val settings = readSettingsLocked(context)
                val current = settings.subscriptions.firstOrNull { it.id == id }
                    ?: error("订阅已被删除")
                ensureUpdateTokenMatchesLocked(current, token)
                val updated = current.copy(lastUpdatedAt = System.currentTimeMillis(), lastError = "")
                saveSubscriptionsLocked(context, settings.subscriptions.map { if (it.id == id) updated else it })
                updated
            }
        }

        val store = FilterSubscriptionStore(context)
        val stagingFile = store.createStagingFile(target.id)
        var staged: StagedFilterSubscription? = null
        var publishedGeneration: String? = null
        var metadataCommitted = false
        try {
            val downloaded = FilterSubscriptionDownloader.download(token.normalizedUrl, stagingFile)
            val candidate = store.inspectStaging(target.id, stagingFile)
            check(candidate.byteCount == downloaded.byteCount) { "订阅候选文件长度不一致" }
            staged = candidate
            val report = FilterEngine.build(
                listOf(FilterRuleSource(target.id, target.title, downloaded.text))
            ).report
            validateCandidateReport(target, report)
            val published = store.publish(candidate)
            check(published.isFile) { "订阅 generation 发布失败" }
            publishedGeneration = candidate.generation

            val updated = synchronized(mutationLock) {
                val settings = readSettingsLocked(context)
                val current = settings.subscriptions.firstOrNull { it.id == id }
                    ?: error("订阅已被删除，忽略过期更新")
                ensureUpdateTokenMatchesLocked(current, token)
                check(published.isFile) { "订阅 generation 在提交前丢失" }
                val nextTarget = current.copy(
                    ruleCount = report.ruleCount,
                    enabledRuleCount = report.enabledRuleCount,
                    unsupportedCount = report.unsupportedRuleCount,
                    lastUpdatedAt = System.currentTimeMillis(),
                    lastError = report.errors.firstOrNull().orEmpty(),
                    subscriptionUrl = token.normalizedUrl,
                    contentGeneration = candidate.generation
                )
                val nextSubscriptions = settings.subscriptions.map {
                    if (it.id == id) nextTarget else it
                }
                ensureEnabledRawBudget(
                    context,
                    settings.copy(subscriptions = nextSubscriptions)
                )
                saveSubscriptionsLocked(context, nextSubscriptions)
                metadataCommitted = true
                store.cleanupAfterCommit(
                    subscriptionId = id,
                    currentGeneration = candidate.generation,
                    previousGeneration = token.subscription.contentGeneration
                )
                nextTarget
            }
            invalidate()
            return updated
        } catch (error: Throwable) {
            staged?.let(store::discard)
            stagingFile.delete()
            publishedGeneration
                ?.takeIf { !metadataCommitted && it != token.subscription.contentGeneration }
                ?.let { generation ->
                    synchronized(mutationLock) {
                        val referenced = readSettingsLocked(context).subscriptions.any { subscription ->
                            subscription.id == target.id && subscription.contentGeneration == generation
                        }
                        if (!referenced) store.deleteGeneration(target.id, generation)
                    }
                }
            throw error
        }
    }

    fun setCustomRules(context: Context, rules: String) {
        require(rules.toByteArray(Charsets.UTF_8).size <= MAX_CUSTOM_RULE_BYTES) {
            "自定义规则超过 128KiB 限制"
        }
        synchronized(mutationLock) {
            val settings = readSettingsLocked(context).copy(
                preset = FilterPreset.CUSTOM,
                customRules = rules
            )
            ensureEnabledRawBudget(context, settings)
            commitOrThrow(
                prefs(context).edit()
                    .putString(KEY_PRESET, FilterPreset.CUSTOM.storageValue)
                    .putString(KEY_CUSTOM_RULES, rules)
            )
        }
        invalidate()
    }

    fun setSiteOverride(context: Context, override: SiteFilterOverride) {
        synchronized(mutationLock) {
            val normalizedOverride = override.copy(host = override.host.normalizeHost())
            val overrides = readSettingsLocked(context).siteOverrides
                .filterNot { it.host == normalizedOverride.host }
                .plus(normalizedOverride)
                .filterNot {
                    !it.networkDisabled && !it.cosmeticDisabled && !it.scriptletDisabled &&
                        it.temporaryAllowUntil <= 0L
                }
            commitOrThrow(
                prefs(context).edit()
                    .putString(KEY_SITE_OVERRIDES, JSONArray(overrides.map { it.toJson() }).toString())
            )
        }
    }

    fun removeSiteOverride(context: Context, host: String) {
        val normalized = host.normalizeHost()
        synchronized(mutationLock) {
            val overrides = readSettingsLocked(context).siteOverrides.filterNot { it.host == normalized }
            commitOrThrow(
                prefs(context).edit()
                    .putString(KEY_SITE_OVERRIDES, JSONArray(overrides.map { it.toJson() }).toString())
            )
        }
    }

    fun getEngine(context: Context, snapshot: FilterRuntimeSnapshot? = null): FilterEngine {
        val runtimeSnapshot = snapshot ?: getRuntimeSnapshot(context)
        val resolvedSnapshot = if (
            runtimeSnapshot.enabledSubscriptionIds.isNotEmpty() &&
            runtimeSnapshot.subscriptions.isEmpty()
        ) {
            val enabledIds = runtimeSnapshot.enabledSubscriptionIds.toSet()
            runtimeSnapshot.copy(
                subscriptions = getSettings(context).subscriptions.filter { it.id in enabledIds }
            )
        } else {
            runtimeSnapshot
        }
        return getOrBuildEngine(context, resolvedSnapshot)
    }

    fun getEngine(snapshot: FilterRuntimeSnapshot): FilterEngine {
        if (!snapshot.enabled) return FilterEngine.EMPTY
        val cacheKey = engineCacheKey(snapshot)
        synchronized(engineCache) {
            engineCache[cacheKey]?.let { return it }
        }
        val enabledIds = snapshot.enabledSubscriptionIds.toSet()
        check(snapshot.subscriptions.none { it.id in enabledIds && it.contentGeneration.isNotBlank() }) {
            "generation 规则需要 Context 读取；请先预热并持有正式引擎"
        }
        return getOrBuildEngine(null, snapshot)
    }

    internal fun bundledFallbackSnapshot(requested: FilterRuntimeSnapshot): FilterRuntimeSnapshot {
        val subscriptions = FilterCatalog.defaultSubscriptionsFor(FilterPreset.STANDARD_CHILD)
            .filter { it.enabled }
            .map { subscription ->
                subscription.copy(
                    lastUpdatedAt = 0L,
                    ruleCount = 0,
                    enabledRuleCount = 0,
                    unsupportedCount = 0,
                    lastError = "",
                    contentGeneration = ""
                )
            }
        return FilterRuntimeSnapshot(
            enabled = requested.enabled,
            preset = BUNDLED_FALLBACK_PRESET,
            customRules = "",
            enabledSubscriptionIds = subscriptions.map { it.id },
            siteOverrides = requested.siteOverrides,
            subscriptions = subscriptions
        )
    }

    internal fun getBundledFallbackEngine(requested: FilterRuntimeSnapshot): FilterEngine {
        return getOrBuildEngine(
            context = null,
            snapshot = bundledFallbackSnapshot(requested),
            cacheNamespace = BUNDLED_FALLBACK_CACHE_NAMESPACE
        )
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
        return snapshot.siteOverrides
            .asSequence()
            .filter { isSameOrSubdomain(normalized, it.host) }
            .maxWithOrNull(
                compareBy<SiteFilterOverride> { it.host.count { char -> char == '.' } }
                    .thenBy { it.host.length }
            )
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

    private fun getOrBuildEngine(
        context: Context?,
        snapshot: FilterRuntimeSnapshot,
        cacheNamespace: String = PRIMARY_ENGINE_CACHE_NAMESPACE
    ): FilterEngine {
        if (!snapshot.enabled) return FilterEngine.EMPTY
        val cacheKey = engineCacheKey(snapshot, cacheNamespace)
        synchronized(engineCache) {
            engineCache[cacheKey]?.let { return it }
        }

        val newTask = FutureTask { buildEngine(context, snapshot) }
        val existingTask = engineBuilds.putIfAbsent(cacheKey, newTask)
        val ownsTask = existingTask == null
        val task = existingTask ?: newTask.also { it.run() }
        return try {
            val engine = task.get()
            synchronized(engineCache) {
                engineCache[cacheKey] ?: engine.also { engineCache[cacheKey] = it }
            }
        } catch (error: java.util.concurrent.ExecutionException) {
            throw (error.cause ?: error)
        } finally {
            if (ownsTask) engineBuilds.remove(cacheKey, task)
        }
    }

    private fun buildEngine(context: Context?, snapshot: FilterRuntimeSnapshot): FilterEngine {
        val ids = snapshot.enabledSubscriptionIds.toSet()
        val sources = mutableListOf<FilterRuleSource>()
        val store = context?.let(::FilterSubscriptionStore)
        val subscriptionsById = buildMap {
            FilterCatalog.builtInSubscriptions.forEach { put(it.id, it) }
            snapshot.subscriptions.forEach { subscription -> put(subscription.id, subscription) }
            if (snapshot.subscriptions.isEmpty()) {
                context?.let { getSettings(it).subscriptions.forEach { subscription -> put(subscription.id, subscription) } }
            }
        }
        ids.mapNotNull { subscriptionsById[it] }.forEach { subscription ->
            val cached = store?.readRules(subscription)
            check(subscription.contentGeneration.isBlank() || cached != null) {
                "订阅 generation 缺失或校验失败: ${subscription.id}"
            }
            sources += FilterRuleSource(
                subscription.id,
                subscription.title,
                cached ?: subscription.bundledRules
            )
        }
        if (snapshot.customRules.isNotBlank()) {
            sources += FilterRuleSource("custom", "自定义规则", snapshot.customRules)
        }
        val rawBytes = sources.sumOf { it.rulesText.toByteArray(Charsets.UTF_8).size.toLong() }
        require(rawBytes <= MAX_ENABLED_RAW_RULE_BYTES) {
            "启用规则原文超过 60MB 限制"
        }
        return FilterEngine.build(sources)
    }

    private fun engineCacheKey(
        snapshot: FilterRuntimeSnapshot,
        cacheNamespace: String = PRIMARY_ENGINE_CACHE_NAMESPACE
    ): String {
        val subscriptionsById = buildMap {
            FilterCatalog.builtInSubscriptions.forEach { put(it.id, it) }
            snapshot.subscriptions.forEach { put(it.id, it) }
        }
        return buildString {
            appendField(cacheNamespace)
            snapshot.enabledSubscriptionIds.distinct().forEach { id ->
                val subscription = subscriptionsById[id]
                appendField(id)
                appendField(
                    subscription?.contentGeneration?.takeIf { it.isNotBlank() }
                        ?: subscription?.bundledRules
                            ?.toByteArray(Charsets.UTF_8)
                            ?.let(FilterSubscriptionStore::sha256)
                        ?: "missing"
                )
            }
            appendField(FilterSubscriptionStore.sha256(snapshot.customRules.toByteArray(Charsets.UTF_8)))
        }
    }

    private fun StringBuilder.appendField(value: String) {
        append(value.length)
        append(':')
        append(value)
        append(';')
    }

    private fun saveSubscriptionsLocked(context: Context, subscriptions: List<FilterSubscription>) {
        commitOrThrow(
            prefs(context).edit()
                .putString(KEY_SUBSCRIPTIONS, JSONArray(subscriptions.map { it.toJson() }).toString())
        )
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
            .put("matchBudgetExhaustionCount", snapshot.matchBudgetExhaustionCount)
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
                matchBudgetExhaustionCount = json.optLong("matchBudgetExhaustionCount", 0L),
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

    private fun FilterSubscription.withStoredMetadata(existing: FilterSubscription): FilterSubscription {
        return copy(
            lastUpdatedAt = existing.lastUpdatedAt,
            ruleCount = existing.ruleCount,
            enabledRuleCount = existing.enabledRuleCount,
            unsupportedCount = existing.unsupportedCount,
            lastError = existing.lastError,
            contentGeneration = existing.contentGeneration
        )
    }

    private fun validateCandidateReport(
        previous: FilterSubscription,
        report: FilterBuildReport
    ) {
        val usableRuleCount = maxOf(
            report.enabledRuleCount,
            report.networkRuleCount + report.cosmeticRuleCount + report.scriptletRuleCount
        )
        require(usableRuleCount > 0) { "订阅没有可用的已编译规则" }
        if (
            previous.ruleCount >= LARGE_LIST_RULE_THRESHOLD &&
            report.ruleCount.toLong() * 100L <
            previous.ruleCount.toLong() * MIN_LARGE_LIST_RETAIN_PERCENT
        ) {
            error("订阅规则数异常下降，已保留上一版本")
        }
    }

    private fun ensureEnabledRawBudget(context: Context, settings: FilterSettings) {
        val store = FilterSubscriptionStore(context)
        var total = settings.customRules.toByteArray(Charsets.UTF_8).size.toLong()
        require(total <= MAX_ENABLED_RAW_RULE_BYTES) { "启用规则原文超过 60MB 限制" }
        settings.subscriptions.asSequence().filter { it.enabled }.forEach { subscription ->
            val bytes = store.contentSize(subscription)
            require(bytes <= MAX_ENABLED_RAW_RULE_BYTES - total) {
                "启用规则原文超过 60MB 限制"
            }
            total += bytes
        }
    }

    private fun ensureUpdateTokenMatchesLocked(
        current: FilterSubscription,
        token: SubscriptionUpdateToken
    ) {
        val currentUrl = normalizeSubscriptionUrl(current.subscriptionUrl)
        val currentEpoch = subscriptionIdentityEpochs[current.id] ?: 0L
        check(
            current.id == token.subscription.id &&
                currentUrl == token.normalizedUrl &&
                current.contentGeneration == token.subscription.contentGeneration &&
                currentEpoch == token.identityEpoch
        ) {
            "订阅已发生变化，忽略过期更新"
        }
    }

    private fun bumpSubscriptionIdentityEpochLocked(id: String) {
        subscriptionIdentityEpochs[id] = (subscriptionIdentityEpochs[id] ?: 0L) + 1L
    }

    private fun commitOrThrow(editor: SharedPreferences.Editor) {
        check(editor.commit()) { "过滤设置保存失败" }
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

    private data class SubscriptionUpdateToken(
        val subscription: FilterSubscription,
        val normalizedUrl: String,
        val identityEpoch: Long
    )

    private const val PRIMARY_ENGINE_CACHE_NAMESPACE = "filter-engine-v2"
    private const val BUNDLED_FALLBACK_CACHE_NAMESPACE = "bundled-fallback-v1"
    private const val BUNDLED_FALLBACK_PRESET = "BUNDLED_FALLBACK_V1"

}
