package id.vern.antigravity

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log
import id.vern.antigravity.gateway.ShizukuConfig
import id.vern.antigravity.nativebridge.RustTurboBridge
import id.vern.antigravity.ui.CrashHandlerActivity
import id.vern.antigravity.util.StorageMaintenance
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class AntigravityApp : Application() {

    companion object {
        private const val TAG = "AntigravityApp"
        lateinit var instance: AntigravityApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (isAuxiliaryProcess()) {
            return
        }
        setupCrashHandler()
        StorageMaintenance.scheduleStartupCleanup(this)
        Log.i(TAG, "Turbo native engine: ${RustTurboBridge.statusSummary()}")
        Log.i(TAG, "Antigravity App initialized with crash handler.")
    }

    private fun isAuxiliaryProcess(): Boolean {
        if (ShizukuConfig.isAuxiliaryUid(Process.myUid())) return true
        val processName = currentProcessName()
        return processName.endsWith(":crash_handler") || processName.endsWith(":shizuku_shell")
    }

    private fun currentProcessName(): String {
        return try {
            val pid = Process.myPid()
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun setupCrashHandler() {
        if (isAuxiliaryProcess()) return

        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            val sw = StringWriter()
            exception.printStackTrace(PrintWriter(sw))
            val rawTrace = sw.toString().trim()
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            val crashDetails = buildString {
                appendLine("[Aplikasi Terhenti (Force Close)]")
                appendLine("Timestamp: $time")
                appendLine("Thread: ${thread.name}")
                appendLine("Exception: ${exception.javaClass.name}")
                appendLine("Message: ${exception.localizedMessage ?: "No message"}")
                appendLine()
                appendLine("Stack Trace:")
                appendLine(rawTrace)
            }.trim()

            Log.e(TAG, "Antigravity force closed on thread: ${thread.name}", exception)

            try {
                File(filesDir, "crash.log").writeText(crashDetails)
            } catch (e: Exception) {}

            try {
                val prefs = getSharedPreferences("crash_recovery", Context.MODE_PRIVATE)
                prefs.edit().putString("pending_crash_log", crashDetails).commit()
            } catch (e: Exception) {}

            try {
                CrashHandlerActivity.start(applicationContext, crashDetails)
                Thread.sleep(1200)
            } catch (e: Exception) {
                Log.e(TAG, "Failed launching CrashHandlerActivity", e)
            }

            Process.killProcess(Process.myPid())
            exitProcess(1)
        }
    }
}

