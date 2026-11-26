package id.vern.antigravity.gateway

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import id.vern.antigravity.BuildConfig
import id.vern.antigravity.shizuku.IShizukuShellService
import moe.shizuku.server.IShizukuService
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class ShizukuConnectionState {
    DISABLED,
    NOT_INSTALLED,
    SERVICE_STOPPED,
    PERMISSION_REQUIRED,
    CONNECTED,
    RISH_ONLY_READY
}

data class ShizukuStatusSnapshot(
    val enabled: Boolean = false,
    val backend: ShizukuBackend = ShizukuBackend.AUTO,
    val fallbackToShell: Boolean = false,
    val state: ShizukuConnectionState = ShizukuConnectionState.DISABLED,
    val appInstalled: Boolean = false,
    val binderAlive: Boolean = false,
    val permissionGranted: Boolean = false,
    val uid: Int = -1,
    val shizukuVersion: Int = -1,
    val hasRishDex: Boolean = false,
    val activeTransport: String = "none",
    val isReady: Boolean = false
)

data class ShizukuExecResult(
    val ok: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val status: String,
    val transport: String,
    val error: String? = null,
    val uid: Int = -1
)

/**
 * Coordinates opt-in Shizuku integration via Native Binder UserService and optional `rish` DEX.
 *
 * When Shizuku is disabled in Settings -> Customizations, [inspectStatus] returns immediately
 * without touching Shizuku Binder, PackageManager, or external storage.
 */
object ShizukuManager {
    private const val TAG = "ShizukuManager"
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val PERMISSION_REQUEST_CODE = 45161
    private const val MAX_OUTPUT_BYTES = 512 * 1024

    private val listenerLock = Any()
    private val serviceLock = Any()

    @Volatile
    private var listenersRegistered = false

    @Volatile
    private var statusChangedCallback: (() -> Unit)? = null

    @Volatile
    private var boundShellService: IShizukuShellService? = null

    @Volatile
    private var activeConnection: ServiceConnection? = null

    @Volatile
    private var pendingLatch: CountDownLatch? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        statusChangedCallback?.invoke()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        boundShellService = null
        pendingLatch?.countDown()
        statusChangedCallback?.invoke()
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == PERMISSION_REQUEST_CODE) {
            statusChangedCallback?.invoke()
        }
    }

    private fun ensureListenersRegistered(onStatusChanged: (() -> Unit)? = null) {
        if (onStatusChanged != null) {
            statusChangedCallback = onStatusChanged
        }
        synchronized(listenerLock) {
            if (listenersRegistered) return
            runCatching {
                Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
                Shizuku.addBinderDeadListener(binderDeadListener)
                Shizuku.addRequestPermissionResultListener(permissionResultListener)
                listenersRegistered = true
            }.onFailure { e ->
                Log.w(TAG, "Failed registering Shizuku listeners: ${e.message}")
            }
        }
    }

    private fun unregisterListeners() {
        synchronized(listenerLock) {
            statusChangedCallback = null
            if (!listenersRegistered) return
            runCatching { Shizuku.removeBinderReceivedListener(binderReceivedListener) }
            runCatching { Shizuku.removeBinderDeadListener(binderDeadListener) }
            runCatching { Shizuku.removeRequestPermissionResultListener(permissionResultListener) }
            listenersRegistered = false
        }
    }

    fun isShizukuAppInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun inspectStatus(
        context: Context,
        onStatusChanged: (() -> Unit)? = null
    ): ShizukuStatusSnapshot {
        val settings = ShizukuConfig.load(context)
        if (!settings.enabled) {
            return ShizukuStatusSnapshot(
                enabled = false,
                backend = settings.backend,
                fallbackToShell = settings.fallbackToShell,
                state = ShizukuConnectionState.DISABLED
            )
        }

        ensureListenersRegistered(onStatusChanged)

        val appInstalled = isShizukuAppInstalled(context)
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val isPreV11 = if (binderAlive) {
            runCatching { Shizuku.isPreV11() }.getOrDefault(false)
        } else {
            false
        }
        val permissionGranted = if (binderAlive && !isPreV11) {
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                .getOrDefault(false)
        } else {
            false
        }
        val uid = if (binderAlive && permissionGranted) {
            runCatching { Shizuku.getUid() }.getOrDefault(-1)
        } else {
            -1
        }
        val shizukuVersion = if (binderAlive) {
            runCatching { Shizuku.getVersion() }.getOrDefault(-1)
        } else {
            -1
        }
        val hasRishDex = RishHelper.isAvailable(context)
        val binderReady = binderAlive && permissionGranted

        val (activeTransport, isReady, state) = when (settings.backend) {
            ShizukuBackend.BINDER -> {
                val st = when {
                    binderReady -> ShizukuConnectionState.CONNECTED
                    !appInstalled && !binderAlive -> ShizukuConnectionState.NOT_INSTALLED
                    !binderAlive -> ShizukuConnectionState.SERVICE_STOPPED
                    else -> ShizukuConnectionState.PERMISSION_REQUIRED
                }
                Triple(if (binderReady) "binder" else "none", binderReady, st)
            }
            ShizukuBackend.RISH -> {
                if (hasRishDex) {
                    val st = if (binderReady) ShizukuConnectionState.CONNECTED else ShizukuConnectionState.RISH_ONLY_READY
                    Triple("rish", true, st)
                } else {
                    val st = when {
                        !appInstalled && !binderAlive -> ShizukuConnectionState.NOT_INSTALLED
                        !binderAlive -> ShizukuConnectionState.SERVICE_STOPPED
                        !permissionGranted -> ShizukuConnectionState.PERMISSION_REQUIRED
                        else -> ShizukuConnectionState.SERVICE_STOPPED
                    }
                    Triple("none", false, st)
                }
            }
            ShizukuBackend.AUTO -> {
                when {
                    binderReady -> Triple("binder", true, ShizukuConnectionState.CONNECTED)
                    hasRishDex -> Triple("rish", true, ShizukuConnectionState.RISH_ONLY_READY)
                    !appInstalled && !binderAlive -> Triple("none", false, ShizukuConnectionState.NOT_INSTALLED)
                    !binderAlive -> Triple("none", false, ShizukuConnectionState.SERVICE_STOPPED)
                    else -> Triple("none", false, ShizukuConnectionState.PERMISSION_REQUIRED)
                }
            }
        }

        return ShizukuStatusSnapshot(
            enabled = true,
            backend = settings.backend,
            fallbackToShell = settings.fallbackToShell,
            state = state,
            appInstalled = appInstalled,
            binderAlive = binderAlive,
            permissionGranted = permissionGranted,
            uid = uid,
            shizukuVersion = shizukuVersion,
            hasRishDex = hasRishDex,
            activeTransport = activeTransport,
            isReady = isReady
        )
    }

    fun onEnabledChanged(context: Context, enabled: Boolean) {
        if (!enabled) {
            unregisterListeners()
            unbindUserService(context)
        } else {
            ensureListenersRegistered()
        }
    }

    fun requestPermission(context: Context): Boolean {
        if (!ShizukuConfig.load(context).enabled) return false
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAlive) return false
        val isPreV11 = runCatching { Shizuku.isPreV11() }.getOrDefault(false)
        if (isPreV11) return false
        val alreadyGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (alreadyGranted) return true
        return runCatching {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            true
        }.getOrElse { e ->
            Log.w(TAG, "requestPermission failed: ${e.message}")
            false
        }
    }

    fun openShizukuApp(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Unable to launch Shizuku app: ${e.message}")
            false
        }
    }

    internal fun isAuxiliaryUid(uid: Int): Boolean = ShizukuConfig.isAuxiliaryUid(uid)

    private fun buildUserServiceArgs(context: Context): Shizuku.UserServiceArgs {
        return Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShizukuShellUserService::class.java.name)
        )
            .tag("shizuku_shell")
            .daemon(false)
            .processNameSuffix("shizuku_shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    fun bindAndGetShellService(context: Context, timeoutMs: Long = 5_000L): IShizukuShellService? {
        if (!ShizukuConfig.load(context).enabled) return null

        val existing = boundShellService
        if (existing != null && runCatching { existing.asBinder().pingBinder() }.getOrDefault(false)) {
            return existing
        }

        val binderOk = runCatching {
            Shizuku.pingBinder() &&
                !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!binderOk) return null

        val latch: CountDownLatch
        val connToBind: ServiceConnection
        synchronized(serviceLock) {
            val recheck = boundShellService
            if (recheck != null && runCatching { recheck.asBinder().pingBinder() }.getOrDefault(false)) {
                return recheck
            }
            boundShellService = null
            latch = CountDownLatch(1)
            pendingLatch = latch
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val iface = if (service != null) IShizukuShellService.Stub.asInterface(service) else null
                    boundShellService = iface
                    runCatching {
                        service?.linkToDeath({
                            boundShellService = null
                        }, 0)
                    }
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    boundShellService = null
                    latch.countDown()
                }
            }
            activeConnection = conn
            connToBind = conn
        }

        val bindRequested = runCatching {
            Shizuku.bindUserService(buildUserServiceArgs(context), connToBind)
            true
        }.getOrElse { e ->
            Log.w(TAG, "bindUserService failed: ${e.message}")
            false
        }
        if (!bindRequested) {
            pendingLatch = null
            return null
        }

        runCatching { latch.await(timeoutMs.coerceIn(500L, 15_000L), TimeUnit.MILLISECONDS) }
        pendingLatch = null
        return boundShellService
    }

    fun unbindUserService(context: Context) {
        val conn: ServiceConnection?
        synchronized(serviceLock) {
            conn = activeConnection
            activeConnection = null
            boundShellService = null
            pendingLatch?.countDown()
            pendingLatch = null
        }
        if (conn != null) {
            runCatching {
                Shizuku.unbindUserService(buildUserServiceArgs(context), conn, true)
            }
        }
    }

    fun executePrivileged(
        context: Context,
        cmd: String,
        timeoutMs: Long = 30_000L,
        requestedViaRish: Boolean = false,
        cwdPath: String = File(context.filesDir, "home").absolutePath,
        gatewayPort: Int = SystemGatewayServer.readActivePort(context.filesDir),
        gatewayToken: String? = null
    ): ShizukuExecResult {
        val settings = ShizukuConfig.load(context)
        if (!settings.enabled) {
            return ShizukuExecResult(
                ok = false,
                exitCode = -1,
                stdout = "",
                stderr = "",
                durationMs = 0L,
                status = "UNAVAILABLE",
                transport = "none",
                error = "Shizuku is disabled in Settings -> Customizations"
            )
        }
        if (cmd.isBlank()) {
            return ShizukuExecResult(
                ok = false,
                exitCode = -1,
                stdout = "",
                stderr = "empty command",
                durationMs = 0L,
                status = "ERROR",
                transport = "none",
                error = "empty command"
            )
        }

        val snapshot = inspectStatus(context)
        val binderReady = snapshot.binderAlive && snapshot.permissionGranted

        return when (settings.backend) {
            ShizukuBackend.BINDER -> {
                if (!binderReady) {
                    unavailableResult(snapshot, "binder")
                } else {
                    executeViaBinder(context, cmd, timeoutMs)
                }
            }
            ShizukuBackend.RISH -> {
                executeViaRish(
                    context = context,
                    cmd = cmd,
                    timeoutMs = timeoutMs,
                    cwdPath = cwdPath,
                    gatewayPort = gatewayPort,
                    gatewayToken = gatewayToken,
                    backendId = settings.backend.id
                )
            }
            ShizukuBackend.AUTO -> {
                var binderFailure: ShizukuExecResult? = null
                if (binderReady) {
                    val binderResult = executeViaBinder(context, cmd, timeoutMs)
                    if (binderResult.status != "UNAVAILABLE") {
                        return binderResult
                    }
                    binderFailure = binderResult
                }
                if (snapshot.hasRishDex || (requestedViaRish && RishHelper.isAvailable(context))) {
                    executeViaRish(
                        context = context,
                        cmd = cmd,
                        timeoutMs = timeoutMs,
                        cwdPath = cwdPath,
                        gatewayPort = gatewayPort,
                        gatewayToken = gatewayToken,
                        backendId = settings.backend.id
                    )
                } else {
                    binderFailure ?: unavailableResult(snapshot, "none")
                }
            }
        }
    }

    private fun unavailableResult(snapshot: ShizukuStatusSnapshot, transport: String): ShizukuExecResult {
        val reason = when (snapshot.state) {
            ShizukuConnectionState.DISABLED -> "Shizuku is disabled in Settings -> Customizations"
            ShizukuConnectionState.NOT_INSTALLED -> "Shizuku app is not installed"
            ShizukuConnectionState.SERVICE_STOPPED -> "Shizuku service not running - open Shizuku app and start service"
            ShizukuConnectionState.PERMISSION_REQUIRED -> "Shizuku permission required - grant permission in Settings -> Customizations"
            else -> "Shizuku service unavailable"
        }
        return ShizukuExecResult(
            ok = false,
            exitCode = -1,
            stdout = "",
            stderr = "",
            durationMs = 0L,
            status = "UNAVAILABLE",
            transport = transport,
            error = reason
        )
    }

    private fun executeViaDirectRemoteProcess(cmd: String, timeoutMs: Long): ShizukuExecResult {
        val startMs = System.currentTimeMillis()
        val rawBinder = runCatching { Shizuku.getBinder() }.getOrNull()
        if (rawBinder == null || !runCatching { rawBinder.pingBinder() }.getOrDefault(false)) {
            return ShizukuExecResult(
                ok = false,
                exitCode = -1,
                stdout = "",
                stderr = "",
                durationMs = System.currentTimeMillis() - startMs,
                status = "UNAVAILABLE",
                transport = "binder",
                error = "Direct Shizuku binder not alive"
            )
        }

        val clampedTimeout = timeoutMs.coerceIn(1_000L, 120_000L)
        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()
        var exitCode = -1
        var status = "ERROR"
        val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)

        try {
            val service = IShizukuService.Stub.asInterface(rawBinder)
                ?: return ShizukuExecResult(
                    ok = false,
                    exitCode = -1,
                    stdout = "",
                    stderr = "",
                    durationMs = System.currentTimeMillis() - startMs,
                    status = "UNAVAILABLE",
                    transport = "binder",
                    error = "IShizukuService interface unavailable"
                )
            val remoteProc = service.newProcess(arrayOf("/system/bin/sh", "-c", cmd), null, null)
                ?: return ShizukuExecResult(
                    ok = false,
                    exitCode = -1,
                    stdout = "",
                    stderr = "",
                    durationMs = System.currentTimeMillis() - startMs,
                    status = "UNAVAILABLE",
                    transport = "binder",
                    error = "IShizukuService.newProcess returned null"
                )

            runCatching { remoteProc.outputStream?.close() }
            val inPfd = runCatching { remoteProc.inputStream }.getOrNull()
            val errPfd = runCatching { remoteProc.errorStream }.getOrNull()

            val outThread = inPfd?.let { pfd ->
                Thread({
                    drainBounded(ParcelFileDescriptor.AutoCloseInputStream(pfd), stdoutBuf)
                }, "ShizukuDirectOut").apply {
                    isDaemon = true
                    start()
                }
            }
            val errThread = errPfd?.let { pfd ->
                Thread({
                    drainBounded(ParcelFileDescriptor.AutoCloseInputStream(pfd), stderrBuf)
                }, "ShizukuDirectErr").apply {
                    isDaemon = true
                    start()
                }
            }

            val finished = try {
                remoteProc.waitForTimeout(clampedTimeout, TimeUnit.MILLISECONDS.name)
            } catch (_: Throwable) {
                remoteProc.waitFor()
                true
            }

            if (finished) {
                exitCode = runCatching { remoteProc.exitValue() }.getOrDefault(-1)
                try { outThread?.join(500) } catch (_: InterruptedException) {}
                try { errThread?.join(500) } catch (_: InterruptedException) {}
                status = if (exitCode == 0) "SUCCESS" else "FAILED"
            } else {
                runCatching { remoteProc.destroy() }
                try { outThread?.join(250) } catch (_: InterruptedException) {}
                try { errThread?.join(250) } catch (_: InterruptedException) {}
                status = "TIMEOUT"
                if (stderrBuf.isNotEmpty()) stderrBuf.append("\n")
                stderrBuf.append("[Process timed out after ${clampedTimeout}ms]")
            }
            runCatching { remoteProc.destroy() }
        } catch (t: Throwable) {
            val msg = t.message ?: t.javaClass.simpleName
            Log.w(TAG, "Direct Shizuku newProcess failed: $msg")
            return ShizukuExecResult(
                ok = false,
                exitCode = -1,
                stdout = "",
                stderr = "",
                durationMs = System.currentTimeMillis() - startMs,
                status = "UNAVAILABLE",
                transport = "binder",
                error = "Direct Shizuku exec failed: $msg",
                uid = uid
            )
        }

        val durationMs = System.currentTimeMillis() - startMs
        val stdout = stdoutBuf.toString().trim()
        val stderr = stderrBuf.toString().trim()
        return ShizukuExecResult(
            ok = status == "SUCCESS",
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
            durationMs = durationMs,
            status = status,
            transport = "binder",
            error = if (status == "SUCCESS") null else stderr.ifBlank { status },
            uid = uid
        )
    }

    private fun executeViaBinder(context: Context, cmd: String, timeoutMs: Long): ShizukuExecResult {
        val directResult = executeViaDirectRemoteProcess(cmd, timeoutMs)
        if (directResult.status != "UNAVAILABLE") {
            return directResult
        }

        val fallbackStartMs = System.currentTimeMillis()
        val service = bindAndGetShellService(context, timeoutMs = 8_000L)
        if (service == null) {
            val totalMs = directResult.durationMs + (System.currentTimeMillis() - fallbackStartMs)
            val directErr = directResult.error?.takeIf { it.isNotBlank() }
            val combinedError = if (directErr != null) {
                "$directErr; UserService bind timed out"
            } else {
                "Failed binding Shizuku shell service"
            }
            return ShizukuExecResult(
                ok = false,
                exitCode = -1,
                stdout = "",
                stderr = "",
                durationMs = totalMs,
                status = "UNAVAILABLE",
                transport = "binder",
                error = combinedError
            )
        }
        return try {
            val rawJson = service.execCommand(cmd, timeoutMs, MAX_OUTPUT_BYTES)
            val obj = JSONObject(rawJson)
            val status = obj.optString("status", "ERROR")
            val exitCode = obj.optInt("exit_code", -1)
            val stdout = obj.optString("stdout", "")
            val stderr = obj.optString("stderr", "")
            val durationMs = obj.optLong("duration_ms", 0L)
            val uid = obj.optInt("uid", -1)
            ShizukuExecResult(
                ok = status == "SUCCESS",
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr,
                durationMs = durationMs,
                status = status,
                transport = "binder",
                error = if (status == "SUCCESS") null else stderr.ifBlank { status },
                uid = uid
            )
        } catch (e: Exception) {
            boundShellService = null
            Log.w(TAG, "Binder exec failed: ${e.message}")
            ShizukuExecResult(
                ok = false,
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "Binder execution error",
                durationMs = System.currentTimeMillis() - fallbackStartMs,
                status = "ERROR",
                transport = "binder",
                error = e.message ?: "Binder execution error"
            )
        }
    }

    private fun executeViaRish(
        context: Context,
        cmd: String,
        timeoutMs: Long,
        cwdPath: String,
        gatewayPort: Int,
        gatewayToken: String?,
        backendId: String
    ): ShizukuExecResult {
        val rishBin = RishHelper.resolveRishBinary(context)
        val rishDex = RishHelper.findInternalRishDex(context)
        if (rishBin == null || !rishBin.isFile || rishDex == null) {
            return ShizukuExecResult(
                ok = false,
                exitCode = -1,
                stdout = "",
                stderr = "",
                durationMs = 0L,
                status = "UNAVAILABLE",
                transport = "rish",
                error = "rish_shizuku.dex not imported (import in Settings -> Customizations or switch backend to Auto/Binder)"
            )
        }

        val startMs = System.currentTimeMillis()
        val filesPath = context.filesDir.absolutePath
        val homeDir = File(context.filesDir, "home").apply { mkdirs() }
        val tmpDir = File(context.filesDir, "tmp").apply { mkdirs() }
        val clampedTimeout = timeoutMs.coerceIn(1_000L, 120_000L)
        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()
        var exitCode = -1
        var status = "ERROR"

        try {
            val pb = ProcessBuilder(rishBin.absolutePath, "-c", cmd)
            val cwd = File(cwdPath)
            if (cwd.isDirectory) pb.directory(cwd)

            val env = pb.environment()
            env.remove("LD_PRELOAD")
            env.remove("LD_LIBRARY_PATH")
            env.putAll(
                SystemGatewayServer.buildExecEnvironment(
                    filesPath = filesPath,
                    homePath = homeDir.absolutePath,
                    tmpPath = tmpDir.absolutePath,
                    packageName = context.packageName,
                    useRish = true,
                    gatewayPort = gatewayPort,
                    gatewayToken = gatewayToken,
                    shizukuEnabled = true,
                    shizukuBackend = backendId
                )
            )
            env["AGY_RISH_DIRECT"] = "1"

            val proc = pb.start()
            val outThread = Thread({ drainBounded(proc.inputStream, stdoutBuf) }, "RishExecOut").apply { start() }
            val errThread = Thread({ drainBounded(proc.errorStream, stderrBuf) }, "RishExecErr").apply { start() }
            try { proc.outputStream.close() } catch (_: Exception) {}

            val finished = proc.waitFor(clampedTimeout, TimeUnit.MILLISECONDS)
            if (finished) {
                exitCode = proc.exitValue()
                try { outThread.join(500) } catch (_: InterruptedException) {}
                try { errThread.join(500) } catch (_: InterruptedException) {}
                status = if (exitCode == 0) "SUCCESS" else "FAILED"
            } else {
                proc.destroyForcibly()
                try { outThread.join(250) } catch (_: InterruptedException) {}
                try { errThread.join(250) } catch (_: InterruptedException) {}
                status = "TIMEOUT"
                stderrBuf.append("\n[Process timed out after ${clampedTimeout}ms]")
            }
        } catch (e: Exception) {
            status = "ERROR"
            stderrBuf.append("\nExecution exception: ${e.message}")
        }

        val durationMs = System.currentTimeMillis() - startMs
        val stdout = stdoutBuf.toString().trim()
        val stderr = stderrBuf.toString().trim()
        return ShizukuExecResult(
            ok = status == "SUCCESS",
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
            durationMs = durationMs,
            status = status,
            transport = "rish",
            error = if (status == "SUCCESS") null else stderr.ifBlank { status },
            uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
        )
    }

    private fun drainBounded(input: InputStream, out: StringBuilder) {
        try {
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                var line: String?
                var truncated = false
                while (reader.readLine().also { line = it } != null) {
                    if (!truncated) {
                        if (out.length + (line?.length ?: 0) > MAX_OUTPUT_BYTES) {
                            out.append("\n...[output truncated at ${MAX_OUTPUT_BYTES / 1024} KiB]")
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

    fun testConnection(context: Context): ShizukuExecResult =
        executePrivileged(context = context, cmd = "id", timeoutMs = 8_000L)

    fun buildStatusJson(context: Context): JSONObject {
        val snapshot = inspectStatus(context)
        return JSONObject()
            .put("ok", true)
            .put("enabled", snapshot.enabled)
            .put("backend", snapshot.backend.id)
            .put("fallback_to_shell", snapshot.fallbackToShell)
            .put("state", snapshot.state.name.lowercase())
            .put("app_installed", snapshot.appInstalled)
            .put("binder_alive", snapshot.binderAlive)
            .put("permission_granted", snapshot.permissionGranted)
            .put("uid", snapshot.uid)
            .put("shizuku_version", snapshot.shizukuVersion)
            .put("has_rish_dex", snapshot.hasRishDex)
            .put("active_transport", snapshot.activeTransport)
            .put("ready", snapshot.isReady)
    }
}
