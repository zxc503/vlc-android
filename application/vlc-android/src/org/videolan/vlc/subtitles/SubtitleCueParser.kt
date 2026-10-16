/*
 * Parses common external subtitle formats (SRT, WebVTT) into cues.
 */

package org.videolan.vlc.subtitles

import java.io.File
import java.nio.charset.Charset
import java.util.regex.Pattern

object SubtitleCueParser {

    private val SRT_TIMESTAMP = Pattern.compile(
        "(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})"
    )

    fun parseFile(file: File, charset: Charset = Charsets.UTF_8): List<SubtitleCue> {
        if (!file.isFile || !file.canRead()) return emptyList()
        val name = file.name.lowercase()
        val content = file.readText(charset)
        return when {
            name.endsWith(".vtt") -> parseVtt(content)
            else -> parseSrt(content)
        }
    }

    fun parseSrt(content: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        val blocks = content.replace("\r\n", "\n").split("\n\n")
        for (block in blocks) {
            val lines = block.trim().lines()
            if (lines.size < 2) continue
            var lineIndex = 0
            if (lines[0].trim().matches(Regex("\\d+"))) lineIndex = 1
            if (lineIndex >= lines.size) continue
            val matcher = SRT_TIMESTAMP.matcher(lines[lineIndex].trim())
            if (!matcher.find()) continue
            val start = timestampToMs(matcher, 1)
            val end = timestampToMs(matcher, 5)
            val text = lines.drop(lineIndex + 1).joinToString("\n").trim()
            if (text.isNotEmpty()) cues.add(SubtitleCue(start, end, text))
        }
        return cues.sortedBy { it.startTimeMs }
    }

    fun parseVtt(content: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        val lines = content.replace("\r\n", "\n").lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val matcher = SRT_TIMESTAMP.matcher(line)
            if (matcher.find()) {
                val start = timestampToMs(matcher, 1)
                val end = timestampToMs(matcher, 5)
                val textLines = ArrayList<String>()
                i++
                while (i < lines.size && lines[i].isNotBlank()) {
                    textLines.add(stripVttTags(lines[i]))
                    i++
                }
                val text = textLines.joinToString("\n").trim()
                if (text.isNotEmpty()) cues.add(SubtitleCue(start, end, text))
            } else {
                i++
            }
        }
        return cues.sortedBy { it.startTimeMs }
    }

    private fun stripVttTags(line: String): String =
        line.replace(Regex("<[^>]+>"), "").trim()

    private fun timestampToMs(matcher: java.util.regex.Matcher, startGroup: Int): Long {
        val h = matcher.group(startGroup)!!.toLong()
        val m = matcher.group(startGroup + 1)!!.toLong()
        val s = matcher.group(startGroup + 2)!!.toLong()
        val ms = matcher.group(startGroup + 3)!!.toLong()
        return ((h * 3600 + m * 60 + s) * 1000) + ms
    }
}
