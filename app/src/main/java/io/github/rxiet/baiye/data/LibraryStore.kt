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
        json.decodeFromString(file.openRead().bufferedReader().use { it.readText() })
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

    suspend fun remove(id: String) = change { state -> state.copy(entries = state.entries.filterNot { it.book.id == id }) }
    suspend fun settings(settings: ReaderSettings) = change { it.copy(settings = settings) }
}
