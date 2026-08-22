package com.chmouel.liseur.ui.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth

/**
 * The shape every one of the library's empty screens takes.
 *
 * All four of them used to build this by hand, and had started to drift
 * apart doing it -- two carried an icon and two did not, the buttons
 * were of three different kinds. One scaffold so that saying nothing is
 * on the shelf looks the same whichever reason put it there.
 *
 * The column scrolls even when it fits. An empty library is exactly
 * when someone needs to pull from the top: they have just added a
 * folder or connected an account and are waiting for books to turn up,
 * and the refresh has to stay in reach.
 *
 * [art] is handed the height the column has been given, because that is
 * the only thing that can decide whether there is room for a picture:
 * in landscape, on a short window, there is not.
 */
@Composable
internal fun LibraryEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    art: @Composable ((available: Dp) -> Unit)? = null,
    actions: @Composable ColumnScope.() -> Unit,
) {
    val cap = contentWidthCap(windowWidth())
    BoxWithConstraints(modifier) {
        val available = maxHeight
        // A phone on its side. There is no room here for a picture and
        // not much for air either, so the screen gives both up rather
        // than push the last way out of an empty library off the
        // bottom.
        val cramped = available < ROOM_FOR_ART
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .heightIn(min = available)
                .padding(horizontal = 24.dp, vertical = if (cramped) 16.dp else 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Column(
                modifier = Modifier.widthIn(max = cap),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (art != null && !cramped) {
                    Box(Modifier.entrance(delayMillis = 0)) { art(available) }
                }
                Column(
                    modifier = Modifier.entrance(delayMillis = ENTRANCE_STEP_MS),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                if (!cramped) Spacer(Modifier.height(8.dp))
                actions()
            }
        }
    }
}

/**
 * A route out of an empty screen: what it is, what it does, and an
 * arrow saying it leads somewhere.
 *
 * The supporting line is the point of the card. Three buttons in a
 * column read as three flavours of one action; the difference between
 * pointing at a folder and logging into a server is not something a
 * four-word label can carry.
 */
@Composable
internal fun LibraryActionCard(
    icon: ImageVector,
    title: String,
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val eInk = LocalEInk.current
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        // A shadow is invisible on e-paper, so there the card is drawn
        // with an edge instead of lifted off the page.
        elevation = CardDefaults.cardElevation(defaultElevation = if (eInk) 0.dp else 1.dp),
        border = if (eInk) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        // One target, and one thing said about it: read out label by
        // label, the icon and the arrow turn a single choice into three
        // fragments to listen through.
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clearAndSetSemantics {
                contentDescription = "$title. $hint"
                onClick(label = title, action = null)
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The glyph the reasons-other-than-empty screens lead with. */
@Composable
private fun LibraryEmptyIcon(icon: ImageVector) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.size(80.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(38.dp),
            )
        }
    }
}

/**
 * The first screen anyone sees, and the only chance the app gets to say
 * what it is for.
 *
 * The three routes are laid out rather than listed: a folder to watch,
 * one book to bring in, and a server to borrow a whole catalog from.
 * The server used to live three taps away under Settings, which is not
 * somewhere a new library looks.
 */
@Composable
internal fun EmptyLibrary(
    onAddBook: () -> Unit,
    onAddFolder: () -> Unit,
    onConnectServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Which cut of the mark to draw is asked of the theme in force
    // here, not of a -night qualifier: those follow the system, and the
    // app's own light/dark setting is allowed to disagree.
    val darkMark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    LibraryEmptyState(
        title = stringResource(R.string.empty_library_title),
        subtitle = stringResource(R.string.empty_library_subtitle),
        art = { available ->
            Image(
                painter = painterResource(
                    if (darkMark) R.drawable.ic_brand_emblem_night_transparent
                    else R.drawable.ic_brand_emblem_transparent,
                ),
                contentDescription = null,
                // The box is given the full width and only the height is
                // dictated, because Fit scales to whichever side runs
                // out first: with the width left at the drawable's own,
                // the mark would be pinned to its intrinsic size and the
                // height would buy nothing but padding.
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(heroHeight(available)),
            )
        },
        modifier = modifier,
    ) {
        LibraryActionCard(
            icon = Icons.Outlined.CreateNewFolder,
            title = stringResource(R.string.add_folder),
            hint = stringResource(R.string.add_folder_hint),
            onClick = onAddFolder,
            modifier = Modifier.entrance(delayMillis = 2 * ENTRANCE_STEP_MS),
        )
        LibraryActionCard(
            icon = Icons.Outlined.LibraryAdd,
            title = stringResource(R.string.add_book),
            hint = stringResource(R.string.add_book_hint),
            onClick = onAddBook,
            modifier = Modifier.entrance(delayMillis = 3 * ENTRANCE_STEP_MS),
        )
        LibraryActionCard(
            icon = Icons.Outlined.CloudQueue,
            title = stringResource(R.string.connect_server),
            hint = stringResource(R.string.connect_server_hint),
            onClick = onConnectServer,
            modifier = Modifier.entrance(delayMillis = 4 * ENTRANCE_STEP_MS),
        )
    }
}

/**
 * Shown when the shelf has books on it but a search or a filter is
 * hiding every one of them.
 */
@Composable
internal fun NothingMatched(
    searching: Boolean,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LibraryEmptyState(
        title = stringResource(
            if (searching) R.string.no_books_match else R.string.no_books_in_filter,
        ),
        art = { LibraryEmptyIcon(Icons.Outlined.Search) },
        modifier = modifier,
    ) {
        FilledTonalButton(
            onClick = onClear,
            modifier = Modifier.entrance(delayMillis = 2 * ENTRANCE_STEP_MS),
        ) {
            Text(stringResource(R.string.show_all_books))
        }
    }
}

/**
 * What the library says once every book on it has been archived.
 *
 * It has to lead somewhere, because the way to those books is an entry
 * in a menu, and a menu is not where anyone looks when the screen in
 * front of them is empty.
 */
@Composable
internal fun EverythingArchived(
    onShowArchived: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LibraryEmptyState(
        title = stringResource(R.string.everything_archived),
        art = { LibraryEmptyIcon(Icons.Outlined.Archive) },
        modifier = modifier,
    ) {
        FilledTonalButton(
            onClick = onShowArchived,
            modifier = Modifier.entrance(delayMillis = 2 * ENTRANCE_STEP_MS),
        ) {
            Text(stringResource(R.string.filter_archived))
        }
    }
}

/**
 * What the library says once every book on it has been read.
 *
 * The shelf is hiding them by a rule of the app's own rather than by
 * anything the reader asked for, so this cannot merely report an empty
 * shelf: it has to hand back the books it took.
 */
@Composable
internal fun EverythingFinished(
    onShowFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LibraryEmptyState(
        title = stringResource(R.string.everything_finished),
        art = { LibraryEmptyIcon(Icons.Outlined.CheckCircle) },
        modifier = modifier,
    ) {
        FilledTonalButton(
            onClick = onShowFinished,
            modifier = Modifier.entrance(delayMillis = 2 * ENTRANCE_STEP_MS),
        ) {
            Text(stringResource(R.string.show_finished_books))
        }
    }
}

/**
 * How tall the reading mark may be drawn given [available] height.
 *
 * The scaffold has already ruled out the windows too short for a
 * picture at all; this is only the difference between a tall phone and
 * a merely tall-ish one.
 */
private fun heroHeight(available: Dp): Dp =
    if (available < HERO_FULL_ROOM) 110.dp else 180.dp

/**
 * Shortest window that still has room for a picture above the words.
 *
 * Below it the three routes out of an empty library matter more than
 * the mark above them, and a hero that pushes the last one off the
 * bottom is worse than no hero.
 */
private val ROOM_FOR_ART = 480.dp
private val HERO_FULL_ROOM = 600.dp

/**
 * The screen assembling itself in order, top to bottom.
 *
 * A fade and a small rise, and nothing at all under e-ink, where a
 * fade is a sequence of full-page flashes rather than a fade.
 */
@Composable
private fun Modifier.entrance(delayMillis: Int): Modifier {
    if (LocalEInk.current) return this
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = ENTRANCE_MS, delayMillis = delayMillis),
        label = "libraryEmptyEntrance",
    )
    return graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * ENTRANCE_RISE.toPx()
    }
}

private const val ENTRANCE_MS = 260
private const val ENTRANCE_STEP_MS = 50
private val ENTRANCE_RISE = 12.dp
