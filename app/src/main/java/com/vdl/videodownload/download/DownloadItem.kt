package com.vdl.videodownload.download

import android.content.Context
import com.vdl.videodownload.core.LinkParser
import com.vdl.videodownload.core.MediaFormat
import com.vdl.videodownload.core.MediaKind
import com.vdl.videodownload.core.VideoInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class DownloadState {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELED;

    val isActive: Boolean get() = this == QUEUED || this == RUNNING
    val isFinished: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELED
}

@Serializable
data class DownloadItem(
    val id: String,
    val title: String,
    val fileName: String,
    val platformId: String,
    val platformLabel: String,
    val sourceUrl: String = "",
    val coverUrl: String? = null,
    val qualityLabel: String = "",
    val kind: MediaKind = MediaKind.PROGRESSIVE,
    val urls: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val ext: String = "mp4",
    val durationMs: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val state: DownloadState = DownloadState.QUEUED,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val progressFraction: Float = 0f,
    val speedBps: Long = 0L,
    val error: String? = null,
    val savedUri: String? = null,
    val savedPath: String? = null,
    val errorIsPermanent: Boolean = false
) {
    val displayName: String get() = "$fileName.$ext"

    /** 记录显示为已完成，但保存地址已丢失（多为旧版本残留或文件被外部删除）。 */
    val fileMissing: Boolean
        get() = state == DownloadState.COMPLETED && savedUri.isNullOrBlank() && savedPath.isNullOrBlank()

    val progress: Float
        get() = when {
            state == DownloadState.COMPLETED -> 1f
            totalBytes > 0 -> (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            progressFraction > 0f -> progressFraction.coerceIn(0f, 1f)
            else -> 0f
        }

    companion object {
        fun from(
            info: VideoInfo,
            format: MediaFormat
        ): DownloadItem {
            val baseName = LinkParser.sanitizeFileName(
                buildString {
                    append(info.title.ifBlank { "视频" })
                    val author = info.author
                    if (!author.isNullOrBlank()) append(" - ").append(author)
                }
            )
            return DownloadItem(
                id = java.util.UUID.randomUUID().toString(),
                title = info.title.ifBlank { "视频" },
                fileName = baseName,
                platformId = info.platform.id,
                platformLabel = info.platform.label,
                sourceUrl = info.sourceUrl,
                coverUrl = info.coverUrl,
                qualityLabel = format.label,
                kind = format.kind,
                urls = format.allUrls,
                headers = format.headers,
                ext = format.ext,
                durationMs = info.durationMs,
                totalBytes = format.sizeBytes ?: 0L
            )
        }
    }
}

class DownloadStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<DownloadItem> = try {
        val raw = prefs.getString(KEY_ITEMS, null)
        if (raw.isNullOrBlank()) emptyList()
        else json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(DownloadItem.serializer()), raw)
            .map { if (it.state == DownloadState.RUNNING) it.copy(state = DownloadState.QUEUED) else it }
    } catch (_: Exception) {
        emptyList()
    }

    fun save(items: List<DownloadItem>) {
        try {
            val trimmed = items.take(MAX_ITEMS)
            prefs.edit().putString(
                KEY_ITEMS,
                json.encodeToString(kotlinx.serialization.builtins.ListSerializer(DownloadItem.serializer()), trimmed)
            ).apply()
        } catch (_: Exception) {
            // 持久化失败不影响下载本身
        }
    }

    companion object {
        private const val PREFS = "downloads_v1"
        private const val KEY_ITEMS = "items_json"
        private const val MAX_ITEMS = 200
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
