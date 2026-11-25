package id.vern.antigravity.executor

import android.content.Context
import android.util.Log
import id.vern.antigravity.install.BootstrapInstaller
import id.vern.antigravity.install.LseCompatHelper
import id.vern.antigravity.nativebridge.RustTurboBridge
import id.vern.antigravity.oauth.OAuthManager
import id.vern.antigravity.service.PermissionConfigService
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/**
 * Persistent supervisor & session pool for `agy acp`.
 *
 * Keeps a single warm `agy acp` process alive across chat turns so glibc loading,
 * MCP server initialization, and the JSON-RPC `initialize` / `session/resume`
 * handshakes are only paid once per workspace/config instead of on every message.
 *
 * Automatically restarts on workspace/account/MCP/binary changes, recovers from
 * unexpected daemon exits with exponential backoff, and reaps idle processes
 * after [IDLE_TIMEOUT_MS] to keep background RAM usage minimal.
 */
object AcpSessionPool {
    private const val TAG = "AcpSessionPool"
    private const val IDLE_TIMEOUT_MS = 6L * 60L * 1000L // 6 minutes
    private const val CRASH_WINDOW_MS = 30_000L
    private const val MAX_CONSECUTIVE_CRASHES = 4

    private class ManagedAcpProcess(
        val process: Process,
        val writer: BufferedWriter,
        val reader: BufferedReader,
        val stderrThread: Thread,
        val workingDirPath: String,
        val addDirPath: String?,
        val configFingerprint: Long
    ) {
        val nextRpcId = AtomicInteger(1)
        val loadedSessions = HashSet<String>()
        val lastModelBySession = HashMap<String, String>()
        val activeStderrSink = AtomicReference<((String, Boolean) -> Unit)?>(null)
        val lastUsedAtMs = AtomicLong(System.currentTimeMillis())
        val broken = AtomicBoolean(false)

        fun isHealthy(): Boolean = !broken.get() && process.isAlive

        fun destroyQuietly() {
            broken.set(true)
            activeStderrSink.set(null)
            try { writer.close() } catch (_: Exception) {}
            try { process.outputStream.close() } catch (_: Exception) {}
            try { process.destroy() } catch (_: Exception) {}
            try {
                if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                }
            } catch (_: Exception) {
                try { process.destroyForcibly() } catch (_: Exception) {}
            }
            try { reader.close() } catch (_: Exception) {}
            try { stderrThread.join(250) } catch (_: Exception) {}
        }
    }

    private val turnLock = ReentrantLock()
    private val stateLock = Any()

    @Volatile
    private var warmSession: ManagedAcpProcess? = null

    @Volatile
    private var turnInProgress = false

    @Volatile
    private var stopRequested = false

    @Volatile
    private var consecutiveCrashCount = 0

    @Volatile
    private var lastCrashTimeMs = 0L

    private val permissionsEnsured = AtomicBoolean(false)

    private val idleScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "AcpSessionPool-Reaper").apply { isDaemon = true }
    }
    private var idleReapTask: ScheduledFuture<*>? = null

    fun isTurnRunning(): Boolean = turnInProgress

    fun isWarmAlive(): Boolean = synchronized(stateLock) { warmSession?.isHealthy() == true }

    fun stopActiveTurn() {
        stopRequested = true
        val toDestroy = synchronized(stateLock) {
            cancelIdleReapLocked()
            val cur = warmSession
            warmSession = null
            cur
        }
        toDestroy?.destroyQuietly()
    }

    fun invalidate(reason: String) {
        val toDestroy = synchronized(stateLock) {
            cancelIdleReapLocked()
            if (turnInProgress) {
                // Mark broken so it will be recycled as soon as the active turn finishes
                warmSession?.broken?.set(true)
                null
            } else {
                val cur = warmSession
                warmSession = null
                cur
            }
        }
        if (toDestroy != null) {
            Log.i(TAG, "Invalidating warm ACP session ($reason)")
            toDestroy.destroyQuietly()
        }
    }

    private fun cancelIdleReapLocked() {
        idleReapTask?.cancel(false)
        idleReapTask = null
    }

    private fun scheduleIdleReapLocked() {
        cancelIdleReapLocked()
        val current = warmSession ?: return
        idleReapTask = idleScheduler.schedule({
            val toDestroy = synchronized(stateLock) {
                val cur = warmSession
                if (cur === current && !turnInProgress) {
                    val idleMs = System.currentTimeMillis() - cur.lastUsedAtMs.get()
                    if (idleMs >= IDLE_TIMEOUT_MS - 1_000L) {
                        warmSession = null
                        cur
                    } else null
                } else null
            }
            if (toDestroy != null) {
                Log.i(TAG, "Reaping idle ACP daemon after ${IDLE_TIMEOUT_MS / 1000}s to free RAM")
                toDestroy.destroyQuietly()
            }
        }, IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun hashFileContentOrStat(file: File, maxContentBytes: Long = 512L * 1024L): Long {
        if (!file.exists() || !file.isFile) return 0L
        val len = file.length()
        if (len <= maxContentBytes && file.canRead()) {
            return try {
                val crc = java.util.zip.CRC32()
                crc.update(file.readBytes())
                crc.value xor (len shl 32)
            } catch (_: Exception) {
                file.lastModified() xor (len shl 16)
            }
        }
        return file.lastModified() xor (len shl 16)
    }

    private fun computeConfigFingerprint(context: Context): Long {
        val filesDir = context.filesDir
        val homeDir = File(filesDir, "home")
        val prefs = context.getSharedPreferences(OAuthManager.PREFS_NAME, Context.MODE_PRIVATE)
        var hash = 17L
        val email = prefs.getString(OAuthManager.KEY_USER_EMAIL, "") ?: ""
        hash = 31L * hash + email.hashCode().toLong()

        // Hash content of small config files so mtime-only touches never invalidate a warm daemon
        val trackedConfigs = listOf(
            File(homeDir, ".gemini/config/mcp_config.json"),
            File(homeDir, ".gemini/config/config.json"),
            File(homeDir, ".gemini/antigravity-cli/settings.json"),
            File(homeDir, ".gemini/antigravity-cli/oauth_creds.json"),
            File(filesDir, ".gemini/config/mcp_config.json")
        )
        for (f in trackedConfigs) {
            hash = 31L * hash + hashFileContentOrStat(f)
        }
        // For the large agy binary, stat metadata is sufficient and avoids reading 250+ MB
        val agyBin = File(filesDir, "engine/bin/agy")
        hash = 31L * hash + if (agyBin.exists()) (agyBin.lastModified() xor (agyBin.length() shl 16)) else 0L
        return hash
    }

    private fun recordCrashAndBackoff(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastCrashTimeMs > CRASH_WINDOW_MS) {
            consecutiveCrashCount = 1
        } else {
            consecutiveCrashCount += 1
        }
        lastCrashTimeMs = now

        if (consecutiveCrashCount >= MAX_CONSECUTIVE_CRASHES) {
            Log.w(TAG, "ACP daemon crashed $consecutiveCrashCount times within ${CRASH_WINDOW_MS}ms; falling back")
            return false
        }
        val backoffMs = (250L * (1L shl (consecutiveCrashCount - 1).coerceAtMost(3))).coerceAtMost(2_000L)
        try {
            Thread.sleep(backoffMs)
        } catch (_: InterruptedException) {}
        return true
    }

    private fun spawnAndInitialize(
        context: Context,
        targetCwd: File,
        addDir: File?,
        configFingerprint: Long,
        onInitErrorLine: (String, Boolean) -> Unit
    ): ManagedAcpProcess? {
        val filesDir = context.filesDir
        val homeDir = File(filesDir, "home").apply { mkdirs() }
        val tmpDir = File(filesDir, "tmp").apply { mkdirs() }
        val engineDir = File(filesDir, "engine")
        val prefixDir = File(filesDir, "usr")
        val prefixLib = File(prefixDir, "lib")
        val libDir = File(engineDir, "glibc/lib")
        val ldBin = File(libDir, "ld-linux-aarch64.so.1")
        val agyBin = File(engineDir, "bin/agy")
        val certFile = File(prefixDir, "etc/tls/cert.pem")

        if (!ldBin.exists() || !agyBin.exists()) {
            return null
        }

        val libraryPaths = listOf(libDir.absolutePath, prefixLib.absolutePath)
            .filter { File(it).exists() }
            .joinToString(":")

        val cmdList = LseCompatHelper.buildGlibcLoaderCommand(
            ldBin = ldBin,
            libraryPaths = libraryPaths,
            glibcLibDir = libDir,
            targetBin = agyBin,
            args = listOf("acp", "--dangerously-skip-permissions")
        )
        if (addDir != null && addDir.exists() && addDir.isDirectory) {
            cmdList.add("--add-dir")
            cmdList.add(addDir.absolutePath)
        }

        val pb = ProcessBuilder(cmdList)
        pb.directory(targetCwd)

        val gitExecDir = File(prefixDir, "libexec/git-core")
        val validGitExec = if (gitExecDir.exists() && gitExecDir.isDirectory) gitExecDir else null
        val validGitTemplate = File(prefixDir, "share/git-core/templates").apply { mkdirs() }

        val env = pb.environment()
        env.remove("LD_PRELOAD")
        env["PREFIX"] = prefixDir.absolutePath
        env["HOME"] = homeDir.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath
        env["PWD"] = targetCwd.absolutePath
        env["GIT_CONFIG_NOSYSTEM"] = "1"
        env["GIT_ATTR_NOSYSTEM"] = "1"
        env["GIT_TERMINAL_PROMPT"] = "0"
        if (validGitExec != null) env["GIT_EXEC_PATH"] = validGitExec.absolutePath
        env["GIT_TEMPLATE_DIR"] = validGitTemplate.absolutePath
        env["TERM"] = "xterm-256color"
        env["LANG"] = "en_US.UTF-8"
        val extraGitPath = if (validGitExec != null) ":${validGitExec.absolutePath}" else ""
        env["PATH"] = "${File(prefixDir, "bin").absolutePath}:${File(engineDir, "bin").absolutePath}$extraGitPath:/system/bin"
        env["LD_LIBRARY_PATH"] = "${prefixLib.absolutePath}:/system/lib64"
        if (certFile.exists()) {
            env["SSL_CERT_FILE"] = certFile.absolutePath
            env["CURL_CA_BUNDLE"] = certFile.absolutePath
        }
        env["GODEBUG"] = "netdns=cgo"
        env["AGY_APPLICATION_ID"] = context.packageName
        env["APPLICATION_ID"] = context.packageName
        env["ANTIGRAVITY_APP_DATA_DIR"] = File(homeDir, ".gemini/antigravity-cli").apply { mkdirs() }.absolutePath

        val process = try {
            pb.start()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to spawn agy acp: ${e.message}")
            return null
        }

        val writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
        val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8), 32_768)
        val sinkRef = AtomicReference<((String, Boolean) -> Unit)?>(onInitErrorLine)

        val stderrThread = Thread({
            try {
                BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).use { errReader ->
                    var line: String?
                    while (errReader.readLine().also { line = it } != null) {
                        val l = line ?: continue
                        val sink = sinkRef.get()
                        if (sink != null) {
                            sink(l, true)
                        } else {
                            Log.d(TAG, "acp-stderr: $l")
                        }
                    }
                }
            } catch (_: Exception) {}
        }, "AcpSessionPool-Stderr").apply { isDaemon = true }
        stderrThread.start()

        val managed = ManagedAcpProcess(
            process = process,
            writer = writer,
            reader = reader,
            stderrThread = stderrThread,
            workingDirPath = targetCwd.absolutePath,
            addDirPath = addDir?.absolutePath,
            configFingerprint = configFingerprint
        )
        // Share the same sink reference via managed.activeStderrSink
        // Re-wire stderr sink cleanly by delegating through managed
        sinkRef.set { line, isErr ->
            val active = managed.activeStderrSink.get()
            if (active != null) active(line, isErr) else Log.d(TAG, "acp-stderr: $line")
        }
        managed.activeStderrSink.set(onInitErrorLine)

        return try {
            val initId = managed.nextRpcId.getAndIncrement()
            sendRpc(managed, initId, "initialize", JSONObject().apply {
                put("protocolVersion", 1)
                put("clientCapabilities", JSONObject())
            })
            val initResp = readSimpleResponse(managed, initId)
            if (initResp == null || initResp.has("error")) {
                managed.destroyQuietly()
                null
            } else {
                Log.i(TAG, "Warm ACP daemon initialized for cwd=${targetCwd.absolutePath}")
                managed
            }
        } catch (e: Exception) {
            Log.w(TAG, "ACP initialize handshake failed: ${e.message}")
            managed.destroyQuietly()
            null
        }
    }

    private fun sendRpc(session: ManagedAcpProcess, id: Int, method: String, params: JSONObject) {
        val req = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        session.writer.write(req.toString())
        session.writer.newLine()
        session.writer.flush()
    }

    private fun readSimpleResponse(session: ManagedAcpProcess, expectedId: Int): JSONObject? {
        var line: String?
        while (session.reader.readLine().also { line = it } != null) {
            val trimmed = line?.trim().orEmpty()
            if (!trimmed.startsWith("{")) continue
            val fast = RustTurboBridge.parseAcpLine(trimmed)
            if (fast != null) {
                if (!fast.startsWith("R\n")) continue
                val idVal = fast.substring(2).toIntOrNull() ?: continue
                if (idVal != expectedId) continue
                return try { JSONObject(trimmed) } catch (_: Exception) { null }
            }
            val msg = try { JSONObject(trimmed) } catch (_: Exception) { continue }
            if (msg.has("id") && msg.optInt("id", -1) == expectedId) {
                return msg
            }
        }
        return null
    }

    private fun extractToolOutput(upd: JSONObject): String? {
        val rawOut = upd.optString("output").ifBlank { upd.optString("rawOutput") }
        if (rawOut.isNotBlank()) return rawOut
        val contentArr = upd.optJSONArray("content") ?: return null
        val sb = StringBuilder()
        for (i in 0 until contentArr.length()) {
            val item = contentArr.optJSONObject(i) ?: continue
            val text = item.optString("text").ifBlank {
                item.optJSONObject("content")?.optString("text") ?: ""
            }
            if (text.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(text)
            }
        }
        return sb.toString().ifBlank { null }
    }

    fun executeTurn(
        context: Context,
        prompt: String,
        cliModelId: String? = null,
        sessionToResume: String? = null,
        workingDir: File? = null,
        onOutputLine: (String, Boolean) -> Unit
    ): Int {
        EngineExecutor.healEnvironment(context)

        val filesDir = context.filesDir
        val homeDir = File(filesDir, "home").apply { mkdirs() }
        val targetCwd = if (workingDir != null && workingDir.exists() && workingDir.isDirectory) workingDir else homeDir
        val addDir = if (workingDir != null && workingDir.exists() && workingDir.isDirectory) workingDir else null

        val engineDir = File(filesDir, "engine")
        val libDir = File(engineDir, "glibc/lib")
        val ldBin = File(libDir, "ld-linux-aarch64.so.1")
        val agyBin = File(engineDir, "bin/agy")
        if (!ldBin.exists() || !agyBin.exists()) {
            return EngineExecutor.ACP_UNAVAILABLE
        }

        try {
            if (permissionsEnsured.compareAndSet(false, true)) {
                BootstrapInstaller.ensurePermissions(context)
            }
            OAuthManager.checkAndImportExistingToken(context)
            OAuthManager.syncCompanionStateFiles(context)
            PermissionConfigService.syncSettingsJsonFiles(context)
        } catch (_: Exception) {}

        val fingerprint = computeConfigFingerprint(context)

        turnLock.lock()
        try {
            stopRequested = false
            turnInProgress = true

            // Allow at most 1 transparent retry if a warm reused process died before emitting output
            var attempt = 0
            while (attempt < 2) {
                attempt++
                var reusedWarm = false
                val session: ManagedAcpProcess = synchronized(stateLock) {
                    cancelIdleReapLocked()
                    val existing = warmSession
                    if (existing != null &&
                        existing.isHealthy() &&
                        existing.workingDirPath == targetCwd.absolutePath &&
                        existing.addDirPath == addDir?.absolutePath &&
                        existing.configFingerprint == fingerprint
                    ) {
                        reusedWarm = true
                        existing
                    } else {
                        warmSession = null
                        existing?.destroyQuietly()
                        null
                    }
                } ?: run {
                    val spawned = spawnAndInitialize(context, targetCwd, addDir, fingerprint, onOutputLine)
                    if (spawned == null) {
                        if (!recordCrashAndBackoff()) {
                            return EngineExecutor.ACP_UNAVAILABLE
                        }
                        return EngineExecutor.ACP_UNAVAILABLE
                    }
                    synchronized(stateLock) {
                        warmSession = spawned
                    }
                    spawned
                }

                session.activeStderrSink.set(onOutputLine)
                var activeSessionId = ""
                val thoughtSb = StringBuilder()
                var thoughtStartMs = 0L
                val responseSb = StringBuilder()
                var emittedAnyOutput = false

                fun emitLine(line: String, isErr: Boolean) {
                    if (!isErr) emittedAnyOutput = true
                    onOutputLine(line, isErr)
                }

                fun flushThoughtDone() {
                    if (thoughtSb.isEmpty()) return
                    val durSec = if (thoughtStartMs > 0L) {
                        ((System.currentTimeMillis() - thoughtStartMs) / 1000L).toInt().coerceAtLeast(1)
                    } else 1
                    val stepObj = JSONObject().apply {
                        put("step_type", "thought")
                        put("state", "DONE")
                        put("text", thoughtSb.toString())
                        put("duration_seconds", durSec)
                    }
                    val evt = JSONObject().apply {
                        put("event", "step_update")
                        if (activeSessionId.isNotBlank()) put("conversation_id", activeSessionId)
                        put("step_update", stepObj)
                    }
                    emitLine(evt.toString(), false)
                    thoughtSb.clear()
                    thoughtStartMs = 0L
                }

                fun handleThoughtChunk(chunk: String) {
                    if (chunk.isEmpty()) return
                    if (thoughtStartMs == 0L) thoughtStartMs = System.currentTimeMillis()
                    thoughtSb.append(chunk)
                    val durSec = ((System.currentTimeMillis() - thoughtStartMs) / 1000L).toInt().coerceAtLeast(1)
                    val stepObj = JSONObject().apply {
                        put("step_type", "thought")
                        put("state", "ACTIVE")
                        put("text", thoughtSb.toString())
                        put("text_delta", chunk)
                        put("duration_seconds", durSec)
                    }
                    val evt = JSONObject().apply {
                        put("event", "step_update")
                        if (activeSessionId.isNotBlank()) put("conversation_id", activeSessionId)
                        put("step_update", stepObj)
                    }
                    emitLine(evt.toString(), false)
                }

                fun handleMessageChunk(chunk: String) {
                    flushThoughtDone()
                    if (chunk.isEmpty()) return
                    responseSb.append(chunk)
                    val stepObj = JSONObject().apply {
                        put("step_type", "agent_response")
                        put("state", "ACTIVE")
                        put("text_delta", chunk)
                    }
                    val evt = JSONObject().apply {
                        put("event", "step_update")
                        if (activeSessionId.isNotBlank()) put("conversation_id", activeSessionId)
                        put("step_update", stepObj)
                    }
                    emitLine(evt.toString(), false)
                }

                fun handleToolUpdate(upd: JSONObject) {
                    flushThoughtDone()
                    val toolName = upd.optString("name").ifBlank { upd.optString("title", "tool") }
                    val status = upd.optString("status").lowercase()
                    val state = if (status == "completed" || status == "failed" || status == "cancelled" || status == "done") {
                        "DONE"
                    } else {
                        "ACTIVE"
                    }
                    val argsObj = upd.optJSONObject("args") ?: upd.optJSONObject("rawInput")
                    val toolInfo = JSONObject().apply {
                        if (argsObj != null) put("args", argsObj)
                    }
                    val stepObj = JSONObject().apply {
                        put("step_type", "tool")
                        put("state", state)
                        put("tool_name", toolName)
                        put("tool_info", toolInfo)
                        extractToolOutput(upd)?.let { put("output", it) }
                    }
                    val evt = JSONObject().apply {
                        put("event", "step_update")
                        if (activeSessionId.isNotBlank()) put("conversation_id", activeSessionId)
                        put("step_update", stepObj)
                    }
                    emitLine(evt.toString(), false)
                }

                fun readUntilResponse(expectedId: Int): JSONObject? {
                    var line: String?
                    while (session.reader.readLine().also { line = it } != null) {
                        val trimmed = line?.trim().orEmpty()
                        if (!trimmed.startsWith("{")) continue

                        // Fast native Rust JSON-RPC line classifier/extractor:
                        // avoids allocating org.json.JSONObject for every streaming text/thought chunk.
                        val fast = RustTurboBridge.parseAcpLine(trimmed)
                        if (fast != null) {
                            when {
                                fast.startsWith("T\n") -> {
                                    handleThoughtChunk(fast.substring(2))
                                    continue
                                }
                                fast.startsWith("M\n") -> {
                                    handleMessageChunk(fast.substring(2))
                                    continue
                                }
                                fast == "I" -> continue
                                fast.startsWith("R\n") -> {
                                    val idVal = fast.substring(2).toIntOrNull()
                                    if (idVal == expectedId) {
                                        return try { JSONObject(trimmed) } catch (_: Exception) { null }
                                    }
                                    continue
                                }
                                // fast == "C" (tool_call / tool_call_update): fall through to full JSONObject parse
                            }
                        }

                        val msg = try { JSONObject(trimmed) } catch (_: Exception) { continue }
                        val method = msg.optString("method")
                        if (method == "session/update") {
                            val upd = msg.optJSONObject("params")?.optJSONObject("update") ?: continue
                            when (upd.optString("sessionUpdate")) {
                                "agent_thought_chunk" -> {
                                    val chunk = upd.optJSONObject("content")?.optString("text", "") ?: ""
                                    handleThoughtChunk(chunk)
                                }
                                "tool_call", "tool_call_update" -> handleToolUpdate(upd)
                                "agent_message_chunk" -> {
                                    val chunk = upd.optJSONObject("content")?.optString("text", "") ?: ""
                                    handleMessageChunk(chunk)
                                }
                            }
                        } else if (msg.has("id") && msg.optInt("id", -1) == expectedId) {
                            return msg
                        }
                    }
                    return null
                }

                val turnOutcome: Int = try {
                    // 1. Resume or create session (skip RPC if already loaded in this warm process)
                    if (!sessionToResume.isNullOrBlank()) {
                        if (session.loadedSessions.contains(sessionToResume)) {
                            activeSessionId = sessionToResume
                        } else {
                            val resumeId = session.nextRpcId.getAndIncrement()
                            sendRpc(session, resumeId, "session/resume", JSONObject().apply {
                                put("sessionId", sessionToResume)
                                put("cwd", targetCwd.absolutePath)
                                put("mcpServers", JSONArray())
                            })
                            val resumed = readUntilResponse(resumeId)
                            if (resumed == null) {
                                throw java.io.IOException("ACP EOF during session/resume")
                            }
                            if (!resumed.has("error")) {
                                activeSessionId = resumed.optJSONObject("result")?.optString("sessionId")
                                    ?.ifBlank { sessionToResume } ?: sessionToResume
                                session.loadedSessions.add(activeSessionId)
                            }
                        }
                    }

                    if (activeSessionId.isBlank()) {
                        val newId = session.nextRpcId.getAndIncrement()
                        sendRpc(session, newId, "session/new", JSONObject().apply {
                            put("cwd", targetCwd.absolutePath)
                            put("mcpServers", JSONArray())
                        })
                        val sessionResp = readUntilResponse(newId)
                            ?: throw java.io.IOException("ACP EOF during session/new")
                        if (sessionResp.has("error")) {
                            return EngineExecutor.ACP_UNAVAILABLE
                        }
                        activeSessionId = sessionResp.optJSONObject("result")?.optString("sessionId").orEmpty()
                        if (activeSessionId.isBlank()) {
                            return EngineExecutor.ACP_UNAVAILABLE
                        }
                        session.loadedSessions.add(activeSessionId)
                    }

                    emitLine(JSONObject().apply {
                        put("event", "init")
                        put("conversation_id", activeSessionId)
                    }.toString(), false)

                    // 2. Select model if specified and changed
                    if (!cliModelId.isNullOrBlank() && session.lastModelBySession[activeSessionId] != cliModelId) {
                        val modelRpcId = session.nextRpcId.getAndIncrement()
                        sendRpc(session, modelRpcId, "session/set_model", JSONObject().apply {
                            put("sessionId", activeSessionId)
                            put("modelId", cliModelId)
                        })
                        val modelResp = readUntilResponse(modelRpcId)
                            ?: throw java.io.IOException("ACP EOF during session/set_model")
                        if (!modelResp.has("error")) {
                            session.lastModelBySession[activeSessionId] = cliModelId
                        }
                    }

                    // 3. Run prompt turn
                    val promptId = session.nextRpcId.getAndIncrement()
                    sendRpc(session, promptId, "session/prompt", JSONObject().apply {
                        put("sessionId", activeSessionId)
                        put("prompt", JSONArray().put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        }))
                    })
                    val promptResp = readUntilResponse(promptId)
                    flushThoughtDone()

                    if (promptResp == null) {
                        throw java.io.IOException("ACP EOF during session/prompt")
                    }
                    if (promptResp.has("error")) {
                        val errMsg = promptResp.optJSONObject("error")?.optString("message") ?: "ACP prompt error"
                        onOutputLine(errMsg, true)
                        1
                    } else {
                        onOutputLine(JSONObject().apply {
                            put("event", "result")
                            put("conversation_id", activeSessionId)
                            put("result", JSONObject().apply {
                                put("status", "SUCCESS")
                                put("response", responseSb.toString())
                            })
                        }.toString(), false)
                        consecutiveCrashCount = 0
                        0
                    }
                } catch (e: Exception) {
                    session.broken.set(true)
                    synchronized(stateLock) {
                        if (warmSession === session) {
                            warmSession = null
                        }
                    }
                    session.destroyQuietly()

                    if (!stopRequested && reusedWarm && !emittedAnyOutput && attempt == 1) {
                        Log.w(TAG, "Warm ACP session died before output (${e.message}); retrying on fresh daemon")
                        continue
                    }
                    if (!stopRequested) {
                        recordCrashAndBackoff()
                    }
                    if (activeSessionId.isBlank() && !stopRequested) EngineExecutor.ACP_UNAVAILABLE else -1
                } finally {
                    session.activeStderrSink.set(null)
                    session.lastUsedAtMs.set(System.currentTimeMillis())
                    if (session.broken.get()) {
                        synchronized(stateLock) {
                            if (warmSession === session) warmSession = null
                        }
                        session.destroyQuietly()
                    }
                }

                return turnOutcome
            }

            return EngineExecutor.ACP_UNAVAILABLE
        } finally {
            synchronized(stateLock) {
                turnInProgress = false
                if (warmSession?.isHealthy() == true) {
                    scheduleIdleReapLocked()
                }
            }
            turnLock.unlock()
        }
    }
}
