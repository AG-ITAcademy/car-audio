package ro.ag.caraudio

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.ag.caraudio.ui.Actions
import ro.ag.caraudio.ui.AppRoot
import ro.ag.caraudio.ui.AppState

/** Connects the screens (ui package) to YouTube and to the playback service. */
class MainActivity : ComponentActivity(), Actions {

    private val state = AppState()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    /**
     * The playlist URL that the queue was made from, while its later pages still load.
     * Those pages are added to the queue only if the queue was not changed since then.
     */
    private var queueSource: String? = null

    /** Queue indices of the "Up next" list (they follow the shuffle order). */
    private var upNextIndices: List<Int> = emptyList()

    /** For the pop-up with the song name when a new song starts. */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /** A link that arrived by "Share" or "Open with" before the controller was ready. */
    private var pendingUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        pendingUrl = linkFrom(intent)
        state.artistsOnly = Library.artistsOnly
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            // The position of the song, for the progress bars.
            LaunchedEffect(Unit) {
                while (true) {
                    controller?.let {
                        state.positionMs = it.currentPosition
                        state.durationMs = it.duration.coerceAtLeast(0)
                    }
                    delay(500)
                }
            }
            AppRoot(state, this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkFrom(intent)?.let { url ->
            if (controller != null) play(YouTube.item(url, url, null, null, 0)) else pendingUrl = url
        }
    }

    override fun onStart() {
        super.onStart()
        state.recent = Library.recent
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = updateState(player)
                override fun onPlayerError(error: PlaybackException) {
                    state.message = "Playback error: ${error.errorCodeName}"
                }
            })
            updateState(c)
            pendingUrl?.let { play(YouTube.item(it, it, null, null, 0)) }
            pendingUrl = null
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        controller = null
        super.onStop()
    }

    private fun updateState(player: Player) {
        state.nowPlaying = player.currentMediaItem
        state.isPlaying = player.isPlaying
        state.isBuffering = player.playbackState == Player.STATE_BUFFERING && player.playWhenReady
        if (state.isPlaying && state.message == "Loading…") state.message = null
        state.queueSize = player.mediaItemCount
        state.queueIndex = player.currentMediaItemIndex
        state.shuffle = player.shuffleModeEnabled
        state.repeatMode = player.repeatMode
        state.recent = Library.recent

        // "Up next": the next 15 items in play order (this follows shuffle).
        val timeline = player.currentTimeline
        val indices = mutableListOf<Int>()
        var i = player.currentMediaItemIndex
        while (!timeline.isEmpty && indices.size < 15) {
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (i == C.INDEX_UNSET) break
            indices += i
        }
        upNextIndices = indices
        state.upNext = indices.map { player.getMediaItemAt(it) }
    }

    // ------------------------------------------------------------ search

    override fun setQuery(text: String) {
        state.query = text
    }

    override fun search() {
        val q = state.query.trim()
        if (q.isEmpty()) return
        state.resultsMood = null
        runSearch(q, state.searchType)
    }

    override fun openMood(mood: Mood) {
        state.resultsMood = mood
        state.query = ""
        state.searchType = YouTube.SearchType.PLAYLISTS
        runSearch(mood.query, YouTube.SearchType.PLAYLISTS)
    }

    private fun runSearch(q: String, type: YouTube.SearchType) {
        val filter = state.artistsOnly
        state.loading = true
        state.message = null
        state.openPlaylist = null
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { YouTube.search(q, type, filter) } }
                .onSuccess {
                    state.results = it
                    Library.lastSearch = it
                    if (it.isEmpty()) state.message = "Nothing found. Try other words, or turn off \"Artists only\"."
                }
                .onFailure { state.message = "Search failed: ${it.message}" }
            state.loading = false
        }
    }

    /** Search again with the same words or mood (after a filter change). */
    private fun searchAgain() {
        val mood = state.resultsMood
        when {
            mood != null -> runSearch(mood.query, state.searchType)
            state.query.isNotBlank() -> runSearch(state.query.trim(), state.searchType)
        }
    }

    override fun setSearchType(type: YouTube.SearchType) {
        if (state.searchType == type) return
        state.searchType = type
        searchAgain()
    }

    override fun toggleArtistsOnly() {
        state.artistsOnly = !state.artistsOnly
        Library.artistsOnly = state.artistsOnly
        searchAgain()
    }

    override fun clearResults() {
        state.results = emptyList()
        state.resultsMood = null
        state.query = ""
        state.message = null
    }

    // ------------------------------------------------------------ playlists

    /**
     * Open a playlist. With [autoplay], also play it: the first song starts as soon as
     * the first page of the playlist arrives, and the later pages are added to the queue.
     */
    override fun openPlaylist(playlist: MediaItem, autoplay: Boolean) {
        val url = playlist.mediaId
        state.openPlaylist = playlist
        state.playlistVideos = emptyList()
        state.loading = true
        state.startingPlaylist = autoplay
        state.message = null
        var started = false
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    Library.playlist(url) { page ->
                        runOnUiThread {
                            if (state.openPlaylist == playlist) state.playlistVideos = state.playlistVideos + page
                            if (!autoplay || page.isEmpty()) return@runOnUiThread
                            if (!started) {
                                started = true
                                state.startingPlaylist = false
                                playAll(page)
                                queueSource = url
                            } else if (queueSource == url) {
                                controller?.addMediaItems(page)
                            }
                        }
                    }
                }
            }
                .onSuccess { all ->
                    if (state.openPlaylist == playlist) state.playlistVideos = all
                    if (autoplay && all.isEmpty()) {
                        state.message = if (state.artistsOnly) "No songs left in this playlist with \"Artists only\" on"
                        else "This playlist is empty"
                    }
                }
                .onFailure { state.message = "Cannot load the playlist: ${it.message}" }
            state.loading = false
            state.startingPlaylist = false
            if (queueSource == url) queueSource = null
        }
    }

    override fun closePlaylist() {
        state.openPlaylist = null
        state.message = null
    }

    /** Add all videos of a playlist to the end of the queue. */
    override fun queuePlaylist(playlist: MediaItem) {
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { Library.playlist(playlist.mediaId) } }
                .onSuccess { videos ->
                    val c = controller ?: return@onSuccess
                    if (c.mediaItemCount == 0) return@onSuccess playAll(videos)
                    c.addMediaItems(videos)
                    state.message = "Added ${videos.size} songs to the queue"
                }
                .onFailure { state.message = "Cannot load the playlist: ${it.message}" }
        }
    }

    // ------------------------------------------------------------ playback

    /** Play a list of videos, starting at [start]. With [shuffle], start at a random video. */
    override fun playAll(videos: List<MediaItem>, start: Int, shuffle: Boolean) {
        val c = controller ?: return
        if (videos.isEmpty()) return
        queueSource = null
        state.message = "Loading…"
        c.shuffleModeEnabled = shuffle
        c.setMediaItems(videos, if (shuffle) videos.indices.random() else start, C.TIME_UNSET)
        c.prepare()
        c.play()
    }

    override fun play(item: MediaItem) {
        val c = controller ?: return
        state.message = "Loading…"
        queueSource = null
        c.shuffleModeEnabled = false
        c.setMediaItem(item)
        c.prepare()
        c.play()
    }

    override fun addToQueue(item: MediaItem) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(item)
        c.addMediaItem(item)
        state.message = "Added to the queue"
    }

    override fun playQueueItem(offsetFromCurrent: Int) {
        val c = controller ?: return
        val index = upNextIndices.getOrNull(offsetFromCurrent - 1) ?: return
        c.seekTo(index, C.TIME_UNSET)
        resume(c)
    }

    /**
     * Next item. The playback service decides with the real queue (see PlaybackService
     * onCustomCommand), and it always continues playback.
     */
    override fun next() = sendSkip(PlaybackService.CMD_NEXT)

    /** Start of the item; a second press within 4 seconds goes to the previous item. */
    override fun previous() = sendSkip(PlaybackService.CMD_PREVIOUS)

    private fun sendSkip(action: String) {
        val c = controller ?: return
        val future = c.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), Bundle.EMPTY)
        future.addListener({
            val result = runCatching { future.get() }.getOrNull()
            if (result == null || result.resultCode != SessionResult.RESULT_SUCCESS) {
                state.message = "Skip failed (code ${result?.resultCode})"
                return@addListener
            }
            val extras = result.extras
            if (!extras.getBoolean(PlaybackService.EXTRA_MOVED, true)) {
                val index = extras.getInt(PlaybackService.EXTRA_INDEX) + 1
                val count = extras.getInt(PlaybackService.EXTRA_COUNT)
                state.message = "No next song in the queue ($index / $count)"
            }
        }, MoreExecutors.directExecutor())
    }

    override fun playPause() {
        val c = controller ?: return
        state.message = null
        if (c.isPlaying) c.pause() else resume(c)
    }

    private fun resume(c: Player) {
        if (c.playbackState == Player.STATE_IDLE || c.playbackState == Player.STATE_ENDED) c.prepare()
        c.play()
    }

    override fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    override fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    override fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    override fun openNowPlaying() {
        state.showNowPlaying = true
    }

    override fun closeNowPlaying() {
        state.showNowPlaying = false
    }

    private fun linkFrom(intent: Intent?): String? {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return null
        return Regex("""https?://\S*(youtube\.com|youtu\.be)\S*""").find(text)?.value
    }
}
