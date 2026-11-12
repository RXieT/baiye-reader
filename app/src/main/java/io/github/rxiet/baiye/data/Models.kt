package io.github.rxiet.baiye.data

import kotlinx.serialization.Serializable

@Serializable
data class Book(
    val id: String,
    val title: String,
    val author: String = "",
    val cover: String = "",
    val description: String = "",
    val status: String = "",
    val local: Boolean = false,
)

@Serializable
data class Chapter(val id: String, val title: String, val volume: String = "")

@Serializable
data class BookDetail(val book: Book, val chapters: List<Chapter>)

@Serializable
data class ReadingProgress(
    val cfi: String = "",
    val fraction: Double = 0.0,
    val chapter: String = "",
    val chapterId: String = "",
)

@Serializable
data class ShelfEntry(
    val book: Book,
    val progress: ReadingProgress = ReadingProgress(),
    val favorite: Boolean = true,
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = 0,
    val epubFile: String = "",
)

@Serializable
data class ReaderSettings(
    val fontSize: Int = 20,
    val lineHeight: Double = 1.8,
    val theme: String = "paper",
    val scroll: Boolean = false,
    val serif: Boolean = true,
    val keepScreenOn: Boolean = true,
)

@Serializable
data class LibraryState(
    val entries: List<ShelfEntry> = emptyList(),
    val settings: ReaderSettings = ReaderSettings(),
)

enum class CatalogOrder(val label: String, val value: String) {
    UPDATED("更新", "lastupdate"), POPULAR("热门", "allvisit"), COMPLETE("完结", "fullflag");
}

@Serializable
data class CatalogPage(val books: List<Book>, val hasMore: Boolean)

data class CatalogState(
    val books: List<Book> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val page: Int = 1,
    val hasMore: Boolean = true,
    val query: String = "",
    val order: CatalogOrder = CatalogOrder.UPDATED,
)

data class DetailState(
    val book: Book,
    val detail: BookDetail? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val downloading: Boolean = false,
    val downloaded: Int = 0,
    val total: Int = 0,
)
