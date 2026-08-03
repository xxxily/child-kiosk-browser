package site.anzz.childkiosk.performance

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import site.anzz.childkiosk.data.AppDatabase

@Entity(tableName = "high_performance_configs")
data class HighPerformanceConfigEntity(
    @PrimaryKey val id: Int = SINGLETON_CONFIG_ID,
    @ColumnInfo(name = "enabled", defaultValue = "0") val enabled: Boolean = false,
    @ColumnInfo(name = "experimental_cdp_continuity_enabled", defaultValue = "0")
    val experimentalCdpContinuityEnabled: Boolean = false,
    @ColumnInfo(name = "experimental_cdp_timing_profile", defaultValue = "'BALANCED'")
    val experimentalCdpTimingProfile: String = ExperimentalCdpTimingProfile.BALANCED.name,
    @ColumnInfo(name = "verbose_diagnostics_enabled", defaultValue = "0")
    val verboseDiagnosticsEnabled: Boolean = false,
    @ColumnInfo(name = "risk_acknowledged_at") val riskAcknowledgedAt: Long? = null,
    @ColumnInfo(name = "config_version", defaultValue = "0") val configVersion: Long = 0L,
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = 0L
) {
    companion object {
        const val SINGLETON_CONFIG_ID = 1
    }
}

@Entity(
    tableName = "high_performance_origin_rules",
    indices = [Index(value = ["origin"], unique = true)]
)
data class HighPerformanceOriginRuleEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "origin") val origin: String,
    @ColumnInfo(name = "enabled", defaultValue = "1") val enabled: Boolean = true,
    @ColumnInfo(name = "include_subdomains", defaultValue = "0") val includeSubdomains: Boolean = false,
    @ColumnInfo(name = "display_name") val displayName: String? = null,
    @ColumnInfo(name = "session_policy", defaultValue = "'FOLLOW_PAGE'")
    val sessionPolicy: String = HIGH_PERFORMANCE_SESSION_POLICY_FOLLOW_PAGE,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Dao
interface HighPerformanceConfigDao {
    @Query("SELECT * FROM high_performance_configs WHERE id = 1 LIMIT 1")
    fun observeConfig(): Flow<HighPerformanceConfigEntity?>

    @Query("SELECT * FROM high_performance_origin_rules ORDER BY origin ASC, id ASC")
    fun observeRules(): Flow<List<HighPerformanceOriginRuleEntity>>

    @Query("SELECT * FROM high_performance_configs WHERE id = 1 LIMIT 1")
    suspend fun getConfig(): HighPerformanceConfigEntity?

    @Query("SELECT * FROM high_performance_origin_rules ORDER BY origin ASC, id ASC")
    suspend fun getRules(): List<HighPerformanceOriginRuleEntity>

    @Query("SELECT * FROM high_performance_origin_rules WHERE id = :id LIMIT 1")
    suspend fun getRuleById(id: String): HighPerformanceOriginRuleEntity?

    @Query("SELECT * FROM high_performance_origin_rules WHERE origin = :origin LIMIT 1")
    suspend fun getRuleByOrigin(origin: String): HighPerformanceOriginRuleEntity?

    @Query("SELECT COUNT(*) FROM high_performance_origin_rules")
    suspend fun countRules(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConfigIfAbsent(config: HighPerformanceConfigEntity): Long

    @Update
    suspend fun updateConfig(config: HighPerformanceConfigEntity): Int

    @Query(
        "UPDATE high_performance_configs " +
            "SET config_version = config_version + 1, updated_at = :updatedAt WHERE id = 1"
    )
    suspend fun bumpConfigVersion(updatedAt: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRule(rule: HighPerformanceOriginRuleEntity)

    @Update
    suspend fun updateRule(rule: HighPerformanceOriginRuleEntity): Int

    @Query("DELETE FROM high_performance_origin_rules WHERE id = :id")
    suspend fun deleteRule(id: String): Int

    @Query("DELETE FROM high_performance_origin_rules")
    suspend fun deleteAllRules(): Int
}

internal data class HighPerformancePersistedRule(
    val id: String,
    val origin: String,
    val enabled: Boolean,
    val includeSubdomains: Boolean,
    val displayName: String?,
    val sessionPolicy: String,
    val createdAt: Long,
    val updatedAt: Long
)

internal data class HighPerformancePersistedState(
    val enabled: Boolean,
    val experimentalCdpContinuityEnabled: Boolean,
    val experimentalCdpTimingProfile: ExperimentalCdpTimingProfile,
    val verboseDiagnosticsEnabled: Boolean,
    val riskAcknowledgedAt: Long?,
    val configVersion: Long,
    val updatedAt: Long,
    val rules: List<HighPerformancePersistedRule>
) {
    val hasAcknowledgedRisk: Boolean get() = riskAcknowledgedAt != null
}

internal data class HighPerformanceMutationResult(
    val state: HighPerformancePersistedState,
    val snapshot: HighPerformanceRuntimeSnapshot,
    val changed: Boolean,
    val publication: HighPerformancePublicationResult?
)

internal class HighPerformancePublicationException(
    val publication: HighPerformancePublicationResult
) : IllegalStateException(
    "高性能运行时同步失败，请重试刷新（${publication.errors.joinToString(separator = ",")}）"
)

/**
 * The sole writer for high-performance configuration.
 *
 * A process-local mutex serializes callers while the Room transaction makes each mutation and
 * config-version increment atomic. Runtime publication happens only after the transaction commits.
 */
internal class HighPerformanceConfigRepository(
    context: Context,
    private val database: AppDatabase = AppDatabase.getInstance(context),
    private val publisher: HighPerformanceSnapshotPublisher = HighPerformanceRuntimePublisher(context),
    private val now: () -> Long = System::currentTimeMillis,
    private val newRuleId: () -> String = { UUID.randomUUID().toString() }
) {
    private val dao = database.highPerformanceConfigDao()
    private val mutationMutex = highPerformanceRepositoryMutationMutex

    fun observePersistedState(): Flow<HighPerformancePersistedState> {
        return combine(dao.observeConfig(), dao.observeRules()) { config, rules ->
            persistedState(config ?: HighPerformanceConfigEntity(), rules)
        }
    }

    suspend fun getPersistedState(): HighPerformancePersistedState {
        return database.withTransaction {
            ensureConfigRow()
            persistedState(requireNotNull(dao.getConfig()), dao.getRules())
        }
    }

    suspend fun getRuntimeSnapshot(): HighPerformanceRuntimeSnapshot {
        return getPersistedState().toRuntimeSnapshot(now())
    }

    suspend fun publishCurrent(): HighPerformancePublicationResult {
        return mutationMutex.withLock {
            publisher.publish(getRuntimeSnapshot()).requireSucceeded()
        }
    }

    suspend fun acknowledgeRiskAndEnable(): HighPerformanceMutationResult {
        return mutate { timestamp ->
            val current = requireNotNull(dao.getConfig())
            val desired = current.copy(
                enabled = true,
                riskAcknowledgedAt = current.riskAcknowledgedAt ?: timestamp
            )
            if (desired.enabled == current.enabled && desired.riskAcknowledgedAt == current.riskAcknowledgedAt) {
                false
            } else {
                check(dao.updateConfig(desired) == 1)
                true
            }
        }
    }

    suspend fun setEnabled(enabled: Boolean): HighPerformanceMutationResult {
        return mutate(stopReason = if (enabled) null else HighPerformanceStopReason.CONFIG_DISABLED) { _ ->
            val current = requireNotNull(dao.getConfig())
            require(!enabled || current.riskAcknowledgedAt != null) {
                "Risk acknowledgement is required before enabling high-performance mode"
            }
            if (current.enabled == enabled) {
                false
            } else {
                check(
                    dao.updateConfig(
                        current.copy(
                            enabled = enabled,
                            experimentalCdpContinuityEnabled = if (enabled) {
                                current.experimentalCdpContinuityEnabled
                            } else {
                                false
                            }
                        )
                    ) == 1
                )
                true
            }
        }
    }

    suspend fun setExperimentalCdpContinuityEnabled(
        enabled: Boolean
    ): HighPerformanceMutationResult {
        return mutate { _ ->
            val current = requireNotNull(dao.getConfig())
            require(!enabled || current.riskAcknowledgedAt != null) {
                "Risk acknowledgement is required before enabling experimental continuity"
            }
            require(!enabled || current.enabled) {
                "High-performance mode must be enabled before experimental continuity"
            }
            if (current.experimentalCdpContinuityEnabled == enabled) {
                false
            } else {
                check(
                    dao.updateConfig(
                        current.copy(experimentalCdpContinuityEnabled = enabled)
                    ) == 1
                )
                true
            }
        }
    }

    suspend fun setExperimentalCdpTimingProfile(
        profile: ExperimentalCdpTimingProfile
    ): HighPerformanceMutationResult {
        return mutate { _ ->
            val current = requireNotNull(dao.getConfig())
            if (current.experimentalCdpTimingProfile == profile.name) {
                false
            } else {
                check(dao.updateConfig(current.copy(experimentalCdpTimingProfile = profile.name)) == 1)
                true
            }
        }
    }

    suspend fun setVerboseDiagnosticsEnabled(enabled: Boolean): HighPerformanceMutationResult {
        return mutate { _ ->
            val current = requireNotNull(dao.getConfig())
            if (current.verboseDiagnosticsEnabled == enabled) {
                false
            } else {
                check(dao.updateConfig(current.copy(verboseDiagnosticsEnabled = enabled)) == 1)
                true
            }
        }
    }

    suspend fun addOrUpdateRule(
        origin: HighPerformanceOrigin,
        displayName: String? = null,
        includeSubdomains: Boolean = false,
        insecureHttpConfirmed: Boolean = false,
        enabled: Boolean = true
    ): HighPerformanceMutationResult {
        require(origin.scheme != "http" || insecureHttpConfirmed) {
            "HTTP Origins require an explicit insecure-connection confirmation"
        }
        require(!includeSubdomains || origin.canIncludeSubdomains()) {
            "This Origin cannot grant access to subdomains"
        }
        val normalizedDisplayName = displayName?.trim()?.takeIf(String::isNotEmpty)
        require(normalizedDisplayName == null || normalizedDisplayName.length <= HighPerformanceRuntimeRule.MAX_DISPLAY_NAME_LENGTH)
        require(normalizedDisplayName == null || normalizedDisplayName.none(Char::isISOControl))

        return mutate { timestamp ->
            val existing = dao.getRuleByOrigin(origin.value)
            if (existing == null) {
                require(dao.countRules() < HighPerformanceRuntimeSnapshot.MAX_RULE_COUNT) {
                    "At most ${HighPerformanceRuntimeSnapshot.MAX_RULE_COUNT} high-performance rules are allowed"
                }
                dao.insertRule(
                    HighPerformanceOriginRuleEntity(
                        id = newRuleId(),
                        origin = origin.value,
                        enabled = enabled,
                        includeSubdomains = includeSubdomains,
                        displayName = normalizedDisplayName,
                        createdAt = timestamp,
                        updatedAt = timestamp
                    )
                )
                true
            } else {
                val desired = existing.copy(
                    enabled = enabled,
                    includeSubdomains = includeSubdomains,
                    displayName = normalizedDisplayName,
                    updatedAt = timestamp
                )
                if (existing.sameRuleValues(desired)) {
                    false
                } else {
                    check(dao.updateRule(desired) == 1)
                    true
                }
            }
        }
    }

    suspend fun addOrUpdateManualRule(
        rawOrigin: String,
        displayName: String? = null,
        includeSubdomains: Boolean = false,
        insecureHttpConfirmed: Boolean = false
    ): HighPerformanceMutationResult {
        return addOrUpdateRule(
            origin = HighPerformanceOriginParser.parseRuleOrigin(rawOrigin),
            displayName = displayName,
            includeSubdomains = includeSubdomains,
            insecureHttpConfirmed = insecureHttpConfirmed
        )
    }

    suspend fun addOrUpdateWebAppRule(
        webAppUrl: String,
        displayName: String? = null,
        includeSubdomains: Boolean = false,
        insecureHttpConfirmed: Boolean = false
    ): HighPerformanceMutationResult {
        return addOrUpdateRule(
            origin = HighPerformanceOriginParser.extractFromUrl(webAppUrl),
            displayName = displayName,
            includeSubdomains = includeSubdomains,
            insecureHttpConfirmed = insecureHttpConfirmed
        )
    }

    suspend fun setRuleEnabled(id: String, enabled: Boolean): HighPerformanceMutationResult {
        return mutate { timestamp ->
            val current = dao.getRuleById(id) ?: return@mutate false
            if (current.enabled == enabled) {
                false
            } else {
                check(dao.updateRule(current.copy(enabled = enabled, updatedAt = timestamp)) == 1)
                true
            }
        }
    }

    suspend fun setRuleIncludeSubdomains(id: String, includeSubdomains: Boolean): HighPerformanceMutationResult {
        return mutate { timestamp ->
            val current = dao.getRuleById(id) ?: return@mutate false
            val origin = HighPerformanceOriginParser.parseRuleOrigin(current.origin)
            require(!includeSubdomains || origin.canIncludeSubdomains()) {
                "This Origin cannot grant access to subdomains"
            }
            if (current.includeSubdomains == includeSubdomains) {
                false
            } else {
                check(dao.updateRule(current.copy(includeSubdomains = includeSubdomains, updatedAt = timestamp)) == 1)
                true
            }
        }
    }

    suspend fun updateRuleDisplayName(id: String, displayName: String?): HighPerformanceMutationResult {
        val normalized = displayName?.trim()?.takeIf(String::isNotEmpty)
        require(normalized == null || normalized.length <= HighPerformanceRuntimeRule.MAX_DISPLAY_NAME_LENGTH)
        require(normalized == null || normalized.none(Char::isISOControl))
        return mutate { timestamp ->
            val current = dao.getRuleById(id) ?: return@mutate false
            if (current.displayName == normalized) {
                false
            } else {
                check(dao.updateRule(current.copy(displayName = normalized, updatedAt = timestamp)) == 1)
                true
            }
        }
    }

    suspend fun removeRule(id: String): HighPerformanceMutationResult {
        return mutate { _ -> dao.deleteRule(id) > 0 }
    }

    suspend fun clearRules(): HighPerformanceMutationResult {
        return mutate { _ -> dao.deleteAllRules() > 0 }
    }

    suspend fun requestStopAll(reason: String = HighPerformanceStopReason.ADMIN_STOP): HighPerformancePublicationResult {
        return mutationMutex.withLock {
            val state = getPersistedState()
            publisher.requestStop(state.configVersion, reason).requireSucceeded()
        }
    }

    private suspend fun mutate(
        stopReason: String? = null,
        mutation: suspend (timestamp: Long) -> Boolean
    ): HighPerformanceMutationResult {
        return mutationMutex.withLock {
            val timestamp = now().coerceAtLeast(0L)
            var changed = false
            val state = database.withTransaction {
                ensureConfigRow()
                val before = requireNotNull(dao.getConfig())
                check(before.configVersion < Long.MAX_VALUE) { "High-performance config version exhausted" }
                changed = mutation(timestamp)
                if (changed) {
                    check(dao.bumpConfigVersion(timestamp) == 1)
                }
                persistedState(requireNotNull(dao.getConfig()), dao.getRules())
            }
            val snapshot = state.toRuntimeSnapshot(timestamp)
            val publication = if (changed) {
                publisher.publish(snapshot, stopReason).requireSucceeded()
            } else {
                null
            }
            HighPerformanceMutationResult(
                state = state,
                snapshot = snapshot,
                changed = changed,
                publication = publication
            )
        }
    }

    private suspend fun ensureConfigRow() {
        dao.insertConfigIfAbsent(HighPerformanceConfigEntity())
    }
}

private fun persistedState(
    config: HighPerformanceConfigEntity,
    rules: List<HighPerformanceOriginRuleEntity>
): HighPerformancePersistedState {
    return HighPerformancePersistedState(
        enabled = config.enabled,
        experimentalCdpContinuityEnabled = config.experimentalCdpContinuityEnabled,
        experimentalCdpTimingProfile = runCatching {
            ExperimentalCdpTimingProfile.valueOf(config.experimentalCdpTimingProfile)
        }.getOrDefault(ExperimentalCdpTimingProfile.BALANCED),
        verboseDiagnosticsEnabled = config.verboseDiagnosticsEnabled,
        riskAcknowledgedAt = config.riskAcknowledgedAt,
        configVersion = config.configVersion,
        updatedAt = config.updatedAt,
        rules = rules.map { rule ->
            HighPerformancePersistedRule(
                id = rule.id,
                origin = rule.origin,
                enabled = rule.enabled,
                includeSubdomains = rule.includeSubdomains,
                displayName = rule.displayName,
                sessionPolicy = rule.sessionPolicy,
                createdAt = rule.createdAt,
                updatedAt = rule.updatedAt
            )
        }
    )
}

private fun HighPerformancePersistedState.toRuntimeSnapshot(generatedAt: Long): HighPerformanceRuntimeSnapshot {
    return runCatching {
        HighPerformanceRuntimeSnapshot(
            configVersion = configVersion,
            enabled = enabled,
            experimentalCdpContinuityEnabled = experimentalCdpContinuityEnabled,
            experimentalCdpTimingProfile = experimentalCdpTimingProfile,
            verboseDiagnosticsEnabled = verboseDiagnosticsEnabled,
            generatedAt = generatedAt.coerceAtLeast(0L),
            rules = rules.map { rule ->
                val parsedOrigin = HighPerformanceOriginParser.parseRuleOrigin(rule.origin)
                require(!rule.includeSubdomains || parsedOrigin.canIncludeSubdomains())
                HighPerformanceRuntimeRule(
                    id = rule.id,
                    origin = parsedOrigin.value,
                    enabled = rule.enabled,
                    includeSubdomains = rule.includeSubdomains,
                    displayName = rule.displayName,
                    sessionPolicy = rule.sessionPolicy,
                    updatedAt = rule.updatedAt
                )
            }
        )
    }.getOrElse {
        HighPerformanceRuntimeSnapshot.disabled(configVersion = configVersion, generatedAt = generatedAt)
    }
}

private fun HighPerformanceOriginRuleEntity.sameRuleValues(other: HighPerformanceOriginRuleEntity): Boolean {
    return origin == other.origin &&
        enabled == other.enabled &&
        includeSubdomains == other.includeSubdomains &&
        displayName == other.displayName &&
        sessionPolicy == other.sessionPolicy
}

internal object HighPerformanceStopReason {
    const val CONFIG_DISABLED = "config_disabled"
    const val ADMIN_STOP = "admin_stop"
}

private fun HighPerformancePublicationResult.requireSucceeded(): HighPerformancePublicationResult {
    if (!succeeded) throw HighPerformancePublicationException(this)
    return this
}

private val highPerformanceRepositoryMutationMutex = Mutex()
