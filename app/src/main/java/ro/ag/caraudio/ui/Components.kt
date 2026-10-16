package ro.ag.caraudio.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import coil.compose.AsyncImage
import ro.ag.caraudio.Mood
import ro.ag.caraudio.R
import ro.ag.caraudio.YouTube

// ---------------------------------------------------------------- basics

@Composable
fun NIcon(
    @DrawableRes res: Int,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
    contentDescription: String? = null,
) {
    Icon(painterResource(res), contentDescription, modifier.size(size), tint = tint)
}

/** An image with a quiet placeholder, cropped to fill. */
@Composable
fun Artwork(model: Any?, modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.small) {
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

/** Small capitals above a title. Amber by default: it points to where to look. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Text(text.uppercase(), modifier, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
}

@Composable
fun SectionTitle(eyebrow: String?, title: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = Space.l)) {
        if (eyebrow != null) Eyebrow(eyebrow)
        Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- buttons

/**
 * A round button. [filled] gives the amber "act here" button; [glow] adds the
 * dashboard-light glow around it.
 */
@Composable
fun RoundButton(
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = Size.touch,
    iconSize: Dp = size * 0.45f,
    filled: Boolean = false,
    glow: Boolean = false,
    tint: Color? = null,
    container: Color? = null,
    contentDescription: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val bg = container ?: if (filled) colors.primary else Color.Transparent
    val fg = tint ?: if (filled) colors.onPrimary else colors.onSurface
    Box(
        modifier
            .size(size)
            .then(
                if (glow) Modifier.shadow(size * 0.35f, CircleShape, ambientColor = colors.primary, spotColor = colors.primary)
                else Modifier
            )
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NIcon(icon, tint = fg, size = iconSize, contentDescription = contentDescription)
    }
}

/** A wide button with a label. Filled = the main action, outlined = the second one. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    filled: Boolean = true,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .height(Size.touch)
            .clip(shape)
            .then(
                if (filled) Modifier.background(if (enabled) colors.primary else colors.surfaceContainerHighest)
                else Modifier.border(1.5.dp, colors.outline, shape)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Space.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val fg = if (filled) colors.onPrimary else colors.onSurface
        if (icon != null) {
            NIcon(icon, tint = fg, size = 22.dp)
            Spacer(Modifier.width(Space.s))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** A filter switch. Selected = amber outline and amber text. */
@Composable
fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit, @DrawableRes icon: Int? = null) {
    val colors = MaterialTheme.colorScheme
    val fg by animateColorAsState(if (selected) colors.primary else colors.onSurfaceVariant, label = "pill")
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .height(40.dp)
            .clip(shape)
            .background(if (selected) colors.primaryContainer else colors.surfaceContainerHigh)
            .border(1.dp, if (selected) colors.primary.copy(alpha = 0.6f) else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            NIcon(icon, tint = fg, size = 18.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

// ---------------------------------------------------------------- search

@Composable
fun SearchPill(value: String, onValueChange: (String) -> Unit, onSearch: () -> Unit, onClear: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .padding(horizontal = Space.l)
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(50))
            .background(colors.surfaceContainerHigh)
            .padding(start = Space.l, end = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NIcon(R.drawable.ic_search, tint = colors.primary)
        Spacer(Modifier.width(Space.m))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text("Artist, song or mood", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            RoundButton(R.drawable.ic_close, onClear, size = 48.dp, iconSize = 20.dp, tint = colors.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- cards and rows

/** A mood tile: the generated artwork, a dark fade and the name. */
@Composable
fun MoodCard(mood: Mood, onClick: () -> Unit) {
    Box(
        Modifier
            .width(150.dp)
            .height(196.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
    ) {
        Artwork(mood.assetUri, Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f)))
        )
        Column(Modifier.align(Alignment.BottomStart).padding(Space.m)) {
            Text(mood.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 2)
            Text(mood.subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
        }
    }
}

@Composable
fun PlaylistCard(item: MediaItem, onClick: () -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val meta = item.mediaMetadata
    val count = meta.extras?.getLong(YouTube.EXTRA_COUNT) ?: -1
    Column(modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(Space.s)) {
        Box {
            Artwork(meta.artworkUri, Modifier.fillMaxWidth().aspectRatio(1f), MaterialTheme.shapes.medium)
            RoundButton(
                R.drawable.ic_play, onClick,
                Modifier.align(Alignment.BottomEnd).padding(Space.s),
                size = 44.dp, filled = true, glow = true,
            )
        }
        Spacer(Modifier.height(Space.s))
        Text(meta.title?.toString().orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(if (count >= 0) "$count songs" else null, meta.artist?.toString()).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            RoundButton(R.drawable.ic_queue_add, onAdd, size = 40.dp, iconSize = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * One song. The current song gets an amber title and the live equalizer in place of
 * its number.
 */
@Composable
fun TrackRow(
    item: MediaItem,
    onClick: () -> Unit,
    onAdd: (() -> Unit)? = null,
    current: Boolean = false,
    playing: Boolean = false,
    number: Int? = null,
) {
    val colors = MaterialTheme.colorScheme
    val meta = item.mediaMetadata
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (current) colors.primaryContainer.copy(alpha = 0.35f) else Color.Transparent)
            .padding(horizontal = Space.l, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
                if (current) EqualizerBars(playing, Modifier.size(18.dp))
                else Text("$number", style = MaterialTheme.typography.labelLarge.copy(fontFamily = SpaceGrotesk), color = colors.onSurfaceVariant)
            }
        }
        Box {
            Artwork(meta.artworkUri, Modifier.size(Size.rowArt), MaterialTheme.shapes.small)
            if (current && number == null) {
                Box(
                    Modifier.size(Size.rowArt).clip(MaterialTheme.shapes.small).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) { EqualizerBars(playing, Modifier.size(22.dp)) }
            }
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(
                meta.title?.toString().orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = if (current) colors.primary else colors.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    meta.artist?.toString(),
                    durationText(meta.extras?.getLong(YouTube.EXTRA_DURATION) ?: 0),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (onAdd != null) {
            RoundButton(R.drawable.ic_queue_add, onAdd, size = 48.dp, iconSize = 22.dp, tint = colors.onSurfaceVariant)
        }
    }
}

/** Three cyan bars that dance while music plays, and rest when it is paused. */
@Composable
fun EqualizerBars(playing: Boolean, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.secondary) {
    val transition = rememberInfiniteTransition(label = "eq")
    val heights = listOf(520, 380, 640).mapIndexed { i, ms ->
        transition.animateFloat(
            initialValue = 0.25f + i * 0.1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(ms), RepeatMode.Reverse),
            label = "bar$i",
        )
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        heights.forEach { h ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(if (playing) h.value else 0.3f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

// ---------------------------------------------------------------- player

/** A thin cyan line: how much of the song has played. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.outline)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.secondary))
    }
}

/** The floating player at the bottom. Tap it to open the full player. */
@Composable
fun MiniPlayer(state: AppState, actions: Actions, modifier: Modifier = Modifier) {
    val item = state.nowPlaying ?: return
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .padding(horizontal = Space.m, vertical = Space.s)
            .shadow(16.dp, MaterialTheme.shapes.large)
            .clip(MaterialTheme.shapes.large)
            .background(colors.surfaceContainerHighest)
            .clickable { actions.openNowPlaying() },
    ) {
        Row(Modifier.padding(Space.s), verticalAlignment = Alignment.CenterVertically) {
            Artwork(item.mediaMetadata.artworkUri, Modifier.size(52.dp), MaterialTheme.shapes.small)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(item.mediaMetadata.title?.toString().orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        item.mediaMetadata.artist?.toString(),
                        if (state.queueSize > 1) "${state.queueIndex + 1}/${state.queueSize}" else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            PlayButton(state, actions, size = 48.dp, glow = false)
            RoundButton(R.drawable.ic_next, actions::next, size = 48.dp, iconSize = 24.dp, contentDescription = "Next")
        }
        val fraction = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
        ProgressLine(fraction)
    }
}

/** Play / pause. A cyan ring turns around it while the song loads. */
@Composable
fun PlayButton(state: AppState, actions: Actions, size: Dp, glow: Boolean = true) {
    Box(contentAlignment = Alignment.Center) {
        if (state.isBuffering) {
            CircularProgressIndicator(
                Modifier.size(size + 8.dp),
                color = MaterialTheme.colorScheme.secondary,
                strokeWidth = 3.dp,
            )
        }
        RoundButton(
            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            actions::playPause,
            size = size, filled = true, glow = glow,
            contentDescription = if (state.isPlaying) "Pause" else "Play",
        )
    }
}

fun durationText(seconds: Long): String? {
    if (seconds <= 0) return null
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun timeText(ms: Long): String = durationText((ms / 1000).coerceAtLeast(0)) ?: "0:00"

