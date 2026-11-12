package io.github.rxiet.baiye

import android.app.Application
import io.github.rxiet.baiye.data.EpubImporter
import io.github.rxiet.baiye.data.LibraryStore
import io.github.rxiet.baiye.data.WenkuRepository

class BaiyeApplication : Application() {
    val ioScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    val library by lazy { LibraryStore(this) }
    val repository by lazy { WenkuRepository(this) }
    val importer by lazy { EpubImporter(this) }
}
