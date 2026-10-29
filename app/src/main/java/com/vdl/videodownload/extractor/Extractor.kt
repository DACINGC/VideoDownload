package com.vdl.videodownload.extractor

import com.vdl.videodownload.core.DebugLog
import com.vdl.videodownload.core.ExtractionException
import com.vdl.videodownload.core.LinkParser
import com.vdl.videodownload.core.Platform
import com.vdl.videodownload.core.VideoInfo
import com.vdl.videodownload.net.WebClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 解析所需的用户设置（纯数据，方便单元测试与跨层传递）。 */
data class ExtractSettings(
    val bilibiliCookie: String = "",
    val douyinCookie: String = "",
    val customUserAgent: String = "",
    val remoteEnabled: Boolean = false,
    /** cobalt | generic */
    val remoteType: String = "cobalt",
    val remoteBase: String = "",
    val remoteToken: String = ""
)

class ExtractContext(
    val web: WebClient,
    val settings: ExtractSettings,
    val log: DebugLog = DebugLog()
) {
    fun note(message: String) = log.add(message)
}

interface Extractor {
    val name: String
    fun supports(url: String, platform: Platform): Boolean
    suspend fun extract(url: String, ctx: ExtractContext): VideoInfo
}

/** 解析调度器：按平台顺序尝试内置解析器，失败后回退到通用解析 / 远程解析。 */
class ExtractorEngine(private val web: WebClient) {

    private val bilibili = BilibiliExtractor()
    private val douyin = DouyinExtractor()
    private val kuaishou = KuaishouExtractor()
    private val xiaohongshu = XiaohongshuExtractor()
    private val weibo = WeiboExtractor()
    private val direct = DirectExtractor()
    private val generic = GenericHtmlExtractor()
    private val remote = RemoteResolverExtractor()

    suspend fun extract(rawUrl: String, settings: ExtractSettings): VideoInfo {
        val log = DebugLog()
        val ctx = ExtractContext(web, settings, log)
        val url = rawUrl.trim()
        val platform = LinkParser.detectPlatform(url)
        ctx.note("识别平台: ${platform.label} ($platform)")

        val remoteConfigured = settings.remoteEnabled && settings.remoteBase.isNotBlank()
        val chain = buildChain(url, platform, settings).filter { extractor ->
            remoteConfigured || extractor !is RemoteResolverExtractor
        }

        var lastError: Throwable? = null
        var platformError: Throwable? = null

        for (extractor in chain) {
            if (!extractor.supports(url, platform)) continue
            try {
                ctx.note("尝试解析器: ${extractor.name}")
                val info = extractor.extract(url, ctx)
                if (info.formats.isEmpty()) {
                    ctx.note("${extractor.name} 未返回可用清晰度")
                    continue
                }
                ctx.note("解析成功: ${info.formats.size} 个清晰度")
                return info
            } catch (e: ExtractionException) {
                lastError = e
                if (extractor !is GenericHtmlExtractor && extractor !is RemoteResolverExtractor) {
                    if (platformError == null) platformError = e
                }
                ctx.note("${extractor.name} 失败: ${e.message}")
            } catch (e: Exception) {
                lastError = e
                if (extractor !is GenericHtmlExtractor && extractor !is RemoteResolverExtractor) {
                    if (platformError == null) platformError = e
                }
                ctx.note("${extractor.name} 异常: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        val detail = log.snapshot().joinToString("\n")
        val reason = platformError?.message ?: lastError?.message ?: "未找到可用的视频地址"
        val hint = if (!remoteConfigured) {
            "\n\n提示：可在「设置 → 高级 → 远程解析兜底」中配置自建解析接口（如 cobalt）以支持更多站点。"
        } else {
            ""
        }
        throw ExtractionException("$reason$hint\n\n解析过程:\n$detail", lastError ?: platformError)
    }

    private fun buildChain(url: String, platform: Platform, settings: ExtractSettings): List<Extractor> {
        val remoteFirst = settings.remoteEnabled && settings.remoteBase.isNotBlank()
        return when (platform) {
            Platform.BILIBILI -> listOf(bilibili, generic, remote)
            Platform.DOUYIN -> if (remoteFirst) listOf(douyin, remote, generic) else listOf(douyin, generic, remote)
            Platform.KUAISHOU -> if (remoteFirst) listOf(kuaishou, remote, generic) else listOf(kuaishou, generic, remote)
            Platform.XIAOHONGSHU -> if (remoteFirst) listOf(xiaohongshu, remote, generic) else listOf(xiaohongshu, generic, remote)
            Platform.WEIBO -> listOf(weibo, generic, remote)
            Platform.DIRECT -> listOf(direct, generic, remote)
            Platform.YOUTUBE, Platform.TIKTOK -> if (remoteFirst) listOf(remote, generic) else listOf(generic, remote)
            else -> if (remoteFirst) listOf(direct, generic, remote) else listOf(direct, generic, remote)
        }
    }
}

// ---------------------------------------------------------------------------
// JSON 小工具（基于 kotlinx.serialization，纯 JVM，可用于单元测试）
// ---------------------------------------------------------------------------

val lazyJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    allowSpecialFloatingPointValues = true
}

fun JsonObject.str(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return (element as? JsonPrimitive)?.contentOrNull
}

fun JsonObject.long(key: String): Long? = str(key)?.trim()?.toLongOrNull()

fun JsonObject.int(key: String): Int? = str(key)?.trim()?.toIntOrNull()

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

fun JsonElement.asObject(): JsonObject? = this as? JsonObject

fun JsonElement.asString(): String? = (this as? JsonPrimitive)?.contentOrNull

/** 深度优先遍历所有 JSON 对象。 */
fun JsonElement.walkObjects(): Sequence<JsonObject> = sequence {
    val stack = ArrayDeque<JsonElement>()
    stack.addLast(this@walkObjects)
    while (stack.isNotEmpty()) {
        when (val node = stack.removeLast()) {
            is JsonObject -> {
                yield(node)
                node.values.forEach { stack.addLast(it) }
            }
            is JsonArray -> node.forEach { stack.addLast(it) }
            else -> Unit
        }
    }
}

/** 在所有对象里找第一个满足条件的对象。 */
fun JsonElement.deepFind(predicate: (JsonObject) -> Boolean): JsonObject? =
    walkObjects().firstOrNull(predicate)

fun JsonElement.deepFindAll(predicate: (JsonObject) -> Boolean): List<JsonObject> =
    walkObjects().filter(predicate).toList()

fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { it.asString() }?.filter { it.isNotBlank() } ?: emptyList()

/**
 * 从 `_ROUTER_DATA = {...}` 这类内联 JSON 中截取完整的对象文本。
 * 字符串中的花括号和转义会被正确跳过。
 */
fun extractInlineJson(html: String, anchor: String, fromIndex: Int = 0): String? {
    val anchorIndex = html.indexOf(anchor, fromIndex)
    if (anchorIndex < 0) return null
    val braceStart = html.indexOf('{', anchorIndex)
    if (braceStart < 0) return null

    var depth = 0
    var inString = false
    var escaped = false
    var i = braceStart
    while (i < html.length) {
        val c = html[i]
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
        } else {
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return html.substring(braceStart, i + 1)
                }
            }
        }
        i++
    }
    return null
}

/** 宽松解析 JSON，失败返回 null。 */
fun parseJsonOrNull(text: String?): JsonElement? {
    if (text.isNullOrBlank()) return null
    return try {
        lazyJson.parseToJsonElement(text)
    } catch (_: Exception) {
        null
    }
}

// ---------------------------------------------------------------------------
// HTML 小工具
// ---------------------------------------------------------------------------

object Html {
    private val titleRegex = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun title(html: String): String? = titleRegex.find(html)?.groupValues?.get(1)
        ?.let { decodeEntities(it) }
        ?.replace(Regex("""\s+"""), " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    /** 取出 `<meta property="og:video" content="...">` 之类的值。 */
    fun meta(html: String, vararg names: String): String? {
        for (name in names) {
            val patterns = listOf(
                Regex("""<meta[^>]+(?:property|name)\s*=\s*["']${Regex.escape(name)}["'][^>]*content\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                Regex("""<meta[^>]+content\s*=\s*["']([^"']+)["'][^>]*(?:property|name)\s*=\s*["']${Regex.escape(name)}["']""", RegexOption.IGNORE_CASE)
            )
            for (pattern in patterns) {
                val value = pattern.find(html)?.groupValues?.get(1)
                if (!value.isNullOrBlank()) return decodeEntities(value)
            }
        }
        return null
    }

    fun videoTagSources(html: String): List<String> {
        val results = mutableListOf<String>()
        val videoSrc = Regex("""<video[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val sourceSrc = Regex("""<source[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        results += videoSrc.findAll(html).map { it.groupValues[1] }
        results += sourceSrc.findAll(html).map { it.groupValues[1] }
        return results.map { decodeEntities(it) }
    }

    private val mediaUrlRegex = Regex(
        """https?://[^\s"'<>\\]+?\.(?:mp4|m3u8|flv|webm|mov)(?:\?[^\s"'<>\\]*)?""",
        RegexOption.IGNORE_CASE
    )

    fun mediaUrls(html: String): List<String> =
        mediaUrlRegex.findAll(html).map { it.value }.distinct().toList()

    fun jsonLdContentUrls(html: String): List<String> {
        val results = mutableListOf<String>()
        val regex = Regex("""<script[^>]+type\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        regex.findAll(html).forEach { match ->
            val json = parseJsonOrNull(match.groupValues[1].trim()) ?: return@forEach
            json.walkObjects().forEach { obj ->
                listOf("contentUrl", "embedUrl", "url").forEach { key ->
                    val value = obj.str(key)
                    if (value != null && Regex("""\.(mp4|m3u8|webm|flv)""", RegexOption.IGNORE_CASE).containsMatchIn(value)) {
                        results += value
                    }
                }
            }
        }
        return results.distinct()
    }

    fun escapeHex(text: String): String = text
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
        .replace("\\/", "/")

    fun decodeEntities(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#x2F;", "/")
        .replace("&nbsp;", " ")
}

/** 从任意 JSON 文本中抽取 http(s) 链接（用于平台页面里的散落字段）。 */
fun extractUrlsFromJsonText(text: String): List<String> {
    val decoded = Html.escapeHex(text)
    return Regex("""https?://[^\s"'<>\\]+""").findAll(decoded)
        .map { it.value.trimEnd(')', ',', '.', '"') }
        .filter { it.startsWith("http") }
        .distinct()
        .toList()
}

fun JsonObject.toMapSafe(): Map<String, String> = entries.mapNotNull { (k, v) ->
    (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
}.toMap()

internal fun JsonElement?.isNullOrJsonNull(): Boolean = this == null || this is JsonNull

internal fun JsonElement?.objOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asJsonObject(): JsonObject = this!!.jsonObject

internal fun JsonElement?.asJsonPrimitive(): JsonPrimitive? = this as? JsonPrimitive

internal fun JsonPrimitive?.strOrNull(): String? = this?.contentOrNull
