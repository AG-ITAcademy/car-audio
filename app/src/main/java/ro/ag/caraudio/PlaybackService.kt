package ro.ag.caraudio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
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
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Plays audio in the background. It also gives the media notification, lock screen and
 * Bluetooth controls, and the Android Auto browse tree and voice search.
 */
class PlaybackService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private val executor: ListeningExecutorService =
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(3))

    /**
     * A separate thread for prefetching, so that it never delays a request to play.
     * Only the latest prefetch is kept: a new song cancels the old prefetch.
     */
    private val prefetcher = Executors.newSingleThreadExecutor()
    private var prefetchTask: Future<*>? = null

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
                val streamUrl = try {
                    YouTube.streamUrl(spec.uri.toString())
                } catch (e: IOException) {
                    throw e
                } catch (e: Exception) {
                    // For example, the video is private, removed or blocked in this country.
                    throw IOException("Cannot get the audio of ${spec.uri}", e)
                }
                spec.withUri(Uri.parse(streamUrl))
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
        session = MediaLibrarySession.Builder(this, CarPlayer(player), LibraryCallback())
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
        prefetcher.shutdownNow()
        super.onDestroy()
    }

    /**
     * Rules for the "back" and "next" buttons (app, notification, steering wheel, Android Auto):
     * - Next: go to the next item in the queue, if there is one.
     * - Back: go to the start of the item. A second press within [DOUBLE_BACK_MS]
     *   goes to the previous item, if there is one.
     * The two commands are always available, so that the car always sends them.
     */
    private class CarPlayer(player: Player) : ForwardingPlayer(player) {
        private var lastBackPress = 0L

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS)
                .build()

        override fun isCommandAvailable(command: Int): Boolean =
            command == Player.COMMAND_SEEK_TO_NEXT || command == Player.COMMAND_SEEK_TO_PREVIOUS ||
                super.isCommandAvailable(command)

        override fun seekToNext() {
            if (!hasNextMediaItem()) return
            seekToNextMediaItem()
            continuePlayback()
        }

        override fun seekToPrevious() {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBackPress < DOUBLE_BACK_MS && hasPreviousMediaItem()) {
                seekToPreviousMediaItem()
                lastBackPress = 0
            } else {
                seekTo(0)
                lastBackPress = now
            }
            continuePlayback()
        }

        /** A skip always plays. After an error the player stops, so prepare it again. */
        private fun continuePlayback() {
            if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) prepare()
            play()
        }
    }

    private inner class PlayerListener : Player.Listener {
        /** The item that already had one retry after an HTTP error. */
        private var retriedId: String? = null

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem ?: return
            Library.addRecent(mediaItem)
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) showTrackPopup(mediaItem)
            prefetchNeighbours()
        }

        /**
         * Get the audio address of the next and previous items now, so that a skip
         * starts at once and not after 2-5 seconds.
         */
        private fun prefetchNeighbours() {
            val neighbours = listOf(player.nextMediaItemIndex, player.previousMediaItemIndex)
                .filter { it != C.INDEX_UNSET }
                .map { player.getMediaItemAt(it).mediaId }
                .filter { it.startsWith("http") }
            prefetchTask?.cancel(true)
            prefetchTask = prefetcher.submit {
                neighbours.forEach { id ->
                    runCatching { YouTube.streamUrl(id) }
                        .onFailure { Log.i(TAG, "Prefetch failed for $id: ${it.message}") }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) retriedId = null
        }

        /**
         * An HTTP error usually means that the stream URL expired: get a new one and try
         * again, one time. Any other error (private, removed or blocked video) does not
         * go away with a retry: go to the next item at once.
         */
        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Playback error ${error.errorCodeName}", error)
            val item = player.currentMediaItem ?: return
            val index = player.currentMediaItemIndex
            val httpError = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
            if (httpError && retriedId != item.mediaId) {
                retriedId = item.mediaId
                YouTube.invalidate(item.mediaId)
                val position = player.currentPosition
                player.replaceMediaItem(index, YouTube.lazy(item))
                player.seekTo(index, position)
                player.prepare()
                player.play()
                return
            }
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        /** Our own app also gets the NEXT and PREVIOUS commands (see [onCustomCommand]). */
        override fun onConnect(
            session: MediaSession,
            controller: ControllerInfo,
        ): MediaSession.ConnectionResult {
            val result = super.onConnect(session, controller)
            if (controller.packageName != packageName) return result
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    result.availableSessionCommands.buildUpon()
                        .add(SessionCommand(CMD_NEXT, Bundle.EMPTY))
                        .add(SessionCommand(CMD_PREVIOUS, Bundle.EMPTY))
                        .build()
                )
                .setAvailablePlayerCommands(result.availablePlayerCommands)
                .build()
        }

        /**
         * Next and previous from the app's buttons. The service decides with the real
         * queue, and returns the queue position so that the app can show it.
         */
        override fun onCustomCommand(
            session: MediaSession,
            controller: ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val p = session.player
            val moved = when (customCommand.customAction) {
                CMD_NEXT -> p.hasNextMediaItem().also { p.seekToNext() }
                CMD_PREVIOUS -> true.also { p.seekToPrevious() }
                else -> return immediate(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            Log.i(TAG, "${customCommand.customAction}: moved=$moved, item ${p.currentMediaItemIndex + 1} of ${p.mediaItemCount}")
            val extras = Bundle().apply {
                putBoolean(EXTRA_MOVED, moved)
                putInt(EXTRA_INDEX, p.currentMediaItemIndex)
                putInt(EXTRA_COUNT, p.mediaItemCount)
            }
            return immediate(SessionResult(SessionResult.RESULT_SUCCESS, extras))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // Tell Android Auto that it can show a search button, and to use lists.
            val extras = Bundle().apply {
                putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
                putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1)
                putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
            }
            val rootParams = LibraryParams.Builder().setExtras(extras).build()
            return immediate(LibraryResult.ofItem(folder(ROOT, "Car Audio"), rootParams))
        }

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
            // Playlists first, then single videos.
            val artistsOnly = Library.artistsOnly
            Library.lastSearch = YouTube.search(query, YouTube.SearchType.PLAYLISTS, artistsOnly) +
                YouTube.search(query, YouTube.SearchType.VIDEOS, artistsOnly)
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
        val artistsOnly = Library.artistsOnly
        val playlists = YouTube.search(query, YouTube.SearchType.PLAYLISTS, artistsOnly)
        Library.lastSearch = playlists
        playlists.firstOrNull()?.let { return Library.playlist(it.mediaId).map(YouTube::lazy) }
        val video = YouTube.search(query, YouTube.SearchType.VIDEOS, artistsOnly).firstOrNull()
            ?: throw IOException("No result for $query")
        return listOf(YouTube.resolve(video))
    }

    /**
     * A short pop-up with the artist and song name, on top of other apps (for example Waze).
     * It closes after some seconds. It shows on the phone screen, not on the Android Auto screen.
     */
    private fun showTrackPopup(item: MediaItem) {
        if (!player.playWhenReady) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(TRACK_CHANNEL, "New song pop-up", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shows the artist and song name when a new song starts"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
        val notification = Notification.Builder(this, TRACK_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(item.mediaMetadata.title)
            .setContentText(item.mediaMetadata.artist)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setTimeoutAfter(TRACK_POPUP_MS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .build()
        manager.notify(TRACK_NOTIFICATION_ID, notification)
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
        const val CMD_NEXT = "ro.ag.caraudio.NEXT"
        const val CMD_PREVIOUS = "ro.ag.caraudio.PREVIOUS"
        const val EXTRA_MOVED = "moved"
        const val EXTRA_INDEX = "index"
        const val EXTRA_COUNT = "count"
        private const val DOUBLE_BACK_MS = 4_000L
        private const val TRACK_CHANNEL = "track_popup"
        private const val TRACK_NOTIFICATION_ID = 2
        private const val TRACK_POPUP_MS = 6_000L
        private const val ROOT = "root"
        private const val RECENT = "recent"
        private const val SEARCH = "search"
    }
}
