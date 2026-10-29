package com.vdl.videodownload

import com.vdl.videodownload.core.ExtractionException
import com.vdl.videodownload.extractor.DouyinExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抖音解析测试。
 *
 * `douyin_share_ok.html` 依据线上分享页真实结构（_ROUTER_DATA → video_(id)/page → videoInfoRes）
 * 构造，覆盖字段提取与水印地址清洗；`douyin_share_empty.html` 是线上真实抓取的页面，
 * 其 item_list 为空（作品被删除），用于验证错误提示。
 */
class DouyinParserTest {

    private fun readResource(name: String): String {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
            ?: error("缺少测试资源 $name")
        return stream.bufferedReader().use { it.readText() }
    }

    @Test
    fun `解析分享页并去除水印`() {
        val html = readResource("douyin_share_ok.html")
        val aweme = DouyinExtractor.parseShareHtml(html, "7300000000000000000")

        assertEquals("7300000000000000000", aweme.awemeId)
        assertEquals("测试视频标题 #日常", aweme.title)
        assertEquals("测试作者", aweme.author)
        assertEquals("https://p3-sign.douyinpic.com/cover.jpeg", aweme.coverUrl)
        assertEquals(15_300L, aweme.durationMs)
        assertEquals(2, aweme.bitRates.size)
        assertFalse(aweme.isImagePost)

        // 所有地址里的 playwm 都应被替换掉
        val allUrls = aweme.playUrls + aweme.bitRates.flatMap { it.second }
        assertTrue(allUrls.isNotEmpty())
        allUrls.forEach { url ->
            assertFalse("仍带水印标记: $url", url.contains("playwm"))
        }
        assertTrue(allUrls.first().contains("/play/"))
    }

    @Test
    fun `水印地址清洗规则`() {
        assertEquals(
            "https://aweme.snssdk.com/aweme/v1/play/?video_id=abc&ratio=720p",
            DouyinExtractor.cleanWatermark("https://aweme.snssdk.com/aweme/v1/playwm/?video_id=abc&ratio=720p")
        )
        assertEquals(
            "https://x.com/play/video.mp4",
            DouyinExtractor.cleanWatermark("https://x.com/playwm/video.mp4")
        )
    }

    @Test
    fun `空 item_list 会给出明确错误`() {
        val html = readResource("douyin_share_empty.html")
        val error = try {
            DouyinExtractor.parseShareHtml(html)
            null
        } catch (e: ExtractionException) {
            e
        }
        assertNotNull("应当抛出解析异常", error)
        assertTrue(error!!.message!!.contains("作品数据"))
    }

    @Test
    fun `旧版 iteminfo 接口结构`() {
        val body = """
            {"status_code":0,"item_list":[{"aweme_id":"7300000000000000001","desc":"旧接口视频",
            "author":{"nickname":"作者B"},
            "video":{"duration":8000,"play_addr":{"url_list":["https://aweme.snssdk.com/aweme/v1/playwm/?video_id=xyz"]},
            "cover":{"url_list":["https://p3.douyinpic.com/c.jpeg"]}}}]}
        """.trimIndent()

        val aweme = DouyinExtractor.parseItemInfoJson(body, "7300000000000000001")
        assertEquals("旧接口视频", aweme.title)
        assertEquals("作者B", aweme.author)
        assertEquals(8_000L, aweme.durationMs)
        assertTrue(aweme.playUrls.first().contains("/play/"))
    }

    @Test
    fun `非法 JSON 抛出异常`() {
        val error = runCatching { DouyinExtractor.parseShareHtml("<html>not json</html>") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is ExtractionException)
    }
}
