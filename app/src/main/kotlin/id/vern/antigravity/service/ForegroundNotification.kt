package id.vern.antigravity.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import id.vern.antigravity.R
import id.vern.antigravity.ui.MainActivity

object ForegroundNotification {

    private const val TAG = "ForegroundNotification"
    const val NOTIFICATION_ID = 1001
    const val CHANNEL_ID = "agy_core_service_channel"

    fun start(service: Service, statusText: String? = null, isActiveTurn: Boolean = false): Boolean {
        val manager = service.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                service.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = service.getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val text = statusText ?: service.getString(R.string.notification_engine_active)
        val notification = buildNotification(service, text, isActiveTurn)
        return promoteForeground(service, notification)
    }

    private fun promoteForeground(service: Service, notification: Notification): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14/15/16: specialUse avoids the 6-hour dataSync FGS hard timeout.
            try {
                service.startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                return true
            } catch (e: Exception) {
                Log.w(TAG, "specialUse FGS start failed (${e.message}); falling back to dataSync")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                service.startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                return true
            } catch (e: Exception) {
                Log.w(TAG, "dataSync FGS start failed (${e.message}); falling back to untyped startForeground")
            }
        }
        return try {
            service.startForeground(NOTIFICATION_ID, notification)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Untyped FGS start failed (${e.message})")
            false
        }
    }

    fun update(context: Context, statusText: String? = null, isActiveTurn: Boolean = false) {
        val text = statusText ?: context.getString(R.string.notification_engine_running)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(context, text, isActiveTurn))
    }

    fun buildNotification(
        context: Context,
        statusText: String,
        isActiveTurn: Boolean = false
    ): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(context, CoreServerService::class.java).apply {
            action = CoreServerService.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            101,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val restartIntent = Intent(context, CoreServerService::class.java).apply {
            action = CoreServerService.ACTION_RESTART
        }
        val restartPendingIntent = PendingIntent.getService(
            context,
            102,
            restartIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val iconRes = context.resources.getIdentifier("ic_notification", "drawable", context.packageName)
            .takeIf { it != 0 } ?: android.R.drawable.stat_notify_sync

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(iconRes)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                R.drawable.ic_action_stop,
                context.getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            .addAction(
                R.drawable.ic_action_restart,
                context.getString(R.string.notification_action_restart),
                restartPendingIntent
            )

        if (isActiveTurn) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(0, 0, false)
        }

        return builder.build()
    }
}
