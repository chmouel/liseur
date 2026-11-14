package com.chmouel.liseur.ui.library.styles

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.quotes.HomeQuote
import com.chmouel.liseur.domain.HomePick
import com.chmouel.liseur.domain.HomeSeriesSet
import com.chmouel.liseur.domain.HomeShelfKind
import com.chmouel.liseur.domain.HomeShelfTile
import com.chmouel.liseur.domain.PickReason
import com.chmouel.liseur.domain.SeriesShelf
import com.chmouel.liseur.domain.displayAuthor
import com.chmouel.liseur.domain.displayTitle
import com.chmouel.liseur.domain.seriesIndexLabel
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.WidthClass
import com.chmouel.liseur.ui.library.BookCover
import com.chmouel.liseur.ui.library.ContinueReading
import com.chmouel.liseur.ui.library.HeroDetail
import com.chmouel.liseur.ui.library.LibraryUiState
import com.chmouel.liseur.ui.theme.HomePalette
import com.chmouel.liseur.ui.theme.TileTint
import com.chmouel.liseur.ui.theme.homePalette
import com.chmouel.liseur.ui.widthClass
import kotlin.math.ceil

/**
 * The Home tab: a short desk about the book being read and what comes
 * next, ending in a way through to the whole Library. It scrolls on its
 * own and never shows the catalogue grid, so it cannot be mistaken for
 * the Library tab.
 *
 * Everything on it comes from the whole shelf rather than from what the
 * Library's filters leave, so narrowing the Library never empties Home.
 */
@Composable
internal fun HomeLibrary(
    state: LibraryUiState,
    hero: HeroDetail?,
    listState: LazyListState,
    onBookSelected: (Book) -> Unit,
    onBookLongPress: (Book) -> Unit,
    onSeriesSelected: (SeriesShelf) -> Unit,
    onOpenShelf: (HomeShelfKind) -> Unit,
    onBrowseAll: () -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
    quote: HomeQuote? = null,
    onOpenQuote: (HomeQuote) -> Unit = {},
    onAnotherQuote: () -> Unit = {},
) {
    val desk = state.home
    val wide = widthClass() != WidthClass.COMPACT
    val current = state.continueReading
    val starter = desk.starter
    // The strips scroll to the screen edge; everything else keeps the gutter.
    val gutter = Modifier.padding(horizontal = 16.dp)
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(key = "home:top") {
            val heroCard: @Composable (Modifier) -> Unit = { cardModifier ->
                when {
                    current != null -> HomeHero(
                        entry = current,
                        detail = hero?.takeIf { it.bookUrl == current.book.url },
                        onOpen = { onBookSelected(current.book) },
                        onActions = { onBookLongPress(current.book) },
                        modifier = cardModifier,
                    )

                    starter != null -> StarterHero(
                        book = starter,
                        onOpen = { onBookSelected(starter) },
                        onActions = { onBookLongPress(starter) },
                        modifier = cardModifier,
                    )
                }
            }
            val hasHero = current != null || starter != null
            if (wide && hasHero) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = gutter) {
                    heroCard(Modifier.weight(0.55f))
                    ShelfTiles(desk.tiles, onOpenShelf, onBrowseAll, Modifier.weight(0.45f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp), modifier = gutter) {
                    if (hasHero) heroCard(Modifier.fillMaxWidth())
                    ShelfTiles(desk.tiles, onOpenShelf, onBrowseAll, Modifier.fillMaxWidth())
                }
            }
        }
        if (quote != null) {
            item(key = "home:quote") {
                QuoteCard(
                    quote = quote,
                    onOpen = { onOpenQuote(quote) },
                    onAnother = onAnotherQuote,
                    modifier = gutter.fillMaxWidth(),
                )
            }
        }
        if (desk.seriesSets.isNotEmpty()) {
            item(key = "home:series:header") {
                SectionHeader(stringResource(R.string.home_series_underway), gutter)
            }
            desk.seriesSets.chunked(if (wide) 2 else 1).forEach { row ->
                item(key = "home:series:${row.first().shelf.key}") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = gutter) {
                        row.forEach { set ->
                            SeriesSetCard(
                                set = set,
                                onClick = { onSeriesSelected(set.shelf) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size == 1 && wide) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        if (desk.pickUpNext.isNotEmpty()) {
            item(key = "home:picks") {
                Column {
                    SectionHeader(stringResource(R.string.home_pick_up_next), gutter)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) {
                        items(desk.pickUpNext, key = { it.book.id }) { pick ->
                            PickTile(
                                pick = pick,
                                state = state,
                                onClick = { onBookSelected(pick.book) },
                                onLongClick = { onBookLongPress(pick.book) },
                            )
                        }
                    }
                }
            }
        }
        if (desk.recentlyAdded.isNotEmpty()) {
            item(key = "home:recent") {
                Column {
                    SectionHeader(stringResource(R.string.home_recently_added), gutter)
                    val width = stripCoverWidth()
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) {
                        items(desk.recentlyAdded, key = { it.id }) { book ->
                            CoverTile(
                                book = book,
                                download = state.downloads[book.url],
                                progression = state.progressions[book.url],
                                onClick = { onBookSelected(book) },
                                onLongClick = { onBookLongPress(book) },
                                title = true,
                                modifier = Modifier.width(width),
                            )
                        }
                    }
                }
            }
        }
        if (desk.shelfSize > 0) {
            item(key = "home:browse") {
                Box(gutter.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    OutlinedButton(onClick = onBrowseAll) {
                        Text(pluralStringResource(R.plurals.home_browse_all, desk.shelfSize, desk.shelfSize))
                    }
                }
            }
        }
    }
}

@Composable
private fun stripCoverWidth(): Dp = when (widthClass()) {
    WidthClass.COMPACT -> 96.dp
    WidthClass.MEDIUM -> 112.dp
    WidthClass.EXPANDED -> 128.dp
}

/**
 * A card on the desk. On e-ink it is an outline on the page instead of a
 * tinted block, which the panel would dither into grey noise.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeskCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    shape: Shape = RoundedCornerShape(16.dp),
    color: Color? = null,
    content: @Composable () -> Unit,
) {
    val eInk = LocalEInk.current
    val palette = homePalette()
    val haptics = LocalHapticFeedback.current
    Surface(
        shape = shape,
        color = when {
            eInk -> Color.Transparent
            color != null -> color
            else -> palette?.card ?: MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = when {
            eInk -> BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            palette != null -> BorderStroke(1.dp, palette.cardBorder)
            else -> null
        },
        shadowElevation = if (palette != null) 2.dp else 0.dp,
        modifier = modifier
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onClickLabel = onClickLabel,
                onLongClick = onLongClick?.let { long ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        long()
                    }
                },
                onLongClickLabel = onLongClick?.let { stringResource(R.string.book_actions) },
            ),
    ) {
        content()
    }
}

/** A line from a book already read; opening it goes to the passage. */
@Composable
private fun QuoteCard(
    quote: HomeQuote,
    onOpen: () -> Unit,
    onAnother: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DeskCard(
        onClick = onOpen,
        onClickLabel = stringResource(R.string.home_quote_open),
        modifier = modifier,
    ) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        if (quote.highlight) R.string.home_quote_highlight else R.string.home_quote_title,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onAnother) {
                    Icon(Icons.Outlined.Shuffle, contentDescription = stringResource(R.string.home_quote_another))
                }
            }
            Text(
                text = stringResource(R.string.home_quote_text, quote.text),
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif),
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(end = 12.dp),
            )
            val source = listOfNotNull(quote.book.displayTitle, quote.book.displayAuthor).joinToString(", ")
            Text(
                text = stringResource(R.string.home_quote_source, source),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp, end = 12.dp),
            )
            quote.chapter?.let { chapter ->
                Text(
                    text = chapter,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }
    }
}

/** A small label on a cover or a card; solid with a border on e-ink so it stays legible. */
@Composable
private fun Pill(text: String, modifier: Modifier = Modifier) {
    val eInk = LocalEInk.current
    val palette = homePalette()
    Surface(
        shape = CircleShape,
        color = when {
            eInk -> MaterialTheme.colorScheme.surface
            palette != null -> palette.pill
            else -> MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = when {
            eInk -> MaterialTheme.colorScheme.onSurface
            palette != null -> palette.onPill
            else -> MaterialTheme.colorScheme.onSecondaryContainer
        },
        border = if (eInk) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun timeLeftText(ms: Long): String {
    val minutes = (ms / 60_000L).toInt().coerceAtLeast(1)
    return if (minutes < 60) {
        stringResource(R.string.home_time_left_minutes, minutes)
    } else {
        stringResource(R.string.home_time_left_hours, minutes / 60, minutes % 60)
    }
}

/** The book being read, as the first and largest thing on the screen. */
@Composable
private fun HomeHero(
    entry: ContinueReading,
    detail: HeroDetail?,
    onOpen: () -> Unit,
    onActions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progression = entry.progression
    val pages = entry.book.remotePageCount?.takeIf { it > 0 }
    val facts = listOfNotNull(
        if (pages != null && progression != null) {
            val page = ceil(progression * pages).toInt().coerceIn(1, pages)
            stringResource(R.string.home_page_of, page, pages)
        } else {
            null
        },
        detail?.timeLeftMs?.let { timeLeftText(it) },
    )
    HeroCard(
        book = entry.book,
        label = stringResource(R.string.continue_reading),
        chapter = detail?.chapter,
        progression = progression,
        facts = facts.joinToString(" · "),
        action = stringResource(R.string.home_continue),
        onOpen = onOpen,
        onActions = onActions,
        modifier = modifier,
    )
}

/** In the hero's place when nothing is under way: the first unread book, to begin. */
@Composable
private fun StarterHero(
    book: Book,
    onOpen: () -> Unit,
    onActions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HeroCard(
        book = book,
        label = stringResource(R.string.home_start_reading),
        chapter = null,
        progression = null,
        facts = "",
        action = stringResource(R.string.home_start_reading),
        onOpen = onOpen,
        onActions = onActions,
        modifier = modifier,
    )
}

@Composable
private fun HeroCard(
    book: Book,
    label: String,
    chapter: String?,
    progression: Double?,
    facts: String,
    action: String,
    onOpen: () -> Unit,
    onActions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val eInk = LocalEInk.current
    val palette = homePalette()
    val wide = widthClass() != WidthClass.COMPACT
    DeskCard(
        onClick = onOpen,
        onLongClick = onActions,
        onClickLabel = stringResource(R.string.read_book),
        shape = RoundedCornerShape(24.dp),
        modifier = modifier,
    ) {
        Box {
            palette?.let { HeroBackdrop(book, it, Modifier.matchParentSize()) }
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = label.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.8.sp,
                        color = palette?.onPill ?: MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    chapter?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Text(
                        text = book.displayTitle,
                        style = if (wide) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    book.displayAuthor?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    progression?.let { fraction ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 14.dp),
                        ) {
                            LinearProgressIndicator(
                                progress = { fraction.toFloat() },
                                strokeCap = StrokeCap.Round,
                                drawStopIndicator = {},
                                color = palette?.progress ?: MaterialTheme.colorScheme.primary,
                                trackColor = palette?.track ?: MaterialTheme.colorScheme.surfaceVariant,
                                gapSize = 0.dp,
                                modifier = Modifier.weight(1f).height(6.dp),
                            )
                            Text(
                                text = "${(fraction * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                    }
                    if (facts.isNotEmpty()) {
                        Text(
                            text = facts,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    // A phone at 360dp leaves the text column about 160dp:
                    // too little for the icon, the label and the actions button.
                    BoxWithConstraints(Modifier.padding(top = 16.dp)) {
                    val roomy = maxWidth >= 200.dp
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (eInk || palette == null) {
                            FilledTonalButton(
                                onClick = onOpen,
                                contentPadding = if (roomy) {
                                    ButtonDefaults.ButtonWithIconContentPadding
                                } else {
                                    ButtonDefaults.TextButtonContentPadding
                                },
                                modifier = Modifier.weight(1f, fill = false),
                            ) { HeroActionLabel(action, icon = roomy) }
                        } else {
                            Button(
                                onClick = onOpen,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = palette.accent,
                                    contentColor = palette.onAccent,
                                ),
                                contentPadding = PaddingValues(horizontal = if (roomy) 20.dp else 16.dp, vertical = 12.dp),
                                // Measured after the actions button, so a long
                                // label gives way rather than pushing it out.
                                modifier = Modifier.weight(1f, fill = false),
                            ) { HeroActionLabel(action, icon = roomy) }
                        }
                        Surface(
                            shape = CircleShape,
                            color = palette?.card ?: Color.Transparent,
                            border = BorderStroke(1.dp, palette?.cardBorder ?: MaterialTheme.colorScheme.outline),
                            modifier = Modifier.padding(start = 12.dp).size(44.dp),
                        ) {
                            IconButton(onClick = onActions) {
                                Icon(
                                    Icons.Outlined.MoreHoriz,
                                    contentDescription = stringResource(R.string.book_actions),
                                )
                            }
                        }
                    }
                    }
                }
                BookCover(
                    book = book,
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .width(if (wide) 132.dp else 112.dp)
                        .height(if (wide) 198.dp else 168.dp)
                        .then(if (eInk) Modifier else Modifier.shadow(12.dp, RoundedCornerShape(10.dp))),
                )
            }
        }
    }
}

@Composable
private fun HeroActionLabel(action: String, icon: Boolean) {
    if (icon) {
        Icon(
            Icons.AutoMirrored.Outlined.MenuBook,
            contentDescription = null,
            modifier = Modifier.padding(end = 8.dp),
        )
    }
    Text(action, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
}

/**
 * The cover, blurred and washed out behind the hero's text. Blur only
 * exists from Android 12; before that the card gets the palette's wash
 * instead of a cover drawn sharp under the title.
 */
@Composable
private fun HeroBackdrop(book: Book, palette: HomePalette, modifier: Modifier = Modifier) {
    Box(modifier) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BookCover(
                book = book,
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier.matchParentSize().blur(40.dp).alpha(0.55f),
            )
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(
                    0f to palette.card.copy(alpha = 0.92f),
                    0.55f to palette.card.copy(alpha = 0.7f),
                    1f to palette.heroWash.copy(alpha = 0.35f),
                ),
            ),
        )
    }
}

private val HomeShelfKind.label: Int
    get() = when (this) {
        HomeShelfKind.READING -> R.string.home_tile_reading
        HomeShelfKind.TO_READ -> R.string.home_tile_to_read
        HomeShelfKind.SERIES -> R.string.home_tile_series
        HomeShelfKind.FINISHED -> R.string.home_tile_finished
    }

private val HomeShelfKind.note: Int
    get() = when (this) {
        HomeShelfKind.READING -> R.plurals.home_note_almost_done
        HomeShelfKind.TO_READ -> R.plurals.home_note_added_lately
        HomeShelfKind.SERIES -> R.plurals.home_note_series_underway
        HomeShelfKind.FINISHED -> R.plurals.home_note_this_month
    }

private val HomeShelfKind.icon: ImageVector
    get() = when (this) {
        HomeShelfKind.READING -> Icons.Outlined.AutoStories
        HomeShelfKind.TO_READ -> Icons.Outlined.BookmarkBorder
        HomeShelfKind.SERIES -> Icons.Outlined.Layers
        HomeShelfKind.FINISHED -> Icons.Outlined.TaskAlt
    }

private fun HomePalette.tint(kind: HomeShelfKind): TileTint = when (kind) {
    HomeShelfKind.READING -> reading
    HomeShelfKind.TO_READ -> toRead
    HomeShelfKind.SERIES -> series
    HomeShelfKind.FINISHED -> finished
}

/** The four ways into the Library, two by two, and a way to all of it. */
@Composable
private fun ShelfTiles(
    tiles: List<HomeShelfTile>,
    onOpenShelf: (HomeShelfKind) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(stringResource(R.string.home_your_shelves), Modifier.weight(1f))
            TextButton(onClick = onSeeAll) {
                Text(stringResource(R.string.home_see_all))
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 6.dp).size(18.dp),
                )
            }
        }
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { tile ->
                    ShelfTile(tile = tile, onClick = { onOpenShelf(tile.kind) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ShelfTile(
    tile: HomeShelfTile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val note = tile.note.takeIf { it > 0 }?.let { pluralStringResource(tile.kind.note, it, it) }
    val tint = homePalette()?.tint(tile.kind)
    val accent = tint?.content ?: MaterialTheme.colorScheme.primary
    DeskCard(
        onClick = onClick,
        color = tint?.container,
        modifier = modifier,
    ) {
        Column(
            Modifier
                .then(
                    if (tint == null) {
                        Modifier
                    } else {
                        // A lighter sweep from the top corner, as if the tile caught the light.
                        Modifier.background(
                            Brush.linearGradient(
                                0f to tint.container,
                                0.55f to tint.container,
                                1f to tint.glow,
                                start = Offset(0f, Float.POSITIVE_INFINITY),
                                end = Offset(Float.POSITIVE_INFINITY, 0f),
                            ),
                        )
                    },
                )
                .padding(16.dp),
        ) {
            Icon(
                imageVector = tile.kind.icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(30.dp),
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(top = 14.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(tile.kind.label),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Kept as a line even when empty, so the tiles of a row line up.
                    Text(
                        text = note.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = tile.count.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/** A series under way: where the next volume is, and one bar segment per volume. */
@Composable
private fun SeriesSetCard(
    set: HomeSeriesSet,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val eInk = LocalEInk.current
    val total = set.shelf.volumes.size
    DeskCard(onClick = onClick, modifier = modifier) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(
                book = set.next?.book ?: set.shelf.cover,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .width(64.dp)
                    .height(96.dp)
                    .then(if (eInk) Modifier else Modifier.shadow(4.dp, RoundedCornerShape(6.dp))),
            )
            Column(
                Modifier
                    .padding(start = 16.dp)
                    .weight(1f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = set.shelf.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Pill(
                        text = stringResource(R.string.home_series_read, set.read, total),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                set.next?.let { next ->
                    val number = seriesIndexLabel(next.index)
                    val name = if (number != null) "#$number ${next.book.displayTitle}" else next.book.displayTitle
                    Text(
                        text = stringResource(R.string.home_series_next, name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                VolumeBar(set.volumeProgress, Modifier.padding(top = 12.dp))
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = homePalette()?.onPill ?: MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

private const val MAX_VOLUME_SEGMENTS = 12

/** One segment per volume, filled as far as that volume has been read. */
@Composable
private fun VolumeBar(progress: List<Float>, modifier: Modifier = Modifier) {
    val eInk = LocalEInk.current
    val palette = homePalette()
    val shape = RoundedCornerShape(3.dp)
    val fill = palette?.progress ?: MaterialTheme.colorScheme.primary
    val segment = if (eInk) {
        Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape)
    } else {
        Modifier.background(palette?.track ?: MaterialTheme.colorScheme.surfaceVariant)
    }
    val read = progress.count { it >= 1f }
    // Past a dozen volumes the segments would shrink to slivers, so the
    // series reads as one bar instead.
    val segments = if (progress.size > MAX_VOLUME_SEGMENTS) listOf(progress.sum() / progress.size) else progress
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .semantics { stateDescription = "$read/${progress.size}" },
    ) {
        segments.forEach { done ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(shape)
                    .then(segment),
            ) {
                if (done > 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth(done)
                            .fillMaxHeight()
                            .background(fill),
                    )
                }
            }
        }
    }
}

/** A cover in "Pick up next", with why it is there under it. */
@Composable
private fun PickTile(
    pick: HomePick,
    state: LibraryUiState,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val width = stripCoverWidth()
    val reason = when (pick.reason) {
        PickReason.IN_PROGRESS -> pick.progression?.let { "${(it * 100).toInt()}%" }
        PickReason.NEXT_IN_SERIES -> seriesIndexLabel(pick.book.seriesIndex)
            ?.let { stringResource(R.string.home_pick_next_volume, it) }
            ?: stringResource(R.string.home_pick_next_in_series)
        PickReason.UNREAD -> stringResource(R.string.filter_unread)
    }
    Column(Modifier.width(width)) {
        CoverTile(
            book = pick.book,
            download = state.downloads[pick.book.url],
            progression = pick.progression,
            onClick = onClick,
            onLongClick = onLongClick,
            title = true,
        )
        reason?.let { Pill(it, Modifier.padding(top = 4.dp)) }
    }
}
