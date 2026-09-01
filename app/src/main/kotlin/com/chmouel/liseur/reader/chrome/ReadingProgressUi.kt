package com.chmouel.liseur.reader.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.remote.ResumeConfidence
import com.chmouel.liseur.data.settings.FooterMode
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.reader.progress.FooterMiddle
import com.chmouel.liseur.reader.progress.ReaderProgress
import com.chmouel.liseur.reader.progress.footerMiddle
import com.chmouel.liseur.ui.LocalEInk

/**
 * The quiet line of text at the bottom of the page, Kindle-style. The
 * percentage read sits on the left and the page number on the right,
 * always; the middle carries the smart slot — time left, the chapter's
 * name — and tapping the footer cycles what that slot shows. Taps
 * never turn the page.
 */
@Composable
fun ReadingFooter(
    progress: ReaderProgress?,
    mode: FooterMode,
    theme: ReaderTheme,
    onCycleMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (mode == FooterMode.NONE || progress == null) return
    val color = theme.foreground.copy(alpha = 0.6f)
    Row(
        modifier
            .fillMaxWidth()
            .clickableWithoutRipple(onCycleMode)
            .padding(horizontal = 20.dp, vertical = FooterMetrics.VERTICAL_PADDING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FooterEdge(stringResource(R.string.footer_percent, progress.percent), color)
        Text(
            text = middleText(progress, mode).orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        FooterEdge(
            stringResource(R.string.footer_page_compact, progress.position, progress.totalPositions),
            color,
        )
    }
}

@Composable
private fun FooterEdge(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
    )
}

@Composable
private fun middleText(progress: ReaderProgress, mode: FooterMode): String? =
    when (val middle = footerMiddle(progress, mode)) {
        is FooterMiddle.TimeInChapter ->
            stringResource(R.string.footer_left_in_chapter, durationText(middle.minutes))

        is FooterMiddle.TimeInBook ->
            stringResource(R.string.footer_left_in_book, durationText(middle.minutes))

        is FooterMiddle.Chapter -> middle.title

        null -> null
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
        modifier = modifier.padding(16.dp),
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
/**
 * The next volume of the series, offered on the last page of the one
 * just finished.
 *
 * It carries the title rather than only a number, because "Book 4" is
 * not what anyone decides to keep reading on.
 */
@Composable
fun NextInSeriesPill(
    title: String,
    volume: String?,
    theme: ReaderTheme,
    onOpen: () -> Unit,
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
                    .clickableWithoutRipple(onOpen),
            ) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null)
                Text(
                    text = if (volume != null) {
                        stringResource(R.string.next_in_series_numbered, volume, title)
                    } else {
                        stringResource(R.string.next_in_series, title)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
