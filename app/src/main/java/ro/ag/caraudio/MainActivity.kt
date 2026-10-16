package ro.ag.caraudio

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private var query by mutableStateOf("")
    private var searchType by mutableStateOf(YouTube.SearchType.PLAYLISTS)
    private var artistsOnly by mutableStateOf(true)
    private var results by mutableStateOf<List<MediaItem>>(emptyList())

    /** The playlist that is open, and its videos (empty while they load). */
    private var openPlaylist by mutableStateOf<MediaItem?>(null)
    private var playlistVideos by mutableStateOf<List<MediaItem>>(emptyList())
    private var recent by mutableStateOf<List<MediaItem>>(emptyList())
    private var searching by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private var nowPlaying by mutableStateOf<MediaItem?>(null)
    private var isPlaying by mutableStateOf(false)
    private var queueSize by mutableStateOf(0)
    private var queueIndex by mutableStateOf(0)

    /** For the pop-up with the song name when a new song starts. */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /** A link that arrived by "Share" or "Open with" before the controller was ready. */
    private var pendingUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingUrl = linkFrom(intent)
        artistsOnly = Library.artistsOnly
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
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
        recent = Library.recent
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = updateState(player)
                override fun onPlayerError(error: PlaybackException) {
                    message = "Playback error: ${error.errorCodeName}"
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
        nowPlaying = player.currentMediaItem
        isPlaying = player.isPlaying
        if (isPlaying && message == "Loading…") message = null
        queueSize = player.mediaItemCount
        queueIndex = player.currentMediaItemIndex
        recent = Library.recent
    }

    private fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        val type = searchType
        val filter = artistsOnly
        searching = true
        message = null
        openPlaylist = null
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { YouTube.search(q, type, filter) } }
                .onSuccess { results = it; Library.lastSearch = it }
                .onFailure { message = "Search failed: ${it.message}" }
            searching = false
        }
    }

    /** Open a playlist. With [autoplay], also play it from the first video. */
    private fun showPlaylist(playlist: MediaItem, autoplay: Boolean = false) {
        openPlaylist = playlist
        playlistVideos = emptyList()
        searching = true
        message = null
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { Library.playlist(playlist.mediaId) } }
                .onSuccess {
                    if (openPlaylist == playlist) playlistVideos = it
                    if (autoplay) playAll(it)
                }
                .onFailure { message = "Cannot load the playlist: ${it.message}" }
            searching = false
        }
    }

    /** Play a list of videos, starting at [start]. With [shuffle], start at a random video. */
    private fun playAll(videos: List<MediaItem>, start: Int = 0, shuffle: Boolean = false) {
        val c = controller ?: return
        if (videos.isEmpty()) return
        message = "Loading…"
        c.shuffleModeEnabled = shuffle
        c.setMediaItems(videos, if (shuffle) videos.indices.random() else start, C.TIME_UNSET)
        c.prepare()
        c.play()
    }

    /** Play all videos of a playlist from the search results, without opening it. */
    /** Add all videos of a playlist to the end of the queue. */
    private fun queuePlaylist(playlist: MediaItem) {
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { Library.playlist(playlist.mediaId) } }
                .onSuccess { videos ->
                    val c = controller ?: return@onSuccess
                    if (c.mediaItemCount == 0) return@onSuccess playAll(videos)
                    c.addMediaItems(videos)
                    message = "Added ${videos.size} songs to the queue"
                }
                .onFailure { message = "Cannot load the playlist: ${it.message}" }
        }
    }

    private fun play(item: MediaItem) {
        val c = controller ?: return
        message = "Loading…"
        c.shuffleModeEnabled = false
        c.setMediaItem(item)
        c.prepare()
        c.play()
    }

    private fun addToQueue(item: MediaItem) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(item)
        c.addMediaItem(item)
        message = "Added to queue"
    }

    /**
     * Next item. The playback service decides with the real queue (see PlaybackService
     * onCustomCommand), and it always continues playback.
     */
    private fun next() = sendSkip(PlaybackService.CMD_NEXT)

    /** Start of the item; a second press within 4 seconds goes to the previous item. */
    private fun previous() = sendSkip(PlaybackService.CMD_PREVIOUS)

    private fun sendSkip(action: String) {
        val c = controller ?: return
        val future = c.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), Bundle.EMPTY)
        future.addListener({
            val result = runCatching { future.get() }.getOrNull()
            if (result == null || result.resultCode != SessionResult.RESULT_SUCCESS) {
                message = "Skip failed (code ${result?.resultCode})"
                return@addListener
            }
            val extras = result.extras
            if (!extras.getBoolean(PlaybackService.EXTRA_MOVED, true)) {
                val index = extras.getInt(PlaybackService.EXTRA_INDEX) + 1
                val count = extras.getInt(PlaybackService.EXTRA_COUNT)
                message = "No next song in the queue ($index / $count)"
            }
        }, MoreExecutors.directExecutor())
    }

    private fun playPause() {
        val c = controller ?: return
        message = null
        if (c.isPlaying) c.pause() else resume(c)
    }

    private fun resume(c: Player) {
        if (c.playbackState == Player.STATE_IDLE || c.playbackState == Player.STATE_ENDED) c.prepare()
        c.play()
    }

    @Composable
    private fun Screen() {
        BackHandler(enabled = openPlaylist != null) { openPlaylist = null }
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            val playlist = openPlaylist
            if (playlist == null) SearchBar()
            if (searching) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
            }
            message?.let {
                Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.secondary)
            }

            if (playlist != null) {
                PlaylistScreen(playlist)
            } else {
                val list = results.ifEmpty { recent }
                if (results.isEmpty() && recent.isNotEmpty()) {
                    Text("Recently played", Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.titleSmall)
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(list, key = { it.mediaId }) { item ->
                        if (item.mediaMetadata.isBrowsable == true) {
                            PlaylistRow(item)
                        } else {
                            VideoRow(item, onClick = { play(item) })
                        }
                    }
                }
                nowPlaying?.let { PlayerBar(it) }
            }
        }
    }

    @Composable
    private fun SearchBar() {
        Column {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(12.dp, 12.dp, 12.dp, 0.dp),
                placeholder = { Text("Search YouTube") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
            )
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YouTube.SearchType.entries.forEach { type ->
                    FilterChip(
                        selected = searchType == type,
                        onClick = {
                            if (searchType != type) {
                                searchType = type
                                search()
                            }
                        },
                        label = { Text(if (type == YouTube.SearchType.PLAYLISTS) "Playlists" else "Videos") },
                    )
                }
                Spacer(Modifier.weight(1f))
                // Hides AI-generated music: only songs that name an artist.
                FilterChip(
                    selected = artistsOnly,
                    onClick = {
                        artistsOnly = !artistsOnly
                        Library.artistsOnly = artistsOnly
                        search()
                    },
                    label = { Text("Artists only") },
                )
            }
        }
    }

    /**
     * An open playlist: the song that plays now (large), the controls, and the songs
     * of the playlist. The song that plays now is marked in the list.
     */
    @Composable
    private fun androidx.compose.foundation.layout.ColumnScope.PlaylistScreen(playlist: MediaItem) {
        PlaylistHeader(playlist)
        nowPlaying?.let { NowPlayingCard(it) }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { playAll(playlistVideos) }, enabled = playlistVideos.isNotEmpty()) {
                Text("▶  Play all")
            }
            TextButton(onClick = { playAll(playlistVideos, shuffle = true) }, enabled = playlistVideos.isNotEmpty()) {
                Text("🔀  Shuffle")
            }
        }
        HorizontalDivider()

        val current = playlistVideos.indexOfFirst { it.mediaId == nowPlaying?.mediaId }
        val listState = rememberLazyListState()
        LaunchedEffect(current) {
            if (current >= 0) listState.animateScrollToItem(current)
        }
        LazyColumn(Modifier.weight(1f), state = listState) {
            items(playlistVideos.size, key = { playlistVideos[it].mediaId }) { index ->
                VideoRow(
                    playlistVideos[index],
                    onClick = { playAll(playlistVideos, start = index) },
                    current = index == current,
                )
            }
        }
    }

    /** Large thumbnail, title, artist and big buttons for the song that plays now. */
    @Composable
    private fun NowPlayingCard(item: MediaItem) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp, 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AsyncImage(
                model = YouTube.largeThumbnail(item.mediaId) ?: item.mediaMetadata.artworkUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                item.mediaMetadata.title?.toString().orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    item.mediaMetadata.artist?.toString(),
                    if (queueSize > 1) "${queueIndex + 1} / $queueSize" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = ::previous, modifier = Modifier.size(64.dp)) {
                    Text("⏮", fontSize = 26.sp)
                }
                FilledIconButton(onClick = ::playPause, modifier = Modifier.size(80.dp), shape = CircleShape) {
                    Text(if (isPlaying) "❚❚" else "▶", fontSize = 30.sp)
                }
                FilledTonalIconButton(onClick = ::next, modifier = Modifier.size(64.dp)) {
                    Text("⏭", fontSize = 26.sp)
                }
            }
        }
    }

    @Composable
    private fun PlaylistHeader(playlist: MediaItem) {
        Row(Modifier.fillMaxWidth().padding(4.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { openPlaylist = null }) { Text("←", fontSize = 22.sp) }
            Text(
                playlist.mediaMetadata.title?.toString().orEmpty(),
                Modifier.weight(1f),
                fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }

    @Composable
    private fun PlaylistRow(item: MediaItem) {
        val count = item.mediaMetadata.extras?.getLong(YouTube.EXTRA_COUNT) ?: -1
        ItemRow(
            item,
            subtitle = listOfNotNull(
                item.mediaMetadata.artist?.toString(),
                if (count >= 0) "$count videos" else null,
            ).joinToString(" · "),
            onClick = { showPlaylist(item, autoplay = true) },
        ) {
            TextButton(onClick = { queuePlaylist(item) }) { Text("+", fontSize = 24.sp) }
        }
    }

    @Composable
    private fun VideoRow(item: MediaItem, onClick: () -> Unit, current: Boolean = false) {
        ItemRow(
            item,
            current = current,
            subtitle = listOfNotNull(
                item.mediaMetadata.artist?.toString(),
                duration(item.mediaMetadata.extras?.getLong(YouTube.EXTRA_DURATION) ?: 0),
            ).joinToString(" · "),
            onClick = onClick,
        ) {
            TextButton(onClick = { addToQueue(item) }) { Text("+", fontSize = 24.sp) }
        }
    }

    @Composable
    private fun ItemRow(
        item: MediaItem,
        subtitle: String,
        onClick: () -> Unit,
        current: Boolean = false,
        action: @Composable () -> Unit,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                .clickable(onClick = onClick)
                .padding(12.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = item.mediaMetadata.artworkUri,
                contentDescription = null,
                modifier = Modifier.size(width = 96.dp, height = 54.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    (if (current) "▶ " else "") + item.mediaMetadata.title?.toString().orEmpty(),
                    fontWeight = if (current) FontWeight.Bold else null,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            action()
        }
    }

    @Composable
    private fun PlayerBar(item: MediaItem) {
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(12.dp, 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    item.mediaMetadata.title?.toString().orEmpty(),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        item.mediaMetadata.artist?.toString(),
                        if (queueSize > 1) "${queueIndex + 1} / $queueSize" else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = ::previous) { Text("⏮", fontSize = 22.sp) }
            TextButton(onClick = ::playPause) { Text(if (isPlaying) "❚❚" else "▶", fontSize = 22.sp) }
            TextButton(onClick = ::next) { Text("⏭", fontSize = 22.sp) }
        }
    }

    private fun duration(seconds: Long): String? {
        if (seconds <= 0) return null
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
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
