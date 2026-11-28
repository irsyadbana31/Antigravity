package id.vern.antigravity.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import id.vern.antigravity.R
import id.vern.antigravity.executor.AcpSessionPool
import id.vern.antigravity.gateway.ShizukuBackend
import id.vern.antigravity.gateway.ShizukuConfig
import id.vern.antigravity.gateway.ShizukuManager
import id.vern.antigravity.gateway.SystemGatewayServer
import id.vern.antigravity.util.AppLogger
import id.vern.antigravity.viewmodel.AntigravityViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class CoreServerService : Service() {

    companion object {
        private const val TAG = "CoreServerService"
        const val ACTION_START = "id.vern.antigravity.action.START"
        const val ACTION_STOP = "id.vern.antigravity.action.STOP"
        const val ACTION_RESTART = "id.vern.antigravity.action.RESTART"
        const val ACTION_ENSURE_ALIVE = "id.vern.antigravity.action.ENSURE_ALIVE"
        const val ACTION_AGENT_TURN_STATE = "id.vern.antigravity.action.AGENT_TURN_STATE"
        const val ACTION_SET_KEEP_ALIVE = "id.vern.antigravity.action.SET_KEEP_ALIVE"

        const val EXTRA_AGENT_ACTIVE = "extra_agent_active"
        const val EXTRA_STATUS_TEXT = "extra_status_text"
        const val EXTRA_KEEP_ALIVE = "extra_keep_alive"

        private const val ACTIVE_TURN_WAKELOCK_SLICE_MS = 5 * 60 * 1000L
        private const val ACTIVE_TURN_RENEW_INTERVAL_MS = 25_000L
        private const val IDLE_KEEPALIVE_WAKELOCK_SLICE_MS = 10 * 60 * 1000L
        private const val IDLE_KEEPALIVE_RENEW_INTERVAL_MS = 4 * 60 * 1000L
        private const val POST_TURN_GRACE_MS = 30_000L

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        @Volatile
        var isAgentTurnActive: Boolean = false
            private set

        @Volatile
        var isKeepBackgroundAliveEnabled: Boolean = false
            private set

        @Volatile
        var isWakeLockHeld: Boolean = false
            private set

        @Volatile
        var isWifiLockHeld: Boolean = false
            private set

        fun startService(context: Context) {
            dispatchServiceIntent(context, Intent(context, CoreServerService::class.java).apply {
                action = ACTION_START
            })
        }

        fun ensureServiceRunning(context: Context) {
            dispatchServiceIntent(context, Intent(context, CoreServerService::class.java).apply {
                action = if (isServiceRunning) ACTION_ENSURE_ALIVE else ACTION_START
            })
        }

        fun notifyAgentTurnState(context: Context, active: Boolean, statusText: String? = null) {
            isAgentTurnActive = active
            val intent = Intent(context, CoreServerService::class.java).apply {
                action = ACTION_AGENT_TURN_STATE
                putExtra(EXTRA_AGENT_ACTIVE, active)
                if (!statusText.isNullOrBlank()) {
                    putExtra(EXTRA_STATUS_TEXT, statusText)
                }
            }
            dispatchServiceIntent(context, intent)
        }

        fun setKeepBackgroundAlive(context: Context, enabled: Boolean) {
            isKeepBackgroundAliveEnabled = enabled
            if (!isServiceRunning && !enabled) return
            val intent = Intent(context, CoreServerService::class.java).apply {
                action = ACTION_SET_KEEP_ALIVE
                putExtra(EXTRA_KEEP_ALIVE, enabled)
            }
            dispatchServiceIntent(context, intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, CoreServerService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "stopService dispatch skipped: ${e.message}")
            }
        }

        private fun dispatchServiceIntent(context: Context, intent: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Service dispatch failed (${intent.action}): ${e.message}")
                try {
                    context.startService(intent)
                } catch (_: Exception) {}
            }
        }

        internal fun shouldHoldPersistentWakeLock(agentTurnActive: Boolean, keepAlivePref: Boolean): Boolean =
            agentTurnActive || keepAlivePref

        internal fun formatActiveTurnStatus(elapsedSec: Long, baseLabel: String): String {
            val safeSec = elapsedSec.coerceAtLeast(0L)
            val label = baseLabel.ifBlank { "Agent working" }
            if (safeSec < 5L) return "$label…"
            val mins = safeSec / 60L
            val secs = safeSec % 60L
            return if (mins > 0L) "$label (${mins}m ${secs}s)…" else "$label (${secs}s)…"
        }

        internal fun buildDaemonEnvironment(
            filesPath: String,
            homePath: String,
            tmpPath: String,
            packageName: String,
            gatewayPort: Int? = null,
            gatewayToken: String? = null,
            shizukuEnabled: Boolean = false,
            shizukuBackend: String = ShizukuBackend.AUTO.id
        ): Map<String, String> {
            val map = linkedMapOf(
                "PATH" to "$filesPath/usr/bin:$filesPath/engine/bin:/system/bin",
                "LD_LIBRARY_PATH" to "$filesPath/usr/lib:/system/lib64",
                "HOME" to homePath,
                "PREFIX" to "$filesPath/usr",
                "TMPDIR" to tmpPath,
                "AGY_APPLICATION_ID" to packageName,
                "APPLICATION_ID" to packageName,
                "RISH_APPLICATION_ID" to packageName,
                "AGY_SHIZUKU_ENABLED" to if (shizukuEnabled) "1" else "0",
                "AGY_SHIZUKU_BACKEND" to shizukuBackend
            )
            if (gatewayPort != null && gatewayPort in 1024..65535) {
                map["AGY_GATEWAY_PORT"] = gatewayPort.toString()
            }
            if (!gatewayToken.isNullOrBlank()) {
                map["AGY_GATEWAY_TOKEN"] = gatewayToken
            }
            return map
        }
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val wakeLockHolder = WakeLockHolder()
    private var gatewayServer: SystemGatewayServer? = null

    private var keepAliveJob: Job? = null
    private var postTurnGraceJob: Job? = null
    @Volatile
    private var activeTurnStartedAtMs: Long = 0L
    @Volatile
    private var activeTurnLabel: String = ""
    @Volatile
    private var keepAlivePref: Boolean = false

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var lastKnownNetworkHandle: Long? = null

    inner class LocalBinder : Binder() {
        fun getService(): CoreServerService = this@CoreServerService
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        Log.i(TAG, "Creating CoreServerService...")
        keepAlivePref = loadKeepAlivePreference()
        isKeepBackgroundAliveEnabled = keepAlivePref

        ForegroundNotification.start(this, getString(R.string.notification_engine_active))
        wakeLockHolder.acquire(this, 30_000L, withWifiLock = keepAlivePref)
        syncLockSnapshot()

        OrphanProcessCleaner.killOrphanCoreProcesses(this)
        ShizukuConfig.syncStateFile(this)

        gatewayServer = SystemGatewayServer(this, 45161, wakeLockHolder)
        gatewayServer?.start()

        registerNetworkObserver()
        startBackgroundDaemon()
        reconcileKeepAliveState()
        AppLogger.d("Engine", "CoreServerService running (Gateway 127.0.0.1:${gatewayServer?.activePort ?: 45161}, keepAlive=$keepAlivePref)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isServiceRunning = true
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stop action requested")
                isAgentTurnActive = false
                keepAliveJob?.cancel()
                postTurnGraceJob?.cancel()
                dismissNotification()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                Log.i(TAG, "Restart action requested")
                stopBackgroundDaemon()
                startBackgroundDaemon()
                ForegroundNotification.update(this, getString(R.string.notification_engine_running), isActiveTurn = isAgentTurnActive)
            }
            ACTION_AGENT_TURN_STATE -> {
                val active = intent.getBooleanExtra(EXTRA_AGENT_ACTIVE, false)
                val statusText = intent.getStringExtra(EXTRA_STATUS_TEXT)
                handleAgentTurnStateChanged(active, statusText)
            }
            ACTION_SET_KEEP_ALIVE -> {
                val enabled = intent.getBooleanExtra(EXTRA_KEEP_ALIVE, loadKeepAlivePreference())
                keepAlivePref = enabled
                isKeepBackgroundAliveEnabled = enabled
                reconcileKeepAliveState()
                AppLogger.d("Engine", "Background keep-alive updated: $enabled")
            }
            ACTION_ENSURE_ALIVE -> {
                ensureGatewayAndDaemonAlive()
                reconcileKeepAliveState()
            }
            else -> {
                if (intent == null) {
                    // System restarted a START_STICKY service after OS kill
                    AppLogger.d("Engine", "CoreServerService auto-recovered by Android (START_STICKY)")
                    ensureGatewayAndDaemonAlive()
                }
                ForegroundNotification.update(
                    this,
                    if (isAgentTurnActive) currentActiveNotificationText() else getString(R.string.notification_engine_running),
                    isActiveTurn = isAgentTurnActive
                )
                reconcileKeepAliveState()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (isAgentTurnActive || keepAlivePref) {
            Log.i(TAG, "Task removed while background work/keep-alive active; keeping CoreServerService alive")
            AppLogger.d("Engine", "Task swiped; CoreServerService staying alive (turnActive=$isAgentTurnActive, keepAlive=$keepAlivePref)")
            reconcileKeepAliveState()
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timeout callback triggered (startId=$startId, fgsType=$fgsType)")
        AppLogger.e("Engine", "Android FGS timeout received (type=$fgsType); re-promoting service safely")
        try {
            if (isAgentTurnActive || keepAlivePref) {
                ForegroundNotification.start(
                    this,
                    if (isAgentTurnActive) currentActiveNotificationText() else getString(R.string.notification_engine_running),
                    isActiveTurn = isAgentTurnActive
                )
            } else {
                dismissNotification()
                stopSelf(startId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed handling FGS timeout gracefully: ${e.message}")
            stopSelf(startId)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Destroying CoreServerService...")
        isServiceRunning = false
        isAgentTurnActive = false
        keepAliveJob?.cancel()
        postTurnGraceJob?.cancel()
        unregisterNetworkObserver()
        dismissNotification()
        serviceScope.cancel()
        AcpSessionPool.invalidate("service_destroy")
        ShizukuManager.unbindUserService(this)
        stopBackgroundDaemon()
        gatewayServer?.stop()
        wakeLockHolder.releaseAll()
        syncLockSnapshot()
        AppLogger.d("Engine", "CoreServerService stopped")
        super.onDestroy()
    }

    private fun loadKeepAlivePreference(): Boolean {
        return try {
            getSharedPreferences(AntigravityViewModel.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(AntigravityViewModel.KEY_KEEP_BACKGROUND_ALIVE, false)
        } catch (_: Exception) {
            false
        }
    }

    private fun handleAgentTurnStateChanged(active: Boolean, statusText: String?) {
        isAgentTurnActive = active
        if (active) {
            postTurnGraceJob?.cancel()
            activeTurnStartedAtMs = System.currentTimeMillis()
            activeTurnLabel = statusText?.takeIf { it.isNotBlank() }
                ?: getString(R.string.notification_agent_working)
            // Ensure foreground promotion + immediate CPU & Wi-Fi lock
            try {
                ForegroundNotification.start(this, currentActiveNotificationText(), isActiveTurn = true)
            } catch (_: Exception) {
                ForegroundNotification.update(this, currentActiveNotificationText(), isActiveTurn = true)
            }
            wakeLockHolder.acquire(this, ACTIVE_TURN_WAKELOCK_SLICE_MS, withWifiLock = true)
            syncLockSnapshot()
            startKeepAliveLoop()
        } else {
            activeTurnStartedAtMs = 0L
            activeTurnLabel = ""
            ForegroundNotification.update(
                this,
                if (keepAlivePref) getString(R.string.notification_engine_keepalive) else getString(R.string.notification_engine_running),
                isActiveTurn = false
            )
            if (keepAlivePref) {
                reconcileKeepAliveState()
            } else {
                // Keep lock briefly for post-turn git diff / title / persistence I/O, then release cleanly
                keepAliveJob?.cancel()
                keepAliveJob = null
                postTurnGraceJob?.cancel()
                postTurnGraceJob = serviceScope.launch {
                    delay(POST_TURN_GRACE_MS)
                    if (!isAgentTurnActive && !keepAlivePref) {
                        wakeLockHolder.release()
                        syncLockSnapshot()
                    }
                }
            }
        }
    }

    private fun reconcileKeepAliveState() {
        if (shouldHoldPersistentWakeLock(isAgentTurnActive, keepAlivePref)) {
            postTurnGraceJob?.cancel()
            val sliceMs = if (isAgentTurnActive) ACTIVE_TURN_WAKELOCK_SLICE_MS else IDLE_KEEPALIVE_WAKELOCK_SLICE_MS
            wakeLockHolder.acquire(this, sliceMs, withWifiLock = true)
            syncLockSnapshot()
            startKeepAliveLoop()
            if (!isAgentTurnActive) {
                ForegroundNotification.update(
                    this,
                    getString(R.string.notification_engine_keepalive),
                    isActiveTurn = false
                )
            }
        } else {
            keepAliveJob?.cancel()
            keepAliveJob = null
            if (wakeLockHolder.isPrimaryHeld) {
                wakeLockHolder.release()
            }
            syncLockSnapshot()
            ForegroundNotification.update(
                this,
                getString(R.string.notification_engine_running),
                isActiveTurn = false
            )
        }
    }

    private fun startKeepAliveLoop() {
        keepAliveJob?.cancel()
        keepAliveJob = serviceScope.launch {
            while (isActive && shouldHoldPersistentWakeLock(isAgentTurnActive, keepAlivePref)) {
                val activeNow = isAgentTurnActive
                val sliceMs = if (activeNow) ACTIVE_TURN_WAKELOCK_SLICE_MS else IDLE_KEEPALIVE_WAKELOCK_SLICE_MS
                wakeLockHolder.acquire(this@CoreServerService, sliceMs, withWifiLock = true)
                syncLockSnapshot()
                if (activeNow) {
                    ForegroundNotification.update(
                        this@CoreServerService,
                        currentActiveNotificationText(),
                        isActiveTurn = true
                    )
                }
                val intervalMs = if (activeNow) ACTIVE_TURN_RENEW_INTERVAL_MS else IDLE_KEEPALIVE_RENEW_INTERVAL_MS
                delay(intervalMs)
            }
        }
    }

    private fun currentActiveNotificationText(): String {
        val elapsedSec = if (activeTurnStartedAtMs > 0L) {
            (System.currentTimeMillis() - activeTurnStartedAtMs) / 1000L
        } else 0L
        val base = activeTurnLabel.ifBlank { getString(R.string.notification_agent_working) }
        return formatActiveTurnStatus(elapsedSec, base)
    }

    private fun syncLockSnapshot() {
        isWakeLockHeld = wakeLockHolder.isHeld
        isWifiLockHeld = wakeLockHolder.isWifiLockHeld
    }

    private fun ensureGatewayAndDaemonAlive() {
        if (gatewayServer == null) {
            gatewayServer = SystemGatewayServer(this, 45161, wakeLockHolder)
        }
        gatewayServer?.start()
        if (!isDaemonProcessAlive()) {
            startBackgroundDaemon()
        }
    }

    private fun isDaemonProcessAlive(): Boolean {
        return try {
            val pidFile = File(filesDir, "home/.gemini/antigravity-app/daemon/daemon.pid")
            if (!pidFile.isFile) return false
            val pid = pidFile.readText(Charsets.UTF_8).trim().toIntOrNull() ?: return false
            if (pid <= 0) return false
            File("/proc/$pid/cmdline").exists()
        } catch (_: Exception) {
            false
        }
    }

    private fun registerNetworkObserver() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val handle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) network.networkHandle else network.hashCode().toLong()
                    val prev = lastKnownNetworkHandle
                    lastKnownNetworkHandle = handle
                    if (prev != null && prev != handle) {
                        Log.i(TAG, "Active network switched ($prev -> $handle); notifying AcpSessionPool")
                        AppLogger.d("Network", "Network changed/reconnected; refreshing idle connections")
                        AcpSessionPool.onNetworkChanged()
                        if (isAgentTurnActive) {
                            wakeLockHolder.acquire(this@CoreServerService, ACTIVE_TURN_WAKELOCK_SLICE_MS, withWifiLock = true)
                            syncLockSnapshot()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    Log.w(TAG, "Active network lost")
                    AppLogger.d("Network", "Network connection lost; awaiting reconnect")
                }
            }
            cm.registerNetworkCallback(req, cb)
            networkCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "Failed registering network observer: ${e.message}")
        }
    }

    private fun unregisterNetworkObserver() {
        val cb = networkCallback ?: return
        networkCallback = null
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
    }

    private fun dismissNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancel(ForegroundNotification.NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "Error dismissing notification: ${e.message}")
        }
    }

    private fun createDaemonProcessBuilder(
        daemonBin: File,
        command: String,
        includeGatewayCredentials: Boolean
    ): ProcessBuilder {
        val homeDir = File(filesDir, "home").apply { mkdirs() }
        val tmpDir = File(filesDir, "tmp").apply { mkdirs() }
        val shizuku = ShizukuConfig.load(this)
        return ProcessBuilder(daemonBin.absolutePath, command).apply {
            directory(homeDir)
            redirectErrorStream(true)
            environment().remove("LD_PRELOAD")
            environment().putAll(
                buildDaemonEnvironment(
                    filesPath = filesDir.absolutePath,
                    homePath = homeDir.absolutePath,
                    tmpPath = tmpDir.absolutePath,
                    packageName = packageName,
                    gatewayPort = if (includeGatewayCredentials) SystemGatewayServer.readActivePort(filesDir) else null,
                    gatewayToken = if (includeGatewayCredentials) SystemGatewayServer.ensureGatewayToken(filesDir) else null,
                    shizukuEnabled = shizuku.enabled,
                    shizukuBackend = shizuku.backend.id
                )
            )
        }
    }

    private fun startBackgroundDaemon() {
        serviceScope.launch {
            try {
                delay(400)
                val daemonBin = File(filesDir, "usr/bin/agy-daemon")
                if (daemonBin.canExecute()) {
                    Log.i(TAG, "Starting autonomous background daemon...")
                    val proc = createDaemonProcessBuilder(daemonBin, "start", includeGatewayCredentials = true).start()
                    val exited = proc.waitFor(5, TimeUnit.SECONDS)
                    if (exited && proc.exitValue() == 0) {
                        runCatching { proc.inputStream.close() }
                        AppLogger.d("Engine", "Background agy-daemon started")
                    } else if (exited) {
                        val errText = runCatching {
                            proc.inputStream.bufferedReader().use { it.readText() }.trim()
                        }.getOrDefault("")
                        Log.w(TAG, "agy-daemon start exited ${proc.exitValue()}: $errText")
                        AppLogger.e("Engine", "agy-daemon start failed (exit ${proc.exitValue()}): ${errText.take(200)}")
                    } else {
                        runCatching { proc.inputStream.close() }
                        Log.w(TAG, "agy-daemon start did not exit within 5s")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch agy-daemon: ${e.message}")
            }
        }
    }

    private fun stopBackgroundDaemon() {
        try {
            val daemonBin = File(filesDir, "usr/bin/agy-daemon")
            if (daemonBin.canExecute()) {
                Log.i(TAG, "Stopping background daemon...")
                val p = createDaemonProcessBuilder(daemonBin, "stop", includeGatewayCredentials = false).start()
                try {
                    if (!p.waitFor(1500, TimeUnit.MILLISECONDS)) {
                        p.destroyForcibly()
                    }
                } finally {
                    runCatching { p.inputStream.close() }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping daemon: ${e.message}")
        }
    }
}
