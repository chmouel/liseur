package com.chmouel.liseur.reader.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.domain.SeriesCompletion
import com.chmouel.liseur.domain.displayTitle
import com.chmouel.liseur.domain.seriesIndexLabel
import com.chmouel.liseur.reader.NextUp
import com.chmouel.liseur.reader.NextVolumeAvailability
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.library.BookCover
import com.chmouel.liseur.ui.windowWidth

/**
 * The page past the last page: a quiet colophon instead of the empty
 * columns Readium would keep turning through.
 *
 * It is a page, so the same tap zones apply — the back side returns to
 * the last page of the book, the forward side stays put. The actions
 * stay at the foot of the page so a short window or a large font cannot
 * push them off the screen; the title above them scrolls if it must.
 * The next volume is a small card of its own: cover, name, and one
 * action, in the reading colours rather than a second chrome.
 */
@Composable
fun Endpaper(
    title: String,
    author: String?,
    theme: ReaderTheme,
    seriesName: String? = null,
    finishedVolume: String? = null,
    next: NextUp?,
    missingIndex: Double? = null,
    noNextInLibrary: Boolean = false,
    seriesCompletion: SeriesCompletion?,
    rtl: Boolean,
    onTurnBack: () -> Unit,
    onLibrary: () -> Unit,
    onOpenNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    var size by remember { mutableStateOf(IntSize.Zero) }
    val description = stringResource(R.string.the_end)
    Box(
        modifier
            .fillMaxSize()
            .background(theme.background)
            .semantics { contentDescription = description }
            .onSizeChanged { size = it }
            .pointerInput(size, rtl, density) {
                if (size.width <= 0 || size.height <= 0) return@pointerInput
                detectTapGestures { offset ->
                    val zone = ReaderTapZones.zoneAt(
                        x = offset.x,
                        y = offset.y,
                        width = size.width.toFloat(),
                        height = size.height.toFloat(),
                        density = density,
                    )
                    val forward = when (zone) {
                        ReaderTapZones.Zone.BACK -> rtl
                        ReaderTapZones.Zone.FORWARD -> !rtl
                        ReaderTapZones.Zone.CHROME -> return@detectTapGestures
                    }
                    if (!forward) onTurnBack()
                }
            },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxHeight()
                .fillMaxWidth()
                .widthIn(max = contentWidthCap(windowWidth()))
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 28.dp, vertical = 16.dp),
        ) {
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = maxHeight)
                        .verticalScroll(rememberScrollState()),
                ) {
                    EndRule(color = theme.foreground.copy(alpha = 0.35f))
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = stringResource(R.string.the_end),
                        style = MaterialTheme.typography.headlineMedium,
                        fontStyle = FontStyle.Italic,
                        color = theme.foreground,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    if (title.isNotBlank()) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            color = theme.foreground,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val seriesLine = finishedSeriesLine(seriesName, finishedVolume)
                    if (seriesLine != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = seriesLine,
                            style = MaterialTheme.typography.bodyMedium,
                            color = theme.foreground.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!author.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = author,
                            style = MaterialTheme.typography.bodyLarge,
                            fontStyle = FontStyle.Italic,
                            color = theme.foreground.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            val missingLabel = seriesIndexLabel(missingIndex)
            if (missingLabel != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.series_missing_volume, missingLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.foreground.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            if (next != null) {
                Spacer(Modifier.height(if (missingLabel != null) 10.dp else 16.dp))
                EndpaperNextOffer(
                    next = next,
                    color = theme.foreground,
                    onOpenNext = onOpenNext,
                )
            } else if (missingLabel == null) {
                val copy = if (noNextInLibrary) {
                    stringResource(R.string.endpaper_no_next_in_library)
                } else {
                    seriesCompletionCopy(seriesCompletion)
                }
                copy?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.foreground.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
            EndpaperAction(
                text = stringResource(R.string.endpaper_library),
                color = theme.foreground.copy(
                    alpha = if (next != null || missingLabel != null) 0.55f else 1f,
                ),
                onClick = onLibrary,
            )
        }
    }
}

@Composable
private fun EndpaperNextOffer(
    next: NextUp,
    color: Color,
    onOpenNext: () -> Unit,
) {
    val clickable = when (next.availability) {
        is NextVolumeAvailability.Ready,
        NextVolumeAvailability.Remote,
        NextVolumeAvailability.Failed,
        NextVolumeAvailability.Queued,
        is NextVolumeAvailability.Downloading,
        -> true
        NextVolumeAvailability.Unavailable -> false
    }
    val eInk = LocalEInk.current
    val shape = RoundedCornerShape(14.dp)
    val kicker = next.volume?.let { stringResource(R.string.endpaper_next_kicker_numbered, it) }
        ?: stringResource(R.string.endpaper_next_kicker)
    val action = nextActionLabel(next)
    val title = next.book.displayTitle
    val fraction = (next.availability as? NextVolumeAvailability.Downloading)?.fraction
    val description = "$kicker. $title. $action"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (eInk) {
                    Modifier.border(1.dp, color.copy(alpha = 0.4f), shape)
                } else {
                    Modifier
                        .background(color.copy(alpha = 0.07f), shape)
                        .border(1.dp, color.copy(alpha = 0.12f), shape)
                },
            )
            .then(
                if (clickable) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOpenNext,
                    )
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(12.dp),
    ) {
        BookCover(
            book = next.book,
            modifier = Modifier
                .width(76.dp)
                .aspectRatio(2f / 3f),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = kicker.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.4.sp,
                color = color.copy(alpha = 0.55f),
                maxLines = 1,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = color.copy(alpha = if (clickable) 1f else 0.7f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = action,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = color.copy(alpha = if (clickable) 0.9f else 0.55f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (fraction != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    color = color,
                    trackColor = color.copy(alpha = 0.2f),
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                )
            }
        }
    }
}

@Composable
private fun nextActionLabel(next: NextUp): String = when (next.availability) {
    is NextVolumeAvailability.Ready -> stringResource(R.string.endpaper_continue)
    NextVolumeAvailability.Remote -> stringResource(R.string.endpaper_download)
    NextVolumeAvailability.Queued -> stringResource(R.string.endpaper_waiting)
    is NextVolumeAvailability.Downloading -> stringResource(R.string.endpaper_downloading)
    NextVolumeAvailability.Failed -> stringResource(R.string.endpaper_retry)
    NextVolumeAvailability.Unavailable -> stringResource(R.string.endpaper_unavailable)
}

@Composable
private fun finishedSeriesLine(seriesName: String?, finishedVolume: String?): String? {
    val series = seriesName?.takeIf { it.isNotBlank() } ?: return null
    val volume = finishedVolume?.takeIf { it.isNotBlank() } ?: return series
    return stringResource(R.string.series_position, series, volume)
}

@Composable
private fun seriesCompletionCopy(completion: SeriesCompletion?): String? = when (completion) {
    SeriesCompletion.COMPLETE -> stringResource(R.string.series_all_read)
    SeriesCompletion.CAUGHT_UP -> stringResource(R.string.series_caught_up)
    SeriesCompletion.ALL_KNOWN_READ -> stringResource(R.string.series_all_known_read)
    SeriesCompletion.IN_PROGRESS, null -> null
}

@Composable
private fun EndpaperAction(
    text: String,
    color: Color,
    onClick: (() -> Unit)?,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** A colophon rule: two thin lines with a point between them. */
@Composable
private fun EndRule(color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(0.42f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(color),
        )
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(color),
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(color),
        )
    }
}
