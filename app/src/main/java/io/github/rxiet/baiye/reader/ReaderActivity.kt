package io.github.rxiet.baiye.reader

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color as AndroidColor
import android.os.BatteryManager
import android.os.Bundle
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
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
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import kotlin.math.roundToInt

class ReaderActivity : ComponentActivity() {
    private var readerModel: ReaderViewModel? = null
    private var volumeTurn: ((Int) -> Unit)? = null
    private var lastVolumeTurn = 0L
    private var rendererLost = false
    private val capturedVolumeKeys = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT))
        setContent { BaiyeTheme { Reader() } }
    }

    override fun onPause() { readerModel?.flushProgress(); super.onPause() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_UP && capturedVolumeKeys.remove(event.keyCode)) return true
            val turn = volumeTurn
            if (event.action == KeyEvent.ACTION_DOWN && turn != null) {
                capturedVolumeKeys.add(event.keyCode)
                val now = SystemClock.uptimeMillis()
                if (event.repeatCount == 0 || now - lastVolumeTurn >= 320) {
                    lastVolumeTurn = now
                    turn(if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) -1 else 1)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Reader(vm: ReaderViewModel = viewModel()) {
        readerModel = vm
        val document by vm.document.collectAsStateWithLifecycle()
        val error by vm.error.collectAsStateWithLifecycle()
        val settings by vm.readerSettings.collectAsStateWithLifecycle()
        val toc by vm.toc.collectAsStateWithLifecycle()
        val progress by vm.progress.collectAsStateWithLifecycle()
        val ready by vm.ready.collectAsStateWithLifecycle()
        val fixedLayout by vm.fixedLayout.collectAsStateWithLifecycle()
        var webView by remember { mutableStateOf<WebView?>(null) }
        var panel by remember { mutableStateOf("") }
        var sheetPage by remember { mutableStateOf("main") }
        var everReady by remember { mutableStateOf(false) }
        var seek by remember { mutableStateOf<Float?>(null) }
        val paper = when (settings.theme) {
            "night" -> Color(0xFF171717)
            "white" -> Color.White
            "green" -> Color(0xFFE3EDDF)
            else -> Color(0xFFF6F1E7)
        }
        val foreground = if (settings.theme == "night") Color(0xFFE4E1D9) else Color(0xFF242820)
        val command: (JSONObject) -> Unit = { data ->
            webView?.evaluateJavascript("window.Reader?.commands(${data});", null)
        }
        val retry: () -> Unit = {
            val needsNewWebView = rendererLost || webView == null || error == "请更新 WebView"
            vm.error.value = null
            if (document == null) vm.load()
            else if (needsNewWebView) recreate()
            else if (!everReady) webView?.reload()
            else command(JSONObject().put("type", "retry"))
        }
        val backPanel: () -> Unit = {
            if (panel != "main" && panel.isNotEmpty()) panel = "main" else panel = ""
        }
        // Keep the last page during the sheet's closing animation.
        LaunchedEffect(panel) { if (panel.isNotEmpty()) sheetPage = panel }
        LaunchedEffect(ready) { if (ready) everReady = true }
        LaunchedEffect(settings.keepScreenOn, settings.brightness) {
            if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val attributes = window.attributes
            attributes.screenBrightness = if (settings.brightness < 0) -1f else settings.brightness.coerceIn(0.01f, 1f)
            window.attributes = attributes
        }
        LaunchedEffect(settings.immersive, settings.theme, panel.isNotEmpty()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightStatusBars = settings.theme != "night"
            controller.isAppearanceLightNavigationBars = settings.theme != "night"
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (settings.immersive && panel.isEmpty()) controller.hide(WindowInsetsCompat.Type.systemBars())
            else controller.show(WindowInsetsCompat.Type.systemBars())
        }
        LaunchedEffect(settings, ready) {
            if (ready) command(JSONObject().put("type", "settings").put("settings", JSONObject(ReaderJson.encodeToString(settings))))
        }
        SideEffect {
            volumeTurn = if (settings.volumeKeys && panel.isEmpty() && ready) {
                { delta -> command(JSONObject().put("type", if (delta < 0) "prev" else "next")) }
            } else null
        }
        DisposableEffect(Unit) { onDispose { volumeTurn = null; capturedVolumeKeys.clear() } }
        DisposableEffect(webView) {
            val current = webView
            onDispose {
                vm.flushProgress()
                if (current != null) {
                    runCatching {
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(current, "Native")
                        current.stopLoading()
                        current.destroy()
                    }
                }
            }
        }
        BackHandler(enabled = panel.isNotEmpty(), onBack = backPanel)
        val readingError = error
        // Fixed safe insets prevent opening the sheet/system bars from repaginating
        // the book. Broadcast time/battery updates live only in ReadingStatus.
        Box(Modifier.fillMaxSize().background(paper)
            .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout))) {
            Column(Modifier.fillMaxSize()) {
                if (settings.showReadingStatus) {
                    Text(progress.chapter.ifBlank { document?.entry?.book?.title.orEmpty() },
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                        fontSize = 14.sp, fontWeight = FontWeight.Medium, color = foreground,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    val doc = document
                    if (doc != null) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                try {
                                    buildWebView(context, vm, doc) { panel = if (panel.isEmpty()) "main" else "" }
                                        .also { webView = it }
                                } catch (failure: Exception) {
                                    android.util.Log.w("BaiyeReader", "WebView initialization failed", failure)
                                    vm.error.value = "请更新 WebView"
                                    android.widget.FrameLayout(context)
                                }
                            },
                        )
                    } else if (error == null) {
                        Text("加载中", Modifier.align(Alignment.Center), fontSize = 18.sp, color = foreground)
                    }
                }
                if (settings.showReadingStatus) ReadingStatus(progress, settings.scroll, foreground, fixedLayout)
            }
            if (everReady && readingError != null) {
                Row(Modifier.align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = if (settings.showReadingStatus) 48.dp else 12.dp)
                    .fillMaxWidth().background(paper.copy(alpha = .98f), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(readingError, Modifier.weight(1f), fontSize = 14.sp, color = foreground,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("重试", Modifier.clickable(onClick = retry).heightIn(min = 48.dp).padding(14.dp),
                        fontSize = 16.sp, fontWeight = FontWeight.Medium, color = foreground)
                    IconButton(onClick = { vm.error.value = null }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭提示", tint = foreground)
                    }
                }
            }
            // Persistence failures never replace the reading scene. Only first-open
            // failures get a blocking retry UI; later chapter faults get a small bar.
            if (readingError != null) {
                if (!everReady) {
                    Column(Modifier.align(Alignment.Center).fillMaxWidth().background(paper).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(readingError, fontSize = 18.sp, color = foreground)
                        ActionButton("重试", retry)
                        ActionButton("返回", { finish() })
                    }
                }
            }
        }
        NativeMiuixSheet(show = panel.isNotEmpty(), secondary = sheetPage != "main", onBack = backPanel,
            onDismiss = { panel = "" }, dark = settings.theme == "night") {
            val sheetInk = MiuixTheme.colorScheme.onSurface
            val height = LocalConfiguration.current.screenHeightDp.dp
            Column(Modifier.fillMaxWidth().animateContentSize(tween(220))) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (sheetPage == "main") finish() else panel = "main" }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (sheetPage == "main") "返回" else "返回阅读菜单", tint = sheetInk)
                    }
                    Text(when (sheetPage) {
                        "toc" -> "目录"; "type" -> "排版"; "background" -> "背景"; "more" -> "更多"
                        else -> document?.entry?.book?.title.orEmpty()
                    }, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        color = sheetInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { panel = "" }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭菜单", tint = sheetInk)
                    }
                }
                AnimatedContent(targetState = sheetPage, label = "reader-panel", transitionSpec = {
                    (fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 8 }) togetherWith
                        (fadeOut(tween(120)) + slideOutHorizontally(tween(180)) { -it / 12 })
                }) { page ->
                    when (page) {
                        "toc" -> {
                            val list = rememberLazyListState()
                            LaunchedEffect(toc, progress.tocHref) {
                                val index = toc.indexOfFirst { it.href.isNotBlank() && it.href == progress.tocHref }
                                if (index >= 0) list.scrollToItem(index)
                            }
                            LazyColumn(Modifier.fillMaxWidth().height((height * .8f - 120.dp).coerceAtLeast(160.dp)),
                                state = list, contentPadding = PaddingValues(vertical = 8.dp)) {
                                items(toc) { item ->
                                    val group = item.href.isBlank()
                                    val current = !group && item.href == progress.tocHref
                                    Text(item.label,
                                        Modifier.fillMaxWidth()
                                            .then(if (!group) Modifier.clickable {
                                                command(JSONObject().put("type", "goto").put("href", item.href))
                                                panel = ""
                                            } else Modifier)
                                            .heightIn(min = 48.dp)
                                            .padding(start = (12 + item.depth.coerceIn(0, 12) * 16).dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                                        fontSize = if (group) 20.sp else 17.sp,
                                        fontWeight = if (group) FontWeight.SemiBold else if (current) FontWeight.Medium else FontWeight.Normal,
                                        color = if (current) MiuixTheme.colorScheme.primary else sheetInk,
                                        lineHeight = 26.sp)
                                }
                            }
                        }
                        "type", "background", "more" -> Box(Modifier.fillMaxWidth()
                            .height((height * .72f - 120.dp).coerceAtLeast(160.dp))) {
                            ReadingPreferences(settings = settings, onChange = { vm.settings(it) }, section = page)
                        }
                        else -> Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                            Slider(value = seek ?: progress.fraction.toFloat().coerceIn(0f, 1f),
                                onValueChange = { seek = it }, valueRange = 0f..1f, enabled = ready,
                                onValueChangeFinished = {
                                    seek?.let { command(JSONObject().put("type", "fraction").put("fraction", it.toDouble())) }
                                    seek = null
                                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { command(JSONObject().put("type", "section").put("delta", -1)) },
                                    enabled = ready && progress.sectionIndex > 0, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一章", tint = sheetInk)
                                }
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(progress.chapter.ifBlank { "阅读进度" }, fontSize = 16.sp, color = sheetInk,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${((seek?.toDouble() ?: progress.fraction) * 100).roundToInt()}%",
                                        fontSize = 14.sp, color = sheetInk)
                                }
                                IconButton(onClick = { command(JSONObject().put("type", "section").put("delta", 1)) },
                                    enabled = ready && progress.sectionIndex + 1 < progress.sectionCount, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "下一章", tint = sheetInk)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                listOf("toc" to "目录", "type" to "排版", "background" to "背景", "more" to "更多").forEach { (key, label) ->
                                    Column(Modifier.weight(1f).sizeIn(minWidth = 64.dp, minHeight = 76.dp).clickable { panel = key }.padding(vertical = 12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        MenuGlyph(key, sheetInk)
                                        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = sheetInk)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun NativeMiuixSheet(show: Boolean, secondary: Boolean, onBack: () -> Unit,
        onDismiss: () -> Unit, dark: Boolean, content: @Composable () -> Unit) {
        val colors = if (dark) darkColorScheme(background = Color(0xFF171717), surface = Color(0xFF242424))
            else MiuixTheme.colorScheme
        MiuixTheme(colors = colors) {
            WindowBottomSheet(show = show, onDismissRequest = onDismiss, allowDismiss = true,
                insideMargin = DpSize(16.dp, 0.dp)) {
                PredictiveBackHandler(enabled = show && secondary) { events ->
                    events.collect()
                    onBack()
                }
                Column(Modifier.navigationBarsPadding()) { content() }
            }
        }
    }

    @Composable
    private fun ReadingStatus(progress: ReadingProgress, scroll: Boolean, ink: Color, fixedLayout: Boolean) {
        var time by remember { mutableStateOf(DateFormat.getTimeFormat(this).format(Date())) }
        var battery by remember { mutableIntStateOf(-1) }
        var charging by remember { mutableStateOf(false) }
        DisposableEffect(Unit) {
            fun batteryChanged(intent: Intent?) {
                if (intent == null) return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                battery = if (level >= 0 && scale > 0) (level * 100f / scale).roundToInt().coerceIn(0, 100) else -1
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_BATTERY_CHANGED) batteryChanged(intent)
                    else time = DateFormat.getTimeFormat(this@ReaderActivity).format(Date())
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            batteryChanged(registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED))
            onDispose { unregisterReceiver(receiver) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(time, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = ink)
            Text(if (scroll && !fixedLayout) "${(progress.fraction * 100).roundToInt()}%"
                else if (progress.pageCount > 0) "${if (fixedLayout) "" else "本章 "}${progress.page.coerceIn(1, progress.pageCount)} / ${progress.pageCount}" else "",
                fontSize = 14.sp, fontWeight = FontWeight.Medium, color = ink)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(24.dp, 14.dp).semantics { contentDescription = if (charging) "正在充电" else "电量" }) {
                    val stroke = 1.2.dp.toPx()
                    drawRoundRect(ink, Offset(stroke, stroke), Size(size.width - stroke * 4, size.height - stroke * 2),
                        CornerRadius(2.dp.toPx()), style = Stroke(stroke))
                    drawRect(ink, Offset(size.width - stroke * 2, size.height * .3f), Size(stroke * 2, size.height * .4f))
                    if (battery >= 0) drawRect(ink, Offset(stroke * 2.5f, stroke * 2.5f),
                        Size((size.width - stroke * 7) * battery / 100f, size.height - stroke * 5))
                    if (charging) {
                        val bolt = Path().apply {
                            moveTo(size.width * .53f, size.height * .12f)
                            lineTo(size.width * .33f, size.height * .54f)
                            lineTo(size.width * .46f, size.height * .54f)
                            lineTo(size.width * .39f, size.height * .88f)
                            lineTo(size.width * .62f, size.height * .42f)
                            lineTo(size.width * .49f, size.height * .42f)
                            close()
                        }
                        drawPath(bolt, if (ink.red > .5f) Color.Black else Color.White)
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text(if (battery >= 0) "$battery%" else "—", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = ink)
            }
        }
    }

    @Composable
    private fun MenuGlyph(kind: String, ink: Color) {
        Canvas(Modifier.size(24.dp)) {
            val w = size.width
            val h = size.height
            val stroke = 1.8.dp.toPx()
            when (kind) {
                "toc" -> repeat(3) { n ->
                    val y = h * (.25f + n * .25f)
                    drawCircle(ink, stroke / 2, Offset(w * .12f, y))
                    drawLine(ink, Offset(w * .3f, y), Offset(w * .92f, y), stroke)
                }
                "type" -> {
                    drawLine(ink, Offset(w * .15f, h * .2f), Offset(w * .85f, h * .2f), stroke)
                    drawLine(ink, Offset(w * .5f, h * .2f), Offset(w * .5f, h * .85f), stroke)
                    drawLine(ink, Offset(w * .3f, h * .85f), Offset(w * .7f, h * .85f), stroke)
                }
                "background" -> {
                    drawCircle(ink, w * .36f, style = Stroke(stroke))
                    drawCircle(ink.copy(alpha = .35f), w * .24f, Offset(w * .53f, h * .53f))
                }
                else -> repeat(3) { drawCircle(ink, stroke, Offset(w * (.2f + it * .3f), h * .5f)) }
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
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { vm.startingSession() }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                rendererLost = true
                vm.flushProgress()
                vm.ready.value = false
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                vm.error.value = "阅读器需要重新载入"
                return true
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Foliate owns same-origin blob subframes, never top-level navigation.
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
                            WebResourceResponse(image.second, null, ByteArrayInputStream(image.first)).apply {
                                responseHeaders = mapOf("Cache-Control" to "private, max-age=604800", "X-Content-Type-Options" to "nosniff")
                            }
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
                        vm.fixedLayout.value = data.optBoolean("fixed", false)
                        val rows = data.optJSONArray("toc")
                        vm.toc.value = if (rows == null) emptyList() else (0 until minOf(rows.length(), 20000)).mapNotNull { index ->
                            val row = rows.optJSONObject(index) ?: return@mapNotNull null
                            val label = row.optString("label").take(500)
                            val href = row.optString("href").take(4096)
                            if (label.isBlank() && href.isBlank()) null
                            else ReaderToc(label, href, row.optInt("depth", 0).coerceIn(0, 32))
                        }
                        vm.readerReady()
                    }
                    "loaded" -> vm.error.value = null
                    "relocate" -> {
                        val sectionCount = data.optInt("sectionCount", 0).coerceIn(0, 100000)
                        val pageCount = data.optInt("pageCount", 0).coerceIn(0, 1000000)
                        vm.record(ReadingProgress(
                            cfi = data.optString("cfi").take(8192), fraction = data.optDouble("fraction", 0.0).coerceIn(0.0, 1.0),
                            chapter = data.optString("chapter").take(500), chapterId = data.optString("chapterId").take(100),
                            tocHref = data.optString("tocHref").take(4096),
                            sectionIndex = data.optInt("sectionIndex", 0).coerceIn(0, (sectionCount - 1).coerceAtLeast(0)),
                            sectionCount = sectionCount,
                            page = if (pageCount > 0) data.optInt("page", 1).coerceIn(1, pageCount) else 0,
                            pageCount = pageCount,
                        ))
                    }
                    "tap" -> onTap()
                    "error" -> {
                        if (BuildConfig.DEBUG) android.util.Log.w("BaiyeReader", data.optString("message").take(500))
                        vm.error.value = if (data.optString("message").contains("WebView")) "请更新 WebView"
                            else if (vm.ready.value) "章节暂时无法加载" else "无法打开书籍"
                    }
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
