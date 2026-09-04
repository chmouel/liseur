package com.chmouel.liseur.reader.chrome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.ui.LocalEInk
import kotlin.math.roundToInt

/** The base the "Aa" sample is drawn at, before the size multiplier. */
private const val SAMPLE_BASE_SP = 22f

/**
 * What a pinch on the page is doing, while it is doing it.
 *
 * The reader is not asking "what multiplier is this", they are asking
 * "can I read that", so the pill answers in the only terms that question
 * has: the letters "Aa" drawn at the size being landed on. The page
 * underneath has not moved — nothing commits until the fingers lift (see
 * `docs/adr/0022-pinch-on-the-page.md`) — so this sample is the whole of
 * the feedback.
 *
 * Painted in the reading theme rather than in Material colours, for the
 * same reason [FootnoteCard] is: a white card over a black page at night
 * is a lamp in the face.
 *
 * Laid over the page inside the reader's own box, and deliberately not
 * clickable: the fingers that raised it are already busy, and a target
 * under them would take the gesture away from the page.
 */
@Composable
fun FontSizeHud(size: Double, theme: ReaderTheme) {
    // The sample is a picture of a size, not a line of prose: "Aa" read
    // aloud tells a screen reader nothing, and the percentage does.
    val spoken = stringResource(R.string.reader_pinch_size, (size * 100).roundToInt())
    HudPill(theme) {
        Text(
            text = stringResource(R.string.reader_pinch_sample),
            color = theme.foreground,
            fontSize = (SAMPLE_BASE_SP * size).sp,
            modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
        )
    }
}

/**
 * The same pill, saying why the pinch did nothing.
 *
 * A fixed-layout book places every page itself and Readium honours no
 * font size inside one, so the gesture has nothing to change. Saying so
 * is the whole point: a gesture that silently does nothing does not read
 * as "this book carries its own layout", it reads as broken, and worse
 * than a greyed-out slider does, because the reader cannot even tell
 * whether the app saw the pinch. Same argument as
 * `docs/adr/0020-fixed-layout-reading-settings.md`, same wording as the
 * line the typography sheet already shows.
 */
@Composable
fun FixedLayoutPinchHud(theme: ReaderTheme) {
    HudPill(theme) {
        Text(
            text = stringResource(R.string.reader_typography_fixed_layout),
            color = theme.foreground,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HudPill(theme: ReaderTheme, content: @Composable () -> Unit) {
    val eInk = LocalEInk.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = theme.background,
            contentColor = theme.foreground,
            border = BorderStroke(1.dp, theme.foreground.copy(alpha = 0.25f)),
            shadowElevation = if (eInk) 0.dp else 12.dp,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Box(
                Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
                contentAlignment = Alignment.Center,
                content = { content() },
            )
        }
    }
}
