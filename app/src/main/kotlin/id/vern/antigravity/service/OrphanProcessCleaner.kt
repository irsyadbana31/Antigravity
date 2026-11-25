package id.vern.antigravity.service

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import id.vern.antigravity.util.AppLogger
import java.io.File
import java.io.FileInputStream

/**
 * Scans `/proc` on service startup to terminate any zombie/orphan `agy`,
 * `agy-daemon`, or `language_server` processes left behind by a previous crash
 * before binding loopback ports or spawning a fresh daemon/ACP session.
 */
object OrphanProcessCleaner {
    private const val TAG = "OrphanProcessCleaner"

    fun killOrphanCoreProcesses(context: Context): Int {
        val myPid = Process.myPid()
        val filesPath = context.filesDir.absolutePath
        val pkgName = context.packageName
        val procDir = File("/proc")
        val entries = procDir.listFiles() ?: return 0

        val orphanPids = mutableListOf<Int>()
        val buf = ByteArray(1024)

        for (entry in entries) {
            val name = entry.name
            val pid = name.toIntOrNull() ?: continue
            if (pid <= 1 || pid == myPid) continue

            val cmdlineFile = File(entry, "cmdline")
            if (!cmdlineFile.canRead()) continue

            try {
                val n = FileInputStream(cmdlineFile).use { it.read(buf) }
                if (n <= 0) continue

                val sb = StringBuilder(n)
                for (i in 0 until n) {
                    val b = buf[i].toInt() and 0xFF
                    sb.append(if (b == 0) ' ' else b.toChar())
                }
                val cmdline = sb.toString()

                val belongsToApp = cmdline.contains(filesPath) || cmdline.contains(pkgName)
                if (!belongsToApp) continue

                val isCoreTarget = cmdline.contains("engine/bin/agy") ||
                    cmdline.contains("/bin/agy ") ||
                    cmdline.contains("agy-daemon") ||
                    cmdline.contains("language_server")

                if (isCoreTarget) {
                    Log.w(TAG, "Found orphan core process PID=$pid: ${cmdline.trim().take(120)}")
                    orphanPids.add(pid)
                }
            } catch (_: Exception) {
                // Process exited or permission denied; skip safely
            }
        }

        if (orphanPids.isEmpty()) return 0

        // Phase 1: graceful SIGTERM
        for (pid in orphanPids) {
            try {
                Os.kill(pid, OsConstants.SIGTERM)
            } catch (_: Exception) {}
        }

        try {
            Thread.sleep(150)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        // Phase 2: force SIGKILL any survivors
        for (pid in orphanPids) {
            if (File("/proc/$pid").exists()) {
                try {
                    Process.sendSignal(pid, Process.SIGNAL_KILL)
                } catch (_: Exception) {}
            }
        }

        AppLogger.d("Engine", "Cleaned ${orphanPids.size} orphan core process(es) on startup")
        return orphanPids.size
    }
}
