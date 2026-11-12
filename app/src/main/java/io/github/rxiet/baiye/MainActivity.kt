package io.github.rxiet.baiye

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rxiet.baiye.reader.ReaderActivity
import io.github.rxiet.baiye.ui.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassNavigationBar
import top.yukonga.miuix.kmp.glass.GlassNavigationItem

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        setContent { BaiyeTheme { BaiyeApp() } }
    }

    @Composable
    private fun BaiyeApp(vm: MainViewModel = viewModel()) {
        val catalog by vm.catalog.collectAsStateWithLifecycle()
        val library by vm.library.collectAsStateWithLifecycle()
        val detail by vm.detail.collectAsStateWithLifecycle()
        var screen by rememberSaveable { mutableStateOf("catalog") }
        var detailParent by rememberSaveable { mutableStateOf("catalog") }
        var settingsParent by rememberSaveable { mutableStateOf("catalog") }
        val savedPages = rememberSaveableStateHolder()
        val scope = rememberCoroutineScope()
        val backdrop = rememberLayerBackdrop()
        val destinations = remember { listOf(GlassNavigationItem(CatalogIcon, "文库"), GlassNavigationItem(ShelfIcon, "书架")) }
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) { vm.importBook(uri); screen = "shelf" }
        }
        LaunchedEffect(vm) {
            vm.messages.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
        }
        val goBack: () -> Unit = {
            screen = when (screen) { "licenses" -> "settings"; "settings" -> settingsParent; "detail" -> detailParent; else -> "catalog" }
        }
        BackHandler(enabled = screen != "catalog", onBack = goBack)
        val openSettings: () -> Unit = { settingsParent = screen; screen = "settings" }
        val rootScreen = screen == "catalog" || screen == "shelf"
        var lastRootTab by rememberSaveable { mutableIntStateOf(0) }
        val rootIndex = when (screen) { "catalog" -> 0; "shelf" -> 1; else -> lastRootTab }
        SideEffect { if (rootScreen) lastRootTab = rootIndex }
        Scaffold(containerColor = Color.White) { insets ->
            Box(Modifier.fillMaxSize().padding(insets)) {
                AnimatedContent(
                    targetState = screen,
                    modifier = Modifier.fillMaxSize().layerBackdrop(backdrop),
                    transitionSpec = {
                        val direction = if (targetState in listOf("detail", "settings", "licenses") || (initialState == "catalog" && targetState == "shelf")) 1 else -1
                        (fadeIn(tween(200)) + slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it / 9 * direction }) togetherWith
                            (fadeOut(tween(140)) + slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { -it / 12 * direction })
                    },
                    label = "pages",
                ) { page ->
                    savedPages.SaveableStateProvider(page) {
                        when (page) {
                            "catalog" -> CatalogScreen(
                                state = catalog, onSearch = vm::search, onOrder = vm::order,
                                onBook = { vm.showBook(it); detailParent = "catalog"; screen = "detail" },
                                onMore = { vm.loadCatalog(more = true) },
                                onRetry = { vm.loadCatalog(more = catalog.books.isNotEmpty()) },
                                onRefresh = { vm.loadCatalog() }, onSettings = openSettings,
                            )
                            "shelf" -> ShelfScreen(
                                entries = library.entries,
                                onBook = { ReaderActivity.open(this@MainActivity, it.book.id) },
                                onImport = { importer.launch(arrayOf("application/epub+zip", "application/octet-stream")) },
                                onSettings = openSettings, onBrowse = { screen = "catalog" },
                                onRemove = vm::remove, onFavorite = { vm.toggleFavorite(it) },
                                onDetails = { vm.showBook(it.book); detailParent = "shelf"; screen = "detail" },
                            )
                            "detail" -> detail?.let { state ->
                                val entry = library.entries.find { it.book.id == state.book.id }
                                DetailScreen(
                                    state = state, favorite = entry?.favorite == true, progress = entry?.progress,
                                    onBack = goBack, onFavorite = { vm.favorite() },
                                    onRead = { chapter ->
                                        scope.launch {
                                            val book = state.detail?.book ?: state.book
                                            if (vm.prepareRead(book)) ReaderActivity.open(this@MainActivity, book.id, chapter?.id)
                                        }
                                    },
                                    onRetry = { vm.showBook(state.book) },
                                    onDownload = vm::download, onCancelDownload = vm::cancelDownload,
                                )
                            } ?: LaunchedEffect(Unit) { screen = "catalog" }
                            "settings" -> SettingsScreen(library.settings, { vm.settings(it) }, goBack, { screen = "licenses" })
                            "licenses" -> LicensesScreen(onBack = goBack)
                        }
                    }
                }
                GlassNavigationBar(
                    items = destinations, selectedIndex = rootIndex,
                    onSelect = { if (rootScreen) screen = if (it == 0) "catalog" else "shelf" },
                    backdrop = backdrop, visible = rootScreen, defaultWindowInsetsPadding = false,
                    modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 420.dp)
                        .fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp),
                )
            }
        }
    }
}
