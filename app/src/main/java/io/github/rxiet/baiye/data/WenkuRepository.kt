package io.github.rxiet.baiye.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.Response
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/** Anonymous requests only. No user cookies, account tokens or bundled APK binaries. */
class WenkuRepository(context: Context) {
    private val folder = File(context.filesDir, "wenku").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val searches = ConcurrentHashMap<String, List<Book>>()

    suspend fun catalog(order: CatalogOrder, page: Int, query: String = ""): CatalogPage = withContext(Dispatchers.IO) {
        require(page in 1..10000)
        val cache = File(folder, "catalog-${hash("${order.value}:$page:$query")}.json")
        try {
            val result = try { apiCatalog(order, page, query) } catch (_: IOException) { webCatalog(order, page, query) }
            atomic(cache, json.encodeToString(result))
            result
        } catch (error: Exception) {
            if (!cache.isFile) throw error
            json.decodeFromString<CatalogPage>(cache.readText())
        }
    }

    private suspend fun apiCatalog(order: CatalogOrder, page: Int, query: String): CatalogPage {
        val books: List<Book>
        val hasMore: Boolean
        if (query.isNotBlank()) {
            val all = searches[query] ?: WenkuParser.list(api(
                "action=search&searchtype=articlename&searchkey=${URLEncoder.encode(query, "UTF-8")}&t=0",
            )).first.also { searches[query] = it }
            val start = (page - 1) * 12
            books = all.drop(start).take(12)
            hasMore = start + books.size < all.size
        } else {
            val parsed = WenkuParser.list(api("action=novellist&sort=${order.value}&page=$page&t=0"))
            books = parsed.first
            hasMore = page < parsed.second
        }
        val enriched = books.map { book ->
            if (book.title.isNotBlank()) book else {
                val cache = readDetail(book.id)
                if (cache != null) cache.book else {
                    delay(100)
                    WenkuParser.meta(book.id, api("action=book&do=meta&aid=${book.id}&t=0"), "")
                }
            }
        }
        return CatalogPage(enriched, hasMore)
    }

    suspend fun detail(book: Book): BookDetail = withContext(Dispatchers.IO) {
        requireId(book.id)
        val cached = readDetail(book.id)
        val cache = detailFile(book.id)
        if (cached != null && System.currentTimeMillis() - cache.lastModified() < 30 * 60 * 1000) return@withContext cached
        try {
            val result = try {
                val meta = api("action=book&do=meta&aid=${book.id}&t=0")
                val intro = api("action=book&do=intro&aid=${book.id}&t=0")
                val toc = api("action=book&do=list&aid=${book.id}&t=0")
                BookDetail(WenkuParser.meta(book.id, meta, intro), WenkuParser.toc(toc))
            } catch (_: IOException) { webDetail(book) }
            require(result.chapters.isNotEmpty()) { "没有可用章节" }
            atomic(cache, json.encodeToString(result))
            result
        } catch (error: Exception) { cached ?: throw error }
    }

    suspend fun chapterXhtml(bookId: String, chapter: Chapter): String = withContext(Dispatchers.IO) {
        requireId(bookId); requireId(chapter.id)
        locks.getOrPut("$bookId/${chapter.id}") { Mutex() }.withLock {
            val directory = File(folder, bookId).apply { mkdirs() }
            val file = File(directory, "${chapter.id}.xhtml")
            if (file.isFile) return@withLock file.readText()
            val raw = try {
                api("action=book&do=text&aid=$bookId&cid=${chapter.id}&t=0")
            } catch (_: IOException) {
                val path = "/novel/${bookId.toInt() / 1000}/$bookId/${chapter.id}.htm"
                val doc = web(path)
                val content = doc.getElementById("content") ?: throw IOException("没有可用正文")
                content.select("script, style, iframe, object, #contentdp, .contentdp").remove()
                content.select("img[src]").forEach { image ->
                    val url = image.absUrl("src")
                    image.replaceWith(TextNode("\n<!--image-->$url<!--image-->\n"))
                }
                content.wholeText()
            }
            if (raw.isBlank()) throw IOException("没有可用正文")
            val html = WenkuParser.chapter(chapter.title, raw)
            atomic(file, html)
            html
        }
    }

    fun image(key: String): Pair<ByteArray, String> {
        require(key.length < 4096)
        val url = String(Base64.getUrlDecoder().decode(key), Charsets.UTF_8)
        val safe = imageUrl(url)
        val name = hash(safe)
        val cache = File(File(folder, "images").apply { mkdirs() }, name)
        if (cache.isFile) return cache.readBytes().let { it to imageMime(it) }
        val bytes = bytes(Request.Builder().url(safe).build(), 16 * 1024 * 1024)
        val mime = imageMime(bytes)
        val atomic = AtomicFile(cache)
        val out = atomic.startWrite()
        try { out.write(bytes); atomic.finishWrite(out) } catch (error: Exception) { atomic.failWrite(out); throw error }
        return bytes to mime
    }

    private fun imageMime(bytes: ByteArray): String = when {
        bytes.size > 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> "image/jpeg"
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
        bytes.size > 6 && String(bytes, 0, 3, Charsets.US_ASCII) == "GIF" -> "image/gif"
        bytes.size > 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> throw IOException("不支持的图片")
    }

    suspend fun removeCachedBook(id: String) = withContext(Dispatchers.IO) {
        if (id.matches(Regex("[1-9][0-9]{0,8}"))) {
            File(folder, id).deleteRecursively()
            detailFile(id).delete()
        }
    }

    private fun readDetail(id: String): BookDetail? = runCatching {
        json.decodeFromString<BookDetail>(detailFile(id).readText())
    }.getOrNull()
    private fun detailFile(id: String) = File(folder, "detail-$id.json")

    private suspend fun api(inner: String): String {
        val body = FormBody.Builder()
            .add("appver", WenkuVersion.value())
            .add("request", Base64.getEncoder().encodeToString(inner.toByteArray(Charsets.UTF_8)))
            .add("timetoken", System.currentTimeMillis().toString()).build()
        val request = Request.Builder().url("https://wenku8-relay.mewx.org/")
            .header("User-Agent", "Baiye/1.0 Android").post(body).build()
        val result = String(awaitBytes(request, 8 * 1024 * 1024), Charsets.UTF_8)
        if (result.isBlank() || result.trimStart().startsWith("<!DOCTYPE", true)
            || result.trimStart().startsWith("<html", true) || result.trim().equals("Bad request.", true)) throw IOException("书源暂不可用")
        return result
    }

    private fun bytes(request: Request, limit: Int): ByteArray = client.newCall(request).execute().use { readResponse(it, limit) }

    private suspend fun awaitBytes(request: Request, limit: Int): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val data = response.use { if (!continuation.isActive) return; readResponse(it, limit) }
                    continuation.resume(data)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private fun readResponse(response: Response, limit: Int): ByteArray {
        if (!response.isSuccessful) throw IOException("书源请求失败 ${response.code}")
        val body = response.body ?: throw IOException("空响应")
        if (body.contentLength() > limit) throw IOException("响应过大")
        val data = body.byteStream().use { it.readNBytes(limit + 1) }
        if (data.size > limit) throw IOException("响应过大")
        return data
    }

    private fun web(path: String): Document {
        val url = "https://www.wenku8.net$path"
        val data = bytes(Request.Builder().url(url).header("User-Agent", "Baiye/1.0 Android").build(), 8 * 1024 * 1024)
        val doc = Jsoup.parse(data.inputStream(), "GB18030", url)
        if (doc.title().contains("Just a moment", true) || doc.selectFirst("#challenge-form") != null) throw IOException("书源需要验证")
        return doc
    }

    private fun webCatalog(order: CatalogOrder, page: Int, query: String): CatalogPage {
        val path = when {
            query.isNotBlank() -> "/modules/article/search.php?searchtype=articlename&searchkey=${URLEncoder.encode(query, "GBK")}&page=$page"
            order == CatalogOrder.COMPLETE -> "/modules/article/articlelist.php?fullflag=1&page=$page"
            else -> "/modules/article/toplist.php?sort=${order.value}&page=$page"
        }
        val doc = web(path)
        val pattern = Regex("/book/([1-9][0-9]*)\\.htm")
        val books = doc.select("a[href]").mapNotNull { link ->
            val id = pattern.find(link.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
            val title = link.text().trim()
            if (title.isBlank()) null else Book(id, title, cover = coverUrl(id))
        }.distinctBy { it.id }
        if (books.isEmpty() && !doc.text().contains("没有")) throw IOException("书源格式变化")
        val max = doc.select(".pagelast, .pagelink a").mapNotNull { it.text().toIntOrNull() }.maxOrNull() ?: page
        return CatalogPage(books, page < max)
    }

    private fun webDetail(book: Book): BookDetail {
        val doc = web("/book/${book.id}.htm")
        val title = doc.selectFirst("#content b")?.text()?.takeIf { it.isNotBlank() } ?: book.title
        val all = doc.text()
        val author = Regex("小说作者[：:]\\s*([^\\s]+)").find(all)?.groupValues?.get(1).orEmpty()
        val intro = doc.selectFirst("#content td span[style*=font-size]")?.wholeText().orEmpty()
        val index = web("/novel/${book.id.toInt() / 1000}/${book.id}/index.htm")
        var volume = ""
        val chapters = mutableListOf<Chapter>()
        index.select("table.css td").forEach { cell ->
            if (cell.hasClass("vcss")) volume = cell.text()
            cell.select("a[href]").forEach { link ->
                val id = Regex("(?:^|/)([1-9][0-9]*)\\.htm(?:$|#)").find(link.attr("href"))?.groupValues?.get(1)
                if (id != null) chapters += Chapter(id, link.text(), volume)
            }
        }
        if (chapters.isEmpty()) throw IOException("没有可用目录")
        return BookDetail(book.copy(title = title, author = author.ifBlank { book.author },
            description = intro, cover = coverUrl(book.id)), chapters.distinctBy { it.id })
    }

    private fun atomic(file: File, text: String) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
}

object WenkuParser {
    fun list(raw: String): Pair<List<Book>, Int> {
        val doc = Jsoup.parse(raw, "", Parser.xmlParser())
        if (doc.selectFirst("result") == null) throw IOException("书源格式变化")
        val books = doc.select("item[aid]").map { item ->
            val id = item.attr("aid"); requireId(id)
            val title = item.select("data").find { it.attr("name") == "Title" }?.text().orEmpty()
            Book(id, title, cover = coverUrl(id))
        }.distinctBy { it.id }
        return books to (doc.selectFirst("page")?.attr("num")?.toIntOrNull() ?: 1)
    }

    fun meta(id: String, raw: String, intro: String): Book {
        requireId(id)
        val doc = Jsoup.parse(raw, "", Parser.xmlParser())
        if (doc.selectFirst("metadata") == null) throw IOException("书源格式变化")
        fun field(name: String): String = doc.select("data").find { it.attr("name") == name }
            ?.let { it.attr("value").ifBlank { it.text() } }.orEmpty()
        val title = field("Title").ifBlank { throw IOException("书籍缺少标题") }
        return Book(id, title, field("Author"), coverUrl(id), intro.trim(), field("BookStatus"))
    }

    fun toc(raw: String): List<Chapter> {
        val doc = Jsoup.parse(raw, "", Parser.xmlParser())
        val result = doc.select("volume").flatMap { volume ->
            volume.select("chapter[cid]").map { chapter ->
                val id = chapter.attr("cid"); requireId(id)
                Chapter(id, chapter.text(), volume.ownText())
            }
        }.distinctBy { it.id }
        if (result.isEmpty()) throw IOException("没有可用目录")
        return result
    }

    fun chapter(title: String, raw: String): String {
        if (raw.isBlank() || raw.trimStart().startsWith("<html", true) || raw.trimStart().startsWith("<!doctype", true)) throw IOException("没有可用正文")
        val doc = Document.createShell("")
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml).escapeMode(Entities.EscapeMode.xhtml).prettyPrint(false)
        doc.selectFirst("html")!!.attr("xmlns", "http://www.w3.org/1999/xhtml").attr("lang", "zh-CN")
        doc.title(title)
        doc.body().appendElement("h1").text(title)
        fun paragraphs(text: String) {
            text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
                doc.body().appendElement("p").text(line)
            }
        }
        val markers = Regex("<!--image-->(.*?)<!--image-->", RegexOption.DOT_MATCHES_ALL)
        var cursor = 0
        markers.findAll(raw).forEach { marker ->
            paragraphs(raw.substring(cursor, marker.range.first))
            runCatching { imageUrl(marker.groupValues[1].trim()) }.getOrNull()?.let { url ->
                val key = Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray(Charsets.UTF_8))
                doc.body().appendElement("figure").appendElement("img")
                    .attr("src", "https://appassets.androidplatform.net/image/$key").attr("alt", "插图")
            }
            cursor = marker.range.last + 1
        }
        paragraphs(raw.substring(cursor))
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + doc.outerHtml()
    }
}

internal fun requireId(id: String) { require(id.matches(Regex("[1-9][0-9]{0,8}"))) { "无效书籍编号" } }
internal fun coverUrl(id: String): String { requireId(id); return "https://img.wenku8.com/image/${id.toInt() / 1000}/$id/${id}s.jpg" }
internal fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
internal fun imageUrl(value: String): String {
    val uri = java.net.URI(value)
    val host = uri.host?.lowercase() ?: throw IOException("无效图片地址")
    require(uri.scheme in setOf("http", "https") && uri.userInfo == null && uri.port in setOf(-1, 443, 80))
    require(host.matches(Regex("(?:[a-z0-9-]+\\.)?wenku8\\.(?:com|net|cn|cc)"))) { "图片地址不受支持" }
    return java.net.URI("https", null, host, -1, uri.path, uri.query, null).toASCIIString()
}
