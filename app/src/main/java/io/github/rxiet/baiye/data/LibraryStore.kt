package io.github.rxiet.baiye.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** UI state is immediate; transient filesystem failures are retried without interrupting reading. */
class LibraryStore(context: Context, private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    private val file = AtomicFile(File(context.filesDir, "library.json"))
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow(LibraryState())
    val state = mutable.asStateFlow()
    private var loaded = false
    private var dirty = false
    private var retryJob: Job? = null

    private fun readState(): LibraryState = try {
        val raw = file.openRead().bufferedReader().use { it.readText() }
        val decoded = json.decodeFromString<LibraryState>(raw)
        val settings = json.parseToJsonElement(raw).jsonObject["settings"]?.jsonObject
        if (settings?.containsKey("turnAnimation") != true && !decoded.settings.animatedPage)
            decoded.copy(settings = decoded.settings.copy(turnAnimation = "none")) else decoded
    } catch (_: java.io.FileNotFoundException) {
        LibraryState()
    } catch (error: SerializationException) {
        // Retain the damaged original for recovery before starting a new state file.
        val recovery = File(file.baseFile.parentFile, "library-recovery-${System.currentTimeMillis()}.json")
        file.baseFile.copyTo(recovery)
        Log.w("BaiyeLibrary", "Recovered damaged library metadata", error)
        LibraryState()
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loaded) { mutable.value = readState(); loaded = true }
        }
    }

    private fun persist(): Boolean {
        if (!dirty) return true
        var output: java.io.FileOutputStream? = null
        return try {
            val data = json.encodeToString(mutable.value).toByteArray(Charsets.UTF_8)
            output = file.startWrite()
            output.write(data)
            file.finishWrite(output)
            dirty = false
            true
        } catch (error: IOException) {
            if (output != null) file.failWrite(output)
            Log.w("BaiyeLibrary", "Deferring library write", error)
            false
        }
    }

    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            for (wait in listOf(1500L, 3000L, 6000L, 12000L, 30000L)) {
                delay(wait)
                val saved = mutex.withLock { persist() }
                if (saved) return@launch
            }
        }
    }

    private suspend fun change(block: (LibraryState) -> LibraryState) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loaded) { mutable.value = readState(); loaded = true }
            val next = block(mutable.value)
            if (next == mutable.value && !dirty) return@withLock
            mutable.value = next
            dirty = true
            if (!persist()) scheduleRetry()
        }
    }

    suspend fun add(book: Book, favorite: Boolean = true, epubFile: String = "") = change { state ->
        val existing = state.entries.find { it.book.id == book.id }
        val entry = existing?.copy(
            book = book, favorite = existing.favorite || favorite,
            epubFile = epubFile.ifEmpty { existing.epubFile },
        ) ?: ShelfEntry(book = book, favorite = favorite, epubFile = epubFile)
        state.copy(entries = state.entries.filterNot { it.book.id == book.id } + entry)
    }

    suspend fun favorite(id: String, value: Boolean) = change { state ->
        state.copy(entries = state.entries.map { if (it.book.id == id) it.copy(favorite = value) else it })
    }

    suspend fun progress(id: String, progress: ReadingProgress) = change { state ->
        state.copy(entries = state.entries.map {
            if (it.book.id == id) it.copy(progress = progress.copy(fraction = progress.fraction.coerceIn(0.0, 1.0)), lastReadAt = System.currentTimeMillis()) else it
        })
    }

    suspend fun remove(id: String) = change { state -> state.copy(
        entries = state.entries.filterNot { it.book.id == id }, bookmarks = state.bookmarks.filterNot { it.bookId == id }) }
    suspend fun settings(settings: ReaderSettings) = change { it.copy(settings = settings) }
    suspend fun appearance(appearance: AppAppearance) = change { it.copy(appearance = appearance) }

    suspend fun addBookmark(bookId: String, progress: ReadingProgress, excerpt: String) = change { state ->
        if (progress.cfi.isBlank() || state.bookmarks.any { it.bookId == bookId && it.cfi == progress.cfi }) state
        else state.copy(bookmarks = state.bookmarks + Bookmark(
            id = java.util.UUID.randomUUID().toString(), bookId = bookId, chapter = progress.chapter,
            cfi = progress.cfi, chapterId = progress.chapterId, fraction = progress.fraction,
            sectionIndex = progress.sectionIndex, page = progress.page, excerpt = excerpt.take(160),
        ))
    }

    suspend fun removeBookmark(id: String) = change { state -> state.copy(bookmarks = state.bookmarks.filterNot { it.id == id }) }

    suspend fun forgetReaderFont(): String {
        var previous = ""
        change { state ->
            previous = state.settings.fontFile
            state.copy(settings = state.settings.copy(fontMode = if (state.settings.fontMode == "custom") "system" else state.settings.fontMode,
                fontFile = "", fontLabel = ""))
        }
        return previous
    }

    suspend fun forgetUiFont(): String {
        var previous = ""
        change { state -> previous = state.appearance.fontFile; state.copy(appearance = AppAppearance()) }
        return previous
    }

    suspend fun forgetBackground(id: String): String {
        var previous = ""
        change { state ->
            previous = state.settings.backgroundPresets.find { it.id == id }?.file.orEmpty()
            state.copy(settings = state.settings.copy(
                background = if (state.settings.background == id) "none" else state.settings.background,
                backgroundPresets = state.settings.backgroundPresets.filterNot { it.id == id },
            ))
        }
        return previous
    }
}
