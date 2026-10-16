/*
 * Lightweight Matroska demuxer for text-based embedded subtitle tracks (SRT/ASS/UTF8 in MKV).
 */

package org.videolan.vlc.subtitles

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

internal object MatroskaTextSubtitleDemuxer {

    private const val ID_SEGMENT = 0x18538067
    private const val ID_TRACKS = 0x1654AE6B
    private const val ID_TRACK_ENTRY = 0xAE
    private const val ID_TRACK_NUMBER = 0xD7
    private const val ID_TRACK_TYPE = 0x83
    private const val ID_CODEC_ID = 0x86
    private const val ID_CLUSTER = 0x1F43B675
    private const val ID_TIMECODE = 0xE7
    private const val ID_SIMPLE_BLOCK = 0xA3

    private const val TRACK_TYPE_SUBTITLE = 0x11

    fun extract(file: File, targetTrackNumber: Int): List<SubtitleCue> {
        RandomAccessFile(file, "r").use { raf ->
            val trackNumbers = readSubtitleTrackNumbers(raf, targetTrackNumber)
            if (trackNumbers.isEmpty()) return emptyList()
            return readSubtitleBlocks(raf, trackNumbers)
        }
    }

    private fun readSubtitleTrackNumbers(raf: RandomAccessFile, targetTrackNumber: Int): Set<Int> {
        val subtitleTracks = linkedMapOf<Int, String>()
        raf.seek(0)
        while (raf.filePointer < raf.length()) {
            val element = readElementHeader(raf) ?: break
            when (element.id) {
                ID_TRACKS -> {
                    val end = raf.filePointer + element.size
                    while (raf.filePointer < end) {
                        val child = readElementHeader(raf) ?: break
                        if (child.id == ID_TRACK_ENTRY) {
                            parseTrackEntry(raf, child.size, subtitleTracks)
                        } else {
                            raf.seek(raf.filePointer + child.size)
                        }
                    }
                }
                ID_CLUSTER -> break
                else -> raf.seek(raf.filePointer + element.size)
            }
        }
        if (subtitleTracks.containsKey(targetTrackNumber)) return setOf(targetTrackNumber)
        return subtitleTracks.keys
    }

    private fun parseTrackEntry(
        raf: RandomAccessFile,
        size: Long,
        out: MutableMap<Int, String>,
    ) {
        val end = raf.filePointer + size
        var trackNumber = -1
        var trackType = -1
        var codecId = ""
        while (raf.filePointer < end) {
            val child = readElementHeader(raf) ?: break
            when (child.id) {
                ID_TRACK_NUMBER -> trackNumber = readUInt(raf, child.size).toInt()
                ID_TRACK_TYPE -> trackType = readUInt(raf, child.size).toInt()
                ID_CODEC_ID -> codecId = readString(raf, child.size)
                else -> raf.seek(raf.filePointer + child.size)
            }
        }
        if (trackType == TRACK_TYPE_SUBTITLE && trackNumber >= 0 && isTextCodec(codecId)) {
            out[trackNumber] = codecId
        }
    }

    private fun readSubtitleBlocks(raf: RandomAccessFile, trackNumbers: Set<Int>): List<SubtitleCue> {
        val rawCues = ArrayList<Pair<Long, String>>()
        raf.seek(0)
        var clusterTimecodeMs = 0L
        while (raf.filePointer < raf.length()) {
            val element = readElementHeader(raf) ?: break
            when (element.id) {
                ID_CLUSTER -> {
                    val clusterEnd = raf.filePointer + element.size
                    clusterTimecodeMs = 0L
                    while (raf.filePointer < clusterEnd) {
                        val child = readElementHeader(raf) ?: break
                        when (child.id) {
                            ID_TIMECODE -> clusterTimecodeMs = readUInt(raf, child.size)
                            ID_SIMPLE_BLOCK -> {
                                val block = readSimpleBlock(raf, child.size)
                                if (block != null && block.trackNumber in trackNumbers) {
                                    val startMs = clusterTimecodeMs + block.relativeTimeMs
                                    val text = cleanBlockText(block.payload)
                                    if (text.isNotBlank()) rawCues.add(startMs to text)
                                }
                            }
                            else -> raf.seek(raf.filePointer + child.size)
                        }
                    }
                }
                else -> raf.seek(raf.filePointer + element.size)
            }
        }
        rawCues.sortBy { it.first }
        return rawCues.mapIndexed { index, (start, text) ->
            val end = if (index + 1 < rawCues.size) rawCues[index + 1].first - 1 else start + 4000
            SubtitleCue(start, maxOf(end, start + 200), text)
        }
    }

    private data class SimpleBlock(
        val trackNumber: Int,
        val relativeTimeMs: Long,
        val payload: String,
    )

    private fun readSimpleBlock(raf: RandomAccessFile, size: Long): SimpleBlock? {
        val start = raf.filePointer
        val trackNumber = readVint(raf).toInt()
        val relTime = readSignedInt16(raf)
        raf.read() // flags
        val payloadSize = size - (raf.filePointer - start)
        if (payloadSize <= 0) return null
        val bytes = ByteArray(payloadSize.toInt())
        raf.readFully(bytes)
        val payload = String(bytes, Charset.forName("UTF-8"))
        return SimpleBlock(trackNumber, relTime.toLong(), payload)
    }

    private fun cleanBlockText(raw: String): String {
        val line = raw.trim().replace("\u0000", "")
        if (line.startsWith("Dialogue:", ignoreCase = true)) {
            val parts = line.split(',', limit = 10)
            if (parts.size >= 10) return parts.subList(9, parts.size).joinToString(",").trim()
        }
        return line.replace(Regex("<[^>]+>"), "").trim()
    }

    private fun isTextCodec(codecId: String): Boolean {
        val c = codecId.lowercase()
        if (c.contains("pgs") || c.contains("dvdsub") || c.contains("vobsub") || c.contains("dvb")) return false
        return c.contains("subrip") || c.contains("srt") || c.contains("ass") || c.contains("ssa")
                || c.contains("utf8") || c.contains("ascii") || c.contains("text")
    }

    private data class ElementHeader(val id: Int, val size: Long)

    private fun readElementHeader(raf: RandomAccessFile): ElementHeader? {
        if (raf.filePointer >= raf.length()) return null
        val id = readEbmlId(raf) ?: return null
        val size = readEbmlSize(raf)
        if (size < 0 || raf.filePointer + size > raf.length()) return null
        return ElementHeader(id, size)
    }

    private fun readEbmlId(raf: RandomAccessFile): Int? {
        val parsed = readEbmlVariableInt(raf) ?: return null
        return parsed.toInt()
    }

    private fun readEbmlSize(raf: RandomAccessFile): Long = readEbmlVariableInt(raf) ?: -1L

    /** EBML variable-length integer (element ID and size). */
    private fun readEbmlVariableInt(raf: RandomAccessFile): Long? {
        val first = raf.read()
        if (first == -1) return null
        var length = 1
        var mask = 0x80
        while (length < 8 && (first and mask) == 0) {
            mask = mask shr 1
            length++
        }
        var value = (first and (0xFF shr length)).toLong()
        for (i in 1 until length) {
            val b = raf.read()
            if (b == -1) return null
            value = (value shl 8) or b.toLong()
        }
        return value
    }

    private fun readVint(raf: RandomAccessFile): Long = readEbmlVariableInt(raf) ?: -1L

    private fun readUInt(raf: RandomAccessFile, size: Long): Long {
        var value = 0L
        repeat(size.toInt()) {
            value = (value shl 8) or raf.read().toLong()
        }
        return value
    }

    private fun readSignedInt16(raf: RandomAccessFile): Int {
        val hi = raf.read()
        val lo = raf.read()
        return (hi shl 8) or lo
    }

    private fun readString(raf: RandomAccessFile, size: Long): String {
        val bytes = ByteArray(size.toInt())
        raf.readFully(bytes)
        return String(bytes, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
    }
}
