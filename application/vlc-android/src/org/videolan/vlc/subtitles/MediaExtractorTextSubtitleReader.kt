/*
 * Reads text subtitle samples from MP4/MKV via Android MediaExtractor when supported.
 */

package org.videolan.vlc.subtitles

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import java.io.File
import java.nio.charset.Charset

internal object MediaExtractorTextSubtitleReader {

    private val TEXT_MIME_PREFIXES = listOf(
        "text/",
        "application/x-subrip",
        "application/ttml+xml",
        "application/x-quicktime-tx3g",
    )

    fun extract(file: File, trackIndexAmongText: Int): List<SubtitleCue> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) return emptyList()
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val textTrackIndices = (0 until extractor.trackCount).filter { i ->
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                TEXT_MIME_PREFIXES.any { mime.startsWith(it) || mime.contains("subrip", true) }
            }
            val mediaTrackIndex = textTrackIndices.getOrNull(trackIndexAmongText) ?: return emptyList()
            extractor.selectTrack(mediaTrackIndex)
            readSamples(extractor)
        } catch (_: Exception) {
            emptyList()
        } finally {
            extractor.release()
        }
    }

    private fun readSamples(extractor: MediaExtractor): List<SubtitleCue> {
        val buffer = java.nio.ByteBuffer.allocate(256 * 1024)
        val raw = ArrayList<Pair<Long, String>>()
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val timeUs = extractor.sampleTime
            buffer.flip()
            val bytes = ByteArray(size)
            buffer.get(bytes)
            val text = decodeSample(bytes).trim()
            if (text.isNotEmpty()) raw.add(timeUs / 1000L to text)
            if (!extractor.advance()) break
        }
        raw.sortBy { it.first }
        return raw.mapIndexed { index, (start, text) ->
            val end = if (index + 1 < raw.size) raw[index + 1].first - 1 else start + 4000
            SubtitleCue(start, maxOf(end, start + 200), text)
        }
    }

    private fun decodeSample(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16BE"))
        }
        return String(bytes, Charsets.UTF_8)
    }
}
