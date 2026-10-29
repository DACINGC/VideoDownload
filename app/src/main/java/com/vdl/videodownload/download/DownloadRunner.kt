package com.vdl.videodownload.download

import android.content.Context
import android.os.SystemClock
import com.vdl.videodownload.core.MediaKind
import com.vdl.videodownload.core.formatBytes
import com.vdl.videodownload.net.UserAgents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class DownloadException(message: String) : Exception(message)

data class ProgressUpdate(
    val downloaded: Long,
    val total: Long,
    val fraction: Float,
    val speedBps: Long
)

/**
 * 单个下载任务的实际执行者：负责断点续传、HLS 分片合并、写入相册。
 *
 * 说明：
 *  - 渐进式文件按字节续传（Range 请求）；
 *  - B 站等平台的分段视频会依次下载并拼接为同一个文件；
 *  - HLS 会先解析主列表挑选最高码率，再顺序拼接分片（fMP4 会先写入 init 段）。
 */
class DownloadRunner(
    private val context: Context,
    private val client: OkHttpClient,
    private val item: DownloadItem,
    private val subDir: String,
    private val onProgress: (ProgressUpdate) -> Unit
) {

    private val workDir: File by lazy {
        File(context.cacheDir, "downloads").apply { mkdirs() }
    }

    suspend fun run(): MediaSaver.Saved = withContext(Dispatchers.IO) {
        val partFile = File(workDir, "${item.id}.part")
        val ext = when (item.kind) {
            MediaKind.PROGRESSIVE -> downloadProgressive(partFile)
            MediaKind.HLS -> downloadHls(partFile)
            MediaKind.DASH -> throw DownloadException("DASH 流需要合并音视频轨，当前版本不支持直接下载")
        }

        if (!partFile.exists() || partFile.length() == 0L) {
            throw DownloadException("下载结果为空文件，请重试")
        }

        val baseName = item.fileName.ifBlank { "video" }
        val saved = MediaSaver.save(context, partFile, baseName, ext, subDir)
        runCatching { partFile.delete() }
        saved
    }

    // ------------------------------------------------------------------
    // 渐进式文件下载（多分段 + 断点续传）
    // ------------------------------------------------------------------

    private suspend fun downloadProgressive(partFile: File): String {
        val urls = item.urls.ifEmpty { throw DownloadException("下载地址为空") }
        val multiSegment = urls.size > 1
        if (multiSegment) runCatching { partFile.delete() }
        if (!partFile.exists()) partFile.createNewFile()

        var total = if (item.totalBytes > 0) item.totalBytes else 0L
        var lastEmit = 0L
        var lastBytes = partFile.length()
        var lastTime = SystemClock.elapsedRealtime()

        val headers = item.headers.toMutableMap()
        if (!headers.keys.any { it.equals("User-Agent", true) }) {
            headers["User-Agent"] = UserAgents.DESKTOP
        }

        fun emit(force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            val downloaded = partFile.length()
            if (!force && now - lastEmit < 200) return
            val dt = (now - lastTime).coerceAtLeast(1)
            val speed = ((downloaded - lastBytes) * 1000 / dt).coerceAtLeast(0)
            onProgress(
                ProgressUpdate(
                    downloaded = downloaded,
                    total = total,
                    fraction = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f,
                    speedBps = speed
                )
            )
            lastEmit = now
            lastBytes = downloaded
            lastTime = now
        }

        for (index in urls.indices) {
            val url = urls[index]
            var attempt = 0
            var done = false

            while (!done) {
                attempt++
                val resumeFrom = if (index == 0) partFile.length() else 0L
                val builder = Request.Builder().url(url).get()
                headers.forEach { (k, v) -> builder.header(k, v) }
                if (resumeFrom > 0) builder.header("Range", "bytes=$resumeFrom-")

                val call = client.newCall(builder.build())
                try {
                    call.execute().use { response ->
                        when {
                            response.code == 416 -> {
                                // 已下载完整，无需继续
                                done = true
                            }

                            !response.isSuccessful -> {
                                throw DownloadException("服务器返回 HTTP ${response.code}")
                            }

                            else -> {
                                val body = response.body ?: throw DownloadException("响应为空")
                                val supportsRange = response.code == 206
                                val append = index > 0 || (resumeFrom > 0 && supportsRange)
                                if (index == 0 && resumeFrom > 0 && !supportsRange) {
                                    // 服务端不支持断点续传，从头开始
                                    runCatching { partFile.delete() }
                                    partFile.createNewFile()
                                }

                                if (index == 0 && total <= 0) {
                                    val contentLength = body.contentLength()
                                    if (contentLength > 0) total = contentLength
                                }

                                FileOutputStream(partFile, append).use { output ->
                                    body.byteStream().use { input ->
                                        val buffer = ByteArray(128 * 1024)
                                        while (true) {
                                            currentCoroutineContext().ensureActive()
                                            val read = input.read(buffer)
                                            if (read == -1) break
                                            output.write(buffer, 0, read)
                                            emit()
                                        }
                                        output.flush()
                                    }
                                }
                                emit(force = true)
                                done = true
                            }
                        }
                    }
                } catch (c: CancellationException) {
                    call.cancel()
                    throw c
                } catch (e: IOException) {
                    if (attempt >= 3) {
                        throw DownloadException("网络错误：${e.message}（已下载 ${formatBytes(partFile.length())}）")
                    }
                    delay(600L * attempt)
                } catch (e: DownloadException) {
                    if (attempt >= 3) throw e
                    delay(600L * attempt)
                }
            }
        }

        return item.ext.ifBlank { "mp4" }
    }

    // ------------------------------------------------------------------
    // HLS 下载
    // ------------------------------------------------------------------

    private suspend fun downloadHls(partFile: File): String {
        runCatching { partFile.delete() }
        val playlistUrl = item.urls.firstOrNull() ?: throw DownloadException("下载地址为空")

        var playlist = fetchPlaylist(playlistUrl)
        if (playlist.isMaster) {
            val best = HlsParser.selectBestVariant(playlist)
                ?: throw DownloadException("m3u8 主列表中没有任何可用清晰度")
            playlist = fetchPlaylist(best.uri)
        }
        if (playlist.encrypted) {
            throw DownloadException("HLS 流已加密（AES-128），当前版本暂不支持")
        }
        if (playlist.segments.isEmpty()) throw DownloadException("m3u8 中没有找到分片")

        val initSegment = playlist.initSegment
        val ext = if (initSegment != null) "mp4" else "ts"
        val headers = item.headers.toMutableMap()
        if (!headers.keys.any { it.equals("User-Agent", true) }) {
            headers["User-Agent"] = UserAgents.DESKTOP
        }

        var downloaded = 0L
        val totalSegments = playlist.segments.size

        FileOutputStream(partFile, false).use { output ->
            val init = initSegment
            if (init != null) {
                downloaded += appendSegment(init, headers, output)
            }
            for (index in playlist.segments.indices) {
                currentCoroutineContext().ensureActive()
                downloaded += appendSegment(playlist.segments[index], headers, output)
                onProgress(
                    ProgressUpdate(
                        downloaded = downloaded,
                        total = 0,
                        fraction = (index + 1).toFloat() / totalSegments,
                        speedBps = 0
                    )
                )
            }
        }

        return ext
    }

    private suspend fun fetchPlaylist(url: String): HlsPlaylist {
        val headers = item.headers.toMutableMap()
        if (!headers.keys.any { it.equals("User-Agent", true) }) {
            headers["User-Agent"] = UserAgents.DESKTOP
        }
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }

        val body = try {
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw DownloadException("m3u8 请求失败 HTTP ${response.code}")
                response.body?.string() ?: throw DownloadException("m3u8 响应为空")
            }
        } catch (e: IOException) {
            throw DownloadException("m3u8 请求失败：${e.message}")
        }
        return try {
            HlsParser.parse(body, url)
        } catch (e: Exception) {
            throw DownloadException(e.message ?: "m3u8 解析失败")
        }
    }

    private suspend fun appendSegment(
        segment: HlsSegment,
        headers: Map<String, String>,
        output: FileOutputStream
    ): Long {
        val builder = Request.Builder().url(segment.url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        segment.range?.let { range ->
            builder.header("Range", "bytes=${range.start}-${range.endInclusive}")
        }
        val call = client.newCall(builder.build())
        var total = 0L
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw DownloadException("分片请求失败 HTTP ${response.code}")
                if (segment.range != null && response.code != 206) {
                    throw DownloadException("服务器未按字节区间返回（HTTP ${response.code}），无法正确合并该 HLS 流")
                }
                val stream = response.body?.byteStream() ?: throw DownloadException("分片响应为空")
                stream.use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        total += read
                    }
                }
            }
        } catch (c: CancellationException) {
            call.cancel()
            throw c
        } catch (e: IOException) {
            throw DownloadException("分片下载失败：${e.message}")
        }
        return total
    }

    companion object {
        fun downloadClient(timeoutSeconds: Long = 60): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    }
}
