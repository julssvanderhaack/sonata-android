package io.github.julssvanderhaack.sonata.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.julssvanderhaack.sonata.eq.EqSettings
import io.github.julssvanderhaack.sonata.library.LibraryFilter
import io.github.julssvanderhaack.sonata.library.SongSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

data class AppSettings(
    val theme: String = DEFAULT_THEME,
    val dynamicColor: Boolean = false,
    val filter: LibraryFilter = LibraryFilter(),
    val songSort: SongSort = SongSort.TITLE,
    /** lrclib.net lookups; off until the user turns them on (only title, artist, album and length are sent). */
    val onlineLyrics: Boolean = false,
    /** SAF tree the user granted for reading .lrc files next to the songs. */
    val lyricsTree: String? = null,
    /** SAF trees the library is built from, walked with their subfolders. */
    val musicFolders: List<String> = emptyList(),
) {
    companion object {
        const val DEFAULT_THEME = "cobalt2"
    }
}

class Settings(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val minDuration = intPreferencesKey("min_duration_s")
        val minSize = intPreferencesKey("min_size_kb")
        val patterns = stringPreferencesKey("exclude_patterns")
        val folders = stringSetPreferencesKey("excluded_folders")
        val ringtones = booleanPreferencesKey("include_ringtones")
        val notifications = booleanPreferencesKey("include_notifications")
        val alarms = booleanPreferencesKey("include_alarms")
        val recordings = booleanPreferencesKey("include_recordings")
        val podcasts = booleanPreferencesKey("include_podcasts")
        val audiobooks = booleanPreferencesKey("include_audiobooks")
        val songSort = stringPreferencesKey("song_sort")
        val onlineLyrics = booleanPreferencesKey("online_lyrics")
        val lyricsTree = stringPreferencesKey("lyrics_tree")
        val eq = stringPreferencesKey("eq")
        val musicFolders = stringPreferencesKey("music_folders")
    }

    val flow: Flow<AppSettings> = context.store.data.map { p ->
        val d = LibraryFilter()
        AppSettings(
            theme = p[K.theme] ?: AppSettings.DEFAULT_THEME,
            dynamicColor = p[K.dynamic] ?: false,
            filter = LibraryFilter(
                minDurationSec = p[K.minDuration] ?: d.minDurationSec,
                minSizeKb = p[K.minSize] ?: d.minSizeKb,
                excludePatterns = p[K.patterns]?.lines()?.filter { it.isNotBlank() } ?: d.excludePatterns,
                excludedFolders = p[K.folders] ?: d.excludedFolders,
                includeRingtones = p[K.ringtones] ?: d.includeRingtones,
                includeNotifications = p[K.notifications] ?: d.includeNotifications,
                includeAlarms = p[K.alarms] ?: d.includeAlarms,
                includeRecordings = p[K.recordings] ?: d.includeRecordings,
                includePodcasts = p[K.podcasts] ?: d.includePodcasts,
                includeAudiobooks = p[K.audiobooks] ?: d.includeAudiobooks,
            ),
            songSort = p[K.songSort]?.let { runCatching { SongSort.valueOf(it) }.getOrNull() } ?: SongSort.TITLE,
            onlineLyrics = p[K.onlineLyrics] ?: false,
            lyricsTree = p[K.lyricsTree],
            musicFolders = p[K.musicFolders]?.lines()?.filter { it.isNotBlank() } ?: emptyList(),
        )
    }

    /** Kept apart from [flow]: the EQ changes many times a second while dragging. */
    val eq: Flow<EqSettings> = context.store.data.map { EqSettings.parse(it[K.eq]) }

    suspend fun setEq(s: EqSettings) = set { it[K.eq] = s.serialize() }
    suspend fun setOnlineLyrics(on: Boolean) = set { it[K.onlineLyrics] = on }
    suspend fun setLyricsTree(uri: String?) = set { if (uri == null) it.remove(K.lyricsTree) else it[K.lyricsTree] = uri }

    suspend fun setMusicFolders(trees: List<String>) = set { it[K.musicFolders] = trees.joinToString("\n") }

    private suspend fun set(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.store.edit { block(it) }
    }

    suspend fun setTheme(name: String) = set { it[K.theme] = name }
    suspend fun setDynamicColor(on: Boolean) = set { it[K.dynamic] = on }
    suspend fun setSongSort(sort: SongSort) = set { it[K.songSort] = sort.name }

    suspend fun setFilter(f: LibraryFilter) = set {
        it[K.minDuration] = f.minDurationSec
        it[K.minSize] = f.minSizeKb
        it[K.patterns] = f.excludePatterns.joinToString("\n")
        it[K.folders] = f.excludedFolders
        it[K.ringtones] = f.includeRingtones
        it[K.notifications] = f.includeNotifications
        it[K.alarms] = f.includeAlarms
        it[K.recordings] = f.includeRecordings
        it[K.podcasts] = f.includePodcasts
        it[K.audiobooks] = f.includeAudiobooks
    }

    suspend fun resetFilter() = set { p ->
        listOf<Preferences.Key<*>>(
            K.minDuration, K.minSize, K.patterns, K.folders, K.ringtones, K.notifications,
            K.alarms, K.recordings, K.podcasts, K.audiobooks,
        ).forEach { p.remove(it) }
    }
}
