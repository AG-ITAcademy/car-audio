package ro.ag.caraudio

import java.io.IOException

/**
 * Chooses what to play for a mood or a search: the first playlist that gives videos.
 *
 * A playlist can fail to load (for example, YouTube sends a page layout that NewPipe
 * cannot read yet). That playlist is logged and skipped, and the next one is tried.
 * When no playlist gives videos, a video search is the fallback.
 *
 * Plain Kotlin (no Android), so that JVM unit tests can run it with fake loaders.
 */
object PlaylistPicker {
    /** How many playlists to try before the fallback. */
    const val MAX_TRIES = 5

    /** A mood: the first playable playlist, else all the songs of a video search. */
    fun <T> forMood(
        playlistUrls: List<String>,
        moodTitle: String,
        load: (String) -> List<T>,
        searchSongs: () -> List<T>,
        onFailure: (String, Exception) -> Unit = { _, _ -> },
    ): List<T> = pick(playlistUrls, moodTitle, load, searchSongs, onFailure)

    /** A search: the first playable playlist, else the first video of a video search. */
    fun <T> forSearch(
        playlistUrls: List<String>,
        words: String,
        load: (String) -> List<T>,
        searchVideos: () -> List<T>,
        onFailure: (String, Exception) -> Unit = { _, _ -> },
    ): List<T> = pick(playlistUrls, words, load, { searchVideos().take(1) }, onFailure)

    /** The videos of the first of [urls] (at most [MAX_TRIES]) that loads and is not empty. */
    fun <T> firstPlayable(
        urls: List<String>,
        load: (String) -> List<T>,
        onFailure: (String, Exception) -> Unit,
    ): List<T>? {
        for (url in urls.take(MAX_TRIES)) {
            val videos = try {
                load(url)
            } catch (e: Exception) {
                onFailure(url, e)
                continue
            }
            if (videos.isNotEmpty()) return videos
        }
        return null
    }

    private fun <T> pick(
        urls: List<String>,
        what: String,
        load: (String) -> List<T>,
        fallback: () -> List<T>,
        onFailure: (String, Exception) -> Unit,
    ): List<T> {
        firstPlayable(urls, load, onFailure)?.let { return it }
        val videos = try {
            fallback()
        } catch (e: Exception) {
            throw IOException("No playable playlist for $what", e)
        }
        if (videos.isEmpty()) throw IOException("No playable playlist for $what")
        return videos
    }
}
