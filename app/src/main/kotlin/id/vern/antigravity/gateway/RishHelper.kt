package id.vern.antigravity.gateway

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Rootless Shizuku `rish` (uid=2000 ADB shell) locator and helper.
 *
 * Avoids touching external storage automatically; scans Download or imports via SAF
 * only when explicitly requested by the user.
 */
object RishHelper {
    private const val TAG = "RishHelper"
    private const val DEX_NAME = "rish_shizuku.dex"
    private const val MAX_DEX_BYTES = 16L * 1024L * 1024L
    private val DEX_MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0A) // "dex\n"

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

    private fun internalDexCandidates(context: Context): List<File> {
        val files = context.filesDir
        return listOf(
            File(files, "usr/bin/$DEX_NAME"),
            File(files, "usr/share/rish/$DEX_NAME"),
            File(files, "runtime/bin/$DEX_NAME")
        )
    }

    /**
     * Checks only app-private paths for `rish_shizuku.dex` with zero external storage I/O.
     */
    fun findInternalRishDex(context: Context): File? {
        val existing = internalDexCandidates(context).firstOrNull { it.isFile && it.length() > 0L }
            ?: return null
        enforceReadOnlyDex(existing)
        return existing
    }

    /**
     * Locates `rish_shizuku.dex` in app-private dirs. Only scans `$HOME` and `Download`
     * when [allowDownloadImport] is explicitly true.
     */
    fun ensureRishDex(context: Context, allowDownloadImport: Boolean = false): File? {
        findInternalRishDex(context)?.let { return it }
        if (!allowDownloadImport) return null

        val files = context.filesDir
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
            if (dest.exists()) {
                dest.setWritable(true, true)
                dest.delete()
            }
            source.copyTo(dest, overwrite = true)
            enforceReadOnlyDex(dest)
            Log.i(TAG, "Imported $DEX_NAME from ${source.absolutePath} to ${dest.absolutePath}")
            dest
        } catch (e: Exception) {
            Log.w(TAG, "Failed importing $DEX_NAME: ${e.message}")
            null
        }
    }

    fun importRishDexFromDownloads(context: Context): File? =
        ensureRishDex(context, allowDownloadImport = true)

    fun importRishDexFromUri(context: Context, uri: Uri): Result<File> {
        val destDir = File(context.filesDir, "usr/share/rish").apply { mkdirs() }
        val tmpFile = File(destDir, "$DEX_NAME.tmp")
        val destFile = File(destDir, DEX_NAME)

        return try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return Result.failure(IllegalStateException("Unable to open selected file"))
            var totalBytes = 0L
            val magic = ByteArray(4)
            var magicRead = 0

            input.use { stream ->
                FileOutputStream(tmpFile).use { out ->
                    val buffer = ByteArray(32_768)
                    var read: Int
                    while (stream.read(buffer).also { read = it } != -1) {
                        totalBytes += read
                        if (totalBytes > MAX_DEX_BYTES) {
                            throw IllegalArgumentException("Selected file exceeds 16 MiB limit")
                        }
                        if (magicRead < magic.size) {
                            val toCopy = minOf(read, magic.size - magicRead)
                            System.arraycopy(buffer, 0, magic, magicRead, toCopy)
                            magicRead += toCopy
                        }
                        out.write(buffer, 0, read)
                    }
                }
            }

            if (totalBytes <= 0L || magicRead < DEX_MAGIC.size || !magic.contentEquals(DEX_MAGIC)) {
                tmpFile.delete()
                return Result.failure(IllegalArgumentException("Invalid DEX file (expected rish_shizuku.dex)"))
            }

            if (destFile.exists()) {
                destFile.setWritable(true, true)
                destFile.delete()
            }
            if (!tmpFile.renameTo(destFile)) {
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
            }
            enforceReadOnlyDex(destFile)
            Log.i(TAG, "Imported $DEX_NAME from SAF URI to ${destFile.absolutePath} ($totalBytes bytes)")
            Result.success(destFile)
        } catch (e: Exception) {
            try { tmpFile.delete() } catch (_: Exception) {}
            Log.w(TAG, "Failed importing $DEX_NAME from URI: ${e.message}")
            Result.failure(e)
        }
    }

    fun clearRishDex(context: Context): Boolean {
        var removedAny = false
        for (candidate in internalDexCandidates(context)) {
            if (candidate.exists()) {
                try {
                    candidate.setWritable(true, true)
                    if (candidate.delete()) {
                        removedAny = true
                    }
                } catch (_: Exception) {}
            }
        }
        return removedAny
    }

    fun isAvailable(context: Context): Boolean {
        val rishBin = resolveRishBinary(context) ?: return false
        if (!rishBin.canExecute()) {
            rishBin.setExecutable(true, false)
            try { Os.chmod(rishBin.absolutePath, 493) } catch (_: Throwable) {}
        }
        val dex = findInternalRishDex(context)
        return rishBin.canExecute() && dex != null && dex.isFile
    }

    private fun enforceReadOnlyDex(dex: File) {
        try {
            dex.setWritable(false, false)
            Os.chmod(dex.absolutePath, 256) // 0400 required on Android 14+ (API 34+)
        } catch (_: Throwable) {}
    }
}
