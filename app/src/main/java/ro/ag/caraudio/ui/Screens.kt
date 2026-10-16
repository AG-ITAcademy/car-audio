package ro.ag.caraudio.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import ro.ag.caraudio.Moods
import ro.ag.caraudio.R
import ro.ag.caraudio.YouTube
import java.util.Calendar

@Composable
fun AppRoot(state: AppState, actions: Actions) {
    NightlineTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                val playlist = state.openPlaylist
                if (playlist == null) HomeScreen(state, actions) else PlaylistScreen(playlist, state, actions)

                AnimatedVisibility(
                    visible = state.showNowPlaying && state.nowPlaying != null,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    NowPlayingScreen(state, actions)
                }
            }
        }
    }
}

// ================================================================ HOME

@Composable
fun HomeScreen(state: AppState, actions: Actions) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp),
        ) {
            item { Greeting() }
            item {
                SearchPill(state.query, actions::setQuery, actions::search, actions::clearResults)
                Spacer(Modifier.height(Space.m))
                FilterRow(state, actions)
                Spacer(Modifier.height(Space.s))
            }
            item {
                if (state.loading) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().padding(horizontal = Space.l).height(3.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                }
                state.message?.let { Message(it) }
            }

            if (state.results.isEmpty()) {
                item {
                    Spacer(Modifier.height(Space.l))
                    SectionTitle("Pick a mood", "Drive moods")
                    Spacer(Modifier.height(Space.m))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Space.l),
                        horizontalArrangement = Arrangement.spacedBy(Space.m),
                    ) {
                        items(Moods.all, key = { it.id }) { mood -> MoodCard(mood) { actions.openMood(mood) } }
                    }
                }
                if (state.recent.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(Space.xl))
                        SectionTitle("Jump back in", "Recently played")
                        Spacer(Modifier.height(Space.s))
                    }
                    items(state.recent.take(25), key = { "r" + it.mediaId }) { item ->
                        TrackRow(
                            item,
                            onClick = { actions.play(item) },
                            onAdd = { actions.addToQueue(item) },
                            current = item.mediaId == state.nowPlaying?.mediaId,
                            playing = state.isPlaying,
                        )
                    }
                }
            } else {
                item { ResultsHeader(state, actions) }
                val playlists = state.results.filter { it.mediaMetadata.isBrowsable == true }
                val videos = state.results.filter { it.mediaMetadata.isBrowsable != true }
                items(playlists.chunked(2), key = { "p" + it.first().mediaId }) { pair ->
                    Row(Modifier.padding(horizontal = Space.s)) {
                        pair.forEach { p ->
                            PlaylistCard(
                                p,
                                onClick = { actions.openPlaylist(p, autoplay = true) },
                                onAdd = { actions.queuePlaylist(p) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                items(videos, key = { "v" + it.mediaId }) { item ->
                    TrackRow(
                        item,
                        onClick = { actions.play(item) },
                        onAdd = { actions.addToQueue(item) },
                        current = item.mediaId == state.nowPlaying?.mediaId,
                        playing = state.isPlaying,
                    )
                }
            }
        }
        MiniPlayer(state, actions, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

@Composable
private fun Greeting() {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val (eyebrow, title) = when (hour) {
        in 5..11 -> "Good morning" to "Where to\nthis morning?"
        in 12..17 -> "Good afternoon" to "What's the\nsoundtrack?"
        in 18..21 -> "Good evening" to "Roll the\nwindows down."
        else -> "Night drive" to "Keep it\nsteady."
    }
    Column(Modifier.statusBarsPadding().padding(Space.l, Space.xl, Space.l, Space.l)) {
        Eyebrow("Car Audio · $eyebrow")
        Spacer(Modifier.height(Space.s))
        Text(title, style = MaterialTheme.typography.displaySmall)
    }
}

@Composable
private fun FilterRow(state: AppState, actions: Actions) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.l),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterPill("Playlists", state.searchType == YouTube.SearchType.PLAYLISTS, { actions.setSearchType(YouTube.SearchType.PLAYLISTS) }, R.drawable.ic_playlist)
        FilterPill("Songs", state.searchType == YouTube.SearchType.VIDEOS, { actions.setSearchType(YouTube.SearchType.VIDEOS) })
        Spacer(Modifier.weight(1f))
        FilterPill("Artists only", state.artistsOnly, actions::toggleArtistsOnly, R.drawable.ic_mic)
    }
}

@Composable
private fun ResultsHeader(state: AppState, actions: Actions) {
    val mood = state.resultsMood
    Row(
        Modifier.fillMaxWidth().padding(top = Space.l, bottom = Space.s, end = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionTitle(
            eyebrow = mood?.subtitle ?: "Results",
            title = mood?.title ?: "“${state.query.trim()}”",
            modifier = Modifier.weight(1f),
        )
        RoundButton(R.drawable.ic_close, actions::clearResults, size = 48.dp, iconSize = 22.dp, contentDescription = "Close results")
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = Space.l, vertical = Space.s),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.secondary,
    )
}

// ================================================================ PLAYLIST

@Composable
fun PlaylistScreen(playlist: MediaItem, state: AppState, actions: Actions) {
    BackHandler { actions.closePlaylist() }
    val meta = playlist.mediaMetadata
    val videos = state.playlistVideos
    val current = videos.indexOfFirst { it.mediaId == state.nowPlaying?.mediaId }
    val listState = rememberLazyListState()
    // The songs start after 3 items: header, now-playing panel, songs title.
    val songsOffset = 3
    LaunchedEffect(current) {
        if (current >= 0) listState.animateScrollToItem(current + songsOffset - 1)
    }
    val panelHidden by remember { derivedStateOf { listState.firstVisibleItemIndex >= 2 } }

    Box(Modifier.fillMaxSize()) {
        AmbientBackdrop(meta.artworkUri, Modifier.fillMaxWidth().height(380.dp))
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 120.dp)) {
            item {
                Column(Modifier.statusBarsPadding().padding(Space.l)) {
                    RoundButton(
                        R.drawable.ic_back, actions::closePlaylist,
                        container = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
                        contentDescription = "Back",
                    )
                    Spacer(Modifier.height(Space.l))
                    Eyebrow("Playlist")
                    Text(meta.title?.toString().orEmpty(), style = MaterialTheme.typography.headlineMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            if (videos.isNotEmpty()) "${videos.size} songs" else null,
                            meta.artist?.toString(),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Space.l))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        PillButton("Play", { actions.playAll(videos) }, icon = R.drawable.ic_play, enabled = videos.isNotEmpty())
                        PillButton("Shuffle", { actions.playAll(videos, shuffle = true) }, icon = R.drawable.ic_shuffle, filled = false, enabled = videos.isNotEmpty())
                    }
                    state.message?.let { Spacer(Modifier.height(Space.s)); Message(it) }
                }
            }
            item { NowPlayingPanel(state, actions) }
            item {
                Spacer(Modifier.height(Space.l))
                Eyebrow(if (state.loading) "Loading songs…" else "Songs", Modifier.padding(horizontal = Space.l, vertical = Space.s))
            }
            itemsIndexed(videos, key = { _, v -> v.mediaId }) { index, video ->
                TrackRow(
                    video,
                    onClick = { actions.playAll(videos, start = index) },
                    onAdd = { actions.addToQueue(video) },
                    current = index == current,
                    playing = state.isPlaying,
                    number = index + 1,
                )
            }
        }
        if (panelHidden) MiniPlayer(state, actions, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

/** The song that plays now, large, with the main controls. */
@Composable
private fun NowPlayingPanel(state: AppState, actions: Actions) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .padding(horizontal = Space.l)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(colors.surfaceContainerHigh)
            .padding(Space.l),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.startingPlaylist) {
            Spacer(Modifier.height(Space.xl))
            EqualizerBars(true, Modifier.size(40.dp))
            Spacer(Modifier.height(Space.m))
            Text("Starting the playlist…", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(Space.xl))
            return@Column
        }
        val item = state.nowPlaying
        if (item == null) {
            Text("Nothing plays yet. Tap Play.", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            return@Column
        }
        Box(Modifier.clickable { actions.openNowPlaying() }) {
            Artwork(
                YouTube.largeThumbnail(item.mediaId) ?: item.mediaMetadata.artworkUri,
                Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                MaterialTheme.shapes.large,
            )
        }
        Spacer(Modifier.height(Space.m))
        Text(item.mediaMetadata.title?.toString().orEmpty(), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(
                item.mediaMetadata.artist?.toString(),
                if (state.queueSize > 1) "${state.queueIndex + 1} of ${state.queueSize}" else null,
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1,
        )
        Spacer(Modifier.height(Space.m))
        val fraction = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
        ProgressLine(fraction, Modifier.clip(MaterialTheme.shapes.extraSmall))
        Spacer(Modifier.height(Space.m))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RoundButton(R.drawable.ic_previous, actions::previous, size = Size.control, container = colors.surfaceContainerHighest, contentDescription = "Previous")
            PlayButton(state, actions, size = Size.hero)
            RoundButton(R.drawable.ic_next, actions::next, size = Size.control, container = colors.surfaceContainerHighest, contentDescription = "Next")
        }
    }
}

// ================================================================ NOW PLAYING

@Composable
fun NowPlayingScreen(state: AppState, actions: Actions) {
    BackHandler { actions.closeNowPlaying() }
    val item = state.nowPlaying ?: return
    val colors = MaterialTheme.colorScheme
    val art = YouTube.largeThumbnail(item.mediaId) ?: item.mediaMetadata.artworkUri

    Box(Modifier.fillMaxSize().background(colors.background)) {
        AmbientBackdrop(art, Modifier.fillMaxSize())
        LazyColumn(
            Modifier.fillMaxSize().safeDrawingPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(bottom = Space.xxl),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(R.drawable.ic_chevron_down, actions::closeNowPlaying, contentDescription = "Close")
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Eyebrow("Now playing")
                        if (state.queueSize > 1) {
                            Text("${state.queueIndex + 1} of ${state.queueSize}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.width(Size.touch))
                }
            }
            item {
                Artwork(
                    art,
                    Modifier
                        .padding(horizontal = Space.xl, vertical = Space.l)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .shadow(if (state.isPlaying) 40.dp else 12.dp, MaterialTheme.shapes.extraLarge, ambientColor = colors.primary, spotColor = colors.primary),
                    MaterialTheme.shapes.extraLarge,
                )
            }
            item {
                Column(Modifier.padding(horizontal = Space.xl).fillMaxWidth()) {
                    Text(item.mediaMetadata.title?.toString().orEmpty(), style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(Space.xs))
                    Text(item.mediaMetadata.artist?.toString().orEmpty(), style = MaterialTheme.typography.titleMedium, color = colors.primary, maxLines = 1)
                }
            }
            item { Scrubber(state, actions) }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Space.l),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundButton(
                        R.drawable.ic_shuffle, actions::toggleShuffle,
                        tint = if (state.shuffle) colors.primary else colors.onSurfaceVariant,
                        contentDescription = "Shuffle",
                    )
                    RoundButton(R.drawable.ic_previous, actions::previous, size = Size.control, iconSize = 32.dp, contentDescription = "Previous")
                    PlayButton(state, actions, size = Size.hero)
                    RoundButton(R.drawable.ic_next, actions::next, size = Size.control, iconSize = 32.dp, contentDescription = "Next")
                    RoundButton(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
                        actions::cycleRepeat,
                        tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) colors.primary else colors.onSurfaceVariant,
                        contentDescription = "Repeat",
                    )
                }
            }
            if (state.upNext.isNotEmpty()) {
                item {
                    Eyebrow("Up next", Modifier.fillMaxWidth().padding(start = Space.l, top = Space.xxl, bottom = Space.s))
                }
                itemsIndexed(state.upNext, key = { i, v -> "$i-${v.mediaId}" }) { i, next ->
                    TrackRow(next, onClick = { actions.playQueueItem(i + 1) })
                }
            }
        }
    }
}

/** Progress you can drag, with times in the display font. */
@Composable
private fun Scrubber(state: AppState, actions: Actions) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1)
    val fraction = dragging ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    Column(Modifier.padding(horizontal = Space.l, vertical = Space.m)) {
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { actions.seekTo((it * duration).toLong()) }
                dragging = null
            },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.secondary,
                activeTrackColor = MaterialTheme.colorScheme.secondary,
                inactiveTrackColor = MaterialTheme.colorScheme.outline,
            ),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.xs)) {
            val numbers = MaterialTheme.typography.labelLarge.copy(fontFamily = SpaceGrotesk)
            Text(timeText((fraction * duration).toLong()), style = numbers, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(if (state.durationMs > 0) timeText(state.durationMs) else "--:--", style = numbers, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The artwork, very blurred and dim, behind the screen: the colors of the song
 * light the room. (Blur needs Android 12; before that, the image is only dimmed.)
 */
@Composable
private fun AmbientBackdrop(model: Any?, modifier: Modifier) {
    val bg = MaterialTheme.colorScheme.background
    Box(modifier) {
        Artwork(
            model,
            Modifier
                .fillMaxSize()
                .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(70.dp) else Modifier)
                .alpha(if (Build.VERSION.SDK_INT >= 31) 0.55f else 0.25f),
            androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to bg.copy(alpha = 0.2f), 0.6f to bg.copy(alpha = 0.75f), 1f to bg)
            )
        )
    }
}
