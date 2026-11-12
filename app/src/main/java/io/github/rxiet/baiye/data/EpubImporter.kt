package io.github.rxiet.baiye.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** Reads metadata without extracting arbitrary ZIP paths into the filesystem. */
class EpubImporter(private val context: Context) {
    suspend fun import(uri: Uri): ShelfEntry = withContext(Dispatchers.IO) {
        val folder = File(context.filesDir, "imports").apply { mkdirs() }
        val temp = File.createTempFile("import-", ".tmp", folder)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var count = 0L
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        count += size
                        require(count <= MAX_FILE_SIZE) { "书籍过大" }
                        digest.update(buffer, 0, size)
                        output.write(buffer, 0, size)
                    }
                }
            } ?: error("无法打开书籍")
            val id = "local-" + digest.digest().joinToString("") { "%02x".format(it) }.take(32)
            ZipFile(temp).use { zip ->
                require(zip.size() <= 20000) { "书籍文件过多" }
                var expanded = 0L
                zip.entries().asSequence().forEach {
                    require(it.size in 0..(64L * 1024 * 1024)) { "书籍格式异常" }
                    expanded += it.size
                    require(expanded <= 512L * 1024 * 1024) { "书籍过大" }
                }
                val container = xml(zip, "META-INF/container.xml")
                val opfPath = container.selectFirst("rootfile")?.attr("full-path") ?: error("无效的 EPUB")
                require(resolveZipPath("", opfPath) == opfPath) { "无效的 EPUB" }
                val opf = xml(zip, opfPath)
                val title = opf.getElementsByTag("dc:title").firstOrNull()?.text()
                    ?: opf.getElementsByTag("title").firstOrNull()?.text().orEmpty()
                require(title.isNotBlank()) { "书籍缺少标题" }
                val author = opf.getElementsByTag("dc:creator").joinToString("、") { it.text() }
                val description = opf.getElementsByTag("dc:description").firstOrNull()?.text().orEmpty()
                val items = opf.select("manifest > item")
                val coverId = opf.select("metadata > meta[name=cover]").firstOrNull()?.attr("content")
                val cover = items.find { "cover-image" in it.attr("properties").split(' ') }
                    ?: items.find { it.id() == coverId && coverId != null }
                var coverPath = ""
                if (cover != null && cover.attr("media-type") in setOf("image/jpeg", "image/png", "image/webp")) {
                    val entry = zip.getEntry(resolveZipPath(opfPath.substringBeforeLast('/', ""), cover.attr("href")))
                    if (entry != null && entry.size in 1..(8 * 1024 * 1024)) {
                        val extension = when (cover.attr("media-type")) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
                        val destination = File(folder, "$id.$extension")
                        zip.getInputStream(entry).use { input -> destination.outputStream().use { input.copyTo(it) } }
                        coverPath = destination.toURI().toString()
                    }
                }
                val destination = File(folder, "$id.epub")
                if (destination.exists()) temp.delete() else check(temp.renameTo(destination)) { "无法保存书籍" }
                ShelfEntry(
                    book = Book(id, title, author, coverPath, description, local = true),
                    epubFile = destination.name,
                )
            }
        } finally { temp.delete() }
    }

    private fun xml(zip: ZipFile, name: String): org.jsoup.nodes.Document {
        val entry = zip.getEntry(name) ?: error("无效的 EPUB")
        require(entry.size in 1..(4 * 1024 * 1024)) { "书籍格式异常" }
        return zip.getInputStream(entry).use { Jsoup.parse(it, "UTF-8", "", Parser.xmlParser()) }
    }

    companion object { const val MAX_FILE_SIZE = 128L * 1024 * 1024 }
}

internal fun resolveZipPath(base: String, href: String): String {
    require(!href.startsWith('/') && !href.contains('\\')) { "无效的书籍路径" }
    val reference = URI(href)
    require(!reference.isAbsolute && reference.authority == null) { "无效的书籍路径" }
    val decoded = URI(if (base.isEmpty()) "" else "$base/").resolve(reference).normalize().path
    val path = URI(null, null, decoded, null).normalize().path
    require(path.isNotEmpty() && !path.startsWith("../") && !path.startsWith('/') && !path.contains('\\')) { "无效的书籍路径" }
    return path
}
