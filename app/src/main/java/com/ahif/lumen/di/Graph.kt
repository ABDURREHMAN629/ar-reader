package com.ahif.lumen.di

import android.content.Context
import com.ahif.lumen.data.library.LibraryRepository
import com.ahif.lumen.data.prefs.DocumentStateStore
import com.ahif.lumen.data.prefs.HistoryStore
import com.ahif.lumen.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.ahif.lumen.data.reader.OfficeUnlock
import com.ahif.lumen.data.library.DocumentResolver

/** Minimal manual dependency container, initialised from [LumenApp]. */
object Graph {
    /** Lives for the whole app process; use for fire-and-forget writes on screen exit. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settings: SettingsRepository
        private set
    lateinit var stateStore: DocumentStateStore
        private set
    lateinit var history: HistoryStore
        private set
    lateinit var library: LibraryRepository
        private set

    fun init(context: Context) {
        val app = context.applicationContext
        settings = SettingsRepository(app)
        stateStore = DocumentStateStore(app)
        history = HistoryStore(app)
        library = LibraryRepository(app, settings, stateStore)
        // Load cached library instantly, then rescan in the background.
        library.loadCacheThenRefresh()
        // Decrypted copies of protected files must not outlive the reader that used them.
        appScope.launch(Dispatchers.IO) { OfficeUnlock.wipeAll(app) }
        // Old History entries may hold expired WhatsApp links; point them at the real files.
        appScope.launch { DocumentResolver.repairHistory(app, history, stateStore, library) }
    }
}
