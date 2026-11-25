package id.vern.antigravity.nativebridge

import android.util.Log
import id.vern.antigravity.ui.components.MarkdownElement

object RustTurboBridge {
    private const val TAG = "RustTurboBridge"

    const val PATCH_BIT_FACCESSAT2 = 1 shl 0
    const val PATCH_BIT_FCHMODAT2 = 1 shl 1
    const val PATCH_BIT_PTY_SHELL = 1 shl 2
    const val PATCH_BIT_RESOLV = 1 shl 3

    val isLoaded: Boolean = try {
        System.loadLibrary("antigravity_turbo")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "Native Rust turbo engine unavailable, using Kotlin fallback: ${t.message}")
        false
    }

    @JvmStatic
    private external fun nativeParseMarkdown(content: String): String?

    @JvmStatic
    private external fun nativeQuickTailFile(path: String, maxBytes: Int): String?

    @JvmStatic
    private external fun nativePatchCoreBinary(path: String): Int

    @JvmStatic
    private external fun nativeStripAnsi(input: String): String?

    @JvmStatic
    private external fun nativeParseAcpLine(line: String): String?

    @JvmStatic
    private external fun nativeEngineVersion(): String?

    fun statusSummary(): String {
        if (!isLoaded) return "Kotlin fallback (native lib not loaded)"
        return try {
            nativeEngineVersion() ?: "antigravity-turbo-rust (active)"
        } catch (_: Throwable) {
            "Kotlin fallback (JNI error)"
        }
    }

    fun quickTailFile(path: String, maxBytes: Int = 262_144): String? {
        if (!isLoaded) return null
        return try {
            nativeQuickTailFile(path, maxBytes)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Patches the 4 Android/glibc binary fixes in a single SIMD-accelerated pass.
     * Returns bitmask (>= 0) on success, or null if native lib unavailable / I/O error.
     */
    fun patchCoreBinary(path: String): Int? {
        if (!isLoaded) return null
        return try {
            val res = nativePatchCoreBinary(path)
            if (res >= 0) res else null
        } catch (t: Throwable) {
            Log.w(TAG, "nativePatchCoreBinary failed, falling back to Kotlin: ${t.message}")
            null
        }
    }

    fun stripAnsi(input: String): String? {
        if (!isLoaded) return null
        return try {
            nativeStripAnsi(input)
        } catch (_: Throwable) {
            null
        }
    }

    fun parseAcpLine(line: String): String? {
        if (!isLoaded) return null
        return try {
            nativeParseAcpLine(line)
        } catch (_: Throwable) {
            null
        }
    }

    fun parseMarkdownNative(content: String): List<MarkdownElement>? {
        if (!isLoaded) return null
        val encoded = try {
            nativeParseMarkdown(content) ?: return null
        } catch (t: Throwable) {
            Log.w(TAG, "nativeParseMarkdown failed, falling back to Kotlin: ${t.message}")
            return null
        }
        return decodeMarkdownRecords(encoded)
    }

    private fun decodeMarkdownRecords(encoded: String): List<MarkdownElement>? {
        val result = ArrayList<MarkdownElement>(16)
        var cursor = 0
        val total = encoded.length

        while (cursor < total) {
            val headerEnd = encoded.indexOf('\n', cursor)
            if (headerEnd == -1) return null
            val header = encoded.substring(cursor, headerEnd)
            cursor = headerEnd + 1

            val firstSpace = header.indexOf(' ')
            val secondSpace = if (firstSpace != -1) header.indexOf(' ', firstSpace + 1) else -1
            if (firstSpace != 1 || secondSpace == -1) return null

            val tag = header[0]
            val metaLen = header.substring(2, secondSpace).toIntOrNull() ?: return null
            val bodyLen = header.substring(secondSpace + 1).toIntOrNull() ?: return null

            if (cursor + metaLen + bodyLen > total) return null
            val meta = if (metaLen > 0) encoded.substring(cursor, cursor + metaLen) else ""
            cursor += metaLen
            val body = if (bodyLen > 0) encoded.substring(cursor, cursor + bodyLen) else ""
            cursor += bodyLen

            when (tag) {
                'H' -> result.add(MarkdownElement.Heading(meta.toIntOrNull() ?: 1, body))
                'C' -> result.add(MarkdownElement.CodeBlock(meta, body))
                'L' -> result.add(MarkdownElement.ListItem(meta, body))
                'Q' -> result.add(MarkdownElement.BlockQuote(body))
                'D' -> result.add(MarkdownElement.Divider)
                'P' -> result.add(MarkdownElement.Paragraph(body))
                else -> return null
            }
        }
        return result
    }
}
