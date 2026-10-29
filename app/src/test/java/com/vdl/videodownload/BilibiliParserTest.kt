package com.vdl.videodownload

import com.vdl.videodownload.core.ExtractionException
import com.vdl.videodownload.extractor.BilibiliExtractor
import com.vdl.videodownload.extractor.parseJsonOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 使用线上真实响应（app/src/test/resources）验证 B 站解析逻辑。
 */
class BilibiliParserTest {

    private fun readResource(name: String): String {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
            ?: error("缺少测试资源 $name")
        return stream.bufferedReader().use { it.readText() }
    }

    @Test
    fun `解析真实 view 响应`() {
        val json = parseJsonOrNull(readResource("bilibili_view.json"))!!
        val view = BilibiliExtractor.parseView(json)

        assertEquals("BV1GJ411x7h7", view.bvid)
        assertEquals(80433022L, view.aid)
        assertEquals("【官方 MV】Never Gonna Give You Up - Rick Astley", view.title)
        assertEquals("索尼音乐中国", view.author)
        assertEquals(213_000L, view.durationMs)
        assertNotNull(view.cover)
        assertEquals(1, view.pages.size)
        assertEquals(137649199L, view.pages.first().cid)
    }

    @Test
    fun `解析真实 playurl 响应`() {
        val json = parseJsonOrNull(readResource("bilibili_playurl.json"))!!
        val stream = BilibiliExtractor.parsePlayUrl(json)

        assertEquals(64, stream.quality)
        assertTrue(stream.urls.isNotEmpty())
        assertTrue(stream.urls.first().startsWith("http"))
        assertNotNull(stream.sizeBytes)
        assertTrue((stream.sizeBytes ?: 0) > 1_000_000)
        assertEquals("720P", BilibiliExtractor.qualityLabel(stream.quality))
        assertEquals(720, BilibiliExtractor.qualityHeight(stream.quality))
    }

    @Test
    fun `错误码会转换为可读异常`() {
        val errorJson = parseJsonOrNull("""{"code":-404,"message":"啥都木有"}""")!!
        val error = try {
            BilibiliExtractor.parseView(errorJson)
            null
        } catch (e: ExtractionException) {
            e
        }
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("啥都木有"))
    }

    @Test
    fun `清晰度标签映射`() {
        assertEquals("1080P", BilibiliExtractor.qualityLabel(80))
        assertEquals("4K", BilibiliExtractor.qualityLabel(120))
        assertEquals(2160, BilibiliExtractor.qualityHeight(120))
    }
}
