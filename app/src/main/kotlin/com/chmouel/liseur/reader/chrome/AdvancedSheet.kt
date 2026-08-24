package com.chmouel.liseur.reader.chrome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.AutoScrollPreference
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.reading.ReadingSectionLabel
import com.chmouel.liseur.ui.windowWidth

/**
 * The second sheet, reached from the typography sheet's last row.
 *
 * It exists so that the typography sheet does not have to grow: that one
 * stays the four or five answers a reader changes often, and everything
 * rarer lives a tap further in. Dismissing this lands back on
 * typography, and dismissing that lands back on the page.
 *
 * Today it holds one row. The rest of what belongs here — finer
 * typography, read-aloud, imported fonts, tap-zone presets — arrives with
 * its own issue, and the shape of the sheet is the point: one row, or
 * five, it is the same sheet and the reader learns it once.
 *
 * See `docs/adr/0001-advanced-reading-menu.md`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSheet(
    autoScrollOffered: Boolean,
    autoScrolling: Boolean,
    autoScrollSpeed: Float,
    onAutoScrollChanged: (Boolean) -> Unit,
    onAutoScrollSpeedChanged: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .align(Alignment.CenterHorizontally)
                .widthIn(max = contentWidthCap(windowWidth()))
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (autoScrollOffered) {
                AutoScrollRow(
                    enabled = autoScrolling,
                    speed = autoScrollSpeed,
                    onChanged = onAutoScrollChanged,
                    onSpeedChanged = onAutoScrollSpeedChanged,
                )
            }
        }
    }
}

/**
 * Starts the page moving on its own, and sets how fast.
 *
 * Only offered to a book read by scrolling, which is what keeps this out
 * of a paginated one: there, the tap zones and the volume keys already
 * turn pages without the reader reaching for anything.
 *
 * The speed sits under the switch whether or not the page is moving.
 * Starting closes this sheet, so a slider shown only while running would
 * be a slider nobody could ever reach: the reader sets a pace, watches
 * the page, and comes back to move it a notch.
 *
 * See `docs/adr/0006-auto-scroll.md`.
 */
@Composable
private fun AutoScrollRow(
    enabled: Boolean,
    speed: Float,
    onChanged: (Boolean) -> Unit,
    onSpeedChanged: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = enabled,
                    role = Role.Switch,
                    onValueChange = onChanged,
                ),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.reader_auto_scroll),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.reader_auto_scroll_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = null)
        }
        AutoScrollSpeedSlider(value = speed, onChanged = onSpeedChanged)
    }
}

/**
 * The pace, in notches rather than in numbers.
 *
 * A figure in dp a second would be honest and useless: nobody knows what
 * their reading speed is in dp a second, and the same figure reads
 * differently at every text size anyway. Notches are what the reader can
 * actually judge, by watching the page at one and then at the next.
 *
 * Committed when the slider is let go rather than as it moves, the way
 * every other reading slider here works: what is written down is the
 * answer, not the sweep of the thumb getting to it.
 */
@Composable
private fun AutoScrollSpeedSlider(value: Float, onChanged: (Float) -> Unit) {
    var sliderValue by remember(value) { mutableFloatStateOf(value) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ReadingSectionLabel(stringResource(R.string.reader_auto_scroll_speed))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.reader_auto_scroll_slower),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onChanged(sliderValue) },
                valueRange = AutoScrollPreference.MIN_STEP.toFloat()..AutoScrollPreference.MAX_STEP.toFloat(),
                steps = AutoScrollPreference.MAX_STEP - AutoScrollPreference.MIN_STEP - 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
            Text(
                text = stringResource(R.string.reader_auto_scroll_faster),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
