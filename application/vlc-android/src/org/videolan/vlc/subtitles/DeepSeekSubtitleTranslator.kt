/*
 * DeepSeek chat-completions client for English → Chinese subtitle translation.
 */

package org.videolan.vlc.subtitles

import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.videolan.vlc.BuildConfig
import java.util.concurrent.TimeUnit

class DeepSeekSubtitleTranslator(
    private val apiKey: String = BuildConfig.DEEPSEEK_API_KEY,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val cache = LruCache<String, String>(500)
    private val inFlight = HashMap<String, Mutex>()
    private val inFlightGuard = Mutex()

    fun isConfigured(): Boolean = apiKey.isNotBlank()

    suspend fun translate(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        cache.get(trimmed)?.let { return it }
        val mutex = inFlightGuard.withLock {
            inFlight.getOrPut(trimmed) { Mutex() }
        }
        return mutex.withLock {
            cache.get(trimmed)?.let { return@withLock it }
            val result = requestTranslation(listOf(trimmed))?.firstOrNull()
            if (result != null) cache.put(trimmed, result)
            result
        }.also {
            inFlightGuard.withLock { inFlight.remove(trimmed) }
        }
    }

    /** Prefetch translations for upcoming cues (single batched API call). */
    suspend fun prefetch(texts: List<String>) {
        val pending = texts.map { it.trim() }.filter { it.isNotEmpty() && cache.get(it) == null }.distinct()
        if (pending.isEmpty() || !isConfigured()) return
        val translated = requestTranslation(pending) ?: return
        pending.zip(translated).forEach { (source, target) ->
            if (target.isNotBlank()) cache.put(source, target)
        }
    }

    private suspend fun requestTranslation(lines: List<String>): List<String>? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext null
        try {
            val numbered = lines.mapIndexed { index, line -> "${index + 1}. $line" }.joinToString("\n")
            val body = JSONObject().apply {
                put("model", MODEL_ID)
                put("stream", false)
                put("temperature", 0.3)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put(
                            "content",
                            "You translate English video subtitles to Simplified Chinese. " +
                                "Return exactly one translated line per input line, same order and count. " +
                                "Output only the translations, no numbering or explanations."
                        )
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", numbered)
                    })
                })
            }
            val request = Request.Builder()
                .url("$BASE_URL/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val content = JSONObject(response.body?.string() ?: return@withContext null)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
                splitTranslationResponse(content, lines.size)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun splitTranslationResponse(content: String, expectedCount: Int): List<String> {
        val rawLines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val stripped = rawLines.map { line ->
            line.replaceFirst(Regex("^\\d+[.)]\\s*"), "").trim()
        }
        return when {
            stripped.size == expectedCount -> stripped
            expectedCount == 1 -> listOf(content.replace("\n", " ").trim())
            else -> stripped.take(expectedCount).ifEmpty { listOf(content) }
        }
    }

    companion object {
        private const val BASE_URL = "https://api.deepseek.com"
        private const val MODEL_ID = "deepseek-flash"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
