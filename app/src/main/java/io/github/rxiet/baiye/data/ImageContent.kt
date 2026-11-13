package io.github.rxiet.baiye.data

import java.io.IOException
import java.util.Base64
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

internal const val IMAGE_CHAPTER_VERSION = "data-baiye-images=\"2\""
internal const val CHAPTER_SOURCE_FORMAT_META = "baiye-source-format"
internal const val CHAPTER_SOURCE_FORMAT_VERSION = "3"

/** This provenance marker is separate from the local image-migration version. */
internal fun hasCurrentChapterSourceFormat(cached: String): Boolean =
    Jsoup.parse(cached, "", Parser.xmlParser()).select("head meta[name=$CHAPTER_SOURCE_FORMAT_META]")
        .any { it.attr("content") == CHAPTER_SOURCE_FORMAT_VERSION }

internal val imageMarkers = Regex("<!--\\s*image\\s*-->(.*?)<!--\\s*image\\s*-->", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private const val IMAGE_PROXY = "https://appassets.androidplatform.net/image/"

internal fun chapterImageUrl(value: String): String {
    val source = Parser.unescapeEntities(value.trim(), true)
    val decoded = if (source.startsWith(IMAGE_PROXY)) {
        try {
            val key = source.removePrefix(IMAGE_PROXY)
            if (key.length >= 4096) throw IOException("图片地址过长")
            String(Base64.getUrlDecoder().decode(key), Charsets.UTF_8)
        } catch (error: IllegalArgumentException) { throw IOException("无效图片地址", error) }
    } else if (source.startsWith("//")) "https:$source" else source
    return imageUrl(decoded)
}

internal fun chapterImageProxy(value: String): String {
    val url = chapterImageUrl(value)
    val key = Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray(Charsets.UTF_8))
    if (key.length >= 4096) throw IOException("图片地址过长")
    return IMAGE_PROXY + key
}

/** Convert only known chapter envelopes/HTML to the upstream plain text + image-marker format. */
internal fun imageChapterPayload(raw: String): String {
    var text = raw.trimStart('\uFEFF')
    repeat(3) {
        val start = text.trimStart()
        if (start.startsWith("&lt;", true) || text.contains("&lt;!--image", true)) {
            text = Parser.unescapeEntities(text, false)
        }
        if (Regex("^(?:<\\?xml[^>]*>\\s*)?<(?:rawXML|content|text|result|response)(?:\\s|>)", RegexOption.IGNORE_CASE).containsMatchIn(text.trimStart())) {
            val xml = Jsoup.parse(protectImageMarkers(text), "", Parser.xmlParser())
            val payload = xml.selectFirst("rawXML, content, text") ?: xml.children().firstOrNull()
            if (payload != null) text = if (payload.children().isEmpty()) payload.wholeText() else payload.html()
        }
    }
    if (!Regex("</?(?:html|body|img|p|div|br|figure|section|article|h[1-6])\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return text
    // Protect comment-delimited images before Jsoup discards comments during text extraction.
    val doc = Jsoup.parse(protectImageMarkers(text))
    if (doc.title().contains("Just a moment", true) || doc.selectFirst("#challenge-form, #cf-challenge-running") != null) {
        throw IOException("书源需要验证")
    }
    val fullPage = Regex("<(?:html|!doctype)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
    val content = if (fullPage) doc.selectFirst("#content, article") ?: throw IOException("没有可用正文") else doc.body()
    return imageNodeText(content)
}

private fun protectImageMarkers(text: String): String = imageMarkers.replace(text) { marker ->
    Element("img").attr("src", marker.groupValues[1].trim()).outerHtml()
}

private fun imageNodeText(root: Node): String = buildString {
    fun visit(node: Node) {
        when (node) {
            is TextNode -> append(node.wholeText)
            is Element -> {
                val tag = node.normalName()
                if (tag in setOf("script", "style", "iframe", "object", "noscript") || node.id() == "contentdp" || node.hasClass("contentdp")) return
                if (tag == "img") {
                    val src = listOf("data-src", "data-original", "src").map { node.attr(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
                    append("\n<!--image-->").append(src).append("<!--image-->\n")
                    return
                }
                if (tag == "br" || node.isBlock) append('\n')
                node.childNodes().forEach(::visit)
                if (node.isBlock) append('\n')
            }
            else -> node.childNodes().forEach(::visit)
        }
    }
    visit(root)
}

/** Local, one-time migration; never certify that the API source has been refreshed. */
internal fun migrateImageChapter(title: String, cached: String): String {
    if (cached.contains(IMAGE_CHAPTER_VERSION)) return cached
    val doc = Jsoup.parse(cached, "", Parser.xmlParser())
    val body = doc.selectFirst("body") ?: return cached
    body.select("h1").firstOrNull()?.remove()
    // TextNode extraction also restores markers/img markup escaped by the old plain-text parser.
    return try { WenkuParser.chapter(title, imageNodeText(body)) }
    catch (_: IOException) { cached }
}
