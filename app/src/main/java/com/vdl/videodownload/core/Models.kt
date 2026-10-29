package com.vdl.videodownload.core

/**
 * 支持的平台。id 用于持久化与远程解析接口的参数，label 用于界面展示。
 */
enum class Platform(val id: String, val label: String, val colorHex: Long) {
    DOUYIN("douyin", "抖音", 0xFF161823),
    BILIBILI("bilibili", "哔哩哔哩", 0xFFFB7299),
    KUAISHOU("kuaishou", "快手", 0xFFFF4906),
    XIAOHONGSHU("xiaohongshu", "小红书", 0xFFFF2442),
    WEIBO("weibo", "微博", 0xFFE6162D),
    TIKTOK("tiktok", "TikTok", 0xFF000000),
    YOUTUBE("youtube", "YouTube", 0xFFFF0000),
    DIRECT("direct", "直链", 0xFF00897B),
    GENERIC("generic", "网页视频", 0xFF607D8B),
    REMOTE("remote", "远程解析", 0xFF7C4DFF);

    companion object {
        fun fromId(id: String?): Platform =
            entries.firstOrNull { it.id == id } ?: GENERIC
    }
}

/** 媒体的封装类型。 */
enum class MediaKind {
    /** 完整的渐进式文件（mp4 / flv / webm ...），可直接顺序下载 */
    PROGRESSIVE,

    /** HLS（m3u8），需要按分片下载后拼接 */
    HLS,

    /** DASH，需要分别下载音视频轨后封装（本应用仅作提示，不自动合并） */
    DASH
}

/**
 * 一个可下载的清晰度 / 码流。
 *
 * [segments] 用于 B 站等平台的“分段视频”：URL 会被按顺序下载并拼接为同一个文件。
 * [audioUrl] 仅用于 DASH 场景，目前只做信息展示。
 */
data class MediaFormat(
    val id: String,
    val label: String,
    val kind: MediaKind,
    val url: String,
    val segments: List<String> = emptyList(),
    val audioUrl: String? = null,
    val sizeBytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val ext: String = "mp4",
    val headers: Map<String, String> = emptyMap()
) {
    /** 需要实际下载的全部地址（分段视频会有多个）。 */
    val allUrls: List<String>
        get() = if (segments.isNotEmpty()) segments else listOf(url)
}

/** 解析结果。 */
data class VideoInfo(
    val sourceUrl: String,
    val platform: Platform,
    val title: String,
    val author: String? = null,
    val coverUrl: String? = null,
    val durationMs: Long? = null,
    val desc: String? = null,
    val formats: List<MediaFormat>,
    /** 下载时需要的 Referer，部分平台不带会 403 */
    val referer: String? = null,
    val userAgent: String? = null,
    val extractor: String = ""
) {
    val bestFormat: MediaFormat?
        get() = formats.maxByOrNull { f -> (f.height ?: 0) * 1000L + (f.sizeBytes ?: 0L) / 1024 }
}

/** 解析失败时抛出，message 会直接展示给用户。 */
class ExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 下载/解析过程中的简单日志收集，便于在界面上展示“为什么失败”。 */
class DebugLog(private val maxLines: Int = 60) {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        lines.addLast(line)
        while (lines.size > maxLines) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()
}

fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes <= 0) return "未知"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}

fun formatDuration(ms: Long?): String {
    if (ms == null || ms <= 0) return "--:--"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}
