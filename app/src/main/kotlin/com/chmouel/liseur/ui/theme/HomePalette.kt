package com.chmouel.liseur.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.chmouel.liseur.ui.LocalEInk

/** One shelf tile's colours: the card, the lighter sweep across it, and its icon and count. */
@Immutable
data class TileTint(val container: Color, val glow: Color, val content: Color)

/**
 * The fixed colours of the Home tab and the tab bar, in a light and a dark
 * cut. Kept apart from the Material scheme on purpose: the shelf tiles keep
 * their own hues whatever the wallpaper is, and everything else on the
 * screen still follows the theme.
 */
@Immutable
data class HomePalette(
    val accent: Color,
    val onAccent: Color,
    val progress: Color,
    val track: Color,
    val pill: Color,
    val onPill: Color,
    val card: Color,
    val cardBorder: Color,
    val heroWash: Color,
    val reading: TileTint,
    val toRead: TileTint,
    val series: TileTint,
    val finished: TileTint,
    val tabBar: Color,
    val tabBarBorder: Color,
    val tabSelected: Color,
    val onTabSelected: Color,
    val onTab: Color,
)

val LightHomePalette = HomePalette(
    accent = Color(0xFF7650DA),
    onAccent = Color.White,
    progress = Color(0xFF7953DF),
    track = Color(0xFFDCDAE6),
    pill = Color(0xFFEEE7FF),
    onPill = Color(0xFF4F2FBF),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFECE8F4),
    heroWash = Color(0xFFF1E9FF),
    reading = TileTint(Color(0xFFF5EDFE), Color(0xFFE6D8FC), Color(0xFF6A45CF)),
    toRead = TileTint(Color(0xFFEEF1FE), Color(0xFFDDE3FC), Color(0xFF3F51C4)),
    series = TileTint(Color(0xFFE8F9F4), Color(0xFFCDF0E5), Color(0xFF00806C)),
    finished = TileTint(Color(0xFFFEF0DC), Color(0xFFFCE0BC), Color(0xFFD27A22)),
    tabBar = Color(0xFFFFFFFF),
    tabBarBorder = Color(0xFFECE8F4),
    tabSelected = Color(0xFFE8DBFD),
    onTabSelected = Color(0xFF4B1DB0),
    onTab = Color(0xFF50536A),
)

val DarkHomePalette = HomePalette(
    accent = Color(0xFF5D44B9),
    onAccent = Color.White,
    progress = Color(0xFFB9BDFA),
    track = Color(0xFF40435A),
    pill = Color(0xFF332D62),
    onPill = Color(0xFFCDCCF6),
    card = Color(0xFF1F2231),
    cardBorder = Color(0xFF2D3144),
    heroWash = Color(0xFF3A2F55),
    reading = TileTint(Color(0xFF2B263E), Color(0xFF3D3060), Color(0xFFB9A6FF)),
    toRead = TileTint(Color(0xFF222943), Color(0xFF2E3962), Color(0xFFA9B8FF)),
    series = TileTint(Color(0xFF1B2B32), Color(0xFF1F4644), Color(0xFF95EED7)),
    finished = TileTint(Color(0xFF342B30), Color(0xFF4D3A2E), Color(0xFFF2B27A)),
    tabBar = Color(0xFF1A1E2B),
    tabBarBorder = Color(0xFF2A2E3E),
    tabSelected = Color(0xFF35345F),
    onTabSelected = Color(0xFFBBBCF9),
    onTab = Color(0xFFCBD2F1),
)

/**
 * The cut that suits the theme in force, read from the surface rather than
 * from the system's night setting, which the app's own may overrule. Null on
 * e-ink, where tints dither into grey and the screens keep their outlines.
 */
@Composable
fun homePalette(): HomePalette? {
    if (LocalEInk.current) return null
    return if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) DarkHomePalette else LightHomePalette
}
