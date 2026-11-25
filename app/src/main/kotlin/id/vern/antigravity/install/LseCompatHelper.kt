package id.vern.antigravity.install

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Runtime compatibility layer for ARMv8.0-A CPUs (e.g. Snapdragon 660/680/685)
 * that lack hardware LSE atomic instructions (`FEAT_LSE` / `atomics` HWCAP).
 *
 * On modern ARMv8.1-A+ CPUs that expose `atomics` in `/proc/cpuinfo`,
 * [needsLsePreload] returns `false` so `liblse_compat.so` is never preloaded
 * (literally 0% overhead).
 *
 * Note: `liblse_compat.so` is a glibc shared library; it MUST ONLY be passed
 * to `ld-linux-aarch64.so.1 --preload` and NEVER exported in `LD_PRELOAD`
 * (which would break Bionic child processes).
 */
object LseCompatHelper {
    private const val TAG = "LseCompatHelper"
    private const val ASSET_PATH = "lse/liblse_compat.so"
    const val LIB_FILENAME = "liblse_compat.so"

    private val hardwareLseSupported: Boolean by lazy {
        detectHardwareLse()
    }

    fun cpuHasHardwareLse(): Boolean = hardwareLseSupported

    fun needsLsePreload(): Boolean {
        val isArm64 = Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) }
        return isArm64 && !hardwareLseSupported
    }

    private fun detectHardwareLse(): Boolean {
        return try {
            val cpuInfo = File("/proc/cpuinfo")
            if (!cpuInfo.isFile || !cpuInfo.canRead()) {
                return true
            }
            val tokenRegex = Regex("""\batomics\b""")
            cpuInfo.useLines { lines ->
                var sawFeatures = false
                for (line in lines) {
                    if (line.startsWith("Features", ignoreCase = true)) {
                        sawFeatures = true
                        if (tokenRegex.containsMatchIn(line)) {
                            return@useLines true
                        }
                    }
                }
                // If no Features line was present (non-standard procfs), fail open (no preload)
                !sawFeatures
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed detecting CPU LSE features: ${e.message}")
            true
        }
    }

    fun deployLseCompat(context: Context, engineDir: File): File? {
        val targetDir = File(engineDir, "glibc/lib").apply { mkdirs() }
        val targetFile = File(targetDir, LIB_FILENAME)
        return try {
            val assetBytes = context.assets.open(ASSET_PATH).use { it.readBytes() }
            if (!targetFile.isFile || targetFile.length() != assetBytes.size.toLong()) {
                val tmpFile = File(targetDir, "$LIB_FILENAME.tmp")
                FileOutputStream(tmpFile).use { it.write(assetBytes) }
                if (!tmpFile.renameTo(targetFile)) {
                    tmpFile.copyTo(targetFile, overwrite = true)
                    tmpFile.delete()
                }
                Log.i(TAG, "Deployed $LIB_FILENAME (${assetBytes.size} bytes) to ${targetFile.absolutePath}")
            }
            targetFile.setReadable(true, false)
            targetFile.setExecutable(true, false)
            try { Os.chmod(targetFile.absolutePath, 493) } catch (_: Exception) {}
            targetFile
        } catch (e: Exception) {
            Log.w(TAG, "Failed deploying $LIB_FILENAME: ${e.message}")
            if (targetFile.isFile) targetFile else null
        }
    }

    fun buildGlibcLoaderCommand(
        ldBin: File,
        libraryPaths: String,
        glibcLibDir: File,
        targetBin: File,
        args: List<String> = emptyList()
    ): MutableList<String> {
        val cmd = mutableListOf(
            ldBin.absolutePath,
            "--library-path",
            libraryPaths
        )
        if (needsLsePreload()) {
            val lseLib = File(glibcLibDir, LIB_FILENAME)
            if (lseLib.isFile) {
                cmd.add("--preload")
                cmd.add(lseLib.absolutePath)
            }
        }
        cmd.add(targetBin.absolutePath)
        cmd.addAll(args)
        return cmd
    }
}
