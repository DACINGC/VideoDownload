package com.vdl.videodownload.download

import java.net.URI

data class HlsVariant(val bandwidth: Long, val resolution: String?, val uri: String)

/** HLS 的字节区间（EXT-X-BYTERANGE），用于同一文件按片段切分的播放列表。 */
data class HlsByteRange(val start: Long, val length: Long) {
    val endInclusive: Long get() = start + length - 1
}

data class HlsSegment(val url: String, val range: HlsByteRange? = null)

data class HlsPlaylist(
    val isMaster: Boolean,
    val variants: List<HlsVariant>,
    val segments: List<HlsSegment>,
    val initSegment: HlsSegment?,
    val encrypted: Boolean,
    val endList: Boolean,
    val targetDuration: Int,
    val totalDurationSec: Double
)

/**
 * 极简 m3u8 解析器：支持主播放列表、fMP4 初始化段（EXT-X-MAP）、
 * 字节区间（EXT-X-BYTERANGE）与加密检测。
 * 纯 Kotlin 实现，便于 JVM 单元测试。
 */
object HlsParser {

    fun parse(content: String, baseUrl: String): HlsPlaylist {
        val lines = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty() || !lines.first().startsWith("#EXTM3U")) {
            throw IllegalArgumentException("不是有效的 m3u8 播放列表")
        }

        val variants = mutableListOf<HlsVariant>()
        val segments = mutableListOf<HlsSegment>()
        var initSegment: HlsSegment? = null
        var encrypted = false
        var endList = false
        var targetDuration = 0
        var totalDuration = 0.0
        var pendingVariantBandwidth = -1L
        var pendingVariantResolution: String? = null
        var pendingDuration = 0.0
        var pendingRange: PendingRange? = null

        // 隐式偏移（只写长度不写 @offset）时，需要接着同一资源上一段的末尾
        var lastRangeUri: String? = null
        var lastRangeEnd = 0L

        lines.forEach { line ->
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    pendingVariantBandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L
                    pendingVariantResolution = attrs["RESOLUTION"]
                }

                line.startsWith("#EXTINF:") -> {
                    pendingDuration = line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
                }

                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    val uri = attrs["URI"]
                    if (uri != null) {
                        val absolute = resolve(baseUrl, uri)
                        val range = attrs["BYTERANGE"]?.let { parseRange(it) }
                        initSegment = HlsSegment(absolute, range)
                    }
                }

                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    pendingRange = parsePendingRange(line.substringAfter(':'))
                }

                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    val method = attrs["METHOD"]?.uppercase()
                    if (method != null && method != "NONE") encrypted = true
                }

                line.startsWith("#EXT-X-TARGETDURATION:") -> {
                    targetDuration = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }

                line.startsWith("#EXT-X-ENDLIST") -> endList = true

                line.startsWith("#") -> Unit

                else -> {
                    val absolute = resolve(baseUrl, line)
                    if (pendingVariantBandwidth >= 0) {
                        variants += HlsVariant(pendingVariantBandwidth, pendingVariantResolution, absolute)
                        pendingVariantBandwidth = -1L
                        pendingVariantResolution = null
                    } else {
                        val pending = pendingRange
                        val range = if (pending != null) {
                            val start = pending.offset
                                ?: if (lastRangeUri == absolute) lastRangeEnd else 0L
                            HlsByteRange(start, pending.length)
                        } else {
                            null
                        }
                        if (range != null) {
                            lastRangeUri = absolute
                            lastRangeEnd = range.endInclusive + 1
                        }
                        segments += HlsSegment(absolute, range)
                        pendingRange = null
                        totalDuration += pendingDuration
                        pendingDuration = 0.0
                    }
                }
            }
        }

        return HlsPlaylist(
            isMaster = variants.isNotEmpty() && segments.isEmpty(),
            variants = variants,
            segments = segments,
            initSegment = initSegment,
            encrypted = encrypted,
            endList = endList,
            targetDuration = targetDuration,
            totalDurationSec = totalDuration
        )
    }

    /** 从主播放列表里挑码率最高的变体。 */
    fun selectBestVariant(playlist: HlsPlaylist): HlsVariant? =
        playlist.variants.maxByOrNull { variant ->
            val height = variant.resolution?.substringAfter('x')?.toIntOrNull() ?: 0
            height.toLong() * 10_000_000L + variant.bandwidth
        }

    /** 解析 `length@offset`，offset 可省略（跟随上一段末尾）。 */
    private data class PendingRange(val length: Long, val offset: Long?)

    private fun parsePendingRange(text: String): PendingRange? {
        val trimmed = text.trim().removeSurrounding("\"")
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split('@')
        val length = parts.getOrNull(0)?.trim()?.toLongOrNull() ?: return null
        val offset = parts.getOrNull(1)?.trim()?.toLongOrNull()
        return PendingRange(length, offset)
    }

    private fun parseRange(text: String): HlsByteRange? {
        val trimmed = text.trim().removeSurrounding("\"")
        val parts = trimmed.split('@')
        val length = parts.getOrNull(0)?.trim()?.toLongOrNull() ?: return null
        val offset = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
        return HlsByteRange(offset, length)
    }

    private fun parseAttributes(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var i = 0
        while (i < text.length) {
            val eq = text.indexOf('=', i)
            if (eq < 0) break
            val key = text.substring(i, eq).substringAfter(',').trim()
            val valueStart = eq + 1
            var value: String
            if (valueStart < text.length && text[valueStart] == '"') {
                val end = text.indexOf('"', valueStart + 1)
                if (end < 0) break
                value = text.substring(valueStart + 1, end)
                i = end + 1
            } else {
                val comma = text.indexOf(',', valueStart)
                if (comma < 0) {
                    value = text.substring(valueStart)
                    i = text.length
                } else {
                    value = text.substring(valueStart, comma)
                    i = comma
                }
            }
            if (key.isNotEmpty() && value.isNotEmpty()) result[key.uppercase()] = value
            if (i < text.length && text[i] == ',') i++
        }
        return result
    }

    private fun resolve(baseUrl: String, relative: String): String = try {
        URI(baseUrl).resolve(relative).toString()
    } catch (_: Exception) {
        relative
    }
}
