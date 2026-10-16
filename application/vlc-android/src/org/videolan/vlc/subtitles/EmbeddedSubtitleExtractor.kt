/*
 * Extracts timed text cues from embedded (muxed) subtitle tracks in local media files.
 *
 * libVLC on Android renders subtitles to a surface and does not expose timed text via
 * MediaPlayer events; we demux text tracks locally instead.
 */

package org.videolan.vlc.subtitles

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.videolan.libvlc.FactoryManager
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IMediaFactory
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.resources.VLCInstance
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.getAllTracks
import java.io.File

object EmbeddedSubtitleExtractor {

    private val BITMAP_CODEC_HINTS = listOf("pgs", "dvdsub", "vobsub", "dvb", "xsub", "bdpg")

    suspend fun extractForCurrentSpu(
        context: Context,
        service: PlaybackService,
        media: MediaWrapper,
    ): Result = withContext(Dispatchers.IO) {
        val path = localMediaPath(media) ?: return@withContext Result.UnsupportedSource
        val file = File(path)
        if (!file.isFile || !file.canRead()) return@withContext Result.UnsupportedSource

        val spuTrackId = service.spuTrack
        val parsed = parseMediaTracks(context, media.uri) ?: return@withContext Result.ParseFailed
        try {
            val textTracks = parsed.tracks.filterIsInstance<IMedia.SubtitleTrack>()
            if (textTracks.isEmpty()) return@withContext Result.NoTextTracks

            val spuIndex = service.spuTracks?.indexOfFirst { it.getId() == spuTrackId }?.takeIf { it >= 0 }
                ?: if (spuTrackId != "-1") textTracks.indexOfFirst { it.id.toString() == spuTrackId }.takeIf { it >= 0 } else null
            val trackIndex = spuIndex ?: 0
            val selected = textTracks.getOrNull(trackIndex) ?: return@withContext Result.NoTextTracks

            if (isBitmapSubtitle(selected)) return@withContext Result.BitmapTrack

            val mkvTrackNumber = selected.id
            var cues = if (path.lowercase().endsWith(".mkv")) {
                MatroskaTextSubtitleDemuxer.extract(file, mkvTrackNumber)
            } else emptyList()

            if (cues.isEmpty()) {
                cues = MediaExtractorTextSubtitleReader.extract(file, trackIndex)
            }

            if (cues.isEmpty()) return@withContext Result.ExtractionFailed
            Result.Success(cues)
        } finally {
            parsed.media.release()
        }
    }

    fun isEmbeddedSpuSelected(service: PlaybackService, media: MediaWrapper): Boolean {
        val spuId = service.spuTrack
        if (spuId == "-1") return false
        val imedia = service.mediaplayer.media ?: return false
        return imedia.getAllTracks().any {
            it.type == IMedia.Track.Type.Text && it.id.toString() == spuId
        }
    }

    private fun localMediaPath(media: MediaWrapper): String? {
        val uri = media.uri ?: return null
        if (uri.scheme != null && uri.scheme != "file") return null
        return uri.path ?: media.location?.removePrefix("file://")
    }

    private data class ParsedMedia(val media: IMedia, val tracks: List<IMedia.Track>)

    private fun parseMediaTracks(context: Context, uri: Uri): ParsedMedia? {
        val factory = FactoryManager.getFactory(IMediaFactory.factoryId) as IMediaFactory
        val libVlc = VLCInstance.getInstance(context)
        val imedia = factory.getFromUri(libVlc, uri)
        if (!imedia.parse(IMedia.Parse.FetchLocal)) return null
        val tracks = imedia.getAllTracks()
        return ParsedMedia(imedia, tracks)
    }

    private fun isBitmapSubtitle(track: IMedia.SubtitleTrack): Boolean {
        val codec = "${track.codec} ${track.originalCodec} ${track.description}".lowercase()
        return BITMAP_CODEC_HINTS.any { codec.contains(it) }
    }

    sealed class Result {
        data class Success(val cues: List<SubtitleCue>) : Result()
        object UnsupportedSource : Result()
        object NoTextTracks : Result()
        object BitmapTrack : Result()
        object ParseFailed : Result()
        object ExtractionFailed : Result()
    }
}
