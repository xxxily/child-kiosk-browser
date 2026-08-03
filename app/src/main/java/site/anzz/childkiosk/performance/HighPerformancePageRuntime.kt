package site.anzz.childkiosk.performance

import android.util.Log
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.Collections
import java.util.WeakHashMap

internal enum class HighPerformanceProbeType {
    INIT,
    MAIN,
    WORKER,
    WORKER_ERROR,
    DEACTIVATED,
    FREEZE,
    RESUME,
    PAGE_HIDE,
    PAGE_SHOW,
    VISIBILITY_CHANGE,
    FOCUS,
    BLUR
}

internal data class HighPerformanceProbeSignal(
    val type: HighPerformanceProbeType,
    val pageTimestamp: Long,
    val token: String,
    val documentHidden: Boolean? = null,
    val documentVisibilityState: String? = null,
    val loadId: String? = null
)

internal data class HighPerformancePageRuntimeInstallResult(
    val installed: Boolean,
    val allowedOriginRules: Set<String>,
    val reason: String? = null
)

/** Installs Origin-scoped lifecycle diagnostics without changing the document's native state. */
internal object HighPerformancePageRuntime {
    private const val BRIDGE_NAME = "ChildKioskHighPerformance"
    private const val PROTOCOL_VERSION = 1
    private const val HEARTBEAT_INTERVAL_MS = 5_000L
    private const val MAX_ACTIVATION_TOKEN_LENGTH = 128

    private data class Installation(
        val scriptHandler: ScriptHandler
    )

    private val installations = Collections.synchronizedMap(
        WeakHashMap<WebView, Installation>()
    )

    fun install(
        webView: WebView,
        snapshot: HighPerformanceRuntimeSnapshot,
        onProbe: (WebView, HighPerformanceProbeSignal) -> Unit
    ): HighPerformancePageRuntimeInstallResult {
        uninstall(webView)
        val allowedOrigins = allowedOriginRules(snapshot)
        if (allowedOrigins.isEmpty()) {
            return HighPerformancePageRuntimeInstallResult(false, emptySet(), "no_enabled_origins")
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        ) {
            return HighPerformancePageRuntimeInstallResult(false, allowedOrigins, "webkit_feature_unsupported")
        }

        return runCatching {
            val scriptHandler = WebViewCompat.addDocumentStartJavaScript(
                webView,
                lifecycleScript(),
                allowedOrigins
            )
            installations[webView] = Installation(scriptHandler)
            WebViewCompat.addWebMessageListener(
                webView,
                BRIDGE_NAME,
                allowedOrigins
            ) { sourceWebView, message, sourceOrigin, isMainFrame, _ ->
                if (!isMainFrame || sourceWebView !== webView) return@addWebMessageListener
                val source = sourceOrigin.toString()
                if (HighPerformanceOriginMatcher.match(source, snapshot.enabledRules) == null) {
                    return@addWebMessageListener
                }
                HighPerformanceProbeProtocol.parse(message.data)?.let { signal ->
                    onProbe(sourceWebView, signal)
                }
            }
            val currentUrl = runCatching { webView.url }.getOrNull()
            if (!currentUrl.isNullOrBlank() &&
                HighPerformanceOriginMatcher.match(currentUrl, snapshot.enabledRules) != null
            ) {
                webView.evaluateJavascript(lifecycleScript(), null)
            }
            HighPerformancePageRuntimeInstallResult(true, allowedOrigins)
        }.getOrElse { failure ->
            installations.remove(webView)?.let { installation ->
                runCatching { installation.scriptHandler.remove() }
            }
            runCatching { WebViewCompat.removeWebMessageListener(webView, BRIDGE_NAME) }
            deactivate(webView)
            Log.w("HighPerformancePage", "Failed to install page runtime", failure)
            HighPerformancePageRuntimeInstallResult(
                installed = false,
                allowedOriginRules = allowedOrigins,
                reason = failure.javaClass.simpleName
            )
        }
    }

    fun uninstall(webView: WebView) {
        installations.remove(webView)?.let { installation ->
            runCatching { installation.scriptHandler.remove() }
        }
        runCatching { WebViewCompat.removeWebMessageListener(webView, BRIDGE_NAME) }
        deactivate(webView)
    }

    /** Stops the current document without removing the handler needed by a later trusted page. */
    fun deactivate(webView: WebView) {
        runCatching { webView.evaluateJavascript(cleanupScript(), null) }
    }

    fun activate(webView: WebView, token: String) {
        if (token.isBlank() || token.length > MAX_ACTIVATION_TOKEN_LENGTH) return
        val tokenLiteral = JSONObject.quote(token)
        val script = """
            (function() {
                var runtime = window.__childKioskHighPerformanceRuntime;
                if (runtime && typeof runtime.activate === 'function') runtime.activate($tokenLiteral);
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(script, null) }
    }

    /** Recreates the runtime for an already-loaded trusted document after config or user restart. */
    fun bootstrapCurrentDocument(webView: WebView, token: String) {
        if (token.isBlank() || token.length > MAX_ACTIVATION_TOKEN_LENGTH) return
        val tokenLiteral = JSONObject.quote(token)
        val script = """
            ${lifecycleScript()}
            (function() {
                var runtime = window.__childKioskHighPerformanceRuntime;
                if (runtime && typeof runtime.activate === 'function') runtime.activate($tokenLiteral);
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(script, null) }
    }

    internal fun allowedOriginRules(snapshot: HighPerformanceRuntimeSnapshot): Set<String> {
        if (!snapshot.enabled) return emptySet()
        return buildSet {
            snapshot.enabledRules.forEach { rule ->
                val origin = runCatching {
                    HighPerformanceOriginParser.parseRuleOrigin(rule.origin)
                }.getOrNull() ?: return@forEach
                add(origin.value)
                if (rule.includeSubdomains && origin.canIncludeSubdomains()) {
                    val host = if (origin.asciiHost.contains(':')) {
                        "[${origin.asciiHost}]"
                    } else {
                        origin.asciiHost
                    }
                    val port = origin.port?.let { ":$it" }.orEmpty()
                    add("${origin.scheme}://*.$host$port")
                }
            }
        }
    }

    internal fun lifecycleScript(): String = """
        (function() {
            if (window.top !== window) return;
            var bridge = window.$BRIDGE_NAME;
            if (!bridge || typeof bridge.postMessage !== 'function') return;
            var key = '__childKioskHighPerformanceRuntime';
            if (window[key] && window[key].active === true) return;
            if (window[key] && typeof window[key].deactivate === 'function') {
                window[key].deactivate(false);
            }
            var state = {
                mainTimer: 0,
                worker: null,
                handlers: [],
                active: true,
                token: '',
                loadId: String(
                    window.performance && Number.isFinite(window.performance.timeOrigin)
                        ? Math.trunc(window.performance.timeOrigin)
                        : Date.now()
                )
            };
            var readVisibility = function() {
                var hidden = null;
                var visibilityState = null;
                try {
                    var hiddenDescriptor = Object.getOwnPropertyDescriptor(Document.prototype, 'hidden');
                    if (hiddenDescriptor && typeof hiddenDescriptor.get === 'function') {
                        hidden = hiddenDescriptor.get.call(document) === true;
                    }
                } catch (_) {}
                try {
                    var visibilityDescriptor = Object.getOwnPropertyDescriptor(Document.prototype, 'visibilityState');
                    if (visibilityDescriptor && typeof visibilityDescriptor.get === 'function') {
                        visibilityState = visibilityDescriptor.get.call(document);
                    }
                } catch (_) {}
                if (hidden === null) {
                    try { hidden = document.hidden === true; } catch (_) {}
                }
                if (typeof visibilityState !== 'string') {
                    try { visibilityState = document.visibilityState; } catch (_) {}
                }
                if (typeof visibilityState !== 'string' || visibilityState.length > 32) {
                    visibilityState = null;
                }
                return { hidden: hidden, visibilityState: visibilityState };
            };
            var post = function(type) {
                if (!state.active || !state.token) return;
                try {
                    var visibility = readVisibility();
                    bridge.postMessage(JSON.stringify({
                        v: $PROTOCOL_VERSION,
                        type: type,
                        ts: Date.now(),
                        token: state.token,
                        hidden: visibility.hidden,
                        visibilityState: visibility.visibilityState,
                        loadId: state.loadId
                    }));
                } catch (_) {}
            };
            var listen = function(target, name, type) {
                var handler = function() {
                    post(type);
                };
                target.addEventListener(name, handler, true);
                state.handlers.push([target, name, handler]);
            };
            listen(document, 'visibilitychange', 'visibility_change');
            listen(document, 'webkitvisibilitychange', 'visibility_change');
            listen(document, 'freeze', 'freeze');
            listen(document, 'resume', 'resume');
            listen(window, 'pagehide', 'page_hide');
            listen(window, 'pageshow', 'page_show');
            listen(window, 'focus', 'focus');
            listen(window, 'blur', 'blur');
            state.mainTimer = window.setInterval(function() { post('main'); }, $HEARTBEAT_INTERVAL_MS);
            try {
                var source = "self.onmessage=function(e){if(e.data==='stop'){close();}else if(e.data==='ping'){postMessage(Date.now());}};" +
                    "setInterval(function(){postMessage(Date.now());},$HEARTBEAT_INTERVAL_MS);";
                var blobUrl = URL.createObjectURL(new Blob([source], { type: 'text/javascript' }));
                state.worker = new Worker(blobUrl);
                URL.revokeObjectURL(blobUrl);
                state.worker.onmessage = function() { post('worker'); };
                state.worker.onerror = function() { post('worker_error'); };
            } catch (_) {
                post('worker_error');
            }
            state.activate = function(token) {
                if (!state.active || typeof token !== 'string' || !token) return;
                state.token = token;
                post('init');
            };
            state.deactivate = function(report) {
                if (!state.active) return;
                if (report !== false) post('deactivated');
                state.active = false;
                if (state.mainTimer) window.clearInterval(state.mainTimer);
                if (state.worker) {
                    try { state.worker.postMessage('stop'); state.worker.terminate(); } catch (_) {}
                }
                state.handlers.forEach(function(item) {
                    item[0].removeEventListener(item[1], item[2], true);
                });
                state.handlers = [];
                try { delete window[key]; } catch (_) { window[key] = null; }
            };
            window[key] = state;
        })();
    """.trimIndent()

    private fun cleanupScript(): String = """
        (function() {
            var runtime = window.__childKioskHighPerformanceRuntime;
            if (runtime && typeof runtime.deactivate === 'function') runtime.deactivate(false);
        })();
    """.trimIndent()
}

internal object HighPerformanceProbeProtocol {
    fun parse(raw: String?): HighPerformanceProbeSignal? {
        if (raw.isNullOrBlank() || raw.length > MAX_PROBE_MESSAGE_LENGTH) return null
        return runCatching {
            val json = JSONObject(raw)
            if ((json.length() != 4 && json.length() != 7) ||
                json.optInt("v", -1) != PROBE_PROTOCOL_VERSION) return null
            val type = when (json.optString("type")) {
                "init" -> HighPerformanceProbeType.INIT
                "main" -> HighPerformanceProbeType.MAIN
                "worker" -> HighPerformanceProbeType.WORKER
                "worker_error" -> HighPerformanceProbeType.WORKER_ERROR
                "deactivated" -> HighPerformanceProbeType.DEACTIVATED
                "freeze" -> HighPerformanceProbeType.FREEZE
                "resume" -> HighPerformanceProbeType.RESUME
                "page_hide" -> HighPerformanceProbeType.PAGE_HIDE
                "page_show" -> HighPerformanceProbeType.PAGE_SHOW
                "visibility_change" -> HighPerformanceProbeType.VISIBILITY_CHANGE
                "focus" -> HighPerformanceProbeType.FOCUS
                "blur" -> HighPerformanceProbeType.BLUR
                else -> return null
            }
            val token = json.optString("token")
            if (token.isBlank() || token.length > MAX_PROBE_TOKEN_LENGTH) return null
            val hasVisibility = json.length() == 7
            val documentHidden = if (hasVisibility) {
                if (!json.has("hidden")) return null
                if (json.isNull("hidden")) null
                else if (json.opt("hidden") is Boolean) json.optBoolean("hidden")
                else return null
            } else {
                null
            }
            val documentVisibilityState = if (hasVisibility) {
                if (!json.has("visibilityState") || (!json.isNull("visibilityState") &&
                        json.opt("visibilityState") !is String)
                ) return null
                json.optString("visibilityState").takeIf { it in VALID_VISIBILITY_STATES }
            } else {
                null
            }
            val loadId = if (hasVisibility) {
                json.optString("loadId").takeIf { it.isNotBlank() && it.length <= MAX_LOAD_ID_LENGTH }
                    ?: return null
            } else {
                null
            }
            val timestampValue = json.opt("ts") as? Number ?: return null
            val timestamp = timestampValue.toLong()
            if (timestamp <= 0L || timestampValue.toDouble() != timestamp.toDouble()) return null
            if (kotlin.math.abs(System.currentTimeMillis() - timestamp) > MAX_PROBE_CLOCK_SKEW_MS) {
                return null
            }
            HighPerformanceProbeSignal(
                type = type,
                pageTimestamp = timestamp,
                token = token,
                documentHidden = documentHidden,
                documentVisibilityState = documentVisibilityState,
                loadId = loadId
            )
        }.getOrNull()
    }

    private const val PROBE_PROTOCOL_VERSION = 1
    private const val MAX_PROBE_MESSAGE_LENGTH = 512
    private const val MAX_PROBE_TOKEN_LENGTH = 128
    private const val MAX_LOAD_ID_LENGTH = 96
    private const val MAX_PROBE_CLOCK_SKEW_MS = 24L * 60L * 60L * 1_000L
    private val VALID_VISIBILITY_STATES = setOf("visible", "hidden", "prerender", "unloaded")
}
