package id.vern.antigravity.executor

import android.content.Context
import android.util.Log
import id.vern.antigravity.gateway.ShizukuConfig
import id.vern.antigravity.gateway.SystemGatewayServer
import id.vern.antigravity.install.BootstrapInstaller
import id.vern.antigravity.install.LseCompatHelper
import id.vern.antigravity.nativebridge.RustTurboBridge
import id.vern.antigravity.oauth.OAuthManager
import id.vern.antigravity.service.PermissionConfigService
import id.vern.antigravity.util.AppLogger
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
 * Direct typed streaming events emitted by [AcpSessionPool] to avoid O(N^2) JSON
 * serialization/re-parsing overhead during high-speed token streaming.
 */
sealed class AcpStreamEvent {
    data class SessionInit(val conversationId: String) : AcpStreamEvent()
    data class ThoughtDelta(val delta: String, val durationSeconds: Int, val isDone: Boolean) : AcpStreamEvent()
    data class MessageDelta(val delta: String) : AcpStreamEvent()
    data class ToolStep(
        val toolCallId: String?,
        val toolName: String,
        val state: String,
        val params: JSONObject?,
        val output: String?
    ) : AcpStreamEvent()
    data class RawOutput(val line: String, val isError: Boolean) : AcpStreamEvent()
}

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
        val pid: Int,
        val writer: BufferedWriter,
        val reader: BufferedReader,
        val stderrThread: Thread,
        val workingDirPath: String,
        val addDirPath: String?,
        val configFingerprint: Long,
        val spawnedNetworkGen: Long
    ) {
        val writeLock = Any()
        val nextRpcId = AtomicInteger(1)
        val loadedSessions = HashSet<String>()
        val lastModelBySession = HashMap<String, String>()
        val activeStderrSink = AtomicReference<((String, Boolean) -> Unit)?>(null)
        val spawnedAtMs = System.currentTimeMillis()
        val lastUsedAtMs = AtomicLong(spawnedAtMs)
        val broken = AtomicBoolean(false)
        @Volatile
        var activeSessionId: String? = null

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

    @Volatile
    private var keepWarmIndefinitely = false

    @Volatile
    var onWarmProcessReady: ((Int) -> Unit)? = null

    private val permissionsEnsured = AtomicBoolean(false)
    private val prewarmInProgress = AtomicBoolean(false)
    private val networkGeneration = AtomicLong(0L)

    private val prewarmExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AcpSessionPool-Prewarm").apply { isDaemon = true }
    }

    private val idleScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "AcpSessionPool-Reaper").apply { isDaemon = true }
    }
    private var idleReapTask: ScheduledFuture<*>? = null

    fun isTurnRunning(): Boolean = turnInProgress

    fun isWarmAlive(): Boolean = synchronized(stateLock) { warmSession?.isHealthy() == true }

    fun isKeepWarmIndefinitely(): Boolean = keepWarmIndefinitely

    fun setKeepWarmIndefinitely(enabled: Boolean) {
        keepWarmIndefinitely = enabled
        synchronized(stateLock) {
            if (enabled) {
                cancelIdleReapLocked()
            } else if (warmSession?.isHealthy() == true && !turnInProgress) {
                scheduleIdleReapLocked()
            }
        }
    }

    fun getWarmDaemonPid(): Int? = synchronized(stateLock) {
        val cur = warmSession
        if (cur != null && cur.isHealthy() && cur.pid > 0) cur.pid else null
    }

    fun getWarmDaemonUptimeMs(): Long = synchronized(stateLock) {
        val cur = warmSession
        if (cur != null && cur.isHealthy()) {
            (System.currentTimeMillis() - cur.spawnedAtMs).coerceAtLeast(0L)
        } else {
            0L
        }
    }

    internal fun shouldScheduleIdleReap(keepWarmMode: Boolean, hasWarmSession: Boolean): Boolean =
        !keepWarmMode && hasWarmSession

    internal fun parsePidFromProcessString(desc: String?): Int {
        if (desc.isNullOrBlank()) return -1
        val match = Regex("""\bpid=(\d+)""").find(desc) ?: return -1
        return match.groupValues.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: -1
    }

    internal fun extractProcessPid(process: Process): Int {
        // 1. Android UNIXProcess.toString() includes "pid=<N>" without touching hidden reflection APIs
        val fromStr = parsePidFromProcessString(runCatching { process.toString() }.getOrNull())
        if (fromStr > 0) return fromStr
        // 2. Reflection fallback for JVM/ART Process.pid() method
        val fromMethod = runCatching {
            (process.javaClass.getMethod("pid").invoke(process) as? Number)?.toInt()
        }.getOrNull()
        if (fromMethod != null && fromMethod > 0) return fromMethod
        // 3. Reflection fallback for UNIXProcess.pid field
        return runCatching {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(process)
        }.getOrDefault(-1)
    }

    fun onNetworkChanged() {
        networkGeneration.incrementAndGet()
        if (!turnInProgress) {
            invalidate("network_changed")
        }
    }

    fun onResumedAfterLongBackground(backgroundDurationMs: Long) {
        if (turnInProgress || backgroundDurationMs < 2L * 60L * 1000L) return
        val shouldRecycle = synchronized(stateLock) {
            val cur = warmSession ?: return@synchronized false
            if (!cur.isHealthy()) {
                true
            } else if (keepWarmIndefinitely) {
                false
            } else {
                (System.currentTimeMillis() - cur.lastUsedAtMs.get()) >= 2L * 60L * 1000L
            }
        }
        if (shouldRecycle) {
            invalidate("resumed_after_${backgroundDurationMs / 1000}s_bg")
        }
    }

    internal fun isTransientNetworkAbort(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val lower = message.lowercase()
        return lower.contains("software caused connection abort") ||
            lower.contains("connection reset by peer") ||
            lower.contains("broken pipe") ||
            lower.contains("stream idle timeout") ||
            lower.contains("unexpected eof") ||
            lower.contains("acp eof during") ||
            lower.contains("network is unreachable") ||
            lower.contains("tls handshake timeout")
    }

    internal fun shouldRetryTransientFailure(
        stopRequested: Boolean,
        reusedWarm: Boolean,
        emittedModelOutputOrTool: Boolean,
        attempt: Int,
        errorMessage: String?
    ): Boolean {
        if (stopRequested || emittedModelOutputOrTool || attempt != 1) return false
        return reusedWarm || isTransientNetworkAbort(errorMessage)
    }

    /**
     * Proactively spawns and initializes a warm `agy acp` daemon in the background
     * after app boot so Turn 1 avoids the cold-start penalty.
     *
     * Uses non-blocking `turnLock.tryLock()` so it never delays an incoming user turn,
     * skips automatically if no OAuth credentials exist or a warm daemon is already healthy,
     * and schedules the standard idle reaper so background RAM is reclaimed if unused.
     */
    fun prewarmAsync(context: Context, workingDir: File? = null) {
        if (turnInProgress || isWarmAlive()) return
        if (!prewarmInProgress.compareAndSet(false, true)) return

        val appCtx = context.applicationContext
        prewarmExecutor.execute {
            try {
                if (turnInProgress || isWarmAlive()) return@execute

                val filesDir = appCtx.filesDir
                val homeDir = File(filesDir, "home").apply { mkdirs() }
                val targetCwd = if (workingDir != null && workingDir.exists() && workingDir.isDirectory) workingDir else homeDir
                val addDir = if (workingDir != null && workingDir.exists() && workingDir.isDirectory) workingDir else null

                val engineDir = File(filesDir, "engine")
                val ldBin = File(engineDir, "glibc/lib/ld-linux-aarch64.so.1")
                val agyBin = File(engineDir, "bin/agy")
                if (!ldBin.exists() || !agyBin.exists()) return@execute

                EngineExecutor.healEnvironment(appCtx)
                try {
                    if (permissionsEnsured.compareAndSet(false, true)) {
                        BootstrapInstaller.ensurePermissions(appCtx)
                    }
                    OAuthManager.checkAndImportExistingToken(appCtx)
                    val email = OAuthManager.getUserEmail(appCtx)
                    if (email.isNullOrBlank() && !OAuthManager.tokenFile(appCtx).exists()) {
                        return@execute
                    }
                    OAuthManager.syncCompanionStateFiles(appCtx)
                    PermissionConfigService.syncSettingsJsonFiles(appCtx)
                } catch (_: Exception) {}

                val fingerprint = computeConfigFingerprint(appCtx)
                val curNetGen = networkGeneration.get()
                if (!turnLock.tryLock()) return@execute
                try {
                    val alreadyHealthy = synchronized(stateLock) {
                        val existing = warmSession
                        existing != null &&
                            existing.isHealthy() &&
                            existing.workingDirPath == targetCwd.absolutePath &&
                            existing.addDirPath == addDir?.absolutePath &&
                            existing.configFingerprint == fingerprint &&
                            existing.spawnedNetworkGen == curNetGen
                    }
                    if (alreadyHealthy) return@execute

                    val t0 = System.currentTimeMillis()
                    val spawned = spawnAndInitialize(appCtx, targetCwd, addDir, fingerprint, curNetGen) { _, _ -> }
                    if (spawned != null) {
                        val elapsedMs = System.currentTimeMillis() - t0
                        val oldToDestroy = synchronized(stateLock) {
                            val prev = warmSession
                            warmSession = spawned
                            scheduleIdleReapLocked()
                            prev
                        }
                        oldToDestroy?.destroyQuietly()
                        AppLogger.d(TAG, "Pre-warmed ACP daemon in ${elapsedMs}ms (cwd=${targetCwd.absolutePath})")
                    }
                } finally {
                    turnLock.unlock()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Background ACP prewarm skipped: ${e.message}")
            } finally {
                prewarmInProgress.set(false)
            }
        }
    }

    internal fun buildCancelNotificationJson(sessionId: String): String =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "session/cancel")
            put("params", JSONObject().put("sessionId", sessionId))
        }.toString()

    fun stopActiveTurn() {
        stopRequested = true
        val current = synchronized(stateLock) {
            cancelIdleReapLocked()
            warmSession
        } ?: return

        val sid = current.activeSessionId
        if (!current.isHealthy() || sid.isNullOrBlank()) {
            val toDestroy = synchronized(stateLock) {
                val cur = warmSession
                if (cur === current) warmSession = null
                cur
            }
            toDestroy?.destroyQuietly()
            return
        }

        // Send graceful JSON-RPC session/cancel so the warm daemon stays alive for the next turn
        Thread({
            try {
                val cancelLine = buildCancelNotificationJson(sid)
                synchronized(current.writeLock) {
                    current.writer.write(cancelLine)
                    current.writer.newLine()
                    current.writer.flush()
                }
            } catch (_: Exception) {
                current.broken.set(true)
            }
        }, "AcpSessionPool-Cancel").apply { isDaemon = true }.start()

        idleScheduler.schedule({
            val toDestroy = synchronized(stateLock) {
                val cur = warmSession
                if (cur === current && ((turnInProgress && stopRequested) || cur.broken.get())) {
                    warmSession = null
                    cur
                } else null
            }
            if (toDestroy != null) {
                Log.w(TAG, "ACP session/cancel grace period expired; recycling daemon")
                toDestroy.destroyQuietly()
            }
        }, 1_500L, TimeUnit.MILLISECONDS)
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
        val current = warmSession
        if (!shouldScheduleIdleReap(keepWarmIndefinitely, current != null)) return
        val target = current ?: return
        idleReapTask = idleScheduler.schedule({
            val toDestroy = synchronized(stateLock) {
                val cur = warmSession
                if (!keepWarmIndefinitely && cur === target && !turnInProgress) {
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

    internal fun hashFileContentOrStat(file: File, maxContentBytes: Long = 512L * 1024L): Long {
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
        val appPrefs = context.getSharedPreferences("antigravity_prefs", Context.MODE_PRIVATE)
        val skipPerm = appPrefs.getBoolean("ag_skip_permissions", true)
        hash = 31L * hash + if (skipPerm) 1L else 0L
        val shizuku = ShizukuConfig.load(context)
        hash = 31L * hash + if (shizuku.enabled) 1L else 0L
        hash = 31L * hash + shizuku.backend.id.hashCode().toLong()
        hash = 31L * hash + if (shizuku.fallbackToShell) 1L else 0L

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
        spawnedNetworkGen: Long,
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

        val skipPermissions = context.getSharedPreferences("antigravity_prefs", Context.MODE_PRIVATE)
            .getBoolean("ag_skip_permissions", true)
        val acpArgs = mutableListOf("acp").apply {
            if (skipPermissions) add("--dangerously-skip-permissions")
        }

        val cmdList = LseCompatHelper.buildGlibcLoaderCommand(
            ldBin = ldBin,
            libraryPaths = libraryPaths,
            glibcLibDir = libDir,
            targetBin = agyBin,
            args = acpArgs
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
        val shizuku = ShizukuConfig.load(context)
        env["AGY_GATEWAY_PORT"] = SystemGatewayServer.readActivePort(filesDir).toString()
        env["AGY_GATEWAY_TOKEN"] = SystemGatewayServer.ensureGatewayToken(filesDir)
        env["AGY_SHIZUKU_ENABLED"] = if (shizuku.enabled) "1" else "0"
        env["AGY_SHIZUKU_BACKEND"] = shizuku.backend.id

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
            pid = extractProcessPid(process),
            writer = writer,
            reader = reader,
            stderrThread = stderrThread,
            workingDirPath = targetCwd.absolutePath,
            addDirPath = addDir?.absolutePath,
            configFingerprint = configFingerprint,
            spawnedNetworkGen = spawnedNetworkGen
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
                Log.i(TAG, "Warm ACP daemon initialized (pid=${managed.pid}) for cwd=${targetCwd.absolutePath}")
                if (managed.pid > 0) {
                    runCatching { onWarmProcessReady?.invoke(managed.pid) }
                }
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
        synchronized(session.writeLock) {
            session.writer.write(req.toString())
            session.writer.newLine()
            session.writer.flush()
        }
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
        onStreamEvent: ((AcpStreamEvent) -> Unit)? = null,
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

            // Allow at most 1 transparent retry if a warm/stale process died or hit a
            // transient socket abort before emitting model output or executing any tool.
            var attempt = 0
            while (attempt < 2) {
                attempt++
                var reusedWarm = false
                val curNetGen = networkGeneration.get()
                val session: ManagedAcpProcess = synchronized(stateLock) {
                    cancelIdleReapLocked()
                    val existing = warmSession
                    if (existing != null &&
                        existing.isHealthy() &&
                        existing.workingDirPath == targetCwd.absolutePath &&
                        existing.addDirPath == addDir?.absolutePath &&
                        existing.configFingerprint == fingerprint &&
                        existing.spawnedNetworkGen == curNetGen
                    ) {
                        reusedWarm = true
                        existing
                    } else {
                        warmSession = null
                        existing?.destroyQuietly()
                        null
                    }
                } ?: run {
                    val stderrForwarder: (String, Boolean) -> Unit = { l, isErr ->
                        if (onStreamEvent != null) {
                            onStreamEvent(AcpStreamEvent.RawOutput(l, isErr))
                        } else {
                            onOutputLine(l, isErr)
                        }
                    }
                    val spawned = spawnAndInitialize(context, targetCwd, addDir, fingerprint, curNetGen, stderrForwarder)
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

                session.activeStderrSink.set { line, isErr ->
                    if (onStreamEvent != null) {
                        onStreamEvent(AcpStreamEvent.RawOutput(line, isErr))
                    } else {
                        onOutputLine(line, isErr)
                    }
                }
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
                    if (thoughtStartMs == 0L && thoughtSb.isEmpty()) return
                    val durSec = if (thoughtStartMs > 0L) {
                        ((System.currentTimeMillis() - thoughtStartMs) / 1000L).toInt().coerceAtLeast(1)
                    } else 1
                    if (onStreamEvent != null) {
                        emittedAnyOutput = true
                        onStreamEvent(AcpStreamEvent.ThoughtDelta("", durSec, isDone = true))
                    } else if (thoughtSb.isNotEmpty()) {
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
                    }
                    thoughtSb.clear()
                    thoughtStartMs = 0L
                }

                fun handleThoughtChunk(chunk: String) {
                    if (chunk.isEmpty()) return
                    if (thoughtStartMs == 0L) thoughtStartMs = System.currentTimeMillis()
                    val durSec = ((System.currentTimeMillis() - thoughtStartMs) / 1000L).toInt().coerceAtLeast(1)
                    if (onStreamEvent != null) {
                        emittedAnyOutput = true
                        onStreamEvent(AcpStreamEvent.ThoughtDelta(chunk, durSec, isDone = false))
                        return
                    }
                    thoughtSb.append(chunk)
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
                    if (onStreamEvent != null) {
                        emittedAnyOutput = true
                        onStreamEvent(AcpStreamEvent.MessageDelta(chunk))
                        return
                    }
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
                    val toolCallId = upd.optString("toolCallId").ifBlank { upd.optString("id").ifBlank { null } }
                    val toolName = upd.optString("name").ifBlank { upd.optString("title", "tool") }
                    val status = upd.optString("status").lowercase()
                    val state = if (status == "completed" || status == "failed" || status == "cancelled" || status == "done") {
                        "DONE"
                    } else {
                        "ACTIVE"
                    }
                    val argsObj = upd.optJSONObject("args") ?: upd.optJSONObject("rawInput") ?: upd.optJSONObject("parameters")
                    val outputText = extractToolOutput(upd)
                    if (onStreamEvent != null) {
                        emittedAnyOutput = true
                        onStreamEvent(
                            AcpStreamEvent.ToolStep(
                                toolCallId = toolCallId,
                                toolName = toolName,
                                state = state,
                                params = argsObj,
                                output = outputText
                            )
                        )
                        return
                    }
                    val toolInfo = JSONObject().apply {
                        if (argsObj != null) put("args", argsObj)
                    }
                    val stepObj = JSONObject().apply {
                        put("step_type", "tool")
                        put("state", state)
                        if (toolCallId != null) put("tool_call_id", toolCallId)
                        put("tool_name", toolName)
                        put("tool_info", toolInfo)
                        outputText?.let { put("output", it) }
                    }
                    val evt = JSONObject().apply {
                        put("event", "step_update")
                        if (activeSessionId.isNotBlank()) put("conversation_id", activeSessionId)
                        put("step_update", stepObj)
                    }
                    emitLine(evt.toString(), false)
                }

                fun readUntilResponse(expectedId: Int): JSONObject? {
                    val batch = ArrayList<String>(16)
                    while (true) {
                        val firstLine = session.reader.readLine() ?: return null
                        batch.clear()
                        var totalChars = 0
                        val firstTrimmed = firstLine.trim()
                        if (firstTrimmed.startsWith("{")) {
                            batch.add(firstTrimmed)
                            totalChars += firstTrimmed.length
                        }
                        // Drain immediately-available buffered stdout lines with zero added delay
                        // so bursts cross the JNI boundary once instead of per line.
                        while (batch.size < 64 && totalChars < 131_072 && session.reader.ready()) {
                            val nextLine = session.reader.readLine() ?: break
                            val nextTrimmed = nextLine.trim()
                            if (nextTrimmed.startsWith("{")) {
                                batch.add(nextTrimmed)
                                totalChars += nextTrimmed.length
                            }
                        }
                        if (batch.isEmpty()) continue

                        val nativeRecords = RustTurboBridge.parseAcpBatch(batch)
                        if (nativeRecords != null) {
                            for (rec in nativeRecords) {
                                when (rec.tag) {
                                    'T' -> handleThoughtChunk(rec.body)
                                    'M' -> handleMessageChunk(rec.body)
                                    'C' -> {
                                        val raw = batch.getOrNull(rec.lineIndex) ?: continue
                                        val msg = try { JSONObject(raw) } catch (_: Exception) { continue }
                                        val upd = msg.optJSONObject("params")?.optJSONObject("update") ?: continue
                                        handleToolUpdate(upd)
                                    }
                                    'R' -> {
                                        val idVal = rec.body.toIntOrNull()
                                        if (idVal == expectedId) {
                                            val raw = batch.getOrNull(rec.lineIndex) ?: return null
                                            return try { JSONObject(raw) } catch (_: Exception) { null }
                                        }
                                    }
                                }
                            }
                            continue
                        }

                        // Kotlin fallback when native lib is unavailable
                        for (trimmed in batch) {
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
                    }
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

                    session.activeSessionId = activeSessionId
                    if (onStreamEvent != null) {
                        // Do NOT mark emittedAnyOutput here: SessionInit happens before
                        // session/prompt is sent, so marking it here would disable transparent
                        // recovery if the warm process dies or aborts before producing output.
                        onStreamEvent(AcpStreamEvent.SessionInit(activeSessionId))
                    } else {
                        onOutputLine(JSONObject().apply {
                            put("event", "init")
                            put("conversation_id", activeSessionId)
                        }.toString(), false)
                    }

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
                        if (stopRequested) {
                            consecutiveCrashCount = 0
                            0
                        } else {
                            val errMsg = promptResp.optJSONObject("error")?.optString("message") ?: "ACP prompt error"
                            if (shouldRetryTransientFailure(stopRequested, reusedWarm, emittedAnyOutput, attempt, errMsg)) {
                                AppLogger.d(TAG, "Transient ACP failure before output ($errMsg); recycling daemon and retrying")
                                throw java.io.IOException(errMsg)
                            }
                            if (onStreamEvent != null) {
                                onStreamEvent(AcpStreamEvent.RawOutput(errMsg, true))
                            } else {
                                onOutputLine(errMsg, true)
                            }
                            1
                        }
                    } else {
                        if (onStreamEvent == null) {
                            onOutputLine(JSONObject().apply {
                                put("event", "result")
                                put("conversation_id", activeSessionId)
                                put("result", JSONObject().apply {
                                    put("status", "SUCCESS")
                                    put("response", responseSb.toString())
                                })
                            }.toString(), false)
                        }
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

                    if (shouldRetryTransientFailure(stopRequested, reusedWarm, emittedAnyOutput, attempt, e.message)) {
                        Log.w(TAG, "Warm/stale ACP session aborted before output (${e.message}); retrying on fresh daemon")
                        AppLogger.d(TAG, "Recovering ACP session after transient abort: ${e.message}")
                        continue
                    }
                    if (!stopRequested) {
                        recordCrashAndBackoff()
                    }
                    if (activeSessionId.isBlank() && !stopRequested) EngineExecutor.ACP_UNAVAILABLE else -1
                } finally {
                    session.activeSessionId = null
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
