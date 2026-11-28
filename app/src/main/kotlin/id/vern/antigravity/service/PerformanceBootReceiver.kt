package id.vern.antigravity.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import id.vern.antigravity.viewmodel.AntigravityViewModel

/**
 * Re-applies Performance Mode keep-alive service and Shizuku system settings after device reboot
 * (some settings like standby bucket or phantom process flags can reset across reboots).
 */
class PerformanceBootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "PerformanceBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(AntigravityViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        val perfEnabled = prefs.getBoolean(PerformanceModeController.KEY_PERFORMANCE_MODE, false)
        val keepAlive = prefs.getBoolean(AntigravityViewModel.KEY_KEEP_BACKGROUND_ALIVE, false)
        if (!perfEnabled && !keepAlive) {
            return
        }

        Log.i(TAG, "Received $action (perf=$perfEnabled, keepAlive=$keepAlive); restoring service")
        try {
            CoreServerService.startService(appCtx)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to start CoreServerService on $action: ${e.message}")
        }
    }
}
