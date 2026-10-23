package io.github.julssvanderhaack.sonata

import io.github.julssvanderhaack.sonata.library.LibraryFilter
import io.github.julssvanderhaack.sonata.library.Scanner
import io.github.julssvanderhaack.sonata.library.SongSort
import io.github.julssvanderhaack.sonata.library.Track
import io.github.julssvanderhaack.sonata.library.folderTree
import io.github.julssvanderhaack.sonata.library.genresWorthShowing
import io.github.julssvanderhaack.sonata.library.groupAlbums
import io.github.julssvanderhaack.sonata.library.groupArtists
import io.github.julssvanderhaack.sonata.library.sectionLetter
import io.github.julssvanderhaack.sonata.library.songComparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private var nextId = 1L

private fun track(
    path: String,
    title: String = path.substringAfterLast('/').substringBeforeLast('.'),
    artist: String? = null,
    album: String? = null,
    albumArtist: String? = null,
    genre: String? = null,
    durationS: Int = 200,
    sizeKb: Int = 4000,
    trackNo: Int = 0,
    kinds: Int = 0,
    volume: String = "external_primary",
) = Track(
    id = nextId++, title = title, artist = artist, albumArtist = albumArtist, album = album, genre = genre,
    year = 0, trackNo = trackNo, discNo = 0, durationMs = durationS * 1000L, size = sizeKb * 1024L, mime = "audio/mpeg",
    volume = volume, relativePath = path.substringBeforeLast('/', "") .let { if (it.isEmpty()) "" else "$it/" },
    fileName = path.substringAfterLast('/'), dateAdded = 0, dateModified = 0, kinds = kinds,
)

class LibraryTest {
    private val keep = LibraryFilter().compile()

    @Test
    fun defaultFilterDropsChatAudioAndShortSounds() {
        assertTrue(keep(track("Music/Queen/Innuendo/01 Innuendo.mp3")))
        assertTrue(keep(track("Download/song.flac")))
        assertFalse(keep(track("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/202542/PTT-20261012-WA0003.opus", durationS = 40)))
        assertFalse(keep(track("WhatsApp/Media/WhatsApp Audio/AUD-20200101-WA0001.mp3")))
        assertFalse(keep(track("Download/PTT-20261012-WA0003.opus", durationS = 40)))
        assertFalse(keep(track("Telegram/Telegram Audio/voice.ogg")))
        assertFalse(keep(track("Recordings/Call/llamada.m4a")))
        assertFalse(keep(track("Music/blip.ogg", durationS = 1, sizeKb = 20)))
        assertFalse(keep(track("Music/notif.ogg", kinds = Track.Kind.NOTIFICATION.bit)))
        assertTrue(keep(track("Podcasts/ep1.mp3", kinds = Track.Kind.PODCAST.bit)))
    }

    @Test
    fun unmeasuredDurationFallsBackToSize() {
        assertTrue(keep(track("Music/a.mp3", durationS = 0)))
        assertFalse(keep(track("Music/a.mp3", durationS = 0, sizeKb = 5)))
    }

    @Test
    fun folderExclusion() {
        val f = LibraryFilter(excludedFolders = setOf("external_primary:Music/Audiolibros/")).compile()
        assertFalse(f(track("Music/Audiolibros/Quijote/c1.mp3")))
        assertTrue(f(track("Music/Audio/x.mp3")))
        // bare volume key = files at the root only
        val root = LibraryFilter(excludedFolders = setOf("external_primary:")).compile()
        assertFalse(root(track("x.mp3")))
        assertTrue(root(track("Music/x.mp3")))
    }

    @Test
    fun albumsWithoutAlbumArtistSplitByFolder() {
        val ts = listOf(
            track("Music/Queen/GH/1.mp3", artist = "Queen", album = "Greatest Hits"),
            track("Music/Queen/GH/2.mp3", artist = "Queen", album = "Greatest Hits"),
            track("Music/ABBA/GH/1.mp3", artist = "ABBA", album = "Greatest Hits"),
            track("Music/Mix/1.mp3", artist = "A", album = "Mix", albumArtist = "Varios"),
            track("Music/Mix/2.mp3", artist = "B", album = "Mix", albumArtist = "Varios"),
            track("Music/Recopilatorio/1.mp3", artist = "A", album = "Éxitos"),
            track("Music/Recopilatorio/2.mp3", artist = "B", album = "Exitos"),
        )
        val albums = groupAlbums(ts)
        assertEquals(4, albums.size)
        val gh = albums.filter { it.title == "Greatest Hits" }.map { it.artist }.toSet()
        assertEquals(setOf("Queen", "ABBA"), gh)
        val exitos = albums.single { it.title.endsWith("xitos") }
        assertTrue(exitos.various)
        assertNull(exitos.artist)
    }

    @Test
    fun untaggedFilesGroupByFolder() {
        val ts = listOf(track("Music/Demo tapes/a.mp3", album = "<unknown>", artist = "<unknown>"))
        assertEquals("Demo tapes", groupAlbums(ts).single().title)
        assertNull(groupArtists(ts).single().name)
    }

    @Test
    fun artistsUseAlbumArtist() {
        val ts = listOf(
            track("M/1.mp3", artist = "Rosalía feat. J Balvin", albumArtist = "Rosalía", album = "X"),
            track("M/2.mp3", artist = "Rosalía", album = "X"),
        )
        assertEquals(listOf("Rosalía"), groupArtists(ts).map { it.name })
    }

    @Test
    fun spanishSorting() {
        val ts = listOf("Zorro", "ñu", "Árbol", "nube", "avión").map { track("M/$it.mp3", title = it) }
        assertEquals(listOf("Árbol", "avión", "nube", "ñu", "Zorro"), ts.sortedWith(songComparator(SongSort.TITLE)).map { it.title })
        assertEquals("A", sectionLetter("Árbol"))
        assertEquals("Ñ", sectionLetter("ñu"))
        assertEquals("#", sectionLetter("50 Cent"))
    }

    @Test
    fun folderTreeCompactsChains() {
        val ts = listOf(
            track("Music/Rock/Metallica/Black/1.mp3"),
            track("Music/Rock/Metallica/Black/2.mp3"),
            track("Music/Rock/Queen/1.mp3"),
            track("Download/x.mp3"),
        )
        val roots = folderTree(ts) { "Interno" }
        assertEquals(listOf("Download", "Music/Rock"), roots.map { it.name })
        val rock = roots[1]
        assertEquals(listOf("Metallica/Black", "Queen"), rock.children.map { it.name })
        assertEquals(3, rock.total)
        assertEquals("external_primary:Music/Rock/Metallica/Black/", rock.children[0].key)
    }

    @Test
    fun folderTreeShowsVolumesWhenThereAreSeveral() {
        val ts = listOf(track("Music/a.mp3"), track("Music/b.mp3", volume = "1234-abcd"))
        val roots = folderTree(ts) { if (it == "external_primary") "Interno" else "SD" }
        assertEquals(listOf("Interno", "SD"), roots.map { it.name })
    }

    @Test
    fun genresTabNeedsTaggedLibrary() {
        val few = List(20) { track("M/$it.mp3") } + track("M/g.mp3", genre = "Rock")
        assertFalse(genresWorthShowing(few))
        val many = List(5) { track("M/$it.mp3") } + track("M/g.mp3", genre = "Rock")
        assertTrue(genresWorthShowing(many))
    }
}

class MojibakeTest {
    @Test
    fun repairsUtf8ReadAsLatin1() {
        val broken = String("Paco de Lucía".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        assertEquals("Paco de Lucía", io.github.julssvanderhaack.sonata.library.fixMojibake(broken))
    }

    @Test
    fun leavesRealTextAlone() {
        for (s in listOf("Ángel", "Café Tacvba", "Björk", "Rosalía", "日本語", "plain")) {
            assertEquals(s, io.github.julssvanderhaack.sonata.library.fixMojibake(s))
        }
    }
}

class AlbumMergeTest {
    @Test
    fun partlyTaggedAlbumStaysWhole() {
        val ts = listOf(
            track("Music/Rosalía/Motomami/01 Saoko.mp3", artist = "Rosalía", album = "Motomami"),
            track("Music/Rosalía/Motomami/02 Candy.mp3", artist = "Rosalía feat. X", album = "Motomami", albumArtist = "Rosalía"),
        )
        assertEquals(1, groupAlbums(ts).size)
        val artists = groupArtists(ts)
        assertEquals(listOf("Rosalía"), artists.map { it.name })
        assertEquals(1, artists.single().albums.size)
    }

    @Test
    fun discFoldersMergeByAlbumArtist() {
        val ts = listOf(
            track("Music/Pink Floyd/The Wall/CD1/1.mp3", artist = "Pink Floyd", album = "The Wall", albumArtist = "Pink Floyd"),
            track("Music/Pink Floyd/The Wall/CD2/1.mp3", artist = "Pink Floyd", album = "The Wall", albumArtist = "Pink Floyd"),
        )
        assertEquals(1, groupAlbums(ts).size)
    }
}

class ScanDiffTest {
    @Test
    fun renamedMovedAndRetaggedSongsAreUpdatedEvenWithTheSameDate() {
        val a = track("Music/Ñandú/Zapateado.mp3")
        val b = track("Music/Recopilatorio/02 Dos.mp3")
        val c = track("Music/Recopilatorio/03 Tres.mp3", artist = "Viejo")
        val d = track("Music/Borrada.mp3")
        val known = listOf(a, b, c, d)
        // MediaStore keeps id and date_modified on a rename or a folder move
        val moved = a.copy(relativePath = "Music/Carpeta movida/")
        val renamed = b.copy(fileName = "02 Dos RENOMBRADA.mp3")
        val retagged = c.copy(artist = "Nuevo")
        val added = track("Music/Nueva.mp3")
        val d2 = Scanner.diff(listOf(moved, renamed, retagged, added), known)
        assertEquals(setOf(moved, renamed, retagged, added), d2.upsert.toSet())
        assertEquals(1, d2.added)
        assertEquals(listOf(d.id), d2.delete)
    }

    @Test
    fun unchangedLibraryWritesNothing() {
        val known = listOf(track("Music/a.mp3"), track("Music/b.mp3"))
        val d = Scanner.diff(known.map { it.copy() }, known)
        assertTrue(d.upsert.isEmpty() && d.delete.isEmpty())
    }
}
