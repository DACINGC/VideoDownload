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

data class DouyinAweme(
    val awemeId: String?,
    val title: String,
    val author: String?,
    val coverUrl: String?,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val playUrls: List<String>,
    val bitRates: List<Pair<String, List<String>>>,
    val isImagePost: Boolean
)

/**
 * 抖音解析。
 *
 * 1. v.douyin.com 短链跟随跳转，取出作品 id；
 * 2. 请求 iesdouyin 分享页，页面内嵌 `_ROUTER_DATA`，包含 play_addr / bit_rate；
 * 3. 把 playwm（带水印）替换为 play，得到无水印地址；
 * 4. 分享页失败时回退到旧版 iteminfo 接口。
 */
class DouyinExtractor : Extractor {

    override val name: String = "抖音"

    override fun supports(url: String, platform: Platform): Boolean = platform == Platform.DOUYIN

    override suspend fun extract(url: String, ctx: ExtractContext): VideoInfo {
        val ua = ctx.settings.customUserAgent.ifBlank { UserAgents.IPHONE }
        if (ctx.settings.douyinCookie.isNotBlank()) {
            ctx.web.cookieJar.preload(".douyin.com", ctx.settings.douyinCookie)
            ctx.web.cookieJar.preload(".iesdouyin.com", ctx.settings.douyinCookie)
        }

        var target = url
        if (LinkParser.hostOf(url) in setOf("v.douyin.com", "www.iesdouyin.com") || LinkParser.isShortLink(url)) {
            val resolved = ctx.web.get(url, Headers.mobile(ua, "https://www.douyin.com/"))
            if (resolved.finalUrl.isNotBlank()) target = resolved.finalUrl
            ctx.note("短链跳转: $target")
        }

        val awemeId = LinkParser.douyinAwemeId(target)
            ?: LinkParser.douyinAwemeId(url)
            ?: throw ExtractionException("无法从链接中识别抖音作品 id")

        var lastError: Exception? = null

        // 方式一：分享页 _ROUTER_DATA
        try {
            val shareUrl = "https://www.iesdouyin.com/share/video/$awemeId/"
            val page = ctx.web.get(shareUrl, Headers.mobile(ua, "https://www.douyin.com/"))
            ctx.note("分享页 HTTP ${page.code}，长度 ${page.body.length}")
            val aweme = parseShareHtml(page.body, awemeId)
            return toVideoInfo(aweme, url, ua, ctx)
        } catch (e: Exception) {
            lastError = e
            ctx.note("分享页解析失败: ${e.message}")
        }

        // 方式二：旧版 iteminfo 接口
        try {
            val api = "https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=$awemeId"
            val result = ctx.web.get(api, Headers.json(ua, "https://www.iesdouyin.com/"))
            ctx.note("iteminfo HTTP ${result.code}")
            val aweme = parseItemInfoJson(result.body, awemeId)
            return toVideoInfo(aweme, url, ua, ctx)
        } catch (e: Exception) {
            ctx.note("iteminfo 解析失败: ${e.message}")
            if (lastError == null) lastError = e
        }

        throw ExtractionException(
            "抖音解析失败：${lastError?.message ?: "页面结构与预期不符"}\n" +
                "可尝试：①在浏览器打开该链接确认视频仍有效；②在设置中配置远程解析接口作为兜底。"
        )
    }

    private fun toVideoInfo(aweme: DouyinAweme, sourceUrl: String, ua: String, ctx: ExtractContext): VideoInfo {
        if (aweme.isImagePost) {
            throw ExtractionException("该链接是抖音图文作品，不含视频流")
        }
        val formats = buildList {
            aweme.bitRates.forEachIndexed { index, (label, urls) ->
                val cleaned = urls.map { cleanWatermark(it) }.distinct()
                if (cleaned.isNotEmpty()) {
                    add(
                        MediaFormat(
                            id = "douyin-br-$index",
                            label = label,
                            kind = MediaKind.PROGRESSIVE,
                            url = cleaned.first(),
                            segments = if (cleaned.size > 1) cleaned else emptyList(),
                            ext = "mp4",
                            headers = mapOf("User-Agent" to ua, "Referer" to "https://www.douyin.com/")
                        )
                    )
                }
            }
            if (isEmpty()) {
                val cleaned = aweme.playUrls.map { cleanWatermark(it) }.distinct()
                if (cleaned.isNotEmpty()) {
                    add(
                        MediaFormat(
                            id = "douyin-default",
                            label = "无水印原画",
                            kind = MediaKind.PROGRESSIVE,
                            url = cleaned.first(),
                            segments = if (cleaned.size > 1) cleaned else emptyList(),
                            ext = "mp4",
                            headers = mapOf("User-Agent" to ua, "Referer" to "https://www.douyin.com/")
                        )
                    )
                }
            }
        }
        if (formats.isEmpty()) throw ExtractionException("未能从抖音页面中提取到视频地址")
        ctx.note("抖音清晰度: " + formats.joinToString { it.label })
        return VideoInfo(
            sourceUrl = sourceUrl,
            platform = Platform.DOUYIN,
            title = aweme.title.ifBlank { "抖音视频" },
            author = aweme.author,
            coverUrl = aweme.coverUrl,
            durationMs = aweme.durationMs,
            formats = formats,
            referer = "https://www.douyin.com/",
            userAgent = ua,
            extractor = name
        )
    }

    companion object {

        /** 去掉水印标记：/playwm/ -> /play/ 等。 */
        fun cleanWatermark(url: String): String = url
            .replace("/playwm/", "/play/")
            .replace("playwm", "play")
            .replace("&watermark=1", "")
            .replace("?watermark=1&", "?")

        fun parseShareHtml(html: String, awemeId: String? = null): DouyinAweme {
            val routerJson = extractInlineJson(html, "_ROUTER_DATA")
                ?: throw ExtractionException("分享页未包含 _ROUTER_DATA")

            val root = parseJsonOrNull(routerJson)
                ?: throw ExtractionException("_ROUTER_DATA 不是合法 JSON")

            // 常见路径：loaderData.video_(id)/page.videoInfoRes.item_list[0]
            val itemList = root.deepFind { it["item_list"] is JsonArray }
                ?.arr("item_list")
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull()

            val item = itemList
                ?: root.deepFind { it["aweme_detail"] is JsonObject }?.obj("aweme_detail")
                ?: root.deepFind { obj ->
                    (obj["video"] is JsonObject) && obj.containsKey("desc")
                }

            if (item == null) throw ExtractionException("页面中没有找到作品数据（可能已删除或需要登录）")
            return parseAwemeObject(item, awemeId)
        }

        fun parseItemInfoJson(body: String, awemeId: String? = null): DouyinAweme {
            val root = parseJsonOrNull(body) ?: throw ExtractionException("iteminfo 未返回 JSON")
            val itemList = root.deepFind { it["item_list"] is JsonArray }
                ?.arr("item_list")
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()
            val item = itemList.firstOrNull()
                ?: throw ExtractionException("iteminfo 中没有作品数据")
            return parseAwemeObject(item, awemeId)
        }

        fun parseAwemeObject(item: JsonObject, awemeId: String? = null): DouyinAweme {
            val video = item.obj("video")
            val images = item.arr("images")
            val playAddr = video?.obj("play_addr")
            // 解析阶段就统一去水印，保证后续所有取值都已清洗
            val playUrls = playAddr?.stringList("url_list").orEmpty().map { cleanWatermark(it) }.distinct()

            val bitRates = mutableListOf<Pair<String, List<String>>>()
            (video?.arr("bit_rate") as? JsonArray)?.mapNotNull { it as? JsonObject }?.forEach { br ->
                val urls = br.obj("play_addr")?.stringList("url_list").orEmpty()
                    .map { cleanWatermark(it) }
                    .distinct()
                if (urls.isNotEmpty()) {
                    val rate = br.long("bit_rate") ?: 0L
                    val gearName = br.str("gear_name")
                    val height = br.obj("play_addr")?.int("height")
                    val qualityLabel = when {
                        gearName != null && height != null -> "${gearName} · ${height}P"
                        height != null -> "${height}P"
                        rate > 0 -> String.format("%.1f Mbps", rate / 1000.0)
                        else -> "码率 ${bitRates.size + 1}"
                    }
                    bitRates += qualityLabel to urls
                }
            }

            val cover = video?.obj("cover")?.stringList("url_list")?.firstOrNull()
                ?: video?.obj("origin_cover")?.stringList("url_list")?.firstOrNull()
                ?: item.obj("cover")?.stringList("url_list")?.firstOrNull()

            return DouyinAweme(
                awemeId = item.str("aweme_id") ?: awemeId,
                title = item.str("desc") ?: item.str("title") ?: "抖音视频",
                author = item.obj("author")?.str("nickname"),
                coverUrl = cover,
                durationMs = video?.long("duration") ?: (item.long("duration")?.times(1000)),
                width = video?.int("width") ?: item.int("width"),
                height = video?.int("height") ?: item.int("height"),
                playUrls = playUrls,
                bitRates = bitRates.sortedByDescending { it.second.size },
                isImagePost = video == null && !images.isNullOrEmpty()
            )
        }

        /** 判断页面是否被风控（用于给出更准确的报错）。 */
        fun looksBlocked(html: String): Boolean =
            html.contains("验证码") || html.contains("verify") && html.length < 3000
    }
}

private fun JsonElement?.stringListOf(key: String): List<String> =
    (this as? JsonObject)?.stringList(key) ?: emptyList()
