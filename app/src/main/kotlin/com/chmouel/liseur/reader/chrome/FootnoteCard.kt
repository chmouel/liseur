package com.chmouel.liseur.reader.chrome

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.reader.footnotes.FootnoteText
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth

/**
 * The note, shown where it was referenced.
 *
 * A footnote used to send the reader to the back of the book and leave them
 * to find their own way home. The card is the answer: the note appears over
 * the page it belongs to, the page does not move, and dismissing it costs a
 * tap anywhere.
 *
 * It is painted in the reading theme rather than in Material colours, because
 * it sits on the page, and a white card over a black page at night is a lamp
 * in the face. For the same reason it is opaque: a note read through the
 * paragraph behind it is not read.
 *
 * The card follows the marker. [anchorY] is where the reader's finger landed,
 * and the card takes whichever side of that has room, so the marker itself is
 * never the thing covered up. With no anchor — a note reached from a keyboard,
 * say — it settles in the middle.
 *
 * Meant to be laid over the page inside the reader's own box, not in a
 * [androidx.compose.ui.window.Popup]: the scrim has to cover the same area
 * the tap zones do, or the tap meant to dismiss the note turns a page.
 */
@Composable
fun FootnoteCard(
    html: String,
    theme: ReaderTheme,
    anchorY: Float?,
    onGoToNote: () -> Unit,
    onDismiss: () -> Unit,
) {
    val text = remember(html) {
        FootnoteText.forCard(html)
            ?.let { AnnotatedString.fromHtml(it) }
            ?: AnnotatedString(FootnoteText.plainText(html))
    }
    val eInk = LocalEInk.current
    var cardHeight by remember { mutableIntStateOf(0) }
    var boxHeight by remember { mutableIntStateOf(0) }

    BackHandler(onBack = onDismiss)

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { boxHeight = it.height }
            // Always darkens, never tints: a scrim built from the reading
            // theme's own ink would lighten a night page instead of pushing it
            // back. Electronic paper redraws a translucent wash as a grey
            // smear, so there the border is what separates note from page.
            .background(Color.Black.copy(alpha = if (eInk) 0f else 0.35f))
            // Named, so a screen reader announces what dismisses the note
            // rather than a full-screen tap target with nothing to say.
            .noRipple(
                onClickLabel = stringResource(R.string.reader_footnote_dismiss),
                onClick = onDismiss,
            ),
    ) {
        val density = LocalDensity.current
        val top = if (anchorY != null && cardHeight > 0 && boxHeight > 0) {
            placeCard(anchorY, cardHeight.toFloat(), boxHeight.toFloat())
        } else {
            null
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = theme.background,
            contentColor = theme.foreground,
            border = BorderStroke(1.dp, theme.foreground.copy(alpha = 0.25f)),
            shadowElevation = if (eInk) 0.dp else 12.dp,
            modifier = Modifier
                .align(if (top == null) Alignment.Center else Alignment.TopCenter)
                .then(
                    if (top == null) {
                        Modifier
                    } else {
                        Modifier.offset(y = with(density) { top.toDp() })
                    },
                )
                .padding(horizontal = 16.dp)
                .widthIn(max = contentWidthCap(windowWidth()))
                .fillMaxWidth()
                .onSizeChanged { cardHeight = it.height }
                // Swallow taps on the card, so reading a note is not also
                // dismissing it.
                .noRipple {},
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
                Text(
                    text = stringResource(R.string.reader_footnote),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = theme.foreground.copy(alpha = 0.6f),
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = theme.foreground,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = onGoToNote) {
                        Text(
                            text = stringResource(R.string.reader_footnote_go_to),
                            color = theme.foreground.copy(alpha = 0.75f),
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = stringResource(R.string.reader_footnote_dismiss),
                            color = theme.foreground,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.noRipple(
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier =
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClickLabel = onClickLabel,
        onClick = onClick,
    )

/**
 * Where the top of a card of [cardHeight] goes, given a marker at [anchorY].
 *
 * Below the marker by preference, because that is where the eye already is
 * and the note reads on from the sentence that raised it. Above it when there
 * is no room below, which is the common case for a marker near the foot of
 * the page. Either way the card never covers the marker, so the reader can
 * still see what they tapped.
 *
 * Split out from the drawing so the arithmetic can be checked without a
 * screen.
 */
internal fun placeCard(
    anchorY: Float,
    cardHeight: Float,
    viewportHeight: Float,
    gap: Float = 48f,
): Float {
    val below = anchorY + gap
    if (below + cardHeight <= viewportHeight) return below
    val above = anchorY - gap - cardHeight
    if (above >= 0f) return above
    // Neither side fits: centre it and let it scroll. A note this long was
    // never going to sit beside its marker.
    return ((viewportHeight - cardHeight) / 2f).coerceAtLeast(0f)
}
