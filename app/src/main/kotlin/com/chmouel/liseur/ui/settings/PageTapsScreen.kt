package com.chmouel.liseur.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.TapLayout
import com.chmouel.liseur.data.settings.TapSideWidth
import com.chmouel.liseur.data.settings.TapZones
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth
import kotlin.math.roundToInt

/**
 * Which sides of the page turn it and how much of the page they take,
 * with a drawing of the result on top.
 *
 * Its own page because the two choices only make sense together and
 * against a picture: a percentage of the page edge means little until
 * you see the band it leaves to the menu. The slider's position lives
 * here and not in the row, so the drawing follows a drag before the
 * value is saved on release.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageTapsScreen(
    tapZones: TapZones,
    tapSideWidth: Int,
    onTapZones: (TapZones) -> Unit,
    onTapSideWidth: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val stops = TapSideWidth.STOPS
    fun indexOf(width: Int) = stops.indexOf(TapSideWidth.sanitize(width)).toFloat()
    fun stopAt(p: Float) = stops[p.roundToInt().coerceIn(stops.indices)]
    var position by remember { mutableFloatStateOf(indexOf(tapSideWidth)) }
    LaunchedEffect(tapSideWidth) { position = indexOf(tapSideWidth) }
    val previewWidth = stopAt(position)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings_page_taps)) },
                navigationIcon = { PaneBackButton(onBack) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = contentWidthCap(windowWidth()))
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            ) {
                PageTapsPreview(
                    layout = TapLayout(tapZones, previewWidth),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    stringResource(R.string.page_taps_rtl_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    ChipRow(
                        title = stringResource(R.string.settings_tap_zones),
                        subtitle = stringResource(R.string.settings_tap_zones_detail),
                        options = TapZones.entries,
                        selected = tapZones,
                        label = { stringResource(it.label) },
                        onSelected = onTapZones,
                    )
                    RowDivider()
                    TapSideWidthRow(
                        position = position,
                        enabled = tapZones.turnsPages,
                        onPosition = { position = it },
                        onFinished = { onTapSideWidth(stopAt(position)) },
                    )
                }
            }
        }
    }
}

/**
 * How much of the page each side turns, as a slider over
 * [TapSideWidth.STOPS] ending on Wide. The thumb moves freely while
 * dragging and the choice is stored when it is let go, so a drag is one
 * write and not one per stop. Greyed out, not hidden, when the sides are
 * off: there are no sides to size, but the row says what it would do.
 */
@Composable
private fun TapSideWidthRow(
    position: Float,
    enabled: Boolean,
    onPosition: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    val stops = TapSideWidth.STOPS
    // The slider keeps its first callbacks, so they must only touch stable state.
    val move by rememberUpdatedState(onPosition)
    val finish by rememberUpdatedState(onFinished)
    val current = stops[position.roundToInt().coerceIn(stops.indices)]
    val title = stringResource(R.string.settings_tap_side_width)
    val valueText = tapSideWidthText(current)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                valueText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = stringResource(R.string.settings_tap_side_width_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = position,
            enabled = enabled,
            onValueChange = { move(it) },
            onValueChangeFinished = { finish() },
            valueRange = 0f..stops.lastIndex.toFloat(),
            steps = stops.size - 2,
            modifier = Modifier.semantics {
                contentDescription = title
                stateDescription = valueText
            },
        )
    }
}

@Composable
internal fun tapSideWidthText(width: Int): String =
    if (width == TapSideWidth.WIDE) {
        stringResource(R.string.tap_side_width_wide)
    } else {
        stringResource(R.string.tap_side_width_percent, width)
    }

/** Internal: the Settings rows summarise the current sides in their subtitles. */
internal val TapZones.label: Int
    get() = when (this) {
        TapZones.BOTH -> R.string.tap_zones_both
        TapZones.LEFT -> R.string.tap_zones_left
        TapZones.RIGHT -> R.string.tap_zones_right
        TapZones.OFF -> R.string.tap_zones_off
    }
