package org.videolan.vlc.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class MatroskaTextSubtitleDemuxerTest {

    @Test
    fun extract_blockGroupUtf8InsideSegment() {
        val file = File.createTempFile("sub", ".mkv")
        file.deleteOnExit()
        file.writeBytes(minimalMkv(trackNumber = 2, codec = "S_TEXT/UTF8", payload = "Hello world".toByteArray(), relTime = 500, duration = 1000))
        val cues = MatroskaTextSubtitleDemuxer.extract(file, targetTrackNumber = 2)
        assertEquals(1, cues.size)
        assertEquals("Hello world", cues[0].text)
        assertEquals(500L, cues[0].startTimeMs)
        assertEquals(1500L, cues[0].endTimeMs)
    }

    @Test
    fun extract_assDropsStyleAndKeepsText() {
        val file = File.createTempFile("sub", ".mkv")
        file.deleteOnExit()
        val payload = "0,0,Default,,0,0,0,,Hello {\\i1}ASS{\\i0}".toByteArray()
        file.writeBytes(minimalMkv(trackNumber = 3, codec = "S_TEXT/ASS", payload = payload, relTime = 200, duration = 800))
        val cues = MatroskaTextSubtitleDemuxer.extract(file, targetTrackNumber = 3)
        assertEquals(1, cues.size)
        assertEquals("Hello ASS", cues[0].text)
        assertEquals(200L, cues[0].startTimeMs)
    }

    @Test
    fun extract_ordinalSelectsTrackWhenIdDiffers() {
        val file = File.createTempFile("sub", ".mkv")
        file.deleteOnExit()
        file.writeBytes(minimalMkv(trackNumber = 4, codec = "S_TEXT/UTF8", payload = "Only".toByteArray(), relTime = 0, duration = 1000))
        val result = MatroskaTextSubtitleDemuxer.extractResult(file, trackNumberHints = intArrayOf(99), subtitleOrdinal = 0)
        assertTrue(result is MatroskaTextSubtitleDemuxer.Result.Cues)
        assertEquals("Only", (result as MatroskaTextSubtitleDemuxer.Result.Cues).cues[0].text)
    }

    private fun minimalMkv(
        trackNumber: Int,
        codec: String,
        payload: ByteArray,
        relTime: Int,
        duration: Int,
    ): ByteArray {
        val trackEntry = element(0xAE, concat(
            element(0xD7, byteArrayOf(trackNumber.toByte())),
            element(0x83, byteArrayOf(0x11)),
            element(0x86, codec.toByteArray()),
        ))
        val tracks = element(0x1654AE6B, trackEntry)
        val info = element(0x1549A966, element(0x2AD7B1, byteArrayOf(0x0F, 0x42, 0x40)))
        val block = concat(
            byteArrayOf((0x80 or trackNumber).toByte()),
            byteArrayOf((relTime shr 8).toByte(), relTime.toByte()),
            byteArrayOf(0),
            payload,
        )
        val group = element(0xA0, concat(
            element(0xA1, block),
            element(0x9B, byteArrayOf((duration shr 8).toByte(), duration.toByte())),
        ))
        val cluster = element(0x1F43B675, concat(element(0xE7, byteArrayOf(0)), group))
        val segment = element(0x18538067, concat(info, tracks, cluster))
        val header = element(0x1A45DFA3, element(0x4282, "matroska".toByteArray()))
        return concat(header, segment)
    }

    private fun element(id: Int, data: ByteArray): ByteArray = concat(encodeId(id), encodeSize(data.size.toLong()), data)

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun encodeId(id: Int): ByteArray = when {
        id <= 0xFF -> byteArrayOf(id.toByte())
        id <= 0xFFFF -> byteArrayOf((id shr 8).toByte(), id.toByte())
        id <= 0xFFFFFF -> byteArrayOf((id shr 16).toByte(), (id shr 8).toByte(), id.toByte())
        else -> byteArrayOf((id shr 24).toByte(), (id shr 16).toByte(), (id shr 8).toByte(), id.toByte())
    }

    private fun encodeSize(size: Long): ByteArray {
        if (size <= 0x7F) return byteArrayOf((0x80 or size.toInt()).toByte())
        if (size <= 0x3FFF) return byteArrayOf((0x40 or (size shr 8).toInt()).toByte(), size.toByte())
        return byteArrayOf(
            (0x20 or (size shr 16).toInt()).toByte(),
            (size shr 8).toByte(),
            size.toByte(),
        )
    }
}
