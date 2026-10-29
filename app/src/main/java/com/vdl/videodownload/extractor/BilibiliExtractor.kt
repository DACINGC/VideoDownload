package com.vdl.videodownload.extractor

import com.vdl.videodownload.core.ExtractionException
import com.vdl.videodownload.core.LinkParser
import com.vdl.videodownload.core.MediaFormat
import com.vdl.videodownload.core.MediaKind
import com.vdl.videodownload.core.Platform
import com.vdl.videodownload.core.VideoInfo
import com.vdl.videodownload.net.Headers
import com.vdl.videodownload.net.UserAgents
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

data class BiliPage(val cid: Long, val page: Int, val part: String?)

data class BiliView(
    val bvid: String,
    val aid: Long,
    val title: String,
    val cover: String?,
    val author: String?,
    val durationMs: Long?,
    val desc: String?,
    val pages: List<BiliPage>
)

data class BiliStream(
    val quality: Int,
    val urls: List<String>,
    val sizeBytes: Long?,
    val acceptQuality: List<Int>
)

/**
 * 哔哩哔哩解析。
 *
 * 流程：
 *  1. b23.tv 短链跟随跳转拿到 BV 号；
 *  2. `x/web-interface/view` 获取标题/封面/UP 主/分P；
 *  3. `x/player/playurl` 取 MP4 直链（fnval=1 为渐进式 MP4，可直接下载）。
 */
class BilibiliExtractor : Extractor {

    override val name: String = "哔哩哔哩"

    override fun supports(url: String, platform: Platform): Boolean = platform == Platform.BILIBILI

    override suspend fun extract(url: String, ctx: ExtractContext): VideoInfo {
        val ua = ctx.settings.customUserAgent.ifBlank { UserAgents.DESKTOP }
        if (ctx.settings.bilibiliCookie.isNotBlank()) {
            ctx.web.cookieJar.preload(".bilibili.com", ctx.settings.bilibiliCookie)
            ctx.note("已载入哔哩哔哩 Cookie")
        }

        var target = url
        val host = LinkParser.hostOf(url)
        if (host == "b23.tv" || host == "bili2233.cn") {
            val resolved = ctx.web.get(url, Headers.web(ua, "https://www.bilibili.com/"))
            if (resolved.finalUrl.isNotBlank()) target = resolved.finalUrl
            ctx.note("短链跳转: $target")
        }

        val epId = Regex("""/bangumi/play/ep(\d+)""").find(target)?.groupValues?.get(1)
        if (epId != null) {
            return extractBangumi(epId, target, ua, ctx)
        }

        val bvid = LinkParser.bilibiliBvid(target)
        val aid = LinkParser.bilibiliAid(target)
        if (bvid == null && aid == null) {
            throw ExtractionException("无法从链接中识别 BV 号或 av 号")
        }

        val api = if (bvid != null) {
            "https://api.bilibili.com/x/web-interface/view?bvid=$bvid"
        } else {
            "https://api.bilibili.com/x/web-interface/view?aid=$aid"
        }
        val viewResult = ctx.web.get(api, Headers.json(ua, "https://www.bilibili.com/"))
        val view = parseView(parseJsonOrNull(viewResult.body)
            ?: throw ExtractionException("哔哩哔哩接口未返回 JSON（HTTP ${viewResult.code}）"))

        val pageIndex = LinkParser.bilibiliPage(target)
        val page = view.pages.getOrNull(pageIndex - 1) ?: view.pages.firstOrNull()
        val cid = page?.cid ?: throw ExtractionException("未找到视频 cid（可能是番剧或已下架内容）")
        ctx.note("视频: ${view.title} / cid=$cid")

        val referer = "https://www.bilibili.com/video/${view.bvid.ifBlank { bvid ?: "" }}"
        val formats = fetchFormats(view.bvid, cid, ua, referer, ctx)

        val titleSuffix = if (view.pages.size > 1 && page != null) " - P${page.page} ${page.part.orEmpty()}".trimEnd() else ""
        return VideoInfo(
            sourceUrl = url,
            platform = Platform.BILIBILI,
            title = view.title + titleSuffix,
            author = view.author,
            coverUrl = view.cover,
            durationMs = view.durationMs,
            desc = view.desc,
            formats = formats,
            referer = referer,
            userAgent = ua,
            extractor = name
        )
    }

    private suspend fun extractBangumi(epId: String, sourceUrl: String, ua: String, ctx: ExtractContext): VideoInfo {
        val api = "https://api.bilibili.com/pgc/view/web/season?ep_id=$epId"
        val result = ctx.web.get(api, Headers.json(ua, "https://www.bilibili.com/"))
        val root = parseJsonOrNull(result.body) as? JsonObject
            ?: throw ExtractionException("番剧接口未返回 JSON（HTTP ${result.code}）")
        val code = root.int("code") ?: -1
        if (code != 0) throw ExtractionException("番剧接口错误[$code] ${root.str("message")}")

        val episodes = root.deepFind { it["episodes"] is JsonArray }?.arr("episodes")
            ?: throw ExtractionException("未找到番剧剧集信息")
        val episode = episodes.mapNotNull { it as? JsonObject }
            .firstOrNull { it.str("id") == epId || it.long("ep_id") == epId.toLongOrNull() }
            ?: episodes.firstOrNull() as? JsonObject
            ?: throw ExtractionException("未找到剧集")

        val bvid = episode.str("bvid") ?: throw ExtractionException("剧集缺少 bvid")
        val cid = episode.long("cid") ?: throw ExtractionException("剧集缺少 cid")
        val title = episode.str("share_copy")
            ?: episode.str("long_title")
            ?: episode.str("title")
            ?: "番剧"
        val cover = episode.str("cover")
        val durationMs = (episode.long("duration") ?: 0) * 1000

        val referer = "https://www.bilibili.com/bangumi/play/ep$epId"
        val formats = fetchFormats(bvid, cid, ua, referer, ctx)

        return VideoInfo(
            sourceUrl = sourceUrl,
            platform = Platform.BILIBILI,
            title = title,
            author = null,
            coverUrl = cover,
            durationMs = durationMs,
            formats = formats,
            referer = referer,
            userAgent = ua,
            extractor = name
        )
    }

    private suspend fun fetchFormats(
        bvid: String,
        cid: Long,
        ua: String,
        referer: String,
        ctx: ExtractContext
    ): List<MediaFormat> {
        val first = requestPlayUrl(bvid, cid, ua, referer, qn = 116, ctx = ctx)
        val candidates = buildList {
            add(first)
            val accept = first.acceptQuality.filter { it != first.quality }
                .sortedDescending()
                .take(2)
            accept.forEach { qn -> add(requestPlayUrl(bvid, cid, ua, referer, qn, ctx)) }
        }

        val formats = candidates
            .distinctBy { it.quality }
            .sortedByDescending { it.quality }
            .map { stream ->
                MediaFormat(
                    id = "bili-$cid-${stream.quality}",
                    label = "${qualityLabel(stream.quality)} · MP4",
                    kind = MediaKind.PROGRESSIVE,
                    url = stream.urls.first(),
                    segments = if (stream.urls.size > 1) stream.urls else emptyList(),
                    sizeBytes = stream.sizeBytes,
                    height = qualityHeight(stream.quality),
                    ext = "mp4",
                    headers = mapOf("Referer" to referer, "User-Agent" to ua)
                )
            }
            .ifEmpty { throw ExtractionException("未获取到可下载的视频地址，可能需要登录 Cookie") }

        ctx.note("清晰度: " + formats.joinToString { it.label })
        return formats
    }

    private suspend fun requestPlayUrl(
        bvid: String,
        cid: Long,
        ua: String,
        referer: String,
        qn: Int,
        ctx: ExtractContext
    ): BiliStream {
        val api = buildString {
            append("https://api.bilibili.com/x/player/playurl?bvid=").append(bvid)
            append("&cid=").append(cid)
            append("&qn=").append(qn)
            append("&fnval=1&fourk=1&platform=html5&high_quality=1")
        }
        val result = ctx.web.get(api, Headers.json(ua, referer))
        val json = parseJsonOrNull(result.body)
            ?: throw ExtractionException("playurl 未返回 JSON（HTTP ${result.code}）")
        return parsePlayUrl(json)
    }

    companion object {
        fun parseView(json: JsonElement): BiliView {
            val root = json as? JsonObject ?: throw ExtractionException("哔哩哔哩返回数据格式异常")
            val code = root.int("code") ?: -1
            if (code != 0) {
                val msg = root.str("message") ?: "未知错误"
                throw ExtractionException("哔哩哔哩接口返回错误[$code] $msg")
            }
            val data = root.obj("data") ?: throw ExtractionException("哔哩哔哩返回缺少 data 字段")
            val pages = data.arr("pages")?.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val cid = obj.long("cid") ?: return@mapNotNull null
                BiliPage(cid = cid, page = obj.int("page") ?: 1, part = obj.str("part"))
            } ?: emptyList()

            return BiliView(
                bvid = data.str("bvid") ?: "",
                aid = data.long("aid") ?: 0,
                title = data.str("title") ?: "未命名视频",
                cover = data.str("pic"),
                author = data.obj("owner")?.str("name"),
                durationMs = (data.long("duration") ?: 0L).takeIf { it > 0 }?.times(1000),
                desc = data.str("desc"),
                pages = pages.ifEmpty {
                    listOfNotNull(
                        data.long("cid")?.let { BiliPage(cid = it, page = 1, part = data.str("title")) }
                    )
                }
            )
        }

        fun parsePlayUrl(json: JsonElement): BiliStream {
            val root = json as? JsonObject ?: throw ExtractionException("playurl 返回格式异常")
            val code = root.int("code") ?: -1
            if (code != 0) {
                throw ExtractionException("playurl 接口错误[$code] ${root.str("message") ?: ""}".trim())
            }
            val data = root.obj("data") ?: throw ExtractionException("playurl 缺少 data")
            val quality = data.int("quality") ?: 0
            val acceptQuality = (data["accept_quality"] as? JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
                ?: emptyList()

            val durl = data.arr("durl")?.mapNotNull { it as? JsonObject }.orEmpty()
            if (durl.isNotEmpty()) {
                val urls = durl.mapNotNull { it.str("url")?.takeIf { u -> u.isNotBlank() } }
                if (urls.isEmpty()) throw ExtractionException("playurl 未返回可用地址")
                val size = durl.sumOf { it.long("size") ?: 0L }.takeIf { it > 0 }
                return BiliStream(quality, urls, size, acceptQuality)
            }

            // 只有 DASH 时，退化为“仅画面”轨道（无法在端内合并音轨）
            val dash = data.obj("dash")
            if (dash != null) {
                val videos = dash.arr("video")?.mapNotNull { it as? JsonObject }.orEmpty()
                val best = videos.maxByOrNull { it.long("bandwidth") ?: 0 }
                val url = best?.str("baseUrl") ?: best?.str("base_url")
                if (url != null) {
                    return BiliStream(quality, listOf(url), best?.long("size"), acceptQuality)
                }
            }
            throw ExtractionException("未获取到 MP4 直链（该视频仅提供 DASH），请填入哔哩哔哩 Cookie 后重试")
        }

        fun qualityLabel(qn: Int): String = when (qn) {
            6 -> "240P"
            16 -> "360P"
            32 -> "480P"
            64 -> "720P"
            74 -> "720P60"
            80 -> "1080P"
            100 -> "智能"
            112 -> "1080P+"
            116 -> "1080P60"
            120 -> "4K"
            125 -> "HDR"
            126 -> "杜比视界"
            127 -> "8K"
            else -> "清晰度 $qn"
        }

        fun qualityHeight(qn: Int): Int? = when (qn) {
            6 -> 240
            16 -> 360
            32 -> 480
            64 -> 720
            74 -> 720
            80 -> 1080
            112 -> 1080
            116 -> 1080
            120 -> 2160
            125 -> 2160
            126 -> 2160
            127 -> 4320
            else -> null
        }
    }
}
