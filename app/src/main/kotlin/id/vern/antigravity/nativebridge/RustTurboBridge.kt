package id.vern.antigravity.nativebridge

import android.util.Log
import id.vern.antigravity.ui.components.MarkdownElement
import java.io.File
import java.util.concurrent.atomic.AtomicLong

data class NativeAcpBatchRecord(
    val tag: Char,
    val lineIndex: Int,
    val body: String
)

data class CpuTopologyInfo(
    val allCoresMask: Long = 0L,
    val bigCoresMask: Long = 0L,
    val minFreqKhz: Long = 0L,
    val maxFreqKhz: Long = 0L,
    val bigCoreIds: List<Int> = emptyList(),
    val totalCpus: Int = 0
) {
    val hasBigCluster: Boolean
        get() = bigCoresMask != 0L && bigCoreIds.isNotEmpty()

    fun formatClusterSummary(): String {
        if (!hasBigCluster) {
            return if (totalCpus > 0) "SMP ($totalCpus cores)" else "Unavailable"
        }
        val range = formatCoreRange(bigCoreIds)
        val minGhz = String.format(java.util.Locale.US, "%.2f", minFreqKhz / 1_000_000.0)
        val maxGhz = String.format(java.util.Locale.US, "%.2f", maxFreqKhz / 1_000_000.0)
        return "CPU $range (${bigCoreIds.size}/$totalCpus · $minGhz–$maxGhz GHz)"
    }

    companion object {
        internal fun formatCoreRange(ids: List<Int>): String {
            if (ids.isEmpty()) return "-"
            val sorted = ids.distinct().sorted()
            val ranges = mutableListOf<String>()
            var start = sorted.first()
            var prev = start
            for (i in 1 until sorted.size) {
                val cur = sorted[i]
                if (cur == prev + 1) {
                    prev = cur
                } else {
                    ranges.add(if (start == prev) "$start" else "$start-$prev")
                    start = cur
                    prev = cur
                }
            }
            ranges.add(if (start == prev) "$start" else "$start-$prev")
            return ranges.joinToString(",")
        }
    }
}

data class NativeProcessStats(
    val pid: Int,
    val name: String,
    val state: String,
    val rssKb: Long,
    val threads: Int,
    val cpusAllowedList: String,
    val affinityMask: Long
) {
    val rssMb: Double
        get() = rssKb / 1024.0
}

data class NativeEngineStatsSnapshot(
    val isLoaded: Boolean,
    val engineVersion: String,
    val nativeCalls: Long,
    val fallbackCalls: Long
) {
    val totalCalls: Long
        get() = nativeCalls + fallbackCalls

    val fallbackRatePercent: Double
        get() = if (totalCalls <= 0L) 0.0 else (fallbackCalls.toDouble() * 100.0) / totalCalls.toDouble()
}

object RustTurboBridge {
    private const val TAG = "RustTurboBridge"

    const val PATCH_BIT_FACCESSAT2 = 1 shl 0
    const val PATCH_BIT_FCHMODAT2 = 1 shl 1
    const val PATCH_BIT_PTY_SHELL = 1 shl 2
    const val PATCH_BIT_RESOLV = 1 shl 3

    private val nativeCallCount = AtomicLong(0L)
    private val fallbackCallCount = AtomicLong(0L)

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
    private external fun nativeParseAcpBatch(batch: String): String?

    @JvmStatic
    private external fun nativeDetectCpuTopology(): String?

    @JvmStatic
    private external fun nativeSetProcessAffinity(pid: Int, coreMask: Long): Int

    @JvmStatic
    private external fun nativeGetProcessAffinity(pid: Int): Long

    @JvmStatic
    private external fun nativeReadProcessStats(pid: Int): String?

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

    fun statsSnapshot(): NativeEngineStatsSnapshot {
        return NativeEngineStatsSnapshot(
            isLoaded = isLoaded,
            engineVersion = statusSummary(),
            nativeCalls = nativeCallCount.get(),
            fallbackCalls = fallbackCallCount.get()
        )
    }

    internal fun resetStatsForTesting() {
        nativeCallCount.set(0L)
        fallbackCallCount.set(0L)
    }

    fun quickTailFile(path: String, maxBytes: Int = 262_144): String? {
        if (!isLoaded) {
            fallbackCallCount.incrementAndGet()
            return null
        }
        return try {
            val out = nativeQuickTailFile(path, maxBytes)
            if (out != null) nativeCallCount.incrementAndGet() else fallbackCallCount.incrementAndGet()
            out
        } catch (_: Throwable) {
            fallbackCallCount.incrementAndGet()
            null
        }
    }

    /**
     * Patches the 4 Android/glibc binary fixes in a single SIMD-accelerated pass.
     * Returns bitmask (>= 0) on success, or null if native lib unavailable / I/O error.
     */
    fun patchCoreBinary(path: String): Int? {
        if (!isLoaded) {
            fallbackCallCount.incrementAndGet()
            return null
        }
        return try {
            val res = nativePatchCoreBinary(path)
            if (res >= 0) {
                nativeCallCount.incrementAndGet()
                res
            } else {
                fallbackCallCount.incrementAndGet()
                null
            }
        } catch (t: Throwable) {
            fallbackCallCount.incrementAndGet()
            Log.w(TAG, "nativePatchCoreBinary failed, falling back to Kotlin: ${t.message}")
            null
        }
    }

    fun stripAnsi(input: String): String? {
        if (!isLoaded) {
            fallbackCallCount.incrementAndGet()
            return null
        }
        return try {
            val out = nativeStripAnsi(input)
            if (out != null) nativeCallCount.incrementAndGet() else fallbackCallCount.incrementAndGet()
            out
        } catch (_: Throwable) {
            fallbackCallCount.incrementAndGet()
            null
        }
    }

    fun parseAcpLine(line: String): String? {
        if (!isLoaded) {
            fallbackCallCount.incrementAndGet()
            return null
        }
        return try {
            val out = nativeParseAcpLine(line)
            if (out != null) nativeCallCount.incrementAndGet() else fallbackCallCount.incrementAndGet()
            out
        } catch (_: Throwable) {
            fallbackCallCount.incrementAndGet()
            null
        }
    }

    fun parseAcpBatch(lines: List<String>): List<NativeAcpBatchRecord>? {
        if (lines.isEmpty()) return emptyList()
        if (!isLoaded) {
            fallbackCallCount.incrementAndGet()
            return null
        }
        val payload = lines.joinToString("\n")
        val encoded = try {
            nativeParseAcpBatch(payload)
        } catch (t: Throwable) {
            fallbackCallCount.incrementAndGet()
            Log.w(TAG, "nativeParseAcpBatch failed, falling back to Kotlin: ${t.message}")
            return null
        } ?: run {
            fallbackCallCount.incrementAndGet()
            return null
        }
        val decoded = decodeAcpBatchRecords(encoded)
        if (decoded != null) {
            nativeCallCount.incrementAndGet()
        } else {
            fallbackCallCount.incrementAndGet()
        }
        return decoded
    }

    fun detectCpuTopology(): CpuTopologyInfo {
        if (isLoaded) {
            try {
                val raw = nativeDetectCpuTopology()
                if (!raw.isNullOrBlank()) {
                    nativeCallCount.incrementAndGet()
                    return parseCpuTopologyString(raw)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "nativeDetectCpuTopology failed, falling back to Kotlin: ${t.message}")
            }
        }
        fallbackCallCount.incrementAndGet()
        return detectCpuTopologyKotlinFallback()
    }

    /**
     * Pins ALL OS threads of [pid] to [coreMask] via `sched_setaffinity`.
     * Returns number of threads pinned (>= 1 on success, 0 if rejected by cpuset/permissions, -1 on error/unavailable).
     */
    fun setProcessAffinity(pid: Int, coreMask: Long): Int {
        if (pid <= 0 || coreMask <= 0L) return -1
        if (!isLoaded) {
            fallbackCallCount.incrementAndGet()
            return -1
        }
        return try {
            val count = nativeSetProcessAffinity(pid, coreMask)
            if (count >= 0) nativeCallCount.incrementAndGet() else fallbackCallCount.incrementAndGet()
            count
        } catch (t: Throwable) {
            fallbackCallCount.incrementAndGet()
            Log.w(TAG, "nativeSetProcessAffinity(pid=$pid) failed: ${t.message}")
            -1
        }
    }

    fun getProcessAffinity(pid: Int): Long {
        if (pid <= 0 || !isLoaded) return -1L
        return try {
            nativeGetProcessAffinity(pid)
        } catch (_: Throwable) {
            -1L
        }
    }

    fun readProcessStats(pid: Int): NativeProcessStats? {
        if (pid <= 0) return null
        if (isLoaded) {
            try {
                val raw = nativeReadProcessStats(pid)
                if (!raw.isNullOrBlank()) {
                    nativeCallCount.incrementAndGet()
                    return parseProcessStatsString(raw)
                }
            } catch (_: Throwable) {}
        }
        fallbackCallCount.incrementAndGet()
        return readProcessStatsKotlinFallback(pid)
    }

    fun parseMarkdownNative(content: String): List<MarkdownElement>? {
        if (!isLoaded) {
            fallbackCallCount.incrementAndGet()
            return null
        }
        val encoded = try {
            nativeParseMarkdown(content)
        } catch (t: Throwable) {
            fallbackCallCount.incrementAndGet()
            Log.w(TAG, "nativeParseMarkdown failed, falling back to Kotlin: ${t.message}")
            return null
        } ?: run {
            fallbackCallCount.incrementAndGet()
            return null
        }
        val decoded = decodeMarkdownRecords(encoded)
        if (decoded != null) {
            nativeCallCount.incrementAndGet()
        } else {
            fallbackCallCount.incrementAndGet()
        }
        return decoded
    }

    internal fun decodeAcpBatchRecords(encoded: String): List<NativeAcpBatchRecord>? {
        if (encoded.isEmpty()) return emptyList()
        val result = ArrayList<NativeAcpBatchRecord>(8)
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
            val lineIndex = header.substring(2, secondSpace).toIntOrNull() ?: return null
            val bodyLen = header.substring(secondSpace + 1).toIntOrNull() ?: return null

            if (lineIndex < 0 || bodyLen < 0 || cursor + bodyLen > total) return null
            val body = if (bodyLen > 0) encoded.substring(cursor, cursor + bodyLen) else ""
            cursor += bodyLen
            result.add(NativeAcpBatchRecord(tag = tag, lineIndex = lineIndex, body = body))
        }
        return result
    }

    internal fun parseCpuTopologyString(raw: String): CpuTopologyInfo {
        val kv = HashMap<String, String>()
        for (part in raw.split(';')) {
            val eq = part.indexOf('=')
            if (eq > 0) {
                kv[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
            }
        }
        val allMask = kv["all_mask"]?.toLongOrNull(16) ?: 0L
        val bigMask = kv["big_mask"]?.toLongOrNull(16) ?: 0L
        val minKhz = kv["min_khz"]?.toLongOrNull() ?: 0L
        val maxKhz = kv["max_khz"]?.toLongOrNull() ?: 0L
        val totalCpus = kv["total_cpus"]?.toIntOrNull() ?: 0
        val bigCoreIds = kv["big_cores"]
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?: emptyList()
        return CpuTopologyInfo(
            allCoresMask = allMask,
            bigCoresMask = bigMask,
            minFreqKhz = minKhz,
            maxFreqKhz = maxKhz,
            bigCoreIds = bigCoreIds,
            totalCpus = totalCpus
        )
    }

    internal fun computeTopologyFromFreqs(freqs: List<Pair<Int, Long>>): CpuTopologyInfo {
        val valid = freqs.filter { it.first in 0..63 && it.second > 0L }.sortedBy { it.first }
        if (valid.isEmpty()) return CpuTopologyInfo()
        var allMask = 0L
        var minKhz = Long.MAX_VALUE
        var maxKhz = 0L
        for ((cpu, khz) in valid) {
            allMask = allMask or (1L shl cpu)
            if (khz < minKhz) minKhz = khz
            if (khz > maxKhz) maxKhz = khz
        }
        if (maxKhz <= minKhz) {
            return CpuTopologyInfo(
                allCoresMask = allMask,
                bigCoresMask = 0L,
                minFreqKhz = minKhz,
                maxFreqKhz = maxKhz,
                bigCoreIds = emptyList(),
                totalCpus = valid.size
            )
        }
        var bigMask = 0L
        val bigIds = mutableListOf<Int>()
        for ((cpu, khz) in valid) {
            if (khz > minKhz) {
                bigMask = bigMask or (1L shl cpu)
                bigIds.add(cpu)
            }
        }
        return CpuTopologyInfo(
            allCoresMask = allMask,
            bigCoresMask = bigMask,
            minFreqKhz = minKhz,
            maxFreqKhz = maxKhz,
            bigCoreIds = bigIds,
            totalCpus = valid.size
        )
    }

    private fun detectCpuTopologyKotlinFallback(): CpuTopologyInfo {
        return try {
            val cpuRoot = File("/sys/devices/system/cpu")
            val dirs = cpuRoot.listFiles() ?: return CpuTopologyInfo()
            val freqs = mutableListOf<Pair<Int, Long>>()
            for (d in dirs) {
                val name = d.name
                if (!name.startsWith("cpu")) continue
                val id = name.removePrefix("cpu").toIntOrNull() ?: continue
                if (id !in 0..63) continue
                val f = File(d, "cpufreq/cpuinfo_max_freq")
                val khz = runCatching { f.readText().trim().toLongOrNull() }.getOrNull() ?: continue
                if (khz > 0L) freqs.add(id to khz)
            }
            computeTopologyFromFreqs(freqs)
        } catch (_: Exception) {
            CpuTopologyInfo()
        }
    }

    internal fun parseProcessStatsString(raw: String): NativeProcessStats? {
        val kv = HashMap<String, String>()
        for (line in raw.lines()) {
            val eq = line.indexOf('=')
            if (eq > 0) {
                kv[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
            }
        }
        val pid = kv["pid"]?.toIntOrNull() ?: return null
        return NativeProcessStats(
            pid = pid,
            name = kv["name"].orEmpty(),
            state = kv["state"].orEmpty(),
            rssKb = kv["rss_kb"]?.toLongOrNull() ?: 0L,
            threads = kv["threads"]?.toIntOrNull() ?: 0,
            cpusAllowedList = kv["cpus_allowed"].orEmpty(),
            affinityMask = kv["affinity_mask"]?.toLongOrNull(16) ?: -1L
        )
    }

    private fun readProcessStatsKotlinFallback(pid: Int): NativeProcessStats? {
        return try {
            val file = File("/proc/$pid/status")
            if (!file.isFile || !file.canRead()) return null
            var name = ""
            var state = ""
            var rssKb = 0L
            var threads = 0
            var cpusAllowed = ""
            file.useLines { lines ->
                for (line in lines) {
                    when {
                        line.startsWith("Name:") -> name = line.removePrefix("Name:").trim()
                        line.startsWith("State:") -> state = line.removePrefix("State:").trim()
                        line.startsWith("VmRSS:") -> rssKb = line.removePrefix("VmRSS:").trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: 0L
                        line.startsWith("Threads:") -> threads = line.removePrefix("Threads:").trim().toIntOrNull() ?: 0
                        line.startsWith("Cpus_allowed_list:") -> cpusAllowed = line.removePrefix("Cpus_allowed_list:").trim()
                    }
                }
            }
            NativeProcessStats(
                pid = pid,
                name = name,
                state = state,
                rssKb = rssKb,
                threads = threads,
                cpusAllowedList = cpusAllowed,
                affinityMask = -1L
            )
        } catch (_: Exception) {
            null
        }
    }

    internal fun decodeMarkdownRecords(encoded: String): List<MarkdownElement>? {
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
                'X' -> result.add(MarkdownElement.TaskItem(meta == "1", body))
                'T' -> {
                    val rawRows = body.split('\u001E')
                    if (rawRows.isEmpty()) return null
                    val headers = rawRows[0].split('\u001F')
                    val aligns = if (meta.isBlank()) {
                        List(headers.size) { 'L' }
                    } else {
                        meta.split(',').map { it.firstOrNull() ?: 'L' }
                    }
                    val dataRows = if (rawRows.size > 1) {
                        rawRows.drop(1).map { it.split('\u001F') }
                    } else {
                        emptyList()
                    }
                    result.add(MarkdownElement.Table(headers, aligns, dataRows))
                }
                'Q' -> result.add(MarkdownElement.BlockQuote(body))
                'D' -> result.add(MarkdownElement.Divider)
                'P' -> result.add(MarkdownElement.Paragraph(body))
                else -> return null
            }
        }
        return result
    }
}
