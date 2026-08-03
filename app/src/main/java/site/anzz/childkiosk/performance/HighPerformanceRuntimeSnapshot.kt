package site.anzz.childkiosk.performance

import org.json.JSONArray
import org.json.JSONObject

internal const val HIGH_PERFORMANCE_RUNTIME_SCHEMA_VERSION = 1
internal const val HIGH_PERFORMANCE_SESSION_POLICY_FOLLOW_PAGE = "FOLLOW_PAGE"
internal const val DEFAULT_HIGH_PERFORMANCE_WAKE_LOCK_LEASE_MS = 10 * 60_000L
internal const val DEFAULT_HIGH_PERFORMANCE_STOP_GRACE_PERIOD_MS = 15_000L

data class HighPerformanceRuntimeRule(
    val id: String,
    val origin: String,
    val enabled: Boolean,
    val includeSubdomains: Boolean,
    val displayName: String?,
    val sessionPolicy: String = HIGH_PERFORMANCE_SESSION_POLICY_FOLLOW_PAGE,
    val updatedAt: Long
) {
    init {
        require(id.isNotBlank() && id.length <= MAX_RULE_ID_LENGTH) { "Invalid high-performance rule id" }
        require(origin.isNotBlank() && origin.length <= MAX_ORIGIN_LENGTH) { "Invalid high-performance origin" }
        require(displayName == null || displayName.length <= MAX_DISPLAY_NAME_LENGTH) {
            "High-performance rule display name is too long"
        }
        require(displayName == null || displayName.none(Char::isISOControl)) {
            "High-performance rule display name contains control characters"
        }
        require(sessionPolicy == HIGH_PERFORMANCE_SESSION_POLICY_FOLLOW_PAGE) {
            "Unsupported high-performance session policy"
        }
        require(updatedAt >= 0L) { "Invalid high-performance rule timestamp" }
    }

    companion object {
        const val MAX_RULE_ID_LENGTH = 128
        const val MAX_ORIGIN_LENGTH = 512
        const val MAX_DISPLAY_NAME_LENGTH = 120
    }
}

/**
 * Immutable configuration consumed by the isolated WebView process.
 *
 * Parsing is deliberately fail-closed: malformed or unsupported input becomes a disabled
 * snapshot, never a partially parsed enabled configuration.
 */
data class HighPerformanceRuntimeSnapshot(
    val schemaVersion: Int = HIGH_PERFORMANCE_RUNTIME_SCHEMA_VERSION,
    val configVersion: Long,
    val enabled: Boolean,
    val experimentalCdpContinuityEnabled: Boolean = false,
    val generatedAt: Long,
    val rules: List<HighPerformanceRuntimeRule>,
    val wakeLockLeaseMs: Long = DEFAULT_HIGH_PERFORMANCE_WAKE_LOCK_LEASE_MS,
    val stopGracePeriodMs: Long = DEFAULT_HIGH_PERFORMANCE_STOP_GRACE_PERIOD_MS
) {
    init {
        require(schemaVersion == HIGH_PERFORMANCE_RUNTIME_SCHEMA_VERSION) {
            "Unsupported high-performance runtime schema"
        }
        require(configVersion >= 0L) { "Invalid high-performance config version" }
        require(generatedAt >= 0L) { "Invalid high-performance generation timestamp" }
        require(rules.size <= MAX_RULE_COUNT) { "Too many high-performance rules" }
        require(wakeLockLeaseMs in MIN_WAKE_LOCK_LEASE_MS..MAX_WAKE_LOCK_LEASE_MS) {
            "Invalid high-performance WakeLock lease"
        }
        require(stopGracePeriodMs in 0L..MAX_STOP_GRACE_PERIOD_MS) {
            "Invalid high-performance stop grace period"
        }
    }

    val enabledRules: List<HighPerformanceRuntimeRule>
        get() = if (enabled) rules.filter(HighPerformanceRuntimeRule::enabled) else emptyList()

    fun toJson(): JSONObject {
        val orderedRules = rules.sortedWith(compareBy(HighPerformanceRuntimeRule::origin, HighPerformanceRuntimeRule::id))
        return JSONObject()
            .put(KEY_SCHEMA_VERSION, schemaVersion)
            .put(KEY_CONFIG_VERSION, configVersion)
            .put(KEY_ENABLED, enabled)
            .put(KEY_EXPERIMENTAL_CDP_CONTINUITY_ENABLED, experimentalCdpContinuityEnabled)
            .put(KEY_GENERATED_AT, generatedAt)
            .put(KEY_WAKE_LOCK_LEASE_MS, wakeLockLeaseMs)
            .put(KEY_STOP_GRACE_PERIOD_MS, stopGracePeriodMs)
            .put(
                KEY_RULES,
                JSONArray().apply {
                    orderedRules.forEach { rule ->
                        put(
                            JSONObject()
                                .put(KEY_RULE_ID, rule.id)
                                .put(KEY_RULE_ORIGIN, rule.origin)
                                .put(KEY_RULE_ENABLED, rule.enabled)
                                .put(KEY_RULE_INCLUDE_SUBDOMAINS, rule.includeSubdomains)
                                .put(KEY_RULE_DISPLAY_NAME, rule.displayName ?: JSONObject.NULL)
                                .put(KEY_RULE_SESSION_POLICY, rule.sessionPolicy)
                                .put(KEY_RULE_UPDATED_AT, rule.updatedAt)
                        )
                    }
                }
            )
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        const val MAX_RULE_COUNT = 200
        const val MAX_SERIALIZED_BYTES = 262_144
        const val MIN_WAKE_LOCK_LEASE_MS = 60_000L
        const val MAX_WAKE_LOCK_LEASE_MS = 15 * 60_000L
        const val MAX_STOP_GRACE_PERIOD_MS = 30_000L

        fun disabled(
            configVersion: Long = 0L,
            generatedAt: Long = System.currentTimeMillis()
        ): HighPerformanceRuntimeSnapshot {
            return HighPerformanceRuntimeSnapshot(
                configVersion = configVersion.coerceAtLeast(0L),
                enabled = false,
                experimentalCdpContinuityEnabled = false,
                generatedAt = generatedAt.coerceAtLeast(0L),
                rules = emptyList()
            )
        }

        fun parseOrDisabled(
            raw: String?,
            minimumConfigVersion: Long = 0L
        ): HighPerformanceRuntimeSnapshot {
            if (raw.isNullOrBlank() || raw.toByteArray(Charsets.UTF_8).size > MAX_SERIALIZED_BYTES) {
                return disabled(minimumConfigVersion)
            }

            val safeMinimumVersion = minimumConfigVersion.coerceAtLeast(0L)
            var observedVersion = safeMinimumVersion
            return runCatching {
                val json = JSONObject(raw)
                val serializedVersion = json.strictLong(KEY_CONFIG_VERSION)
                require(serializedVersion >= 0L) { "Invalid high-performance config version" }
                require(serializedVersion >= safeMinimumVersion) {
                    "Stale high-performance config version"
                }
                observedVersion = serializedVersion
                require(json.strictInt(KEY_SCHEMA_VERSION) == HIGH_PERFORMANCE_RUNTIME_SCHEMA_VERSION)

                val parsedRules = json.strictArray(KEY_RULES).let { array ->
                    require(array.length() <= MAX_RULE_COUNT)
                    buildList(array.length()) {
                        for (index in 0 until array.length()) {
                            val item = array.optJSONObject(index) ?: error("Invalid high-performance rule")
                            val parsedOrigin = HighPerformanceOriginParser.parseRuleOrigin(
                                item.strictString(KEY_RULE_ORIGIN)
                            )
                            val includeSubdomains = item.strictBoolean(KEY_RULE_INCLUDE_SUBDOMAINS)
                            require(!includeSubdomains || parsedOrigin.canIncludeSubdomains())
                            add(
                                HighPerformanceRuntimeRule(
                                    id = item.strictString(KEY_RULE_ID),
                                    origin = parsedOrigin.value,
                                    enabled = item.strictBoolean(KEY_RULE_ENABLED),
                                    includeSubdomains = includeSubdomains,
                                    displayName = item.strictNullableString(KEY_RULE_DISPLAY_NAME),
                                    sessionPolicy = item.strictString(KEY_RULE_SESSION_POLICY),
                                    updatedAt = item.strictLong(KEY_RULE_UPDATED_AT)
                                )
                            )
                        }
                    }
                }
                require(parsedRules.map(HighPerformanceRuntimeRule::origin).toSet().size == parsedRules.size)
                require(parsedRules.map(HighPerformanceRuntimeRule::id).toSet().size == parsedRules.size)

                HighPerformanceRuntimeSnapshot(
                    configVersion = observedVersion,
                    enabled = json.strictBoolean(KEY_ENABLED),
                    experimentalCdpContinuityEnabled = if (
                        json.has(KEY_EXPERIMENTAL_CDP_CONTINUITY_ENABLED)
                    ) {
                        json.strictBoolean(KEY_EXPERIMENTAL_CDP_CONTINUITY_ENABLED)
                    } else {
                        false
                    },
                    generatedAt = json.strictLong(KEY_GENERATED_AT),
                    rules = parsedRules.sortedWith(
                        compareBy(HighPerformanceRuntimeRule::origin, HighPerformanceRuntimeRule::id)
                    ),
                    wakeLockLeaseMs = json.strictLong(KEY_WAKE_LOCK_LEASE_MS),
                    stopGracePeriodMs = json.strictLong(KEY_STOP_GRACE_PERIOD_MS)
                )
            }.getOrElse {
                disabled(configVersion = observedVersion)
            }
        }
    }
}

private const val KEY_SCHEMA_VERSION = "schemaVersion"
private const val KEY_CONFIG_VERSION = "configVersion"
private const val KEY_ENABLED = "enabled"
private const val KEY_EXPERIMENTAL_CDP_CONTINUITY_ENABLED =
    "experimentalCdpContinuityEnabled"
private const val KEY_GENERATED_AT = "generatedAt"
private const val KEY_WAKE_LOCK_LEASE_MS = "wakeLockLeaseMs"
private const val KEY_STOP_GRACE_PERIOD_MS = "stopGracePeriodMs"
private const val KEY_RULES = "rules"
private const val KEY_RULE_ID = "id"
private const val KEY_RULE_ORIGIN = "origin"
private const val KEY_RULE_ENABLED = "enabled"
private const val KEY_RULE_INCLUDE_SUBDOMAINS = "includeSubdomains"
private const val KEY_RULE_DISPLAY_NAME = "displayName"
private const val KEY_RULE_SESSION_POLICY = "sessionPolicy"
private const val KEY_RULE_UPDATED_AT = "updatedAt"

private fun JSONObject.strictBoolean(key: String): Boolean {
    val value = get(key)
    require(value is Boolean) { "Expected boolean for $key" }
    return value
}

private fun JSONObject.strictInt(key: String): Int {
    val value = get(key)
    require(value is Number) { "Expected integer for $key" }
    val asLong = value.toLong()
    require(asLong in Int.MIN_VALUE..Int.MAX_VALUE && value.toDouble() == asLong.toDouble())
    return asLong.toInt()
}

private fun JSONObject.strictLong(key: String): Long {
    val value = get(key)
    require(value is Number) { "Expected long for $key" }
    val asLong = value.toLong()
    require(value.toDouble().isFinite() && value.toDouble() == asLong.toDouble())
    return asLong
}

private fun JSONObject.strictString(key: String): String {
    val value = get(key)
    require(value is String) { "Expected string for $key" }
    return value
}

private fun JSONObject.strictNullableString(key: String): String? {
    val value = get(key)
    if (value == JSONObject.NULL) return null
    require(value is String) { "Expected nullable string for $key" }
    return value
}

private fun JSONObject.strictArray(key: String): JSONArray {
    val value = get(key)
    require(value is JSONArray) { "Expected array for $key" }
    return value
}
