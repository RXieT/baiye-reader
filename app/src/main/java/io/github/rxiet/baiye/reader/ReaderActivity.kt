package io.github.rxiet.baiye.reader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.rxiet.baiye.BuildConfig
import io.github.rxiet.baiye.data.ReadingProgress
import io.github.rxiet.baiye.ui.*
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import kotlin.math.roundToInt

class ReaderActivity : ComponentActivity() {
    private var readerModel: ReaderViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT))
        setContent { BaiyeTheme { Reader() } }
    }

    override fun onPause() { readerModel?.flushProgress(); super.onPause() }

    @Composable
    private fun Reader(vm: ReaderViewModel = viewModel()) {
        readerModel = vm
        val document by vm.document.collectAsStateWithLifecycle()
        val error by vm.error.collectAsStateWithLifecycle()
        val library by vm.library.collectAsStateWithLifecycle()
        val toc by vm.toc.collectAsStateWithLifecycle()
        val progress by vm.progress.collectAsStateWithLifecycle()
        val ready by vm.ready.collectAsStateWithLifecycle()
        var webView by remember { mutableStateOf<WebView?>(null) }
        var chrome by remember { mutableStateOf(true) }
        var panel by remember { mutableStateOf("") }
        var seek by remember { mutableStateOf<Float?>(null) }
        val settings = library.settings
        val paper = when (settings.theme) { "night" -> Color(0xFF171717); "white" -> Color.White; else -> Color(0xFFF6F1E7) }
        val foreground = if (settings.theme == "night") Color(0xFFE4E1D9) else Ink
        val command: (JSONObject) -> Unit = { data ->
            webView?.evaluateJavascript("window.Reader?.commands(${data});", null)
        }
        LaunchedEffect(settings, ready) {
            if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightStatusBars = settings.theme != "night"
            controller.isAppearanceLightNavigationBars = settings.theme != "night"
            if (ready) command(JSONObject().put("type", "settings").put("settings", JSONObject(ReaderJson.encodeToString(settings))))
        }
        DisposableEffect(webView) {
            val current = webView
            onDispose {
                vm.flushProgress()
                if (current != null) {
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(current, "Native")
                    current.stopLoading()
                    current.destroy()
                }
            }
        }
        BackHandler(enabled = panel.isNotEmpty()) { panel = "" }
        Box(Modifier.fillMaxSize().background(paper).safeDrawingPadding()) {
            val doc = document
            if (doc != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        try {
                            buildWebView(context, vm, doc) { chrome = !chrome }.also { webView = it }
                        } catch (_: Exception) {
                            vm.error.value = "请更新 WebView"
                            android.widget.FrameLayout(context)
                        }
                    },
                )
            } else if (error == null) {
                Text("加载中", Modifier.align(Alignment.Center), fontSize = 18.sp, color = foreground)
            }
            if (chrome && panel.isEmpty()) {
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().background(paper.copy(alpha = 0.97f)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { finish() }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = foreground)
                    }
                    Text(doc?.entry?.book?.title.orEmpty(), Modifier.weight(1f), fontSize = 20.sp,
                        color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(paper.copy(alpha = 0.98f)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(progress.chapter.ifBlank { "阅读进度" }, Modifier.weight(1f), fontSize = 16.sp,
                            color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(12.dp))
                        Text("${(progress.fraction * 100).roundToInt()}%", fontSize = 16.sp, color = foreground)
                    }
                    Slider(
                        value = seek ?: progress.fraction.toFloat().coerceIn(0f, 1f),
                        onValueChange = { seek = it }, valueRange = 0f..1f, enabled = ready,
                        onValueChangeFinished = {
                            seek?.let { command(JSONObject().put("type", "fraction").put("fraction", it.toDouble())) }
                            seek = null
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("上一页", "下一页", "目录", "设置").forEachIndexed { index, label ->
                            Button(
                                onClick = {
                                    when (index) {
                                        0 -> command(JSONObject().put("type", "prev"))
                                        1 -> command(JSONObject().put("type", "next"))
                                        2 -> panel = "toc"
                                        3 -> panel = "settings"
                                    }
                                },
                                enabled = ready || index == 3,
                                modifier = Modifier.weight(1f), minWidth = 0.dp, minHeight = 48.dp,
                                insideMargin = PaddingValues(horizontal = 6.dp, vertical = 12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    color = foreground.copy(alpha = 0.07f), contentColor = foreground,
                                ),
                            ) { Text(label, fontSize = 16.sp) }
                        }
                    }
                }
            }
            if (error != null && panel.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center).fillMaxWidth().background(paper).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(error.orEmpty(), fontSize = 20.sp, color = foreground)
                    ActionButton("重试", {
                        val requiresNewWebView = webView == null || error == "请更新 WebView"
                        vm.error.value = null
                        if (document == null) vm.load()
                        else if (requiresNewWebView) recreate()
                        else if (!vm.ready.value) webView?.reload()
                        else command(JSONObject().put("type", "retry"))
                    })
                    ActionButton("返回", { finish() })
                }
            }
            when (panel) {
                "toc" -> Column(Modifier.fillMaxSize().background(Color.White)) {
                    Header("目录", { panel = "" })
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
                        items(toc) { item ->
                            Text(item.label,
                                Modifier.fillMaxWidth().clickable {
                                    command(JSONObject().put("type", "goto").put("href", item.href))
                                    panel = ""
                                }.heightIn(min = 52.dp).padding(vertical = 14.dp),
                                fontSize = 18.sp, lineHeight = 26.sp, color = Ink)
                        }
                    }
                }
                "settings" -> SettingsScreen(settings, { vm.settings(it) }, { panel = "" }, { panel = "licenses" })
                "licenses" -> LicensesScreen { panel = "settings" }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(context: Context, vm: ReaderViewModel, document: ReaderDocument, onTap: () -> Unit): WebView {
        val webView = WebView(context)
        webView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        webView.setBackgroundColor(AndroidColor.TRANSPARENT)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                vm.startingSession()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Foliate owns its same-origin blob frames. Block top-level/external navigation,
                // not the renderer's own iframe loads.
                val uri = request.url
                if (!request.isForMainFrame && uri.scheme == "blob" && uri.toString().startsWith("blob:$ORIGIN/")) return false
                if (!request.isForMainFrame && uri.scheme == "https" && uri.host == HOST && uri.path?.startsWith("/chapter/") == true) return false
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val uri = request.url
                if (uri.scheme != "https" || uri.host != HOST || request.method != "GET") return response("", "text/plain", 403)
                return try {
                    when {
                        uri.path == "/session.json" -> response(ReaderJson.encodeToString(vm.sessionManifest(document)), "application/json")
                        uri.path == "/book.epub" && document.entry.book.local -> {
                            val root = File(context.filesDir, "imports").canonicalFile
                            val file = File(root, document.entry.epubFile).canonicalFile
                            check(file.parentFile == root && file.extension == "epub" && file.isFile)
                            WebResourceResponse("application/epub+zip", null, file.inputStream())
                        }
                        uri.pathSegments.firstOrNull() == "chapter" -> {
                            val parts = uri.pathSegments
                            check(parts.size == 3 && parts[1] == document.entry.book.id && parts[2].endsWith(".xhtml"))
                            val chapterId = parts[2].removeSuffix(".xhtml")
                            val chapter = document.manifest.chapters.find { it.id == chapterId } ?: error("Unknown chapter")
                            val html = runBlocking(Dispatchers.IO) { vm.app.repository.chapterXhtml(document.entry.book.id, chapter) }
                            response(html, "application/xhtml+xml").apply { responseHeaders = CHAPTER_HEADERS }
                        }
                        uri.pathSegments.firstOrNull() == "image" -> {
                            val key = uri.pathSegments.getOrNull(1) ?: error("Unknown image")
                            val image = vm.app.repository.image(key)
                            WebResourceResponse(image.second, null, ByteArrayInputStream(image.first))
                        }
                        uri.path?.startsWith("/assets/") == true -> assets.shouldInterceptRequest(uri) ?: response("", "text/plain", 404)
                        else -> response("", "text/plain", 403)
                    }
                } catch (_: Exception) {
                    if (uri.path?.startsWith("/chapter/") == true) view.post { vm.error.value = "章节加载失败" }
                    response("", "text/plain", 503)
                }
            }
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            vm.error.value = "请更新 WebView"
            return webView
        }
        WebViewCompat.addWebMessageListener(webView, "Native", setOf(ORIGIN)) { _, message, source, mainFrame, _ ->
            if (!mainFrame || source.scheme != "https" || source.host != HOST) return@addWebMessageListener
            val raw = message.data ?: return@addWebMessageListener
            if (raw.length > 1024 * 1024) return@addWebMessageListener
            runCatching {
                val data = JSONObject(raw)
                when (data.optString("type")) {
                    "ready" -> {
                        val rows = data.optJSONArray("toc")
                        vm.toc.value = if (rows == null) emptyList() else (0 until minOf(rows.length(), 20000)).mapNotNull { index ->
                            val row = rows.optJSONObject(index) ?: return@mapNotNull null
                            val href = row.optString("href")
                            if (href.isBlank()) null else ReaderToc(row.optString("label").take(500), href.take(4096))
                        }
                        vm.readerReady()
                    }
                    "loaded" -> vm.error.value = null
                    "relocate" -> vm.record(ReadingProgress(
                        cfi = data.optString("cfi").take(8192), fraction = data.optDouble("fraction", 0.0),
                        chapter = data.optString("chapter").take(500), chapterId = data.optString("chapterId").take(100),
                    ))
                    "tap" -> onTap()
                    "error" -> vm.error.value = if (data.optString("message").contains("WebView")) "请更新 WebView" else "正文加载失败"
                }
            }
        }
        webView.loadUrl("$ORIGIN/assets/reader/index.html")
        return webView
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val ORIGIN = "https://$HOST"
        private val CHAPTER_HEADERS = mapOf(
            "Content-Security-Policy" to "default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src https://$HOST data:; font-src 'none'; frame-src 'none'; connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'",
            "X-Content-Type-Options" to "nosniff",
            "Cache-Control" to "no-store",
        )
        private fun response(text: String, mime: String, status: Int = 200): WebResourceResponse = WebResourceResponse(
            mime, "UTF-8", status, if (status == 200) "OK" else "Blocked", mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
            ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)),
        )
        fun open(context: Context, bookId: String, chapterId: String? = null) {
            context.startActivity(Intent(context, ReaderActivity::class.java).putExtra("bookId", bookId).putExtra("chapterId", chapterId))
        }
    }
}
