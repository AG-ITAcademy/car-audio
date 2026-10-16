package ro.ag.caraudio

/**
 * Rules to find songs by real artists, and to get the artist and song name from a
 * YouTube title. YouTube does not mark AI-generated music, so these rules are a best guess.
 */
object MusicFilter {

    /** Words that AI music channels use in titles and channel names. */
    private val AI_WORDS = listOf(
        Regex("""(?<![A-Za-z])A\.?I\.?(?![A-Za-z])"""),
        Regex("""\b(ai|a\.i\.)[ -]?(generated|cover|song|music|made|version|remix|voice|singer|artist|band)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(generated|made|created|composed) (by|with) (ai|a\.i\.)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bartificial intelligence\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(suno|udio)\b""", RegexOption.IGNORE_CASE),
    )

    /** "Artist - Song", with a hyphen, en dash or em dash. */
    private val SEPARATOR = Regex("""\s+[-–—]\s+""")

    /** "(Official Video)", "[Lyrics]", "(Remastered 2011)" and similar. */
    private val NOISE = Regex(
        """\s*[(\[][^)\]]*(official|video|audio|lyric|visuali[sz]er|\bhd\b|\bhq\b|\b4k\b|remaster|\bmv\b|clip)[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )

    fun mentionsAi(vararg texts: String?): Boolean =
        texts.any { text -> text != null && AI_WORDS.any { it.containsMatchIn(text) } }

    /**
     * True if the video is probably a song by a real artist: the title has the
     * "Artist - Song" form, or the channel is an official artist channel.
     */
    fun isArtistSong(title: String?, channel: String?, channelVerified: Boolean): Boolean {
        if (title.isNullOrBlank() || mentionsAi(title, channel)) return false
        val ch = channel.orEmpty()
        return SEPARATOR.split(cleanTitle(title), 2).let { it.size == 2 && it.all(String::isNotBlank) } ||
            channelVerified ||
            ch.endsWith(" - Topic") ||
            ch.contains("VEVO", ignoreCase = true) ||
            Regex("""\bby\s+\S""", RegexOption.IGNORE_CASE).containsMatchIn(title) ||
            (cleanChannel(ch).length >= 3 && title.contains(cleanChannel(ch), ignoreCase = true))
    }

    /** Returns (artist, song). Uses the channel name as the artist when the title has no artist. */
    fun artistAndSong(title: String, channel: String?): Pair<String?, String> {
        val clean = cleanTitle(title)
        val parts = SEPARATOR.split(clean, 2)
        if (parts.size == 2 && parts.all(String::isNotBlank)) return parts[0].trim() to parts[1].trim()
        return channel?.let(::cleanChannel)?.ifBlank { null } to clean
    }

    private fun cleanTitle(title: String): String = title.replace(NOISE, "").trim()

    private fun cleanChannel(channel: String): String =
        channel.removeSuffix(" - Topic")
            .replace(Regex("""VEVO$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\bofficial\b\s*""", RegexOption.IGNORE_CASE), " ")
            .trim()
}
