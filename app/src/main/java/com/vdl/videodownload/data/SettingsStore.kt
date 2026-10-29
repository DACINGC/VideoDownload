package com.vdl.videodownload.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AppSettings(
    val autoClipboard: Boolean = true,
    val maxConcurrent: Int = 2,
    val wifiOnly: Boolean = false,
    val bilibiliCookie: String = "",
    val douyinCookie: String = "",
    val customUserAgent: String = "",
    val remoteEnabled: Boolean = false,
    /** cobalt | generic */
    val remoteType: String = "cobalt",
    val remoteBase: String = "",
    val remoteToken: String = "",
    val saveSubDir: String = "VideoDownload"
) {
    val effectiveUserAgent: String
        get() = customUserAgent.ifBlank { "" }
}

class SettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    private fun load(): AppSettings = try {
        val raw = prefs.getString(KEY_JSON, null)
        if (raw.isNullOrBlank()) AppSettings() else json.decodeFromString(AppSettings.serializer(), raw)
    } catch (_: Exception) {
        AppSettings()
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit().putString(KEY_JSON, json.encodeToString(AppSettings.serializer(), next)).apply()
    }

    fun setBilibiliCookie(value: String) = update { it.copy(bilibiliCookie = value.trim()) }
    fun setDouyinCookie(value: String) = update { it.copy(douyinCookie = value.trim()) }
    fun setUserAgent(value: String) = update { it.copy(customUserAgent = value.trim()) }
    fun setAutoClipboard(value: Boolean) = update { it.copy(autoClipboard = value) }
    fun setMaxConcurrent(value: Int) = update { it.copy(maxConcurrent = value.coerceIn(1, 4)) }
    fun setWifiOnly(value: Boolean) = update { it.copy(wifiOnly = value) }
    fun setRemoteEnabled(value: Boolean) = update { it.copy(remoteEnabled = value) }
    fun setRemoteType(value: String) = update { it.copy(remoteType = value) }
    fun setRemoteBase(value: String) = update { it.copy(remoteBase = value.trim()) }
    fun setRemoteToken(value: String) = update { it.copy(remoteToken = value.trim()) }

    fun toExtractSettings(): com.vdl.videodownload.extractor.ExtractSettings {
        val s = _settings.value
        return com.vdl.videodownload.extractor.ExtractSettings(
            bilibiliCookie = s.bilibiliCookie,
            douyinCookie = s.douyinCookie,
            customUserAgent = s.customUserAgent,
            remoteEnabled = s.remoteEnabled,
            remoteType = s.remoteType,
            remoteBase = s.remoteBase,
            remoteToken = s.remoteToken
        )
    }

    companion object {
        private const val PREFS = "settings_v1"
        private const val KEY_JSON = "settings_json"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
