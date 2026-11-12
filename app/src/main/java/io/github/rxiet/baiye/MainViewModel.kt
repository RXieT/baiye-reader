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
import kotlinx.coroutines.coroutineScope
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
    private var enrichmentJob: Job? = null
    private var downloadJob: Job? = null
    private var downloadBookId: String? = null
    private var downloadDone = 0
    private var downloadTotal = 0

    init {
        viewModelScope.launch {
            try { app.library.load() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { android.util.Log.w("BaiyeLibrary", "Library load deferred", failure) }
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
        enrichmentJob?.cancel()
        val request = catalog.value
        val page = if (more) request.page + 1 else 1
        catalogJob = viewModelScope.launch {
            mutableCatalog.update { it.copy(loading = true, error = null) }
            try {
                val result = app.repository.catalog(request.order, page, request.query)
                ensureActive()
                val combined = (if (more) request.books + result.books else result.books).distinctBy { it.id }
                mutableCatalog.value = request.copy(
                    books = combined, loading = false, error = null, page = page,
                    hasMore = result.hasMore && (!more || result.books.any { book -> request.books.none { it.id == book.id } }),
                )
                app.prefetchCovers(result.books)
                enrichmentJob = viewModelScope.launch {
                    coroutineScope {
                        combined.filter { it.author.isBlank() }.forEach { book ->
                            launch {
                                try {
                                    val enriched = app.repository.metadata(book)
                                    mutableCatalog.update { current ->
                                        if (current.query != request.query || current.order != request.order) current
                                        else current.copy(books = current.books.map { if (it.id == book.id) enriched else it })
                                    }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { /* Optional metadata must not block the book list. */ }
                            }
                        }
                    }
                }
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
                val downloading = downloadBookId == book.id && downloadJob?.isActive == true
                mutableDetail.value = DetailState(result.book, detail = result, loading = false,
                    downloading = downloading, downloaded = if (downloading) downloadDone else 0,
                    total = if (downloading) downloadTotal else 0)
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
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { android.util.Log.w("BaiyeLibrary", "Bookmark write deferred", failure) }
    }

    fun toggleFavorite(entry: ShelfEntry) = viewModelScope.launch {
        try { app.library.favorite(entry.book.id, !entry.favorite) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { android.util.Log.w("BaiyeLibrary", "Bookmark write deferred", failure) }
    }

    suspend fun prepareRead(book: Book): Boolean = try {
        val existing = library.value.entries.find { it.book.id == book.id }
        app.library.add(book, favorite = existing?.favorite ?: true)
        true
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { android.util.Log.w("BaiyeLibrary", "Cannot prepare book", failure); false }

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
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { android.util.Log.w("BaiyeLibrary", "Settings write deferred", failure) }
    }

    fun download() {
        val detail = detail.value?.detail ?: return
        if (downloadJob?.isActive == true) { mutableMessages.tryEmit("正在缓存"); return }
        downloadBookId = detail.book.id
        downloadDone = 0
        downloadTotal = detail.chapters.size
        downloadJob = viewModelScope.launch {
            try {
                app.library.add(detail.book)
                mutableDetail.update { it?.copy(downloading = true, downloaded = 0, total = detail.chapters.size) }
                detail.chapters.forEachIndexed { index, chapter ->
                    ensureActive()
                    app.repository.cacheChapter(detail.book.id, chapter)
                    downloadDone = index + 1
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
