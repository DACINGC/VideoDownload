package com.vdl.videodownload

import com.vdl.videodownload.download.HlsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsParserTest {

    @Test
    fun `主播放列表挑选最高清晰度`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            360p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080
            1080p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720
            720p/index.m3u8
        """.trimIndent()

        val playlist = HlsParser.parse(master, "https://cdn.example.com/live/master.m3u8")
        assertTrue(playlist.isMaster)
        assertEquals(3, playlist.variants.size)

        val best = HlsParser.selectBestVariant(playlist)
        assertNotNull(best)
        assertEquals(1080, best!!.resolution?.substringAfter('x')?.toInt())
        assertEquals("https://cdn.example.com/live/1080p/index.m3u8", best.uri)
    }

    @Test
    fun `媒体播放列表解析相对分片与初始化段`() {
        val media = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:4
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4.0,
            seg-1.m4s
            #EXTINF:4.0,
            seg-2.m4s
            #EXTINF:3.5,
            /abs/seg-3.m4s
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = HlsParser.parse(media, "https://cdn.example.com/vod/index.m3u8")
        assertFalse(playlist.isMaster)
        assertEquals("https://cdn.example.com/vod/init.mp4", playlist.initSegment?.url)
        assertNull(playlist.initSegment?.range)
        assertEquals(3, playlist.segments.size)
        assertEquals("https://cdn.example.com/vod/seg-1.m4s", playlist.segments[0].url)
        assertEquals("https://cdn.example.com/vod/seg-2.m4s", playlist.segments[1].url)
        assertEquals("https://cdn.example.com/abs/seg-3.m4s", playlist.segments[2].url)
        assertTrue(playlist.endList)
        assertFalse(playlist.encrypted)
        assertEquals(11.5, playlist.totalDurationSec, 0.001)
        playlist.segments.forEach { assertNull(it.range) }
    }

    @Test
    fun `字节区间播放列表（Apple fMP4 示例结构）`() {
        val media = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:6
            #EXT-X-MAP:URI="main.mp4",BYTERANGE="719@0"
            #EXTINF:6.00000,
            #EXT-X-BYTERANGE:274201@719
            main.mp4
            #EXTINF:6.00000,
            #EXT-X-BYTERANGE:280451@274920
            main.mp4
            #EXTINF:6.00000,
            #EXT-X-BYTERANGE:282332
            main.mp4
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = HlsParser.parse(media, "https://cdn.example.com/v2/prog_index.m3u8")

        val init = playlist.initSegment
        assertNotNull(init)
        assertEquals("https://cdn.example.com/v2/main.mp4", init!!.url)
        assertEquals(0L, init.range?.start)
        assertEquals(719L, init.range?.length)

        assertEquals(3, playlist.segments.size)
        val first = playlist.segments[0]
        assertEquals("https://cdn.example.com/v2/main.mp4", first.url)
        assertEquals(719L, first.range?.start)
        assertEquals(274201L, first.range?.length)
        assertEquals(first.range!!.endInclusive, 719L + 274201L - 1)

        val second = playlist.segments[1]
        assertEquals(274920L, second.range?.start)

        // 省略 @offset 时接着上一段末尾
        val third = playlist.segments[2]
        assertEquals(274920L + 280451L, third.range?.start)
        assertEquals(282332L, third.range?.length)
    }

    @Test
    fun `识别 AES-128 加密流`() {
        val media = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:5.0,
            part0.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = HlsParser.parse(media, "https://cdn.example.com/a/index.m3u8")
        assertTrue(playlist.encrypted)
    }

    @Test
    fun `无效列表抛出异常`() {
        val error = runCatching { HlsParser.parse("<html></html>", "https://x.com/a.m3u8") }.exceptionOrNull()
        assertNotNull(error)
    }
}
