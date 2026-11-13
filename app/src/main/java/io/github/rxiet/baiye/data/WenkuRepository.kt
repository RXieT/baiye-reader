package io.github.rxiet.baiye.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
class WenkuRepository(context: Context, baseClient: OkHttpClient) {
    private val folder = File(context.filesDir, "wenku").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val client = baseClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val metadataSlots = Semaphore(3)
    private val imageLocks = ConcurrentHashMap<String, Any>()
    private val chapterSourceRetryAfter = ConcurrentHashMap<String, Long>()
    private val imageMemory = object : android.util.LruCache<String, Pair<ByteArray, String>>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Pair<ByteArray, String>): Int = value.first.size
    }
    private val searches = ConcurrentHashMap<String, List<Book>>()

    suspend fun catalog(order: CatalogOrder, page: Int, query: String = ""): CatalogPage = withContext(Dispatchers.IO) {
        require(page in 1..10000)
        val cache = File(folder, "catalog-${hash("${order.value}:$page:$query")}.json")
        try {
            val result = try { apiCatalog(order, page, query) } catch (_: IOException) { webCatalog(order, page, query) }
            cacheText(cache, json.encodeToString(result))
            result.copy(books = result.books.map(::cachedSummary))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (!cache.isFile) throw error
            val saved = json.decodeFromString<CatalogPage>(cache.readText())
            saved.copy(books = saved.books.map(::cachedSummary))
        }
    }

    private fun cachedSummary(book: Book): Book {
        val cached = readDetail(book.id)?.book ?: runCatching {
            json.decodeFromString<Book>(File(folder, "meta-${book.id}.json").readText())
        }.getOrNull() ?: return book
        return book.copy(
            title = book.title.ifBlank { cached.title }, author = cached.author,
            cover = book.cover.ifBlank { cached.cover }, status = cached.status,
        )
    }

    suspend fun metadata(book: Book): Book = withContext(Dispatchers.IO) {
        if (book.local || book.author.isNotBlank()) return@withContext book
        requireId(book.id)
        locks.getOrPut("meta/${book.id}") { Mutex() }.withLock {
            val cached = cachedSummary(book)
            if (cached.author.isNotBlank()) return@withLock cached
            metadataSlots.withPermit {
                val result = WenkuParser.meta(book.id, api("action=book&do=meta&aid=${book.id}&t=0"), "")
                cacheText(File(folder, "meta-${book.id}.json"), json.encodeToString(result))
                result.copy(title = book.title.ifBlank { result.title })
            }
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
        val enriched = coroutineScope {
            books.map { book -> async { if (book.title.isNotBlank()) cachedSummary(book) else metadata(book) } }.awaitAll()
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
                coroutineScope {
                    val meta = async { api("action=book&do=meta&aid=${book.id}&t=0") }
                    val intro = async { api("action=book&do=intro&aid=${book.id}&t=0") }
                    val toc = async { api("action=book&do=list&aid=${book.id}&t=0") }
                    BookDetail(WenkuParser.meta(book.id, meta.await(), intro.await()), WenkuParser.toc(toc.await()))
                }
            } catch (_: IOException) { webDetail(book) }
            require(result.chapters.isNotEmpty()) { "没有可用章节" }
            cacheText(cache, json.encodeToString(result))
            result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { cached ?: throw error }
    }

    suspend fun chapterXhtml(bookId: String, chapter: Chapter): String = withContext(Dispatchers.IO) {
        requireId(bookId); requireId(chapter.id)
        locks.getOrPut("$bookId/${chapter.id}") { Mutex() }.withLock {
            val directory = File(folder, bookId).apply { mkdirs() }
            val file = File(directory, "${chapter.id}.xhtml")
            val saved = if (file.isFile) file.readText() else null
            val key = "$bookId/${chapter.id}"
            if (saved != null) {
                val migrated = migrateImageChapter(chapter.title, saved)
                if (migrated != saved) cacheText(file, migrated)
                // Local image migration cannot recover URLs discarded by older parsers, even
                // in ordinary text chapters. Only a successful API parse certifies the source.
                if (hasCurrentChapterSourceFormat(migrated) ||
                    android.os.SystemClock.elapsedRealtime() < (chapterSourceRetryAfter[key] ?: 0L)) {
                    return@withLock migrated
                }
                val refreshed = try {
                    // No blocking web fallback here: a readable cache must be returned promptly.
                    withTimeoutOrNull(6_000L) {
                        WenkuParser.chapterFromApi(chapter.title,
                            api("action=book&do=text&aid=$bookId&cid=${chapter.id}&t=0"))
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                if (refreshed != null) {
                    cacheText(file, refreshed)
                    chapterSourceRetryAfter.remove(key)
                    return@withLock refreshed
                }
                // The chapter mutex serializes attempts; failures only suppress retries briefly.
                chapterSourceRetryAfter[key] = android.os.SystemClock.elapsedRealtime() + 60_000L
                return@withLock migrated
            }
            var fromApi = true
            val raw = try {
                api("action=book&do=text&aid=$bookId&cid=${chapter.id}&t=0")
            } catch (_: IOException) {
                fromApi = false
                val path = "/novel/${bookId.toInt() / 1000}/$bookId/${chapter.id}.htm"
                val doc = web(path)
                val content = doc.getElementById("content") ?: throw IOException("没有可用正文")
                content.select("script, style, iframe, object, #contentdp, .contentdp").remove()
                content.select("img").forEach { image ->
                    val attribute = listOf("data-src", "data-original", "src").firstOrNull { image.attr(it).isNotBlank() }
                    val url = attribute?.let { image.absUrl(it) }.orEmpty()
                    image.replaceWith(TextNode("\n<!--image-->$url<!--image-->\n"))
                }
                content.wholeText()
            }
            if (raw.isBlank()) throw IOException("没有可用正文")
            val html = if (fromApi) WenkuParser.chapterFromApi(chapter.title, raw)
                else WenkuParser.chapter(chapter.title, raw)
            cacheText(file, html)
            html
        }
    }

    fun image(key: String, requireStored: Boolean = false): Pair<ByteArray, String> = imageContent(key, requireStored, false)
    fun retryImage(key: String): Pair<ByteArray, String> = imageContent(key, false, true)

    private fun imageContent(key: String, requireStored: Boolean, forceRefresh: Boolean): Pair<ByteArray, String> {
        if (key.length >= 4096) throw IOException("图片地址过长")
        val safe = try { imageUrl(String(Base64.getUrlDecoder().decode(key), Charsets.UTF_8)) }
        catch (error: IllegalArgumentException) { throw IOException("无效图片地址", error) }
        val name = hash(safe)
        val cache = File(File(folder, "images").apply { mkdirs() }, name)
        synchronized(imageLocks.getOrPut(name) { Any() }) {
            if (forceRefresh) { imageMemory.remove(name); cache.delete() }
            imageMemory.get(name)?.let { if (!requireStored || cache.isFile) return it }
            if (cache.isFile && !forceRefresh) {
                try {
                    if (cache.length() > 16 * 1024 * 1024) throw IOException("图片缓存过大")
                    val data = cache.readBytes()
                    return (data to imageMime(data)).also { imageMemory.put(name, it) }
                } catch (_: IOException) {
                    // A truncated/obsolete file must not permanently poison subsequent loads.
                    cache.delete()
                    imageMemory.remove(name)
                }
            }
            // Stored-only readers may persist existing memory bytes, but must never fetch.
            val data = imageMemory.get(name)?.first ?: if (requireStored) {
                throw IOException("图片尚未缓存")
            } else imageBytes(safe, forceRefresh)
            val result = data to imageMime(data)
            imageMemory.put(name, result)
            val atomic = AtomicFile(cache)
            var output: java.io.FileOutputStream? = null
            try {
                output = atomic.startWrite(); output.write(data); atomic.finishWrite(output)
            } catch (error: IOException) {
                if (output != null) atomic.failWrite(output)
                if (requireStored) throw error
            }
            return result
        }
    }

    suspend fun cacheChapter(bookId: String, chapter: Chapter) = withContext(Dispatchers.IO) {
        val html = chapterXhtml(bookId, chapter)
        if (!File(File(folder, bookId), "${chapter.id}.xhtml").isFile) throw IOException("无法写入缓存")
        val keys = Jsoup.parse(html).select("img[src]").map { it.attr("src").substringAfter("/image/", "") }.filter { it.isNotEmpty() }.distinct()
        coroutineScope {
            keys.chunked(2).forEach { group ->
                group.map { key ->
                    async {
                        // Explicit download first; the stored-only pass verifies durable caching.
                        image(key)
                        image(key, requireStored = true)
                    }
                }.awaitAll()
            }
        }
    }

    private fun imageBytes(original: String, forceRefresh: Boolean): ByteArray {
        val parsed = java.net.URI(original)
        val publicPictureHost = parsed.host in setOf("pic.wenku8.com", "pic.wenku8.cn", "pic.wenku8.net", "img.wenku8.com")
        val publicImagePath = parsed.path.startsWith("/pictures/") || parsed.path.startsWith("/image/")
        val candidates = mutableListOf(original)
        if (publicPictureHost && publicImagePath) {
            val alternate = original.replaceFirst("${parsed.scheme}://", if (parsed.scheme == "https") "http://" else "https://")
            candidates += imageUrl(alternate)
        }
        var lastFailure: IOException? = null
        for (candidate in candidates.distinct()) {
            var next = candidate
            try {
                for (hop in 0..3) {
                    val request = Request.Builder().url(next)
                        .header("Referer", "${java.net.URI(next).scheme}://www.wenku8.net/")
                        .header("User-Agent", System.getProperty("http.agent") ?: "Baiye/1.2 Android")
                        // Do not advertise AVIF: older Android WebViews cannot decode it.
                        .header("Accept", "image/jpeg,image/png,image/webp,image/gif;q=0.9,image/*;q=0.5")
                        .apply { if (forceRefresh) cacheControl(okhttp3.CacheControl.FORCE_NETWORK) }.build()
                    client.newCall(request).execute().use { response ->
                        if (response.code in setOf(301, 302, 303, 307, 308)) {
                            val location = response.header("Location") ?: throw IOException("图片跳转无效")
                            val resolved = response.request.url.resolve(location) ?: throw IOException("图片跳转无效")
                            next = imageUrl(resolved.toString())
                        } else {
                            val result = readResponse(response, 16 * 1024 * 1024)
                            // CDN Content-Type can be octet-stream or stale; bytes are authoritative.
                            // Conversely, an image Content-Type must never admit a challenge HTML page.
                            imageMime(result, response.header("Content-Type"))
                            return result
                        }
                    }
                }
                throw IOException("图片跳转过多")
            } catch (failure: IOException) { lastFailure = failure }
        }
        throw lastFailure ?: IOException("图片暂不可用")
    }

    private fun imageMime(bytes: ByteArray, declared: String? = null): String {
        fun ascii(offset: Int, length: Int): String = if (offset >= 0 && offset + length <= bytes.size)
            String(bytes, offset, length, Charsets.US_ASCII) else ""
        return when {
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
            bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> "image/png"
            ascii(0, 6) in setOf("GIF87a", "GIF89a") -> "image/gif"
            bytes.size >= 16 && ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" && ascii(12, 4) in setOf("VP8 ", "VP8L", "VP8X") -> "image/webp"
            isAvif(bytes) -> "image/avif"
            else -> throw IOException(if (declared?.substringBefore(';')?.trim()?.startsWith("text/", true) == true)
                "图片返回了网页而非图片" else "不支持的图片")
        }
    }

    private fun isAvif(bytes: ByteArray): Boolean {
        if (bytes.size < 16 || String(bytes, 4, 4, Charsets.US_ASCII) != "ftyp") return false
        val boxSize = (0..3).fold(0L) { size, index -> (size shl 8) or (bytes[index].toLong() and 255) }
        if (boxSize < 16 || boxSize > bytes.size || boxSize > 4096 || boxSize % 4 != 0L) return false
        // Major brand is at 8; minor version at 12 is not a compatible brand.
        return (listOf(8) + (16 until boxSize.toInt() step 4)).any { offset ->
            String(bytes, offset, 4, Charsets.US_ASCII) in setOf("avif", "avis")
        }
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

    private fun cacheText(file: File, text: String) {
        try { atomic(file, text) }
        catch (failure: IOException) { android.util.Log.w("BaiyeNetwork", "Optional cache write deferred", failure) }
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

    fun chapter(title: String, raw: String): String = renderChapter(title, raw, fromApi = false)

    internal fun chapterFromApi(title: String, raw: String): String = renderChapter(title, raw, fromApi = true)

    private fun renderChapter(title: String, raw: String, fromApi: Boolean): String {
        val payload = imageChapterPayload(raw)
        if (payload.isBlank()) throw IOException("没有可用正文")
        val doc = Document.createShell("")
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml).escapeMode(Entities.EscapeMode.xhtml).prettyPrint(false)
        doc.selectFirst("html")!!.attr("xmlns", "http://www.w3.org/1999/xhtml").attr("lang", "zh-CN").attr("data-baiye-images", "2")
        doc.title(title)
        if (fromApi) doc.head().appendElement("meta")
            .attr("name", CHAPTER_SOURCE_FORMAT_META).attr("content", CHAPTER_SOURCE_FORMAT_VERSION)
        doc.body().appendElement("h1").text(title)
        fun paragraphs(text: String) {
            text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
                doc.body().appendElement("p").text(line)
            }
        }
        var cursor = 0
        imageMarkers.findAll(payload).forEach { marker ->
            paragraphs(payload.substring(cursor, marker.range.first))
            try {
                val proxy = chapterImageProxy(marker.groupValues[1])
                doc.body().appendElement("figure").appendElement("img")
                    .attr("src", proxy).attr("alt", "插图").attr("decoding", "async")
            } catch (_: IOException) {
                // Keep a visible diagnostic instead of silently deleting an illustration.
                doc.body().appendElement("p").text("[插图地址不受支持]")
            }
            cursor = marker.range.last + 1
        }
        paragraphs(payload.substring(cursor))
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + doc.outerHtml()
    }
}

internal fun requireId(id: String) { require(id.matches(Regex("[1-9][0-9]{0,8}"))) { "无效书籍编号" } }
internal fun coverUrl(id: String): String { requireId(id); return "https://img.wenku8.com/image/${id.toInt() / 1000}/$id/${id}s.jpg" }
internal fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
internal fun imageUrl(value: String): String {
    val uri = try { java.net.URI(value).normalize() }
    catch (error: java.net.URISyntaxException) { throw IOException("无效图片地址", error) }
    val host = uri.host?.lowercase() ?: throw IOException("无效图片地址")
    val inputScheme = uri.scheme?.lowercase()
    if (inputScheme !in setOf("http", "https") || uri.userInfo != null ||
        uri.port !in setOf(-1, if (inputScheme == "http") 80 else 443)) throw IOException("无效图片地址")
    val path = uri.rawPath.orEmpty()
    // Exact public upstream CDN fixture (WenkuReaderLoaderXMLTest), not a general proxy host.
    // It is HTTPS-only and restricted to the documented numeric chapter-image path shape.
    val upstreamCdnImage = host == "pic.777743.xyz" &&
        path.matches(Regex("/[0-9]+/[1-9][0-9]*/[1-9][0-9]*/[1-9][0-9]*\\.(?:jpe?g|png|gif|webp|avif)", RegexOption.IGNORE_CASE))
    if (!host.matches(Regex("(?:[a-z0-9-]+\\.)?wenku8\\.(?:com|net|cn|cc)")) && !upstreamCdnImage) throw IOException("图片地址不受支持")
    val cleartextHost = host in setOf("pic.wenku8.com", "pic.wenku8.cn", "pic.wenku8.net", "img.wenku8.com")
    val scheme = if (inputScheme == "http" && cleartextHost && (path.startsWith("/pictures/") || path.startsWith("/image/"))) "http" else "https"
    // Preserve escaped path/query components (decoding and reconstructing changes signed URLs).
    return java.net.URI("$scheme://$host$path" + (uri.rawQuery?.let { "?$it" } ?: "")).toASCIIString()
}
