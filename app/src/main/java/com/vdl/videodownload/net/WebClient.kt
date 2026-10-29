package com.vdl.videodownload.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object UserAgents {
    const val DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    const val IPHONE =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
    const val ANDROID =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
}

/** 一个简单的内存 CookieJar：既能自动接收 Set-Cookie，也支持预置用户 Cookie。 */
class MemoryCookieJar : CookieJar {

    private data class Key(val domain: String, val name: String, val path: String)

    private val store = LinkedHashMap<Key, Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        cookies.forEach { cookie ->
            val key = Key(cookie.domain, cookie.name, cookie.path)
            if (cookie.expiresAt < now) store.remove(key) else store[key] = cookie
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val matched = store.values.filter { cookie ->
            cookie.expiresAt >= now && cookie.matches(url)
        }
        return matched
    }

    /** 直接预置一条 `name=value` 形式的 Cookie（例如用户填写的 SESSDATA）。 */
    @Synchronized
    fun preload(domain: String, rawCookie: String) {
        rawCookie.split(';')
            .map { it.trim() }
            .filter { it.contains('=') }
            .forEach { pair ->
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=').trim()
                if (name.isNotEmpty()) {
                    store[Key(domain, name, "/")] = Cookie.Builder()
                        .domain(domain)
                        .path("/")
                        .name(name)
                        .value(value)
                        .expiresAt(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(30))
                        .build()
                }
            }
    }

    @Synchronized
    fun cookieHeaderFor(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        val cookies = loadForRequest(parsed)
        if (cookies.isEmpty()) return null
        return cookies.joinToString("; ") { "${it.name}=${it.value}" }
    }

    @Synchronized
    fun clear() = store.clear()
}

data class HttpResult(
    val code: Int,
    val body: String,
    val finalUrl: String,
    val contentType: String?,
    val contentLength: Long?
) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * 所有平台解析共用的 HTTP 客户端。
 * 不依赖任何 Android API，方便在 JVM 上直接跑单元测试。
 */
class WebClient(
    val cookieJar: MemoryCookieJar = MemoryCookieJar()
) {
    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .cookieJar(cookieJar)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun buildRequest(
        url: String,
        headers: Map<String, String>,
        method: String = "GET"
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Accept", headers["Accept"] ?: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        headers.forEach { (k, v) ->
            if (!k.equals("Accept", ignoreCase = true)) builder.header(k, v)
        }
        return builder.method(method, null).build()
    }

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResult = withContext(Dispatchers.IO) {
        val request = buildRequest(url, headers)
        okHttp.newCall(request).execute().use { response ->
            val body = response.body?.stringSafely() ?: ""
            HttpResult(
                code = response.code,
                body = body,
                finalUrl = response.request.url.toString(),
                contentType = response.header("Content-Type"),
                contentLength = response.body?.contentLength()?.takeIf { it > 0 }
                    ?: response.header("Content-Length")?.toLongOrNull()
            )
        }
    }

    suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/plain, */*")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            .build()
        okHttp.newCall(request).execute().use { response ->
            HttpResult(
                code = response.code,
                body = response.body?.stringSafely() ?: "",
                finalUrl = response.request.url.toString(),
                contentType = response.header("Content-Type"),
                contentLength = response.header("Content-Length")?.toLongOrNull()
            )
        }
    }

    /** 只取响应头，用于探测文件大小或跳转目标。 */
    suspend fun head(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResult = withContext(Dispatchers.IO) {
        val request = buildRequest(url, headers, method = "HEAD")
        try {
            okHttp.newCall(request).execute().use { response ->
                HttpResult(
                    code = response.code,
                    body = "",
                    finalUrl = response.request.url.toString(),
                    contentType = response.header("Content-Type"),
                    contentLength = response.header("Content-Length")?.toLongOrNull()
                )
            }
        } catch (t: Throwable) {
            HttpResult(code = -1, body = "", finalUrl = url, contentType = null, contentLength = null)
        }
    }

    /**
     * 跟随跳转取到最终地址。OkHttp 已自动跟随，这里返回最终 URL 与页面内容，
     * 短链（v.douyin.com / b23.tv / xhslink.com）解析都依赖它。
     */
    suspend fun resolve(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResult = get(url, headers)

    fun close() {
        okHttp.dispatcher.executorService.shutdown()
        okHttp.connectionPool.evictAll()
    }
}

private fun ResponseBody.stringSafely(): String = try {
    string()
} catch (t: Throwable) {
    ""
}

/** 构造平台请求头的小工具。 */
object Headers {
    fun web(userAgent: String, referer: String? = null): MutableMap<String, String> {
        val map = mutableMapOf("User-Agent" to userAgent)
        if (referer != null) map["Referer"] = referer
        return map
    }

    fun json(userAgent: String, referer: String? = null): MutableMap<String, String> =
        web(userAgent, referer).apply { put("Accept", "application/json, text/plain, */*") }

    fun mobile(userAgent: String, referer: String? = null): MutableMap<String, String> =
        web(userAgent, referer).apply {
            put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        }
}

internal fun Response.headerSafe(name: String): String? = header(name)
