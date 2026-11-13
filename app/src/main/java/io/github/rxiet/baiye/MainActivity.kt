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
    private fun BaiyeApp(vm: MainViewModel = viewModel(), community: CommunityViewModel = viewModel()) {
        val catalog by vm.catalog.collectAsStateWithLifecycle()
        val library by vm.library.collectAsStateWithLifecycle()
        val detail by vm.detail.collectAsStateWithLifecycle()
        val account by community.account.collectAsStateWithLifecycle()
        val topics by community.topics.collectAsStateWithLifecycle()
        val thread by community.thread.collectAsStateWithLifecycle()
        var screen by rememberSaveable { mutableStateOf("catalog") }
        var detailParent by rememberSaveable { mutableStateOf("catalog") }
        var settingsParent by rememberSaveable { mutableStateOf("catalog") }
        var accountParent by rememberSaveable { mutableStateOf("settings") }
        val savedPages = rememberSaveableStateHolder()
        val scope = rememberCoroutineScope()
        val backdrop = rememberLayerBackdrop()
        val destinations = remember { listOf(GlassNavigationItem(CatalogIcon, "文库"), GlassNavigationItem(ShelfIcon, "书架")) }
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) { vm.importBook(uri); screen = "shelf" }
        }
        val readerFontImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.importFont(uri)
        }
        val uiFontImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.importFont(uri, forInterface = true)
        }
        val backgroundImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.importBackground(uri)
        }
        val fontTypes = arrayOf("font/ttf", "font/otf", "font/collection", "application/x-font-ttf", "application/vnd.ms-opentype", "application/octet-stream")
        LaunchedEffect(vm) {
            vm.messages.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
        }
        val goBack: () -> Unit = {
            screen = when (screen) { "licenses" -> "settings"; "settings" -> settingsParent; "detail" -> detailParent
                "account" -> accountParent; "comments" -> "detail"; "thread" -> "comments"; else -> "catalog" }
        }
        BackHandler(enabled = screen != "catalog", onBack = goBack)
        val openAccount: () -> Unit = { accountParent = screen; screen = "account"; community.restoreAccount() }
        LaunchedEffect(account.user?.id, screen) {
            if (screen == "account" && account.user != null && accountParent in listOf("comments", "thread")) screen = accountParent
        }
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
                        val direction = if (targetState in listOf("detail", "settings", "licenses", "account", "comments", "thread") || (initialState == "catalog" && targetState == "shelf")) 1 else -1
                        (fadeIn(tween(200)) + slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it / 9 * direction }) togetherWith
                            (fadeOut(tween(140)) + slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { -it / 12 * direction })
                    },
                    label = "pages",
                ) { page ->
                    val pageKey = when (page) { "comments" -> "$page:${topics.bookId}"; "thread" -> "$page:${thread.topic?.id}"; else -> page }
                    savedPages.SaveableStateProvider(pageKey) {
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
                                    onComments = { community.showTopics(state.detail?.book ?: state.book); screen = "comments" },
                                )
                            } ?: LaunchedEffect(Unit) { screen = "catalog" }
                            "settings" -> SettingsScreen(
                                library.settings, { vm.settings(it) }, goBack, { screen = "licenses" },
                                onImportReaderFont = { readerFontImporter.launch(fontTypes) },
                                onImportBackground = { backgroundImporter.launch(arrayOf("image/*")) },
                                appearance = library.appearance, onAppearance = { vm.appearance(it) },
                                onImportUiFont = { uiFontImporter.launch(fontTypes) },
                                onDeleteReaderFont = { vm.deleteReaderFont() }, onDeleteBackground = { vm.deleteBackground(it) },
                                onDeleteUiFont = { vm.deleteUiFont() }, onAccount = openAccount,
                            )
                            "account" -> AccountScreen(account, community::login, { community.logout() }, goBack, community::restoreAccount)
                            "comments" -> if (topics.bookId.isNotBlank()) TopicsScreen(topics, account.user != null,
                                onTopic = { community.showThread(it); screen = "thread" },
                                onMore = { community.loadTopics(more = true) }, onRetry = { community.loadTopics() },
                                onLogin = openAccount, onPost = community::post, onBack = goBack)
                            else LaunchedEffect(Unit) { screen = "catalog" }
                            "thread" -> if (thread.topic != null) ThreadScreen(thread, account.user != null,
                                onMore = { community.loadReplies(more = true) }, onRetry = { community.loadReplies() },
                                onLogin = openAccount, onReply = community::reply, onBack = goBack)
                            else LaunchedEffect(Unit) { screen = if (topics.bookId.isBlank()) "catalog" else "comments" }
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
