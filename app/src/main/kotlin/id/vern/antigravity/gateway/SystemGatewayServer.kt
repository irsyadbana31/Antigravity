package id.vern.antigravity.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.system.Os
import android.util.Log
import android.widget.Toast
import id.vern.antigravity.install.LseCompatHelper
import id.vern.antigravity.service.WakeLockHolder
import id.vern.antigravity.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SystemGatewayServer(
    private val context: Context,
    private val port: Int = 45161,
    private val wakeLockHolder: WakeLockHolder? = null
) {
    internal enum class ExecMode { SHELL, RISH, SHIZUKU }

    companion object {
        private const val TAG = "SystemGateway"
        private const val SOCKET_TIMEOUT_MS = 10_000
        private const val MAX_HEADER_LINE_BYTES = 8192
        private const val MAX_HEADERS = 64
        private const val MAX_BODY_BYTES = 4 * 1024 * 1024 // 4 MiB
        private const val MAX_EXEC_OUTPUT_BYTES = 512 * 1024 // 512 KiB per stream
        private const val MAX_COMMAND_HISTORY = 30
        private const val TOKEN_BYTES = 32
        private val HEX_TOKEN_REGEX = Regex("^[0-9a-f]{64}$")
        private val ALLOWED_HOSTS = setOf("127.0.0.1", "localhost", "[::1]")

        private val tokenLock = Any()
        @Volatile
        private var cachedToken: String? = null
        @Volatile
        private var cachedTokenDir: String? = null

        const val CHANNEL_TASKS = "agy_tasks"
        const val CHANNEL_ALERTS = "agy_alerts"

        fun readActivePort(filesDir: File, defaultPort: Int = 45161): Int {
            return try {
                val portFile = File(filesDir, "logs/gateway.port")
                val parsed = if (portFile.isFile) portFile.readText(Charsets.UTF_8).trim().toIntOrNull() else null
                if (parsed != null && parsed in 1024..65535) parsed else defaultPort
            } catch (_: Exception) {
                defaultPort
            }
        }

        fun ensureGatewayToken(filesDir: File): String {
            val dirKey = filesDir.absolutePath
            synchronized(tokenLock) {
                val tokenFile = File(filesDir, "logs/gateway.token")
                val inMem = cachedToken
                if (inMem != null && cachedTokenDir == dirKey && tokenFile.isFile) {
                    return inMem
                }

                if (tokenFile.isFile) {
                    try {
                        val existing = tokenFile.readText(Charsets.UTF_8).trim().lowercase()
                        if (HEX_TOKEN_REGEX.matches(existing)) {
                            enforceOwnerOnlyPermissions(tokenFile)
                            cachedToken = existing
                            cachedTokenDir = dirKey
                            return existing
                        }
                    } catch (_: Exception) {}
                }

                val bytes = ByteArray(TOKEN_BYTES)
                SecureRandom().nextBytes(bytes)
                val generated = bytes.joinToString("") { "%02x".format(it) }

                try {
                    tokenFile.parentFile?.mkdirs()
                    val tmpFile = File(tokenFile.parentFile, "gateway.token.tmp")
                    tmpFile.writeText("$generated\n", Charsets.UTF_8)
                    enforceOwnerOnlyPermissions(tmpFile)
                    if (!tmpFile.renameTo(tokenFile)) {
                        tokenFile.writeText("$generated\n", Charsets.UTF_8)
                        tmpFile.delete()
                    }
                    enforceOwnerOnlyPermissions(tokenFile)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed persisting gateway token: ${e.message}")
                }

                cachedToken = generated
                cachedTokenDir = dirKey
                return generated
            }
        }

        private fun enforceOwnerOnlyPermissions(file: File) {
            try {
                file.setReadable(false, false)
                file.setReadable(true, true)
                file.setWritable(false, false)
                file.setWritable(true, true)
                Os.chmod(file.absolutePath, 384) // 0600
            } catch (_: Throwable) {}
        }

        internal fun validateHostAndOriginHeaders(headers: Map<String, String>): String? {
            if (headers.containsKey("origin") || headers.keys.any { it.startsWith("sec-fetch-") }) {
                return "browser origin requests are forbidden"
            }
            val rawHost = headers["host"]?.trim().orEmpty()
            if (rawHost.isNotEmpty()) {
                val hostOnly = if (rawHost.startsWith("[")) {
                    val endBracket = rawHost.indexOf(']')
                    if (endBracket > 0) rawHost.substring(0, endBracket + 1) else rawHost
                } else {
                    rawHost.substringBefore(':')
                }.lowercase()
                if (hostOnly !in ALLOWED_HOSTS) {
                    return "invalid Host header"
                }
            }
            return null
        }

        internal fun isAuthorizedRequest(headers: Map<String, String>, expectedToken: String): Boolean {
            if (expectedToken.isBlank()) return false
            val xToken = headers["x-agy-token"]?.trim()?.takeIf { it.isNotEmpty() }
            val bearer = headers["authorization"]?.trim()?.let { auth ->
                if (auth.startsWith("Bearer ", ignoreCase = true)) auth.substring(7).trim() else null
            }?.takeIf { it.isNotEmpty() }
            val candidate = xToken ?: bearer ?: return false
            return MessageDigest.isEqual(
                candidate.toByteArray(Charsets.UTF_8),
                expectedToken.toByteArray(Charsets.UTF_8)
            )
        }

        internal fun normalizeCommandEndpoint(rawPath: String): String {
            val cleanPath = rawPath.substringBefore("?").trimEnd('/').ifEmpty { "/" }
            return cleanPath
                .removePrefix("/gateway/")
                .removePrefix("/")
        }

        internal fun resolveExecCommand(json: JSONObject): String =
            json.optString("cmd", "").ifBlank { json.optString("command", "") }.trim()

        internal fun resolveExecMode(json: JSONObject): ExecMode? {
            val rawMode = json.optString("mode", "").trim()
            if (rawMode.isNotEmpty()) {
                return when (rawMode.lowercase()) {
                    "shell" -> ExecMode.SHELL
                    "rish" -> ExecMode.RISH
                    "shizuku" -> ExecMode.SHIZUKU
                    else -> null
                }
            }
            return when {
                json.optBoolean("shizuku", false) -> ExecMode.SHIZUKU
                json.optBoolean("rish", false) -> ExecMode.RISH
                else -> ExecMode.SHELL
            }
        }

        internal fun buildExecEnvironment(
            filesPath: String,
            homePath: String,
            tmpPath: String,
            packageName: String,
            useRish: Boolean,
            gatewayPort: Int = 45161,
            gatewayToken: String? = null,
            shizukuEnabled: Boolean = false,
            shizukuBackend: String = ShizukuBackend.AUTO.id
        ): Map<String, String> {
            val map = linkedMapOf(
                "HOME" to homePath,
                "PREFIX" to "$filesPath/usr",
                "TMPDIR" to tmpPath,
                "TERM" to "xterm-256color",
                "PATH" to "$filesPath/usr/bin:$filesPath/engine/bin:/system/bin",
                "AGY_APPLICATION_ID" to packageName,
                "RISH_APPLICATION_ID" to packageName,
                "AGY_GATEWAY_PORT" to gatewayPort.toString(),
                "AGY_SHIZUKU_ENABLED" to if (shizukuEnabled) "1" else "0",
                "AGY_SHIZUKU_BACKEND" to shizukuBackend
            )
            if (!gatewayToken.isNullOrBlank()) {
                map["AGY_GATEWAY_TOKEN"] = gatewayToken
            }
            if (!useRish) {
                map["LD_LIBRARY_PATH"] = "$filesPath/usr/lib:/system/lib64"
            }
            return map
        }

        private val VALID_PACKAGE_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

        internal fun validatePackageName(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.length > 160) return null
            return if (VALID_PACKAGE_REGEX.matches(trimmed)) trimmed else null
        }

        internal fun parseShizukuDiagOutput(pkg: String, raw: String): JSONObject {
            val sections = LinkedHashMap<String, String>()
            var currentHeader: String? = null
            val curBuf = StringBuilder()
            for (line in raw.lineSequence()) {
                val s = line.trim()
                if (s.startsWith("===") && s.endsWith("===") && s.length > 6) {
                    currentHeader?.let { sections[it] = curBuf.toString() }
                    currentHeader = s.substring(3, s.length - 3)
                    curBuf.setLength(0)
                } else {
                    if (curBuf.isNotEmpty()) curBuf.append('\n')
                    curBuf.append(line)
                }
            }
            currentHeader?.let { sections[it] = curBuf.toString() }

            fun firstInt(regex: String, text: String): Int? =
                Regex(regex).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

            fun firstDouble(regex: String, text: String): Double? =
                Regex(regex).find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()

            val pkgSec = sections["PKG"].orEmpty()
            val versionCode = firstInt("""versionCode=(\d+)""", pkgSec)
            val versionName = Regex("""versionName=(\S+)""").find(pkgSec)?.groupValues?.getOrNull(1)
            val lastUpdate = Regex("""lastUpdateTime=(.+)""").find(pkgSec)?.groupValues?.getOrNull(1)?.trim()

            val pid = sections["PID"].orEmpty().trim().split(Regex("""\s+""")).firstOrNull()?.toIntOrNull()

            val battSec = sections["BATT"].orEmpty()
            val tempDeci = firstInt("""(?m)^\s*temperature:\s*(-?\d+)""", battSec)
            val batteryJson = JSONObject()
                .put("level", firstInt("""(?m)^\s*level:\s*(\d+)""", battSec) ?: JSONObject.NULL)
                .put("voltage_mv", firstInt("""(?m)^\s*voltage:\s*(\d+)""", battSec) ?: JSONObject.NULL)
                .put("temperature_c", tempDeci?.let { kotlin.math.round(it.toDouble()) / 10.0 } ?: JSONObject.NULL)
                .put("status", firstInt("""(?m)^\s*status:\s*(\d+)""", battSec) ?: JSONObject.NULL)

            val totalProcs = sections["PROCS"].orEmpty().trim().split(Regex("""\s+""")).firstOrNull()?.toIntOrNull()

            val memSec = sections["MEM"].orEmpty()
            val memJson = JSONObject()
                .put("total_pss_kb", firstInt("""TOTAL PSS:\s*(\d+)""", memSec) ?: JSONObject.NULL)
                .put("total_rss_kb", firstInt("""TOTAL RSS:\s*(\d+)""", memSec) ?: JSONObject.NULL)

            val gfxSec = sections["GFX"].orEmpty()
            val jankMatch = Regex("""Janky frames:\s*(\d+)\s*\(([\d.]+)%\)""").find(gfxSec)
            val gfxJson = JSONObject()
                .put("total_frames", firstInt("""Total frames rendered:\s*(\d+)""", gfxSec) ?: JSONObject.NULL)
                .put("janky_frames", jankMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: JSONObject.NULL)
                .put("janky_percent", jankMatch?.groupValues?.getOrNull(2)?.toDoubleOrNull() ?: JSONObject.NULL)
                .put("p50_ms", firstInt("""50th percentile:\s*(\d+)ms""", gfxSec) ?: JSONObject.NULL)
                .put("p90_ms", firstInt("""90th percentile:\s*(\d+)ms""", gfxSec) ?: JSONObject.NULL)
                .put("p95_ms", firstInt("""95th percentile:\s*(\d+)ms""", gfxSec) ?: JSONObject.NULL)
                .put("p99_ms", firstInt("""99th percentile:\s*(\d+)ms""", gfxSec) ?: JSONObject.NULL)

            val anrArray = JSONArray()
            for (line in sections["ANR"].orEmpty().lineSequence()) {
                val trimmedLine = line.trim()
                if (trimmedLine.isNotEmpty()) {
                    anrArray.put(trimmedLine)
                }
            }

            return JSONObject()
                .put("package", pkg)
                .put("version_name", versionName ?: JSONObject.NULL)
                .put("version_code", versionCode ?: JSONObject.NULL)
                .put("last_update_time", lastUpdate ?: JSONObject.NULL)
                .put("pid", pid ?: JSONObject.NULL)
                .put("battery", batteryJson)
                .put("total_processes", totalProcs ?: JSONObject.NULL)
                .put("meminfo", memJson)
                .put("gfxinfo", gfxJson)
                .put("recent_anrs", anrArray)
        }
    }

    private data class CommandRecord(
        val id: String,
        val timestamp: Long,
        val cmd: String,
        val rish: Boolean,
        val exitCode: Int,
        val durationMs: Long,
        val stdout: String,
        val stderr: String,
        val status: String,
        val source: String
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("timestamp", timestamp)
            .put("cmd", cmd)
            .put("rish", rish)
            .put("exit_code", exitCode)
            .put("duration_ms", durationMs)
            .put("stdout", stdout)
            .put("stderr", stderr)
            .put("status", status)
            .put("source", source)
    }

    private var acceptorExecutor: ExecutorService? = null
    private var workerExecutor: ExecutorService? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var running = false
    @Volatile
    private var boundPort: Int = port

    val activePort: Int
        get() = boundPort

    private val historyLock = Any()
    private val commandHistory = ArrayDeque<CommandRecord>(MAX_COMMAND_HISTORY)

    init {
        initNotificationChannels()
    }

    private fun initNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                val tasks = NotificationChannel(
                    CHANNEL_TASKS,
                    "Antigravity Tasks",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notifications for ongoing AI agent and daemon tasks"
                }
                val alerts = NotificationChannel(
                    CHANNEL_ALERTS,
                    "Antigravity Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "High-priority alerts and task completions"
                }
                nm.createNotificationChannel(tasks)
                nm.createNotificationChannel(alerts)
            } catch (e: Exception) {
                Log.w(TAG, "Failed creating notification channels: ${e.message}")
            }
        }
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        ensureGatewayToken(context.filesDir)
        val acceptor = Executors.newSingleThreadExecutor()
        val workers = Executors.newFixedThreadPool(6)
        acceptorExecutor = acceptor
        workerExecutor = workers

        acceptor.execute {
            try {
                var boundSocket: ServerSocket? = null
                var chosenPort = port
                for (candidate in port..(port + 10)) {
                    try {
                        val s = ServerSocket()
                        s.reuseAddress = true
                        s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), candidate), 16)
                        boundSocket = s
                        chosenPort = candidate
                        break
                    } catch (_: Exception) {
                        // Try next port if already bound (e.g. side-by-side install)
                    }
                }
                val socket = boundSocket ?: run {
                    Log.w(TAG, "Could not bind gateway on ports $port..${port + 10}")
                    return@execute
                }
                serverSocket = socket
                boundPort = chosenPort
                writeGatewayPortFile(chosenPort)
                Log.i(TAG, "Gateway server listening on 127.0.0.1:$chosenPort")

                while (running && !socket.isClosed) {
                    try {
                        val client = socket.accept()
                        workers.execute { handleClient(client) }
                    } catch (_: Exception) {
                        if (!running) break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not bind gateway on $port: ${e.message}")
            }
        }
    }

    private fun writeGatewayPortFile(activePort: Int) {
        try {
            val portFile = File(context.filesDir, "logs/gateway.port")
            portFile.parentFile?.mkdirs()
            val tmpFile = File(portFile.parentFile, "gateway.port.tmp")
            tmpFile.writeText("$activePort\n", Charsets.UTF_8)
            if (!tmpFile.renameTo(portFile)) {
                portFile.writeText("$activePort\n", Charsets.UTF_8)
                tmpFile.delete()
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        wakeLockHolder?.releaseAll()
        acceptorExecutor?.shutdownNow()
        workerExecutor?.shutdownNow()
        acceptorExecutor = null
        workerExecutor = null
    }

    private class HttpRejectException(val statusCode: Int, val reason: String, message: String) : IOException(message)

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = SOCKET_TIMEOUT_MS
                val input = client.getInputStream()
                val firstLine = readHeaderLine(input) ?: return
                val parts = firstLine.split(" ")
                if (parts.size < 2) {
                    writeJsonResponse(client, 400, "Bad Request", JSONObject().put("ok", false).put("error", "malformed request line"))
                    return
                }
                val method = parts[0].uppercase()
                val rawPath = parts[1]

                val headers = HashMap<String, String>()
                var contentLength = 0
                var headerCount = 0
                while (true) {
                    val headerLine = readHeaderLine(input) ?: break
                    if (headerLine.isEmpty()) break
                    headerCount++
                    if (headerCount > MAX_HEADERS) {
                        throw HttpRejectException(431, "Request Header Fields Too Large", "Too many HTTP headers")
                    }
                    val colonIdx = headerLine.indexOf(':')
                    if (colonIdx > 0) {
                        headers[headerLine.substring(0, colonIdx).trim().lowercase()] =
                            headerLine.substring(colonIdx + 1).trim()
                    }
                    if (headerLine.startsWith("Content-Length:", ignoreCase = true)) {
                        val parsed = headerLine.substringAfter(":").trim().toIntOrNull()
                            ?: throw HttpRejectException(400, "Bad Request", "Invalid Content-Length header")
                        if (parsed < 0) {
                            throw HttpRejectException(400, "Bad Request", "Negative Content-Length")
                        }
                        if (parsed > MAX_BODY_BYTES) {
                            throw HttpRejectException(413, "Payload Too Large", "Content-Length exceeds $MAX_BODY_BYTES bytes")
                        }
                        contentLength = parsed
                    }
                }

                validateHostAndOriginHeaders(headers)?.let { reason ->
                    throw HttpRejectException(403, "Forbidden", reason)
                }
                val expectedToken = ensureGatewayToken(context.filesDir)
                if (!isAuthorizedRequest(headers, expectedToken)) {
                    throw HttpRejectException(
                        401,
                        "Unauthorized",
                        "unauthorized: provide X-AGY-Token or Authorization: Bearer <token>"
                    )
                }

                val body = if (contentLength > 0) {
                    val payloadBytes = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(payloadBytes, read, contentLength - read)
                        if (n == -1) break
                        read += n
                    }
                    if (read < contentLength) {
                        throw HttpRejectException(400, "Bad Request", "Unexpected EOF reading request body")
                    }
                    String(payloadBytes, Charsets.UTF_8)
                } else ""

                val responseJson = routeRequest(method, rawPath, body)
                writeJsonResponse(client, 200, "OK", responseJson)
            } catch (reject: HttpRejectException) {
                try {
                    writeJsonResponse(
                        client,
                        reject.statusCode,
                        reject.reason,
                        JSONObject().put("ok", false).put("error", reject.message ?: reject.reason)
                    )
                } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.w(TAG, "Gateway client handle error: ${e.message}")
            }
        }
    }

    private fun writeJsonResponse(client: Socket, statusCode: Int, reason: String, json: JSONObject) {
        val responseBytes = json.toString().toByteArray(Charsets.UTF_8)
        val out = client.getOutputStream()
        val header = "HTTP/1.1 $statusCode $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${responseBytes.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(responseBytes)
        out.flush()
    }

    private fun readHeaderLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream()
        var b: Int
        while (input.read().also { b = it } != -1) {
            if (b == '\r'.code) continue
            if (b == '\n'.code) break
            if (baos.size() >= MAX_HEADER_LINE_BYTES) {
                throw HttpRejectException(
                    431,
                    "Request Header Fields Too Large",
                    "HTTP header line exceeded $MAX_HEADER_LINE_BYTES bytes"
                )
            }
            baos.write(b)
        }
        if (b == -1 && baos.size() == 0) return null
        return baos.toString("UTF-8")
    }

    private fun parseQueryParams(rawPath: String): Map<String, String> {
        val query = rawPath.substringAfter("?", "")
        if (query.isEmpty()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in query.split("&")) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                map[pair.substring(0, idx)] = pair.substring(idx + 1)
            }
        }
        return map
    }

    private fun routeRequest(method: String, rawPath: String, body: String): JSONObject {
        val cleanPath = rawPath.substringBefore("?").trimEnd('/').ifEmpty { "/" }
        val query = parseQueryParams(rawPath)

        // Diagnostic GET/POST endpoints (/gateway/*)
        when (cleanPath) {
            "/gateway/status" -> return handleStatus()

            "/gateway/shizuku" -> return ShizukuManager.buildStatusJson(context)

            "/gateway/shizuku/diag" -> return handleShizukuDiag(query, parseJsonBody(body))

            "/gateway/logs" -> {
                val tail = query["tail"]?.toIntOrNull() ?: 50
                return JSONObject()
                    .put("ok", true)
                    .put("logs", AppLogger.getRecentJson(tail))
            }

            "/gateway/crash" -> return handleCrashQuery()

            "/gateway/commands" -> {
                val limit = query["limit"]?.toIntOrNull() ?: 20
                return JSONObject()
                    .put("ok", true)
                    .put("commands", getRecentCommandsJson(limit))
            }

            "/gateway" -> {
                if (method == "POST") {
                    val req = parseJsonBody(body)
                    val cmd = req.optString("command", "").trim()
                    val payload = req.optJSONObject("payload") ?: JSONObject()
                    return dispatchCommand(cmd, payload)
                }
                return JSONObject()
                    .put("ok", true)
                    .put("status", "online")
                    .put("port", boundPort)
            }
        }

        // Direct endpoint compatibility (e.g. /toast, /exec, /notify, /vibrate, /wakelock, /system_info)
        // and dual-route /gateway/<command> support (e.g. /gateway/exec, /gateway/notify, /gateway/toast)
        val endpoint = normalizeCommandEndpoint(cleanPath)
        val json = parseJsonBody(body)
        if (endpoint.isEmpty()) {
            return JSONObject()
                .put("ok", true)
                .put("status", "running")
                .put("port", boundPort)
                .put("gateway", "Antigravity Jetpack Gateway")
        }
        return dispatchCommand(endpoint, json)
    }

    private fun parseJsonBody(body: String): JSONObject {
        return try {
            if (body.isNotBlank()) JSONObject(body) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun dispatchCommand(command: String, json: JSONObject): JSONObject {
        return when (command) {
            "toast" -> handleToast(json)
            "open_url" -> handleOpenUrl(json)
            "clipboard", "clipboard_set" -> handleClipboard(json)
            "wakelock" -> handleWakeLock(json)
            "system_info" -> handleSystemInfo()
            "notify" -> handleNotify(json)
            "vibrate" -> handleVibrate(json)
            "exec" -> handleExec(json)
            "status" -> handleStatus()
            "shizuku" -> ShizukuManager.buildStatusJson(context)
            "shizuku_diag" -> handleShizukuDiag(emptyMap(), json)
            "logs" -> JSONObject()
                .put("ok", true)
                .put("logs", AppLogger.getRecentJson(json.optInt("tail", 50)))
            "crash" -> handleCrashQuery()
            "commands" -> JSONObject()
                .put("ok", true)
                .put("commands", getRecentCommandsJson(json.optInt("limit", 20)))
            "command_get" -> {
                val id = json.optString("id", "")
                val rec = synchronized(historyLock) { commandHistory.firstOrNull { it.id == id } }
                JSONObject()
                    .put("ok", rec != null)
                    .put("command", rec?.toJson() ?: JSONObject.NULL)
            }
            else -> JSONObject()
                .put("ok", true)
                .put("status", "running")
                .put("gateway", "Antigravity Jetpack Gateway")
                .put("unknown_command", command)
        }
    }

    private fun handleStatus(): JSONObject {
        val snapshot = ShizukuManager.inspectStatus(context)
        return JSONObject()
            .put("ok", true)
            .put("status", "online")
            .put("port", boundPort)
            .put("cpu_has_lse", LseCompatHelper.cpuHasHardwareLse())
            .put("shizuku_enabled", snapshot.enabled)
            .put("shizuku_backend", snapshot.backend.id)
            .put("shizuku_state", snapshot.state.name.lowercase())
            .put("shizuku_ready", snapshot.isReady)
            .put("shizuku_uid", snapshot.uid)
            .put("rish_available", snapshot.enabled && (snapshot.isReady || snapshot.hasRishDex))
    }

    private fun handleToast(json: JSONObject): JSONObject {
        val msg = json.optString("message", json.optString("text", "Antigravity"))
        val isLong = "long".equals(json.optString("duration", "short"), ignoreCase = true)
        mainHandler.post {
            Toast.makeText(context, msg, if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
        return JSONObject().put("ok", true).put("toast", "shown")
    }

    private fun handleOpenUrl(json: JSONObject): JSONObject {
        val url = json.optString("url")
        val result = JSONObject().put("ok", true)
        if (url.isNotBlank()) {
            mainHandler.post {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
            result.put("opened", url)
        }
        return result
    }

    private fun handleClipboard(json: JSONObject): JSONObject {
        val result = JSONObject().put("ok", true)
        if (json.has("text")) {
            val text = json.getString("text")
            mainHandler.post {
                try {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("agy", text))
                } catch (_: Exception) {}
            }
            result.put("set", true)
        } else {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val text = cm?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            result.put("clipboard", text)
        }
        return result
    }

    private fun handleWakeLock(json: JSONObject): JSONObject {
        val action = json.optString("action", "status").lowercase()
        val tag = json.optString("tag", "").trim()
        val timeoutMs = json.optLong("timeout_ms", 10 * 60 * 1000L)
        val result = JSONObject().put("ok", true)

        wakeLockHolder?.cleanupExpired()

        if (tag.isNotEmpty()) {
            val held = when (action) {
                "acquire" -> wakeLockHolder?.acquireTagged(context, tag, timeoutMs) == true
                "release" -> wakeLockHolder?.releaseTagged(tag) == true
                else -> wakeLockHolder?.isTaggedHeld(tag) == true
            }
            result.put("held", held).put("tag", tag)
        } else {
            when (action) {
                "acquire" -> wakeLockHolder?.acquire(context, timeoutMs.coerceIn(1_000L, 60 * 60 * 1000L))
                "release" -> wakeLockHolder?.release()
            }
            result.put("held", wakeLockHolder?.isHeld == true)
        }
        return result
    }

    private fun handleNotify(json: JSONObject): JSONObject {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return JSONObject().put("ok", false).put("error", "NotificationManager unavailable")

        val action = json.optString("action", "post")
        val tag = json.optString("tag", "agy_task")
        val id = json.optInt("id", (System.currentTimeMillis() % 100_000L).toInt())

        if ("cancel".equals(action, ignoreCase = true)) {
            nm.cancel(tag, id)
            return JSONObject().put("ok", true).put("cancelled", true).put("tag", tag).put("id", id)
        }

        val title = json.optString("title", "Antigravity")
        val message = json.optString("message", json.optString("text", ""))
        val progress = json.optInt("progress", -1)
        val ongoing = json.optBoolean("ongoing", false)
        val channel = json.optString("channel", CHANNEL_TASKS).let {
            if (it == CHANNEL_ALERTS) CHANNEL_ALERTS else CHANNEL_TASKS
        }

        val icon = context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channel)
        } else {
            Notification.Builder(context)
        }

        builder
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(icon)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)

        if (message.length > 48) {
            builder.style = Notification.BigTextStyle().bigText(message)
        }
        if (progress >= 0) {
            builder.setProgress(100, progress.coerceIn(0, 100), false)
        } else if ("indeterminate".equals(json.optString("progress_mode"), ignoreCase = true)) {
            builder.setProgress(0, 0, true)
        }

        return try {
            nm.notify(tag, id, builder.build())
            JSONObject().put("ok", true).put("tag", tag).put("id", id)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "notify failed")
        }
    }

    private fun handleVibrate(json: JSONObject): JSONObject {
        val durationMs = json.optLong("duration_ms", 100L).coerceIn(10L, 5_000L)
        return try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator == null || !vibrator.hasVibrator()) {
                return JSONObject().put("ok", false).put("error", "vibrator not available")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
            JSONObject().put("ok", true).put("duration_ms", durationMs)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "vibrate failed")
        }
    }

    private fun handleExec(json: JSONObject): JSONObject {
        val cmd = resolveExecCommand(json)
        if (cmd.isEmpty()) {
            return JSONObject().put("ok", false).put("error", "empty command")
        }
        val execMode = resolveExecMode(json)
            ?: return JSONObject()
                .put("ok", false)
                .put("error", "invalid mode: '${json.optString("mode")}' (supported: shell, rish, shizuku)")
        val timeoutMs = json.optLong("timeout_ms", 30_000L).coerceIn(1_000L, 120_000L)
        val source = json.optString("source", "agent")
        val homeDir = File(context.filesDir, "home").apply { mkdirs() }
        val cwdPath = json.optString("cwd", homeDir.absolutePath)
        val shizukuSettings = ShizukuConfig.load(context)

        if (execMode == ExecMode.RISH || execMode == ExecMode.SHIZUKU) {
            if (!shizukuSettings.enabled) {
                return JSONObject()
                    .put("ok", false)
                    .put("error", "Shizuku is disabled in Settings -> Customizations")
                    .put("shizuku_enabled", false)
                    .put("shizuku_ready", false)
                    .put("rish_available", false)
            }

            val privResult = ShizukuManager.executePrivileged(
                context = context,
                cmd = cmd,
                timeoutMs = timeoutMs,
                requestedViaRish = (execMode == ExecMode.RISH),
                cwdPath = cwdPath,
                gatewayPort = boundPort,
                gatewayToken = ensureGatewayToken(context.filesDir)
            )
            if (privResult.status == "UNAVAILABLE") {
                if (shizukuSettings.fallbackToShell) {
                    val shellRes = executeShellCommand(
                        cmd = cmd,
                        timeoutMs = timeoutMs,
                        source = source,
                        cwdPath = cwdPath,
                        shizukuSettings = shizukuSettings
                    )
                    return shellRes
                        .put("fallback_used", true)
                        .put("shizuku_fallback_reason", privResult.error ?: "Shizuku unavailable")
                        .put("transport", "shell_fallback")
                }
                return JSONObject()
                    .put("ok", false)
                    .put("error", privResult.error ?: "Shizuku service unavailable")
                    .put("shizuku_enabled", true)
                    .put("shizuku_ready", false)
                    .put("rish_available", false)
            }

            val record = CommandRecord(
                id = "cmd_${System.currentTimeMillis()}_${UUID.randomUUID().toString().substring(0, 4)}",
                timestamp = System.currentTimeMillis() - privResult.durationMs,
                cmd = cmd,
                rish = true,
                exitCode = privResult.exitCode,
                durationMs = privResult.durationMs,
                stdout = privResult.stdout,
                stderr = privResult.stderr,
                status = privResult.status,
                source = source
            )
            recordCommand(record)
            return record.toJson()
                .put("ok", privResult.ok)
                .put("transport", privResult.transport)
                .put("uid", privResult.uid)
        }

        return executeShellCommand(
            cmd = cmd,
            timeoutMs = timeoutMs,
            source = source,
            cwdPath = cwdPath,
            shizukuSettings = shizukuSettings
        ).put("transport", "shell")
    }

    private fun executeShellCommand(
        cmd: String,
        timeoutMs: Long,
        source: String,
        cwdPath: String,
        shizukuSettings: ShizukuSettings
    ): JSONObject {
        val homeDir = File(context.filesDir, "home").apply { mkdirs() }
        val cmdId = "cmd_${System.currentTimeMillis()}_${UUID.randomUUID().toString().substring(0, 4)}"
        val startTime = System.currentTimeMillis()

        var exitCode = -1
        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()
        var status = "ERROR"

        try {
            val prefixBash = File(context.filesDir, "usr/bin/bash")
            val shell = if (prefixBash.canExecute()) prefixBash.absolutePath else "/system/bin/sh"
            val pb = ProcessBuilder(shell, "-c", cmd)

            val cwd = File(cwdPath)
            if (cwd.isDirectory) {
                pb.directory(cwd)
            }

            val filesPath = context.filesDir.absolutePath
            val tmpDir = File(context.filesDir, "tmp").apply { mkdirs() }
            val env = pb.environment()
            env.remove("LD_PRELOAD")
            env.putAll(
                buildExecEnvironment(
                    filesPath = filesPath,
                    homePath = homeDir.absolutePath,
                    tmpPath = tmpDir.absolutePath,
                    packageName = context.packageName,
                    useRish = false,
                    gatewayPort = boundPort,
                    gatewayToken = ensureGatewayToken(context.filesDir),
                    shizukuEnabled = shizukuSettings.enabled,
                    shizukuBackend = shizukuSettings.backend.id
                )
            )

            val proc = pb.start()
            val outThread = Thread({ drainBoundedStream(proc.inputStream, stdoutBuf) }, "GatewayExecOut")
            val errThread = Thread({ drainBoundedStream(proc.errorStream, stderrBuf) }, "GatewayExecErr")
            outThread.start()
            errThread.start()

            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (finished) {
                exitCode = proc.exitValue()
                outThread.join(500)
                errThread.join(500)
                status = if (exitCode == 0) "SUCCESS" else "FAILED"
            } else {
                proc.destroyForcibly()
                status = "TIMEOUT"
                stderrBuf.append("\n[Process timed out after ${timeoutMs}ms]")
            }
        } catch (e: Exception) {
            status = "ERROR"
            stderrBuf.append("\nExecution exception: ${e.message}")
            Log.w(TAG, "Gateway exec error: ${e.message}")
        }

        val durationMs = System.currentTimeMillis() - startTime
        val record = CommandRecord(
            id = cmdId,
            timestamp = startTime,
            cmd = cmd,
            rish = false,
            exitCode = exitCode,
            durationMs = durationMs,
            stdout = stdoutBuf.toString().trim(),
            stderr = stderrBuf.toString().trim(),
            status = status,
            source = source
        )
        recordCommand(record)

        return record.toJson().put("ok", status == "SUCCESS")
    }

    private fun recordCommand(record: CommandRecord) {
        synchronized(historyLock) {
            if (commandHistory.size >= MAX_COMMAND_HISTORY) {
                commandHistory.removeFirst()
            }
            commandHistory.addLast(record)
        }
    }

    private fun drainBoundedStream(input: InputStream, out: StringBuilder) {
        try {
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                var line: String?
                var truncated = false
                while (reader.readLine().also { line = it } != null) {
                    if (!truncated) {
                        if (out.length + (line?.length ?: 0) > MAX_EXEC_OUTPUT_BYTES) {
                            out.append("\n...[output truncated at ${MAX_EXEC_OUTPUT_BYTES / 1024} KiB]")
                            truncated = true
                        } else {
                            if (out.isNotEmpty()) out.append("\n")
                            out.append(line)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun getRecentCommandsJson(limit: Int): JSONArray {
        val count = limit.coerceIn(1, MAX_COMMAND_HISTORY)
        val arr = JSONArray()
        val snapshot = synchronized(historyLock) {
            if (commandHistory.size <= count) commandHistory.toList()
            else commandHistory.toList().takeLast(count)
        }
        for (rec in snapshot) {
            arr.put(rec.toJson())
        }
        return arr
    }

    private fun handleShizukuDiag(query: Map<String, String>, json: JSONObject): JSONObject {
        val snapshot = ShizukuManager.inspectStatus(context)
        if (!snapshot.enabled || !snapshot.isReady) {
            return JSONObject()
                .put("ok", false)
                .put(
                    "error",
                    if (!snapshot.enabled) "Shizuku is disabled in Settings -> Customizations"
                    else "Shizuku service not ready"
                )
                .put("state", snapshot.state.name.lowercase())
                .put("enabled", snapshot.enabled)
                .put("ready", snapshot.isReady)
        }
        val rawPkg = query["pkg"] ?: json.optString("pkg", "").ifBlank { json.optString("package", "") }
        val pkg = if (rawPkg.isBlank()) {
            context.packageName
        } else {
            validatePackageName(rawPkg) ?: return JSONObject()
                .put("ok", false)
                .put("error", "Invalid package name: $rawPkg")
        }
        val diagCmd = listOf(
            "echo '===PKG==='",
            "dumpsys package '$pkg' | grep -E 'versionCode|versionName|lastUpdateTime' | head -n 5",
            "echo '===PID==='",
            "pidof '$pkg' || true",
            "echo '===BATT==='",
            "dumpsys battery | grep -E 'level:|voltage:|temperature:|status:'",
            "echo '===PROCS==='",
            "ps -A | wc -l",
            "echo '===MEM==='",
            "dumpsys meminfo '$pkg' | grep -E 'TOTAL PSS:' | head -n 2",
            "echo '===GFX==='",
            "dumpsys gfxinfo '$pkg' | head -n 22",
            "echo '===ANR==='",
            "dumpsys dropbox | grep -E 'data_app_anr|data_app_crash' | tail -n 5"
        ).joinToString("; ")
        val execRes = ShizukuManager.executePrivileged(
            context = context,
            cmd = diagCmd,
            timeoutMs = 15_000L,
            gatewayPort = boundPort,
            gatewayToken = ensureGatewayToken(context.filesDir)
        )
        if (!execRes.ok) {
            return JSONObject()
                .put("ok", false)
                .put("package", pkg)
                .put("transport", execRes.transport)
                .put("uid", execRes.uid)
                .put("duration_ms", execRes.durationMs)
                .put("error", execRes.error ?: "Shizuku diagnostics execution failed")
        }
        return parseShizukuDiagOutput(pkg, execRes.stdout)
            .put("ok", true)
            .put("transport", execRes.transport)
            .put("uid", execRes.uid)
            .put("duration_ms", execRes.durationMs)
    }

    private fun handleCrashQuery(): JSONObject {
        val crashFile = File(context.filesDir, "crash.log")
        if (!crashFile.isFile || crashFile.length() == 0L) {
            return JSONObject().put("ok", true).put("has_crash", false).put("crash", JSONObject.NULL)
        }
        return try {
            val content = crashFile.readText(Charsets.UTF_8).take(32_768)
            JSONObject()
                .put("ok", true)
                .put("has_crash", true)
                .put("timestamp", crashFile.lastModified())
                .put("crash", content)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "failed reading crash.log")
        }
    }

    private fun handleSystemInfo(): JSONObject {
        val result = JSONObject().put("ok", true)
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        result.put("battery", batteryPct)

        try {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (batteryIntent != null) {
                val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
                result.put("charging", charging)
            }
        } catch (_: Exception) {}

        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val networkType = if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                when {
                    caps == null -> "offline"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                    else -> "other"
                }
            } else {
                "unknown"
            }
            result.put("network", networkType)
        } catch (_: Exception) {}

        val rt = Runtime.getRuntime()
        result.put("storage_free_mb", context.filesDir.usableSpace / (1024 * 1024))
        result.put("memory_free_mb", (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) / (1024 * 1024))
        result.put("android", Build.VERSION.RELEASE)
        result.put("sdk", Build.VERSION.SDK_INT)
        result.put("model", Build.MODEL)
        val snapshot = ShizukuManager.inspectStatus(context)
        result.put("cpu_has_lse", LseCompatHelper.cpuHasHardwareLse())
        result.put("shizuku_enabled", snapshot.enabled)
        result.put("shizuku_backend", snapshot.backend.id)
        result.put("shizuku_state", snapshot.state.name.lowercase())
        result.put("shizuku_ready", snapshot.isReady)
        result.put("shizuku_uid", snapshot.uid)
        result.put("rish_available", snapshot.enabled && (snapshot.isReady || snapshot.hasRishDex))
        return result
    }
}
