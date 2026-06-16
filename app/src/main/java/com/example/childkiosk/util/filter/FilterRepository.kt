package com.example.childkiosk.util.filter

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

object FilterRepository {
    private const val PREFS_NAME = "kiosk_filter_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PRESET = "preset"
    private const val KEY_SUBSCRIPTIONS = "subscriptions"
    private const val KEY_CUSTOM_RULES = "custom_rules"
    private const val KEY_SITE_OVERRIDES = "site_overrides"
    private const val KEY_EVENTS = "events"
    private const val RULE_DIR = "filter_subscriptions"
    private const val MAX_EVENTS = 200

    private val engineCache = AtomicReference<CachedEngine?>(null)

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
        val cleanUrl = url.trim()
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
        val text = downloadRules(target.subscriptionUrl)
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
            lastError = report.errors.firstOrNull().orEmpty()
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
        val cacheKey = runtimeSnapshot.toJson().toString()
        engineCache.get()?.let { cached ->
            if (cached.key == cacheKey) return cached.engine
        }
        val engine = buildEngine(context, runtimeSnapshot)
        engineCache.set(CachedEngine(cacheKey, engine))
        return engine
    }

    fun getEngine(snapshot: FilterRuntimeSnapshot): FilterEngine {
        if (!snapshot.enabled) return FilterEngine.EMPTY
        val cacheKey = snapshot.toJson().toString()
        engineCache.get()?.let { cached ->
            if (cached.key == cacheKey) return cached.engine
        }
        val engine = buildEngine(null, snapshot)
        engineCache.set(CachedEngine(cacheKey, engine))
        return engine
    }

    fun siteOverrideFor(snapshot: FilterRuntimeSnapshot, host: String): SiteFilterOverride? {
        val normalized = host.normalizeHost()
        return snapshot.siteOverrides.firstOrNull { isSameOrSubdomain(normalized, it.host) }
    }

    fun recordEvent(context: Context, event: FilterEvent) {
        val prefs = prefs(context)
        val events = getRecentEvents(context).toMutableList()
        events.add(0, event)
        val bounded = events.take(MAX_EVENTS)
        prefs.edit().putString(KEY_EVENTS, JSONArray(bounded.map { it.toJson() }).toString()).apply()
    }

    fun getRecentEvents(context: Context): List<FilterEvent> {
        val raw = prefs(context).getString(KEY_EVENTS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { json ->
                    add(FilterEvent.fromJson(json))
                }
            }
        }
    }

    fun clearEvents(context: Context) {
        prefs(context).edit().remove(KEY_EVENTS).apply()
    }

    fun validateCustomRules(rules: String): FilterBuildReport {
        return FilterEngine.build(listOf(FilterRuleSource("custom", "自定义规则", rules))).report
    }

    fun invalidate() {
        engineCache.set(null)
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

    private fun downloadRules(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
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

    private data class CachedEngine(
        val key: String,
        val engine: FilterEngine
    )
}
