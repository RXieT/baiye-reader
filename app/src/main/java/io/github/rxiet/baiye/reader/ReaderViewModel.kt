package io.github.rxiet.baiye.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.rxiet.baiye.BaiyeApplication
import io.github.rxiet.baiye.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class ReaderManifest(
    val book: Book,
    val chapters: List<Chapter> = emptyList(),
    val initialCfi: String = "",
    val initialChapterId: String = "",
    val startChapterId: String = "",
    val settings: ReaderSettings,
)

data class ReaderDocument(val entry: ShelfEntry, val manifest: ReaderManifest)
data class ReaderToc(val label: String, val href: String)

class ReaderViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    val app = application as BaiyeApplication
    val library = app.library.state
    private val mutableDocument = MutableStateFlow<ReaderDocument?>(null)
    val document = mutableDocument.asStateFlow()
    val error = MutableStateFlow<String?>(null)
    val toc = MutableStateFlow<List<ReaderToc>>(emptyList())
    val ready = MutableStateFlow(false)
    val progress = MutableStateFlow(ReadingProgress())
    private var pendingProgress: ReadingProgress? = null
    private var saveJob: Job? = null
    private var loadJob: Job? = null

    init { load() }

    fun load() {
        loadJob?.cancel()
        error.value = null
        loadJob = viewModelScope.launch {
            try {
                app.library.load()
                val id = savedState.get<String>("bookId") ?: kotlin.error("缺少书籍")
                val entry = library.value.entries.find { it.book.id == id } ?: kotlin.error("书籍已移除")
                val detail = if (entry.book.local) null else app.repository.detail(entry.book)
                val manifest = ReaderManifest(
                    book = detail?.book ?: entry.book,
                    chapters = detail?.chapters.orEmpty(),
                    initialCfi = entry.progress.cfi,
                    initialChapterId = entry.progress.chapterId,
                    startChapterId = savedState.get<String>("chapterId").orEmpty(),
                    settings = library.value.settings,
                )
                progress.value = entry.progress
                mutableDocument.value = ReaderDocument(entry, manifest)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error.value = "书籍加载失败" }
        }
    }

    fun record(value: ReadingProgress) {
        if (!ready.value || error.value != null || value.cfi.isBlank() || !value.fraction.isFinite()) return
        progress.value = value
        pendingProgress = value
        saveJob?.cancel()
        saveJob = app.ioScope.launch { delay(350); saveProgress(value) }
    }

    private suspend fun saveProgress(value: ReadingProgress) {
        val id = document.value?.entry?.book?.id ?: return
        try { app.library.progress(id, value) }
        catch (_: Exception) { error.value = "进度保存失败" }
    }

    fun flushProgress() {
        val value = pendingProgress ?: return
        saveJob?.cancel()
        saveJob = app.ioScope.launch { saveProgress(value) }
    }

    fun settings(value: ReaderSettings) = viewModelScope.launch {
        try { app.library.settings(value) }
        catch (_: Exception) { error.value = "设置保存失败" }
    }
}
