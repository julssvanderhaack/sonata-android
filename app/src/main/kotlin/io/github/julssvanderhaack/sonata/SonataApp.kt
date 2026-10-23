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
import io.github.julssvanderhaack.sonata.library.TrackIndex
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
            .fallbackToDestructiveMigration(dropAllTables = true) // a cache of the music folders: rebuilt by the next scan
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

    private var started = false
    private var lastScanAt = 0L
    private var again = false

    /** Called once at launch: scans now, whenever the music folders change and when MediaStore notices changes. */
    fun startLibrary() {
        if (started) return
        started = true
        lastScanAt = System.currentTimeMillis() // the scan below counts: no second one from coming to the foreground
        scope.launch { db.tracks().all().collect { TrackIndex.byId = it.associateBy { t -> t.id } } }
        // the first value starts the initial scan
        scope.launch { settings.flow.map { it.musicFolders }.distinctUntilChanged().collect { rescan() } }
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

    /** Back in the foreground: files may have arrived meanwhile (SFTP, a cable…) without anyone telling. */
    fun rescanIfStale() {
        if (started && System.currentTimeMillis() - lastScanAt > 2 * 60_000) rescan()
    }

    private val scanLock = Mutex()

    /** Background scan; if one is running, another follows it so nothing asked for is lost. */
    fun rescan() {
        if (scanLock.isLocked) {
            again = true
            return
        }
        scope.launch { scanNow() }
    }

    /** Scans and reports what changed. Waits for a scan already running, then does a fresh one. */
    suspend fun scanNow(): Scanner.Result {
        val r = scanLock.withLock {
            _scanning.value = true
            again = false
            try {
                scanner.scan(settings.flow.first().musicFolders)
            } finally {
                lastScanAt = System.currentTimeMillis()
                _scanning.value = false
            }
        }
        if (again) rescan()
        return r
    }
}
