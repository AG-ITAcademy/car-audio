package ro.ag.caraudio.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ro.ag.caraudio.R

/**
 * NIGHTLINE — the Car Audio design system.
 *
 * A dashboard at night: deep asphalt surfaces, one warm "signal amber" light for the
 * thing you act on, and "lane cyan" for what is live. Large type and large targets,
 * so that everything reads at a glance and works with one thumb.
 *
 * Rules:
 * 1. Amber means "act here" (play, the current song, the selected filter). One amber
 *    focus per screen.
 * 2. Cyan means "live" (progress, the playing equalizer, the road marks).
 * 3. Touch targets are at least [Size.touch] (56 dp); the main play button is
 *    [Size.hero] (88 dp).
 * 4. Space Grotesk for names and numbers (display), Inter for everything you read.
 */
object Nightline {
    // Night palette
    val Asphalt = Color(0xFF0B0D12)
    val Tarmac = Color(0xFF12151C)
    val Kerb = Color(0xFF1A1E28)
    val Overpass = Color(0xFF232836)
    val Line = Color(0xFF2E3546)
    val Headlight = Color(0xFFF3F5F9)
    val Dim = Color(0xFF9AA3B5)

    val Amber = Color(0xFFFFB224)
    val AmberDeep = Color(0xFFFF8A1F)
    val OnAmber = Color(0xFF1C1300)
    val Cyan = Color(0xFF2EE6D6)
    val Brake = Color(0xFFFF4D5E)

    // Day palette (same roles, printed on paper)
    val Paper = Color(0xFFF6F4EF)
    val Card = Color(0xFFFFFFFF)
    val Ink = Color(0xFF14161B)
    val InkDim = Color(0xFF5B6273)
    val AmberDay = Color(0xFFB86A00)
    val CyanDay = Color(0xFF00857A)
}

object Size {
    val touch = 56.dp
    val control = 64.dp
    val hero = 88.dp
    val rowArt = 56.dp
}

object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

private val NightColors = darkColorScheme(
    primary = Nightline.Amber,
    onPrimary = Nightline.OnAmber,
    primaryContainer = Color(0xFF3A2A08),
    onPrimaryContainer = Nightline.Amber,
    secondary = Nightline.Cyan,
    onSecondary = Nightline.Asphalt,
    secondaryContainer = Color(0xFF0E3A38),
    onSecondaryContainer = Nightline.Cyan,
    tertiary = Nightline.Brake,
    background = Nightline.Asphalt,
    onBackground = Nightline.Headlight,
    surface = Nightline.Asphalt,
    onSurface = Nightline.Headlight,
    surfaceVariant = Nightline.Kerb,
    onSurfaceVariant = Nightline.Dim,
    surfaceContainerLowest = Nightline.Asphalt,
    surfaceContainerLow = Nightline.Tarmac,
    surfaceContainer = Nightline.Tarmac,
    surfaceContainerHigh = Nightline.Kerb,
    surfaceContainerHighest = Nightline.Overpass,
    outline = Nightline.Line,
    outlineVariant = Nightline.Kerb,
    error = Nightline.Brake,
)

private val DayColors = lightColorScheme(
    primary = Nightline.AmberDay,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE2B0),
    onPrimaryContainer = Color(0xFF3A2400),
    secondary = Nightline.CyanDay,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC9F2EE),
    onSecondaryContainer = Color(0xFF00332F),
    tertiary = Color(0xFFC62838),
    background = Nightline.Paper,
    onBackground = Nightline.Ink,
    surface = Nightline.Paper,
    onSurface = Nightline.Ink,
    surfaceVariant = Color(0xFFECE8DF),
    onSurfaceVariant = Nightline.InkDim,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFAF7),
    surfaceContainer = Nightline.Card,
    surfaceContainerHigh = Color(0xFFF0EDE6),
    surfaceContainerHighest = Color(0xFFE7E3DA),
    outline = Color(0xFFD5D0C4),
    outlineVariant = Color(0xFFE7E3DA),
)

private val NightType = Typography(
    displayLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 46.sp, letterSpacing = (-1.5).sp),
    displaySmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp),
    /** The "eyebrow": small capitals above a title. */
    labelSmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.8.sp),
)

private val NightShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

@Composable
fun NightlineTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) NightColors else DayColors,
        typography = NightType,
        shapes = NightShapes,
        content = content,
    )
}
