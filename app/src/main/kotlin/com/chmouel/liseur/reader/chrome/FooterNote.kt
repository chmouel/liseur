package com.chmouel.liseur.reader.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.ui.LocalEInk
import kotlinx.coroutines.delay

/**
 * What a tap on a footer slot just chose.
 *
 * [id] is a plain counter, and it is the reason this is a class rather
 * than a string resource on its own: a reader tapping the same corner
 * twice, or coming round the cycle to an entry they have already seen,
 * has made two separate taps and is owed two separate notes. Without
 * something that differs between them the second tap would inherit
 * whatever was left of the first one's five seconds and then take the
 * note away mid-sentence.
 */
data class FooterSlotNote(val label: Int, val unknown: Boolean, val id: Long)

/**
 * A word of explanation for a tap that would otherwise be a riddle.
 *
 * The footer prints bare figures — `85% left`, `3/20`, `Loc 1234` —
 * and a tap that swaps one for another hands the reader a new number
 * with no indication of what it counts. This names it, and then gets
 * out of the way.
 *
 * [FooterSlotNote.unknown] covers the taps that land on a slot with
 * nothing to say. An edge waiting on a measured reading pace draws
 * nothing at all, and an edge set to nothing draws nothing by
 * definition; without a word, both look like a tap that failed.
 *
 * It takes no touches of its own. It is drawn over the page-turn
 * zones, and a reader who wants the next page while it is up should
 * get the next page rather than spend the tap dismissing a label.
 */
@Composable
fun FooterNote(
    note: FooterSlotNote?,
    theme: ReaderTheme,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val eInk = LocalEInk.current
    // Keyed on the id rather than on the note, so the countdown starts
    // again from the top for every tap — including a tap that chose
    // the same thing as the one before it.
    LaunchedEffect(note?.id) {
        if (note != null) {
            delay(FOOTER_NOTE_MS)
            onDone()
        }
    }
    // The note is cleared the instant its time is up, and the pill is
    // still on screen for the length of the fade after that. Held here
    // so the words do not blank out from under it.
    val held = remember { mutableStateOf<FooterSlotNote?>(null) }
    if (note != null) held.value = note
    AnimatedVisibility(
        visible = note != null,
        enter = if (eInk) EnterTransition.None else fadeIn(),
        exit = if (eInk) ExitTransition.None else fadeOut(),
        modifier = modifier,
    ) {
        val shown = held.value ?: return@AnimatedVisibility
        val name = stringResource(shown.label)
        Text(
            text = if (shown.unknown) {
                stringResource(R.string.footer_note_unknown, name)
            } else {
                name
            },
            style = MaterialTheme.typography.labelSmall,
            color = theme.foreground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(FOOTER_NOTE_RADIUS))
                // The page's own paper, opaque, so the line of text
                // underneath does not read through it. The chrome
                // follows the reading theme; a Material surface here
                // would be the one bright rectangle on a sepia page.
                .background(theme.background)
                .border(
                    width = 1.dp,
                    color = theme.foreground.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(FOOTER_NOTE_RADIUS),
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * How long the note stays up.
 *
 * Long enough to be read after the eye has gone back to the page and
 * come off it again, and short enough that a reader cycling through
 * the catalog is not reading a stack of stale labels.
 */
private const val FOOTER_NOTE_MS = 5_000L

private val FOOTER_NOTE_RADIUS = 8.dp

/**
 * How far above the screen's edge the note floats.
 *
 * Clear of the footer rather than over it: the figure that has just
 * changed is the thing the note is about, and covering it to explain
 * it would be a poor trade. It is derived from the footer's own
 * reserved height rather than fixed, because a reader who has turned
 * the system font up has a taller footer, and a constant that cleared
 * it at one font size would sit on it at another.
 */
fun footerNoteBottom(footerReserve: Dp): Dp = footerReserve + FooterMetrics.ABOVE_GAP_DP.dp
