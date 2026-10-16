package ro.ag.caraudio.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import ro.ag.caraudio.Mood
import ro.ag.caraudio.YouTube

/** All that the screens show. MainActivity changes it; the screens only read it. */
class AppState {
    // Search
    var query by mutableStateOf("")
    var searchType by mutableStateOf(YouTube.SearchType.PLAYLISTS)
    var artistsOnly by mutableStateOf(true)
    var results by mutableStateOf<List<MediaItem>>(emptyList())
    /** The mood of the results, if the search came from a mood tile. */
    var resultsMood by mutableStateOf<Mood?>(null)
    var recent by mutableStateOf<List<MediaItem>>(emptyList())
    var loading by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)

    // Playlist screen
    var openPlaylist by mutableStateOf<MediaItem?>(null)
    var playlistVideos by mutableStateOf<List<MediaItem>>(emptyList())
    var startingPlaylist by mutableStateOf(false)

    // Player
    var nowPlaying by mutableStateOf<MediaItem?>(null)
    var isPlaying by mutableStateOf(false)
    var isBuffering by mutableStateOf(false)
    var queueIndex by mutableStateOf(0)
    var queueSize by mutableStateOf(0)
    var upNext by mutableStateOf<List<MediaItem>>(emptyList())
    var shuffle by mutableStateOf(false)
    var repeatMode by mutableStateOf(Player.REPEAT_MODE_OFF)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)

    /** The full-screen player is open. */
    var showNowPlaying by mutableStateOf(false)
}

/** What the user can do. MainActivity does it. */
interface Actions {
    fun search()
    fun setQuery(text: String)
    fun setSearchType(type: YouTube.SearchType)
    fun toggleArtistsOnly()
    fun openMood(mood: Mood)
    fun clearResults()

    fun openPlaylist(playlist: MediaItem, autoplay: Boolean)
    fun closePlaylist()
    fun queuePlaylist(playlist: MediaItem)

    fun play(item: MediaItem)
    fun playAll(videos: List<MediaItem>, start: Int = 0, shuffle: Boolean = false)
    fun addToQueue(item: MediaItem)
    fun playQueueItem(offsetFromCurrent: Int)

    fun playPause()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun toggleShuffle()
    fun cycleRepeat()

    fun openNowPlaying()
    fun closeNowPlaying()
}
