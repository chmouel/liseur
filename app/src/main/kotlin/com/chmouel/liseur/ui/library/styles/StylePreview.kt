package com.chmouel.liseur.ui.library.styles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.domain.LibraryStyle
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.browseCover

private val PREVIEW_WIDTH = 64.dp
private val PREVIEW_HEIGHT = 88.dp
private const val COVER_ASPECT = 2f / 3f

/**
 * A thumbnail of what [style] looks like, drawn with the reader's own
 * covers where there are some, so the choice can be judged before it is
 * made. Decorative: the row it sits in says which style it is.
 */
@Composable
internal fun StylePreview(
    style: LibraryStyle,
    books: List<Book>,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .size(PREVIEW_WIDTH, PREVIEW_HEIGHT)
            .clip(shape)
            .background(colors.surfaceVariant)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) colors.primary else colors.outlineVariant,
                shape = shape,
            )
            .padding(5.dp),
    ) {
        val cover = { i: Int -> books.getOrNull(i % books.size.coerceAtLeast(1)) }
        when (style) {
            LibraryStyle.CLASSIC -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(3.dp))
                        .background(colors.surface)
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MiniCover(cover(0), index = 0, modifier = Modifier.height(18.dp))
                    TextLines(count = 2, modifier = Modifier.weight(1f))
                }
                CoverGrid(rows = 2, columns = 3, first = 1, cover = cover)
            }

            LibraryStyle.HOME -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    MiniCover(cover(0), index = 0, modifier = Modifier.height(28.dp))
                    TextLines(count = 3, modifier = Modifier.weight(1f).padding(top = 2.dp))
                }
                repeat(2) { strip ->
                    TextLine(Modifier.fillMaxWidth(0.5f))
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        repeat(4) { i ->
                            val n = 1 + strip * 4 + i
                            MiniCover(cover(n), index = n, modifier = Modifier.height(15.dp))
                        }
                    }
                }
            }

            LibraryStyle.SHELVES -> Column(
                Modifier.fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                repeat(2) { shelf ->
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            repeat(3) { i ->
                                val n = shelf * 3 + i
                                MiniCover(cover(n), index = n, modifier = Modifier.weight(1f))
                            }
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(colors.outline),
                        )
                    }
                }
            }

            LibraryStyle.LIST -> Column(
                Modifier.fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                repeat(4) { i ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MiniCover(cover(i), index = i, modifier = Modifier.height(15.dp))
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            TextLine(Modifier.fillMaxWidth(0.9f))
                            ProgressLine(fraction = listOf(0.8f, 0.45f, 1f, 0.2f)[i])
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.CoverGrid(rows: Int, columns: Int, first: Int, cover: (Int) -> Book?) {
    repeat(rows) { r ->
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(columns) { c ->
                val n = first + r * columns + c
                MiniCover(cover(n), index = n, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * One cover at thumbnail size. Without artwork it is a block in one of
 * the theme's container colours, so an empty library still shows the
 * shape of the style.
 */
@Composable
private fun MiniCover(book: Book?, index: Int, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val fallback = when (index % 3) {
        0 -> colors.primaryContainer
        1 -> colors.tertiaryContainer
        else -> colors.secondaryContainer
    }
    val shaped = modifier
        .aspectRatio(COVER_ASPECT)
        .clip(RoundedCornerShape(1.5.dp))
        .background(fallback)
    val artwork = book?.coverPath ?: book?.coverUrl
    if (book == null || artwork == null) {
        Box(shaped)
        return
    }
    val context = LocalContext.current
    val eInk = LocalEInk.current
    val request = remember(artwork, eInk, context, book.browseServerId) {
        ImageRequest.Builder(context)
            .data(artwork)
            .browseCover(artwork, book.coverUrl, book.browseServerId)
            .crossfade(!eInk)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = shaped,
    )
}

@Composable
private fun TextLines(count: Int, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(count) { i -> TextLine(Modifier.fillMaxWidth(if (i == count - 1) 0.6f else 1f)) }
    }
}

@Composable
private fun TextLine(modifier: Modifier = Modifier, height: Dp = 2.5.dp) {
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(1.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)),
    )
}

@Composable
private fun ProgressLine(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}
