package id.vern.antigravity.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object StorageMaintenance {
    private const val TAG = "StorageMaintenance"
    private const val MAX_LOG_FILES_TO_KEEP = 5
    private const val CRASH_MAX_AGE_MS = 3L * 24L * 60L * 60L * 1000L
    private const val NODE_CACHE_MAX_BYTES = 20L * 1024L * 1024L

    private val hasRun = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun scheduleStartupCleanup(context: Context) {
        if (!hasRun.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        scope.launch {
            try {
                delay(5_000L)
                runCleanup(appContext)
            } catch (e: Exception) {
                Log.w(TAG, "Background storage cleanup skipped: ${e.message}")
            }
        }
    }

    private fun runCleanup(context: Context) {
        val filesDir = context.filesDir
        val cliDir = File(filesDir, "home/.gemini/antigravity-cli")
        var freedBytes = 0L

        // 1. Rotate CLI logs (keep active symlink target + newest MAX_LOG_FILES_TO_KEEP)
        val logDir = File(cliDir, "log")
        if (logDir.isDirectory) {
            val activeLogCanonical = try {
                File(cliDir, "cli.log").canonicalPath
            } catch (_: Exception) {
                null
            }
            val logs = logDir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
            logs.drop(MAX_LOG_FILES_TO_KEEP).forEach { file ->
                val isCurrent = try {
                    activeLogCanonical != null && file.canonicalPath == activeLogCanonical
                } catch (_: Exception) {
                    false
                }
                if (!isCurrent) {
                    val len = file.length()
                    if (file.delete()) freedBytes += len
                }
            }
        }

        // 2. Prune empty or stale crash logs
        val crashDir = File(cliDir, "crashes")
        if (crashDir.isDirectory) {
            val cutoff = System.currentTimeMillis() - CRASH_MAX_AGE_MS
            crashDir.listFiles()?.forEach { file ->
                if (file.isFile && (file.length() == 0L || file.lastModified() < cutoff)) {
                    val len = file.length()
                    if (file.delete()) freedBytes += len
                }
            }
        }

        // 3. Prune oversized node compile cache in tmp/
        val nodeCache = File(filesDir, "tmp/node-compile-cache")
        if (nodeCache.isDirectory) {
            val size = directorySize(nodeCache)
            if (size > NODE_CACHE_MAX_BYTES) {
                if (nodeCache.deleteRecursively()) {
                    freedBytes += size
                }
            }
        }

        if (freedBytes > 0L) {
            Log.i(TAG, "Auto-cleanup reclaimed ${freedBytes / 1024} KB")
        }
    }

    private fun directorySize(dir: File): Long {
        var total = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile) total += f.length()
        }
        return total
    }
}
