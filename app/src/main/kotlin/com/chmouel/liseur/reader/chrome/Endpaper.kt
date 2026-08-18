package com.chmouel.liseur.reader.chrome

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth

/**
 * The page past the last page: a quiet colophon instead of the empty
 * columns Readium would keep turning through.
 *
 * It is a page, so the same tap zones apply — the back side returns to
 * the last page of the book, the forward side stays put. The actions
 * stay at the foot of the page so a short window or a large font cannot
 * push them off the screen; the title above them scrolls if it must.
 */
@Composable
fun Endpaper(
    title: String,
    author: String?,
    theme: ReaderTheme,
    nextTitle: String?,
    nextVolume: String?,
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
                .padding(horizontal = 32.dp, vertical = 16.dp),
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
                    Spacer(Modifier.height(20.dp))
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
                    if (!author.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
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
            if (nextTitle != null) {
                Spacer(Modifier.height(12.dp))
                EndpaperAction(
                    text = if (nextVolume != null) {
                        stringResource(R.string.endpaper_next_numbered, nextVolume, nextTitle)
                    } else {
                        stringResource(R.string.endpaper_next, nextTitle)
                    },
                    color = theme.foreground,
                    onClick = onOpenNext,
                )
            }
            EndpaperAction(
                text = stringResource(R.string.endpaper_library),
                color = theme.foreground.copy(alpha = if (nextTitle != null) 0.7f else 1f),
                onClick = onLibrary,
            )
        }
    }
}

@Composable
private fun EndpaperAction(
    text: String,
    color: Color,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
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
