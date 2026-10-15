package ro.ag.caraudio

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
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

    override fun onCreate() {
        super.onCreate()

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        // An item can have a YouTube page URL as its URI. Here, just before the player reads
        // it, change it to the audio stream URL. This runs on a background thread.
        val dataSource = ResolvingDataSource.Factory(http) { spec ->
            val real = if (YouTube.isYouTubePage(spec.uri)) {
                spec.withUri(Uri.parse(YouTube.streamUrl(spec.uri.toString())))
            } else {
                spec
            }
            real.withAdditionalHeaders(YouTube.streamHeaders(real.uri))
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
        /**
         * Stream URLs expire after some hours. On an error, get a new URL one time.
         * If it fails again (for example a private video), go to the next item.
         */
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
            val index = player.currentMediaItemIndex
            if (retriedId == item.mediaId) {
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
                return
            }
            retriedId = item.mediaId
            YouTube.invalidate(item.mediaId)
            val position = player.currentPosition
            player.replaceMediaItem(index, YouTube.lazy(item))
            player.seekTo(index, position)
            player.prepare()
            player.play()
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
                // A playlist: load its videos.
                else -> return executor.submit<LibraryResult<ImmutableList<MediaItem>>> {
                    LibraryResult.ofItemList(ImmutableList.copyOf(Library.playlist(parentId)), params)
                }
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
            Library.lastSearch = YouTube.search(query, YouTube.SearchType.PLAYLISTS)
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

        /** Controllers send only the mediaId (the YouTube URL). Make playable items here. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = executor.submit<MutableList<MediaItem>> {
            val single = mediaItems.size == 1
            val resolved = mediaItems.flatMap { item ->
                runCatching { expand(item, single) }
                    .onFailure { Log.w(TAG, "Cannot resolve ${item.mediaId}", it) }
                    .getOrDefault(emptyList())
            }
            if (resolved.isEmpty()) throw IOException("No playable item")
            resolved.toMutableList()
        }

        /**
         * In Android Auto, a tap on a video in a playlist folder sends only that video.
         * Play the rest of the playlist after it.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> {
            if (mediaItems.size == 1 && controller.packageName != packageName) {
                Library.playlistContaining(mediaItems[0].mediaId)?.let { (items, index) ->
                    return immediate(
                        MediaItemsWithStartPosition(items.map(YouTube::lazy), index, C.TIME_UNSET)
                    )
                }
            }
            return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
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

    /**
     * One request to one or more playable items: a video, a playlist (all its videos),
     * or a voice request from Android Auto ("play <something> on Car Audio").
     */
    private fun expand(item: MediaItem, single: Boolean): List<MediaItem> {
        val id = item.mediaId
        if (id.startsWith("http")) {
            if (Uri.parse(id).path == "/playlist") return Library.playlist(id).map(YouTube::lazy)
            // For one item, get the metadata and stream now: a shared link has no title yet.
            return listOf(if (single) YouTube.resolve(item) else YouTube.lazy(item))
        }
        val query = item.requestMetadata.searchQuery
            ?: throw IOException("Unknown item $id")
        val playlists = YouTube.search(query, YouTube.SearchType.PLAYLISTS)
        Library.lastSearch = playlists
        playlists.firstOrNull()?.let { return Library.playlist(it.mediaId).map(YouTube::lazy) }
        val video = YouTube.search(query, YouTube.SearchType.VIDEOS).firstOrNull()
            ?: throw IOException("No result for $query")
        return listOf(YouTube.resolve(video))
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
