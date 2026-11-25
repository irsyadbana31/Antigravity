package id.vern.antigravity.gateway

import android.content.Context
import android.os.Environment
import android.system.Os
import android.util.Log
import java.io.File

/**
 * Rootless Shizuku `rish` (uid=2000 ADB shell) locator and helper.
 *
 * Requires no Shizuku API library dependency; executes the bundled `usr/bin/rish`
 * wrapper which invokes `app_process` with `rish_shizuku.dex`.
 */
object RishHelper {
    private const val TAG = "RishHelper"
    private const val DEX_NAME = "rish_shizuku.dex"

    fun resolveRishBinary(context: Context): File? {
        val files = context.filesDir
        val candidates = listOf(
            File(files, "usr/bin/rish"),
            File(files, "runtime/bin/rish"),
            File(files, "bin/rish")
        )
        return candidates.firstOrNull { it.isFile && it.canExecute() }
            ?: candidates.firstOrNull { it.isFile }
    }

    /**
     * Locates `rish_shizuku.dex` in app-private dirs, or auto-imports it into
     * `files/usr/share/rish/rish_shizuku.dex` (with Android 14+ `chmod 400`)
     * if the user exported it from the Shizuku app to Downloads or `$HOME`.
     */
    fun ensureRishDex(context: Context): File? {
        val files = context.filesDir
        val internalCandidates = listOf(
            File(files, "usr/bin/$DEX_NAME"),
            File(files, "usr/share/rish/$DEX_NAME"),
            File(files, "runtime/bin/$DEX_NAME")
        )
        val existing = internalCandidates.firstOrNull { it.isFile && it.length() > 0L }
        if (existing != null) {
            enforceReadOnlyDex(existing)
            return existing
        }

        val downloadDir = try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        } catch (_: Exception) {
            null
        }
        val importCandidates = buildList {
            add(File(files, "home/$DEX_NAME"))
            if (downloadDir != null) {
                add(File(downloadDir, DEX_NAME))
                add(File(downloadDir, "rish/$DEX_NAME"))
            }
        }
        val source = importCandidates.firstOrNull { it.isFile && it.canRead() && it.length() > 0L }
            ?: return null

        val dest = File(files, "usr/share/rish/$DEX_NAME")
        return try {
            dest.parentFile?.mkdirs()
            if (dest.exists()) dest.delete()
            source.copyTo(dest, overwrite = true)
            enforceReadOnlyDex(dest)
            Log.i(TAG, "Imported $DEX_NAME from ${source.absolutePath} to ${dest.absolutePath}")
            dest
        } catch (e: Exception) {
            Log.w(TAG, "Failed importing $DEX_NAME: ${e.message}")
            null
        }
    }

    fun isAvailable(context: Context): Boolean {
        val rishBin = resolveRishBinary(context) ?: return false
        if (!rishBin.canExecute()) {
            rishBin.setExecutable(true, false)
            try { Os.chmod(rishBin.absolutePath, 493) } catch (_: Exception) {}
        }
        val dex = ensureRishDex(context)
        return rishBin.canExecute() && dex != null && dex.isFile
    }

    private fun enforceReadOnlyDex(dex: File) {
        try {
            dex.setWritable(false, false)
            Os.chmod(dex.absolutePath, 256) // 0400 required on Android 14+ (API 34+)
        } catch (_: Exception) {}
    }
}
