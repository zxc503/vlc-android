package org.videolan.vlc.subtitles

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleCueParserTest {

    @Test
    fun parseSrt_singleCue() {
        val srt = """
            1
            00:00:01,000 --> 00:00:04,000
            Hello world
        """.trimIndent()
        val cues = SubtitleCueParser.parseSrt(srt)
        assertEquals(1, cues.size)
        assertEquals(1000L, cues[0].startTimeMs)
        assertEquals(4000L, cues[0].endTimeMs)
        assertEquals("Hello world", cues[0].text)
    }

    @Test
    fun parseVtt_basic() {
        val vtt = """
            WEBVTT

            00:00:02.000 --> 00:00:05.000
            Line one
        """.trimIndent()
        val cues = SubtitleCueParser.parseVtt(vtt)
        assertEquals(1, cues.size)
        assertEquals("Line one", cues[0].text)
    }
}
