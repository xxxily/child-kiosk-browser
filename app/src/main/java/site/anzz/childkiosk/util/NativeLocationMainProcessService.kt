package site.anzz.childkiosk.util

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

class NativeLocationMainProcessService : Service() {
    private val manager by lazy { NativeLocationManager(this) }
    private val requestIds = ConcurrentHashMap<String, String>()
    private val incomingMessenger = Messenger(IncomingHandler())

    override fun onBind(intent: Intent?): IBinder = incomingMessenger.binder

    override fun onDestroy() {
        requestIds.values.toList().forEach { manager.cancelRequest(it) }
        requestIds.clear()
        manager.destroy()
        super.onDestroy()
    }

    private fun handleSingleRequest(message: Message) {
        val replyTo = message.replyTo ?: return
        val data = message.data ?: Bundle.EMPTY
        val clientRequestId = data.getString(NativeLocationIpc.KEY_REQUEST_ID).orEmpty()
        if (clientRequestId.isBlank()) return
        val fallbackConfig = KioskPrefs.getWebViewRuntimeConfig(this)
        val config = NativeLocationIpc.configFrom(data, fallbackConfig)
        val nativeRequestId = manager.requestSingleLocation(
            config = config,
            timeoutMs = NativeLocationIpc.timeoutMsFrom(data, config.nativeLocationRequestTimeoutMs),
            allowCached = NativeLocationIpc.allowCachedFrom(data),
            purpose = NativeLocationIpc.purposeFrom(data),
            origin = NativeLocationIpc.originFrom(data)
        ) { result ->
            requestIds.remove(clientRequestId)
            sendResult(replyTo, clientRequestId, result)
        }
        if (nativeRequestId.isNotBlank()) {
            requestIds[clientRequestId] = nativeRequestId
        }
    }

    private fun handleWatchRequest(message: Message) {
        val replyTo = message.replyTo ?: return
        val data = message.data ?: Bundle.EMPTY
        val clientRequestId = data.getString(NativeLocationIpc.KEY_REQUEST_ID).orEmpty()
        if (clientRequestId.isBlank()) return
        val fallbackConfig = KioskPrefs.getWebViewRuntimeConfig(this)
        val config = NativeLocationIpc.configFrom(data, fallbackConfig)
        val nativeRequestId = manager.startWatch(
            config = config,
            origin = NativeLocationIpc.originFrom(data)
        ) { result ->
            sendResult(replyTo, clientRequestId, result)
            if (!result.success) {
                requestIds.remove(clientRequestId)
            }
        }
        if (nativeRequestId.isNotBlank()) {
            requestIds[clientRequestId] = nativeRequestId
        }
    }

    private fun handleCancel(message: Message) {
        val clientRequestId = message.data?.getString(NativeLocationIpc.KEY_REQUEST_ID).orEmpty()
        if (clientRequestId.isBlank()) return
        requestIds.remove(clientRequestId)?.let { manager.cancelRequest(it) }
    }

    private fun sendResult(replyTo: Messenger, clientRequestId: String, result: NativeLocationResult) {
        val response = Message.obtain(null, NativeLocationIpc.MSG_RESULT).apply {
            data = NativeLocationIpc.resultBundle(result).apply {
                putString(NativeLocationIpc.KEY_REQUEST_ID, clientRequestId)
            }
        }
        runCatching { replyTo.send(response) }
            .onFailure { Log.w(AmapLocationDebug.TAG, "Failed to send location proxy result", it) }
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                NativeLocationIpc.MSG_REQUEST_SINGLE -> handleSingleRequest(msg)
                NativeLocationIpc.MSG_START_WATCH -> handleWatchRequest(msg)
                NativeLocationIpc.MSG_CANCEL -> handleCancel(msg)
                else -> super.handleMessage(msg)
            }
        }
    }
}
