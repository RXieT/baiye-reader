package io.github.rxiet.baiye

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.rxiet.baiye.data.Book
import io.github.rxiet.baiye.reader.ReaderActivity
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun navigationAndSettings() {
        compose.onAllNodesWithText("书架").onLast().performClick()
        compose.onNodeWithText("收藏").assertExists()
        compose.onAllNodesWithText("导入").onFirst().assertExists()
        capture("shelf")
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("字号").assertExists()
        compose.onNodeWithText("纸色").assertExists()
        capture("settings")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onAllNodesWithText("文库").onLast().performClick()
        compose.onNodeWithText("更新").assertExists()
        capture("catalog")
    }

    @Test fun localEpubLoadsTurnsChaptersAndPersistsProgress() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BaiyeApplication
        val folder = File(app.filesDir, "imports").apply { mkdirs() }
        val file = File(folder, "smoke.epub")
        makeEpub(file)
        runBlocking {
            app.library.load()
            app.library.add(Book("local-smoke", "排版测试", author = "白页", local = true), epubFile = file.name)
        }
        ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("bookId", "local-smoke")).use {
            compose.waitUntil(45000) { compose.onAllNodesWithText("第一章").fetchSemanticsNodes().isNotEmpty() }
            capture("reader")
            compose.onNodeWithText("目录").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("第二章").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("第二章").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("第二章").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(10000) {
                app.library.state.value.entries.first { entry -> entry.book.id == "local-smoke" }.progress.chapter == "第二章"
            }
            assertTrue(app.library.state.value.entries.first { entry -> entry.book.id == "local-smoke" }.progress.cfi.startsWith("epubcfi("))
            capture("reader-chapter")
        }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun makeEpub(file: File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(path: String, text: String, stored: Boolean = false) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                val entry = ZipEntry(path)
                if (stored) { entry.method = ZipEntry.STORED; entry.size = bytes.size.toLong(); entry.crc = CRC32().apply { update(bytes) }.value }
                zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip", true)
            entry("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry("OPS/book.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">baiye-smoke</dc:identifier><dc:title>排版测试</dc:title><dc:language>zh-CN</dc:language><dc:creator>白页</dc:creator><meta property="dcterms:modified">2026-11-12T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/><item id="two" href="two.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>""")
            entry("OPS/nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>目录</title></head><body><nav epub:type="toc"><ol><li><a href="one.xhtml">第一章</a></li><li><a href="two.xhtml">第二章</a></li></ol></nav></body></html>""")
            for ((path, title) in listOf("one" to "第一章", "two" to "第二章")) {
                val paragraphs = (1..36).joinToString("") { "<p>窗外的风吹动白纸。书页留下整齐的文字，让阅读回到内容本身。这段文字由项目编写，用于测试中文排版与翻页。</p>" }
                entry("OPS/$path.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head><body><h1>$title</h1>$paragraphs</body></html>""")
            }
        }
    }
}
