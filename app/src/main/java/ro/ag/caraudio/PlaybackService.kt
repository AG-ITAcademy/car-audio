package ro.ag.caraudio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.MediaStore
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
import androidx.media3.session.CommandButton
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
        updateCustomLayout()
    }

    /**
     * Shuffle and repeat buttons on the Android Auto playback screen (and in the media
     * notification). The icon shows the current mode: a bar under it means "on".
     */
    private fun updateCustomLayout() {
        if (!::session.isInitialized) return
        val shuffle = CommandButton.Builder()
            .setDisplayName(if (player.shuffleModeEnabled) "Shuffle on" else "Shuffle off")
            .setIconResId(if (player.shuffleModeEnabled) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle)
            .setSessionCommand(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
            .build()
        val repeat = CommandButton.Builder()
            .setDisplayName(
                when (player.repeatMode) {
                    Player.REPEAT_MODE_ALL -> "Repeat all"
                    Player.REPEAT_MODE_ONE -> "Repeat one"
                    else -> "Repeat off"
                }
            )
            .setIconResId(
                when (player.repeatMode) {
                    Player.REPEAT_MODE_ALL -> R.drawable.ic_repeat_on
                    Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one_on
                    else -> R.drawable.ic_repeat
                }
            )
            .setSessionCommand(SessionCommand(CMD_REPEAT, Bundle.EMPTY))
            .build()
        session.setCustomLayout(ImmutableList.of(shuffle, repeat))
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

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            updateCustomLayout()
            prefetchNeighbours()
        }

        override fun onRepeatModeChanged(repeatMode: Int) = updateCustomLayout()

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
            if (!isTrusted(controller)) {
                Log.w(TAG, "Connection refused: ${controller.packageName}")
                return MediaSession.ConnectionResult.reject()
            }
            val result = super.onConnect(session, controller)
            val commands = result.availableSessionCommands.buildUpon()
                .add(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
                .add(SessionCommand(CMD_REPEAT, Bundle.EMPTY))
            if (controller.packageName == packageName) {
                commands.add(SessionCommand(CMD_NEXT, Bundle.EMPTY))
                commands.add(SessionCommand(CMD_PREVIOUS, Bundle.EMPTY))
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands.build())
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
            when (customCommand.customAction) {
                CMD_SHUFFLE -> {
                    p.shuffleModeEnabled = !p.shuffleModeEnabled
                    return immediate(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CMD_REPEAT -> {
                    p.repeatMode = when (p.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    return immediate(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
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
            // Tell Android Auto that it can show a search button. Lists by default;
            // the Moods tab and the mood pages ask for grids (see folder()).
            val extras = Bundle().apply {
                putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
                putInt(STYLE_BROWSABLE, STYLE_LIST)
                putInt(STYLE_PLAYABLE, STYLE_LIST)
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
            val children = when {
                // The tabs of Android Auto.
                parentId == ROOT -> listOf(
                    folder(MOODS, "Moods", ArtProvider.uri("tab_moods.png"), childStyle = STYLE_GRID),
                    folder(RECENT, "Recent", ArtProvider.uri("tab_recent.png")),
                    folder(SEARCH, "Search", ArtProvider.uri("tab_search.png"), childStyle = STYLE_GRID),
                )
                parentId == MOODS -> Moods.all.map { mood ->
                    folder(MOOD_PREFIX + mood.id, mood.title, mood.contentUri, mood.subtitle, childStyle = STYLE_GRID)
                }
                parentId == RECENT -> Library.recent
                parentId == SEARCH -> grouped(Library.lastSearch)
                // A mood: its playlists (search on YouTube, one time).
                parentId.startsWith(MOOD_PREFIX) -> {
                    val mood = Moods.byId(parentId.removePrefix(MOOD_PREFIX))
                        ?: return immediate(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                    return executor.submit<LibraryResult<ImmutableList<MediaItem>>> {
                        LibraryResult.ofItemList(paged(Library.moodPlaylists(mood), page, pageSize), params)
                    }
                }
                // A playlist: load its videos.
                else -> return executor.submit<LibraryResult<ImmutableList<MediaItem>>> {
                    LibraryResult.ofItemList(paged(Library.playlist(parentId), page, pageSize), params)
                }
            }
            return immediate(LibraryResult.ofItemList(paged(children, page, pageSize), params))
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
            immediate(LibraryResult.ofItemList(paged(grouped(Library.lastSearch), page, pageSize), params))

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
     * One request to one or more playable items. See CONTROL.md for what other apps send.
     * - a video URL: that video;
     * - a playlist URL: all its videos;
     * - "mood:<id>": the first playlist of that drive mood;
     * - a search (voice, or another app): a playlist, or a song when the request says so.
     */
    private fun expand(item: MediaItem, single: Boolean): List<MediaItem> {
        val id = item.mediaId
        if (id.startsWith("http")) {
            if (Uri.parse(id).path == "/playlist") return Library.playlist(id).map(YouTube::lazy)
            // For one item, get the metadata and stream now: a shared link has no title yet.
            return listOf(if (single) YouTube.resolve(item) else YouTube.lazy(item))
        }
        if (id.startsWith(MOOD_PREFIX)) {
            val mood = Moods.byId(id.removePrefix(MOOD_PREFIX)) ?: throw IOException("Unknown mood $id")
            return playFirstPlaylist(Library.moodPlaylists(mood), mood.title)
        }
        val request = item.requestMetadata
        val query = request.searchQuery ?: throw IOException("Unknown item $id")
        return playSearch(query, request.extras ?: Bundle.EMPTY)
    }

    /**
     * A search request. The standard Android search extras say what kind of thing the
     * user wants (EXTRA_MEDIA_FOCUS) and can give the artist and title separately.
     * A song: that song, then the other songs found. Anything else: the first playlist.
     * An empty search ("play music"): the last song played.
     */
    private fun playSearch(query: String, extras: Bundle): List<MediaItem> {
        val focus = extras.getString(MediaStore.EXTRA_MEDIA_FOCUS)
        val title = extras.getString(MediaStore.EXTRA_MEDIA_TITLE)
        val artist = extras.getString(MediaStore.EXTRA_MEDIA_ARTIST)
        val words = listOfNotNull(artist, title).joinToString(" ").ifBlank { query }.trim()
        Log.i(TAG, "Search request: \"$words\" (focus=$focus)")

        if (words.isEmpty()) {
            val last = Library.recent.firstOrNull() ?: throw IOException("Nothing played yet")
            return listOf(YouTube.resolve(last))
        }
        val artistsOnly = Library.artistsOnly
        if (focus == MediaStore.Audio.Media.ENTRY_CONTENT_TYPE) {
            val songs = YouTube.search(words, YouTube.SearchType.VIDEOS, artistsOnly)
            Library.lastSearch = songs
            val first = songs.firstOrNull() ?: throw IOException("No song for $words")
            return listOf(YouTube.resolve(first)) + songs.drop(1).map(YouTube::lazy)
        }
        val playlists = YouTube.search(words, YouTube.SearchType.PLAYLISTS, artistsOnly)
        Library.lastSearch = playlists
        if (playlists.isNotEmpty()) return playFirstPlaylist(playlists, words)
        val video = YouTube.search(words, YouTube.SearchType.VIDEOS, artistsOnly).firstOrNull()
            ?: throw IOException("No result for $words")
        return listOf(YouTube.resolve(video))
    }

    /** The videos of the first playlist that has any (after the "Artists only" filter). */
    private fun playFirstPlaylist(playlists: List<MediaItem>, what: String): List<MediaItem> {
        for (playlist in playlists.take(3)) {
            val videos = Library.playlist(playlist.mediaId)
            if (videos.isNotEmpty()) return videos.map(YouTube::lazy)
        }
        throw IOException("No playable playlist for $what")
    }

    /**
     * Which apps may control playback: this app, the apps in [TRUSTED_APPS] (Android
     * Auto, Google Assistant, RoadGuide) and apps that are part of the phone's system
     * (System UI, Bluetooth, the media button handler). Other installed apps are refused.
     */
    private fun isTrusted(controller: ControllerInfo): Boolean {
        val pkg = controller.packageName
        if (pkg == packageName || pkg in TRUSTED_APPS) return true
        if (pkg == LEGACY_CONTROLLER) return true
        if (controller.uid == Process.SYSTEM_UID) return true
        return runCatching {
            val flags = packageManager.getApplicationInfo(pkg, 0).flags
            flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }.getOrDefault(false)
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

    /**
     * A browsable item. [childStyle] asks Android Auto to show the children of this
     * item as a grid ([STYLE_GRID]) or a list ([STYLE_LIST]).
     */
    private fun folder(
        id: String,
        title: String,
        artwork: Uri? = null,
        subtitle: String? = null,
        childStyle: Int = STYLE_LIST,
    ): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(artwork)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setExtras(Bundle().apply {
                        putInt(STYLE_BROWSABLE, childStyle)
                        putInt(STYLE_PLAYABLE, STYLE_LIST)
                    })
                    .build()
            )
            .build()

    /** Search results in two groups with titles: "Playlists", then "Songs". */
    private fun grouped(items: List<MediaItem>): List<MediaItem> {
        val (playlists, songs) = items.partition { it.mediaMetadata.isBrowsable == true }
        return playlists.map { withGroup(it, "Playlists") } + songs.map { withGroup(it, "Songs") }
    }

    private fun withGroup(item: MediaItem, group: String): MediaItem {
        val meta = item.mediaMetadata
        val extras = Bundle(meta.extras ?: Bundle()).apply { putString(STYLE_GROUP_TITLE, group) }
        return item.buildUpon().setMediaMetadata(meta.buildUpon().setExtras(extras).build()).build()
    }

    /** The part of [items] that Android Auto asks for. */
    private fun paged(items: List<MediaItem>, page: Int, pageSize: Int): ImmutableList<MediaItem> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return ImmutableList.copyOf(items)
        return ImmutableList.copyOf(items.drop(page * pageSize).take(pageSize))
    }

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
        private const val MOODS = "moods"
        const val MOOD_PREFIX = "mood:"

        /** The name media3 gives to platform controllers whose app is not known. */
        private const val LEGACY_CONTROLLER = "android.media.session.MediaController"

        /** Apps (not part of the system) that may control Car Audio. */
        private val TRUSTED_APPS = setOf(
            "ro.roadguide",                                // RoadGuide drive assistant
            "com.google.android.projection.gearhead",      // Android Auto
            "com.google.android.googlequicksearchbox",     // Google app / Assistant
            "com.google.android.apps.googleassistant",     // Google Assistant
        )
        const val CMD_SHUFFLE = "ro.ag.caraudio.SHUFFLE"
        const val CMD_REPEAT = "ro.ag.caraudio.REPEAT"

        // Android Auto content style keys and values.
        private const val STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val STYLE_GROUP_TITLE = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"
        private const val STYLE_LIST = 1
        private const val STYLE_GRID = 2
    }
}
