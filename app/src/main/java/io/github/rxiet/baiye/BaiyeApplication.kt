package io.github.rxiet.baiye

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.rxiet.baiye.data.Book
import io.github.rxiet.baiye.data.EpubImporter
import io.github.rxiet.baiye.data.LibraryStore
import io.github.rxiet.baiye.data.WenkuRepository
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

class BaiyeApplication : Application(), SingletonImageLoader.Factory {
    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val httpClient by lazy {
        OkHttpClient.Builder()
            .cache(Cache(File(cacheDir, "http"), 64L * 1024 * 1024))
            .connectionPool(ConnectionPool(12, 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val original = chain.request()
                val request = if ((original.url.host == "wenku8.com" || original.url.host.endsWith(".wenku8.com")) &&
                    original.method == "GET" && original.header("Referer").isNullOrBlank()) {
                    original.newBuilder().header("Referer", "${original.url.scheme}://www.wenku8.net/").build()
                } else original
                chain.proceed(request)
            }.build()
    }
    val library by lazy { LibraryStore(this, ioScope) }
    val repository by lazy { WenkuRepository(this, httpClient) }
    val importer by lazy { EpubImporter(this) }
    val media by lazy { io.github.rxiet.baiye.reader.ReaderMediaStore(this) }
    val account by lazy { io.github.rxiet.baiye.data.AccountRepository(this, httpClient) }

    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { httpClient })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.18).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("covers")).maxSizeBytes(96L * 1024 * 1024).build() }
        .crossfade(180)
        .build()

    suspend fun deleteReaderFont() {
        val name = library.forgetReaderFont()
        if (name.isNotEmpty() && library.state.value.appearance.fontFile != name) media.deleteFont(name)
    }

    suspend fun deleteUiFont() {
        val name = library.forgetUiFont()
        if (name.isNotEmpty() && library.state.value.settings.fontFile != name) media.deleteFont(name)
    }

    suspend fun deleteBackground(id: String) {
        val name = library.forgetBackground(id)
        if (name.isNotEmpty() && library.state.value.settings.backgroundPresets.none { it.file == name }) media.deleteBackground(name)
    }

    fun prefetchCovers(books: List<Book>) {
        val loader = SingletonImageLoader.get(this)
        books.filter { it.cover.isNotBlank() }.take(20).forEach { book ->
            loader.enqueue(ImageRequest.Builder(this).data(book.cover).size(384, 576).build())
        }
    }
}
