package io.github.julssvanderhaack.sonata

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.room.Room
import io.github.julssvanderhaack.sonata.eq.EqBus
import io.github.julssvanderhaack.sonata.library.LibraryDb
import io.github.julssvanderhaack.sonata.lyrics.LyricsRepository
import io.github.julssvanderhaack.sonata.library.Scanner
import io.github.julssvanderhaack.sonata.player.PlayerConnection
import io.github.julssvanderhaack.sonata.settings.Settings
import io.github.julssvanderhaack.sonata.theme.ThemeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Process-wide singletons. Small enough not to need a DI framework. */
class SonataApp : Application() {
    val scope: CoroutineScope = MainScope()
    lateinit var db: LibraryDb
        private set
    lateinit var settings: Settings
        private set
    lateinit var themes: ThemeRepository
        private set
    lateinit var scanner: Scanner
        private set
    lateinit var player: PlayerConnection
        private set
    lateinit var lyrics: LyricsRepository
        private set

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private var observer: ContentObserver? = null
    private var pendingScan: Job? = null

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, LibraryDb::class.java, "library.db")
            .fallbackToDestructiveMigration(dropAllTables = true) // it's a cache of MediaStore
            .build()
        settings = Settings(this)
        themes = ThemeRepository(this)
        scanner = Scanner(this, db.tracks())
        player = PlayerConnection(this)
        lyrics = LyricsRepository(this)
        scope.launch { themes.load() }
        // the EQ lives in EqBus for the audio thread; persist it once the user stops dragging
        scope.launch {
            EqBus.settings.value = settings.eq.first()
            @OptIn(FlowPreview::class)
            EqBus.settings.drop(1).debounce(600).collect { settings.setEq(it) }
        }
    }

    /** Called once the audio permission is granted: scans now and again whenever MediaStore changes. */
    fun startLibrary() {
        rescan()
        if (observer != null) return
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                // copying an album fires one change per file; wait for the burst to end
                pendingScan?.cancel()
                pendingScan = scope.launch {
                    delay(2000)
                    rescan()
                }
            }
        }.also { contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, it) }
    }

    fun rescan() {
        if (_scanning.value) return
        _scanning.value = true
        scope.launch {
            try {
                scanner.scan()
            } finally {
                _scanning.value = false
            }
        }
    }
}
