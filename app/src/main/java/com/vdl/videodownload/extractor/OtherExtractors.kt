package com.vdl.videodownload.extractor

import com.vdl.videodownload.core.ExtractionException
import com.vdl.videodownload.core.LinkParser
import com.vdl.videodownload.core.MediaFormat
import com.vdl.videodownload.core.MediaKind
import com.vdl.videodownload.core.Platform
import com.vdl.videodownload.core.VideoInfo
import com.vdl.videodownload.core.formatBytes
import com.vdl.videodownload.net.Headers
import com.vdl.videodownload.net.UserAgents
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

// ---------------------------------------------------------------------------
// 直链
// ---------------------------------------------------------------------------

class DirectExtractor : Extractor {
    override val name: String = "直链"

    override fun supports(url: String, platform: Platform): Boolean =
        platform == Platform.DIRECT || LinkParser.isDirectMedia(url)

    override suspend fun extract(url: String, ctx: ExtractContext): VideoInfo {
        val ua = ctx.settings.customUserAgent.ifBlank { UserAgents.DESKTOP }
        val path = url.substringBefore('?')
        val ext = path.substringAfterLast('.', "mp4").lowercase()
        val fileName = LinkParser.sanitizeFileName(
            java.net.URLDecoder.decode(url.substringAfterLast('/').substringBefore('?'), "UTF-8")
        )

        if (ext == "m3u8") {
            return VideoInfo(
                sourceUrl = url,
                platform = Platform.DIRECT,
                title = fileName.ifBlank { "HLS 视频" },
                formats = listOf(
                    MediaFormat(
                        id = "hls-direct",
                        label = "HLS 直链（自动合并分片）",
                        kind = MediaKind.HLS,
                        url = url,
                        ext = "mp4",
                        headers = mapOf("User-Agent" to ua)
                    )
                ),
                userAgent = ua,
                extractor = name
            )
        }

        val head = ctx.web.head(url, mapOf("User-Agent" to ua))
        val isVideoType = head.contentType?.startsWith("video/") == true ||
            head.contentType?.contains("octet-stream") == true
        if (head.code !in listOf(200, 206, 302, 403, 405, 416) ) {
            ctx.note("HEAD 返回 HTTP ${head.code}，继续尝试下载")
        }
        if (!isVideoType && head.code == -1) {
            throw ExtractionException("无法访问该直链，请确认链接有效")
        }
        ctx.note("直链大小: ${formatBytes(head.contentLength)}")

        return VideoInfo(
            sourceUrl = url,
            platform = Platform.DIRECT,
            title = fileName.ifBlank { "视频" },
            formats = listOf(
                MediaFormat(
                    id = "direct-${ext}",
                    label = "原始文件 · ${formatBytes(head.contentLength)}",
                    kind = MediaKind.PROGRESSIVE,
                    url = url,
                    sizeBytes = head.contentLength,
                    ext = if (ext.length in 2..4) ext else "mp4",
                    headers = mapOf("User-Agent" to ua)
                )
            ),
            userAgent = ua,
            extractor = name
        )
    }
}

// ---------------------------------------------------------------------------
// 通用网页解析（og:video / <video> / JSON-LD / .mp4 正则）
// ---------------------------------------------------------------------------

class GenericHtmlExtractor : Extractor {
    override val name: String = "通用网页"

    override fun supports(url: String, platform: Platform): Boolean = true

    override suspend fun extract(url: String, ctx: ExtractContext): VideoInfo {
        val ua = ctx.settings.customUserAgent.ifBlank { UserAgents.DESKTOP }
        val result = ctx.web.get(url, Headers.web(ua))
        if (!result.isSuccessful) throw ExtractionException("页面请求失败 HTTP ${result.code}")

        val contentType = result.contentType.orEmpty()
        if (contentType.startsWith("video/") || contentType.contains("application/octet-stream")) {
            return VideoInfo(
                sourceUrl = url,
                platform = Platform.DIRECT,
                title = LinkParser.sanitizeFileName(url.substringAfterLast('/')),
                formats = listOf(
                    MediaFormat(
                        id = "generic-direct",
                        label = "原始文件 · ${formatBytes(result.contentLength)}",
                        kind = MediaKind.PROGRESSIVE,
                        url = result.finalUrl,
                        sizeBytes = result.contentLength,
                        headers = mapOf("User-Agent" to ua)
                    )
                ),
                userAgent = ua,
                extractor = name
            )
        }

        val html = result.body
        val candidates = LinkedHashSet<String>()
        listOf(
            Html.meta(html, "og:video:secure_url", "og:video:url", "og:video"),
            Html.meta(html, "twitter:player:stream"),
            Html.meta(html, "video_src", "videoSrc")
        ).filterNotNull().forEach { candidates += it }
        candidates += Html.videoTagSources(html)
        candidates += Html.jsonLdContentUrls(html)
        candidates += Html.mediaUrls(html)

        val normalized = candidates
            .map { Html.decodeEntities(Html.escapeHex(it)) }
            .map { if (it.startsWith("//")) "https:$it" else it }
            .filter { it.startsWith("http") }
            .filterNot { it.contains("favicon") || it.endsWith(".gif") }
            .distinct()

        if (normalized.isEmpty()) {
            throw ExtractionException(
                "页面中未找到可直接下载的视频地址（可能需要登录，或该站点使用加密播放协议）。\n" +
                    "可在设置中配置「远程解析接口」作为兜底。"
            )
        }

        val referer = result.finalUrl.substringBefore('?')
        val formats = normalized.mapIndexedNotNull { index, mediaUrl ->
            val lower = mediaUrl.lowercase()
            when {
                lower.contains(".m3u8") -> MediaFormat(
                    id = "generic-hls-$index",
                    label = "HLS 流 · 自动合并",
                    kind = MediaKind.HLS,
                    url = mediaUrl,
                    headers = mapOf("User-Agent" to ua, "Referer" to referer)
                )
                lower.contains(".mpd") -> null
                else -> MediaFormat(
                    id = "generic-mp4-$index",
                    label = "直链视频 ${index + 1}",
                    kind = MediaKind.PROGRESSIVE,
                    url = mediaUrl,
                    ext = mediaUrl.substringBefore('?').substringAfterLast('.', "mp4").let {
                        if (it.length in 2..4) it else "mp4"
                    },
                    headers = mapOf("User-Agent" to ua, "Referer" to referer)
                )
            }
        }

        if (formats.isEmpty()) throw ExtractionException("只找到 DASH(mpd) 流，暂不支持在线合并")

        val title = Html.meta(html, "og:title")
            ?: Html.title(html)
            ?: LinkParser.sanitizeFileName(url.substringAfterLast('/'))
        val cover = Html.meta(html, "og:image")

        ctx.note("通用解析命中 ${formats.size} 个地址")
        return VideoInfo(
            sourceUrl = url,
            platform = Platform.GENERIC,
            title = title,
            coverUrl = cover,
            formats = formats,
            referer = referer,
            userAgent = ua,
            extractor = name
        )
    }
}

// ---------------------------------------------------------------------------
// 快手
// ---------------------------------------------------------------------------

class KuaishouExtractor : Extractor {
    override val name: String = "快手"

    override fun supports(url: String, platform: Platform): Boolean = platform == Platform.KUAISHOU

    override suspend fun extract(url: String, ctx: ExtractContext): VideoInfo {
        val ua = ctx.settings.customUserAgent.ifBlank { UserAgents.IPHONE }
        var target = url
        if (LinkParser.hostOf(url) == "v.kuaishou.com") {
            val resolved = ctx.web.get(url, Headers.mobile(ua))
            if (resolved.finalUrl.isNotBlank()) target = resolved.finalUrl
            ctx.note("短链跳转: $target")
        }

        val desktopUa = UserAgents.DESKTOP
        val page = ctx.web.get(target, Headers.web(desktopUa, "https://www.kuaishou.com/"))
        val html = Html.escapeHex(page.body)
        ctx.note("页面 HTTP ${page.code}，长度 ${html.length}")

        val playUrl = firstMatch(html, "\"srcNoMark\"\\s*:\\s*\"([^\"]+)\"")
            ?: firstMatch(html, "\"photoUrl\"\\s*:\\s*\"([^\"]+)\"")
            ?: firstMatch(html, "\"playUrl\"\\s*:\\s*\"([^\"]+)\"")
            ?: firstMatch(html, "\"mainMvUrls\"\\s*:\\s*\\[\\s*\\{[^}]*\"url\"\\s*:\\s*\"([^\"]+)\"")

        if (playUrl == null) {
            throw ExtractionException("未在快手页面中找到视频地址（可能需要登录或链接已失效）")
        }

        val title = firstMatch(html, "\"caption\"\\s*:\\s*\"([^\"]+)\"")
            ?: Html.meta(page.body, "og:title")
            ?: Html.title(page.body)
            ?: "快手视频"
        val cover = firstMatch(html, "\"coverUrl\"\\s*:\\s*\"([^\"]+)\"")
            ?: Html.meta(page.body, "og:image")

        return VideoInfo(
            sourceUrl = url,
            platform = Platform.KUAISHOU,
            title = Html.decodeEntities(title).replace("\\n", " "),
            author = firstMatch(html, "\"userName\"\\s*:\\s*\"([^\"]+)\""),
            coverUrl = cover,
            formats = listOf(
                MediaFormat(
                    id = "kuaishou-mp4",
                    label = "无水印 MP4",
                    kind = MediaKind.PROGRESSIVE,
                    url = playUrl,
                    headers = mapOf("User-Agent" to desktopUa, "Referer" to "https://www.kuaishou.com/")
                )
            ),
            referer = "https://www.kuaishou.com/",
            userAgent = desktopUa,
            extractor = name
        )
    }

    private fun firstMatch(source: String, pattern: String): String? =
        Regex(pattern).find(source)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
}

// ---------------------------------------------------------------------------
// 小红书
// ---------------------------------------------------------------------------

class XiaohongshuExtractor : Extractor {
    override val name: String = "小红书"

    override fun supports(url: String, platform: Platform): Boolean = platform == Platform.XIAOHONGSHU

    override suspend fun extract(url: String, ctx: ExtractContext): VideoInfo {
        val ua = ctx.settings.customUserAgent.ifBlank { UserAgents.DESKTOP }
        var target = url
        val host = LinkParser.hostOf(url)
        if (host == "xhslink.com" || host == "xhslink.cn") {
            val resolved = ctx.web.get(url, Headers.mobile(ua))
            if (resolved.finalUrl.isNotBlank()) target = resolved.finalUrl
            ctx.note("短链跳转: $target")
        }

        val page = ctx.web.get(target, Headers.web(ua, "https://www.xiaohongshu.com/"))
        val html = page.body
        ctx.note("页面 HTTP ${page.code}，长度 ${html.length}")

        val noteId = Regex("""/(?:explore|discovery/item|item)/([0-9a-zA-Z]{16,32})""")
            .find(target)?.groupValues?.get(1)

        val stateJson = extractInlineJson(html, "__INITIAL_STATE__")?.replace("undefined", "null")
        val state = parseJsonOrNull(stateJson)

        val note = state?.let { root ->
            val detailMap = root.deepFind { it["noteDetailMap"] is JsonObject }?.obj("noteDetailMap")
            val entry = if (noteId != null && detailMap?.get(noteId) != null) {
                detailMap[noteId]
            } else {
                detailMap?.values?.firstOrNull()
            }
            (entry as? JsonObject)?.obj("note")
        }

        val videoObj = note?.obj("video")
        val stream = videoObj?.obj("media")?.obj("stream")
        val h264 = stream?.arr("h264")?.mapNotNull { it as? JsonObject }.orEmpty()
        val h265 = stream?.arr("h265")?.mapNotNull { it as? JsonObject }.orEmpty()
        val masterUrl = (h264 + h265)
            .firstNotNullOfOrNull { it.str("masterUrl") ?: it.str("url") }
            ?: firstMatch(html, "\"masterUrl\"\\s*:\\s*\"([^\"]+)\"")
            ?: firstMatch(html, "\"originVideoKey\"\\s*:\\s*\"([^\"]+)\"")
                ?.let { "https://sns-video-bd.xhscdn.com/$it" }

        if (masterUrl.isNullOrBlank()) {
            if (note?.arr("imageList") != null) {
                throw ExtractionException("该链接是小红书图文笔记，不含视频")
            }
            throw ExtractionException("未在小红书页面中找到视频地址（可能已删除或需要登录）")
        }

        val title = note?.str("title")?.takeIf { it.isNotBlank() }
            ?: note?.str("desc")?.take(60)
            ?: Html.title(html)
            ?: "小红书视频"
        val cover = note?.obj("video")?.obj("image")?.let { image ->
            image.str("firstFrame") ?: image.arr("thumbnail")?.firstOrNull()?.asString()
        } ?: Html.meta(html, "og:image")

        return VideoInfo(
            sourceUrl = url,
            platform = Platform.XIAOHONGSHU,
            title = Html.decodeEntities(title),
            author = note?.obj("user")?.str("nickname"),
            coverUrl = cover,
            durationMs = videoObj?.obj("capa")?.long("duration")?.times(1000),
            desc = note?.str("desc"),
            formats = listOf(
                MediaFormat(
                    id = "xhs-mp4",
                    label = "无水印 MP4",
                    kind = MediaKind.PROGRESSIVE,
                    url = masterUrl,
                    headers = mapOf("User-Agent" to ua, "Referer" to "https://www.xiaohongshu.com/")
                )
            ),
            referer = "https://www.xiaohongshu.com/",
            userAgent = ua,
            extractor = name
        )
    }

    private fun firstMatch(source: String, pattern: String): String? =
        Regex(pattern).find(Html.escapeHex(source))?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
}

// ---------------------------------------------------------------------------
// 微博
// ---------------------------------------------------------------------------

class WeiboExtractor : Extractor {
    override val name: String = "微博"

    override fun supports(url: String, platform: Platform): Boolean = platform == Platform.WEIBO

    override suspend fun extract(url: String, ctx: ExtractContext): VideoInfo {
        val ua = ctx.settings.customUserAgent.ifBlank { UserAgents.DESKTOP }
        val target = normalizeWeiboUrl(url)
        val page = ctx.web.get(target, Headers.web(ua, "https://weibo.com/"))
        val html = page.body
        ctx.note("页面 HTTP ${page.code}，长度 ${html.length}")

        val candidates = LinkedHashSet<String>()
        Regex("\"stream_url\"\\s*:\\s*\"([^\"]+)\"").findAll(html).forEach { candidates += it.groupValues[1] }
        Regex("\"play_url\"\\s*:\\s*\"([^\"]+)\"").findAll(html).forEach { candidates += it.groupValues[1] }
        Regex("\"mp4_(\\d{3,4})p?_mp4\"\\s*:\\s*\"([^\"]+)\"").findAll(html).forEach {
            candidates += it.groupValues[2]
        }
        Html.meta(html, "og:video", "og:video:url")?.let { candidates += it }
        Html.videoTagSources(html).forEach { candidates += it }

        val urls = candidates
            .map { Html.decodeEntities(Html.escapeHex(it)) }
            .map { if (it.startsWith("//")) "https:$it" else it }
            .filter { it.startsWith("http") }
            .distinct()

        if (urls.isEmpty()) throw ExtractionException("未在微博页面中找到视频地址（可能已删除或需要登录）")

        val title = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?: Html.meta(html, "og:title")
            ?: Html.title(html)
            ?: "微博视频"

        return VideoInfo(
            sourceUrl = url,
            platform = Platform.WEIBO,
            title = Html.decodeEntities(title),
            coverUrl = Html.meta(html, "og:image"),
            formats = urls.mapIndexed { index, videoUrl ->
                MediaFormat(
                    id = "weibo-$index",
                    label = if (index == 0) "原始画质" else "备用线路 ${index + 1}",
                    kind = MediaKind.PROGRESSIVE,
                    url = videoUrl,
                    headers = mapOf("User-Agent" to ua, "Referer" to "https://weibo.com/")
                )
            },
            referer = "https://weibo.com/",
            userAgent = ua,
            extractor = name
        )
    }

    /** weibo.com/tv/show/1034:xxx 与 video.weibo.com/show?fid= 是同一个页面。 */
    private fun normalizeWeiboUrl(url: String): String {
        val tvShow = Regex("""weibo\.com/tv/show/(\d+):(\d+)""").find(url)
        if (tvShow != null) {
            return "https://video.weibo.com/show?fid=${tvShow.groupValues[1]}:${tvShow.groupValues[2]}"
        }
        return url
    }
}
