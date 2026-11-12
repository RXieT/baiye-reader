package io.github.rxiet.baiye

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.rxiet.baiye.data.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BaiyeApplication
    val library = app.library.state
    private val mutableCatalog = MutableStateFlow(CatalogState())
    val catalog = mutableCatalog.asStateFlow()
    private val mutableDetail = MutableStateFlow<DetailState?>(null)
    val detail = mutableDetail.asStateFlow()
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = mutableMessages.asSharedFlow()
    private var catalogJob: Job? = null
    private var detailJob: Job? = null
    private var downloadJob: Job? = null
    private var downloadBookId: String? = null

    init {
        viewModelScope.launch {
            try { app.library.load() } catch (_: Exception) { mutableMessages.emit("书架读取失败") }
        }
        loadCatalog()
    }

    fun search(query: String) {
        mutableCatalog.value = CatalogState(query = query.trim().take(100), order = catalog.value.order)
        loadCatalog()
    }

    fun order(order: CatalogOrder) {
        mutableCatalog.value = CatalogState(order = order)
        loadCatalog()
    }

    fun loadCatalog(more: Boolean = false) {
        if (more && (catalog.value.loading || !catalog.value.hasMore)) return
        catalogJob?.cancel()
        val request = catalog.value
        val page = if (more) request.page + 1 else 1
        catalogJob = viewModelScope.launch {
            mutableCatalog.update { it.copy(loading = true, error = null) }
            try {
                val result = app.repository.catalog(request.order, page, request.query)
                ensureActive()
                mutableCatalog.value = request.copy(
                    books = (if (more) request.books + result.books else result.books).distinctBy { it.id },
                    loading = false, error = null, page = page, hasMore = result.hasMore,
                )
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableCatalog.update { it.copy(loading = false, error = "连接失败") }
            }
        }
    }

    fun showBook(book: Book) {
        detailJob?.cancel()
        mutableDetail.value = DetailState(book)
        detailJob = viewModelScope.launch {
            try {
                val result = app.repository.detail(book)
                ensureActive()
                mutableDetail.value = DetailState(result.book, detail = result, loading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableDetail.value = DetailState(book, loading = false, error = "连接失败") }
        }
    }

    fun favorite() = viewModelScope.launch {
        val book = detail.value?.detail?.book ?: return@launch
        try {
            val existing = library.value.entries.find { it.book.id == book.id }
            if (existing == null) app.library.add(book)
            else app.library.favorite(book.id, !existing.favorite)
        } catch (_: Exception) { mutableMessages.emit("保存失败") }
    }

    suspend fun prepareRead(book: Book): Boolean = try {
        app.library.add(book, favorite = false)
        true
    } catch (_: Exception) { mutableMessages.emit("保存失败"); false }

    fun importBook(uri: Uri) = viewModelScope.launch {
        mutableMessages.emit("正在导入")
        try {
            val entry = app.importer.import(uri)
            app.library.add(entry.book, epubFile = entry.epubFile)
            mutableMessages.emit("已导入")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableMessages.emit("导入失败") }
    }

    fun remove(entry: ShelfEntry) = viewModelScope.launch {
        try {
            if (downloadBookId == entry.book.id) cancelDownload()
            app.library.remove(entry.book.id)
            app.repository.removeCachedBook(entry.book.id)
            if (entry.book.local) {
                val folder = File(app.filesDir, "imports")
                folder.listFiles()?.filter { it.name.startsWith("${entry.book.id}.") }?.forEach { it.delete() }
            }
            mutableMessages.emit("已移除")
        } catch (_: Exception) { mutableMessages.emit("移除失败") }
    }

    fun settings(settings: ReaderSettings) = viewModelScope.launch {
        try { app.library.settings(settings) }
        catch (_: Exception) { mutableMessages.emit("保存失败") }
    }

    fun download() {
        val detail = detail.value?.detail ?: return
        if (downloadJob?.isActive == true) return
        downloadBookId = detail.book.id
        downloadJob = viewModelScope.launch {
            try {
                app.library.add(detail.book)
                mutableDetail.update { it?.copy(downloading = true, downloaded = 0, total = detail.chapters.size) }
                detail.chapters.forEachIndexed { index, chapter ->
                    ensureActive()
                    app.repository.chapterXhtml(detail.book.id, chapter)
                    if (mutableDetail.value?.book?.id == detail.book.id) {
                        mutableDetail.update { it?.copy(downloaded = index + 1) }
                    }
                    delay(250)
                }
                mutableMessages.emit("缓存完成")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableMessages.emit("缓存中断") }
            finally {
                if (mutableDetail.value?.book?.id == detail.book.id) mutableDetail.update { it?.copy(downloading = false) }
                downloadBookId = null
            }
        }
    }

    fun cancelDownload() { downloadJob?.cancel(); downloadJob = null }
}
