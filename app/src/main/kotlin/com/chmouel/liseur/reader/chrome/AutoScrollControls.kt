package com.chmouel.liseur.reader.chrome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.AutoScrollPreference
import com.chmouel.liseur.data.settings.ReaderTheme
import kotlin.math.roundToInt

/**
 * The auto-scroll controls laid over a scrolled page, the way a video
 * player lays its own over the picture: pause or carry on, a notch
 * slower or faster, or stop altogether.
 *
 * Painted like the other pills at the bottom of the page, in the
 * reading theme, so it does not light up a dark page. When it shows and
 * when it fades is decided by the screen (see [autoScrollControlsShown]);
 * this only draws it.
 *
 * See `docs/adr/0040-auto-scroll-controls.md`.
 */
@Composable
fun AutoScrollControls(
    playing: Boolean,
    speed: Float,
    theme: ReaderTheme,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSlower: () -> Unit,
    onFaster: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val notch = AutoScrollPreference.snap(speed).roundToInt()
    ChromePill(theme = theme, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            if (playing) {
                ControlButton(onPause) {
                    Icon(Icons.Filled.Pause, stringResource(R.string.reader_auto_scroll_pause))
                }
            } else {
                ControlButton(onPlay) {
                    Icon(Icons.Filled.PlayArrow, stringResource(R.string.reader_auto_scroll_resume))
                }
            }
            ControlButton(onSlower, enabled = notch > AutoScrollPreference.MIN_STEP) {
                Icon(Icons.Filled.Remove, stringResource(R.string.reader_auto_scroll_slower))
            }
            val spoken = stringResource(R.string.reader_auto_scroll_speed_value, notch)
            Text(
                text = notch.toString(),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { contentDescription = spoken },
            )
            ControlButton(onFaster, enabled = notch < AutoScrollPreference.MAX_STEP) {
                Icon(Icons.Filled.Add, stringResource(R.string.reader_auto_scroll_faster))
            }
            ControlButton(onStop) {
                Icon(Icons.Filled.Close, stringResource(R.string.reader_auto_scroll_stop))
            }
        }
    }
}

@Composable
private fun ControlButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    // The pill paints its own content colour; a disabled button keeps it,
    // dimmed, rather than dropping to a Material grey that belongs to no
    // reading theme.
    val tint = LocalContentColor.current
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = tint,
            disabledContentColor = tint.copy(alpha = 0.38f),
        ),
        content = content,
    )
}
