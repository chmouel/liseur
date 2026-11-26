package com.chmouel.liseur.ui

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A single-choice segmented row that keeps every segment one height.
 *
 * English fits each of these labels on one line, so a row measured
 * segment by segment comes out flat and nothing looks wrong. Any other
 * language can need a second line — the page turn's third option is
 * "Bez animace" in Czech (#547) — and only that segment grows: a ragged
 * row where every option is the same size everywhere else, which reads
 * as a broken layout rather than a translated one.
 *
 * Measured at its tallest segment, and every segment filling that, the
 * row stays even whatever a label does. Segments have to be drawn with
 * [Segment]; a bare Material [SegmentedButton] keeps its own height and
 * takes the row back to ragged.
 */
@Composable
internal fun SegmentedChoiceRow(
    modifier: Modifier = Modifier,
    content: @Composable SingleChoiceSegmentedButtonRowScope.() -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = modifier.height(IntrinsicSize.Min),
        content = content,
    )
}

/**
 * One segment of a [SegmentedChoiceRow], as tall as the row.
 *
 * Wraps [SegmentedButton] so the height has to be asked for once rather
 * than at every call site, and keeps Material's selected checkmark as the
 * default icon.
 */
@Composable
internal fun SingleChoiceSegmentedButtonRowScope.Segment(
    index: Int,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: @Composable () -> Unit = { SegmentedButtonDefaults.Icon(selected) },
    label: @Composable () -> Unit,
) {
    SegmentedButton(
        selected = selected,
        onClick = onClick,
        shape = SegmentedButtonDefaults.itemShape(index, count),
        modifier = modifier.fillMaxHeight(),
        enabled = enabled,
        icon = icon,
        label = label,
    )
}
