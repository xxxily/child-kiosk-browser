package site.anzz.childkiosk.performance

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import site.anzz.childkiosk.util.WebViewRuntime
import java.lang.ref.WeakReference
import java.util.UUID

internal data class HighPerformanceResourceRequest(
    val eligibleSessionCount: Int,
    val siteCount: Int,
    val oldestSessionStartedAt: Long?,
    val wakeLockLeaseMs: Long,
    val configVersion: Long
)

internal data class HighPerformanceRendererGoneResult(
    val ownerId: String,
    val tabId: String,
    val lastCommittedUrl: String?,
    val origin: String?,
    val hadActiveSession: Boolean
)

internal fun shouldDeferForegroundServiceStart(
    serviceAlreadyActive: Boolean,
    ownerInForeground: Boolean
): Boolean = !serviceAlreadyActive && !ownerInForeground

internal fun shouldUninstallPageRuntimeForStop(suppressCurrentWebViews: Boolean): Boolean =
    suppressCurrentWebViews

internal fun highPerformanceCompositeStateForActiveSessions(
    resourceProtectionReady: Boolean,
    javascriptStates: List<HighPerformanceJavascriptState>
): HighPerformanceCompositeState = if (
    resourceProtectionReady &&
    javascriptStates.isNotEmpty() &&
    javascriptStates.all { it == HighPerformanceJavascriptState.RESPONSIVE }
) {
    HighPerformanceCompositeState.ACTIVE
} else {
    HighPerformanceCompositeState.DEGRADED
}

/**
 * :webview-process state machine for top-level, rule-qualified WebViews.
 *
 * A registered WebView is a real tab owned by WebViewActivity. Pool/preload instances must never be
 * registered. Only committed top-level URLs create sessions; request interception and iframe URLs
 * do not enter this API.
 */
internal object HighPerformanceSessionController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val registrations = mutableListOf<ManagedWebView>()
    private val ownerStates = mutableMapOf<String, HighPerformanceActivityState>()
    private val ownersWarnedAboutProtectedMemoryCap = mutableSetOf<String>()
    private val restartPolicy = HighPerformanceRestartPolicy()

    private var appContext: Context? = null
    private var runtimeSnapshot = HighPerformanceRuntimeSnapshot.disabled()
    private var foregroundServiceState = HighPerformanceForegroundServiceState.STOPPED
    private var foregroundServiceError: String? = null
    private var foregroundServiceStartedAt: Long? = null
    private var serviceStopDeadlineElapsed: Long? = null
    private var wakeLockSnapshot = HighPerformanceWakeLockSnapshot(
        state = HighPerformanceWakeLockState.NOT_HELD,
        required = false,
        acquiredAt = null,
        lastRenewedAt = null,
        lastReleasedAt = null,
        leaseMs = DEFAULT_HIGH_PERFORMANCE_WAKE_LOCK_LEASE_MS,
        error = null
    )
    private var lastSessionStartedAt: Long? = null
    private var lastSessionStoppedAt: Long? = null
    private var lastInterruptionAt: Long? = null
    private var lastSystemState: HighPerformanceProcessSnapshot? = null
    private var nativeHeartbeatAt: Long = System.currentTimeMillis()

    private val heartbeat = object : Runnable {
        override fun run() {
            nativeHeartbeatAt = System.currentTimeMillis()
            val previousSystem = lastSystemState
            pruneLostWebViews()
            val context = appContext
            if (context != null) {
                lastSystemState = HighPerformanceProcessState.collect(context)
            }
            val currentSystem = lastSystemState
            val notificationsChanged = currentSystem != null && (
                previousSystem == null ||
                    currentSystem.notificationPermissionGranted != previousSystem.notificationPermissionGranted ||
                    currentSystem.notificationsVisible != previousSystem.notificationsVisible
                )
            if (notificationsChanged && currentSystem != null) {
                HighPerformanceDiagnostics.record(
                    type = "notification_permission_changed",
                    result = if (currentSystem.notificationsVisible) "available" else "missing",
                    reason = "health_check"
                )
            }
            if (currentSystem != null &&
                currentSystem.ignoringBatteryOptimizations != previousSystem?.ignoringBatteryOptimizations
            ) {
                HighPerformanceDiagnostics.record(
                    type = "battery_status_changed",
                    result = if (currentSystem.ignoringBatteryOptimizations) "ignored" else "optimized",
                    reason = "health_check"
                )
            }
            syncSystemResources("health_check")
            publishStatus()
            scheduleHeartbeat()
        }
    }

    fun initialize(context: Context, initialSnapshot: HighPerformanceRuntimeSnapshot? = null) {
        ensureMainThread()
        if (appContext == null) {
            appContext = context.applicationContext
            lastSystemState = HighPerformanceProcessState.collect(context)
            HighPerformanceDiagnostics.record(
                type = "runtime_process_initialized",
                result = if (lastSystemState?.isWebViewProcess == true) "ok" else "wrong_process",
                reason = lastSystemState?.processName
            )
        }
        initialSnapshot?.let { applySnapshot(it, source = "initial_snapshot") }
        publishStatus()
    }

    fun registerWebView(
        context: Context,
        ownerId: String,
        tabId: String,
        webView: WebView,
        initialCommittedUrl: String? = null,
        visible: Boolean = webView.visibility == View.VISIBLE,
        allowResourceRestart: Boolean = false
    ) {
        ensureMainThread()
        initialize(context)
        val safeOwnerId = safeRuntimeId(ownerId)
        val safeTabId = safeRuntimeId(tabId)
        val tabKey = HighPerformanceTabKey(safeOwnerId, safeTabId)
        val existing = find(webView)
        if (existing != null) {
            if (allowResourceRestart) authorizeRestart(existing, "registered_user_action")
            existing.visible = visible
            existing.lastPageCallbackAt = System.currentTimeMillis()
            publishStatus()
            return
        }
        val managed = ManagedWebView(
            ownerId = safeOwnerId,
            tabId = safeTabId,
            webView = WeakReference(webView),
            visible = visible,
            activityState = ownerStates[safeOwnerId] ?: HighPerformanceActivityState.CREATED,
            explicitlySuppressed = restartPolicy.shouldSuppress(tabKey, allowResourceRestart)
        )
        registrations += managed
        installPageRuntime(webView, runtimeSnapshot)
        HighPerformanceDiagnostics.record(
            type = "webview_registered",
            originOrUrl = initialCommittedUrl,
            reason = "managed_tab"
        )
        initialCommittedUrl?.let {
            managed.lastCommittedUrl = it
            evaluate(managed, it, source = "registered_committed_page")
            prepareJavascriptHeartbeat(webView)?.let { token ->
                HighPerformancePageRuntime.activate(webView, token)
            }
        }
        scheduleHeartbeat()
        publishStatus()
    }

    fun onNavigationStarted(webView: WebView, url: String?) {
        ensureMainThread()
        val managed = find(webView) ?: return
        managed.lastPageCallbackAt = System.currentTimeMillis()
        managed.pendingNavigationUrl = url
        val active = managed.session ?: return publishStatus()
        active.jsHeartbeatToken = null
        active.jsHeartbeatInstalledAt = null
        active.lastJsHeartbeatAt = null
        active.lastMainJsHeartbeatAt = null
        active.lastWorkerJsHeartbeatAt = null
        val provisionalOrigin = url?.let(::strictOriginOrNull)
        if (provisionalOrigin == null || provisionalOrigin != active.origin) {
            stopSession(managed, reason = "top_level_navigation_started", restoreRenderer = true)
            syncSystemResources("top_level_navigation_started")
            publishStatus()
        } else {
            publishStatus()
        }
    }

    /** Clears stop/failure suppression only for a container-confirmed user navigation action. */
    fun allowResourceRestart(webView: WebView, source: String) {
        ensureMainThread()
        val managed = find(webView) ?: return
        if (!managed.explicitlySuppressed) return
        authorizeRestart(managed, source)
        if (!managed.explicitlySuppressed) {
            evaluate(managed, managed.lastCommittedUrl, source = source)
            installPageRuntime(webView, runtimeSnapshot)
            prepareJavascriptHeartbeat(webView)?.let { token ->
                HighPerformancePageRuntime.bootstrapCurrentDocument(webView, token)
            }
        }
    }

    private fun authorizeRestart(managed: ManagedWebView, source: String) {
        val authorized = restartPolicy.authorize(managed.tabKey())
        managed.explicitlySuppressed = restartPolicy.shouldSuppress(
            managed.tabKey(),
            allowRestart = false
        )
        if (!authorized || managed.explicitlySuppressed) {
            HighPerformanceDiagnostics.record(
                type = "resource_restart_blocked",
                result = "suppressed",
                originOrUrl = managed.lastCommittedUrl,
                reason = "parent_authorization_required"
            )
            publishStatus()
            return
        }
        if (foregroundServiceError == "foreground_activity_required") {
            foregroundServiceError = null
        }
        HighPerformanceDiagnostics.record(
            type = "explicit_stop_suppression_cleared",
            originOrUrl = managed.lastCommittedUrl,
            reason = source
        )
        publishStatus()
    }

    /** Primary activation point; call from WebViewClient.onPageCommitVisible. */
    fun onPageCommitted(webView: WebView, url: String?) {
        ensureMainThread()
        val managed = find(webView) ?: return
        managed.lastPageCallbackAt = System.currentTimeMillis()
        managed.session?.let(::resetJavascriptHeartbeat)
        managed.pendingNavigationUrl = null
        managed.lastCommittedUrl = url
        evaluate(managed, url, source = "page_commit_visible")
    }

    /** Fallback for providers which omit onPageCommitVisible. Stale redirect callbacks are ignored. */
    fun onPageFinishedFallback(webView: WebView, url: String?) {
        ensureMainThread()
        val managed = find(webView) ?: return
        managed.lastPageCallbackAt = System.currentTimeMillis()
        if (url.isNullOrBlank()) {
            publishStatus()
            return
        }
        val currentUrl = runCatching { webView.url }.getOrNull()
        if (!currentUrl.isNullOrBlank() && currentUrl != url) {
            publishStatus()
            return
        }
        if (managed.pendingNavigationUrl != null) {
            managed.session?.let(::resetJavascriptHeartbeat)
        }
        managed.lastCommittedUrl = url
        managed.pendingNavigationUrl = null
        evaluate(managed, url, source = "page_finished_fallback")
    }

    fun onVisibilityChanged(webView: WebView, visible: Boolean) {
        ensureMainThread()
        find(webView)?.let {
            it.visible = visible
            publishStatus()
        }
    }

    fun onActivityStateChanged(
        ownerId: String,
        state: HighPerformanceActivityState
    ) {
        ensureMainThread()
        val safeOwnerId = safeRuntimeId(ownerId)
        ownerStates[safeOwnerId] = state
        registrations.filter { it.ownerId == safeOwnerId }.forEach { it.activityState = state }
        if (state == HighPerformanceActivityState.DESTROYED) {
            unregisterActivity(safeOwnerId, "activity_destroyed")
        } else {
            publishStatus()
        }
    }

    /** Applies only monotonically newer/equal snapshots; a lower version can never revive old rules. */
    fun applySnapshot(snapshot: HighPerformanceRuntimeSnapshot, source: String): Boolean {
        ensureMainThread()
        val current = runtimeSnapshot
        if (snapshot.configVersion < current.configVersion) {
            HighPerformanceDiagnostics.record(
                type = "config_snapshot_rejected",
                result = "stale",
                reason = "version_${snapshot.configVersion}_below_${current.configVersion}"
            )
            return false
        }
        val reenabled = !current.enabled && snapshot.enabled
        recordRuleSnapshotChanges(current, snapshot, source)
        runtimeSnapshot = snapshot
        if (reenabled) {
            restartPolicy.clearSuppression()
            registrations.forEach { managed ->
                managed.explicitlySuppressed = restartPolicy.shouldSuppress(
                    managed.tabKey(),
                    allowRestart = false
                )
            }
        }
        HighPerformanceDiagnostics.record(
            type = if (snapshot.enabled) "config_enabled" else "config_disabled",
            reason = source
        )
        registrations.toList().forEach { managed ->
            evaluate(managed, managed.lastCommittedUrl, source = "config_updated")
            managed.webView.get()?.let { webView ->
                installPageRuntime(webView, snapshot)
                prepareJavascriptHeartbeat(webView)?.let { token ->
                    HighPerformancePageRuntime.bootstrapCurrentDocument(webView, token)
                }
            }
        }
        syncSystemResources("config_updated")
        publishStatus()
        return true
    }

    fun refreshSystemConditions(source: String) {
        ensureMainThread()
        val context = appContext ?: return
        val before = lastSystemState
        val after = HighPerformanceProcessState.collect(context)
        lastSystemState = after
        if (before?.notificationPermissionGranted != after.notificationPermissionGranted ||
            before.notificationsVisible != after.notificationsVisible
        ) {
            HighPerformanceDiagnostics.record(
                type = "notification_permission_changed",
                result = if (after.notificationsVisible) "available" else "missing",
                reason = source
            )
        }
        if (before?.ignoringBatteryOptimizations != after.ignoringBatteryOptimizations) {
            HighPerformanceDiagnostics.record(
                type = "battery_status_changed",
                result = if (after.ignoringBatteryOptimizations) "ignored" else "optimized",
                reason = source
            )
        }
        syncSystemResources(source)
        publishStatus()
    }

    fun unregisterWebView(
        webView: WebView,
        reason: String,
        restoreRenderer: Boolean = true
    ) {
        ensureMainThread()
        val managed = find(webView) ?: return
        stopSession(managed, reason, restoreRenderer)
        HighPerformancePageRuntime.uninstall(webView)
        registrations.remove(managed)
        if (reason == "tab_closed" || reason == "child_webview_closed") {
            restartPolicy.forget(managed.tabKey())
        }
        HighPerformanceDiagnostics.record(
            type = "webview_unregistered",
            originOrUrl = managed.lastCommittedUrl,
            reason = reason
        )
        syncSystemResources(reason)
        publishStatus()
    }

    fun unregisterActivity(ownerId: String, reason: String) {
        ensureMainThread()
        val safeOwnerId = safeRuntimeId(ownerId)
        registrations.filter { it.ownerId == safeOwnerId }.toList().forEach { managed ->
            stopSession(managed, reason, restoreRenderer = true)
            managed.webView.get()?.let(HighPerformancePageRuntime::uninstall)
            registrations.remove(managed)
        }
        ownerStates.remove(safeOwnerId)
        ownersWarnedAboutProtectedMemoryCap.remove(safeOwnerId)
        restartPolicy.forgetOwner(safeOwnerId)
        syncSystemResources(reason)
        publishStatus()
    }

    /** Stops the current pages without deleting rules. Current WebViews remain suppressed. */
    fun stopAll(source: String, suppressCurrentWebViews: Boolean = true) {
        ensureMainThread()
        if (suppressCurrentWebViews) {
            restartPolicy.suppressAll(registrations.map(ManagedWebView::tabKey))
        }
        registrations.forEach { managed ->
            if (suppressCurrentWebViews) managed.explicitlySuppressed = true
            stopSession(managed, reason = source, restoreRenderer = true)
            if (shouldUninstallPageRuntimeForStop(suppressCurrentWebViews)) {
                managed.webView.get()?.let(HighPerformancePageRuntime::uninstall)
            }
        }
        HighPerformanceDiagnostics.record(
            type = if (source == "notification_action") {
                "user_stopped_from_notification"
            } else {
                "sessions_stopped_manually"
            },
            reason = source
        )
        syncSystemResources(source)
        publishStatus()
    }

    /** Health limits are owner-scoped and ordinary navigation cannot clear this latch. */
    fun stopOwnerUntilParentAuthorization(ownerId: String, source: String) {
        ensureMainThread()
        val safeOwnerId = safeRuntimeId(ownerId)
        restartPolicy.requireParentAuthorization(safeOwnerId)
        registrations.filter { it.ownerId == safeOwnerId }.forEach { managed ->
            managed.explicitlySuppressed = true
            stopSession(managed, reason = source, restoreRenderer = true)
            managed.webView.get()?.let(HighPerformancePageRuntime::uninstall)
        }
        HighPerformanceDiagnostics.record(
            type = "owner_sessions_stopped",
            reason = source
        )
        syncSystemResources(source)
        publishStatus()
    }

    fun stopAllFromPublishedSignal(configVersion: Long, source: String): Boolean {
        ensureMainThread()
        if (configVersion < runtimeSnapshot.configVersion) {
            HighPerformanceDiagnostics.record(
                type = "stop_signal_rejected",
                result = "stale",
                reason = "version_${configVersion}_below_${runtimeSnapshot.configVersion}"
            )
            return false
        }
        if (source == HIGH_PERFORMANCE_PUBLICATION_FAILED_REASON) {
            applySnapshot(
                HighPerformanceRuntimeSnapshot.disabled(configVersion = configVersion),
                source = HIGH_PERFORMANCE_PUBLICATION_FAILED_REASON
            )
        }
        stopAll(source = source, suppressCurrentWebViews = true)
        return true
    }

    fun resumeOwnerAfterParentAuthorization(ownerId: String, source: String) {
        ensureMainThread()
        val safeOwnerId = safeRuntimeId(ownerId)
        restartPolicy.clearParentAuthorizationRequirement(safeOwnerId)
        val ownerRegistrations = registrations.filter { it.ownerId == safeOwnerId }
        ownerRegistrations.forEach { managed ->
            managed.explicitlySuppressed = restartPolicy.shouldSuppress(
                managed.tabKey(),
                allowRestart = false
            )
        }
        HighPerformanceDiagnostics.record(
            type = "owner_sessions_resume_authorized",
            result = if (ownerRegistrations.any { it.explicitlySuppressed }) {
                "still_globally_suppressed"
            } else {
                "ok"
            },
            reason = source
        )
        ownerRegistrations.toList().forEach { managed ->
            evaluate(managed, managed.lastCommittedUrl, source = source)
            managed.webView.get()?.let { webView ->
                installPageRuntime(webView, runtimeSnapshot)
                prepareJavascriptHeartbeat(webView)?.let { token ->
                    HighPerformancePageRuntime.bootstrapCurrentDocument(webView, token)
                }
            }
        }
        syncSystemResources(source)
        publishStatus()
    }

    fun onBackgroundWebViewMemoryLimitEvaluated(
        ownerId: String,
        activeBackgroundCount: Int,
        maxBackgroundCount: Int,
        protectedRetainedCount: Int
    ) {
        ensureMainThread()
        val safeOwnerId = safeRuntimeId(ownerId)
        val active = activeBackgroundCount.coerceAtLeast(0)
        val limit = maxBackgroundCount.coerceAtLeast(0)
        val protected = protectedRetainedCount.coerceIn(0, active)
        if (active <= limit || protected == 0) {
            ownersWarnedAboutProtectedMemoryCap.remove(safeOwnerId)
            return
        }
        if (!ownersWarnedAboutProtectedMemoryCap.add(safeOwnerId)) return
        HighPerformanceDiagnostics.record(
            type = "protected_tab_retained_over_memory_cap",
            result = "degraded",
            reason = "background_${active}_cap_${limit}_protected_${protected}"
        )
        publishStatus()
    }

    fun isProtected(webView: WebView): Boolean {
        ensureMainThread()
        return find(webView)?.session != null
    }

    /**
     * True when the WebView has an active protected session AND its host Activity is paused or
     * stopped (real background or screen-off).
     *
     * [site.anzz.childkiosk.PersistentWebView] uses this to keep Chromium scheduling alive while
     * the host is not visible, without touching foreground IME/focus behavior: every deception is
     * disabled as soon as the Activity returns to STARTED/RESUMED.
     */
    fun isProtectedAndBackground(webView: WebView): Boolean {
        ensureMainThread()
        val managed = find(webView) ?: return false
        return managed.session != null && (
            managed.activityState == HighPerformanceActivityState.PAUSED ||
                managed.activityState == HighPerformanceActivityState.STOPPED
            )
    }

    fun prepareJavascriptHeartbeat(webView: WebView): String? {
        ensureMainThread()
        val session = find(webView)?.session ?: return null
        if (session.jsHeartbeatToken == null) {
            session.jsHeartbeatToken = UUID.randomUUID().toString()
            session.jsHeartbeatInstalledAt = System.currentTimeMillis()
            session.lastJsHeartbeatAt = null
            session.lastMainJsHeartbeatAt = null
            session.lastWorkerJsHeartbeatAt = null
            HighPerformanceDiagnostics.record(
                type = "js_heartbeat_installed",
                originOrUrl = session.origin,
                sessionId = session.tokenId
            )
            publishStatus()
        }
        return session.jsHeartbeatToken
    }

    fun onJavascriptHeartbeat(
        webView: WebView,
        token: String,
        source: HighPerformanceProbeType,
        pageTimestamp: Long
    ): Boolean {
        ensureMainThread()
        val session = find(webView)?.session ?: return false
        if (token.isBlank() || token != session.jsHeartbeatToken || pageTimestamp <= 0L) return false
        if (source == HighPerformanceProbeType.DEACTIVATED) return false
        val now = System.currentTimeMillis()
        val wasState = classifyJavascriptHeartbeat(
            now = now,
            installedAt = session.jsHeartbeatInstalledAt,
            lastMainHeartbeatAt = session.lastMainJsHeartbeatAt
        )
        when (source) {
            HighPerformanceProbeType.INIT -> session.lastJsHeartbeatAt = now
            HighPerformanceProbeType.MAIN -> {
                session.lastJsHeartbeatAt = now
                session.lastMainJsHeartbeatAt = now
            }
            HighPerformanceProbeType.WORKER -> {
                session.lastJsHeartbeatAt = now
                session.lastWorkerJsHeartbeatAt = now
            }
            HighPerformanceProbeType.WORKER_ERROR,
            HighPerformanceProbeType.DEACTIVATED,
            HighPerformanceProbeType.FREEZE,
            HighPerformanceProbeType.RESUME,
            HighPerformanceProbeType.PAGE_HIDE,
            HighPerformanceProbeType.PAGE_SHOW,
            HighPerformanceProbeType.VISIBILITY_CHANGE,
            HighPerformanceProbeType.FOCUS,
            HighPerformanceProbeType.BLUR -> Unit
        }
        val newState = classifyJavascriptHeartbeat(
            now = now,
            installedAt = session.jsHeartbeatInstalledAt,
            lastMainHeartbeatAt = session.lastMainJsHeartbeatAt
        )
        val stateChanged = newState != wasState
        if (source == HighPerformanceProbeType.WORKER_ERROR) {
            HighPerformanceDiagnostics.record(
                type = "js_worker_error",
                result = "degraded",
                originOrUrl = session.origin,
                sessionId = session.tokenId
            )
        }
        if (newState == HighPerformanceJavascriptState.RESPONSIVE &&
            wasState != HighPerformanceJavascriptState.RESPONSIVE
        ) {
            HighPerformanceDiagnostics.record(
                type = "js_heartbeat_responsive",
                originOrUrl = session.origin,
                sessionId = session.tokenId,
                reason = source.name.lowercase()
            )
        }
        if (stateChanged || source == HighPerformanceProbeType.WORKER_ERROR) {
            publishStatus()
        }
        return true
    }

    fun onPageProbe(
        webView: WebView,
        token: String,
        signal: HighPerformanceProbeSignal
    ): Boolean {
        ensureMainThread()
        return when (signal.type) {
            HighPerformanceProbeType.INIT,
            HighPerformanceProbeType.MAIN,
            HighPerformanceProbeType.WORKER,
            HighPerformanceProbeType.WORKER_ERROR -> onJavascriptHeartbeat(
                webView = webView,
                token = token,
                source = signal.type,
                pageTimestamp = signal.pageTimestamp
            )
            else -> {
                val session = find(webView)?.session ?: return false
                if (token.isBlank() || token != session.jsHeartbeatToken) return false
                HighPerformanceDiagnostics.record(
                    type = "page_lifecycle_signal",
                    originOrUrl = session.origin,
                    sessionId = session.tokenId,
                    reason = signal.type.name.lowercase()
                )
                publishStatus()
                true
            }
        }
    }

    fun onRendererGone(
        webView: WebView,
        didCrash: Boolean,
        rendererPriorityAtExit: Int
    ): HighPerformanceRendererGoneResult? {
        ensureMainThread()
        val managed = find(webView) ?: return null
        val active = managed.session
        val result = HighPerformanceRendererGoneResult(
            ownerId = managed.ownerId,
            tabId = managed.tabId,
            lastCommittedUrl = managed.lastCommittedUrl,
            origin = active?.origin ?: managed.lastCommittedUrl?.let(::strictOriginOrNull),
            hadActiveSession = active != null
        )
        stopSession(managed, reason = "renderer_gone", restoreRenderer = false)
        registrations.remove(managed)
        lastInterruptionAt = System.currentTimeMillis()
        HighPerformanceDiagnostics.record(
            type = "renderer_gone",
            result = if (didCrash) "crashed" else "killed",
            originOrUrl = result.origin,
            sessionId = active?.tokenId,
            reason = "priority_$rendererPriorityAtExit"
        )
        syncSystemResources("renderer_gone")
        publishStatus()
        return result
    }

    fun recordRendererRecoveryStarted(tabId: String, originOrUrl: String?) {
        HighPerformanceDiagnostics.record(
            type = "page_recovery_started",
            originOrUrl = originOrUrl,
            sessionId = safeRuntimeId(tabId)
        )
        publishStatus()
    }

    fun recordRendererRecoveryResult(
        tabId: String,
        originOrUrl: String?,
        success: Boolean,
        failureReason: String? = null
    ) {
        HighPerformanceDiagnostics.record(
            type = "page_recovery_result",
            result = if (success) "usable" else "failed",
            originOrUrl = originOrUrl,
            sessionId = safeRuntimeId(tabId),
            reason = if (success) {
                "page_reloaded_not_business_continuity"
            } else {
                failureReason ?: "reload_failed"
            }
        )
        publishStatus()
    }

    fun clearDiagnostics(source: String) {
        ensureMainThread()
        HighPerformanceDiagnostics.clear()
        HighPerformanceDiagnostics.record(type = "diagnostics_cleared", reason = source)
        publishStatus()
    }

    /**
     * Called by [HighPerformanceAlarmReceiver] when the AlarmManager fires.
     * Runs the full health check cycle: prune lost WebViews, collect system state,
     * sync system resources (including WakeLock renewal via FGS), publish status,
     * and reschedule the Handler-based heartbeat.
     *
     * This is critical for surviving Doze/suspend on aggressive OEM devices where
     * Handler.postDelayed callbacks stop firing despite a held WakeLock.
     */
    internal fun triggerAlarmHealthCheck() {
        ensureMainThread()
        if (registrations.isEmpty()) return
        // Run the heartbeat logic directly — this also reschedules the next Handler-based tick.
        heartbeat.run()
    }

    internal fun currentResourceRequest(): HighPerformanceResourceRequest {
        ensureMainThread()
        val system = lastSystemState ?: appContext?.let(HighPerformanceProcessState::collect)
        val systemReady = system?.notificationPermissionGranted == true &&
            system.notificationsVisible &&
            system.foregroundServiceDeclared &&
            system.specialUseTypeDeclared
        // Resource need follows valid page tokens even while the Activity is stopped. Whether a
        // *new* FGS may be started is a separate Android 12+ foreground-state decision below.
        val eligible = if (systemReady) activeSessions() else emptyList()
        return HighPerformanceResourceRequest(
            eligibleSessionCount = eligible.size,
            siteCount = eligible.map(Session::origin).distinct().size,
            oldestSessionStartedAt = eligible.minOfOrNull(Session::startedAt),
            wakeLockLeaseMs = runtimeSnapshot.wakeLockLeaseMs,
            configVersion = runtimeSnapshot.configVersion
        )
    }

    internal fun onForegroundServiceRunning() {
        ensureMainThread()
        val wasRunning = foregroundServiceState == HighPerformanceForegroundServiceState.RUNNING
        foregroundServiceState = HighPerformanceForegroundServiceState.RUNNING
        foregroundServiceError = null
        if (foregroundServiceStartedAt == null) foregroundServiceStartedAt = System.currentTimeMillis()
        if (!wasRunning) HighPerformanceDiagnostics.record(type = "fgs_started")
        publishStatus()
    }

    internal fun onForegroundServiceStartFailed(error: String) {
        ensureMainThread()
        foregroundServiceState = HighPerformanceForegroundServiceState.FAILED
        foregroundServiceError = safeRuntimeId(error)
        HighPerformanceDiagnostics.record(
            type = "fgs_start_failed",
            result = "degraded",
            reason = error
        )
        publishStatus()
    }

    internal fun onForegroundServiceStopped(expected: Boolean, reason: String) {
        ensureMainThread()
        foregroundServiceState = if (expected) {
            HighPerformanceForegroundServiceState.STOPPED
        } else {
            HighPerformanceForegroundServiceState.FAILED
        }
        foregroundServiceStartedAt = null
        if (!expected) {
            foregroundServiceError = "service_stopped_unexpectedly"
        } else if (activeSessions().isEmpty() && foregroundServiceError != "foreground_activity_required") {
            foregroundServiceError = null
        }
        HighPerformanceDiagnostics.record(
            type = "fgs_stopped",
            result = if (expected) "ok" else "interrupted",
            reason = reason
        )
        publishStatus()
    }

    internal fun onWakeLockStateChanged(snapshot: HighPerformanceWakeLockSnapshot) {
        ensureMainThread()
        wakeLockSnapshot = snapshot
        publishStatus()
    }

    internal fun debugStateForTests(): HighPerformanceControllerDebugState {
        ensureMainThread()
        return HighPerformanceControllerDebugState(
            registrationCount = registrations.size,
            activeSessionCount = activeSessions().size,
            foregroundServiceState = foregroundServiceState,
            foregroundServiceError = foregroundServiceError,
            suppressedRegistrationCount = registrations.count { it.explicitlySuppressed },
            stoppedRegistrationCount = registrations.count {
                it.activityState == HighPerformanceActivityState.STOPPED
            }
        )
    }

    internal fun javascriptStateForTests(webView: WebView): HighPerformanceJavascriptState {
        ensureMainThread()
        val session = find(webView)?.session ?: return HighPerformanceJavascriptState.UNKNOWN
        return classifyJavascriptHeartbeat(
            now = System.currentTimeMillis(),
            installedAt = session.jsHeartbeatInstalledAt,
            lastMainHeartbeatAt = session.lastMainJsHeartbeatAt
        )
    }

    internal fun resetForTests() {
        ensureMainThread()
        mainHandler.removeCallbacks(heartbeat)
        mainHandler.removeCallbacks(stopServiceAfterGrace)
        registrations.toList().forEach { managed ->
            stopSession(managed, reason = "test_reset", restoreRenderer = true)
            managed.webView.get()?.let(HighPerformancePageRuntime::uninstall)
        }
        registrations.clear()
        ownerStates.clear()
        ownersWarnedAboutProtectedMemoryCap.clear()
        restartPolicy.clearSuppression()
        appContext = null
        runtimeSnapshot = HighPerformanceRuntimeSnapshot.disabled()
        foregroundServiceState = HighPerformanceForegroundServiceState.STOPPED
        foregroundServiceError = null
        foregroundServiceStartedAt = null
        serviceStopDeadlineElapsed = null
        wakeLockSnapshot = HighPerformanceWakeLockSnapshot(
            state = HighPerformanceWakeLockState.NOT_HELD,
            required = false,
            acquiredAt = null,
            lastRenewedAt = null,
            lastReleasedAt = null,
            leaseMs = DEFAULT_HIGH_PERFORMANCE_WAKE_LOCK_LEASE_MS,
            error = null
        )
        lastSessionStartedAt = null
        lastSessionStoppedAt = null
        lastInterruptionAt = null
        lastSystemState = null
        nativeHeartbeatAt = System.currentTimeMillis()
    }

    private fun evaluate(managed: ManagedWebView, url: String?, source: String) {
        managed.explicitlySuppressed = restartPolicy.shouldSuppress(
            managed.tabKey(),
            allowRestart = false
        )
        val rule = if (runtimeSnapshot.enabled && !url.isNullOrBlank()) {
            HighPerformanceOriginMatcher.match(url, runtimeSnapshot.enabledRules)
        } else {
            null
        }
        val origin = url?.let(::strictOriginOrNull)
        if (rule == null || origin == null || managed.explicitlySuppressed) {
            stopSession(
                managed,
                reason = when {
                    managed.explicitlySuppressed -> "explicit_stop_suppression"
                    !runtimeSnapshot.enabled -> "config_disabled"
                    else -> "origin_not_matched"
                },
                restoreRenderer = true
            )
            syncSystemResources("origin_not_matched")
            publishStatus()
            return
        }

        val existing = managed.session
        if (existing != null && existing.origin == origin && existing.ruleId == rule.id) {
            existing.displayName = rule.displayName
            existing.lastPageCallbackAt = managed.lastPageCallbackAt
            publishStatus()
            return
        }
        if (existing != null) {
            stopSession(managed, reason = "committed_origin_changed", restoreRenderer = true)
        }

        val webView = managed.webView.get() ?: return
        val rendererApplied = runCatching {
            WebViewRuntime.applyRendererPriorityPolicy(webView, highPerformance = true)
        }.isSuccess
        val now = System.currentTimeMillis()
        val session = Session(
            tokenId = UUID.randomUUID().toString(),
            ruleId = rule.id,
            displayName = rule.displayName,
            origin = origin,
            startedAt = now,
            lastPageCallbackAt = managed.lastPageCallbackAt,
            rendererApplied = rendererApplied
        )
        managed.session = session
        lastSessionStartedAt = now
        HighPerformanceDiagnostics.record(
            type = "session_started",
            result = if (rendererApplied) "ok" else "degraded",
            originOrUrl = origin,
            sessionId = session.tokenId,
            reason = source
        )
        HighPerformanceDiagnostics.record(
            type = "renderer_policy_applied",
            result = if (rendererApplied) "ok" else "failed",
            originOrUrl = origin,
            sessionId = session.tokenId
        )
        syncSystemResources(source)
        publishStatus()
    }

    private fun stopSession(managed: ManagedWebView, reason: String, restoreRenderer: Boolean) {
        val session = managed.session ?: return
        managed.session = null
        managed.webView.get()?.let(HighPerformancePageRuntime::deactivate)
        var rendererRestored = false
        if (restoreRenderer) {
            managed.webView.get()?.let { webView ->
                runCatching {
                    WebViewRuntime.applyRendererPriorityPolicy(webView, highPerformance = false)
                }.onSuccess {
                    rendererRestored = true
                }.onFailure {
                    HighPerformanceDiagnostics.record(
                        type = "renderer_policy_restore_failed",
                        result = "failed",
                        originOrUrl = session.origin,
                        sessionId = session.tokenId,
                        reason = it.javaClass.simpleName
                    )
                }
            }
        }
        lastSessionStoppedAt = System.currentTimeMillis()
        HighPerformanceDiagnostics.record(
            type = "session_stopped",
            originOrUrl = session.origin,
            sessionId = session.tokenId,
            reason = reason
        )
        if (rendererRestored) {
            HighPerformanceDiagnostics.record(
                type = "renderer_policy_restored",
                originOrUrl = session.origin,
                sessionId = session.tokenId,
                reason = reason
            )
        }
    }

    private fun resetJavascriptHeartbeat(session: Session) {
        session.jsHeartbeatToken = null
        session.jsHeartbeatInstalledAt = null
        session.lastJsHeartbeatAt = null
        session.lastMainJsHeartbeatAt = null
        session.lastWorkerJsHeartbeatAt = null
        session.lastReportedJavascriptState = HighPerformanceJavascriptState.UNKNOWN
    }

    private fun syncSystemResources(reason: String) {
        val context = appContext ?: return
        val request = currentResourceRequest()
        if (request.eligibleSessionCount > 0) {
            serviceStopDeadlineElapsed = null
            mainHandler.removeCallbacks(stopServiceAfterGrace)
            val serviceAlreadyActive = foregroundServiceState == HighPerformanceForegroundServiceState.RUNNING ||
                foregroundServiceState == HighPerformanceForegroundServiceState.STARTING
            val ownerInForeground = registrations.any { managed ->
                managed.session != null && (
                    managed.activityState == HighPerformanceActivityState.STARTED ||
                        managed.activityState == HighPerformanceActivityState.RESUMED
                    )
            }
            if (shouldDeferForegroundServiceStart(serviceAlreadyActive, ownerInForeground)) {
                if (foregroundServiceError != "foreground_activity_required") {
                    foregroundServiceError = "foreground_activity_required"
                    HighPerformanceDiagnostics.record(
                        type = "fgs_start_deferred",
                        result = "degraded",
                        reason = "foreground_activity_required"
                    )
                }
                publishStatus()
                return
            }
            if (!serviceAlreadyActive) {
                foregroundServiceState = HighPerformanceForegroundServiceState.STARTING
            }
            HighPerformanceForegroundService.requestSync(
                context = context,
                serviceAlreadyRunning = serviceAlreadyActive,
                source = reason
            ).onFailure { failure ->
                onForegroundServiceStartFailed(failure.javaClass.simpleName)
            }
        } else if (
            foregroundServiceState == HighPerformanceForegroundServiceState.RUNNING ||
            foregroundServiceState == HighPerformanceForegroundServiceState.STARTING
        ) {
            val immediate = reason == "config_disabled" ||
                reason == "config_updated" ||
                reason == "admin_stop" ||
                reason == "notification_action" ||
                reason == "sessions_stopped_manually" ||
                reason == "activity_destroyed" ||
                reason == "renderer_gone" ||
                reason == "notification_unavailable" ||
                reason == "webview_reference_health_check"
            val graceMs = if (immediate) 0L else runtimeSnapshot.stopGracePeriodMs
            if (graceMs == 0L) {
                mainHandler.removeCallbacks(stopServiceAfterGrace)
                serviceStopDeadlineElapsed = null
                requestServiceSync(context, reason)
            } else if (serviceStopDeadlineElapsed == null) {
                serviceStopDeadlineElapsed = android.os.SystemClock.elapsedRealtime() + graceMs
                mainHandler.postDelayed(stopServiceAfterGrace, graceMs)
            }
        }
    }

    private val stopServiceAfterGrace = Runnable {
        serviceStopDeadlineElapsed = null
        val context = appContext ?: return@Runnable
        if (currentResourceRequest().eligibleSessionCount == 0) {
            requestServiceSync(context, "session_exit_grace_elapsed")
        }
    }

    private fun requestServiceSync(context: Context, reason: String) {
        HighPerformanceForegroundService.requestSync(
            context = context,
            serviceAlreadyRunning = true,
            source = reason
        ).onFailure { failure ->
            foregroundServiceError = failure.javaClass.simpleName
            publishStatus()
        }
    }

    private fun publishStatus() {
        val context = appContext ?: return
        val now = System.currentTimeMillis()
        val system = HighPerformanceProcessState.collect(context).also { lastSystemState = it }
        val sessions = registrations.mapNotNull { managed ->
            val session = managed.session ?: return@mapNotNull null
            HighPerformanceSessionStatus(
                tokenId = session.tokenId,
                tabId = managed.tabId,
                displayName = session.displayName,
                origin = session.origin,
                startedAt = session.startedAt,
                lastPageCallbackAt = session.lastPageCallbackAt,
                jsHeartbeatInstalledAt = session.jsHeartbeatInstalledAt,
                lastJsHeartbeatAt = session.lastJsHeartbeatAt,
                lastMainJsHeartbeatAt = session.lastMainJsHeartbeatAt,
                lastWorkerJsHeartbeatAt = session.lastWorkerJsHeartbeatAt,
                javascriptState = classifyJavascriptHeartbeat(
                    now = now,
                    installedAt = session.jsHeartbeatInstalledAt,
                    lastMainHeartbeatAt = session.lastMainJsHeartbeatAt
                ).also { state ->
                    if (state == HighPerformanceJavascriptState.STALE &&
                        session.lastReportedJavascriptState != HighPerformanceJavascriptState.STALE
                    ) {
                        HighPerformanceDiagnostics.record(
                            type = "js_heartbeat_stale",
                            result = "degraded",
                            originOrUrl = session.origin,
                            sessionId = session.tokenId,
                            reason = "main_timer_missing"
                        )
                    }
                    session.lastReportedJavascriptState = state
                },
                visible = managed.visible,
                activityState = managed.activityState,
                rendererPolicy = if (session.rendererApplied) {
                    HighPerformanceRendererPolicy.HIGH_PERFORMANCE_IMPORTANT_NOT_WAIVED
                } else {
                    HighPerformanceRendererPolicy.BASELINE_IMPORTANT_WAIVED
                },
                fullSystemProtection = session.rendererApplied &&
                    foregroundServiceState == HighPerformanceForegroundServiceState.RUNNING &&
                    wakeLockSnapshot.state == HighPerformanceWakeLockState.HELD &&
                    system.notificationsVisible &&
                    system.ignoringBatteryOptimizations
            )
        }
        val status = HighPerformanceRuntimeStatus(
            processInstanceId = system.processInstanceId,
            processName = system.processName,
            pid = system.pid,
            processStartedAt = system.processStartedAt,
            appVersionName = system.appVersionName,
            appVersionCode = system.appVersionCode,
            androidRelease = system.androidRelease,
            androidSdkInt = system.androidSdkInt,
            manufacturer = system.manufacturer,
            model = system.model,
            webViewPackageName = system.webViewPackageName,
            webViewVersionName = system.webViewVersionName,
            updatedAt = now,
            nativeHeartbeatAt = nativeHeartbeatAt,
            appliedConfigVersion = runtimeSnapshot.configVersion,
            configuredRuleCount = runtimeSnapshot.enabledRules.size,
            compositeState = compositeState(system, sessions),
            notificationPermissionGranted = system.notificationPermissionGranted,
            notificationsVisible = system.notificationsVisible,
            ignoringBatteryOptimizations = system.ignoringBatteryOptimizations,
            screenInteractive = system.screenInteractive,
            foregroundServiceDeclared = system.foregroundServiceDeclared,
            specialUseTypeDeclared = system.specialUseTypeDeclared,
            foregroundServiceState = foregroundServiceState,
            foregroundServiceError = foregroundServiceError,
            foregroundServiceStartedAt = foregroundServiceStartedAt,
            wakeLockState = wakeLockSnapshot.state,
            wakeLockAcquiredAt = wakeLockSnapshot.acquiredAt,
            wakeLockLastReleasedAt = wakeLockSnapshot.lastReleasedAt,
            wakeLockError = wakeLockSnapshot.error,
            lastSessionStartedAt = lastSessionStartedAt,
            lastSessionStoppedAt = lastSessionStoppedAt,
            lastInterruptionAt = lastInterruptionAt,
            sessions = sessions,
            recentEvents = HighPerformanceDiagnostics.snapshot(80)
        )
        HighPerformanceRuntimeStatusStore.publish(context, status)
    }

    private fun recordRuleSnapshotChanges(
        previous: HighPerformanceRuntimeSnapshot,
        current: HighPerformanceRuntimeSnapshot,
        source: String
    ) {
        val previousById = previous.rules.associateBy(HighPerformanceRuntimeRule::id)
        val currentById = current.rules.associateBy(HighPerformanceRuntimeRule::id)

        current.rules
            .filter { it.id !in previousById }
            .sortedBy(HighPerformanceRuntimeRule::origin)
            .forEach { rule ->
                HighPerformanceDiagnostics.record(
                    type = "rule_added",
                    originOrUrl = rule.origin,
                    reason = source,
                    timestamp = rule.updatedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
                )
            }
        current.rules
            .filter { rule -> previousById[rule.id]?.let { it != rule } == true }
            .sortedBy(HighPerformanceRuntimeRule::origin)
            .forEach { rule ->
                HighPerformanceDiagnostics.record(
                    type = "rule_updated",
                    originOrUrl = rule.origin,
                    reason = source,
                    timestamp = rule.updatedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
                )
            }
        previous.rules
            .filter { it.id !in currentById }
            .sortedBy(HighPerformanceRuntimeRule::origin)
            .forEach { rule ->
                HighPerformanceDiagnostics.record(
                    type = "rule_removed",
                    originOrUrl = rule.origin,
                    reason = source,
                    timestamp = current.generatedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
                )
            }
    }

    private fun compositeState(
        system: HighPerformanceProcessSnapshot,
        sessions: List<HighPerformanceSessionStatus>
    ): HighPerformanceCompositeState {
        if (!runtimeSnapshot.enabled) return HighPerformanceCompositeState.DISABLED
        if (runtimeSnapshot.enabledRules.isEmpty()) return HighPerformanceCompositeState.NO_RULES
        if (sessions.isNotEmpty()) {
            return highPerformanceCompositeStateForActiveSessions(
                resourceProtectionReady =
                    sessions.all {
                        it.rendererPolicy == HighPerformanceRendererPolicy.HIGH_PERFORMANCE_IMPORTANT_NOT_WAIVED
                    } &&
                foregroundServiceState == HighPerformanceForegroundServiceState.RUNNING &&
                wakeLockSnapshot.state == HighPerformanceWakeLockState.HELD &&
                system.notificationsVisible &&
                    system.ignoringBatteryOptimizations,
                javascriptStates = sessions.map(HighPerformanceSessionStatus::javascriptState)
            )
        }
        if (lastInterruptionAt != null &&
            (lastSessionStartedAt == null || lastInterruptionAt!! >= lastSessionStartedAt!!)
        ) {
            return HighPerformanceCompositeState.INTERRUPTED
        }
        if (!system.notificationPermissionGranted || !system.notificationsVisible) {
            return HighPerformanceCompositeState.NEEDS_NOTIFICATION_PERMISSION
        }
        if (!system.ignoringBatteryOptimizations) {
            return HighPerformanceCompositeState.NEEDS_BATTERY_SETUP
        }
        if (!system.foregroundServiceDeclared || !system.specialUseTypeDeclared) {
            return HighPerformanceCompositeState.ERROR
        }
        if (foregroundServiceState == HighPerformanceForegroundServiceState.FAILED) {
            return HighPerformanceCompositeState.INTERRUPTED
        }
        return HighPerformanceCompositeState.READY
    }

    private fun pruneLostWebViews() {
        var changed = false
        registrations.filter { it.webView.get() == null }.toList().forEach { managed ->
            val session = managed.session
            managed.session = null
            registrations.remove(managed)
            changed = true
            if (session != null) {
                lastSessionStoppedAt = System.currentTimeMillis()
                HighPerformanceDiagnostics.record(
                    type = "session_stopped",
                    result = "interrupted",
                    originOrUrl = session.origin,
                    sessionId = session.tokenId,
                    reason = "webview_reference_lost"
                )
            }
        }
        if (changed) syncSystemResources("webview_reference_health_check")
    }

    private fun scheduleHeartbeat() {
        mainHandler.removeCallbacks(heartbeat)
        if (registrations.isNotEmpty()) mainHandler.postDelayed(heartbeat, STATUS_HEARTBEAT_MS)
    }

    private fun find(webView: WebView): ManagedWebView? =
        registrations.firstOrNull { it.webView.get() === webView }

    private fun installPageRuntime(webView: WebView, snapshot: HighPerformanceRuntimeSnapshot) {
        val result = HighPerformancePageRuntime.install(webView, snapshot) { sourceWebView, signal ->
            onPageProbe(sourceWebView, signal.token, signal)
        }
        HighPerformanceDiagnostics.record(
            type = "page_runtime_configured",
            result = if (result.installed) "ok" else "inactive",
            originOrUrl = webView.url,
            reason = result.reason ?: "origins_${result.allowedOriginRules.size}"
        )
    }

    private fun activeSessions(): List<Session> = registrations.mapNotNull(ManagedWebView::session)

    private fun strictOriginOrNull(url: String): String? = runCatching {
        HighPerformanceOriginParser.extractFromUrl(url).value
    }.getOrNull()

    private fun safeRuntimeId(raw: String): String = raw.trim()
        .replace(Regex("[^A-Za-z0-9._:-]"), "_")
        .take(96)
        .ifBlank { "unknown" }

    private fun ensureMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "HighPerformanceSessionController must be called on the WebView process main thread"
        }
    }

    private data class ManagedWebView(
        val ownerId: String,
        val tabId: String,
        val webView: WeakReference<WebView>,
        var visible: Boolean,
        var activityState: HighPerformanceActivityState,
        var pendingNavigationUrl: String? = null,
        var lastCommittedUrl: String? = null,
        var lastPageCallbackAt: Long = System.currentTimeMillis(),
        var explicitlySuppressed: Boolean = false,
        var session: Session? = null
    ) {
        fun tabKey(): HighPerformanceTabKey = HighPerformanceTabKey(ownerId, tabId)
    }

    private data class Session(
        val tokenId: String,
        val ruleId: String,
        var displayName: String?,
        val origin: String,
        val startedAt: Long,
        var lastPageCallbackAt: Long,
        val rendererApplied: Boolean,
        var jsHeartbeatToken: String? = null,
        var jsHeartbeatInstalledAt: Long? = null,
        var lastJsHeartbeatAt: Long? = null,
        var lastMainJsHeartbeatAt: Long? = null,
        var lastWorkerJsHeartbeatAt: Long? = null,
        var lastReportedJavascriptState: HighPerformanceJavascriptState =
            HighPerformanceJavascriptState.UNKNOWN
    )

    private const val STATUS_HEARTBEAT_MS = 30_000L
}

internal data class HighPerformanceControllerDebugState(
    val registrationCount: Int,
    val activeSessionCount: Int,
    val foregroundServiceState: HighPerformanceForegroundServiceState,
    val foregroundServiceError: String?,
    val suppressedRegistrationCount: Int,
    val stoppedRegistrationCount: Int
)
