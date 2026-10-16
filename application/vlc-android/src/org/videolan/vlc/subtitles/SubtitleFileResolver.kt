/*
 * Resolves an external subtitle file for the current playback session.
 */

package org.videolan.vlc.subtitles

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.vlc.repository.ExternalSubRepository
import org.videolan.vlc.repository.SlaveRepository
import java.io.File

object SubtitleFileResolver {

    private val SUBTITLE_EXTENSIONS = listOf(".srt", ".vtt", ".ass", ".ssa")

    suspend fun resolve(context: Context, media: MediaWrapper): File? = withContext(Dispatchers.IO) {
        val candidates = linkedSetOf<String>()
        media.slaves?.forEach { slave ->
            if (slave.type == IMedia.Slave.Type.Subtitle) candidates.add(slave.uri)
        }
        SlaveRepository.getInstance(context).getSlaves(media.location).forEach { slave ->
            if (slave.type == IMedia.Slave.Type.Subtitle) candidates.add(slave.uri)
        }
        val mediaPath = media.uri.path
        if (mediaPath != null) {
            ExternalSubRepository.getInstance(context).getDownloadedSubtitles(media.uri)
            // LiveData — use DAO path directly via file scan of known external subs folder pattern
            findSiblingSubtitleFiles(mediaPath).forEach { candidates.add(it.absolutePath) }
        }
        candidates
            .mapNotNull { pathToReadableFile(it) }
            .firstOrNull { file -> SUBTITLE_EXTENSIONS.any { file.name.lowercase().endsWith(it) } }
    }

    private fun findSiblingSubtitleFiles(mediaPath: String): List<File> {
        val videoFile = File(mediaPath)
        val parent = videoFile.parentFile ?: return emptyList()
        val baseName = videoFile.nameWithoutExtension
        return parent.listFiles()?.filter { file ->
            file.isFile && file.nameWithoutExtension.startsWith(baseName, ignoreCase = true)
                    && SUBTITLE_EXTENSIONS.any { file.name.lowercase().endsWith(it) }
        } ?: emptyList()
    }

    private fun pathToReadableFile(path: String): File? {
        val decoded = Uri.decode(path)
        return when {
            decoded.startsWith("file:") -> {
                val uri = decoded.toUri()
                uri.path?.let { File(it) }?.takeIf { it.canRead() }
            }
            decoded.startsWith("/") -> File(decoded).takeIf { it.canRead() }
            else -> {
                val asFile = File(decoded)
                if (asFile.canRead()) asFile else null
            }
        }
    }
}
