package io.github.julssvanderhaack.sonata.library

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/*
 * Albums, artists, genres and folders are derived from the filtered track
 * list in memory: a few thousand rows group in milliseconds, and it keeps the
 * database a plain mirror of MediaStore.
 */

enum class SongSort { TITLE, ARTIST, ALBUM, YEAR, DURATION, ADDED }

enum class AlbumSort { TITLE, ARTIST, YEAR }

data class Album(
    val key: String,
    val title: String,
    /** null = unknown artist. */
    val artist: String?,
    val various: Boolean,
    val year: Int,
    val tracks: List<Track>,
) {
    val artTrackId get() = tracks.first().id
}

data class Artist(val key: String, val name: String?, val albums: List<Album>, val tracks: List<Track>)

data class Genre(val name: String, val tracks: List<Track>)

class FolderNode(
    val key: String,
    /** Shown name; compacted chains read "Música/Rock/Metallica". */
    val name: String,
    val children: List<FolderNode>,
    val tracks: List<Track>,
) {
    val total: Int = tracks.size + children.sumOf { it.total }
}

private val collator: Collator = Collator.getInstance(Locale.forLanguageTag("es")).apply { strength = Collator.PRIMARY }

private val compareText = Comparator<String?> { a, b ->
    when {
        a == b -> 0
        a == null -> 1 // unknown last
        b == null -> -1
        else -> collator.compare(a, b)
    }
}

/**
 * Undoes UTF-8 read as Latin-1 ("Paco de LucÃ­a"), which MediaStore does with
 * some FLAC/Vorbis tags. Only applied when the bytes really are valid UTF-8,
 * so genuine Latin-1 text like "Ángel" is left alone.
 */
fun fixMojibake(s: String?): String? {
    if (s == null || s.none { it in '\u00c2'..'\u00f4' } || s.any { it.code > 0xff }) return s
    return try {
        java.nio.charset.StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(s.toByteArray(Charsets.ISO_8859_1)))
            .toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        s
    }
}

fun cleanTag(s: String?): String? = s?.trim()?.takeUnless { it.isEmpty() || it == "<unknown>" }

fun Track.artistOrNull() = cleanTag(artist)
fun Track.albumArtistOrNull() = cleanTag(albumArtist)
fun Track.folderName() = relativePath.trimEnd('/').substringAfterLast('/').ifEmpty { relativePath }
fun Track.albumOrFolder() = cleanTag(album) ?: folderName()

private val byDiscTrack = compareBy<Track>({ it.discNo }, { it.trackNo }).thenBy(compareText) { it.title }

fun songComparator(sort: SongSort): Comparator<Track> = when (sort) {
    SongSort.TITLE -> compareBy(compareText) { it.title }
    SongSort.ARTIST -> compareBy(compareText, Track::artistOrNull).thenBy(compareText) { it.albumOrFolder() }.then(byDiscTrack)
    SongSort.ALBUM -> compareBy(compareText, Track::albumOrFolder).then(byDiscTrack)
    SongSort.YEAR -> compareByDescending<Track> { it.year }.thenBy(compareText) { it.albumOrFolder() }.then(byDiscTrack)
    SongSort.DURATION -> compareByDescending<Track> { it.durationMs }.thenBy(compareText) { it.title }
    SongSort.ADDED -> compareByDescending<Track> { it.dateAdded }.thenBy(compareText) { it.title }
}

/** Fast-scroll letter for a track under [sort]; null when the sort isn't alphabetical. */
fun sectionOf(t: Track, sort: SongSort): String? {
    val s = when (sort) {
        SongSort.TITLE -> t.title
        SongSort.ARTIST -> t.artistOrNull()
        SongSort.ALBUM -> t.albumOrFolder()
        else -> return null
    } ?: return "?"
    return sectionLetter(s)
}

fun sectionLetter(s: String): String {
    val c = foldText(s).firstOrNull { !it.isWhitespace() } ?: return "#"
    return if (c in 'a'..'z') c.uppercase() else if (c == 'ñ') "Ñ" else "#"
}

/** Lower case without accents, for search and sections. 'ñ' survives because Spanish treats it as a letter. */
fun foldText(s: String): String =
    Normalizer.normalize(s.lowercase().replace('ñ', '\u0001'), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace('\u0001', 'ñ')

fun matchesQuery(t: Track, folded: String): Boolean {
    if (folded.isEmpty()) return true
    return listOfNotNull(t.title, t.artist, t.album, t.albumArtist, t.fileName).any { foldText(it).contains(folded) }
}

fun groupAlbums(tracks: List<Track>, sort: AlbumSort = AlbumSort.TITLE): List<Album> {
    // An album is first its name inside one folder, so an album where only some
    // tracks carry an album artist stays whole. Folders that share name and album
    // artist (CD1/, CD2/) are then merged; without an album artist the folder is
    // what tells same-named albums apart ("Greatest Hits").
    val byFolder = tracks.groupBy { foldText(it.albumOrFolder()) + "\u0000" + it.folderKey }
    val groups = LinkedHashMap<String, MutableList<Track>>()
    for ((folderKey, ts) in byFolder) {
        val albumArtist = ts.firstNotNullOfOrNull { it.albumArtistOrNull() }
        val key = if (albumArtist != null) foldText(ts.first().albumOrFolder()) + "\u0000" + foldText(albumArtist) else folderKey
        groups.getOrPut(key) { ArrayList() } += ts
    }
    val albums = groups.map { (key, ts) ->
        val first = ts.first()
        val artists = ts.mapNotNull { it.artistOrNull() }.distinctBy(::foldText)
        val albumArtist = ts.firstNotNullOfOrNull { it.albumArtistOrNull() }
        Album(
            key = key,
            title = first.albumOrFolder(),
            artist = albumArtist ?: artists.singleOrNull(),
            various = albumArtist == null && artists.size > 1,
            year = ts.maxOf { it.year },
            tracks = ts.sortedWith(byDiscTrack),
        )
    }
    return when (sort) {
        AlbumSort.TITLE -> albums.sortedWith(compareBy(compareText) { it.title })
        AlbumSort.ARTIST -> albums.sortedWith(compareBy(compareText, Album::artist).thenByDescending { it.year })
        AlbumSort.YEAR -> albums.sortedWith(compareByDescending<Album> { it.year }.thenBy(compareText) { it.title })
    }
}

fun groupArtists(tracks: List<Track>): List<Artist> {
    // a track inherits its album's album artist, so "Candy" (tagged) and "Saoko" (not) stay together
    val albumArtistOf = HashMap<Long, String>()
    for (a in groupAlbums(tracks)) a.tracks.firstNotNullOfOrNull { it.albumArtistOrNull() }?.let { aa -> a.tracks.forEach { albumArtistOf[it.id] = aa } }
    return tracks.groupBy { t -> (albumArtistOf[t.id] ?: t.artistOrNull())?.let(::foldText) ?: "" }
        .map { (key, ts) ->
            val name = ts.firstNotNullOfOrNull { albumArtistOf[it.id] ?: it.artistOrNull() }
            Artist(
                key = key,
                name = name,
                albums = groupAlbums(ts).sortedByDescending { it.year },
                tracks = ts.sortedWith(songComparator(SongSort.ALBUM)),
            )
        }
        .sortedWith(compareBy(compareText) { it.name })
}

fun groupGenres(tracks: List<Track>): List<Genre> =
    tracks.mapNotNull { t -> cleanTag(t.genre)?.let { it to t } }
        .groupBy({ foldText(it.first) }, { it })
        .map { (_, pairs) -> Genre(pairs.first().first, pairs.map { it.second }.sortedWith(songComparator(SongSort.ARTIST))) }
        .sortedWith(compareBy(compareText) { it.name })

/** The Géneros tab only makes sense when a fair share of the library is tagged. */
fun genresWorthShowing(tracks: List<Track>): Boolean =
    tracks.isNotEmpty() && tracks.count { cleanTag(it.genre) != null } * 10 >= tracks.size

/**
 * Folder tree. Volumes become roots only when there is more than one;
 * folders with no songs of their own and a single subfolder are merged into
 * it, so "Music/Artist/Album" doesn't take three taps.
 */
fun folderTree(tracks: List<Track>, volumeLabel: (String) -> String): List<FolderNode> {
    class Mut(val key: String, val name: String) {
        val children = LinkedHashMap<String, Mut>()
        val tracks = ArrayList<Track>()
    }
    val volumes = LinkedHashMap<String, Mut>()
    for (t in tracks) {
        var node = volumes.getOrPut(t.volume) { Mut("${t.volume}:", volumeLabel(t.volume)) }
        var key = "${t.volume}:"
        for (seg in t.relativePath.split('/').filter { it.isNotEmpty() }) {
            key += "$seg/"
            val k = key
            node = node.children.getOrPut(seg) { Mut(k, seg) }
        }
        node.tracks += t
    }
    fun freeze(m: Mut): FolderNode {
        var cur = m
        var name = m.name
        while (cur.tracks.isEmpty() && cur.children.size == 1) {
            cur = cur.children.values.first()
            name += "/" + cur.name
        }
        return FolderNode(
            key = cur.key,
            name = name,
            children = cur.children.values.map(::freeze).sortedWith(compareBy(compareText) { it.name }),
            tracks = cur.tracks.sortedWith(compareBy(compareText) { it.fileName }),
        )
    }
    val roots = volumes.values.toList()
    if (roots.size == 1) {
        val only = roots.first()
        val top = only.children.values.map(::freeze).sortedWith(compareBy(compareText) { it.name })
        // songs right at the volume root get their own entry
        return if (only.tracks.isEmpty()) top
        else listOf(FolderNode(only.key, only.name, emptyList(), only.tracks)) + top
    }
    return roots.map { v ->
        FolderNode(v.key, v.name, v.children.values.map(::freeze).sortedWith(compareBy(compareText) { it.name }), v.tracks)
    }
}
