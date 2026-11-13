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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import androidx.compose.ui.graphics.toArgb

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
        val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
        val ready by vm.ready.collectAsStateWithLifecycle()
        val fixedLayout by vm.fixedLayout.collectAsStateWithLifecycle()
        var webView by remember { mutableStateOf<WebView?>(null) }
        var panel by remember { mutableStateOf("") }
        var everReady by remember { mutableStateOf(false) }
        var seek by remember { mutableStateOf<Float?>(null) }
        var seeking by remember { mutableStateOf(false) }
        var seekSection by remember { mutableIntStateOf(-1) }
        var curlView by remember { mutableStateOf<PageCurlView?>(null) }
        var turnOverlayActive by remember { mutableStateOf(false) }
        var captureArea by remember { mutableStateOf(android.graphics.Rect()) }
        val importBackground = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { vm.importBackground(it) }
        }
        val importFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { vm.importFont(it) }
        }
        val paper = when (settings.theme) {
            "night" -> Color(0xFF171717)
            "white" -> Color.White
            "green" -> Color(0xFFE3EDDF)
            else -> Color(0xFFF6F1E7)
        }
        val foreground = if (settings.theme == "night") Color(0xFFE4E1D9) else Color(0xFF242820)
        val rawCommand: (JSONObject) -> Unit = { data ->
            webView?.evaluateJavascript("window.Reader?.commands(${data});", null)
        }
        val turn: (Int) -> Unit = turn@{ delta ->
            val native = curlView
            if (native?.isBusy == true) return@turn
            panel = ""
            if (settings.turnAnimation == "none" || settings.scroll || native == null) {
                rawCommand(JSONObject().put("type", "nativeTurn").put("direction", delta))
            } else {
                native.paperColor = paper.toArgb()
                native.capture(window, captureArea, delta, settings.turnAnimation) {
                    rawCommand(JSONObject().put("type", "nativeTurn").put("direction", delta))
                }
            }
        }
        val command: (JSONObject) -> Unit = { data ->
            when (data.optString("type")) {
                "next" -> turn(1)
                "prev" -> turn(-1)
                else -> rawCommand(data)
            }
        }
        // AndroidView.factory runs once: its callbacks must dereference current
        // settings/commands rather than the first composition's turn closure.
        val currentTurn by rememberUpdatedState(turn)
        val currentReaderEvent by rememberUpdatedState<(JSONObject) -> Unit> { event ->
            when (event.optString("type")) {
                "turn" -> currentTurn(if (event.optInt("direction", 1) < 0) -1 else 1)
                "turnEnd" -> if (event.optBoolean("changed", true)) curlView?.turnEnd() else curlView?.clear()
                "interaction", "nativeTurn" -> if (event.optString("source") !in listOf("slider", "toolbar")) {
                    panel = ""
                    seek = null
                    seeking = false
                }
            }
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
            panel = when (panel) {
                "background", "brightness", "turn", "bookmarks" -> "more"
                "main", "" -> ""
                else -> "main"
            }
        }
        LaunchedEffect(progress.page, progress.sectionIndex, seeking) {
            if (!seeking && seek != null && progress.sectionIndex == seekSection && progress.page == seek?.roundToInt()) {
                seek = null
            }
        }
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
        DisposableEffect(Unit) { onDispose { volumeTurn = null; capturedVolumeKeys.clear(); curlView?.clear() } }
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
            Column(Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                captureArea = android.graphics.Rect(bounds.left.roundToInt(), bounds.top.roundToInt(),
                    bounds.right.roundToInt(), bounds.bottom.roundToInt())
            }) {
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
                                    buildWebView(context, vm, doc,
                                        onTap = { panel = if (panel.isEmpty()) "main" else "" },
                                        onReaderEvent = { event -> currentReaderEvent(event) }).also { webView = it }
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
            AndroidView(factory = { context -> PageCurlView(context).also {
                it.onActiveChanged = { active -> turnOverlayActive = active }
                curlView = it
            } }, update = { it.paperColor = paper.toArgb() },
                modifier = Modifier.fillMaxSize().zIndex(if (turnOverlayActive) 1f else -1f))
            if (!turnOverlayActive) {
                val overlayColors = if (settings.theme == "night") darkColorScheme(primary = Accent,
                    background = paper, surface = Color(0xFF242424)) else MiuixTheme.colorScheme
                MiuixTheme(colors = overlayColors) {
                    ReaderOverlay(panel = panel, onPanel = { panel = it }, onBack = backPanel,
                paper = paper, ink = foreground, ready = ready,
                pageSeekEnabled = ready && !settings.scroll && !fixedLayout && progress.pageCount > 1, progress = progress,
                seek = seek, onSeek = { value ->
                    seeking = true
                    seek = value.roundToInt().toFloat()
                    seekSection = progress.sectionIndex
                    command(JSONObject().put("type", "page").put("page", value.roundToInt())
                        .put("sectionIndex", seekSection))
                }, onSeekFinished = { seeking = false },
                onSection = { delta ->
                    seek = null; seeking = false
                    command(JSONObject().put("type", "section").put("delta", delta).put("source", "toolbar"))
                }, secondary = { page ->
                    if (page == "toc") ReaderContents(toc, progress.tocHref, foreground) { href ->
                        command(JSONObject().put("type", "goto").put("href", href)); panel = ""
                    } else if (page == "bookmarks") ReaderBookmarksPanel(bookmarks, progress.cfi, ready,
                        onAdd = { command(JSONObject().put("type", "bookmark")) },
                        onGo = { bookmark ->
                            val target = vm.bookmarkTarget(bookmark)
                            if (target != null) {
                                command(JSONObject().put("type", "goto").put("href", target)
                                    .put("fallbackIndex", vm.bookmarkSectionIndex(bookmark))); panel = ""
                            } else android.widget.Toast.makeText(this@ReaderActivity, "书签对应章节已移除", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onDelete = { vm.removeBookmark(it) })
                    else ReadingPreferences(settings = settings, onChange = { vm.settings(it) }, section = page,
                        onImportFont = { importFont.launch(arrayOf("font/ttf", "font/otf", "application/octet-stream", "application/x-font-ttf", "application/vnd.ms-opentype")) },
                        onImportBackground = { importBackground.launch(arrayOf("image/*")) },
                        onDeleteFont = { vm.deleteFont() }, onDeleteBackground = { vm.deleteBackground(it) })
                })
                }
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
    }

    /** These are siblings of the reading surface, never a dialog or a modal sheet.
     * Only the actual controls hit-test; the rest of the book remains interactive. */
    @Composable
    private fun BoxScope.ReaderOverlay(
        panel: String, onPanel: (String) -> Unit, onBack: () -> Unit,
        paper: Color, ink: Color, ready: Boolean, pageSeekEnabled: Boolean, progress: ReadingProgress,
        seek: Float?, onSeek: (Float) -> Unit, onSeekFinished: () -> Unit,
        onSection: (Int) -> Unit, secondary: @Composable (String) -> Unit,
    ) {
        var retained by remember { mutableStateOf("more") }
        if (panel.isNotEmpty() && panel != "main" && retained != panel) SideEffect { retained = panel }
        AnimatedVisibility(visible = panel.isNotEmpty(), modifier = Modifier.align(Alignment.TopStart),
            enter = fadeIn(tween(120)), exit = fadeOut(tween(100))) {
            IconButton(onClick = { finish() }, modifier = Modifier.padding(8.dp).size(48.dp)
                .background(paper, androidx.compose.foundation.shape.CircleShape)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = ink)
            }
        }
        val secondaryPanel = panel.isNotEmpty() && panel != "main"
        AnimatedVisibility(visible = panel == "main", modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(130)) + slideInVertically(spring(dampingRatio = .86f, stiffness = Spring.StiffnessMedium)) { it / 5 },
            exit = fadeOut(tween(100)) + slideOutVertically(tween(110)) { it / 8 }) {
            Column(Modifier.fillMaxWidth().background(paper,
                androidx.compose.foundation.shape.RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onSection(-1) }, enabled = ready && progress.sectionIndex > 0,
                        modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一章", tint = ink)
                    }
                    Slider(value = (seek ?: progress.page.toFloat()).coerceIn(1f, progress.pageCount.coerceAtLeast(1).toFloat()),
                        onValueChange = onSeek, valueRange = 1f..progress.pageCount.coerceAtLeast(2).toFloat(),
                        enabled = pageSeekEnabled, onValueChangeFinished = onSeekFinished,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                    IconButton(onClick = { onSection(1) },
                        enabled = ready && progress.sectionIndex + 1 < progress.sectionCount,
                        modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "下一章", tint = ink)
                    }
                }
                Row(Modifier.fillMaxWidth().height(88.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("toc" to "目录", "type" to "排版", "font" to "字体", "more" to "更多").forEach { (key, label) ->
                        Column(Modifier.weight(1f).fillMaxHeight().clickable { onPanel(key) }.padding(vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MenuGlyph(key, ink)
                            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ink)
                        }
                    }
                }
            }
        }
        // One 180ms transition only: fast first response, the final ~40ms settles.
        AnimatedVisibility(visible = secondaryPanel, modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(110)) + slideInVertically(tween(150, easing = CubicBezierEasing(.12f, .84f, .22f, 1f))) { it / 14 },
            exit = fadeOut(tween(80)) + slideOutVertically(tween(95)) { it / 18 }) {
            val page = if (secondaryPanel) panel else retained
            val available = LocalConfiguration.current.screenHeightDp.dp
            Column(Modifier.fillMaxWidth().height((available * if (page == "toc" || page == "bookmarks") .55f else .38f).coerceIn(240.dp, 480.dp))
                .background(paper, androidx.compose.foundation.shape.RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .padding(horizontal = 12.dp)) {
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上级", tint = ink)
                    }
                    Text(when (page) {
                        "toc" -> "目录"; "bookmarks" -> "书签"; "type" -> "排版"; "font" -> "字体"; "background" -> "背景"; else -> "更多"
                    }, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Medium, color = ink)
                    IconButton(onClick = { onPanel("") }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭菜单", tint = ink)
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (page == "more") Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("background" to "背景与亮度", "bookmarks" to "书签").forEach { (key, label) ->
                                Button(onClick = { onPanel(key) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                    colors = ButtonDefaults.buttonColors(color = ink.copy(alpha = .06f), contentColor = ink)) {
                                    Text(label, fontSize = 15.sp, maxLines = 1)
                                }
                            }
                        }
                        Box(Modifier.fillMaxWidth().weight(1f)) { secondary("more") }
                    } else secondary(page)
                }
            }
        }
    }

    @Composable
    private fun ReaderContents(toc: List<ReaderToc>, currentHref: String, ink: Color, go: (String) -> Unit) {
        val groups = remember(toc) {
            toc.indices.filter { index -> toc[index].href.isBlank() ||
                (index + 1 < toc.size && toc[index + 1].depth > toc[index].depth) }.toSet()
        }
        val ends = remember(toc) {
            groups.associateWith { start ->
                // Some plain-text manifests use a blank-href volume with flat children.
                val flatVolume = toc[start].href.isBlank() && toc.getOrNull(start + 1)?.depth == toc[start].depth
                ((start + 1) until toc.size).firstOrNull { next ->
                    if (flatVolume) toc[next].href.isBlank() && toc[next].depth <= toc[start].depth
                    else toc[next].depth <= toc[start].depth
                } ?: toc.size
            }
        }
        val current = toc.indexOfFirst { it.href.isNotBlank() && it.href == currentHref }
        var expanded by remember(toc, currentHref) { mutableStateOf(groups.filter { group ->
            current >= group && current < (ends[group] ?: group + 1)
        }.toSet()) }
        val visible = toc.indices.filter { index -> groups.none { group ->
            group < index && index < (ends[group] ?: group + 1) && group !in expanded
        } }
        val list = rememberLazyListState()
        LaunchedEffect(currentHref, toc) {
            val position = visible.indexOf(current)
            if (position >= 0) list.scrollToItem(position)
        }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 12.dp)) {
            items(visible, key = { it }) { index ->
                val item = toc[index]
                val group = index in groups
                val caret by animateFloatAsState(if (index in expanded) .65f else .35f, tween(160), label = "reader-volume")
                Row(Modifier.animateItem(fadeInSpec = tween(120), fadeOutSpec = tween(100), placementSpec = tween(160)).fillMaxWidth().clickable {
                    if (group) expanded = if (index in expanded) expanded - index else expanded + index
                    else if (item.href.isNotBlank()) go(item.href)
                }.heightIn(min = 48.dp)
                    .padding(start = (12 + item.depth.coerceIn(0, 12) * 16).dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(item.label, Modifier.weight(1f), fontSize = if (group) 17.sp else 15.sp,
                        fontWeight = if (group || index == current) FontWeight.Medium else FontWeight.Normal,
                        color = if (index == current) MiuixTheme.colorScheme.primary else ink, lineHeight = 24.sp)
                    if (group) Canvas(Modifier.size(16.dp)) {
                        val y = caret
                        drawLine(ink, Offset(size.width * .2f, size.height * (1f - y)), Offset(size.width * .5f, size.height * y), 1.5.dp.toPx())
                        drawLine(ink, Offset(size.width * .5f, size.height * y), Offset(size.width * .8f, size.height * (1f - y)), 1.5.dp.toPx())
                    }
                }
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
            Text(if (progress.pageCount > 0) "${progress.page.coerceIn(1, progress.pageCount)} / ${progress.pageCount}" else "", 
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
                "type", "font" -> {
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
    private fun buildWebView(context: Context, vm: ReaderViewModel, document: ReaderDocument,
        onTap: () -> Unit, onReaderEvent: (JSONObject) -> Unit): WebView {
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
                        uri.pathSegments.firstOrNull() == "media" -> {
                            val parts = uri.pathSegments
                            check(parts.size == 3)
                            val name = parts[2]
                            check(name.isNotBlank() && name != "." && name != ".." &&
                                name.none { it == '/' || it == '\\' || it == '\u0000' } &&
                                name.matches(Regex("[A-Za-z0-9_.-]+")))
                            val file = when (parts[1]) {
                                "background" -> vm.app.media.backgroundFile(name)
                                "font" -> vm.app.media.fontFile(name)
                                else -> null
                            } ?: return response("", "text/plain", 404)
                            val mime = when (file.extension.lowercase()) {
                                "png" -> "image/png"
                                "jpg", "jpeg" -> "image/jpeg"
                                "webp" -> "image/webp"
                                "gif" -> "image/gif"
                                "avif" -> "image/avif"
                                "ttf" -> "font/ttf"
                                "otf" -> "font/otf"
                                "woff" -> "font/woff"
                                "woff2" -> "font/woff2"
                                else -> "application/octet-stream"
                            }
                            WebResourceResponse(mime, null, file.inputStream()).apply {
                                responseHeaders = mapOf("Cache-Control" to "private, max-age=604800", "X-Content-Type-Options" to "nosniff")
                            }
                        }
                        uri.pathSegments.firstOrNull() == "image" -> {
                            val key = uri.pathSegments.getOrNull(1) ?: error("Unknown image")
                            val image = if (uri.getQueryParameter("retry")?.toLongOrNull() != null) vm.app.repository.retryImage(key)
                                else vm.app.repository.image(key)
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
                    "bookmarkData" -> vm.addBookmark(data.optString("excerpt"), data.optString("cfi"))
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
                    "turn", "turnEnd", "interaction", "nativeTurn" -> onReaderEvent(data)
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
            "Content-Security-Policy" to "default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src 'self' https://$HOST data:; font-src 'self' https://$HOST; frame-src 'none'; connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'",
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
