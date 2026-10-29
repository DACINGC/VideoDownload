package com.vdl.videodownload

import com.vdl.videodownload.core.LinkParser
import com.vdl.videodownload.core.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 链接解析测试：覆盖各平台真实的分享文案格式。
 */
class LinkParserTest {

    @Test
    fun `抖音分享文案中提取短链`() {
        val text = "7.43 xYz:/ 复制打开抖音，看看【某某某的作品】这个视频太有趣了 # 日常 https://v.douyin.com/iRNBho5m/ 复制此链接，打开Dou音搜索，直接观看视频！"
        val urls = LinkParser.extractUrls(text)
        assertEquals(listOf("https://v.douyin.com/iRNBho5m/"), urls)
        assertEquals(Platform.DOUYIN, LinkParser.detectPlatform(urls.first()))
    }

    @Test
    fun `快手分享文案中提取短链`() {
        val text = "https://v.kuaishou.com/5xAbCd 复制此消息，打开【快手】直接观看！"
        val url = LinkParser.firstUrl(text)!!
        assertEquals("https://v.kuaishou.com/5xAbCd", url)
        assertEquals(Platform.KUAISHOU, LinkParser.detectPlatform(url))
    }

    @Test
    fun `小红书分享文案中的中文逗号会被剥离`() {
        val text = "https://xhslink.com/a/XyZ123，复制本条信息，打开【小红书】App查看精彩内容！"
        val url = LinkParser.firstUrl(text)!!
        assertEquals("https://xhslink.com/a/XyZ123", url)
        assertEquals(Platform.XIAOHONGSHU, LinkParser.detectPlatform(url))
    }

    @Test
    fun `哔哩哔哩分享文案中的 b23 短链`() {
        val text = "【【官方 MV】Never Gonna Give You Up-哔哩哔哩】 https://b23.tv/abcd12"
        val url = LinkParser.firstUrl(text)!!
        assertEquals("https://b23.tv/abcd12", url)
        assertEquals(Platform.BILIBILI, LinkParser.detectPlatform(url))
    }

    @Test
    fun `微博与直链识别`() {
        assertEquals(
            Platform.WEIBO,
            LinkParser.detectPlatform("https://video.weibo.com/show?fid=1034:5000000000000000")
        )
        assertEquals(
            Platform.DIRECT,
            LinkParser.detectPlatform("https://cdn.example.com/video/abc.mp4?token=123")
        )
        assertTrue(LinkParser.isDirectMedia("https://cdn.example.com/live/index.m3u8"))
    }

    @Test
    fun `http 链接会升级为 https`() {
        assertEquals(
            "https://www.bilibili.com/video/BV1GJ411x7h7",
            LinkParser.firstUrl("http://www.bilibili.com/video/BV1GJ411x7h7")
        )
    }

    @Test
    fun `哔哩哔哩视频号与分P解析`() {
        val url = "https://www.bilibili.com/video/BV1GJ411x7h7?p=3&vd_source=abc"
        assertEquals("BV1GJ411x7h7", LinkParser.bilibiliBvid(url))
        assertEquals(3, LinkParser.bilibiliPage(url))
        assertEquals(1, LinkParser.bilibiliPage("https://www.bilibili.com/video/BV1GJ411x7h7"))
        assertEquals("abc", LinkParser.queryParam(url, "vd_source"))
    }

    @Test
    fun `av 号解析`() {
        assertEquals(170001L, LinkParser.bilibiliAid("https://www.bilibili.com/video/av170001"))
        assertEquals(80433022L, LinkParser.bilibiliAid("https://www.bilibili.com/video/BV1GJ411x7h7?aid=80433022"))
    }

    @Test
    fun `抖音作品 id 的多种链接形式`() {
        assertEquals(
            "7300000000000000000",
            LinkParser.douyinAwemeId("https://www.douyin.com/video/7300000000000000000?previous_page=app_code_link")
        )
        assertEquals(
            "7300000000000000000",
            LinkParser.douyinAwemeId("https://www.iesdouyin.com/share/video/7300000000000000000/?region=CN")
        )
        assertEquals(
            "7300000000000000000",
            LinkParser.douyinAwemeId("https://www.douyin.com/discover?modal_id=7300000000000000000")
        )
        // 旧版分享链接（v.douyin.com 跳转后的落地页）
        assertEquals(
            "6792858421352729859",
            LinkParser.douyinAwemeId("https://www.douyin.com/share/forward/6792858421352729859/?region=CN&app=aweme")
        )
        assertNull(LinkParser.douyinAwemeId("https://www.douyin.com/"))
    }

    @Test
    fun `文件名过滤非法字符`() {
        assertEquals("a_b_c_d_e_f_g_h", LinkParser.sanitizeFileName("a/b:c*d?e\"f<g>h"))
        assertEquals("video", LinkParser.sanitizeFileName("   "))
        assertTrue(LinkParser.sanitizeFileName("x".repeat(200)).length <= 80)
    }

    @Test
    fun `无链接文本返回空`() {
        assertTrue(LinkParser.extractUrls("这里没有任何链接").isEmpty())
        assertNull(LinkParser.firstUrl(null))
    }

    @Test
    fun `短链优先级高于正文链接`() {
        val text = "详情看 https://example.com/article 或 https://v.douyin.com/abc123/ 复制打开"
        assertEquals("https://v.douyin.com/abc123/", LinkParser.pickBestUrl(text))
    }
}
