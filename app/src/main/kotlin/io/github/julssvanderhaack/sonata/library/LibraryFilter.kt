package io.github.julssvanderhaack.sonata.library

/**
 * What counts as a song. Patterns are globs ('*' = anything, '?' = one
 * character, case-insensitive) matched against the whole path relative to
 * the volume, e.g. "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/202542/PTT-20261012-WA0003.opus".
 */
data class LibraryFilter(
    val minDurationSec: Int = 10,
    val minSizeKb: Int = 100,
    val excludePatterns: List<String> = DEFAULT_PATTERNS,
    /** Folder keys ("volume:relative/path/") hidden from the Carpetas screen; subfolders go with them. */
    val excludedFolders: Set<String> = emptySet(),
    val includeRingtones: Boolean = false,
    val includeNotifications: Boolean = false,
    val includeAlarms: Boolean = false,
    val includeRecordings: Boolean = false,
    val includePodcasts: Boolean = true,
    val includeAudiobooks: Boolean = true,
) {
    fun compile(): (Track) -> Boolean {
        val regexes = excludePatterns.mapNotNull { globToRegex(it) }
        val folders = excludedFolders.toList()
        var excludedKinds = 0
        if (!includeRingtones) excludedKinds = excludedKinds or Track.Kind.RINGTONE.bit
        if (!includeNotifications) excludedKinds = excludedKinds or Track.Kind.NOTIFICATION.bit
        if (!includeAlarms) excludedKinds = excludedKinds or Track.Kind.ALARM.bit
        if (!includeRecordings) excludedKinds = excludedKinds or Track.Kind.RECORDING.bit
        if (!includePodcasts) excludedKinds = excludedKinds or Track.Kind.PODCAST.bit
        if (!includeAudiobooks) excludedKinds = excludedKinds or Track.Kind.AUDIOBOOK.bit
        val minMs = minDurationSec * 1000L
        val minBytes = minSizeKb * 1024L
        return { t ->
            // duration 0 = MediaStore hasn't measured it yet; let the size decide
            !(t.durationMs in 1 until minMs) &&
                t.size >= minBytes &&
                (t.kinds and excludedKinds) == 0 &&
                folders.none { hides(it, t.folderKey) } &&
                t.path.let { p -> regexes.none { it.matches(p) } }
        }
    }

    companion object {
        /**
         * Whether excluding folder [excluded] hides [folderKey]: the folder and its subfolders.
         * A bare volume key ("external_primary:") stands for the files at the volume root only,
         * otherwise unticking that entry would hide the whole library.
         */
        fun hides(excluded: String, folderKey: String) =
            if (excluded.endsWith(':')) folderKey == excluded else folderKey.startsWith(excluded)

        val DEFAULT_PATTERNS = listOf(
            "Android/*",          // app folders: WhatsApp, Telegram, podcast apps…
            "*WhatsApp*",         // older WhatsApp layout (/WhatsApp/Media/…)
            "*Telegram*",
            "*PTT-*-WA*",         // WhatsApp voice notes saved elsewhere
            "*AUD-*-WA*",         // WhatsApp audios saved elsewhere
            "Recordings/*",
            "*Call recordings*",
            "MIUI/sound_recorder/*",
            "Notifications/*",
            "Ringtones/*",
            "Alarms/*",
        )

        fun globToRegex(glob: String): Regex? {
            val g = glob.trim()
            if (g.isEmpty()) return null
            val sb = StringBuilder()
            for (c in g) when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(c.toString()))
            }
            return Regex(sb.toString(), setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        }
    }
}
