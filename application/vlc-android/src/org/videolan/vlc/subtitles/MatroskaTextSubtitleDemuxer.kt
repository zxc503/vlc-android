/*
 * Matroska/WebM demuxer for text subtitle tracks (S_TEXT/UTF8, ASS, SSA, WebVTT, …).
 *
 * libVLC renders these to a surface and does not expose cue text. This reader walks EBML
 * directly: element IDs keep their length marker bits; sizes are vints; cues live in
 * Segment → Cluster → SimpleBlock or BlockGroup/Block.
 */

package org.videolan.vlc.subtitles

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.Inflater
import kotlin.math.min

internal object MatroskaTextSubtitleDemuxer {

    private const val TAG = "VLC/AiSubMkv"
    private const val UNKNOWN_SIZE = -1L
    private const val MAX_CUE_BYTES = 1 * 1024 * 1024

    private const val ID_SEGMENT = 0x18538067L
    private const val ID_INFO = 0x1549A966L
    private const val ID_TIMECODE_SCALE = 0x2AD7B1L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_CODEC_ID = 0x86L
    private const val ID_CONTENT_ENCODINGS = 0x6D80L
    private const val ID_CONTENT_ENCODING = 0x6240L
    private const val ID_CONTENT_ENCODING_ORDER = 0x5031L
    private const val ID_CONTENT_ENCODING_SCOPE = 0x5032L
    private const val ID_CONTENT_ENCODING_TYPE = 0x5033L
    private const val ID_CONTENT_COMPRESSION = 0x5034L
    private const val ID_CONTENT_COMP_ALGO = 0x4254L
    private const val ID_CONTENT_COMP_SETTINGS = 0x4255L
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_TIMECODE = 0xE7L
    private const val ID_SIMPLE_BLOCK = 0xA3L
    private const val ID_BLOCK_GROUP = 0xA0L
    private const val ID_BLOCK = 0xA1L
    private const val ID_BLOCK_DURATION = 0x9BL

    private const val TRACK_TYPE_SUBTITLE = 0x11
    private const val COMP_ZLIB = 0
    private const val COMP_HEADER_STRIP = 3
    private const val DEFAULT_TIMECODE_SCALE = 1_000_000L

    sealed class Result {
        data class Cues(val cues: List<SubtitleCue>) : Result()
        object Bitmap : Result()
        object None : Result()
    }

    fun extract(file: File, targetTrackNumber: Int): List<SubtitleCue> {
        return when (val result = extractResult(file, intArrayOf(targetTrackNumber), subtitleOrdinal = -1)) {
            is Result.Cues -> result.cues
            else -> emptyList()
        }
    }

    /**
     * @param trackNumberHints Matroska TrackNumber candidates (player SPU id, parsed media id).
     * @param subtitleOrdinal index of the selected subtitle among subtitle tracks in file order.
     */
    fun extractResult(file: File, trackNumberHints: IntArray, subtitleOrdinal: Int): Result {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                extractResult(RafSource(raf), trackNumberHints, subtitleOrdinal)
            }
        } catch (e: Exception) {
            log("extract-failed ${e.javaClass.simpleName}")
            Result.None
        }
    }

    private fun extractResult(src: EbmlSource, trackNumberHints: IntArray, subtitleOrdinal: Int): Result {
        val parsed = parse(src)
        val subs = parsed.subtitles
        if (subs.isEmpty()) {
            log("no-subtitle-tracks scale=${parsed.timecodeScale}")
            return Result.None
        }
        val hints = trackNumberHints.filter { it >= 0 }.toSet()
        val byNumber = subs.firstOrNull { it.number in hints }
        val byOrdinal = if (subtitleOrdinal in subs.indices) subs[subtitleOrdinal] else null
        val chosen = byNumber
            ?: byOrdinal
            ?: if (subtitleOrdinal < 0) subs.singleOrNull() else null
        if (chosen == null) {
            log("track-miss hints=${hints.size} ordinal=$subtitleOrdinal subs=${subs.size}")
            return Result.None
        }
        if (chosen.kind == TrackKind.BITMAP) {
            log("bitmap-track number=${chosen.number}")
            return Result.Bitmap
        }
        if (chosen.kind != TrackKind.TEXT || chosen.encrypted) {
            log("unsupported-track number=${chosen.number} encrypted=${chosen.encrypted}")
            return Result.None
        }
        val raw = parsed.blocks.filter { it.trackNumber == chosen.number }
        val cues = toCues(raw, chosen, parsed.timecodeScale)
        log("cues=${cues.size} blocks=${raw.size} number=${chosen.number} ordinalHit=${byNumber == null}")
        return if (cues.isEmpty()) Result.None else Result.Cues(cues)
    }

    private data class Parsed(
        val timecodeScale: Long,
        val subtitles: List<SubtitleTrackInfo>,
        val blocks: List<RawBlock>,
    )

    private enum class TrackKind { TEXT, BITMAP, OTHER }

    private data class SubtitleTrackInfo(
        val number: Int,
        val codecId: String,
        val kind: TrackKind,
        val encrypted: Boolean,
        val encodings: List<ContentEncoding>,
    )

    private data class ContentEncoding(
        val order: Int,
        val scope: Int,
        val type: Int,
        val compAlgo: Int,
        val compSettings: ByteArray,
    )

    private data class RawBlock(
        val trackNumber: Int,
        val startUnits: Long,
        val durationUnits: Long?,
        val payload: ByteArray,
    )

    private fun parse(src: EbmlSource): Parsed {
        var scale = DEFAULT_TIMECODE_SCALE
        val subtitles = ArrayList<SubtitleTrackInfo>()
        val clusterOffsets = ArrayList<Long>()
        val earlyBlocks = ArrayList<RawBlock>()
        var pos = 0L
        val end = src.length
        while (pos < end) {
            val element = readElement(src, pos, end) ?: break
            when (element.id) {
                ID_SEGMENT -> {
                    val segmentEnd = if (element.size == UNKNOWN_SIZE) end else element.dataPos + element.size
                    val segment = parseSegment(src, element.dataPos, segmentEnd)
                    scale = segment.scale
                    subtitles.addAll(segment.subtitles)
                    clusterOffsets.addAll(segment.deferredClusters)
                    earlyBlocks.addAll(segment.earlyBlocks)
                }
                else -> Unit
            }
            pos = if (element.size == UNKNOWN_SIZE) end else element.dataPos + element.size
        }
        val textNumbers = subtitles.filter { it.kind == TrackKind.TEXT && !it.encrypted }.map { it.number }.toSet()
        val blocks = ArrayList<RawBlock>(earlyBlocks.size)
        blocks.addAll(earlyBlocks.filter { it.trackNumber in textNumbers })
        for (offset in clusterOffsets) {
            val element = readElement(src, offset, src.length) ?: continue
            if (element.id != ID_CLUSTER) continue
            val clusterEnd = if (element.size == UNKNOWN_SIZE) src.length else element.dataPos + element.size
            blocks.addAll(readCluster(src, element.dataPos, clusterEnd, textNumbers))
        }
        return Parsed(scale, subtitles, blocks)
    }

    private data class SegmentParse(
        val scale: Long,
        val subtitles: List<SubtitleTrackInfo>,
        val deferredClusters: List<Long>,
        val earlyBlocks: List<RawBlock>,
    )

    private fun parseSegment(src: EbmlSource, start: Long, end: Long): SegmentParse {
        var scale = DEFAULT_TIMECODE_SCALE
        val subtitles = ArrayList<SubtitleTrackInfo>()
        val deferred = ArrayList<Long>()
        val early = ArrayList<RawBlock>()
        var tracksSeen = false
        var pos = start
        while (pos < end) {
            val element = readElement(src, pos, end) ?: break
            when (element.id) {
                ID_INFO -> {
                    val infoEnd = boundedEnd(element, end)
                    scale = readTimecodeScale(src, element.dataPos, infoEnd) ?: scale
                }
                ID_TRACKS -> {
                    val tracksEnd = boundedEnd(element, end)
                    subtitles.addAll(readTracks(src, element.dataPos, tracksEnd))
                    tracksSeen = true
                }
                ID_CLUSTER -> {
                    if (!tracksSeen) {
                        deferred.add(pos)
                    } else {
                        val textNumbers = subtitles.filter { it.kind == TrackKind.TEXT && !it.encrypted }
                            .map { it.number }.toSet()
                        val clusterEnd = boundedEnd(element, end)
                        early.addAll(readCluster(src, element.dataPos, clusterEnd, textNumbers))
                    }
                }
            }
            pos = nextPos(element, end)
        }
        return SegmentParse(scale, subtitles, deferred, early)
    }

    private fun readTimecodeScale(src: EbmlSource, start: Long, end: Long): Long? {
        var pos = start
        while (pos < end) {
            val element = readElement(src, pos, end) ?: break
            if (element.id == ID_TIMECODE_SCALE && element.size in 1..8) {
                return readUInt(src, element.dataPos, element.size)
            }
            pos = nextPos(element, end)
        }
        return null
    }

    private fun readTracks(src: EbmlSource, start: Long, end: Long): List<SubtitleTrackInfo> {
        val tracks = ArrayList<SubtitleTrackInfo>()
        var pos = start
        while (pos < end) {
            val element = readElement(src, pos, end) ?: break
            if (element.id == ID_TRACK_ENTRY) {
                val entryEnd = boundedEnd(element, end)
                parseTrackEntry(src, element.dataPos, entryEnd)?.let { tracks.add(it) }
            }
            pos = nextPos(element, end)
        }
        return tracks
    }

    private fun parseTrackEntry(src: EbmlSource, start: Long, end: Long): SubtitleTrackInfo? {
        var number = -1
        var type = -1
        var codecId = ""
        val encodings = ArrayList<ContentEncoding>()
        var pos = start
        while (pos < end) {
            val element = readElement(src, pos, end) ?: break
            when (element.id) {
                ID_TRACK_NUMBER -> if (element.size in 1..8) number = readUInt(src, element.dataPos, element.size).toInt()
                ID_TRACK_TYPE -> if (element.size in 1..8) type = readUInt(src, element.dataPos, element.size).toInt()
                ID_CODEC_ID -> if (element.size in 1..64) codecId = readString(src, element.dataPos, element.size)
                ID_CONTENT_ENCODINGS -> encodings.addAll(readEncodings(src, element.dataPos, boundedEnd(element, end)))
            }
            pos = nextPos(element, end)
        }
        if (type != TRACK_TYPE_SUBTITLE || number < 0) return null
        val kind = classifyCodec(codecId)
        val encrypted = encodings.any { it.type == 1 }
        return SubtitleTrackInfo(number, codecId, kind, encrypted, encodings.sortedByDescending { it.order })
    }

    private fun readEncodings(src: EbmlSource, start: Long, end: Long): List<ContentEncoding> {
        val list = ArrayList<ContentEncoding>()
        var pos = start
        while (pos < end) {
            val element = readElement(src, pos, end) ?: break
            if (element.id == ID_CONTENT_ENCODING) {
                readEncoding(src, element.dataPos, boundedEnd(element, end))?.let { list.add(it) }
            }
            pos = nextPos(element, end)
        }
        return list
    }

    private fun readEncoding(src: EbmlSource, start: Long, end: Long): ContentEncoding? {
        var order = 0
        var scope = 1
        var type = 0
        var algo = 0
        var settings = ByteArray(0)
        var pos = start
        while (pos < end) {
            val element = readElement(src, pos, end) ?: break
            when (element.id) {
                ID_CONTENT_ENCODING_ORDER -> if (element.size in 1..8) order = readUInt(src, element.dataPos, element.size).toInt()
                ID_CONTENT_ENCODING_SCOPE -> if (element.size in 1..8) scope = readUInt(src, element.dataPos, element.size).toInt()
                ID_CONTENT_ENCODING_TYPE -> if (element.size in 1..8) type = readUInt(src, element.dataPos, element.size).toInt()
                ID_CONTENT_COMPRESSION -> {
                    val comp = readCompression(src, element.dataPos, boundedEnd(element, end))
                    algo = comp.first
                    settings = comp.second
                }
            }
            pos = nextPos(element, end)
        }
        return ContentEncoding(order, scope, type, algo, settings)
    }

    private fun readCompression(src: EbmlSource, start: Long, end: Long): Pair<Int, ByteArray> {
        var algo = 0
        var settings = ByteArray(0)
        var pos = start
        while (pos < end) {
            val element = readElement(src, pos, end) ?: break
            when (element.id) {
                ID_CONTENT_COMP_ALGO -> if (element.size in 1..8) algo = readUInt(src, element.dataPos, element.size).toInt()
                ID_CONTENT_COMP_SETTINGS -> if (element.size in 0..4096) settings = readBytes(src, element.dataPos, element.size)
            }
            pos = nextPos(element, end)
        }
        return algo to settings
    }

    private fun readCluster(
        src: EbmlSource,
        start: Long,
        end: Long,
        textTrackNumbers: Set<Int>,
    ): List<RawBlock> {
        if (textTrackNumbers.isEmpty()) return emptyList()
        val blocks = ArrayList<RawBlock>()
        var clusterUnits = 0L
        var pos = start
        while (pos < end) {
            val element = readElement(src, pos, end) ?: break
            when (element.id) {
                ID_TIMECODE -> if (element.size in 1..8) clusterUnits = readUInt(src, element.dataPos, element.size)
                ID_SIMPLE_BLOCK -> readBlockElement(src, element, clusterUnits, durationUnits = null, textTrackNumbers)?.let { blocks.add(it) }
                ID_BLOCK_GROUP -> blocks.addAll(readBlockGroup(src, element.dataPos, boundedEnd(element, end), clusterUnits, textTrackNumbers))
            }
            pos = nextPos(element, end)
        }
        return blocks
    }

    private fun readBlockGroup(
        src: EbmlSource,
        start: Long,
        end: Long,
        clusterUnits: Long,
        textTrackNumbers: Set<Int>,
    ): List<RawBlock> {
        val blocks = ArrayList<RawBlock>()
        var duration: Long? = null
        val pending = ArrayList<Pair<EbmlElement, Long>>()
        var pos = start
        while (pos < end) {
            val element = readElement(src, pos, end) ?: break
            when (element.id) {
                ID_BLOCK_DURATION -> if (element.size in 1..8) duration = readUInt(src, element.dataPos, element.size)
                ID_BLOCK -> pending.add(element to clusterUnits)
            }
            pos = nextPos(element, end)
        }
        for ((element, cluster) in pending) {
            readBlockElement(src, element, cluster, duration, textTrackNumbers)?.let { blocks.add(it) }
        }
        return blocks
    }

    private fun readBlockElement(
        src: EbmlSource,
        element: EbmlElement,
        clusterUnits: Long,
        durationUnits: Long?,
        textTrackNumbers: Set<Int>,
    ): RawBlock? {
        if (element.size <= 0 || element.size > Int.MAX_VALUE) return null
        val blockEnd = element.dataPos + element.size
        src.seek(element.dataPos)
        val trackNumber = readVint(src).toInt()
        if (trackNumber !in textTrackNumbers) return null
        if (src.pos + 3 > blockEnd) return null
        val rel = readInt16(src)
        val flags = src.read()
        if (flags < 0) return null
        val lacing = (flags shr 1) and 0x03
        val payloadStart = src.pos
        val payloadSize = (blockEnd - payloadStart).toInt()
        if (payloadSize <= 0 || payloadSize > MAX_CUE_BYTES) return null
        val payload = ByteArray(payloadSize)
        src.readFully(payload)
        val frame = if (lacing == 0) payload else firstLacedFrame(payload, lacing) ?: payload
        val startUnits = clusterUnits + rel
        return RawBlock(trackNumber, startUnits, durationUnits, frame)
    }

    private fun firstLacedFrame(payload: ByteArray, lacing: Int): ByteArray? {
        if (payload.isEmpty()) return null
        val frameCount = (payload[0].toInt() and 0xFF) + 1
        if (frameCount <= 1 || lacing == 2) {
            val size = payload.size / frameCount.coerceAtLeast(1)
            if (size <= 0 || size > payload.size) return payload
            return payload.copyOfRange(1.coerceAtMost(payload.size), min(payload.size, 1 + size))
        }
        return payload
    }

    private fun payloadToText(codecId: String, payload: ByteArray): String {
        val bytes = stripBom(payload)
        val raw = decodeText(bytes)
        val codec = codecId.uppercase()
        val text = when {
            codec.contains("ASS") || codec.contains("SSA") -> assToPlain(raw)
            codec.contains("WEBVTT") -> vttToPlain(raw)
            codec.contains("USF") -> raw.replace(Regex("<[^>]+>"), " ")
            else -> raw.replace(Regex("</?(i|b|u|font)[^>]*>", RegexOption.IGNORE_CASE), "")
        }
        return text.replace('\u0000', ' ').trim()
    }

    private fun assToPlain(raw: String): String {
        val lines = raw.replace("\r\n", "\n").lines()
        val texts = ArrayList<String>()
        for (line in lines) {
            var body = line.trim()
            if (body.isEmpty()) continue
            if (body.startsWith("Comment:", ignoreCase = true)) continue
            if (body.startsWith("Dialogue:", ignoreCase = true) || body.startsWith("Comment:", ignoreCase = true)) {
                body = body.substringAfter(':').trim()
            }
            val fields = splitCsv(body)
            val text = if (fields.size >= 9 && fields[0].all { it.isDigit() || it == '-' }) {
                if (fields.size >= 10 && fields.getOrNull(1)?.contains(':') == true) {
                    fields.drop(9).joinToString(",")
                } else {
                    fields.drop(8).joinToString(",")
                }
            } else {
                body
            }
            val plain = unescapeAss(text)
            if (plain.isNotBlank()) texts.add(plain)
        }
        return texts.joinToString("\n")
    }

    private fun unescapeAss(text: String): String {
        val noTags = text.replace(Regex("\\{[^}]*\\}"), "")
        return noTags
            .replace("\\N", "\n", ignoreCase = false)
            .replace("\\n", "\n")
            .replace("\\h", " ")
            .trim()
    }

    private fun vttToPlain(raw: String): String {
        return raw.replace("\r\n", "\n").lines()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                    line != "WEBVTT" &&
                    !line.startsWith("NOTE") &&
                    !line.startsWith("STYLE") &&
                    !line.contains("-->")
            }
            .joinToString("\n") { it.replace(Regex("<[^>]+>"), "") }
            .trim()
    }

    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (ch in line) {
            if (ch == ',') {
                out.add(cur.toString())
                cur.setLength(0)
            } else {
                cur.append(ch)
            }
        }
        out.add(cur.toString())
        return out
    }

    private fun classifyCodec(codecId: String): TrackKind {
        val c = codecId.uppercase()
        if (c.isEmpty()) return TrackKind.OTHER
        if (c == "S_HDMV/PGS" || c == "S_VOBSUB" || c == "S_DVBSUB" || c.startsWith("V_")) return TrackKind.BITMAP
        if (
            c == "S_TEXT/UTF8" || c == "S_TEXT/ASCII" || c == "S_TEXT/ASS" || c == "S_TEXT/SSA" ||
            c == "S_TEXT/WEBVTT" || c == "S_TEXT/USF" || c == "S_HDMV/TEXTST" || c == "S_KATE" ||
            c.startsWith("S_TEXT/") || c.startsWith("D_WEBVTT/")
        ) return TrackKind.TEXT
        if (c.contains("PGS") || c.contains("VOBSUB") || c.contains("DVBSUB")) return TrackKind.BITMAP
        return TrackKind.OTHER
    }

    private fun decodeFrame(track: SubtitleTrackInfo, payload: ByteArray): ByteArray {
        var data = payload
        for (encoding in track.encodings) {
            if (encoding.scope and 1 == 0) continue
            if (encoding.type != 0) continue
            data = when (encoding.compAlgo) {
                COMP_ZLIB -> inflate(data) ?: data
                COMP_HEADER_STRIP -> encoding.compSettings + data
                else -> data
            }
        }
        return data
    }

    private fun toCues(blocks: List<RawBlock>, track: SubtitleTrackInfo, scale: Long): List<SubtitleCue> {
        val sorted = blocks.sortedBy { it.startUnits }
        val raw = ArrayList<Triple<Long, Long?, String>>(sorted.size)
        for (block in sorted) {
            val decoded = decodeFrame(track, block.payload)
            val text = payloadToText(track.codecId, decoded)
            if (text.isBlank()) continue
            raw.add(Triple(unitsToMs(block.startUnits, scale), block.durationUnits?.let { unitsToMs(it, scale) }, text))
        }
        return raw.mapIndexed { index, (start, duration, text) ->
            val end = when {
                duration != null && duration > 0 -> start + duration
                index + 1 < raw.size -> raw[index + 1].first - 1
                else -> start + 4000
            }
            SubtitleCue(start, maxOf(end, start + 200), text)
        }
    }

    private fun inflate(data: ByteArray): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            var total = 0
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                total += n
                if (total > MAX_CUE_BYTES) return null
                out.write(buf, 0, n)
            }
            if (out.size() == 0) null else out.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun stripBom(bytes: ByteArray): ByteArray {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return bytes.copyOfRange(3, bytes.size)
        }
        return bytes
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun unitsToMs(units: Long, scaleNs: Long): Long {
        return ((units.toDouble() * scaleNs.toDouble()) / 1_000_000.0).toLong()
    }

    private data class EbmlElement(val id: Long, val size: Long, val dataPos: Long)

    private fun readElement(src: EbmlSource, pos: Long, limit: Long): EbmlElement? {
        if (pos >= limit) return null
        src.seek(pos)
        val id = readId(src) ?: return null
        val size = readSize(src) ?: return null
        val dataPos = src.pos
        if (size != UNKNOWN_SIZE && (size < 0 || dataPos + size > limit)) return null
        return EbmlElement(id, size, dataPos)
    }

    private fun boundedEnd(element: EbmlElement, parentEnd: Long): Long {
        if (element.size == UNKNOWN_SIZE) return parentEnd
        return minOf(element.dataPos + element.size, parentEnd)
    }

    private fun nextPos(element: EbmlElement, parentEnd: Long): Long {
        if (element.size == UNKNOWN_SIZE) return parentEnd
        return minOf(element.dataPos + element.size, parentEnd)
    }

    /** EBML element ID: width from the leading marker, value includes marker bits. */
    private fun readId(src: EbmlSource): Long? {
        val first = src.read()
        if (first < 0) return null
        val width = vintWidth(first) ?: return null
        var id = first.toLong()
        for (i in 1 until width) {
            val b = src.read()
            if (b < 0) return null
            id = (id shl 8) or b.toLong()
        }
        return id
    }

    /** EBML size vint. All data bits set means unknown length. */
    private fun readSize(src: EbmlSource): Long? = readVintOrUnknown(src)

    private fun readVint(src: EbmlSource): Long {
        val value = readVintOrUnknown(src)
        return if (value == null || value == UNKNOWN_SIZE) -1 else value
    }

    private fun readVintOrUnknown(src: EbmlSource): Long? {
        val first = src.read()
        if (first < 0) return null
        val width = vintWidth(first) ?: return null
        var value = (first and (0xFF shr width)).toLong()
        for (i in 1 until width) {
            val b = src.read()
            if (b < 0) return null
            value = (value shl 8) or b.toLong()
        }
        val unknown = (1L shl (7 * width)) - 1L
        if (value == unknown) return UNKNOWN_SIZE
        return value
    }

    private fun vintWidth(first: Int): Int? {
        for (width in 1..8) {
            if (first and (1 shl (8 - width)) != 0) return width
        }
        return null
    }

    private fun readUInt(src: EbmlSource, pos: Long, size: Long): Long {
        src.seek(pos)
        var value = 0L
        repeat(size.toInt()) {
            val b = src.read()
            if (b < 0) throw EOFException()
            value = (value shl 8) or b.toLong()
        }
        return value
    }

    private fun readInt16(src: EbmlSource): Int {
        val hi = src.read()
        val lo = src.read()
        if (hi < 0 || lo < 0) return 0
        val value = (hi shl 8) or lo
        return if (value >= 0x8000) value - 0x10000 else value
    }

    private fun readString(src: EbmlSource, pos: Long, size: Long): String {
        return String(readBytes(src, pos, size), Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
    }

    private fun readBytes(src: EbmlSource, pos: Long, size: Long): ByteArray {
        if (size == 0L) return ByteArray(0)
        val out = ByteArray(size.toInt())
        src.seek(pos)
        src.readFully(out)
        return out
    }

    private fun log(message: String) {
        try {
            val logClass = Class.forName("android.util.Log")
            logClass.getMethod("i", String::class.java, String::class.java).invoke(null, TAG, message)
        } catch (_: Throwable) {
        }
    }

    private interface EbmlSource : AutoCloseable {
        val length: Long
        var pos: Long
        fun read(): Int
        fun seek(position: Long)
        fun readFully(out: ByteArray)
    }

    private class RafSource(private val raf: RandomAccessFile) : EbmlSource {
        private val buf = ByteArray(8192)
        private var bufStart = 0L
        private var bufEnd = 0L
        override val length: Long = raf.length()
        override var pos: Long = 0L

        override fun read(): Int {
            if (pos >= length) return -1
            if (pos < bufStart || pos >= bufEnd) fill()
            if (pos >= bufEnd) return -1
            val value = buf[(pos - bufStart).toInt()].toInt() and 0xFF
            pos++
            return value
        }

        override fun seek(position: Long) {
            pos = position.coerceIn(0L, length)
        }

        override fun readFully(out: ByteArray) {
            var offset = 0
            while (offset < out.size) {
                if (pos >= length) throw EOFException()
                if (pos < bufStart || pos >= bufEnd) fill()
                val n = min(out.size - offset, (bufEnd - pos).toInt())
                if (n <= 0) throw EOFException()
                System.arraycopy(buf, (pos - bufStart).toInt(), out, offset, n)
                pos += n
                offset += n
            }
        }

        private fun fill() {
            raf.seek(pos)
            val n = raf.read(buf)
            bufStart = pos
            bufEnd = if (n <= 0) pos else pos + n
        }

        override fun close() {
            raf.close()
        }
    }
}
