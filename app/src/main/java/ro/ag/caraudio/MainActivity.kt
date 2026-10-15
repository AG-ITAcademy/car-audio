package ro.ag.caraudio

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
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
    private var results by mutableStateOf<List<MediaItem>>(emptyList())
    private var recent by mutableStateOf<List<MediaItem>>(emptyList())
    private var searching by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private var nowPlaying by mutableStateOf<MediaItem?>(null)
    private var isPlaying by mutableStateOf(false)
    private var queueSize by mutableStateOf(0)

    /** A link that arrived by "Share" or "Open with" before the controller was ready. */
    private var pendingUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingUrl = linkFrom(intent)
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
        recent = Library.recent
    }

    private fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        searching = true
        message = null
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { YouTube.search(q) } }
                .onSuccess { results = it; Library.lastSearch = it }
                .onFailure { message = "Search failed: ${it.message}" }
            searching = false
        }
    }

    private fun play(item: MediaItem) {
        val c = controller ?: return
        message = "Loading…"
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

    @Composable
    private fun Screen() {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                placeholder = { Text("Search YouTube") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
            )
            if (searching) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
            }
            message?.let {
                Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.secondary)
            }
            val list = results.ifEmpty { recent }
            if (results.isEmpty() && recent.isNotEmpty()) {
                Text("Recently played", Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.titleSmall)
            }
            LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.mediaId }) { item ->
                    Row(
                        Modifier.fillMaxWidth().clickable { play(item) }.padding(12.dp, 8.dp),
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
                                item.mediaMetadata.title?.toString().orEmpty(),
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOfNotNull(
                                    item.mediaMetadata.artist?.toString(),
                                    duration(item.mediaMetadata.extras?.getLong(YouTube.EXTRA_DURATION) ?: 0),
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = { addToQueue(item) }) { Text("+", fontSize = 24.sp) }
                    }
                }
            }
            nowPlaying?.let { PlayerBar(it) }
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
                if (queueSize > 1) Text("$queueSize in queue", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = {
                val c = controller ?: return@TextButton
                if (c.isPlaying) c.pause() else c.play()
                message = null
            }) { Text(if (isPlaying) "❚❚" else "▶", fontSize = 22.sp) }
            TextButton(onClick = { controller?.seekToNext() }) { Text("⏭", fontSize = 22.sp) }
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
