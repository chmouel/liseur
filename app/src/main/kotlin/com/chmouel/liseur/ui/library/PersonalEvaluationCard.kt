package com.chmouel.liseur.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.StarHalf
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.domain.PersonalEvaluation
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import com.chmouel.liseur.ui.LocalEInk
import java.text.NumberFormat

internal enum class StarFill { EMPTY, HALF, FULL }

/** Maps a stored evaluation rating (2..10, or null) to five star states. */
internal fun starFills(rating: Int?): List<StarFill> =
    (1..5).map { star ->
        when {
            rating == null -> StarFill.EMPTY
            rating >= star * 2 -> StarFill.FULL
            rating == star * 2 - 1 -> StarFill.HALF
            else -> StarFill.EMPTY
        }
    }

/**
 * Picks the next valid wire rating (2..10) when tapping [tappedStar] (1..5).
 * Tapping an already-full star (2..5) steps down by half a star; tapping
 * again returns to the full star. Star 1 stays at 2 (the 1.0-star minimum).
 */
internal fun nextStarRating(current: Int?, tappedStar: Int): Int {
    val star = tappedStar.coerceIn(1, 5)
    val full = star * 2
    val half = full - 1
    return if (star > 1 && current == full) half else full
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PersonalEvaluationCard(
    details: BookDetails,
    onSet: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
    /** Set when the review also goes somewhere public, which then replaces the privacy promise. */
    publicNote: String? = null,
) {
    val book = details.book
    key(book.url, book.id, book.workId, book.fileModifiedAt, book.linkGeneration) {
        val rating = details.evaluations.find { it.field == PersonalEvaluation.RATING }?.value?.toIntOrNull()
        val review = details.evaluations.find { it.field == PersonalEvaluation.REVIEW }?.value
        var editingReview by rememberSaveable(book.url) { mutableStateOf(false) }
        val eInk = LocalEInk.current

        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            color = if (eInk) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow,
            border =
                BorderStroke(
                    1.dp,
                    if (eInk) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,
                ),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.evaluation_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (publicNote != null) Icons.Outlined.Public else Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            publicNote ?: stringResource(
                                if (details.liseurSyncConfigured) {
                                    R.string.evaluation_private_synced
                                } else {
                                    R.string.evaluation_private_local
                                },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    StarRow(
                        rating = rating,
                        starSize = 28.dp,
                        onTapStar = { tapped ->
                            onSet(PersonalEvaluation.RATING, nextStarRating(rating, tapped).toString())
                        },
                    )
                    if (rating != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            RatingReadout(rating)
                            IconButton(
                                onClick = { onSet(PersonalEvaluation.RATING, null) },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.evaluation_clear_rating),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }

                if (review != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = if (eInk) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer,
                        border =
                            BorderStroke(
                                1.dp,
                                if (eInk) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,
                            ),
                    ) {
                        Text(
                            review,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                } else if (rating == null) {
                    Text(
                        stringResource(R.string.evaluation_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                TextButton(
                    onClick = { editingReview = true },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                    modifier = Modifier.heightIn(min = 40.dp),
                ) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(if (review != null) R.string.evaluation_edit_review else R.string.evaluation_add_review),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        if (editingReview) {
            ReviewEditorSheet(
                bookUrl = book.url,
                initialReview = review,
                liseurSyncConfigured = details.liseurSyncConfigured,
                publicNote = publicNote,
                onDismiss = { editingReview = false },
                onSave = { normalized ->
                    onSet(PersonalEvaluation.REVIEW, normalized)
                    editingReview = false
                },
            )
        }
    }
}

@Composable
private fun RatingReadout(rating: Int) {
    val compact = stringResource(R.string.evaluation_rating_compact, NumberFormat.getNumberInstance().format(rating / 2.0))
    val slashIndex = compact.indexOf('/')
    if (slashIndex > 0) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                    append(compact.substring(0, slashIndex))
                }
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    append(compact.substring(slashIndex))
                }
            },
            style = MaterialTheme.typography.titleMedium,
        )
    } else {
        Text(
            compact,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun StarRow(
    rating: Int?,
    starSize: Dp,
    onTapStar: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val eInk = LocalEInk.current
    val filledTint =
        when {
            eInk -> MaterialTheme.colorScheme.onSurface
            MaterialTheme.colorScheme.surface.luminance() < 0.5f -> Color(0xFFFFCA6C)
            else -> Color(0xFFD9820B)
        }
    val emptyTint = MaterialTheme.colorScheme.onSurfaceVariant
    val fills = starFills(rating)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        fills.forEachIndexed { index, fill ->
            val starNumber = index + 1
            val nextValue = nextStarRating(rating, starNumber)
            val label = stringResource(R.string.evaluation_rating_value, NumberFormat.getNumberInstance().format(nextValue / 2.0))
            val icon =
                when (fill) {
                    StarFill.FULL -> Icons.Rounded.Star
                    StarFill.HALF -> Icons.AutoMirrored.Rounded.StarHalf
                    StarFill.EMPTY -> Icons.Outlined.StarBorder
                }
            Box(
                modifier =
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { onTapStar(starNumber) }
                        .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (fill == StarFill.EMPTY) emptyTint else filledTint,
                    modifier = Modifier.size(starSize),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewEditorSheet(
    bookUrl: String,
    initialReview: String?,
    liseurSyncConfigured: Boolean,
    publicNote: String?,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    val originalReview by rememberSaveable(bookUrl) { mutableStateOf(initialReview) }
    var review by rememberSaveable(bookUrl) { mutableStateOf(initialReview.orEmpty()) }
    val normalized = review.takeIf { it.isNotBlank() }
    val valid = PersonalEvaluation.valid(PersonalEvaluation.REVIEW, normalized)

    LiseurModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(if (initialReview != null) R.string.evaluation_edit_review else R.string.evaluation_add_review),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )

            OutlinedTextField(
                value = review,
                onValueChange = { review = it },
                label = { Text(stringResource(if (publicNote != null) R.string.evaluation_review_public else R.string.evaluation_review)) },
                trailingIcon =
                    if (review.isNotEmpty()) {
                        {
                            IconButton(onClick = { review = "" }) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.clear),
                                )
                            }
                        }
                    } else {
                        null
                    },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 8,
                isError = !valid,
                supportingText = {
                    if (!valid) {
                        Text(stringResource(R.string.evaluation_review_limit))
                    } else {
                        Text(
                            publicNote ?: stringResource(
                                if (liseurSyncConfigured) {
                                    R.string.evaluation_private
                                } else {
                                    R.string.evaluation_private_local_detail
                                },
                            ),
                        )
                    }
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        if (normalized != originalReview) {
                            onSave(normalized)
                        } else {
                            onDismiss()
                        }
                    },
                    enabled = valid,
                    shape = CircleShape,
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}
