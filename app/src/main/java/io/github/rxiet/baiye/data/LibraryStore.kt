package io.github.rxiet.baiye.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LibraryStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "library.json"))
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow(LibraryState())
    val state = mutable.asStateFlow()
    private var loaded = false

    private fun readState(): LibraryState = try {
        json.decodeFromString(file.openRead().bufferedReader().use { it.readText() })
    } catch (error: java.io.FileNotFoundException) {
        LibraryState()
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loaded) {
                // A damaged file is kept intact rather than silently overwritten.
                mutable.value = readState()
                loaded = true
            }
        }
    }

    private suspend fun change(block: (LibraryState) -> LibraryState) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loaded) { mutable.value = readState(); loaded = true }
            val next = block(mutable.value)
            val output = file.startWrite()
            try {
                output.write(json.encodeToString(next).toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
            mutable.value = next
        }
    }

    suspend fun add(book: Book, favorite: Boolean = true, epubFile: String = "") = change { state ->
        val existing = state.entries.find { it.book.id == book.id }
        val entry = existing?.copy(
            book = book,
            favorite = existing.favorite || favorite,
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

    suspend fun remove(id: String) = change { state -> state.copy(entries = state.entries.filterNot { it.book.id == id }) }
    suspend fun settings(settings: ReaderSettings) = change { it.copy(settings = settings) }
}
