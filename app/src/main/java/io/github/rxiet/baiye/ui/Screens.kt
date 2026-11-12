package io.github.rxiet.baiye.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.material.icons.filled.ExpandMore
import io.github.rxiet.baiye.data.AppAppearance
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.rxiet.baiye.BaiyeApplication
import androidx.compose.material.icons.filled.Refresh
import io.github.rxiet.baiye.data.ReadingProgress
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.rxiet.baiye.data.Book
import io.github.rxiet.baiye.data.CatalogOrder
import io.github.rxiet.baiye.data.CatalogState
import io.github.rxiet.baiye.data.Chapter
import io.github.rxiet.baiye.data.DetailState
import io.github.rxiet.baiye.data.ReaderSettings
import io.github.rxiet.baiye.data.ShelfEntry
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField

@Composable
fun CatalogScreen(
    state: CatalogState,
    onSearch: (String) -> Unit,
    onOrder: (CatalogOrder) -> Unit,
    onBook: (Book) -> Unit,
    onMore: () -> Unit,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit = {},
) {
    var search by rememberSaveable(state.query) { mutableStateOf(state.query) }
    val gridState = rememberSaveable(state.order, state.query, saver = LazyGridState.Saver) { LazyGridState() }
    val currentState by rememberUpdatedState(state)
    val currentOnMore by rememberUpdatedState(onMore)
    val pageFade = remember { Animatable(1f) }
    var previousPage by remember { mutableStateOf(state.order to state.query) }
    LaunchedEffect(state.order, state.query) {
        val page = state.order to state.query
        if (previousPage != page) {
            previousPage = page
            pageFade.animateTo(0f, tween(70))
            pageFade.animateTo(1f, tween(90))
        }
    }
    val scope = rememberCoroutineScope()
    var refreshVersion by remember(state.order, state.query) { mutableStateOf(0) }
    LaunchedEffect(gridState, refreshVersion) {
        var requested: Pair<Int, String?>? = null
        snapshotFlow {
            val latest = currentState
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val nearEnd = last >= (latest.books.size - 6).coerceAtLeast(0)
            Triple(nearEnd, latest, gridState.layoutInfo.totalItemsCount)
        }.collect { (nearEnd, latest, _) ->
            val token = latest.books.size to latest.books.lastOrNull()?.id
            if (nearEnd && !latest.loading && latest.hasMore && latest.error == null &&
                latest.books.isNotEmpty() && requested != token
            ) {
                requested = token
                currentOnMore()
            }
        }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        keyboard?.hide()
        onSearch(search.trim())
    }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        MainHeading("文库") {
            IconButton(
                onClick = {
                    scope.launch {
                        gridState.animateScrollToItem(0)
                        onRefresh()
                        refreshVersion += 1
                    }
                },
                modifier = Modifier.size(48.dp),
            ) { Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = Ink) }
            SettingsAction(onSettings)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.weight(1f),
                label = "搜索",
                useLabelAsPlaceholder = true,
                singleLine = true,
                textStyle = TextStyle(fontSize = 17.sp, color = Ink, fontFamily = LocalAppFont.current),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                trailingIcon = {
                    IconButton(onClick = submit, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Search, contentDescription = "搜索", tint = Ink)
                    }
                },
            )
        }
        ChoiceRow(
            labels = CatalogOrder.entries.map { it.label },
            selectedIndex = CatalogOrder.entries.indexOf(state.order),
            onSelect = { onOrder(CatalogOrder.entries[it]) },
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(105.dp),
            state = gridState,
            modifier = Modifier.weight(1f).graphicsLayer {
                alpha = pageFade.value
                translationX = (1f - pageFade.value) * 20.dp.toPx()
            },
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 112.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(state.books, key = { it.id }) { book ->
                BookTile(book = book, onClick = { onBook(book) }, modifier = Modifier.animateItem(
                    fadeInSpec = tween(180), placementSpec = tween(220), fadeOutSpec = tween(120),
                ))
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (state.loading) {
                        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(size = 24.dp)
                        }
                    }
                    if (state.error != null) FailureState("连接失败", onRetry)
                    if (state.books.isEmpty() && !state.loading && state.error == null) {
                        StatusLabel("暂无书籍")
                        ActionButton("重试", onRetry)
                    }
                }
            }
        }
    }
}

@Composable
fun ShelfScreen(
    entries: List<ShelfEntry>,
    onBook: (ShelfEntry) -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
    onBrowse: () -> Unit,
    onRemove: (ShelfEntry) -> Unit,
    onDetails: (ShelfEntry) -> Unit = {},
    onFavorite: (ShelfEntry) -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable(selectedId) { mutableStateOf(false) }
    val favoritesGrid = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    val recentGrid = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    val visible = remember(entries, tab) {
        if (tab == 0) entries.filter { it.favorite }.sortedByDescending { maxOf(it.lastReadAt, it.addedAt) }
        else entries.filter { it.lastReadAt > 0 }.sortedByDescending { it.lastReadAt }
    }
    val selectedEntry = entries.firstOrNull { it.book.id == selectedId }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        MainHeading("书架") {
            PlainTextButton("导入", onImport)
            SettingsAction(onSettings)
        }
        ChoiceRow(
            labels = listOf("收藏", "最近"),
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        if (visible.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(if (tab == 0) "书架为空" else "暂无记录", fontSize = 22.sp, color = Ink)
                Spacer(Modifier.height(24.dp))
                ActionButton("导入", onImport, Modifier.fillMaxWidth(), primary = true)
                Spacer(Modifier.height(12.dp))
                ActionButton("前往文库", onBrowse, Modifier.fillMaxWidth())
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(105.dp),
                state = if (tab == 0) favoritesGrid else recentGrid,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 112.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                items(visible, key = { it.book.id }) { entry ->
                    BookTile(
                        book = entry.book,
                        onClick = { onBook(entry) },
                        onLongClick = { selectedId = entry.book.id },
                        progress = entry.progress.takeIf { entry.lastReadAt > 0 },
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(180), placementSpec = tween(220), fadeOutSpec = tween(120),
                        ),
                    )
                }
            }
        }
    }
    if (selectedEntry != null) {
        Dialog(onDismissRequest = { selectedId = null }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(Color.White).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(if (confirmDelete) "删除书籍" else selectedEntry.book.title,
                    fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                if (confirmDelete) {
                    Text(selectedEntry.book.title, fontSize = 17.sp, color = Ink)
                    TextButton(
                        text = "删除书籍",
                        onClick = { selectedId = null; onRemove(selectedEntry) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(color = Color(0xFFFCEBE9), textColor = Color(0xFFB3261E)),
                    )
                    PlainTextButton("取消", { confirmDelete = false }, Modifier.fillMaxWidth())
                } else {
                    ActionButton("阅读", { selectedId = null; onBook(selectedEntry) },
                        Modifier.fillMaxWidth(), primary = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!selectedEntry.book.local) {
                            PlainTextButton("书籍", { selectedId = null; onDetails(selectedEntry) }, Modifier.weight(1f))
                        }
                        PlainTextButton(if (selectedEntry.favorite) "移出书架" else "加入书架",
                            { selectedId = null; onFavorite(selectedEntry) }, Modifier.weight(1f))
                    }
                    Rule()
                    TextButton(
                        text = "删除书籍",
                        onClick = { confirmDelete = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(color = Color.Transparent, textColor = Color(0xFFB3261E)),
                    )
                    PlainTextButton("取消", { selectedId = null }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
fun DetailScreen(
    state: DetailState,
    favorite: Boolean,
    onBack: () -> Unit,
    onFavorite: () -> Unit,
    onRead: (Chapter?) -> Unit,
    onRetry: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    progress: ReadingProgress? = null,
) {
    val detail = state.detail
    val book = detail?.book ?: state.book
    var expanded by rememberSaveable(book.id) { mutableStateOf(false) }
    val volumes = remember(detail) { detail?.chapters?.groupBy { it.volume }.orEmpty() }
    val currentVolume = detail?.chapters?.firstOrNull { it.id == progress?.chapterId }?.volume
    var expandedVolumes by rememberSaveable(book.id) { mutableStateOf(arrayListOf<String>()) }
    var volumesInitialized by rememberSaveable(book.id) { mutableStateOf(false) }
    LaunchedEffect(detail != null, currentVolume) {
        if (!volumesInitialized && detail != null) {
            expandedVolumes = arrayListOf<String>().apply { currentVolume?.let { add(it) } }
            volumesInitialized = true
        }
    }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Header("书籍", onBack)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    BookCover(book, Modifier.width(112.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(book.title, fontSize = 26.sp, lineHeight = 34.sp, color = Ink,
                            fontWeight = FontWeight.SemiBold)
                        if (book.author.isNotBlank()) Text(book.author, fontSize = 16.sp,
                            fontWeight = FontWeight.Medium, color = Ink)
                        if (book.status.isNotBlank()) Text(book.status, fontSize = 14.sp, color = Muted)
                    }
                }
            }
            if (detail == null) {
                item {
                    when {
                        state.error != null -> FailureState("连接失败", onRetry)
                        state.loading -> StatusLabel("加载中")
                        else -> FailureState("暂无书籍", onRetry)
                    }
                }
            } else {
                if (state.error != null) {
                    item { FailureState("连接失败", onRetry) }
                }
                if (book.description.isNotBlank()) {
                    item {
                        Column(Modifier.animateContentSize(animationSpec = tween(220))) {
                            Rule()
                            Spacer(Modifier.height(20.dp))
                            Text(
                                book.description,
                                fontSize = 17.sp,
                                lineHeight = 28.sp,
                                color = Ink,
                                maxLines = if (expanded) Int.MAX_VALUE else 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(12.dp)).background(Soft)
                                    .clickable(role = Role.Button) { expanded = !expanded }
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(if (expanded) "收起简介" else "展开简介", fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium, color = Ink)
                                val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(160), label = "intro")
                                Icon(Icons.Default.ExpandMore, contentDescription = null,
                                    modifier = Modifier.graphicsLayer { rotationZ = rotation }, tint = Muted)
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (detail.chapters.isNotEmpty()) {
                            ActionButton(if (!progress?.cfi.isNullOrBlank()) "继续阅读" else "阅读",
                                { onRead(null) }, Modifier.fillMaxWidth(), primary = true)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ActionButton(
                                if (favorite) "取消收藏" else "收藏", onFavorite, Modifier.weight(1f),
                            )
                            ActionButton(
                                if (state.downloading) "取消缓存" else "缓存",
                                if (state.downloading) onCancelDownload else onDownload,
                                Modifier.weight(1f),
                                enabled = state.downloading || detail.chapters.isNotEmpty(),
                            )
                        }
                        if (state.downloading || state.total > 0) {
                            Text("缓存 ${state.downloaded} / ${state.total}", fontSize = 14.sp, color = Muted)
                        }
                    }
                }
                item {
                    Column {
                        Rule()
                        Text("目录", Modifier.padding(top = 24.dp, bottom = 8.dp),
                            fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    }
                }
                if (detail.chapters.isEmpty()) {
                    item { FailureState("暂无章节", onRetry) }
                }
                volumes.forEach { (volume, chapters) ->
                    val volumeExpanded = volume in expandedVolumes
                    item(key = "volume:$volume") {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clickable(role = Role.Button) {
                                    expandedVolumes = ArrayList(expandedVolumes).apply {
                                        if (volumeExpanded) remove(volume) else add(volume)
                                    }
                                }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (volumeExpanded) "−" else "+", Modifier.width(32.dp),
                                fontSize = 22.sp, color = Muted)
                            Text(volume.ifBlank { "正文" }, Modifier.weight(1f),
                                fontSize = 20.sp, lineHeight = 28.sp,
                                fontWeight = FontWeight.SemiBold, color = Ink)
                            Text("${chapters.size}章", fontSize = 14.sp, color = Muted)
                        }
                    }
                    if (volumeExpanded) items(chapters, key = { it.id }) { chapter ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable(role = Role.Button) { onRead(chapter) },
                        ) {
                            Text(chapter.title,
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 12.dp),
                                fontSize = 17.sp, lineHeight = 28.sp,
                                fontWeight = if (chapter.id == progress?.chapterId) FontWeight.Medium else FontWeight.Normal,
                                color = if (chapter.id == progress?.chapterId) Accent else Ink)
                            Rule()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    settings: ReaderSettings,
    onChange: (ReaderSettings) -> Unit,
    onBack: () -> Unit,
    onLicenses: () -> Unit,
    onImportReaderFont: () -> Unit = {},
    onImportBackground: () -> Unit = {},
    appearance: AppAppearance = AppAppearance(),
    onAppearance: (AppAppearance) -> Unit = {},
    onImportUiFont: () -> Unit = {},
) {
    var category by rememberSaveable { mutableStateOf(0) }
    var typeSection by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Header("设置", onBack)
        ChoiceRow(listOf("排版", "外观", "阅读", "界面"), category, { category = it },
            Modifier.padding(horizontal = 24.dp))
        if (category == 0) {
            ChoiceRow(listOf("间距", "字体"), typeSection, { typeSection = it },
                Modifier.padding(horizontal = 24.dp))
        }
        if (category == 3) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("界面字体", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Text(appearance.fontLabel, fontSize = 14.sp, color = Muted)
                ActionButton("跟随系统", { onAppearance(AppAppearance()) }, Modifier.fillMaxWidth(),
                    primary = appearance.fontFile.isBlank())
                ActionButton("导入界面字体", onImportUiFont, Modifier.fillMaxWidth())
                PlainTextButton("开源许可", onLicenses, Modifier.fillMaxWidth())
            }
        } else {
            PreferencesContent(settings, onChange,
                when (category) { 0 -> if (typeSection == 0) "type" else "font"; 1 -> "background"; else -> "more" },
                Modifier.weight(1f), onImportFont = onImportReaderFont,
                onImportBackground = onImportBackground)
        }
    }
}

@Composable
fun ReadingPreferences(
    settings: ReaderSettings,
    onChange: (ReaderSettings) -> Unit,
    section: String = "type",
    onImportFont: () -> Unit = {},
    onImportBackground: () -> Unit = {},
) {
    PreferencesContent(settings, onChange, section, Modifier.fillMaxSize(),
        onImportFont = onImportFont, onImportBackground = onImportBackground)
}

@Composable
private fun PreferencesContent(
    settings: ReaderSettings,
    onChange: (ReaderSettings) -> Unit,
    section: String,
    modifier: Modifier,
    onImportFont: () -> Unit = {},
    onImportBackground: () -> Unit = {},
) {
    val latest by rememberUpdatedState(settings)
    val change by rememberUpdatedState(onChange)
    var fontSize by remember(settings.fontSize) { mutableStateOf(settings.fontSize.toFloat()) }
    var fontWeight by remember(settings.fontWeight) { mutableStateOf(settings.fontWeight.toFloat()) }
    var lineHeight by remember(settings.lineHeight) { mutableStateOf(settings.lineHeight.toFloat()) }
    var paragraphSpacing by remember(settings.paragraphSpacing) { mutableStateOf(settings.paragraphSpacing.toFloat()) }
    var pageMargin by remember(settings.pageMargin) { mutableStateOf(settings.pageMargin.toFloat()) }
    var brightness by remember(settings.brightness) { mutableStateOf(settings.brightness.takeIf { it >= 0f } ?: .5f) }
    val preview = settings.copy(
        fontSize = fontSize.roundToInt().coerceIn(16, 32),
        fontWeight = ((fontWeight / 100).roundToInt() * 100).coerceIn(300, 700),
        lineHeight = lineHeight.toDouble(),
        paragraphSpacing = paragraphSpacing.toDouble(),
        pageMargin = pageMargin.roundToInt(),
    )
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (section == "type" || section == "font") {
            item(key = "preview") { ReadingPreview(preview) }
            item(key = "size") {
                Column {
                    PreferenceSlider("字号", fontSize, fontSize.roundToInt().toString(), 16f..32f, 15,
                        { fontSize = it }) { change(latest.copy(fontSize = fontSize.roundToInt().coerceIn(16, 32))) }
                }
            }
        }
        if (section == "font") {
            item(key = "font") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val modes = listOf("system" to "跟随系统", "serif" to "宋体", "sans" to "黑体") +
                        if (settings.fontFile.isNotBlank()) listOf("custom" to settings.fontLabel.ifBlank { "已导入字体" }) else emptyList()
                    modes.forEach { (mode, label) ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (settings.fontMode == mode) MiuixTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                            .semantics { selected = settings.fontMode == mode }
                            .clickable(role = Role.RadioButton) {
                                change(latest.copy(fontMode = mode, serif = mode == "serif"))
                            }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f), fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
                            if (settings.fontMode == mode) Text("✓", fontSize = 16.sp, color = Accent)
                        }
                    }
                    ActionButton("导入阅读字体", onImportFont, Modifier.fillMaxWidth())
                    PreferenceSlider("字重", fontWeight, ((fontWeight / 100).roundToInt() * 100).toString(), 300f..700f, 3,
                        { fontWeight = it }) {
                        change(latest.copy(fontWeight = ((fontWeight / 100).roundToInt() * 100).coerceIn(300, 700)))
                    }
                }
            }
        }
        if (section == "type") {
            item(key = "spacing") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PreferenceSlider("行距", lineHeight, decimalValue(lineHeight), 1.3f..2.4f, 10,
                        { lineHeight = it }) { change(latest.copy(lineHeight = decimalValue(lineHeight).toDouble())) }
                    PreferenceSlider("段距", paragraphSpacing, decimalValue(paragraphSpacing), 0f..1.6f, 15,
                        { paragraphSpacing = it }) {
                        change(latest.copy(paragraphSpacing = decimalValue(paragraphSpacing).toDouble()))
                    }
                    PreferenceSlider("页边距", pageMargin, ((pageMargin / 2).roundToInt() * 2).toString(), 12f..40f, 13,
                        { pageMargin = it }) {
                        change(latest.copy(pageMargin = ((pageMargin / 2).roundToInt() * 2).coerceIn(12, 40)))
                    }
                }
            }
        }
        if (section == "background") {
            item(key = "background") {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    BackgroundChoices(settings.theme) { change(latest.copy(theme = it)) }
                    Text("背景纹理", fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
                    val backgrounds = listOf("none" to "无", "paper" to "纸张", "linen" to "亚麻", "mist" to "薄雾") +
                        settings.backgroundPresets.map { it.id to it.name }
                    backgrounds.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { (id, name) ->
                                val preset = settings.backgroundPresets.firstOrNull { it.id == id }
                                val active = settings.background == id
                                Box(Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(12.dp))
                                    .background(when (id) {
                                        "paper" -> Color(0xFFF2E7CF)
                                        "linen" -> Color(0xFFE5DFD2)
                                        "mist" -> Color(0xFFE4EBED)
                                        else -> if (preset?.dark == true) Color(0xFF303030) else Soft
                                    })
                                    .border(if (active) 2.dp else 1.dp, if (active) Accent else Line, RoundedCornerShape(12.dp))
                                    .semantics { selected = active }
                                    .clickable(role = Role.RadioButton) {
                                        change(latest.copy(background = id, theme = when {
                                            preset?.dark == true -> "night"
                                            preset != null && latest.theme == "night" -> "paper"
                                            else -> latest.theme
                                        }))
                                    },
                                    contentAlignment = Alignment.BottomStart) {
                                    if (preset != null) AsyncImage(model = (LocalContext.current.applicationContext as? BaiyeApplication)?.media?.backgroundFile(preset.file), contentDescription = null,
                                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    Text(name, Modifier.fillMaxWidth().background(Color.White.copy(alpha = .88f)).padding(8.dp),
                                        fontSize = 14.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    ActionButton("导入背景并保存预设", onImportBackground, Modifier.fillMaxWidth())
                    PreferenceSwitch("跟随系统亮度", settings.brightness < 0f) {
                        change(latest.copy(brightness = if (it) -1f else brightness.coerceIn(.1f, 1f)))
                    }
                    if (settings.brightness >= 0f) {
                        PreferenceSlider("亮度", brightness, "${(brightness * 100).roundToInt()}%", .1f..1f, 0,
                            { brightness = it }) { change(latest.copy(brightness = brightness.coerceIn(.1f, 1f))) }
                    }
                }
            }
        }
        if (section == "more" || section == "reading") {
            item(key = "more") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingChoices("翻页", listOf("横向", "滚动"), if (settings.scroll) 1 else 0) {
                        change(latest.copy(scroll = it == 1))
                    }
                    PreferenceSwitch("音量键翻页", settings.volumeKeys) { change(latest.copy(volumeKeys = it)) }
                    SettingChoices("翻页动画", listOf("无动画", "现有", "翻书"),
                        listOf("none", "slide", "curl").indexOf(settings.turnAnimation)) {
                        change(latest.copy(turnAnimation = listOf("none", "slide", "curl")[it]))
                    }
                    PreferenceSwitch("保持亮屏", settings.keepScreenOn) { change(latest.copy(keepScreenOn = it)) }
                    PreferenceSwitch("阅读状态", settings.showReadingStatus) { change(latest.copy(showReadingStatus = it)) }
                    PreferenceSwitch("全屏阅读", settings.immersive) { change(latest.copy(immersive = it)) }
                }
            }
        }
    }
}

private fun decimalValue(value: Float): String = ((value * 10).roundToInt() / 10.0).toString()

private fun readingBackground(theme: String): Color = when (theme) {
    "paper" -> Color(0xFFF6F1E7)
    "green" -> Color(0xFFE3EDDF)
    "night" -> Color(0xFF171717)
    else -> Color.White
}

@Composable
private fun ReadingPreview(settings: ReaderSettings) {
    val app = LocalContext.current.applicationContext as? BaiyeApplication
    val previewFamily by produceState<FontFamily>(FontFamily.Default, settings.fontMode, settings.fontFile) {
        value = when (settings.fontMode) {
            "serif" -> FontFamily.Serif
            "sans" -> FontFamily.SansSerif
            "custom" -> withContext(Dispatchers.IO) {
                runCatching { app?.media?.fontFile(settings.fontFile)?.let { FontFamily(Font(it)) } }.getOrNull() ?: FontFamily.Default
            }
            else -> FontFamily.Default
        }
    }
    val foreground = when (settings.theme) {
        "night" -> Color(0xFFDEDAD2)
        "green" -> Color(0xFF243427)
        "paper" -> Color(0xFF24211D)
        else -> Color(0xFF202020)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = readingBackground(settings.theme), contentColor = foreground),
        insideMargin = PaddingValues(horizontal = settings.pageMargin.dp, vertical = 20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy((settings.fontSize * settings.paragraphSpacing).dp)) {
            listOf("风穿过书页，故事继续。", "翻开下一页，远方也近了一些。").forEach { sentence ->
                Text(sentence, fontSize = settings.fontSize.sp,
                    fontWeight = FontWeight(settings.fontWeight.coerceIn(300, 700)),
                    fontFamily = previewFamily,
                    lineHeight = (settings.fontSize * settings.lineHeight).sp, color = foreground)
            }
        }
    }
}

@Composable
private fun PreferenceGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurface)
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(16.dp),
            colors = CardDefaults.defaultColors(
                color = if (MiuixTheme.colorScheme.background.luminance() < .5f) Color(0xFF303030) else Soft,
                contentColor = MiuixTheme.colorScheme.onSurface),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
        }
    }
}

@Composable
private fun PreferenceSlider(
    title: String,
    value: Float,
    displayValue: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValue: (Float) -> Unit,
    onFinish: () -> Unit,
) {
    Column {
        SettingValue(title, displayValue)
        Slider(value = value, onValueChange = onValue, onValueChangeFinished = onFinish,
            valueRange = range, steps = steps,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = title })
    }
}

@Composable
private fun PreferenceSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, Modifier.weight(1f), fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
        Switch(checked = checked, onCheckedChange = onChange,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = title })
    }
}

@Composable
private fun BackgroundChoices(selectedTheme: String, onSelect: (String) -> Unit) {
    val themes = listOf("white" to "白色", "paper" to "纸色", "green" to "护眼", "night" to "夜间")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("背景", fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
        themes.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { (theme, label) ->
                    val active = theme == selectedTheme
                    Row(
                        Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
                            .background(readingBackground(theme))
                            .border(if (active) 2.dp else 1.dp, if (active) Accent else Line, RoundedCornerShape(12.dp))
                            .semantics { selected = active }
                            .clickable(role = Role.RadioButton) { onSelect(theme) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        val foreground = if (theme == "night") Color(0xFFE0DDD5) else Ink
                        Text(label, fontSize = 16.sp, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                            color = foreground)
                        if (active) Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Accent))
                    }
                }
            }
        }
    }
}

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val links = remember {
        listOf(
            "Miuix" to "https://github.com/compose-miuix-ui/miuix",
            "foliate-js" to "https://github.com/johnfactotum/foliate-js",
            "zip.js" to "https://github.com/gildas-lormeau/zip.js",
            "OkHttp" to "https://github.com/square/okhttp",
            "Jsoup" to "https://github.com/jhy/jsoup",
            "Coil" to "https://github.com/coil-kt/coil",
            "Kotlin" to "https://github.com/JetBrains/kotlin",
            "AndroidX" to "https://github.com/androidx/androidx",
        )
    }
    var failedUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val openLink: (String) -> Unit = { url ->
        failedUrl = if (runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.isSuccess) null else url
    }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Header("开源许可", onBack)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        ) {
            items(links) { (name, url) ->
                Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { openLink(url) }) {
                    Text(name, Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 20.dp),
                        fontSize = 18.sp, color = Ink)
                    Rule()
                }
            }
            failedUrl?.let { url ->
                item { FailureState("无法打开链接", { openLink(url) }, Modifier.padding(vertical = 24.dp)) }
            }
        }
    }
}

@Composable
fun Header(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Ink)
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(title, Modifier.weight(1f), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        actions()
    }
}

@Composable
fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        minHeight = 48.dp,
        cornerRadius = 12.dp,
        colors = ButtonDefaults.buttonColors(
            color = if (primary) Ink else Soft,
            contentColor = if (primary) Color.White else Ink,
        ),
    ) {
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun FailureState(label: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(label, fontSize = 20.sp, color = Ink)
        ActionButton("重试", onRetry)
    }
}

@Composable
private fun MainHeading(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), fontSize = 42.sp, lineHeight = 52.sp,
            fontWeight = FontWeight.Bold, color = Color.Black)
        actions()
    }
}

@Composable
private fun SettingsAction(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Default.Settings, contentDescription = "设置", tint = Ink)
    }
}

@Composable
private fun PlainTextButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        text = label,
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        minHeight = 48.dp,
        colors = ButtonDefaults.textButtonColors(color = Color.Transparent, textColor = MiuixTheme.colorScheme.onSurface),
        textStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, fontFamily = LocalAppFont.current),
    )
}

@Composable
private fun ChoiceRow(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            Column(Modifier.weight(1f).semantics { selected = index == selectedIndex }) {
                PlainTextButton(label, { onSelect(index) }, Modifier.fillMaxWidth())
                Box(Modifier.fillMaxWidth().height(2.dp)
                    .background(if (index == selectedIndex) Accent else MiuixTheme.colorScheme.dividerLine))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(
    book: Book,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    progress: ReadingProgress? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, tween(130), label = "bookPress")
    Column(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .semantics(mergeDescendants = true) {}
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) "管理书籍" else null,
            ),
    ) {
        BookCover(book, Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Text(book.title, Modifier.fillMaxWidth().height(46.dp), fontSize = 16.sp,
            fontWeight = FontWeight.Medium, lineHeight = 23.sp, color = Ink,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(book.author, Modifier.fillMaxWidth().height(22.dp), fontSize = 14.sp,
            fontWeight = FontWeight.Normal, lineHeight = 22.sp, color = Muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (progress != null) {
            val fraction = progress.fraction.coerceIn(0.0, 1.0).toFloat()
            Spacer(Modifier.height(8.dp))
            Text("已读 ${(fraction * 100).roundToInt()}%", fontSize = 14.sp, lineHeight = 22.sp, color = Muted)
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(Line)) {
                Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(Accent))
            }
        }
    }
}

private val BookGlyph: ImageVector = ImageVector.Builder(
    name = "Book", defaultWidth = 36.dp, defaultHeight = 36.dp,
    viewportWidth = 36f, viewportHeight = 36f,
).apply {
    path(fill = null, stroke = SolidColor(Muted), strokeLineWidth = 1.5f) {
        moveTo(8f, 5f)
        lineTo(27f, 5f)
        lineTo(27f, 31f)
        lineTo(8f, 31f)
        close()
        moveTo(12f, 5f)
        lineTo(12f, 31f)
        moveTo(16f, 12f)
        lineTo(23f, 12f)
        moveTo(16f, 17f)
        lineTo(23f, 17f)
    }
}.build()

@Composable
private fun BookCover(book: Book, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(context, book.cover) {
        ImageRequest.Builder(context).data(book.cover).crossfade(180).build()
    }
    Box(
        modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(Soft),
        contentAlignment = Alignment.Center,
    ) {
        Icon(BookGlyph, contentDescription = null, modifier = Modifier.size(36.dp), tint = Muted)
        if (book.cover.isNotBlank()) {
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun StatusLabel(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 18.sp, color = Muted)
    }
}

@Composable
private fun Rule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

@Composable
private fun SettingValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
        Text(value, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun SettingChoices(title: String, labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
        ChoiceRow(labels, selected, onSelect)
    }
}
