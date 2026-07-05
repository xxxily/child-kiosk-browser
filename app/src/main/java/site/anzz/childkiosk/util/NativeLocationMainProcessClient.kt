package site.anzz.childkiosk.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NativeLocationMainProcessClient(private val context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()
    private val replyMessenger = Messenger(ReplyHandler())
    private var serviceMessenger: Messenger? = null
    private var binding = false

    fun requestSingleLocation(
        config: WebViewRuntimeConfig,
        timeoutMs: Long,
        allowCached: Boolean,
        purpose: String,
        origin: String?,
        callback: (NativeLocationResult) -> Unit
    ): String {
        val requestId = UUID.randomUUID().toString()
        val pending = PendingRequest(
            bundle = NativeLocationIpc.requestBundle(config, timeoutMs, allowCached, purpose, origin).apply {
                putString(NativeLocationIpc.KEY_REQUEST_ID, requestId)
            },
            timeoutMs = timeoutMs,
            callback = callback,
            messageWhat = NativeLocationIpc.MSG_REQUEST_SINGLE
        )
        pendingRequests[requestId] = pending
        mainHandler.post {
            pending.timeoutRunnable = Runnable {
                pendingRequests.remove(requestId)?.let {
                    sendCancel(requestId)
                    it.callback(
                        NativeLocationResult(
                            success = false,
                            provider = "ipc",
                            error = NativeLocationError.TIMEOUT,
                            elapsedMs = it.elapsedMs(),
                            message = "主进程定位代理请求超时"
                        )
                    )
                }
            }.also { timeout ->
                mainHandler.postDelayed(timeout, timeoutMs.coerceAtLeast(1_000L) + IPC_TIMEOUT_GRACE_MS)
            }
            dispatchOrBind(requestId, pending)
        }
        return "main:$requestId"
    }

    fun startWatch(
        config: WebViewRuntimeConfig,
        origin: String?,
        callback: (NativeLocationResult) -> Unit
    ): String {
        val requestId = UUID.randomUUID().toString()
        val pending = PendingRequest(
            bundle = NativeLocationIpc.requestBundle(
                config = config,
                timeoutMs = config.nativeLocationRequestTimeoutMs,
                allowCached = false,
                purpose = "watch",
                origin = origin
            ).apply {
                putString(NativeLocationIpc.KEY_REQUEST_ID, requestId)
            },
            timeoutMs = config.nativeLocationWatchMaxDurationMs,
            callback = callback,
            messageWhat = NativeLocationIpc.MSG_START_WATCH
        )
        pendingRequests[requestId] = pending
        mainHandler.post {
            pending.timeoutRunnable = Runnable {
                pendingRequests.remove(requestId)?.let {
                    sendCancel(requestId)
                    it.callback(
                        NativeLocationResult(
                            success = false,
                            provider = "ipc",
                            error = NativeLocationError.TIMEOUT,
                            elapsedMs = it.elapsedMs(),
                            message = "主进程定位代理 watchPosition 超时"
                        )
                    )
                }
            }.also { timeout ->
                mainHandler.postDelayed(
                    timeout,
                    config.nativeLocationWatchMaxDurationMs.coerceAtLeast(1_000L) + IPC_TIMEOUT_GRACE_MS
                )
            }
            dispatchOrBind(requestId, pending)
        }
        return "main:$requestId"
    }

    fun cancelRequest(id: String) {
        val requestId = id.removePrefix("main:")
        val pending = pendingRequests.remove(requestId) ?: return
        pending.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        sendCancel(requestId)
    }

    fun destroy() {
        pendingRequests.keys.toList().forEach { cancelRequest("main:$it") }
        if (serviceMessenger != null || binding) {
            runCatching { appContext.unbindService(connection) }
        }
        serviceMessenger = null
        binding = false
    }

    private fun dispatchOrBind(requestId: String, pending: PendingRequest) {
        val messenger = serviceMessenger
        if (messenger != null) {
            sendRequest(requestId, pending, messenger)
            return
        }
        bindService()
    }

    private fun bindService() {
        if (binding || serviceMessenger != null) return
        binding = true
        val intent = Intent(appContext, NativeLocationMainProcessService::class.java)
        val ok = runCatching {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!ok) {
            binding = false
            failPending("无法连接主进程定位代理")
        }
    }

    private fun sendRequest(requestId: String, pending: PendingRequest, messenger: Messenger) {
        val message = Message.obtain(null, pending.messageWhat).apply {
            data = pending.bundle
            replyTo = replyMessenger
        }
        runCatching { messenger.send(message) }
            .onFailure { e ->
                Log.w(AmapLocationDebug.TAG, "Main process location proxy send failed", e)
                pendingRequests.remove(requestId)
                pending.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                pending.callback(
                    NativeLocationResult(
                        success = false,
                        provider = "ipc",
                        error = NativeLocationError.PROVIDER_UNAVAILABLE,
                        elapsedMs = pending.elapsedMs(),
                        message = "主进程定位代理发送失败: ${e.javaClass.simpleName}"
                    )
                )
            }
    }

    private fun sendCancel(requestId: String) {
        val messenger = serviceMessenger ?: return
        val message = Message.obtain(null, NativeLocationIpc.MSG_CANCEL).apply {
            data = Bundle().apply { putString(NativeLocationIpc.KEY_REQUEST_ID, requestId) }
        }
        runCatching { messenger.send(message) }
            .onFailure { e ->
                if (e !is RemoteException) {
                    Log.w(AmapLocationDebug.TAG, "Main process location proxy cancel failed", e)
                }
            }
    }

    private fun onServiceConnected(service: IBinder) {
        binding = false
        serviceMessenger = Messenger(service)
        val messenger = serviceMessenger ?: return
        pendingRequests.entries.toList().forEach { (requestId, pending) ->
            sendRequest(requestId, pending, messenger)
        }
    }

    private fun onServiceDisconnected() {
        serviceMessenger = null
        binding = false
        failPending("主进程定位代理连接断开")
    }

    private fun handleResult(data: Bundle) {
        val requestId = data.getString(NativeLocationIpc.KEY_REQUEST_ID).orEmpty()
        val pending = pendingRequests.remove(requestId) ?: return
        val result = NativeLocationIpc.resultFrom(data)
        pending.callback(result.copy(message = appendProxyMessage(result.message)))
        if (result.success && pending.messageWhat == NativeLocationIpc.MSG_START_WATCH) {
            pendingRequests[requestId] = pending
        } else {
            pending.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        }
    }

    private fun failPending(message: String) {
        pendingRequests.entries.toList().forEach { (requestId, pending) ->
            pendingRequests.remove(requestId)
            pending.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            pending.callback(
                NativeLocationResult(
                    success = false,
                    provider = "ipc",
                    error = NativeLocationError.PROVIDER_UNAVAILABLE,
                    elapsedMs = pending.elapsedMs(),
                    message = message
                )
            )
        }
    }

    private fun appendProxyMessage(message: String): String {
        val base = message.ifBlank { "定位完成" }
        return "$base；主进程定位代理"
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) {
                onServiceDisconnected()
            } else {
                this@NativeLocationMainProcessClient.onServiceConnected(service)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            this@NativeLocationMainProcessClient.onServiceDisconnected()
        }

        override fun onBindingDied(name: ComponentName?) {
            this@NativeLocationMainProcessClient.onServiceDisconnected()
            bindService()
        }

        override fun onNullBinding(name: ComponentName?) {
            this@NativeLocationMainProcessClient.onServiceDisconnected()
        }
    }

    private inner class ReplyHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                NativeLocationIpc.MSG_RESULT -> handleResult(msg.data)
                else -> super.handleMessage(msg)
            }
        }
    }

    private data class PendingRequest(
        val bundle: Bundle,
        val timeoutMs: Long,
        val callback: (NativeLocationResult) -> Unit,
        val startedAt: Long = System.currentTimeMillis(),
        var timeoutRunnable: Runnable? = null,
        val messageWhat: Int
    ) {
        fun elapsedMs(): Long = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
    }

    companion object {
        private const val IPC_TIMEOUT_GRACE_MS = 1_500L
    }
}
