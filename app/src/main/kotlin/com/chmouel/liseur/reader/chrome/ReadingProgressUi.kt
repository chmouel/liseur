package com.chmouel.liseur.reader.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.remote.ResumeConfidence
import com.chmouel.liseur.data.settings.FooterField
import com.chmouel.liseur.data.settings.FooterMode
import com.chmouel.liseur.data.settings.FooterSlot
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.data.settings.footerHasAnythingToSay
import com.chmouel.liseur.reader.progress.BookScreenEstimate
import com.chmouel.liseur.reader.progress.FooterFigure
import com.chmouel.liseur.reader.progress.FooterMiddle
import com.chmouel.liseur.reader.progress.SectionScreens
import com.chmouel.liseur.reader.progress.footerFigure
import com.chmouel.liseur.reader.progress.ReaderProgress
import com.chmouel.liseur.reader.progress.footerMiddle
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.reading.label

/** How tall the ramp at a chrome edge is. */
private val CHROME_FADE_HEIGHT = 20.dp

/**
 * The quiet line of text at the bottom of the page, Kindle-style.
 *
 * Three slots, each its own setting. The two edges are a
 * [com.chmouel.liseur.data.settings.FooterField] apiece — the
 * percentage read, the page of the book, the chapter countdown, the
 * clock, and the rest of the catalog — and the middle is the
 * [FooterMode] it has been since the footer had one slot, which is why
 * it alone can carry a chapter's name. Tapping a slot cycles that slot
 * and nothing else; holding it opens a picker. Taps never turn the
 * page.
 *
 * What a page is here depends on the book. A reflowable one counts
 * screenfuls of the resource on screen, measured from the laid-out
 * page, so the page number moves and the chapter countdown comes down
 * by one for every turn. A fixed-layout one counts the book's own
 * pages, which are Readium positions there. [screens] is null while a
 * reflowable page is still being laid out, and a slot that would need
 * it stays blank for that moment rather than filling itself with a
 * number that counts something else.
 *
 * [turn] counts page turns and means nothing on its own. The clock and
 * the battery are read while the footer is drawn, so a footer that
 * never redraws holds a stale reading, which is what would happen on
 * electronic paper, where they have no ticker, in a book with no
 * positions to change.
 */
@Composable
fun ReadingFooter(
    progress: ReaderProgress?,
    reflowable: Boolean,
    screens: SectionScreens?,
    bookScreens: BookScreenEstimate,
    mode: FooterMode,
    left: FooterField,
    right: FooterField,
    turn: Int,
    theme: ReaderTheme,
    onCycleMode: (onChosen: (FooterMode) -> Unit) -> Unit,
    onCycleField: (FooterSlot, onChosen: (FooterField) -> Unit) -> Unit,
    onPickSlot: (FooterPickTarget) -> Unit,
    onNote: (label: Int, unknown: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!footerHasAnythingToSay(mode, left, right)) return
    val device = rememberFooterDevice(
        wantsClock = left == FooterField.CLOCK || right == FooterField.CLOCK,
        wantsBattery = left == FooterField.BATTERY || right == FooterField.BATTERY,
        turn = turn,
    )
    // The note names the figure the write settled on, which the tap
    // is told once the write is through. Working it out here instead
    // would work it out from the settings the footer was drawn with,
    // and a second tap arriving before the first has come back round
    // through DataStore would name the figure the first one chose.
    //
    // Whether that figure has anything to draw is still answered here,
    // because this is the only place holding the book's position and
    // the page's measurements.
    val context = LocalContext.current
    val noteForField = { next: FooterField ->
        onNote(
            next.label,
            // "Nothing here" is not a slot that failed to answer; it
            // is a slot that was asked for nothing, and its own label
            // is the whole explanation.
            next != FooterField.EMPTY &&
                footerFigure(
                    next,
                    progress,
                    reflowable,
                    screens,
                    bookScreens,
                    // Not the device the footer is drawing from: that
                    // one only holds the readings the slots ask for
                    // now, so a tap arriving at the clock would be
                    // told there was no clock a moment before the
                    // clock appeared. The tap is not a composition and
                    // can afford to look.
                    footerDeviceFor(next, context),
                ) == null,
        )
    }
    val noteForMode = { next: FooterMode ->
        onNote(
            next.label,
            next != FooterMode.EMPTY &&
                (progress == null || footerMiddle(progress, next, reflowable, screens) == null),
        )
    }
    val color = theme.foreground.copy(alpha = 0.6f)
    // The row's padding, horizontal and vertical alike, lives inside
    // each slot's clickable instead of on the row. The height of the
    // band is the same either way, and [FooterMetrics.reservedHeightDp]
    // still describes it; what changes is that every pixel of it
    // answers a tap, including the ones over a slot set to nothing,
    // rather than leaving that to how an empty box happens to measure.
    val slotPadding = FooterMetrics.VERTICAL_PADDING_DP.dp
    // A `Row` puts its first child at the reading-start edge, which in
    // an Arabic or Hebrew locale is the physical right. The two
    // settings are named for corners the reader can point at, so the
    // slots trade places here and "left" stays on the left whichever
    // way the locale runs. Nothing else has to change: `start` and
    // `end` in the paddings and the alignments resolve against the
    // same direction, so each corner keeps its wide margin against the
    // side of the screen it is actually sitting on.
    val leftFirst = LocalLayoutDirection.current == LayoutDirection.Ltr
    val startSlot = if (leftFirst) FooterSlot.LEFT else FooterSlot.RIGHT
    val endSlot = if (leftFirst) FooterSlot.RIGHT else FooterSlot.LEFT
    val startField = if (leftFirst) left else right
    val endField = if (leftFirst) right else left
    Row(
        modifier
            .fillMaxWidth()
            // The edges are as tall as the row so an edge showing
            // nothing is still a full-height target. Without it the
            // row is only as tall as its tallest child and an empty
            // box is its padding, which leaves the reader poking at
            // a corner that has quietly stopped listening.
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FooterEdge(
            field = startField,
            figure = footerFigure(startField, progress, reflowable, screens, bookScreens, device),
            color = color,
            clickLabel = stringResource(startSlot.cycleLabel),
            onClick = { onCycleField(startSlot, noteForField) },
            onLongClick = { onPickSlot(startSlot.pickTarget) },
            padding = PaddingValues(
                start = FOOTER_MARGIN,
                end = FOOTER_GAP,
                top = slotPadding,
                bottom = slotPadding,
            ),
            alignment = Alignment.CenterStart,
            modifier = Modifier.fillMaxHeight(),
        )
        val middle = middleText(progress, mode, reflowable, screens)
        FooterSlotBox(
            clickLabel = stringResource(R.string.footer_cycle_middle),
            onClick = { onCycleMode(noteForMode) },
            onLongClick = { onPickSlot(FooterPickTarget.MIDDLE) },
            padding = PaddingValues(horizontal = FOOTER_GAP, vertical = slotPadding),
            alignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            blankDescription = if (middle == null) {
                blankDescription(
                    mode.label,
                    asked = mode == FooterMode.EMPTY || mode == FooterMode.NONE,
                )
            } else {
                null
            },
        ) {
            Text(
                text = middle.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        FooterEdge(
            field = endField,
            figure = footerFigure(endField, progress, reflowable, screens, bookScreens, device),
            color = color,
            clickLabel = stringResource(endSlot.cycleLabel),
            onClick = { onCycleField(endSlot, noteForField) },
            onLongClick = { onPickSlot(endSlot.pickTarget) },
            padding = PaddingValues(
                start = FOOTER_GAP,
                end = FOOTER_MARGIN,
                top = slotPadding,
                bottom = slotPadding,
            ),
            alignment = Alignment.CenterEnd,
            modifier = Modifier.fillMaxHeight(),
        )
    }
}

/**
 * What a slot with nothing in it is announced as.
 *
 * The same two sentences the note uses after a tap, so a slot read out
 * an hour later says what the tap that set it said. [asked] separates
 * a slot that was told to show nothing from one that has a figure to
 * show and cannot show it yet.
 */
@Composable
private fun blankDescription(label: Int, asked: Boolean): String {
    val name = stringResource(label)
    return if (asked) name else stringResource(R.string.footer_note_unknown, name)
}

/** What a tap on this corner is announced as. */
private val FooterSlot.cycleLabel: Int
    get() = when (this) {
        FooterSlot.LEFT -> R.string.footer_cycle_left
        FooterSlot.RIGHT -> R.string.footer_cycle_right
    }

/** Which picker a long press on this corner opens. */
private val FooterSlot.pickTarget: FooterPickTarget
    get() = when (this) {
        FooterSlot.LEFT -> FooterPickTarget.LEFT
        FooterSlot.RIGHT -> FooterPickTarget.RIGHT
    }

/** The margin between an edge figure and the side of the screen. */
private val FOOTER_MARGIN = 20.dp

/** The gap kept between two slots. */
private val FOOTER_GAP = 6.dp

/**
 * The narrowest a footer edge's content is allowed to be.
 *
 * Two things hang on this. An edge set to nothing would otherwise be
 * nothing to tap, and the tap is how it is filled again — a setting
 * that can only be undone in a sheet two screens away is a trap laid
 * by a single tap. And an edge showing `2%` is four millimetres of
 * text, which is not a target a thumb can find; the middle has
 * [androidx.compose.foundation.layout.RowScope.weight] and would
 * otherwise take everything the figure did not want, right up to the
 * screen's corner.
 *
 * It is a minimum on the content rather than on the whole slot, so the
 * margin and the gap are added outside it and a blank corner is still
 * a corner. The width is invisible either way: it is blank paper at
 * the edge of a page that already keeps a margin there.
 */
private val FOOTER_EDGE_MIN_CONTENT = 28.dp

@Composable
private fun FooterEdge(
    field: FooterField,
    figure: FooterFigure?,
    color: Color,
    clickLabel: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    padding: PaddingValues,
    alignment: Alignment,
    modifier: Modifier = Modifier,
) {
    val text = figureText(figure)
    FooterSlotBox(
        clickLabel = clickLabel,
        onClick = onClick,
        onLongClick = onLongClick,
        padding = padding,
        alignment = alignment,
        modifier = modifier,
        minContentWidth = FOOTER_EDGE_MIN_CONTENT,
        blankDescription = if (text == null) {
            blankDescription(field.label, asked = field == FooterField.EMPTY)
        } else {
            null
        },
    ) {
        if (text == null) return@FooterSlotBox
        if (figure is FooterFigure.Battery) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    imageVector = if (figure.charging) {
                        Icons.Outlined.BatteryChargingFull
                    } else {
                        Icons.Outlined.BatteryStd
                    },
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(BATTERY_GLYPH),
                )
                FooterEdgeText(text, color)
            }
        } else {
            FooterEdgeText(text, color)
        }
    }
}

/**
 * How big the battery glyph is drawn.
 *
 * The charge is a percentage and so is the book's progress, and two
 * bare percentages a hand's width apart on the same line is how a
 * footer starts lying about which is which. The glyph says which one
 * this is without a word of explanation, at the size of the text it
 * stands beside.
 */
private val BATTERY_GLYPH = 12.dp

@Composable
private fun FooterEdgeText(text: FooterText, color: Color) {
    Text(
        text = text.text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        modifier = if (text.description == null) {
            Modifier
        } else {
            Modifier.clearAndSetSemantics { contentDescription = text.description }
        },
    )
}

/**
 * One tappable slot of the footer.
 *
 * The padding sits after the click rather than before it, which is
 * what makes the touch target bigger than the few characters drawn in
 * it. [minContentWidth] sits after the padding for the same reason in
 * the other direction: a minimum put on the whole slot would be eaten
 * by the twenty-dp margin the edges keep, and the corner would end up
 * narrower than the figure it holds rather than wider.
 *
 * The clickable is on this box rather than on the text, so the text
 * keeps the spoken description it sets for itself and the box keeps
 * the click.
 */
@Composable
private fun FooterSlotBox(
    clickLabel: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    padding: PaddingValues,
    alignment: Alignment,
    modifier: Modifier = Modifier,
    minContentWidth: Dp = Dp.Unspecified,
    blankDescription: String? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            // A slot drawing nothing has no text to be read out, and a
            // target announced by its actions alone leaves a screen
            // reader user tapping to find out what they have. The
            // description says which figure the slot is set to, and
            // whether it is a slot that was asked for nothing or one
            // that cannot answer yet.
            .then(
                if (blankDescription == null) {
                    Modifier
                } else {
                    Modifier.semantics { contentDescription = blankDescription }
                },
            )
            .combinedClickableWithoutRipple(
                onClick = onClick,
                onLongClick = onLongClick,
                onClickLabel = clickLabel,
                onLongClickLabel = stringResource(R.string.footer_pick_action),
            )
            .padding(padding)
            .then(
                if (minContentWidth == Dp.Unspecified) {
                    Modifier
                } else {
                    Modifier.widthIn(min = minContentWidth)
                },
            ),
        contentAlignment = alignment,
    ) {
        content()
    }
}

/** A figure as the footer draws it, and as it is read aloud. */
private data class FooterText(val text: String, val description: String?)

/**
 * The words for a figure, or null when there is nothing to say.
 *
 * The drawn form is short because an edge is narrow: `755 left` rather
 * than "755 pages left in the book", `137/892` rather than "137 of
 * 892". The spoken form is the long one, where there is room to say it
 * properly, and it is the only place the hedge on an estimated total
 * can be afforded.
 */
@Composable
private fun figureText(figure: FooterFigure?): FooterText? = when (figure) {
    null -> null

    is FooterFigure.BookPercent -> FooterText(
        text = stringResource(
            if (figure.left) R.string.footer_percent_left else R.string.footer_percent,
            figure.percent,
        ),
        description = pluralStringResource(
            if (figure.left) {
                R.plurals.footer_percent_left_a11y
            } else {
                R.plurals.footer_percent_read_a11y
            },
            figure.percent,
            figure.percent,
        ),
    )

    is FooterFigure.ChapterPercent -> FooterText(
        text = stringResource(
            if (figure.left) R.string.footer_percent_left else R.string.footer_percent,
            figure.percent,
        ),
        description = pluralStringResource(
            if (figure.left) {
                R.plurals.footer_chapter_percent_left_a11y
            } else {
                R.plurals.footer_chapter_percent_read_a11y
            },
            figure.percent,
            figure.percent,
        ),
    )

    // "137/892" rather than "137 of 892": the long form is what the
    // scrubber prints for stable locations, and the same shape in the
    // same corner for a different count is how a footer starts lying
    // about which of the two a reader is looking at.
    is FooterFigure.Pages -> FooterText(
        text = stringResource(R.string.footer_screen_compact, figure.page, figure.pages),
        description = stringResource(
            if (figure.exact) R.string.footer_page else R.string.footer_page_about,
            figure.page,
            figure.pages,
        ),
    )

    is FooterFigure.PagesLeftInBook -> FooterText(
        text = pluralStringResource(
            R.plurals.footer_pages_left_book,
            figure.pages,
            figure.pages,
        ),
        description = pluralStringResource(
            if (figure.exact) {
                R.plurals.footer_pages_left_book_a11y
            } else {
                R.plurals.footer_pages_left_book_about_a11y
            },
            figure.pages,
            figure.pages,
        ),
    )

    is FooterFigure.PagesInChapter -> FooterText(
        text = stringResource(R.string.footer_screen_compact, figure.page, figure.pages),
        description = stringResource(
            R.string.footer_page_in_chapter_a11y,
            figure.page,
            figure.pages,
        ),
    )

    is FooterFigure.PagesLeftInChapter ->
        if (figure.pages == 0) {
            FooterText(
                text = stringResource(R.string.footer_last_page_in_chapter_short),
                description = stringResource(R.string.footer_last_page_in_chapter),
            )
        } else {
            FooterText(
                text = pluralStringResource(
                    R.plurals.footer_pages_left_chapter_short,
                    figure.pages,
                    figure.pages,
                ),
                description = pluralStringResource(
                    R.plurals.footer_pages_left_in_chapter,
                    figure.pages,
                    figure.pages,
                ),
            )
        }

    is FooterFigure.TimeInBook -> durationText(figure.minutes).let {
        FooterText(it, stringResource(R.string.footer_left_in_book, it))
    }

    is FooterFigure.TimeInChapter -> durationText(figure.minutes).let {
        FooterText(it, stringResource(R.string.footer_left_in_chapter, it))
    }

    is FooterFigure.Location -> FooterText(
        text = stringResource(R.string.footer_location, figure.position),
        description = stringResource(
            R.string.footer_location_a11y,
            figure.position,
            figure.total,
        ),
    )

    is FooterFigure.Clock -> clockText(figure.atMillis).let {
        FooterText(it, stringResource(R.string.footer_clock_a11y, it))
    }

    is FooterFigure.Battery -> FooterText(
        text = stringResource(R.string.footer_percent, figure.percent),
        description = pluralStringResource(
            if (figure.charging) {
                R.plurals.footer_battery_charging_a11y
            } else {
                R.plurals.footer_battery_a11y
            },
            figure.percent,
            figure.percent,
        ),
    )
}

/**
 * The middle's string, or null when it has nothing to print.
 *
 * Every one of [FooterMode]'s figures is about the book, so a reading
 * position the navigator cannot give is the end of it. The corners are
 * not in the same position: the clock and the battery are answers the
 * device can always give, and they carry on while the page works
 * itself out.
 */
@Composable
private fun middleText(
    progress: ReaderProgress?,
    mode: FooterMode,
    reflowable: Boolean,
    screens: SectionScreens?,
): String? {
    if (progress == null) return null
    return when (val middle = footerMiddle(progress, mode, reflowable, screens)) {
        is FooterMiddle.TimeInChapter ->
            stringResource(R.string.footer_left_in_chapter, durationText(middle.minutes))

        is FooterMiddle.TimeInBook ->
            stringResource(R.string.footer_left_in_book, durationText(middle.minutes))

        is FooterMiddle.Chapter -> middle.title

        is FooterMiddle.PagesInChapter ->
            if (middle.pages == 0) {
                stringResource(R.string.footer_last_page_in_chapter)
            } else {
                pluralStringResource(
                    R.plurals.footer_pages_left_in_chapter,
                    middle.pages,
                    middle.pages,
                )
            }

        null -> null
    }
}
/** "45 mins", "2 hrs 5 mins", or a friendly line for nearly nothing left. */
@Composable
fun durationText(minutes: Int): String {
    if (minutes < 1) return stringResource(R.string.duration_under_minute)
    val hours = minutes / 60
    val remaining = minutes % 60
    val minutesText = pluralStringResource(R.plurals.duration_minutes, remaining, remaining)
    if (hours == 0) return minutesText
    val hoursText = pluralStringResource(R.plurals.duration_hours, hours, hours)
    if (remaining == 0) return hoursText
    return stringResource(R.string.duration_hours_and_minutes, hoursText, minutesText)
}

/**
 * A short ramp from the page's colour to nothing, drawn at the inner
 * edge of a piece of chrome.
 *
 * The chrome lies over the page rather than pushing it aside, so its
 * edges land wherever the type happens to be and cut a line in half.
 * The ramp makes that read as something covering the page instead of
 * as a rendering fault (#223). [solidAtTop] says which end the chrome
 * is on: the page's colour is there, and the fade runs away from it.
 *
 * Electronic paper gets nothing. A gradient is dithered there and
 * ghosts on the next repaint, so a clean hard edge is the better of
 * the two, as it is for [ChromePill]'s shadow.
 */
@Composable
fun ChromeEdgeFade(
    theme: ReaderTheme,
    solidAtTop: Boolean,
    modifier: Modifier = Modifier,
) {
    if (LocalEInk.current) return
    // The clear end of the ramp is the page's own colour at zero alpha,
    // not Color.Transparent, which is a transparent *black* and drags
    // the hue towards it wherever the two are interpolated unpremultiplied.
    val clear = theme.background.copy(alpha = 0f)
    val stops = if (solidAtTop) {
        listOf(theme.background, clear)
    } else {
        listOf(clear, theme.background)
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(CHROME_FADE_HEIGHT)
            .background(Brush.verticalGradient(stops)),
    )
}

/**
 * The scrubber shown with the reader chrome: drag to move through the
 * book, with a tick for every chapter and a preview of where you are
 * heading.
 */
@Composable
fun ReadingScrubber(
    progress: ReaderProgress?,
    theme: ReaderTheme,
    chapterTicks: List<Float>,
    titleAtPosition: (Int) -> String?,
    positionAtProgression: (Float) -> Int,
    onSeek: (Int) -> Unit,
    onGoToPage: () -> Unit,
    onGoToPercent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (progress == null) return
    var dragged by remember { mutableStateOf<Float?>(null) }
    var pending by remember { mutableFloatStateOf(0f) }
    val value = dragged ?: progress.totalProgression
    val previewPosition = positionAtProgression(value)
    val accent = theme.foreground

    Column(
        modifier
            .fillMaxWidth()
            .background(theme.background)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = titleAtPosition(previewPosition)
                ?: stringResource(R.string.footer_page, previewPosition, progress.totalPositions),
            style = MaterialTheme.typography.labelLarge,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Slider(
            value = value,
            onValueChange = {
                dragged = it
                pending = it
            },
            onValueChangeFinished = {
                dragged = null
                onSeek(positionAtProgression(pending))
            },
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = accent.copy(alpha = 0.2f),
            ),
            modifier = Modifier.chapterTicks(chapterTicks, accent.copy(alpha = 0.5f)),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FooterHint(
                stringResource(R.string.footer_percent, progress.percent),
                accent,
                Modifier
                    .clickableWithoutRipple(
                        onClick = onGoToPercent,
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.go_to_percent_title),
                    )
                    .heightIn(min = 48.dp)
                    .wrapContentHeight(Alignment.CenterVertically),
            )
            FooterHint(
                stringResource(R.string.footer_page, previewPosition, progress.totalPositions),
                accent,
                Modifier
                    .clickableWithoutRipple(
                        onClick = onGoToPage,
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.go_to_page_title),
                    )
                    // The readouts are lines of small print, so the tap
                    // targets are grown to a comfortable size around them
                    // rather than left the height of the text.
                    .heightIn(min = 48.dp)
                    .wrapContentHeight(Alignment.CenterVertically),
            )
        }
    }
}

@Composable
private fun FooterHint(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color.copy(alpha = 0.6f),
        modifier = modifier,
    )
}

/**
 * Clicks that do not draw a ripple: the reader chrome sits over the
 * page and should stay quiet, but must still swallow taps so they
 * don't turn the page.
 */
@Composable
private fun Modifier.clickableWithoutRipple(
    onClick: () -> Unit,
    role: Role? = null,
    onClickLabel: String? = null,
): Modifier =
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        role = role,
        onClickLabel = onClickLabel,
        onClick = onClick,
    )

/** The same, for a slot that answers a long press as well as a tap. */
@Composable
private fun Modifier.combinedClickableWithoutRipple(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClickLabel: String? = null,
    onLongClickLabel: String? = null,
): Modifier =
    combinedClickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        role = Role.Button,
        onClickLabel = onClickLabel,
        onLongClickLabel = onLongClickLabel,
        onLongClick = onLongClick,
        onClick = onClick,
    )

/**
 * The rounded bar the reader chrome speaks from: the page's own ink,
 * with the page's own paper written on it.
 *
 * The lift is drawn two different ways. On a backlit screen a shadow
 * and a hair of translucency place it above the text. Electronic paper
 * has neither to give: a shadow is dithered into a halo of grey specks
 * that then ghosts, and 92% of an ink-coloured bar over a page of text
 * is that text, faintly, showing through the words on top of it. There
 * the bar is simply solid, which separates it from the page more
 * plainly than either.
 */
@Composable
private fun ChromePill(
    theme: ReaderTheme,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val eInk = LocalEInk.current
    Surface(
        shape = RoundedCornerShape(50),
        color = if (eInk) theme.foreground else theme.foreground.copy(alpha = 0.92f),
        contentColor = theme.background,
        shadowElevation = if (eInk) 0.dp else 6.dp,
        // The column this sits in no longer takes the navigation bar's
        // room, since the scrubber under it must reach the screen's
        // edge. A pill must still keep out of a bar stood on its side,
        // which is where a landscape phone puts it.
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
            .padding(16.dp),
        content = content,
    )
}

/** Small marks along the scrubber showing where chapters begin. */
private fun Modifier.chapterTicks(ticks: List<Float>, color: Color): Modifier =
    drawWithContent {
        drawContent()
        if (ticks.isEmpty()) return@drawWithContent
        // The track is inset by the thumb radius on both sides.
        val inset = 10.dp.toPx()
        val usable = size.width - inset * 2
        val height = 6.dp.toPx()
        ticks.forEach { tick ->
            if (tick <= 0.01f || tick >= 0.99f) return@forEach
            val x = inset + usable * tick
            drawLine(
                color = color,
                start = Offset(x, (size.height - height) / 2),
                end = Offset(x, (size.height + height) / 2),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
    }

/**
 * The "back to where I was" pill offered after a jump, so exploring
 * the contents or the scrubber is never a one-way trip.
 */
@Composable
fun JumpBackPill(
    position: Int?,
    fromSync: Boolean,
    excerpt: String?,
    remoteAt: Long?,
    confidence: ResumeConfidence,
    resumePosition: Int?,
    theme: ReaderTheme,
    onJumpBack: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ChromePill(theme = theme, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clickableWithoutRipple(onJumpBack),
            ) {
                Icon(
                    imageVector = if (fromSync) Icons.Outlined.CloudSync else Icons.Outlined.Undo,
                    contentDescription = null,
                )
                Column {
                    Text(
                        text = if (fromSync) {
                            resumeHeadline(resumePosition, remoteAt, confidence)
                        } else if (position != null) {
                            stringResource(R.string.jump_back_to_page, position)
                        } else {
                            stringResource(R.string.jump_back)
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (fromSync && !excerpt.isNullOrBlank()) {
                        Text(
                            text = excerpt,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (fromSync) {
                        Text(
                            text = position?.let {
                                stringResource(R.string.jump_back_to_page, it)
                            } ?: stringResource(R.string.jump_back),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                if (fromSync) {
                    Icon(
                        imageVector = Icons.Outlined.Undo,
                        contentDescription = if (position != null) {
                            stringResource(R.string.jump_back_to_page, position)
                        } else {
                            stringResource(R.string.jump_back)
                        },
                    )
                }
            }
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.dismiss),
                modifier = Modifier
                    .clickableWithoutRipple(onDismiss)
                    .padding(4.dp),
            )
        }
    }
}

/**
 * The offer to continue where another device has read further. The
 * same shape as [JumpBackPill], because it is the same bargain in the
 * other direction: one tap to take the place, one to wave it away.
 */
@Composable
fun CatchUpPill(
    position: Int?,
    excerpt: String?,
    remoteAt: Long?,
    confidence: ResumeConfidence,
    theme: ReaderTheme,
    onCatchUp: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ChromePill(theme = theme, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.clickableWithoutRipple(onCatchUp),
            ) {
                Icon(Icons.Outlined.Redo, contentDescription = null)
                Column {
                    Text(
                        text = if (position != null) {
                            val base = if (confidence == ResumeConfidence.EXACT) {
                                stringResource(R.string.catch_up_to_page, position)
                            } else {
                                stringResource(R.string.catch_up_near_page, position)
                            }
                            relativeAge(remoteAt)?.let { "$base · $it" } ?: base
                        } else {
                            stringResource(R.string.catch_up)
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (!excerpt.isNullOrBlank()) {
                        Text(
                            text = excerpt,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.dismiss),
                modifier = Modifier
                    .clickableWithoutRipple(onDismiss)
                    .padding(4.dp),
            )
        }
    }
}

@Composable
private fun resumeHeadline(
    position: Int?,
    remoteAt: Long?,
    confidence: ResumeConfidence,
): String {
    val base = if (confidence == ResumeConfidence.APPROXIMATE && position != null) {
        stringResource(R.string.resumed_near_page, position)
    } else {
        stringResource(R.string.resumed_from_device)
    }
    return relativeAge(remoteAt)?.let { "$base · $it" } ?: base
}

/**
 * How long ago the server recorded something, in the words the pills and
 * the sync dialog both use, so there is only one spelling of "3 hours
 * ago" in the app.
 *
 * A server's clock is a server's clock: a timestamp from the future ages
 * to "just now" rather than counting down to it.
 */
@Composable
internal fun relativeAge(timestamp: Long?): String? {
    timestamp ?: return null
    if (timestamp <= 0L) return null
    val minutes = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0L) / 60_000L).toInt()
    return when {
        minutes < 1 -> stringResource(R.string.remote_age_now)
        minutes < 60 -> stringResource(R.string.remote_age_minutes, minutes)
        minutes < 1_440 -> stringResource(R.string.remote_age_hours, minutes / 60)
        else -> stringResource(R.string.remote_age_days, minutes / 1_440)
    }
}
