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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rxiet.baiye.reader.ReaderActivity
import io.github.rxiet.baiye.ui.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.*

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
        val scope = rememberCoroutineScope()
        val backdrop = rememberLayerBackdrop()
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
        val settings: () -> Unit = { settingsParent = screen; screen = "settings" }
        val rootScreen = screen == "catalog" || screen == "shelf"
        Scaffold(containerColor = Color.White) { insets ->
            Box(Modifier.fillMaxSize().padding(insets)) {
                Box(
                    Modifier.fillMaxSize().layerBackdrop(backdrop)
                        .padding(bottom = if (rootScreen) 88.dp else 0.dp),
                ) {
                    when (screen) {
                        "catalog" -> CatalogScreen(
                            state = catalog, onSearch = vm::search, onOrder = vm::order,
                            onBook = { vm.showBook(it); detailParent = "catalog"; screen = "detail" },
                            onMore = { vm.loadCatalog(more = true) },
                            onRetry = { vm.loadCatalog(more = catalog.books.isNotEmpty()) },
                            onSettings = settings,
                        )
                        "shelf" -> ShelfScreen(
                            entries = library.entries,
                            onBook = { ReaderActivity.open(this@MainActivity, it.book.id) },
                            onImport = { importer.launch(arrayOf("application/epub+zip", "application/octet-stream")) },
                            onSettings = settings,
                            onBrowse = { screen = "catalog" },
                            onRemove = vm::remove,
                        )
                        "detail" -> detail?.let { state ->
                            DetailScreen(
                                state = state,
                                favorite = library.entries.any { it.book.id == state.book.id && it.favorite },
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
                        "settings" -> SettingsScreen(
                            settings = library.settings, onChange = { vm.settings(it) },
                            onBack = goBack, onLicenses = { screen = "licenses" },
                        )
                        "licenses" -> LicensesScreen(onBack = goBack)
                    }
                }
                if (rootScreen) {
                    Row(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                            .widthIn(max = 300.dp).fillMaxWidth(0.78f)
                            .glassPanel(
                                backdrop = backdrop,
                                shape = GlassShape(28.dp),
                                material = GlassMaterials.puredThinGlass(isDark = false),
                                stroke = GlassStrokes.forTheme(isDark = false),
                                shadow = GlassShadows.Float,
                                shading = false,
                                fallback = Modifier.clip(RoundedCornerShape(28.dp)).background(Soft),
                            ).padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf("catalog" to "文库", "shelf" to "书架").forEach { (destination, label) ->
                            Button(
                                onClick = { screen = destination },
                                modifier = Modifier.weight(1f).semantics { role = Role.Tab; selected = screen == destination },
                                minHeight = 48.dp, cornerRadius = 24.dp,
                                colors = ButtonDefaults.buttonColors(
                                    color = if (screen == destination) Ink else Color.Transparent,
                                    contentColor = if (screen == destination) Color.White else Ink,
                                ),
                            ) { Text(label, fontSize = 18.sp) }
                        }
                    }
                }
            }
        }
    }
}
