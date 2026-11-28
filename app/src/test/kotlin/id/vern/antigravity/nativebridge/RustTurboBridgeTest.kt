package id.vern.antigravity.nativebridge

import id.vern.antigravity.ui.components.MarkdownElement
import id.vern.antigravity.ui.components.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RustTurboBridgeTest {

    @Test
    fun computeTopologyFromFreqs_selectsAllNonLittleCoresOnTriClusterDimensity() {
        // Real Infinix GT 30 Pro (Dimensity 8350 Ultimate): 4x A510 (2.2G) + 3x A715 (3.2G) + 1x Prime (3.35G)
        val freqs = listOf(
            0 to 2_200_000L,
            1 to 2_200_000L,
            2 to 2_200_000L,
            3 to 2_200_000L,
            4 to 3_200_000L,
            5 to 3_200_000L,
            6 to 3_200_000L,
            7 to 3_350_000L
        )
        val topo = RustTurboBridge.computeTopologyFromFreqs(freqs)
        assertTrue(topo.hasBigCluster)
        assertEquals(0xFFL, topo.allCoresMask)
        assertEquals(0xF0L, topo.bigCoresMask) // Must select ALL 4 A715 cores (4-7), NOT just cpu7!
        assertEquals(listOf(4, 5, 6, 7), topo.bigCoreIds)
        assertEquals("CPU 4-7 (4/8 · 2.20–3.35 GHz)", topo.formatClusterSummary())
    }

    @Test
    fun computeTopologyFromFreqs_handlesHomogeneousSmpGracefully() {
        val smp = listOf(0 to 2_000_000L, 1 to 2_000_000L, 2 to 2_000_000L, 3 to 2_000_000L)
        val topo = RustTurboBridge.computeTopologyFromFreqs(smp)
        assertFalse(topo.hasBigCluster)
        assertEquals(0x0FL, topo.allCoresMask)
        assertEquals(0L, topo.bigCoresMask)
        assertEquals("SMP (4 cores)", topo.formatClusterSummary())
    }

    @Test
    fun parseCpuTopologyString_roundTripsNativeFormat() {
        val raw = "all_mask=ff;big_mask=f0;min_khz=2200000;max_khz=3350000;big_cores=4,5,6,7;total_cpus=8"
        val topo = RustTurboBridge.parseCpuTopologyString(raw)
        assertEquals(0xFFL, topo.allCoresMask)
        assertEquals(0xF0L, topo.bigCoresMask)
        assertEquals(listOf(4, 5, 6, 7), topo.bigCoreIds)
        assertEquals(8, topo.totalCpus)
    }

    @Test
    fun decodeAcpBatchRecords_decodesUnicodeMultilineToolAndResponseRecords() {
        val thought = "analisa 🚀\nbaris 2"
        val msg = "selesai"
        val encoded = buildString {
            append("T 0 ${thought.length}\n").append(thought)
            append("M 1 ${msg.length}\n").append(msg)
            append("C 2 0\n")
            append("R 4 2\n42")
        }
        val decoded = RustTurboBridge.decodeAcpBatchRecords(encoded)
        assertNotNull(decoded)
        assertEquals(
            listOf(
                NativeAcpBatchRecord('T', 0, thought),
                NativeAcpBatchRecord('M', 1, msg),
                NativeAcpBatchRecord('C', 2, ""),
                NativeAcpBatchRecord('R', 4, "42")
            ),
            decoded
        )
    }

    @Test
    fun decodeAcpBatchRecords_rejectsCorruptFraming() {
        assertNull(RustTurboBridge.decodeAcpBatchRecords("T 0 99\nshort"))
        assertNull(RustTurboBridge.decodeAcpBatchRecords("BADHEADER"))
    }

    @Test
    fun parseProcessStatsString_parsesNativeProcSummary() {
        val raw = """
            pid=12345
            name=agy
            state=S (sleeping)
            rss_kb=94208
            threads=18
            cpus_allowed=4-7
            affinity_mask=f0
        """.trimIndent()
        val stats = RustTurboBridge.parseProcessStatsString(raw)
        assertNotNull(stats)
        assertEquals(12345, stats!!.pid)
        assertEquals("agy", stats.name)
        assertEquals(94208L, stats.rssKb)
        assertEquals(18, stats.threads)
        assertEquals("4-7", stats.cpusAllowedList)
        assertEquals(0xF0L, stats.affinityMask)
    }

    @Test
    fun markdownRecordDecoder_matchesKotlinParserGoldenCorpus() {
        // Golden corpus covering headings, streaming partial headings, tables with escapes & emoji,
        // task lists, bullets, numbered lists, quotes, dividers, code blocks, and paragraphs.
        val corpus = """
            ### Ringkasan Utama

            Paragraf pertama #tag dan ###bukan heading

            | Kolom | Rata | Nilai |
            | :--- | :---: | ---: |
            | a \| b | ok 🚀 | 100 |

            - [ ] Belum selesai
            * [x] Sudah selesai 🚀
            - Bullet biasa
            1. Langkah satu

            > Baris kutipan satu
            > Baris kutipan dua

            ---

            ```kotlin
            val x = 42
            ```
        """.trimIndent()

        // Expected native wire encoding matching rust_turbo/src/lib.rs append_record format
        fun rec(tag: Char, meta: String, body: String): String =
            "$tag ${meta.length} ${body.length}\n$meta$body"

        val tableBody = "Kolom\u001FRata\u001FNilai\u001Ea | b\u001Fok 🚀\u001F100"
        val nativeWire = buildString {
            append(rec('H', "3", "Ringkasan Utama"))
            append(rec('P', "", "Paragraf pertama #tag dan ###bukan heading"))
            append(rec('T', "L,C,R", tableBody))
            append(rec('X', "0", "Belum selesai"))
            append(rec('X', "1", "Sudah selesai 🚀"))
            append(rec('L', "•", "Bullet biasa"))
            append(rec('L', "1.", "Langkah satu"))
            append(rec('Q', "", "Baris kutipan satu Baris kutipan dua"))
            append(rec('D', "", ""))
            append(rec('C', "kotlin", "val x = 42"))
        }

        val fromKotlin = parseMarkdown(corpus)
        val fromNativeDecoder = RustTurboBridge.decodeMarkdownRecords(nativeWire)
        assertEquals(fromKotlin, fromNativeDecoder)
        assertTrue(fromKotlin.any { it is MarkdownElement.Table })
        assertTrue(fromKotlin.any { it is MarkdownElement.TaskItem && it.checked })
    }
}
