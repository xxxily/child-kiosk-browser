package com.example.childkiosk.util.filter

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

enum class FilterPreset(val storageValue: String, val label: String) {
    LIGHT("LIGHT", "轻量兼容"),
    STANDARD_CHILD("STANDARD_CHILD", "标准儿童过滤"),
    STRONG("STRONG", "强力去干扰"),
    CUSTOM("CUSTOM", "自定义");

    companion object {
        fun fromStorage(value: String?): FilterPreset {
            return entries.firstOrNull { it.storageValue == value } ?: STANDARD_CHILD
        }
    }
}

enum class FilterResourceType(val optionName: String) {
    DOCUMENT("document"),
    SCRIPT("script"),
    IMAGE("image"),
    STYLESHEET("stylesheet"),
    FONT("font"),
    MEDIA("media"),
    XMLHTTPREQUEST("xmlhttprequest"),
    SUBDOCUMENT("subdocument"),
    POPUP("popup"),
    WEBSOCKET("websocket"),
    PING("ping"),
    OTHER("other");

    companion object {
        fun fromOption(value: String): FilterResourceType? {
            val normalized = value.lowercase(Locale.US)
            return entries.firstOrNull { it.optionName == normalized }
        }

        fun infer(url: String?, acceptHeader: String?, isMainFrame: Boolean): FilterResourceType {
            if (isMainFrame) return DOCUMENT
            val lowerUrl = url.orEmpty().lowercase(Locale.US)
            val lowerAccept = acceptHeader.orEmpty().lowercase(Locale.US)
            return when {
                lowerUrl.startsWith("ws://") || lowerUrl.startsWith("wss://") -> WEBSOCKET
                lowerUrl.endsWith(".js") || "javascript" in lowerAccept -> SCRIPT
                lowerUrl.endsWith(".css") || "text/css" in lowerAccept -> STYLESHEET
                lowerUrl.endsWith(".png") || lowerUrl.endsWith(".jpg") || lowerUrl.endsWith(".jpeg") ||
                    lowerUrl.endsWith(".gif") || lowerUrl.endsWith(".webp") || lowerUrl.endsWith(".svg") ||
                    "image/" in lowerAccept -> IMAGE
                lowerUrl.endsWith(".woff") || lowerUrl.endsWith(".woff2") || lowerUrl.endsWith(".ttf") ||
                    lowerUrl.endsWith(".otf") || "font/" in lowerAccept -> FONT
                lowerUrl.endsWith(".mp4") || lowerUrl.endsWith(".webm") || lowerUrl.endsWith(".mp3") ||
                    lowerUrl.endsWith(".m3u8") || "video/" in lowerAccept || "audio/" in lowerAccept -> MEDIA
                lowerUrl.contains("xhr") || "application/json" in lowerAccept -> XMLHTTPREQUEST
                else -> OTHER
            }
        }
    }
}

data class FilterSubscription(
    val id: String,
    val title: String,
    val category: String,
    val homepageUrl: String,
    val subscriptionUrl: String,
    val defaultInStandard: Boolean,
    val defaultInStrong: Boolean,
    val bundledRules: String,
    val enabled: Boolean = false,
    val ruleCount: Int = 0,
    val enabledRuleCount: Int = 0,
    val unsupportedCount: Int = 0,
    val lastUpdatedAt: Long = 0L,
    val lastError: String = ""
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("category", category)
            .put("homepageUrl", homepageUrl)
            .put("subscriptionUrl", subscriptionUrl)
            .put("enabled", enabled)
            .put("lastUpdatedAt", lastUpdatedAt)
            .put("ruleCount", ruleCount)
            .put("enabledRuleCount", enabledRuleCount)
            .put("unsupportedCount", unsupportedCount)
            .put("lastError", lastError)
    }
}

data class SiteFilterOverride(
    val host: String,
    val networkDisabled: Boolean = false,
    val cosmeticDisabled: Boolean = false,
    val scriptletDisabled: Boolean = false,
    val temporaryAllowUntil: Long = 0L
) {
    fun isTemporarilyAllowed(now: Long = System.currentTimeMillis()): Boolean {
        return temporaryAllowUntil > now
    }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("host", host)
            .put("networkDisabled", networkDisabled)
            .put("cosmeticDisabled", cosmeticDisabled)
            .put("scriptletDisabled", scriptletDisabled)
            .put("temporaryAllowUntil", temporaryAllowUntil)
    }

    companion object {
        fun fromJson(json: JSONObject): SiteFilterOverride? {
            val host = json.optString("host").normalizeHost()
            if (host.isBlank()) return null
            return SiteFilterOverride(
                host = host,
                networkDisabled = json.optBoolean("networkDisabled", false),
                cosmeticDisabled = json.optBoolean("cosmeticDisabled", false),
                scriptletDisabled = json.optBoolean("scriptletDisabled", false),
                temporaryAllowUntil = json.optLong("temporaryAllowUntil", 0L)
            )
        }
    }
}

data class FilterSettings(
    val enabled: Boolean,
    val preset: FilterPreset,
    val customRules: String,
    val subscriptions: List<FilterSubscription>,
    val siteOverrides: List<SiteFilterOverride>
) {
    fun toRuntimeSnapshot(): FilterRuntimeSnapshot {
        return FilterRuntimeSnapshot(
            enabled = enabled,
            preset = preset.storageValue,
            customRules = customRules,
            enabledSubscriptionIds = subscriptions.filter { it.enabled }.map { it.id },
            siteOverrides = siteOverrides
        )
    }
}

data class FilterRuntimeSnapshot(
    val enabled: Boolean,
    val preset: String,
    val customRules: String,
    val enabledSubscriptionIds: List<String>,
    val siteOverrides: List<SiteFilterOverride>
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("enabled", enabled)
            .put("preset", preset)
            .put("customRules", customRules)
            .put("enabledSubscriptionIds", JSONArray(enabledSubscriptionIds))
            .put("siteOverrides", JSONArray(siteOverrides.map { it.toJson() }))
    }

    companion object {
        fun fromJson(json: JSONObject?): FilterRuntimeSnapshot {
            if (json == null) return default()
            val subscriptionIds = json.optJSONArray("enabledSubscriptionIds").toStringList()
            val overrides = json.optJSONArray("siteOverrides").toSiteOverrideList()
            return FilterRuntimeSnapshot(
                enabled = json.optBoolean("enabled", false),
                preset = json.optString("preset", FilterPreset.STANDARD_CHILD.storageValue),
                customRules = json.optString("customRules", ""),
                enabledSubscriptionIds = subscriptionIds,
                siteOverrides = overrides
            )
        }

        fun default(): FilterRuntimeSnapshot {
            return FilterRuntimeSnapshot(
                enabled = false,
                preset = FilterPreset.STANDARD_CHILD.storageValue,
                customRules = "",
                enabledSubscriptionIds = FilterCatalog.defaultSubscriptionsFor(FilterPreset.STANDARD_CHILD)
                    .filter { it.enabled }
                    .map { it.id },
                siteOverrides = emptyList()
            )
        }
    }
}

data class FilterRequestContext(
    val requestUrl: String,
    val topLevelUrl: String,
    val resourceType: FilterResourceType,
    val isMainFrame: Boolean,
    val method: String,
    val hasGesture: Boolean
) {
    val requestHost: String = requestUrl.hostFromUrl()
    val topLevelHost: String = topLevelUrl.hostFromUrl()
    val isThirdParty: Boolean = isThirdPartyHost(requestHost, topLevelHost)
}

data class FilterDecision(
    val action: FilterAction,
    val rule: FilterRule? = null,
    val reason: String = ""
) {
    companion object {
        val ALLOW = FilterDecision(FilterAction.ALLOW)
    }
}

enum class FilterAction {
    ALLOW,
    BLOCK,
    EXCEPTION
}

data class FilterEvent(
    val timestamp: Long,
    val action: String,
    val url: String,
    val topLevelUrl: String,
    val resourceType: String,
    val ruleText: String,
    val sourceName: String,
    val reason: String
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("timestamp", timestamp)
            .put("action", action)
            .put("url", url)
            .put("topLevelUrl", topLevelUrl)
            .put("resourceType", resourceType)
            .put("ruleText", ruleText)
            .put("sourceName", sourceName)
            .put("reason", reason)
    }

    companion object {
        fun fromJson(json: JSONObject): FilterEvent {
            return FilterEvent(
                timestamp = json.optLong("timestamp"),
                action = json.optString("action"),
                url = json.optString("url"),
                topLevelUrl = json.optString("topLevelUrl"),
                resourceType = json.optString("resourceType"),
                ruleText = json.optString("ruleText"),
                sourceName = json.optString("sourceName"),
                reason = json.optString("reason")
            )
        }
    }
}

internal fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            val value = optString(i)
            if (value.isNotBlank()) add(value)
        }
    }
}

internal fun JSONArray?.toSiteOverrideList(): List<SiteFilterOverride> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            optJSONObject(i)?.let { json ->
                SiteFilterOverride.fromJson(json)?.let(::add)
            }
        }
    }
}

internal fun String.hostFromUrl(): String {
    if (isBlank()) return ""
    return runCatching {
        URI(this).host.orEmpty().normalizeHost()
    }.getOrDefault("")
}

fun String.normalizeHost(): String {
    return trim()
        .removePrefix("http://")
        .removePrefix("https://")
        .substringBefore("/")
        .substringBefore(":")
        .trim('.')
        .lowercase(Locale.US)
}

internal fun isSameOrSubdomain(host: String, domain: String): Boolean {
    if (host.isBlank() || domain.isBlank()) return false
    return host == domain || host.endsWith(".$domain")
}

internal fun isThirdPartyHost(requestHost: String, topLevelHost: String): Boolean {
    if (requestHost.isBlank() || topLevelHost.isBlank()) return false
    val requestBase = registrableDomainApprox(requestHost)
    val topBase = registrableDomainApprox(topLevelHost)
    return requestBase != topBase
}

internal fun registrableDomainApprox(host: String): String {
    val parts = host.normalizeHost().split('.').filter { it.isNotBlank() }
    if (parts.size <= 2) return parts.joinToString(".")
    val twoPartSuffixes = setOf(
        "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn",
        "co.uk", "org.uk", "ac.uk", "com.au", "com.br"
    )
    val lastTwo = parts.takeLast(2).joinToString(".")
    return if (lastTwo in twoPartSuffixes && parts.size >= 3) {
        parts.takeLast(3).joinToString(".")
    } else {
        lastTwo
    }
}
