package id.vern.antigravity.util

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.util.Locale

data class OemBackgroundProfile(
    val vendorKey: String,
    val displayName: String,
    val hasAggressiveFreezer: Boolean,
    val guideEn: String,
    val guideId: String
)

object PermissionHelper {

    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun hasManageStoragePermission(context: Context): Boolean {
        return hasStoragePermission(context)
    }

    fun getRequiredRuntimePermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.toTypedArray()
    }

    fun hasAllRuntimePermissions(context: Context): Boolean {
        for (perm in getRequiredRuntimePermissions()) {
            if (ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    fun hasAllRequiredPermissions(context: Context): Boolean {
        return hasStoragePermission(context)
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasBatteryOptimizationExclusion(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        return pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }

    fun isBackgroundRestricted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.isBackgroundRestricted ?: false
        } else {
            false
        }
    }

    fun detectOemBackgroundProfile(manufacturer: String = Build.MANUFACTURER.orEmpty()): OemBackgroundProfile {
        val norm = manufacturer.trim().lowercase(Locale.US)
        return when {
            norm.contains("infinix") || norm.contains("tecno") || norm.contains("itel") || norm.contains("transsion") ->
                OemBackgroundProfile(
                    vendorKey = "transsion",
                    displayName = "Infinix / Tecno / XOS",
                    hasAggressiveFreezer = true,
                    guideEn = "XOS freezes background apps (Usf_Hiber) even with a foreground service. Open App Battery / Battery Lab and set Antigravity Turbo to Unrestricted / Don't freeze, and lock it in Recents.",
                    guideId = "XOS membekukan aplikasi latar belakang (Usf_Hiber) meski ada Foreground Service. Buka Pengaturan Baterai Aplikasi / Battery Lab, atur Antigravity Turbo ke Tidak dibatasi (Unrestricted) / Jangan bekukan, dan kunci di Recents."
                )
            norm.contains("xiaomi") || norm.contains("redmi") || norm.contains("poco") ->
                OemBackgroundProfile(
                    vendorKey = "xiaomi",
                    displayName = "Xiaomi / HyperOS / MIUI",
                    hasAggressiveFreezer = true,
                    guideEn = "Set Battery saver to No restrictions, enable Autostart, and lock Antigravity Turbo in Recents.",
                    guideId = "Atur Penghemat baterai ke Tanpa pembatasan, aktifkan Mulai otomatis, dan kunci Antigravity Turbo di Recents."
                )
            norm.contains("samsung") ->
                OemBackgroundProfile(
                    vendorKey = "samsung",
                    displayName = "Samsung One UI",
                    hasAggressiveFreezer = true,
                    guideEn = "Set Battery to Unrestricted and add Antigravity Turbo to Never auto sleeping apps.",
                    guideId = "Atur Baterai ke Tidak dibatasi dan masukkan Antigravity Turbo ke Aplikasi yang tidak pernah tidur."
                )
            norm.contains("oppo") || norm.contains("realme") || norm.contains("oneplus") ->
                OemBackgroundProfile(
                    vendorKey = "oplus",
                    displayName = "ColorOS / Realme UI / OxygenOS",
                    hasAggressiveFreezer = true,
                    guideEn = "Enable Allow background activity and Allow auto-launch under App Info -> Battery usage.",
                    guideId = "Aktifkan Izinkan aktivitas latar belakang dan Mulai otomatis di Info Aplikasi -> Penggunaan baterai."
                )
            norm.contains("vivo") || norm.contains("iqoo") ->
                OemBackgroundProfile(
                    vendorKey = "vivo",
                    displayName = "FuntouchOS / OriginOS",
                    hasAggressiveFreezer = true,
                    guideEn = "Enable High background power consumption and Autostart for Antigravity Turbo.",
                    guideId = "Aktifkan Konsumsi daya latar belakang tinggi dan Mulai otomatis untuk Antigravity Turbo."
                )
            norm.contains("huawei") || norm.contains("honor") ->
                OemBackgroundProfile(
                    vendorKey = "huawei",
                    displayName = "EMUI / MagicOS",
                    hasAggressiveFreezer = true,
                    guideEn = "Switch App launch to Manage manually and enable Auto-launch, Secondary launch, and Run in background.",
                    guideId = "Ubah Peluncuran aplikasi ke Kelola manual dan aktifkan Mulai otomatis & Jalankan di latar belakang."
                )
            else ->
                OemBackgroundProfile(
                    vendorKey = "generic",
                    displayName = "Android",
                    hasAggressiveFreezer = false,
                    guideEn = "Set Battery usage to Unrestricted so long-running agent sessions stay active with the screen off.",
                    guideId = "Atur Penggunaan baterai ke Tidak dibatasi agar sesi agent yang panjang tetap berjalan saat layar mati."
                )
        }
    }

    fun createBatteryOptimizationIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    fun createOemBackgroundSettingsIntents(context: Context): List<Intent> {
        val pkg = context.packageName
        val profile = detectOemBackgroundProfile()
        val intents = mutableListOf<Intent>()

        when (profile.vendorKey) {
            "transsion" -> {
                // On Infinix/Tecno XOS 14-16, IGNORE_BATTERY_OPTIMIZATION_SETTINGS resolves directly
                // to com.android.settings/.Settings$AppBatteryUsageActivity (where per-app Unrestricted
                // / anti-freeze mode is configured), followed by Transsion Battery Lab and App Info.
                intents.add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                intents.add(Intent("android.settings.TRAN_BATTERY_LAB"))
                intents.add(Intent("com.transsion.batterylab.other_power_save_settings"))
            }
            "xiaomi" -> {
                intents.add(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
                intents.add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            "oplus" -> {
                intents.add(Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")))
                intents.add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            "vivo" -> {
                intents.add(Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")))
                intents.add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            else -> {
                intents.add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        intents.add(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$pkg")
            }
        )
        return intents
    }

    fun openBatteryOptimizationRequest(context: Context): Boolean {
        val reqIntent = createBatteryOptimizationIntent(context).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(reqIntent)
            true
        } catch (_: Exception) {
            openOemBackgroundSettings(context)
        }
    }

    fun openOemBackgroundSettings(context: Context): Boolean {
        val pm = context.packageManager
        for (candidate in createOemBackgroundSettingsIntents(context)) {
            candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                if (candidate.resolveActivity(pm) != null) {
                    context.startActivity(candidate)
                    return true
                }
            } catch (_: Exception) {}
        }
        return false
    }

    fun createManageStorageIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }
}
