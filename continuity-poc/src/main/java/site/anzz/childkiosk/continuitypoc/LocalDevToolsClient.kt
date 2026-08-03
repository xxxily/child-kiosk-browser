package site.anzz.childkiosk.continuitypoc

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom

internal data class DevToolsTarget(
    val id: String,
    val url: String,
    val webSocketDebuggerUrl: String
)

internal enum class LifecycleEdgeOutcome {
    SENT,
    PAGE_STILL_VISIBLE
}

internal class LocalDevToolsClient(
    private val socketName: String,
    private val targetUrlHint: String
) {
    private val random = SecureRandom()

    fun discoverTarget(timeoutMs: Long, shouldContinue: () -> Boolean): DevToolsTarget {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastTargets = emptyList<String>()
        var lastFailure: Throwable? = null
        while (System.currentTimeMillis() < deadline && shouldContinue()) {
            runCatching { fetchTargets() }
                .onSuccess { targets ->
                    lastTargets = targets.map(DevToolsTarget::url)
                    targets.firstOrNull { target -> target.url.contains(targetUrlHint) }?.let {
                        return it
                    }
                }
                .onFailure { lastFailure = it }
            Thread.sleep(TARGET_RETRY_DELAY_MS)
        }
        error(
            "target_not_found urls=$lastTargets last=${lastFailure?.javaClass?.simpleName}:" +
                lastFailure?.message.orEmpty().take(160)
        )
    }

    fun sendLifecycleEdgeWhenHidden(
        target: DevToolsTarget,
        edgeDelayMs: Long,
        hiddenConfirmationTimeoutMs: Long,
        shouldContinue: () -> Boolean
    ): LifecycleEdgeOutcome {
        val path = URI(target.webSocketDebuggerUrl).rawPath
            ?.takeIf(String::isNotBlank)
            ?: error("missing_websocket_path")
        LocalSocket().use { socket ->
            socket.connect(address())
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val input = socket.inputStream
            val output = socket.outputStream
            performWebSocketHandshake(input, output, path)
            var commandId = 1
            val hiddenDeadline = System.currentTimeMillis() + hiddenConfirmationTimeoutMs
            var pageHidden = false
            while (System.currentTimeMillis() < hiddenDeadline && shouldContinue()) {
                pageHidden = evaluateDocumentHidden(input, output, commandId++)
                if (pageHidden) break
                Thread.sleep(HIDDEN_RETRY_DELAY_MS)
            }
            if (!pageHidden) {
                runCatching { writeFrame(output, ByteArray(0), OPCODE_CLOSE) }
                return LifecycleEdgeOutcome.PAGE_STILL_VISIBLE
            }
            check(shouldContinue()) { "cancelled_before_frozen" }
            sendLifecycleState(input, output, commandId++, "frozen")
            Thread.sleep(edgeDelayMs)
            check(shouldContinue()) { "cancelled_before_active" }
            sendLifecycleState(input, output, commandId, "active")
            runCatching { writeFrame(output, ByteArray(0), OPCODE_CLOSE) }
            return LifecycleEdgeOutcome.SENT
        }
    }

    private fun fetchTargets(): List<DevToolsTarget> {
        LocalSocket().use { socket ->
            socket.connect(address())
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val request = buildString {
                append("GET /json/list HTTP/1.1\r\n")
                append("Host: localhost\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.outputStream.write(request.toByteArray(Charsets.US_ASCII))
            socket.outputStream.flush()
            val response = readHttpResponse(socket.inputStream)
            check(response.statusCode == 200) { "target_http_${response.statusCode}" }
            val array = JSONArray(response.body.toString(Charsets.UTF_8))
            return buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    if (json.optString("type") != "page") continue
                    val id = json.optString("id")
                    val url = json.optString("url")
                    val websocketUrl = json.optString("webSocketDebuggerUrl")
                    if (id.isNotBlank() && url.isNotBlank() && websocketUrl.isNotBlank()) {
                        add(DevToolsTarget(id, url, websocketUrl))
                    }
                }
            }
        }
    }

    private fun performWebSocketHandshake(input: InputStream, output: OutputStream, path: String) {
        val nonce = ByteArray(16).also(random::nextBytes)
        val key = Base64.encodeToString(nonce, Base64.NO_WRAP)
        val request = buildString {
            append("GET $path HTTP/1.1\r\n")
            append("Host: localhost\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $key\r\n")
            append("Sec-WebSocket-Version: 13\r\n\r\n")
        }
        output.write(request.toByteArray(Charsets.US_ASCII))
        output.flush()
        val response = readHttpResponse(input, readBody = false)
        check(response.statusCode == 101) { "websocket_http_${response.statusCode}" }
        val expectedAccept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((key + WEBSOCKET_GUID).toByteArray(Charsets.US_ASCII)),
            Base64.NO_WRAP
        )
        check(response.headers["sec-websocket-accept"] == expectedAccept) {
            "websocket_accept_mismatch"
        }
    }

    private fun evaluateDocumentHidden(
        input: InputStream,
        output: OutputStream,
        id: Int
    ): Boolean {
        val result = sendCommand(
            input = input,
            output = output,
            id = id,
            method = "Runtime.evaluate",
            params = JSONObject()
                .put("expression", "document.hidden === true")
                .put("returnByValue", true)
        )
        return result.optJSONObject("result")?.optBoolean("value", false) == true
    }

    private fun sendLifecycleState(
        input: InputStream,
        output: OutputStream,
        id: Int,
        state: String
    ) {
        sendCommand(
            input = input,
            output = output,
            id = id,
            method = "Page.setWebLifecycleState",
            params = JSONObject().put("state", state)
        )
    }

    private fun sendCommand(
        input: InputStream,
        output: OutputStream,
        id: Int,
        method: String,
        params: JSONObject
    ): JSONObject {
        val payload = JSONObject()
            .put("id", id)
            .put("method", method)
            .put("params", params)
            .toString()
            .toByteArray(Charsets.UTF_8)
        writeFrame(output, payload, OPCODE_TEXT)
        while (true) {
            val message = readMessage(input, output)
            val response = JSONObject(message.toString(Charsets.UTF_8))
            if (response.optInt("id", -1) != id) continue
            check(!response.has("error")) { "cdp_${method}_${response.optJSONObject("error")}" }
            return response.optJSONObject("result") ?: JSONObject()
        }
    }

    private fun writeFrame(output: OutputStream, payload: ByteArray, opcode: Int) {
        val mask = ByteArray(4).also(random::nextBytes)
        val header = ByteArrayOutputStream().apply {
            write(0x80 or opcode)
            when {
                payload.size < 126 -> write(0x80 or payload.size)
                payload.size <= 0xffff -> {
                    write(0x80 or 126)
                    write((payload.size ushr 8) and 0xff)
                    write(payload.size and 0xff)
                }
                else -> {
                    write(0x80 or 127)
                    write(ByteBuffer.allocate(8).putLong(payload.size.toLong()).array())
                }
            }
            write(mask)
        }.toByteArray()
        val masked = ByteArray(payload.size) { index ->
            (payload[index].toInt() xor mask[index % mask.size].toInt()).toByte()
        }
        output.write(header)
        output.write(masked)
        output.flush()
    }

    private fun readMessage(input: InputStream, output: OutputStream): ByteArray {
        val fragments = ByteArrayOutputStream()
        while (true) {
            val first = readByte(input)
            val second = readByte(input)
            val finalFrame = first and 0x80 != 0
            val opcode = first and 0x0f
            val masked = second and 0x80 != 0
            var length = (second and 0x7f).toLong()
            if (length == 126L) {
                length = ((readByte(input) shl 8) or readByte(input)).toLong()
            } else if (length == 127L) {
                length = ByteBuffer.wrap(readExactly(input, 8)).long
            }
            check(length in 0..MAX_FRAME_BYTES) { "websocket_frame_too_large=$length" }
            val mask = if (masked) readExactly(input, 4) else null
            var payload = readExactly(input, length.toInt())
            if (mask != null) {
                payload = ByteArray(payload.size) { index ->
                    (payload[index].toInt() xor mask[index % mask.size].toInt()).toByte()
                }
            }
            when (opcode) {
                OPCODE_PING -> {
                    writeFrame(output, payload, OPCODE_PONG)
                    continue
                }
                OPCODE_CLOSE -> throw EOFException("websocket_closed")
                OPCODE_TEXT, OPCODE_CONTINUATION -> fragments.write(payload)
            }
            if (finalFrame) return fragments.toByteArray()
        }
    }

    private fun readHttpResponse(input: InputStream, readBody: Boolean = true): HttpResponse {
        val headerBytes = readUntil(input, HTTP_HEADER_END, MAX_HTTP_HEADER_BYTES)
        val headerText = headerBytes.toString(Charsets.US_ASCII)
        val lines = headerText.split("\r\n")
        val statusCode = lines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull()
            ?: error("invalid_http_status")
        val headers = buildMap {
            lines.drop(1).forEach { line ->
                val separator = line.indexOf(':')
                if (separator > 0) {
                    put(
                        line.substring(0, separator).trim().lowercase(),
                        line.substring(separator + 1).trim()
                    )
                }
            }
        }
        val bodyLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val body = if (readBody && bodyLength > 0) readExactly(input, bodyLength) else ByteArray(0)
        return HttpResponse(statusCode, headers, body)
    }

    private fun readUntil(input: InputStream, marker: ByteArray, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        var matched = 0
        while (output.size() < maxBytes) {
            val value = input.read()
            if (value < 0) throw EOFException("unexpected_http_eof")
            output.write(value)
            if (value.toByte() == marker[matched]) {
                matched += 1
                if (matched == marker.size) return output.toByteArray()
            } else {
                matched = if (value.toByte() == marker[0]) 1 else 0
            }
        }
        error("http_header_too_large")
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(result, offset, size - offset)
            if (count < 0) throw EOFException("unexpected_socket_eof")
            offset += count
        }
        return result
    }

    private fun readByte(input: InputStream): Int {
        val value = input.read()
        if (value < 0) throw EOFException("unexpected_socket_eof")
        return value
    }

    private fun address() = LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT)

    private data class HttpResponse(
        val statusCode: Int,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    companion object {
        private val HTTP_HEADER_END = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
        private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val SOCKET_TIMEOUT_MS = 5_000
        private const val TARGET_RETRY_DELAY_MS = 250L
        private const val HIDDEN_RETRY_DELAY_MS = 200L
        private const val MAX_HTTP_HEADER_BYTES = 64 * 1024
        private const val MAX_FRAME_BYTES = 4L * 1024L * 1024L
        private const val OPCODE_CONTINUATION = 0x0
        private const val OPCODE_TEXT = 0x1
        private const val OPCODE_CLOSE = 0x8
        private const val OPCODE_PING = 0x9
        private const val OPCODE_PONG = 0xA
    }
}
