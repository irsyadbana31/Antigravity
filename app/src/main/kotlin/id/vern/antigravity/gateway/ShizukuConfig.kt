package id.vern.antigravity.gateway

import android.content.Context
import android.system.Os
import java.io.File

enum class ShizukuBackend(val id: String) {
    AUTO("auto"),
    BINDER("binder"),
    RISH("rish");

    companion object {
        fun fromId(raw: String?): ShizukuBackend {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.id == normalized } ?: AUTO
        }
    }
}

data class ShizukuSettings(
    val enabled: Boolean = false,
    val backend: ShizukuBackend = ShizukuBackend.AUTO,
    val fallbackToShell: Boolean = false
)

/**
 * Persistent opt-in Shizuku settings backed by SharedPreferences and mirrored to
 * `files/logs/shizuku.conf` so overlay CLI tools (`rish`, `agy-notify`, `agy-daemon`)
 * respect the toggle without Android context.
 */
object ShizukuConfig {
    const val PREFS_NAME = "antigravity_prefs"
    const val KEY_SHIZUKU_ENABLED = "ag_shizuku_enabled"
    const val KEY_SHIZUKU_BACKEND = "ag_shizuku_backend"
    const val KEY_SHIZUKU_FALLBACK_SHELL = "ag_shizuku_fallback_shell"
    const val CONF_RELATIVE_PATH = "logs/shizuku.conf"

    private val confLock = Any()

    fun isAuxiliaryUid(uid: Int): Boolean = uid == 2000 || uid == 0

    fun formatConf(settings: ShizukuSettings): String {
        val enabledFlag = if (settings.enabled) "1" else "0"
        val fallbackFlag = if (settings.fallbackToShell) "1" else "0"
        return buildString {
            append("SHIZUKU_ENABLED=").append(enabledFlag).append('\n')
            append("SHIZUKU_BACKEND=").append(settings.backend.id).append('\n')
            append("SHIZUKU_FALLBACK_SHELL=").append(fallbackFlag).append('\n')
        }
    }

    fun parseConf(text: String): ShizukuSettings {
        var enabled = false
        var backend = ShizukuBackend.AUTO
        var fallback = false
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#')) continue
            val eqIdx = line.indexOf('=')
            if (eqIdx <= 0) continue
            val key = line.substring(0, eqIdx).trim()
            val value = line.substring(eqIdx + 1).trim().trim('"', '\'')
            when (key) {
                "SHIZUKU_ENABLED" -> enabled = parseBoolFlag(value)
                "SHIZUKU_BACKEND" -> backend = ShizukuBackend.fromId(value)
                "SHIZUKU_FALLBACK_SHELL" -> fallback = parseBoolFlag(value)
            }
        }
        return ShizukuSettings(
            enabled = enabled,
            backend = backend,
            fallbackToShell = fallback
        )
    }

    private fun parseBoolFlag(raw: String): Boolean {
        return when (raw.trim().lowercase()) {
            "1", "true", "yes", "on" -> true
            else -> false
        }
    }

    fun readFromFilesDir(filesDir: File): ShizukuSettings {
        val confFile = File(filesDir, CONF_RELATIVE_PATH)
        if (!confFile.isFile || !confFile.canRead()) {
            return ShizukuSettings()
        }
        return try {
            parseConf(confFile.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            ShizukuSettings()
        }
    }

    fun writeToFilesDir(filesDir: File, settings: ShizukuSettings) {
        val formatted = formatConf(settings)
        synchronized(confLock) {
            val confFile = File(filesDir, CONF_RELATIVE_PATH)
            try {
                if (confFile.isFile && confFile.readText(Charsets.UTF_8) == formatted) {
                    enforceOwnerOnlyPermissions(confFile)
                    return
                }
                confFile.parentFile?.mkdirs()
                val tmpFile = File(confFile.parentFile, "shizuku.conf.tmp")
                tmpFile.writeText(formatted, Charsets.UTF_8)
                enforceOwnerOnlyPermissions(tmpFile)
                if (!tmpFile.renameTo(confFile)) {
                    confFile.writeText(formatted, Charsets.UTF_8)
                    tmpFile.delete()
                }
                enforceOwnerOnlyPermissions(confFile)
            } catch (_: Throwable) {}
        }
    }

    fun load(context: Context): ShizukuSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_SHIZUKU_ENABLED, false)
        val backend = ShizukuBackend.fromId(prefs.getString(KEY_SHIZUKU_BACKEND, ShizukuBackend.AUTO.id))
        val fallback = prefs.getBoolean(KEY_SHIZUKU_FALLBACK_SHELL, false)
        return ShizukuSettings(
            enabled = enabled,
            backend = backend,
            fallbackToShell = fallback
        )
    }

    fun save(context: Context, settings: ShizukuSettings) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_SHIZUKU_ENABLED, settings.enabled)
            .putString(KEY_SHIZUKU_BACKEND, settings.backend.id)
            .putBoolean(KEY_SHIZUKU_FALLBACK_SHELL, settings.fallbackToShell)
            .apply()
        writeToFilesDir(context.filesDir, settings)
    }

    fun syncStateFile(context: Context): ShizukuSettings {
        val current = load(context)
        writeToFilesDir(context.filesDir, current)
        return current
    }

    private fun enforceOwnerOnlyPermissions(file: File) {
        try {
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
            Os.chmod(file.absolutePath, 384) // 0600
        } catch (_: Throwable) {}
    }
}
