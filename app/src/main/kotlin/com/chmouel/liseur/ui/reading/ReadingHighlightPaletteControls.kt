package com.chmouel.liseur.ui.reading

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.reader.annotations.HighlightPalette
import com.chmouel.liseur.reader.annotations.HighlightTint

/**
 * Which colours the bar over a selected passage offers, and which one a
 * mark gets when none was picked.
 *
 * One control shown in two places — the reader's Advanced sheet and
 * Settings → Reading appearance → Advanced — so that the two cannot
 * drift, as `ReadingPageTurnStyleControl` is.
 *
 * All six are always on screen, ticked or not, because a set is a
 * clearer thing to be shown than a count: the row is the bar, at the
 * size the bar will be, and a colour missing from it is missing where
 * the reader can see the gap and fill it. A number would have made the
 * reader work out which three "3" meant.
 *
 * The default rides on the same swatch, under a long press, rather than
 * a second row of six repeating the first. It is the rarer choice by
 * far, and two rows of the same colours invite the reader to think the
 * rows disagree about something.
 *
 * The row wraps rather than being a fixed one: six 44dp targets and
 * their gaps want 284dp, which a 320dp phone does not have once the
 * sheet has taken its padding, and neither does a narrow split-screen
 * window. Shrinking the swatches instead would put the touch target
 * under the 44dp that makes it reachable, so a second line is the one
 * that gives way.
 */
@Composable
fun ReadingHighlightPaletteControls(
    palette: HighlightPalette,
    onTintToggled: (HighlightTint) -> Unit,
    onDefaultChanged: (HighlightTint) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ReadingSectionLabel(stringResource(R.string.reader_highlight_colours))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HighlightTint.entries.forEach { tint ->
                HighlightTintSwatch(
                    tint = tint,
                    offered = tint in palette.offered,
                    isDefault = tint == palette.default,
                    onToggle = { onTintToggled(tint) },
                    onMakeDefault = { onDefaultChanged(tint) },
                )
            }
        }
        ReadingSupportingText(
            stringResource(
                if (palette.isEmpty) {
                    R.string.reader_highlight_colours_none
                } else {
                    R.string.reader_highlight_colours_help
                },
                palette.default.name(),
            ),
        )
    }
}

/**
 * One colour, on or off, and ringed when it is the default.
 *
 * Off is drawn hollow rather than faded: a dimmed yellow is still a
 * yellow, and the reader would have to compare it against its
 * neighbours to know it was off. An outline is unmistakable at a glance
 * and survives the sepia and black reading themes, which a fixed
 * opacity does not.
 *
 * The default's ring sits outside the swatch rather than replacing its
 * border, because the default is not required to be offered and a
 * hollow swatch wearing a ring in the theme's colour would have lost
 * the only thing that said which colour it was.
 *
 * The long press is also a TalkBack custom action, since a screen
 * reader user cannot see the line that explains it, and the tick and
 * the ring both go into the spoken state rather than being left to the
 * eye.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HighlightTintSwatch(
    tint: HighlightTint,
    offered: Boolean,
    isDefault: Boolean,
    onToggle: () -> Unit,
    onMakeDefault: () -> Unit,
) {
    val name = tint.name()
    val state = stringResource(
        when {
            offered && isDefault -> R.string.reader_highlight_state_offered_default
            offered -> R.string.reader_highlight_state_offered
            isDefault -> R.string.reader_highlight_state_hidden_default
            else -> R.string.reader_highlight_state_hidden
        },
    )
    val makeDefault = stringResource(R.string.reader_highlight_make_default)
    Box(
        Modifier
            .size(44.dp)
            .semantics {
                contentDescription = name
                stateDescription = state
            }
            .clip(CircleShape)
            .border(
                width = if (isDefault) 2.dp else 0.dp,
                color = if (isDefault) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    Color.Transparent
                },
                shape = CircleShape,
            )
            .combinedClickable(
                role = Role.Checkbox,
                onClick = onToggle,
                onLongClickLabel = makeDefault,
                onLongClick = onMakeDefault,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (offered) tint.color else Color.Transparent)
                .border(
                    width = if (offered) 0.dp else 2.dp,
                    color = if (offered) Color.Transparent else tint.color,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (offered) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.Black.copy(alpha = 0.55f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** The colour's own name, without the "Highlight in" the popup needs. */
@Composable
private fun HighlightTint.name(): String = stringResource(
    when (this) {
        HighlightTint.YELLOW -> R.string.colour_yellow
        HighlightTint.GREEN -> R.string.colour_green
        HighlightTint.BLUE -> R.string.colour_blue
        HighlightTint.PINK -> R.string.colour_pink
        HighlightTint.PURPLE -> R.string.colour_purple
        HighlightTint.ORANGE -> R.string.colour_orange
    },
)
