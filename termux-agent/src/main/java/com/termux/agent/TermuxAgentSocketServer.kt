package com.termux.agent

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.termux.agent.adb.AdbKey
import com.termux.agent.adb.AdbMdns
import com.termux.agent.adb.AdbPairingClient
import com.termux.agent.adb.AdbPairingService
import com.termux.agent.adb.PreferenceAdbKeyStore
import com.termux.agent.start.AgentEnvironment
import com.termux.agent.start.AgentPrivilegeOrchestrator
import com.termux.agent.start.AgentSettings
import com.termux.agent.start.AdbStarter
import com.termux.agent.start.StartOutcome
import com.termux.shared.errors.Error
import com.termux.shared.net.socket.local.LocalClientSocket
import com.termux.shared.net.socket.local.LocalSocketManager
import com.termux.shared.net.socket.local.LocalSocketManagerClientBase
import com.termux.shared.net.socket.local.LocalSocketRunConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private const val TAG = "TermuxAgentSocket"

/**
 * Local AF_UNIX HTTP/1.1 bridge allowing Termux CLI tools and AI agents to inspect and control
 * the device via [TermuxAgentClient] without ADB CLI startup overhead.
 *
 * Access is enforced by [LocalServerSocket] `SO_PEERCRED` (Termux app UID and root UID only).
 */
class TermuxAgentSocketServer private constructor(
    context: Context,
    socketPath: String
) {
    private val appContext = context.applicationContext
    private val manager: LocalSocketManager

    init {
        val config = LocalSocketRunConfig(
            "TermuxAgent",
            socketPath,
            SocketClientHandler()
        ).apply {
            setReceiveTimeout(180_000)
            setSendTimeout(180_000)
            setDeadline(0)
        }
        manager = LocalSocketManager(appContext, config)
    }

    fun start(): Boolean {
        val err = manager.start()
        if (err != null) {
            Log.e(TAG, "Failed to start TermuxAgentSocketServer: ${err.minimalErrorString}")
            return false
        }
        return manager.isRunning
    }

    fun stop() {
        runCatching { manager.stop() }
    }

    fun isRunning(): Boolean = manager.isRunning

    private inner class SocketClientHandler : LocalSocketManagerClientBase() {
        override fun getLogTag(): String = TAG

        override fun onClientAccepted(
            localSocketManager: LocalSocketManager,
            clientSocket: LocalClientSocket
        ) {
            try {
                handleHttpClient(clientSocket)
            } catch (t: Throwable) {
                Log.w(TAG, "Error handling agent socket client", t)
                runCatching {
                    val errJson = JSONObject().put("ok", false).put("error", t.message ?: t.javaClass.simpleName)
                    sendHttpResponse(clientSocket, 500, "application/json", errJson.toString().toByteArray())
                }
            } finally {
                super.onClientAccepted(localSocketManager, clientSocket)
            }
        }
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    private fun handleHttpClient(clientSocket: LocalClientSocket) {
        val req = readHttpRequest(clientSocket) ?: run {
            sendHttpResponse(
                clientSocket,
                400,
                "application/json",
                JSONObject().put("ok", false).put("error", "Malformed HTTP request").toString().toByteArray()
            )
            return
        }

        when {
            req.method == "GET" && req.path == "/v1/status" -> {
                val json = DeviceSnapshotCollector.collectStatusJson(appContext).put("ok", true)
                sendJson(clientSocket, 200, json)
            }
            req.method == "GET" && req.path == "/v1/info" -> {
                val json = DeviceSnapshotCollector.collectInfoJson(appContext).put("ok", true)
                sendJson(clientSocket, 200, json)
            }
            req.method == "POST" && req.path == "/v1/up" -> {
                val noWifi = parseBoolQuery(req.query["no_wifi"], AgentSettings.getForceNoWifi(appContext))
                val tcp = parseBoolQuery(req.query["tcp"], AgentSettings.getTcpMode(appContext))
                val outcome = runBlocking {
                    AgentPrivilegeOrchestrator.ensureStarted(
                        appContext,
                        preferNoWifi = noWifi,
                        enableTcpMode = tcp
                    )
                }
                sendJson(clientSocket, if (outcome.ok) 200 else 503, outcome.toJson())
            }
            req.method == "POST" && req.path == "/v1/down" -> {
                val stopped = TermuxAgentClient.stopServer()
                sendJson(clientSocket, 200, JSONObject().put("ok", stopped).put("serverRunning", false))
            }
            req.method == "POST" && req.path == "/v1/tcp" -> {
                val port = req.query["port"]?.toIntOrNull() ?: AgentSettings.getTcpPort(appContext)
                val result = runCatching {
                    runBlocking { AdbStarter.openTcpPort(appContext, port) }
                }
                val ok = result.getOrDefault(false)
                val json = JSONObject()
                    .put("ok", ok)
                    .put("port", port)
                    .put("adbTcpPort", AgentEnvironment.getAdbTcpPort())
                result.exceptionOrNull()?.let { json.put("error", it.message ?: it.javaClass.simpleName) }
                sendJson(clientSocket, if (ok) 200 else 503, json)
            }
            req.method == "POST" && req.path == "/v1/pair" -> {
                handlePair(clientSocket, req)
            }
            req.method == "POST" && req.path == "/v1/exec" -> {
                handleExec(clientSocket, req)
            }
            else -> {
                sendJson(
                    clientSocket,
                    404,
                    JSONObject().put("ok", false).put("error", "Unknown endpoint: ${req.method} ${req.path}")
                )
            }
        }
    }

    private fun handlePair(clientSocket: LocalClientSocket, req: HttpRequest) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            sendJson(
                clientSocket,
                400,
                JSONObject().put("ok", false).put("error", "Wireless ADB pairing requires Android 11+")
            )
            return
        }

        val bodyText = req.body.toString(StandardCharsets.UTF_8).trim()
        var code = req.query["code"]?.trim().orEmpty()
        var host = req.query["host"]?.trim().orEmpty()
        var port = req.query["port"]?.toIntOrNull() ?: -1

        if (bodyText.startsWith("{")) {
            val obj = runCatching { JSONObject(bodyText) }.getOrNull()
            if (obj != null) {
                if (code.isEmpty()) code = obj.optString("code", "").trim()
                if (host.isEmpty()) host = obj.optString("host", "").trim()
                if (port <= 0) port = obj.optInt("port", -1)
            }
        } else if (bodyText.isNotEmpty() && code.isEmpty()) {
            code = bodyText
        }

        if (code.isEmpty()) {
            // Launch foreground notification pairing flow so user can enter code directly in shade
            val intent = AdbPairingService.startIntent(appContext)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            }
            sendJson(
                clientSocket,
                200,
                JSONObject()
                    .put("ok", true)
                    .put("mode", "notification")
                    .put("message", "Started pairing notification. Open Wireless Debugging -> Pair device with pairing code and enter the 6-digit code in the notification.")
            )
            return
        }

        if (port <= 0) {
            val found = runBlocking { discoverPairingEndpoint(appContext, 12_000L) }
            if (found == null) {
                sendJson(
                    clientSocket,
                    408,
                    JSONObject().put("ok", false).put("error", "Timed out waiting for _adb-tls-pairing._tcp mDNS service; pass explicit host & port or open Pair device dialog.")
                )
                return
            }
            if (host.isEmpty()) host = found.first
            port = found.second
        }
        if (host.isEmpty()) host = "127.0.0.1"

        val pairResult = runCatching {
            val key = AdbKey(appContext, PreferenceAdbKeyStore(AgentSettings.prefs(appContext)))
            AdbPairingClient(host, port, code, key).use { it.start() }
        }
        val paired = pairResult.getOrDefault(false)
        if (!paired) {
            val err = pairResult.exceptionOrNull()?.message ?: "Pairing rejected"
            sendJson(clientSocket, 400, JSONObject().put("ok", false).put("paired", false).put("error", err))
            return
        }

        val startOutcome = runBlocking { AgentPrivilegeOrchestrator.ensureStarted(appContext) }
        sendJson(
            clientSocket,
            200,
            startOutcome.toJson().put("paired", true).put("pairHost", host).put("pairPort", port)
        )
    }

    private suspend fun discoverPairingEndpoint(context: Context, timeoutMs: Long): Pair<String, Int>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val deferred = CompletableDeferred<Pair<String, Int>>()
        val mdns = AdbMdns(context, AdbMdns.TLS_PAIRING) { (h, p) ->
            if (p > 0 && h.isNotEmpty()) deferred.complete(h to p)
        }
        mdns.start()
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            mdns.stop()
        }
    }

    private fun handleExec(clientSocket: LocalClientSocket, req: HttpRequest) {
        val bodyText = req.body.toString(StandardCharsets.UTF_8)
        var command = ""
        var cwd: String? = req.query["cwd"]
        var timeoutMs = req.query["timeout_ms"]?.toLongOrNull() ?: 30_000L

        val contentType = req.headers["content-type"].orEmpty()
        if (contentType.contains("json", ignoreCase = true) || bodyText.trimStart().startsWith("{")) {
            val obj = runCatching { JSONObject(bodyText) }.getOrNull()
            if (obj != null) {
                command = obj.optString("command", "")
                if (obj.has("cwd") && !obj.isNull("cwd")) cwd = obj.optString("cwd")
                if (obj.has("timeoutMs")) timeoutMs = obj.optLong("timeoutMs", timeoutMs)
            } else {
                command = bodyText
            }
        } else {
            command = bodyText
        }

        if (command.isBlank()) {
            sendJson(clientSocket, 400, JSONObject().put("ok", false).put("error", "Empty command"))
            return
        }

        val autoUp = parseBoolQuery(req.query["auto_up"], true)
        if (!TermuxAgentClient.isAlive() && autoUp) {
            val outcome = runBlocking { AgentPrivilegeOrchestrator.ensureStarted(appContext) }
            if (!outcome.ok) {
                val accept = req.headers["accept"].orEmpty()
                if (accept.contains("application/x-termux-exec")) {
                    sendFramedExec(
                        clientSocket,
                        AgentExecResult(1, "", outcome.message + "\n", 0L, -1)
                    )
                } else {
                    sendJson(clientSocket, 503, outcome.toJson())
                }
                return
            }
        }

        val result = TermuxAgentClient.execSync(command, cwd, timeoutMs)
        val accept = req.headers["accept"].orEmpty()
        if (accept.contains("application/x-termux-exec")) {
            sendFramedExec(clientSocket, result)
        } else {
            val json = JSONObject()
                .put("ok", result.exitCode == 0)
                .put("exitCode", result.exitCode)
                .put("uid", result.serverUid)
                .put("durationMs", result.durationMs)
                .put("stdout", result.stdout)
                .put("stderr", result.stderr)
            sendJson(clientSocket, 200, json)
        }
    }

    private fun sendFramedExec(clientSocket: LocalClientSocket, res: AgentExecResult) {
        val outBytes = res.stdout.toByteArray(StandardCharsets.UTF_8)
        val errBytes = res.stderr.toByteArray(StandardCharsets.UTF_8)
        val header = "${res.exitCode}\n${outBytes.size}\n${errBytes.size}\n".toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArray(header.size + outBytes.size + errBytes.size)
        header.copyInto(payload, 0)
        outBytes.copyInto(payload, header.size)
        errBytes.copyInto(payload, header.size + outBytes.size)
        sendHttpResponse(clientSocket, 200, "application/x-termux-exec", payload)
    }

    private fun sendJson(clientSocket: LocalClientSocket, status: Int, json: JSONObject) {
        sendHttpResponse(clientSocket, status, "application/json; charset=utf-8", json.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun sendHttpResponse(
        clientSocket: LocalClientSocket,
        status: Int,
        contentType: String,
        body: ByteArray
    ) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            408 -> "Request Timeout"
            503 -> "Service Unavailable"
            else -> "Internal Server Error"
        }
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)

        sendAll(clientSocket, headers)
        if (body.isNotEmpty()) {
            sendAll(clientSocket, body)
        }
    }

    private fun sendAll(clientSocket: LocalClientSocket, data: ByteArray) {
        val chunkSize = 32768
        var offset = 0
        while (offset < data.size) {
            val end = (offset + chunkSize).coerceAtMost(data.size)
            val slice = if (offset == 0 && end == data.size) data else data.copyOfRange(offset, end)
            val err: Error? = clientSocket.send(slice)
            if (err != null) {
                throw IllegalStateException(err.minimalErrorString)
            }
            offset = end
        }
    }

    private fun readHttpRequest(clientSocket: LocalClientSocket): HttpRequest? {
        val buf = ByteArrayOutputStream()
        var headerEnd = -1

        while (headerEnd < 0 && buf.size() < 65536) {
            if (!readAvailableOrOneByte(clientSocket, buf, 65536 - buf.size())) break
            headerEnd = findHeaderEnd(buf.toByteArray())
        }
        if (headerEnd < 0) return null

        val allBytes = buf.toByteArray()
        val headerText = String(allBytes, 0, headerEnd, StandardCharsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        if (lines.isEmpty()) return null

        val reqParts = lines[0].split(' ')
        if (reqParts.size < 2) return null
        val method = reqParts[0].uppercase()
        val rawTarget = reqParts[1]
        val qIdx = rawTarget.indexOf('?')
        val path = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget
        val query = if (qIdx >= 0) parseQuery(rawTarget.substring(qIdx + 1)) else emptyMap()

        val headers = LinkedHashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }

        val bodyStart = headerEnd + 4
        val bodyBuf = ByteArrayOutputStream()
        if (bodyStart < allBytes.size) {
            bodyBuf.write(allBytes, bodyStart, allBytes.size - bodyStart)
        }

        if (headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
            sendAll(clientSocket, "HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        }

        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtMost(4 * 1024 * 1024) ?: 0
        while (bodyBuf.size() < contentLength) {
            val remaining = contentLength - bodyBuf.size()
            if (!readAvailableOrOneByte(clientSocket, bodyBuf, remaining)) break
        }

        return HttpRequest(method, path, query, headers, bodyBuf.toByteArray())
    }

    /**
     * `LocalSocketManager.read` loops until the entire `chunk.size` buffer is filled or EOF.
     * Reading `available()` bytes (or 1 byte when empty) avoids blocking on keep-open HTTP sockets.
     */
    private fun readAvailableOrOneByte(
        clientSocket: LocalClientSocket,
        dest: ByteArrayOutputStream,
        maxBytes: Int
    ): Boolean {
        if (maxBytes <= 0) return false
        val availRes = LocalSocketManager.available(TAG, clientSocket.fd)
        val avail = if (availRes != null && availRes.retval == 0) availRes.intData else 0
        val toRead = if (avail > 0) avail.coerceAtMost(maxBytes).coerceAtMost(32768) else 1
        val chunk = ByteArray(toRead)
        val readOut = LocalClientSocket.MutableInt(0)
        val err = clientSocket.read(chunk, readOut)
        if (err != null || readOut.value <= 0) return false
        dest.write(chunk, 0, readOut.value)
        return true
    }

    private fun findHeaderEnd(data: ByteArray): Int {
        for (i in 0..data.size - 4) {
            if (data[i] == '\r'.code.toByte() &&
                data[i + 1] == '\n'.code.toByte() &&
                data[i + 2] == '\r'.code.toByte() &&
                data[i + 3] == '\n'.code.toByte()
            ) {
                return i
            }
        }
        return -1
    }

    private fun parseQuery(raw: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = decodeUrl(if (eq >= 0) pair.substring(0, eq) else pair)
            val v = if (eq >= 0) decodeUrl(pair.substring(eq + 1)) else ""
            map[k] = v
        }
        return map
    }

    private fun decodeUrl(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun parseBoolQuery(value: String?, default: Boolean): Boolean = when (value?.lowercase()) {
        "1", "true", "yes" -> true
        "0", "false", "no" -> false
        else -> default
    }

    private fun StartOutcome.toJson(): JSONObject = JSONObject()
        .put("ok", ok)
        .put("method", method)
        .put("uid", uid)
        .put("pid", pid)
        .put("port", port)
        .put("message", message)
        .put("needsPairing", needsPairing)
        .put("needsWifiOrRoot", needsWifiOrRoot)

    companion object {
        @Volatile
        private var instance: TermuxAgentSocketServer? = null

        @Synchronized
        fun start(context: Context, socketPath: String): Boolean {
            instance?.stop()
            val server = TermuxAgentSocketServer(context, socketPath)
            val ok = server.start()
            instance = if (ok) server else null
            return ok
        }

        @Synchronized
        fun stop() {
            instance?.stop()
            instance = null
        }

        @Synchronized
        fun isRunning(): Boolean = instance?.isRunning() == true
    }
}
