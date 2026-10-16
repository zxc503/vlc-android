/*
 * Local storage for DeepSeek API key (runtime overrides BuildConfig).
 */

package org.videolan.vlc.subtitles

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.videolan.vlc.BuildConfig

object DeepSeekApiKeyStore {

    private const val PREFS_NAME = "deepseek_api_key_secure"
    private const val PREF_API_KEY = "api_key"

    fun getApiKey(context: Context): String {
        val runtime = readRuntimeKey(context)?.trim().orEmpty()
        if (runtime.isNotEmpty()) return runtime
        return BuildConfig.DEEPSEEK_API_KEY.trim()
    }

    fun setApiKey(context: Context, key: String) {
        securePrefs(context).edit().putString(PREF_API_KEY, key.trim()).apply()
    }

    fun hasRuntimeKey(context: Context): Boolean = !readRuntimeKey(context).isNullOrBlank()

    fun maskedSummary(context: Context): String {
        val key = readRuntimeKey(context)?.trim().orEmpty()
        if (key.isEmpty()) return ""
        return when {
            key.length <= 8 -> "****"
            else -> "${key.take(3)}****${key.takeLast(4)}"
        }
    }

    private fun readRuntimeKey(context: Context): String? =
        securePrefs(context).getString(PREF_API_KEY, null)

    private fun securePrefs(context: Context): SharedPreferences {
        val appContext = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } else {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
}
