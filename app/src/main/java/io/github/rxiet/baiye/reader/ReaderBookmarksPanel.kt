package io.github.rxiet.baiye.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.rxiet.baiye.data.Bookmark
import io.github.rxiet.baiye.ui.ActionButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ReaderBookmarksPanel(bookmarks: List<Bookmark>, currentCfi: String, ready: Boolean,
    onAdd: () -> Unit, onGo: (Bookmark) -> Unit, onDelete: (Bookmark) -> Unit) {
    var removing by remember { mutableStateOf<Bookmark?>(null) }
    val colors = MiuixTheme.colorScheme
    val exists = bookmarks.any { it.cfi == currentCfi }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ActionButton(if (exists) "已添加书签" else "添加当前页", onAdd,
                Modifier.fillMaxWidth(), primary = true, enabled = ready && !exists)
        }
        if (bookmarks.isEmpty()) item {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("暂无书签", fontSize = 16.sp, color = colors.onSurfaceVariantSummary)
            }
        }
        items(bookmarks, key = { it.id }) { bookmark ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(colors.surfaceContainerHigh).clickable { onGo(bookmark) }.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(bookmark.chapter.ifBlank { "书签" }, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                    color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (bookmark.excerpt.isNotBlank()) Text(bookmark.excerpt, fontSize = 14.sp, lineHeight = 23.sp,
                    color = colors.onSurfaceVariantSummary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(remember(bookmark.createdAt) { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(bookmark.createdAt)) },
                        Modifier.weight(1f), fontSize = 14.sp, color = colors.onSurfaceVariantSummary)
                    TextButton(text = "删除", onClick = { removing = bookmark }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
    }
    removing?.let { bookmark ->
        Dialog(onDismissRequest = { removing = null }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.surface).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("删除书签", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                Text(bookmark.chapter.ifBlank { "书签" }, fontSize = 16.sp, color = colors.onSurface)
                ActionButton("删除", { removing = null; onDelete(bookmark) }, Modifier.fillMaxWidth())
                ActionButton("取消", { removing = null }, Modifier.fillMaxWidth())
            }
        }
    }
}
