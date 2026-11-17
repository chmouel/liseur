package com.chmouel.liseur.ui.stats

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.domain.StatsRange
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.theme.DarkHomePalette
import com.chmouel.liseur.ui.theme.homePalette

/*
 * The parts both stats screens are built from.
 *
 * One figure set large, then plain rows of label and value in a card
 * under a section heading, the way the settings are laid out. A grid of
 * equal tiles makes every number shout at the same volume and leaves a
 * hole when one of them is missing; a row that is not there simply is
 * not there.
 */

/** Table figures, so values line up down a column of rows. */
internal const val TABULAR = "tnum"

/** What a section card and the group of rows inside it are cut to. */
internal val StatsCardCorner = 20.dp

/** How far inside a card its rows start, and its dividers with them. */
private val RowInset = 16.dp

/** The span the reader picked, as the line over the total names it. */
@get:StringRes
internal val StatsRange.label: Int
    get() = when (this) {
        StatsRange.THIS_WEEK -> R.string.reading_stats_range_7d
        StatsRange.THIS_MONTH -> R.string.reading_stats_range_this_month
        StatsRange.THIS_YEAR -> R.string.reading_stats_range_this_year
        StatsRange.ALL_TIME -> R.string.reading_stats_range_all
    }

/**
 * The same span as its chip names it. Short, so the four fit on one line;
 * the line under them says it in full.
 */
@get:StringRes
internal val StatsRange.chipLabel: Int
    get() = when (this) {
        StatsRange.THIS_WEEK -> R.string.reading_stats_chip_week
        StatsRange.THIS_MONTH -> R.string.reading_stats_chip_month
        StatsRange.THIS_YEAR -> R.string.reading_stats_chip_year
        StatsRange.ALL_TIME -> R.string.reading_stats_range_all
    }

/** The two halves of the line saying what the dashboard total counts. */
internal data class ScopeLine(@param:StringRes val range: Int, @param:StringRes val devices: Int)

/**
 * The span and the devices the total covers, as one line (ADR-0021).
 *
 * Both come from the same state, so the line cannot say "every device"
 * over figures that counted this one alone, which the old caption did.
 */
internal fun scopeLine(range: StatsRange, provenance: StatsProvenance): ScopeLine = ScopeLine(
    range = range.label,
    devices = when (provenance) {
        StatsProvenance.THIS_DEVICE -> R.string.reading_stats_scope_this_device
        StatsProvenance.ALL_DEVICES -> R.string.reading_stats_scope_all_devices
    },
)

/**
 * The figure a screen leads with, on the page rather than in a card.
 *
 * Literata at display size and nothing else competing with it: the
 * space around it does the work a border and an eyebrow label did.
 */
@Composable
internal fun StatsHero(
    scope: String,
    total: String,
    modifier: Modifier = Modifier,
    scopeIcon: ImageVector? = null,
    below: @Composable ColumnScope.() -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            scopeIcon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = scope,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = total,
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = TABULAR,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        below()
    }
}

/** A section's name, set like the headings in the settings. */
@Composable
internal fun StatsSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 8.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        trailing?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The quiet surface a section's content sits on. */
@Composable
internal fun StatsCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(StatsCardCorner),
    content: @Composable ColumnScope.() -> Unit,
) {
    val eInk = LocalEInk.current
    val palette = homePalette()
    val dark = palette == DarkHomePalette
    Surface(
        shape = shape,
        color = palette?.card ?: MaterialTheme.colorScheme.surfaceContainerLow,
        border = when {
            eInk -> BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            palette != null -> BorderStroke(1.dp, palette.cardBorder)
            else -> null
        },
        shadowElevation = if (palette != null && !dark) 2.dp else 0.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

/** One fact for a [StatRows] card: what it counts and how many. */
internal data class StatLine(
    val label: String,
    val value: String,
    val supporting: String? = null,
)

/** A card of [StatLine]s, divided by hairlines. */
@Composable
internal fun StatRows(lines: List<StatLine>, modifier: Modifier = Modifier) {
    StatsCard(modifier) {
        lines.forEachIndexed { index, line ->
            if (index > 0) StatsRowDivider()
            StatRow(line)
        }
    }
}

/**
 * A label on the left and its figure on the right.
 *
 * The label takes the room and wraps, because some carry the qualifier
 * that is the point of them ("Pace (synced reading)") and at a large
 * font scale that does not fit on one line. Read out as one node, so
 * TalkBack says "Sittings, 12" rather than two stops a swipe apart.
 */
@Composable
internal fun StatRow(line: StatLine, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = RowInset, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = line.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            line.supporting?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = line.value,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = TABULAR,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

/** The hairline between two rows of one card, inset like the rows. */
@Composable
internal fun StatsRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = RowInset),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * The shape of one row in a group drawn as a single card.
 *
 * Each row stays its own lazy item, so a long list of books is still
 * laid out as it scrolls; only the first and last round their corners.
 */
internal fun groupedRowShape(index: Int, count: Int): Shape = RoundedCornerShape(
    topStart = if (index == 0) StatsCardCorner else 0.dp,
    topEnd = if (index == 0) StatsCardCorner else 0.dp,
    bottomStart = if (index == count - 1) StatsCardCorner else 0.dp,
    bottomEnd = if (index == count - 1) StatsCardCorner else 0.dp,
)
