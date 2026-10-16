/*
 * Resolves subtitle cues from external files or embedded mux tracks.
 */

package org.videolan.vlc.subtitles

import android.content.Context
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.vlc.PlaybackService

object SubtitleCueSource {

    sealed class LoadResult {
        data class Ok(val cues: List<SubtitleCue>) : LoadResult()
        data class Error(val kind: Kind) : LoadResult()
    }

    enum class Kind {
        NoSource,
        ParseFailed,
        BitmapEmbedded,
        UnsupportedEmbedded,
    }

    suspend fun load(
        context: Context,
        service: PlaybackService,
        media: MediaWrapper,
    ): LoadResult {
        val embeddedSelected = EmbeddedSubtitleExtractor.isEmbeddedSpuSelected(service, media)

        if (embeddedSelected) {
            return when (val embedded = EmbeddedSubtitleExtractor.extractForCurrentSpu(context, service, media)) {
                is EmbeddedSubtitleExtractor.Result.Success -> LoadResult.Ok(embedded.cues)
                is EmbeddedSubtitleExtractor.Result.BitmapTrack -> LoadResult.Error(Kind.BitmapEmbedded)
                is EmbeddedSubtitleExtractor.Result.NoTextTracks,
                is EmbeddedSubtitleExtractor.Result.ExtractionFailed,
                is EmbeddedSubtitleExtractor.Result.ParseFailed,
                -> LoadResult.Error(Kind.UnsupportedEmbedded)
                is EmbeddedSubtitleExtractor.Result.UnsupportedSource -> LoadResult.Error(Kind.UnsupportedEmbedded)
            }
        }

        val file = SubtitleFileResolver.resolve(context, media)
        if (file != null) {
            val cues = SubtitleCueParser.parseFile(file)
            if (cues.isNotEmpty()) return LoadResult.Ok(cues)
            if (file.exists()) return LoadResult.Error(Kind.ParseFailed)
        }

        when (val embedded = EmbeddedSubtitleExtractor.extractForCurrentSpu(context, service, media)) {
            is EmbeddedSubtitleExtractor.Result.Success -> return LoadResult.Ok(embedded.cues)
            is EmbeddedSubtitleExtractor.Result.BitmapTrack -> return LoadResult.Error(Kind.BitmapEmbedded)
            else -> { /* fall through */ }
        }

        return LoadResult.Error(Kind.NoSource)
    }
}
