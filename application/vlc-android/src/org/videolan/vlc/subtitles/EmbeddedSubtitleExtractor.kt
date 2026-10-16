/*
 * Extracts timed text cues from embedded (muxed) subtitle tracks in local media files.
 *
 * libVLC on Android renders subtitles to a surface and does not expose timed text via
 * MediaPlayer events; text tracks are demuxed locally instead.
 */

package org.videolan.vlc.subtitles

import android.content.Context
import android.net.Uri
import android.util.Log
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

    private const val TAG = "VLC/AiSub"

    suspend fun extractForCurrentSpu(
        context: Context,
        service: PlaybackService,
        media: MediaWrapper,
    ): Result = withContext(Dispatchers.IO) {
        openLocalFile(context, media)?.use { opened ->
            val file = opened.file
            val spuTrackId = service.spuTrack
            val ordinal = service.spuTracks?.indexOfFirst { it.getId() == spuTrackId }?.takeIf { it >= 0 } ?: -1
            val hints = ArrayList<Int>()
            spuTrackId.toIntOrNull()?.let { hints.add(it) }

            val parsed = tryParseTracks(context, media.uri)
            try {
                val textTracks = parsed?.tracks?.filterIsInstance<IMedia.SubtitleTrack>().orEmpty()
                val matched = when {
                    spuTrackId != "-1" -> textTracks.firstOrNull { it.id.toString() == spuTrackId }
                    else -> null
                } ?: textTracks.getOrNull(ordinal)
                matched?.id?.let { if (it !in hints) hints.add(it) }

                if (isMatroska(file)) {
                    when (val mkv = MatroskaTextSubtitleDemuxer.extractResult(file, hints.toIntArray(), ordinal)) {
                        is MatroskaTextSubtitleDemuxer.Result.Cues -> {
                            Log.i(TAG, "mkv cues=${mkv.cues.size} ordinal=$ordinal")
                            return@withContext Result.Success(mkv.cues)
                        }
                        is MatroskaTextSubtitleDemuxer.Result.Bitmap -> {
                            Log.i(TAG, "mkv bitmap ordinal=$ordinal")
                            return@withContext Result.BitmapTrack
                        }
                        is MatroskaTextSubtitleDemuxer.Result.None -> Log.i(TAG, "mkv none ordinal=$ordinal hints=${hints.size}")
                    }
                }

                val extractorOrdinal = ordinal.coerceAtLeast(0)
                val cues = MediaExtractorTextSubtitleReader.extract(file, extractorOrdinal)
                if (cues.isEmpty()) {
                    Log.i(TAG, "extract-empty matroska=${isMatroska(file)} ordinal=$ordinal")
                    Result.ExtractionFailed
                } else {
                    Result.Success(cues)
                }
            } finally {
                parsed?.media?.release()
            }
        } ?: Result.UnsupportedSource.also { Log.i(TAG, "no-local-file") }
    }

    fun isEmbeddedSpuSelected(service: PlaybackService, media: MediaWrapper): Boolean {
        val spuId = service.spuTrack
        if (spuId == "-1") return false
        val selectedName = service.spuTracks?.firstOrNull { it.getId() == spuId }?.getName().orEmpty()
        if (looksLikeExternalSubtitleFile(selectedName)) return false
        val imedia = service.mediaplayer.media
        if (imedia != null && imedia.getAllTracks().any {
                it.type == IMedia.Track.Type.Text && it.id.toString() == spuId
            }
        ) {
            return true
        }
        val file = directFile(media)
        return file != null && isMatroska(file)
    }

    private fun looksLikeExternalSubtitleFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".srt") || lower.endsWith(".vtt") || lower.endsWith(".ass") ||
            lower.endsWith(".ssa") || lower.contains("/") || lower.contains("\\")
    }

    private class OpenedFile(val file: File, private val closer: () -> Unit) : AutoCloseable {
        override fun close() = closer()
    }

    private fun openLocalFile(context: Context, media: MediaWrapper): OpenedFile? {
        directFile(media)?.let { return OpenedFile(it) {} }
        val uri = media.uri ?: return null
        if (!uri.scheme.equals("content", ignoreCase = true)) return null
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val fdFile = File("/proc/self/fd/${pfd.fd}")
            if (!fdFile.canRead()) {
                pfd.close()
                null
            } else {
                OpenedFile(fdFile) { pfd.close() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun directFile(media: MediaWrapper): File? {
        val candidates = ArrayList<String>()
        media.uri?.let { uri ->
            if (uri.scheme == null || uri.scheme.equals("file", ignoreCase = true)) {
                uri.path?.let { candidates.add(it) }
            }
        }
        media.location?.let { location ->
            when {
                location.startsWith("file:", ignoreCase = true) -> {
                    val path = Uri.parse(location).path ?: location.removePrefix("file://")
                    candidates.add(path)
                }
                location.startsWith("/") -> candidates.add(location)
            }
        }
        for (raw in candidates) {
            val file = File(Uri.decode(raw))
            if (file.isFile && file.canRead()) return file
        }
        return null
    }

    private fun isMatroska(file: File): Boolean {
        val name = file.name.lowercase()
        if (name.endsWith(".mkv") || name.endsWith(".mks") || name.endsWith(".mk3d") || name.endsWith(".webm")) {
            return true
        }
        return try {
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                input.read(magic) == 4 &&
                    magic[0] == 0x1A.toByte() &&
                    magic[1] == 0x45.toByte() &&
                    magic[2] == 0xDF.toByte() &&
                    magic[3] == 0xA3.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    private data class ParsedMedia(val media: IMedia, val tracks: List<IMedia.Track>)

    private fun tryParseTracks(context: Context, uri: Uri): ParsedMedia? {
        return try {
            val factory = FactoryManager.getFactory(IMediaFactory.factoryId) as IMediaFactory
            val libVlc = VLCInstance.getInstance(context)
            val imedia = factory.getFromUri(libVlc, uri)
            if (!imedia.parse(IMedia.Parse.FetchLocal)) {
                imedia.release()
                Log.i(TAG, "media-parse-failed")
                return null
            }
            ParsedMedia(imedia, imedia.getAllTracks())
        } catch (e: Exception) {
            Log.i(TAG, "media-parse-error ${e.javaClass.simpleName}")
            null
        }
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
