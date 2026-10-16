/*
 * Subtitle cue model for AI translation overlay.
 */

package org.videolan.vlc.subtitles

data class SubtitleCue(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String,
)
