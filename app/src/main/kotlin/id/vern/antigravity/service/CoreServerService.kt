package id.vern.antigravity.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import id.vern.antigravity.R
import id.vern.antigravity.executor.AcpSessionPool
import id.vern.antigravity.gateway.SystemGatewayServer
import id.vern.antigravity.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class CoreServerService : Service() {

    companion object {
        private const val TAG = "CoreServerService"
        const val ACTION_START = "id.vern.antigravity.action.START"
        const val ACTION_STOP = "id.vern.antigravity.action.STOP"
        const val ACTION_RESTART = "id.vern.antigravity.action.RESTART"

        fun startService(context: Context) {
            val intent = Intent(context, CoreServerService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, CoreServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        internal fun buildDaemonEnvironment(
            filesPath: String,
            homePath: String,
            tmpPath: String,
            packageName: String,
            gatewayPort: Int? = null,
            gatewayToken: String? = null
        ): Map<String, String> {
            val map = linkedMapOf(
                "PATH" to "$filesPath/usr/bin:$filesPath/engine/bin:/system/bin",
                "LD_LIBRARY_PATH" to "$filesPath/usr/lib:/system/lib64",
                "HOME" to homePath,
                "PREFIX" to "$filesPath/usr",
                "TMPDIR" to tmpPath,
                "AGY_APPLICATION_ID" to packageName,
                "APPLICATION_ID" to packageName,
                "RISH_APPLICATION_ID" to packageName
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

    inner class LocalBinder : Binder() {
        fun getService(): CoreServerService = this@CoreServerService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Creating CoreServerService...")
        ForegroundNotification.start(this, getString(R.string.notification_engine_active))
        wakeLockHolder.acquire(this, 15_000L)

        OrphanProcessCleaner.killOrphanCoreProcesses(this)

        gatewayServer = SystemGatewayServer(this, 45161, wakeLockHolder)
        gatewayServer?.start()

        startBackgroundDaemon()
        AppLogger.d("Engine", "CoreServerService running (Gateway 127.0.0.1:${gatewayServer?.activePort ?: 45161})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stop action requested")
                dismissNotification()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                Log.i(TAG, "Restart action requested")
                stopBackgroundDaemon()
                startBackgroundDaemon()
                ForegroundNotification.update(this, getString(R.string.notification_engine_running))
            }
            else -> {
                ForegroundNotification.update(this, getString(R.string.notification_engine_running))
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.i(TAG, "Destroying CoreServerService...")
        dismissNotification()
        serviceScope.cancel()
        AcpSessionPool.invalidate("service_destroy")
        stopBackgroundDaemon()
        gatewayServer?.stop()
        wakeLockHolder.releaseAll()
        AppLogger.d("Engine", "CoreServerService stopped")
        super.onDestroy()
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
                    gatewayToken = if (includeGatewayCredentials) SystemGatewayServer.ensureGatewayToken(filesDir) else null
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

