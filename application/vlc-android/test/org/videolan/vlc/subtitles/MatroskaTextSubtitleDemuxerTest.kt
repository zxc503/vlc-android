package org.videolan.vlc.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class MatroskaTextSubtitleDemuxerTest {

    @Test
    fun extract_readsSimpleUtf8Block() {
        val file = File.createTempFile("test-sub", ".mkv")
        file.deleteOnExit()
        writeMinimalMkv(file, trackNumber = 2, text = "Hello world")
        val cues = MatroskaTextSubtitleDemuxer.extract(file, targetTrackNumber = 2)
        assertTrue(cues.isNotEmpty())
        assertEquals("Hello world", cues.first().text)
    }

    /** Builds a tiny valid-enough MKV with one text subtitle block. */
    private fun writeMinimalMkv(file: File, trackNumber: Int, text: String) {
        RandomAccessFile(file, "rw").use { raf ->
            fun writeEbml(id: Int, data: ByteArray) {
                raf.write(encodeId(id))
                raf.write(encodeSize(data.size.toLong()))
                raf.write(data)
            }
            fun writeUIntElement(id: Int, value: Long) {
                val bytes = byteArrayOf(value.toByte())
                writeEbml(id, bytes)
            }
            fun writeStringElement(id: Int, value: String) {
                writeEbml(id, value.toByteArray(Charsets.UTF_8))
            }

            // EBML header (minimal)
            writeEbml(0x1A45DFA3, byteArrayOf(
                0x42.toByte(), 0x86.toByte(), 0x81.toByte(), 0x01, // EBMLVersion = 1
            ))

            val segmentPayload = java.io.ByteArrayOutputStream()
            fun segWrite(id: Int, data: ByteArray) {
                segmentPayload.write(encodeId(id))
                segmentPayload.write(encodeSize(data.size.toLong()))
                segmentPayload.write(data)
            }

            // Tracks
            val trackEntry = java.io.ByteArrayOutputStream()
            fun teWrite(id: Int, data: ByteArray) {
                trackEntry.write(encodeId(id))
                trackEntry.write(encodeSize(data.size.toLong()))
                trackEntry.write(data)
            }
            teWrite(0xD7, byteArrayOf(trackNumber.toByte()))
            teWrite(0x83, byteArrayOf(0x11)) // subtitle
            teWrite(0x86, "S_TEXT/UTF8".toByteArray(Charsets.UTF_8))
            val trackEntryBytes = trackEntry.toByteArray()
            val tracksContent = java.io.ByteArrayOutputStream().apply {
                write(encodeId(0xAE))
                write(encodeSize(trackEntryBytes.size.toLong()))
                write(trackEntryBytes)
            }.toByteArray()
            segWrite(0x1654AE6B, tracksContent)

            // Cluster with one SimpleBlock at t=1000ms
            val blockPayload = buildSimpleBlock(trackNumber, relativeTimeMs = 0, text)
            val clusterContent = java.io.ByteArrayOutputStream().apply {
                write(encodeId(0xE7))
                write(encodeSize(1))
                write(0x03.toByte()) // timecode 3 -> with scale typically ms; cluster uses ms in real mkv
                write(encodeId(0xA3))
                write(encodeSize(blockPayload.size.toLong()))
                write(blockPayload)
            }.toByteArray()
            // Use timecode 1000 (0x03E8) properly
            val clusterFixed = java.io.ByteArrayOutputStream().apply {
                write(encodeId(0xE7))
                write(encodeSize(2))
                write(0x03.toByte())
                write(0xE8.toByte())
                write(encodeId(0xA3))
                write(encodeSize(blockPayload.size.toLong()))
                write(blockPayload)
            }.toByteArray()
            segWrite(0x1F43B675, clusterFixed)

            writeEbml(0x18538067, segmentPayload.toByteArray())
        }
    }

    private fun buildSimpleBlock(trackNumber: Int, relativeTimeMs: Int, text: String): ByteArray {
        val payload = text.toByteArray(Charsets.UTF_8)
        val trackVint = encodeVint(trackNumber.toLong())
        val out = java.io.ByteArrayOutputStream()
        out.write(trackVint)
        out.write((relativeTimeMs shr 8).toByte())
        out.write(relativeTimeMs.toByte())
        out.write(0x80) // keyframe flag
        out.write(payload)
        return out.toByteArray()
    }

    private fun encodeId(id: Int): ByteArray {
        return when {
            id <= 0xFF -> byteArrayOf(id.toByte())
            id <= 0xFFFF -> byteArrayOf((id shr 8).toByte(), id.toByte())
            else -> byteArrayOf(
                (id shr 24).toByte(),
                (id shr 16).toByte(),
                (id shr 8).toByte(),
                id.toByte(),
            )
        }
    }

    private fun encodeSize(size: Long): ByteArray {
        if (size <= 0x7F) return byteArrayOf(size.toByte())
        if (size <= 0x3FFF) return byteArrayOf((0x40 or (size shr 8).toInt()).toByte(), size.toByte())
        if (size <= 0x1FFFFF) return byteArrayOf(
            (0x20 or (size shr 16).toInt()).toByte(),
            (size shr 8).toByte(),
            size.toByte(),
        )
        return byteArrayOf(
            (0x10 or (size shr 24).toInt()).toByte(),
            (size shr 16).toByte(),
            (size shr 8).toByte(),
            size.toByte(),
        )
    }

    private fun encodeVint(value: Long): ByteArray {
        var length = 1
        var max = 127L
        while (value > max && length < 8) {
            length++
            max = (max shl 7) or 0x7F
        }
        val bytes = ByteArray(length)
        for (i in length - 1 downTo 0) {
            bytes[i] = (value and 0xFF).toByte()
            value = value shr 8
        }
        bytes[0] = (bytes[0].toInt() or (1 shl (8 - length))).toByte()
        return bytes
    }
}
