package ro.ag.caraudio

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Plays audio in the background. It also gives the media notification, lock screen and
 * Bluetooth controls, and the Android Auto browse tree and voice search.
 */
class PlaybackService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private val executor: ListeningExecutorService =
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(3))
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        val dataSource = ResolvingDataSource.Factory(http) { spec ->
            spec.withAdditionalHeaders(YouTube.streamHeaders(spec.uri))
        }

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(PlayerListener())

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing when the app is swiped away, but stop if nothing plays.
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session.release()
        player.release()
        executor.shutdownNow()
        super.onDestroy()
    }

    private inner class PlayerListener : Player.Listener {
        /** Stream URLs expire after some hours. On an error, get a new URL one time. */
        private var retriedId: String? = null

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.let(Library::addRecent)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) retriedId = null
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Playback error", error)
            val item = player.currentMediaItem ?: return
            if (retriedId == item.mediaId) return
            retriedId = item.mediaId
            val index = player.currentMediaItemIndex
            val position = player.currentPosition
            executor.execute {
                val fresh = runCatching { YouTube.resolve(item) }
                    .onFailure { Log.w(TAG, "Cannot resolve ${item.mediaId} again", it) }
                    .getOrNull() ?: return@execute
                mainHandler.post {
                    if (index >= player.mediaItemCount) return@post
                    player.replaceMediaItem(index, fresh)
                    player.seekTo(index, position)
                    player.prepare()
                    player.play()
                }
            }
        }
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            immediate(LibraryResult.ofItem(folder(ROOT, "Car Audio"), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val children = when (parentId) {
                ROOT -> listOf(folder(RECENT, "Recently played"), folder(SEARCH, "Last search"))
                RECENT -> Library.recent
                SEARCH -> Library.lastSearch
                else -> emptyList()
            }
            return immediate(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = Library.find(mediaId)
            return immediate(
                if (item != null) LibraryResult.ofItem(item, null)
                else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = executor.submit<LibraryResult<Void>> {
            Library.lastSearch = YouTube.search(query)
            session.notifySearchResultChanged(browser, query, Library.lastSearch.size, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            immediate(LibraryResult.ofItemList(ImmutableList.copyOf(Library.lastSearch), params))

        /** Controllers send only the mediaId (the video URL). Find the audio stream here. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = executor.submit<MutableList<MediaItem>> {
            val resolved = mediaItems.mapNotNull { item ->
                runCatching { resolveRequest(item) }
                    .onFailure { Log.w(TAG, "Cannot resolve ${item.mediaId}", it) }
                    .getOrNull()
            }
            if (resolved.isEmpty()) throw IOException("No playable item")
            resolved.toMutableList()
        }

        /** Bluetooth "play" when the app is not running: continue the last track. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: ControllerInfo,
        ): ListenableFuture<MediaItemsWithStartPosition> = executor.submit<MediaItemsWithStartPosition> {
            val last = Library.recent.firstOrNull() ?: throw IOException("Nothing played yet")
            MediaItemsWithStartPosition(listOf(YouTube.resolve(last)), 0, C.TIME_UNSET)
        }
    }

    /** A plain item, or a voice request from Android Auto ("play <something> on Car Audio"). */
    private fun resolveRequest(item: MediaItem): MediaItem {
        if (item.mediaId.isNotEmpty() && item.mediaId.startsWith("http")) {
            return YouTube.resolve(item)
        }
        val query = item.requestMetadata.searchQuery
            ?: throw IOException("Unknown item ${item.mediaId}")
        val results = YouTube.search(query)
        Library.lastSearch = results
        return YouTube.resolve(results.firstOrNull() ?: throw IOException("No result for $query"))
    }

    private fun folder(id: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()

    private fun <T> immediate(value: T): ListenableFuture<T> =
        com.google.common.util.concurrent.Futures.immediateFuture(value)

    companion object {
        private const val TAG = "CarAudio"
        private const val ROOT = "root"
        private const val RECENT = "recent"
        private const val SEARCH = "search"
    }
}
