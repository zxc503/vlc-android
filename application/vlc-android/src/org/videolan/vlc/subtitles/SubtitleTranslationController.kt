/*
 * Drives AI subtitle overlay during video playback.
 */

package org.videolan.vlc.subtitles

import android.content.SharedPreferences
import android.graphics.Color
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.tools.KEY_AI_SUBTITLE_TRANSLATION
import org.videolan.tools.KEY_SUBTITLES_BACKGROUND
import org.videolan.tools.KEY_SUBTITLES_BACKGROUND_COLOR
import org.videolan.tools.KEY_SUBTITLES_BACKGROUND_COLOR_OPACITY
import org.videolan.tools.KEY_SUBTITLES_BOLD
import org.videolan.tools.KEY_SUBTITLES_COLOR
import org.videolan.tools.KEY_SUBTITLES_SIZE
import org.videolan.tools.setGone
import org.videolan.tools.setVisible
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.R

class SubtitleTranslationController(
    private val overlay: TextView,
    private val settings: SharedPreferences,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val serviceProvider: () -> PlaybackService?,
    private val translator: DeepSeekSubtitleTranslator = DeepSeekSubtitleTranslator(overlay.context.applicationContext),
) {
    private var cues: List<SubtitleCue> = emptyList()
    private var activeCueIndex = -1
    private var loadJob: Job? = null
    private var translateJob: Job? = null
    private var savedSpuTrackId: String? = null
    private var nativeSpuHidden = false

    var enabled: Boolean
        get() = settings.getBoolean(KEY_AI_SUBTITLE_TRANSLATION, false)
        set(value) {
            settings.edit().putBoolean(KEY_AI_SUBTITLE_TRANSLATION, value).apply()
            applyEnabledState(showErrors = true)
        }

    fun toggle(): Boolean {
        enabled = !enabled
        return enabled
    }

    fun onPlaybackSessionStart() {
        applyEnabledState(showErrors = false)
    }

    fun onPlaybackStopped() {
        clearOverlay()
        restoreNativeSpu()
        cues = emptyList()
        activeCueIndex = -1
    }

    fun onTimeChanged(timeMs: Long) {
        if (!enabled || cues.isEmpty()) return
        val service = serviceProvider() ?: return
        val adjustedMs = timeMs + service.spuDelay / 1000L
        val index = cues.indexOfFirst { adjustedMs in it.startTimeMs..it.endTimeMs }
        if (index == activeCueIndex) return
        activeCueIndex = index
        if (index < 0) {
            clearOverlay()
            return
        }
        val cue = cues[index]
        translateJob?.cancel()
        translateJob = lifecycleScope.launch {
            val translated = translator.translate(cue.text) ?: cue.text
            if (index == activeCueIndex) showText(translated)
            prefetchUpcoming(index)
        }
    }

    private fun prefetchUpcoming(fromIndex: Int) {
        lifecycleScope.launch {
            val upcoming = cues.drop(fromIndex + 1).take(PREFETCH_COUNT).map { it.text }
            translator.prefetch(upcoming)
        }
    }

    private fun applyEnabledState(showErrors: Boolean) {
        if (!enabled) {
            clearOverlay()
            restoreNativeSpu()
            return
        }
        if (!translator.isConfigured()) {
            if (showErrors) {
                overlay.post {
                    android.widget.Toast.makeText(
                        overlay.context,
                        overlay.context.getString(R.string.ai_subtitle_missing_api_key),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            settings.edit().putBoolean(KEY_AI_SUBTITLE_TRANSLATION, false).apply()
            return
        }
        applyOverlayStyle()
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val service = serviceProvider() ?: return@launch
            val media = service.currentMediaWrapper ?: return@launch
            when (val loaded = SubtitleCueSource.load(overlay.context, service, media)) {
                is SubtitleCueSource.LoadResult.Ok -> {
                    cues = loaded.cues
                    hideNativeSpu(service)
                    onTimeChanged(service.getTime())
                }
                is SubtitleCueSource.LoadResult.Error -> {
                    if (showErrors) {
                        val msg = when (loaded.kind) {
                            SubtitleCueSource.Kind.BitmapEmbedded -> R.string.ai_subtitle_bitmap_embedded
                            SubtitleCueSource.Kind.ParseFailed -> R.string.ai_subtitle_parse_failed
                            SubtitleCueSource.Kind.UnsupportedEmbedded -> R.string.ai_subtitle_embedded_unsupported
                            SubtitleCueSource.Kind.NoSource -> R.string.ai_subtitle_no_subtitle_source
                        }
                        showToast(msg)
                    }
                    settings.edit().putBoolean(KEY_AI_SUBTITLE_TRANSLATION, false).apply()
                }
            }
        }
    }

    /** Reload cues when the user picks a different embedded/external subtitle track. */
    fun onSubtitleTrackChanged() {
        if (!enabled) return
        activeCueIndex = -1
        cues = emptyList()
        applyEnabledState(showErrors = false)
    }

    private fun hideNativeSpu(service: PlaybackService) {
        val current = service.spuTrack
        if (current != "-1") {
            savedSpuTrackId = current
            service.setSpuTrack("-1")
        }
        nativeSpuHidden = true
    }

    private fun restoreNativeSpu() {
        if (!nativeSpuHidden) return
        val service = serviceProvider()
        val restore = savedSpuTrackId
        if (service != null && restore != null && restore != "-1") {
            service.setSpuTrack(restore)
        }
        savedSpuTrackId = null
        nativeSpuHidden = false
    }

    private fun showText(text: String) {
        overlay.text = text
        overlay.setVisible()
    }

    private fun clearOverlay() {
        overlay.text = ""
        overlay.setGone()
    }

    private fun showToast(stringRes: Int) {
        overlay.post {
            android.widget.Toast.makeText(overlay.context, overlay.context.getString(stringRes), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun applyOverlayStyle() {
        val context = overlay.context
        val sizeSp = settings.getString(KEY_SUBTITLES_SIZE, "16")?.toFloatOrNull() ?: 16f
        overlay.textSize = sizeSp
        overlay.paint.isFakeBoldText = settings.getBoolean(KEY_SUBTITLES_BOLD, false)
        overlay.setTextColor(settings.getInt(KEY_SUBTITLES_COLOR, Color.WHITE))
        if (settings.getBoolean(KEY_SUBTITLES_BACKGROUND, false)) {
            val bg = settings.getInt(KEY_SUBTITLES_BACKGROUND_COLOR, Color.BLACK)
            val alpha = settings.getInt(KEY_SUBTITLES_BACKGROUND_COLOR_OPACITY, 255)
            overlay.setBackgroundColor(Color.argb(alpha, Color.red(bg), Color.green(bg), Color.blue(bg)))
        } else {
            overlay.setBackgroundColor(0x66000000)
        }
    }

    companion object {
        private const val PREFETCH_COUNT = 4
    }
}
