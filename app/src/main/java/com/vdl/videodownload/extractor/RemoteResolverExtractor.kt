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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 远程解析接口兜底。
 *
 * 内置两种协议：
 *  1. cobalt（https://github.com/imputnet/cobalt）：POST {base}，body {"url": "..."}，
 *     返回 {"status":"tunnel"|"redirect","url":"..."}，或 {"status":"picker","picker":[...]}。
 *     自建实例常见，可显著提升抖音/YouTube/Instagram 等站点成功率。
 *  2. generic：GET {base}?url=...，返回 JSON，任意层级出现 url/download/video/play 字段即可。
 */
class RemoteResolverExtractor : Extractor {

    override val name: String = "远程解析"

    override fun supports(url: String, platform: Platform): Boolean = true

    override suspend fun extract(url: String, ctx: ExtractContext): VideoInfo {
        val settings = ctx.settings
        val base = settings.remoteBase.trim()
        if (!settings.remoteEnabled || base.isBlank()) {
            throw ExtractionException("未启用远程解析接口")
        }

        val endpoint = normalizeBase(base, settings.remoteType)
        val headers = mutableMapOf("User-Agent" to UserAgents.DESKTOP)
        if (settings.remoteToken.isNotBlank()) {
            // cobalt 使用 Api-Key，其它服务通常使用 Bearer
            if (settings.remoteType == "cobalt") {
                headers["Authorization"] = "Api-Key ${settings.remoteToken}"
            } else {
                headers["Authorization"] = "Bearer ${settings.remoteToken}"
            }
        }

        val body = if (settings.remoteType == "cobalt") {
            val payload = buildString {
                append("{\"url\":\"").append(escapeJson(url)).append("\"")
                append(",\"videoQuality\":\"1080\"")
                append("}")
            }
            val result = ctx.web.postJson(endpoint, payload, headers)
            ctx.note("远程接口 HTTP ${result.code}")
            if (!result.isSuccessful) throw ExtractionException("远程接口返回 HTTP ${result.code}")
            parseCobaltResponse(result.body, url)
        } else {
            val sep = if (endpoint.contains('?')) "&" else "?"
            val result = ctx.web.get("$endpoint${sep}url=${encode(url)}", Headers.web(UserAgents.DESKTOP))
            ctx.note("远程接口 HTTP ${result.code}")
            if (!result.isSuccessful) throw ExtractionException("远程接口返回 HTTP ${result.code}")
            parseGenericResponse(result.body, url)
        }

        ctx.note("远程解析得到: ${body.url.take(120)}…")
        return VideoInfo(
            sourceUrl = url,
            platform = LinkParser.detectPlatform(url),
            title = body.title ?: "视频 ${System.currentTimeMillis() % 100000}",
            coverUrl = body.cover,
            formats = listOf(
                MediaFormat(
                    id = "remote-1",
                    label = body.label,
                    kind = if (body.url.contains(".m3u8")) MediaKind.HLS else MediaKind.PROGRESSIVE,
                    url = body.url,
                    ext = if (body.url.contains(".m3u8")) "mp4" else "mp4",
                    headers = mapOf("User-Agent" to UserAgents.DESKTOP)
                )
            ),
            userAgent = UserAgents.DESKTOP,
            extractor = name
        )
    }

    data class RemoteMedia(
        val url: String,
        val title: String? = null,
        val cover: String? = null,
        val label: String = "远程解析"
    )

    private fun normalizeBase(base: String, type: String): String {
        val trimmed = base.trim()
        if (type != "cobalt") return trimmed
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    companion object {
        fun parseCobaltResponse(body: String, originalUrl: String): RemoteMedia {
            val root = parseJsonOrNull(body) as? JsonObject
                ?: throw ExtractionException("远程接口返回的不是 JSON")
            val status = root.str("status") ?: root.str("Status")

            root.obj("error")?.let { error ->
                val code = error.str("code") ?: error.str("Code")
                throw ExtractionException("远程解析失败: ${code ?: error.toString()}")
            }

            when (status?.lowercase()) {
                "picker" -> {
                    val picker = root.arr("picker") ?: root.arr("Picker")
                    val first = picker?.mapNotNull { it as? JsonObject }?.firstOrNull { it.str("url") != null }
                    val mediaUrl = first?.str("url")
                        ?: throw ExtractionException("远程解析返回 picker 但没有可用地址")
                    return RemoteMedia(
                        url = mediaUrl,
                        title = first.str("filename"),
                        label = "远程解析 · picker"
                    )
                }
                "tunnel", "redirect", null, "stream", "success" -> {
                    val mediaUrl = root.str("url") ?: root.str("Url")
                        ?: throw ExtractionException("远程接口未返回 url 字段")
                    return RemoteMedia(
                        url = mediaUrl,
                        title = root.str("filename") ?: root.str("title"),
                        label = "远程解析 · ${status ?: "ok"}"
                    )
                }
                "error" -> {
                    val message = root.obj("error")?.str("code") ?: root.str("text") ?: "未知错误"
                    throw ExtractionException("远程解析失败: $message")
                }
                else -> {
                    val mediaUrl = root.str("url") ?: throw ExtractionException("远程接口状态异常: $status")
                    return RemoteMedia(url = mediaUrl, label = "远程解析 · $status")
                }
            }
        }

        fun parseGenericResponse(body: String, originalUrl: String): RemoteMedia {
            val root = parseJsonOrNull(body)
                ?: throw ExtractionException("远程接口返回的不是 JSON")

            val preferredKeys = listOf("url", "download", "video", "play", "src", "videoUrl", "playUrl", "data")
            val found = root.deepFind { obj ->
                preferredKeys.any { key ->
                    val value = obj.str(key)
                    value != null && value.startsWith("http")
                }
            }

            // data 可能是字符串也可能是对象，先看直接命中的 URL
            val direct = root.deepFindAll { obj ->
                preferredKeys.any { key ->
                    val value = obj.str(key)
                    value != null && value.startsWith("http")
                }
            }.firstOrNull()

            val mediaUrl = (found ?: direct)?.let { obj ->
                preferredKeys.firstNotNullOfOrNull { key ->
                    obj.str(key)?.takeIf { it.startsWith("http") }
                }
            }
            val anyUrl = mediaUrl ?: root.walkObjects()
                .flatMap { obj -> obj.values.mapNotNull { it.asString() } }
                .firstOrNull { it.startsWith("http") && Regex("""\.(mp4|m3u8|webm|flv)""").containsMatchIn(it) }

            if (anyUrl == null) throw ExtractionException("远程接口返回中未找到视频地址")

            return RemoteMedia(
                url = anyUrl,
                title = (root as? JsonObject)?.str("title") ?: (root as? JsonObject)?.str("filename"),
                cover = (root as? JsonObject)?.str("cover") ?: (root as? JsonObject)?.str("thumbnail"),
                label = "远程解析 · JSON"
            )
        }

        private fun escapeJson(text: String): String = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")

        private fun encode(text: String): String =
            java.net.URLEncoder.encode(text, "UTF-8")

        /** 探测远程接口是否可用（设置页“测试接口”按钮）。 */
        suspend fun testEndpoint(web: com.vdl.videodownload.net.WebClient, settings: ExtractSettings): String {
            val base = settings.remoteBase.trim()
            if (base.isBlank()) return "请先填写接口地址"
            val type = settings.remoteType
            val headers = mutableMapOf("User-Agent" to UserAgents.DESKTOP)
            if (settings.remoteToken.isNotBlank()) {
                headers["Authorization"] =
                    if (type == "cobalt") "Api-Key ${settings.remoteToken}" else "Bearer ${settings.remoteToken}"
            }
            return try {
                val result = if (type == "cobalt") {
                    web.postJson(
                        normalizeBaseStatic(base),
                        "{\"url\":\"https://www.bilibili.com/video/BV1GJ411x7h7\"}",
                        headers
                    )
                } else {
                    web.get("$base?url=https://www.bilibili.com/video/BV1GJ411x7h7", headers)
                }
                "HTTP ${result.code} · ${result.body.take(160)}"
            } catch (e: Exception) {
                "连接失败: ${e.message}"
            }
        }

        private fun normalizeBaseStatic(base: String): String =
            if (base.endsWith("/")) base else "$base/"
    }
}
