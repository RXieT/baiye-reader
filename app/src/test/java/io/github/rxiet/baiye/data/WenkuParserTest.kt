package io.github.rxiet.baiye.data

import java.io.IOException
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.*
import org.junit.Test

class WenkuParserTest {
    @Test fun listReadsIdsWithoutDependingOnAttributeOrder() {
        val raw = """<result><page num="3"/><item aid="7"><data name="Title"><![CDATA[风与纸]]></data></item><item aid="9"/></result>"""
        val (books, pages) = WenkuParser.list(raw)
        assertEquals(listOf("7", "9"), books.map { it.id })
        assertEquals("风与纸", books.first().title)
        assertEquals("", books.last().title)
        assertEquals(3, pages)
    }
    @Test fun emptySearchIsAValidEmptyResult() {
        assertTrue(WenkuParser.list("<result/>").first.isEmpty())
    }
    @Test(expected = IOException::class) fun challengeIsNotAnEmptyCatalog() {
        WenkuParser.list("<!doctype html><html><title>Just a moment</title></html>")
    }
    @Test fun metadataReadsValueAndCdata() {
        val raw = """<metadata><data aid="1234" name="Title"><![CDATA[白纸]]></data><data value="佚名" name="Author"/><data name="BookStatus" value="已完成"/></metadata>"""
        val book = WenkuParser.meta("1234", raw, " 简介 ")
        assertEquals("白纸", book.title)
        assertEquals("佚名", book.author)
        assertEquals("简介", book.description)
        assertEquals("https://img.wenku8.com/image/1/1234/1234s.jpg", book.cover)
    }
    @Test fun tocKeepsDocumentOrderAndOwnVolumeText() {
        val raw = """<book><volume vid="1"><![CDATA[第一卷]]><chapter cid="9"><![CDATA[序章]]></chapter><chapter cid="3">正文</chapter></volume><volume vid="2"><![CDATA[第二卷]]><chapter cid="12">尾声</chapter></volume></book>"""
        val chapters = WenkuParser.toc(raw)
        assertEquals(listOf("9", "3", "12"), chapters.map { it.id })
        assertEquals(listOf("第一卷", "第一卷", "第二卷"), chapters.map { it.volume })
    }
    @Test(expected = IOException::class) fun missingTocFailsExplicitly() { WenkuParser.toc("<error>disabled</error>") }
    @Test fun chapterEscapesTextAndKeepsMixedImages() {
        val raw = "第一段 <script>alert(1)</script>\r\n<!--image-->http://pic.wenku8.com/pictures/1/a.jpg<!--image-->\n第二段 & 结尾"
        val html = WenkuParser.chapter("测试章节", raw)
        val doc = Jsoup.parse(html, "", Parser.xmlParser())
        assertTrue(doc.select("script").isEmpty())
        assertEquals(2, doc.select("p").size)
        assertEquals(1, doc.select("img").size)
        assertTrue(doc.selectFirst("img")!!.attr("src").startsWith("https://appassets.androidplatform.net/image/"))
        assertTrue(html.contains("&lt;script&gt;"))
    }
    @Test fun foreignImageIsNotProxied() {
        val html = WenkuParser.chapter("测试", "<!--image-->https://127.0.0.1/admin<!--image-->\n正文")
        assertFalse(html.contains("<img"))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidBookIdRejected() { coverUrl("../../secret") }
    @Test fun zipPathsResolveNormalRelativePaths() { assertEquals("OPS/Images/a.jpg", resolveZipPath("OPS/Text", "../Images/a.jpg")) }
    @Test(expected = IllegalArgumentException::class) fun zipPathsRejectTraversal() { resolveZipPath("", "../../etc/passwd") }
    @Test(expected = IllegalArgumentException::class) fun zipPathsRejectExternalUrls() { resolveZipPath("OPS", "https://example.org/a") }
    @Test(expected = IllegalArgumentException::class) fun zipPathsRejectEncodedTraversal() { resolveZipPath("", "%2e%2e/secret") }
    @Test(expected = IllegalArgumentException::class) fun zipPathsRejectNestedEncodedTraversal() { resolveZipPath("OPS", "%2e%2e/%2e%2e/secret") }
}
