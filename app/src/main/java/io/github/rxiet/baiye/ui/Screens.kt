package io.github.rxiet.baiye.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
) {
    var search by rememberSaveable { mutableStateOf(state.query) }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        keyboard?.hide()
        onSearch(search.trim())
    }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        MainHeading("文库") { SettingsAction(onSettings) }
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
                textStyle = TextStyle(fontSize = 17.sp, color = Ink),
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
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(state.books) { book ->
                BookTile(book = book, onClick = { onBook(book) })
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (state.loading) StatusLabel("加载中")
                    if (state.error != null) FailureState("连接失败", onRetry)
                    if (state.books.isEmpty() && !state.loading && state.error == null) {
                        StatusLabel("暂无书籍")
                        ActionButton("重试", onRetry)
                    }
                    if (state.books.isNotEmpty() && state.hasMore && state.error == null) {
                        ActionButton(
                            label = "加载更多",
                            onClick = onMore,
                            enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth(),
                        )
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
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val visible = remember(entries, tab) {
        if (tab == 0) entries.filter { it.favorite }
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
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                items(visible) { entry ->
                    BookTile(
                        book = entry.book,
                        onClick = { onBook(entry) },
                        onLongClick = { selectedId = entry.book.id },
                    )
                }
            }
        }
    }
    if (selectedEntry != null) {
        Dialog(onDismissRequest = { selectedId = null }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(Color.White).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text("删除书籍", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Text(selectedEntry.book.title, fontSize = 18.sp, color = Ink)
                ActionButton(
                    "删除",
                    onClick = {
                        selectedId = null
                        onRemove(selectedEntry)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    primary = true,
                )
                ActionButton("取消", { selectedId = null }, Modifier.fillMaxWidth())
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
) {
    val detail = state.detail
    val book = detail?.book ?: state.book
    var expanded by rememberSaveable(book.id) { mutableStateOf(false) }
    val volumes = remember(detail) { detail?.chapters?.groupBy { it.volume }.orEmpty() }
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
                        if (book.author.isNotBlank()) Text(book.author, fontSize = 17.sp, color = Ink)
                        if (book.status.isNotBlank()) Text(book.status, fontSize = 17.sp, color = Muted)
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
                        Column {
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
                            PlainTextButton(if (expanded) "收起简介" else "展开简介", { expanded = !expanded })
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (detail.chapters.isNotEmpty()) {
                            ActionButton("开始阅读", { onRead(null) }, Modifier.fillMaxWidth(), primary = true)
                        }
                        ActionButton(
                            if (favorite) "取消收藏" else "收藏",
                            onFavorite,
                            Modifier.fillMaxWidth(),
                        )
                        ActionButton(
                            if (state.downloading) "取消缓存" else "缓存",
                            if (state.downloading) onCancelDownload else onDownload,
                            Modifier.fillMaxWidth(),
                            enabled = state.downloading || detail.chapters.isNotEmpty(),
                        )
                        if (state.downloading) {
                            Text("缓存 ${state.downloaded} / ${state.total}", fontSize = 17.sp, color = Ink)
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
                    if (volume.isNotBlank()) {
                        item {
                            Text(volume, Modifier.padding(top = 12.dp, bottom = 4.dp),
                                fontSize = 20.sp, lineHeight = 28.sp,
                                fontWeight = FontWeight.SemiBold, color = Ink)
                        }
                    }
                    items(chapters) { chapter ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable(role = Role.Button) { onRead(chapter) },
                        ) {
                            Text(chapter.title,
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 12.dp),
                                fontSize = 18.sp, lineHeight = 28.sp, color = Ink)
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
) {
    var fontSize by remember(settings.fontSize) { mutableStateOf(settings.fontSize.toFloat().coerceIn(16f, 32f)) }
    var lineHeight by remember(settings.lineHeight) { mutableStateOf(settings.lineHeight.toFloat().coerceIn(1.3f, 2.4f)) }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Header("设置", onBack)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingValue("字号", fontSize.roundToInt().toString())
                    Slider(
                        value = fontSize,
                        onValueChange = { fontSize = it },
                        onValueChangeFinished = {
                            onChange(settings.copy(fontSize = fontSize.roundToInt().coerceIn(16, 32)))
                        },
                        valueRange = 16f..32f,
                        steps = 15,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .semantics { contentDescription = "字号" },
                    )
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingValue("行距", ((lineHeight * 10).roundToInt() / 10.0).toString())
                    Slider(
                        value = lineHeight,
                        onValueChange = { lineHeight = it },
                        onValueChangeFinished = {
                            val value = ((lineHeight * 10).roundToInt() / 10.0).coerceIn(1.3, 2.4)
                            onChange(settings.copy(lineHeight = value))
                        },
                        valueRange = 1.3f..2.4f,
                        steps = 10,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .semantics { contentDescription = "行距" },
                    )
                }
            }
            item {
                SettingChoices("外观", listOf("白色", "纸色", "夜间"),
                    listOf("white", "paper", "night").indexOf(settings.theme)) {
                    onChange(settings.copy(theme = listOf("white", "paper", "night")[it]))
                }
            }
            item {
                SettingChoices("翻页", listOf("横向", "滚动"), if (settings.scroll) 1 else 0) {
                    onChange(settings.copy(scroll = it == 1))
                }
            }
            item {
                SettingChoices("字体", listOf("宋体", "黑体"), if (settings.serif) 0 else 1) {
                    onChange(settings.copy(serif = it == 0))
                }
            }
            item {
                Rule()
                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(min = 48.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("保持亮屏", Modifier.weight(1f), fontSize = 18.sp, color = Ink)
                    Switch(
                        checked = settings.keepScreenOn,
                        onCheckedChange = { onChange(settings.copy(keepScreenOn = it)) },
                        modifier = Modifier.heightIn(min = 48.dp)
                            .semantics { contentDescription = "保持亮屏" },
                    )
                }
            }
            item { ActionButton("开源许可", onLicenses, Modifier.fillMaxWidth()) }
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
        colors = ButtonDefaults.textButtonColors(color = Color.Transparent, textColor = Ink),
        textStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
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
                    .background(if (index == selectedIndex) Accent else Line))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(book: Book, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) { contentDescription = book.title }
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) "删除书籍" else null,
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BookCover(book, Modifier.fillMaxWidth())
        Text(book.title, Modifier.clearAndSetSemantics {}, fontSize = 16.sp,
            lineHeight = 23.sp, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    Box(
        modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(Soft),
        contentAlignment = Alignment.Center,
    ) {
        Icon(BookGlyph, contentDescription = null, modifier = Modifier.size(36.dp), tint = Muted)
        if (book.cover.isNotBlank()) {
            AsyncImage(
                model = book.cover,
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
        Text(label, fontSize = 18.sp, color = Ink)
        Text(value, fontSize = 18.sp, color = Ink)
    }
}

@Composable
private fun SettingChoices(title: String, labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 18.sp, color = Ink)
        ChoiceRow(labels, selected, onSelect)
    }
}
