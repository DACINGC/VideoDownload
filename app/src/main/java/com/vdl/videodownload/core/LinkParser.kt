package com.vdl.videodownload.core

/**
 * 从用户复制/分享的文本中提取链接，并判断属于哪个平台。
 *
 * 纯函数实现，便于在 JVM 单元测试中覆盖真实的分享文案：
 *   - 抖音: "7.43 复制打开抖音，看看【xxx】... https://v.douyin.com/iRNBho5m/ ..."
 *   - 快手: "https://v.kuaishou.com/xxxx 复制此消息..."
 *   - 小红书: "https://xhslink.com/a/xxxxx，复制本条信息..."
 *   - 哔哩哔哩: "【标题-哔哩哔哩】 https://b23.tv/xxxxx"
 */
object LinkParser {

    private val urlRegex = Regex(
        """(?i)\bhttps?://[^\s<>"'，。！？、；：（）【】《》「」『』]+"""
    )

    /** 分享文案里常见的尾部标点，需要从 URL 末尾剥离。 */
    private const val TRAILING_JUNK = ".,;:!?)]}'\"，。！？、；：）】》」』…"

    fun extractUrls(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return urlRegex.findAll(text)
            .map { it.value.trim().trimEnd { c -> c in TRAILING_JUNK } }
            .filter { it.length > 8 && it.contains(".") }
            .map { normalizeScheme(it) }
            .distinct()
            .toList()
    }

    fun firstUrl(text: String?): String? = extractUrls(text).firstOrNull()

    /** 某些平台复制出来是 `http://`，统一升级为 https，避免被重定向拦截。 */
    private fun normalizeScheme(url: String): String =
        if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url

    fun hostOf(url: String): String {
        val withoutScheme = url.substringAfter("://", url)
        return withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfter('@').substringBefore(':').lowercase()
    }

    fun detectPlatform(url: String): Platform {
        val host = hostOf(url)
        return when {
            host.endsWith("douyin.com") || host == "iesdouyin.com" || host.endsWith(".iesdouyin.com") -> Platform.DOUYIN
            host == "b23.tv" || host.endsWith("bilibili.com") || host == "bili2233.cn" || host == "bili22.cn" -> Platform.BILIBILI
            host.endsWith("kuaishou.com") || host == "gifshow.com" || host == "chenzhongtech.com" -> Platform.KUAISHOU
            host == "xhslink.com" || host == "xhslink.cn" || host.endsWith("xiaohongshu.com") -> Platform.XIAOHONGSHU
            host.endsWith("weibo.com") || host == "weibo.cn" || host == "t.cn" -> Platform.WEIBO
            host.endsWith("tiktok.com") -> Platform.TIKTOK
            host.endsWith("youtube.com") || host == "youtu.be" -> Platform.YOUTUBE
            isDirectMedia(url) -> Platform.DIRECT
            else -> Platform.GENERIC
        }
    }

    fun isDirectMedia(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return DIRECT_EXTENSIONS.any { path.endsWith(it) }
    }

    private val DIRECT_EXTENSIONS = listOf(
        ".mp4", ".m3u8", ".flv", ".webm", ".mov", ".mkv", ".m4v", ".ts", ".mpd"
    )

    /** 判断一个 URL 是不是可以交给“远程解析”兜底（抖音、快手等私有协议页）。 */
    fun isShortLink(url: String): Boolean {
        val host = hostOf(url)
        return host in SHORT_HOSTS || (host.endsWith("douyin.com") && url.contains("/share/"))
    }

    private val SHORT_HOSTS = setOf("b23.tv", "v.douyin.com", "v.kuaishou.com", "xhslink.com", "xhslink.cn", "t.cn", "youtu.be")

    /** 平台主页，用作下载时的 Referer。 */
    fun defaultReferer(platform: Platform): String? = when (platform) {
        Platform.BILIBILI -> "https://www.bilibili.com/"
        Platform.DOUYIN -> "https://www.douyin.com/"
        Platform.KUAISHOU -> "https://www.kuaishou.com/"
        Platform.XIAOHONGSHU -> "https://www.xiaohongshu.com/"
        Platform.WEIBO -> "https://weibo.com/"
        Platform.TIKTOK -> "https://www.tiktok.com/"
        Platform.YOUTUBE -> "https://www.youtube.com/"
        else -> null
    }

    /**
     * 把分享文本 / 原始链接整理成可解析的 URL。
     * 如果文本里既有短链又有正文，优先取短链（短链重定向后会带回完整参数）。
     */
    fun pickBestUrl(text: String?): String? {
        val urls = extractUrls(text)
        if (urls.isEmpty()) return null
        return urls.firstOrNull { isShortLink(it) } ?: urls.first()
    }

    // ---------- 各平台的 ID 提取 ----------

    fun douyinAwemeId(url: String): String? {
        val patterns = listOf(
            Regex("""/(?:share/)?(?:video|note|slides)/(\d{15,25})"""),
            // 旧版分享链接：https://www.douyin.com/share/forward/{id}/
            Regex("""/share/forward/(\d{15,25})"""),
            Regex("""[?&](?:modal_id|item_id|aweme_id|vid)=(\d{15,25})"""),
            Regex("""/(\d{15,25})(?:\?|/|$)"""),
            // 兜底：链接路径中的 19 位数字
            Regex("""(?:^|/)(\d{19})(?:/|\?|$)""")
        )
        return patterns.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }
    }

    fun bilibiliBvid(url: String): String? =
        Regex("""(BV[0-9A-Za-z]{10})""").find(url)?.groupValues?.get(1)

    fun bilibiliAid(url: String): Long? =
        Regex("""/video/av(\d+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.toLongOrNull()
            ?: Regex("""[?&]aid=(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull()

    /** 分 P 视频的页码，从 1 开始。 */
    fun bilibiliPage(url: String): Int =
        Regex("""[?&]p=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1

    fun queryParam(url: String, key: String): String? {
        val query = url.substringAfter('?', "")
        if (query.isEmpty()) return null
        return query.split('&')
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null else part.substring(0, idx) to part.substring(idx + 1)
            }
            .firstOrNull { it.first == key }
            ?.second
            ?.let { decodeComponent(it) }
    }

    private fun decodeComponent(value: String): String = try {
        java.net.URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }

    /** 生成一个安全的文件名。 */
    fun sanitizeFileName(name: String, maxLength: Int = 80): String {
        val cleaned = name
            .replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('.')
        val safe = if (cleaned.isBlank()) "video" else cleaned
        return if (safe.length <= maxLength) safe else safe.take(maxLength).trim()
    }
}
