package com.chmouel.liseur.ui.library

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.MetadataOverrideFields
import com.chmouel.liseur.data.library.BookMetadataOverrideRepository
import com.chmouel.liseur.data.library.BookMetadataOverrideRepository.Companion.MAX_COVER_SOURCE_BYTES
import com.chmouel.liseur.data.library.BookMetadataOverrideRepository.Companion.withinWireLimit
import com.chmouel.liseur.data.library.CoverEdit
import com.chmouel.liseur.data.library.FieldEdit
import com.chmouel.liseur.domain.seriesIndexLabel
import com.chmouel.liseur.domain.validSeriesPosition
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.launchPicker
import com.chmouel.liseur.ui.windowWidth
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class FieldEditMode {
    UNCHANGED,
    SET,
    RESET,
}

internal fun resolveFieldEdit(
    mode: FieldEditMode,
    currentValue: String,
    initialValue: String,
): FieldEdit =
    when (mode) {
        FieldEditMode.RESET -> FieldEdit.Reset
        FieldEditMode.SET -> FieldEdit.Set(currentValue)
        FieldEditMode.UNCHANGED -> {
            if (currentValue != initialValue) FieldEdit.Set(currentValue) else FieldEdit.Unchanged
        }
    }

/**
 * Whether this field is about to be sent with a value the server refuses.
 *
 * Only a value on its way to the server has a wire limit: text the reader
 * leaves alone, or resets to, is never sent as a value, so a long source
 * description must not block editing the title or the cover.
 */
internal fun overWireLimit(mode: FieldEditMode, currentValue: String, initialValue: String, field: String): Boolean =
    mode != FieldEditMode.RESET &&
        resolveFieldEdit(mode, currentValue, initialValue) is FieldEdit.Set &&
        !withinWireLimit(field, currentValue)

internal enum class CoverDraft {
    UNCHANGED,
    SET,
    EMPTY,
    RESET,
}

/** What a cover draft asks the library for once the reader saves. */
internal fun coverEditOf(draft: CoverDraft, bytes: ByteArray?): CoverEdit =
    when (draft) {
        CoverDraft.UNCHANGED -> CoverEdit.Unchanged
        CoverDraft.EMPTY -> CoverEdit.Empty
        CoverDraft.RESET -> CoverEdit.Reset
        CoverDraft.SET -> bytes?.let { CoverEdit.Set(it) } ?: CoverEdit.Unchanged
    }

/** Whether the draft leaves a cover that can be taken away. */
internal fun canRemoveCover(book: Book, draft: CoverDraft): Boolean =
    when (draft) {
        CoverDraft.UNCHANGED -> book.effectiveCover != null || !book.coverOverridden
        CoverDraft.EMPTY -> false
        CoverDraft.SET -> true
        CoverDraft.RESET -> book.coverPath != null || book.coverUrl != null
    }

/** Whether the draft holds an edit the original cover can replace. */
internal fun canResetCover(book: Book, draft: CoverDraft): Boolean =
    when (draft) {
        CoverDraft.UNCHANGED -> book.coverOverridden
        CoverDraft.RESET -> false
        else -> true
    }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun BookMetadataEditSheet(
    details: BookDetails,
    onSave: (title: FieldEdit, author: FieldEdit, description: FieldEdit, cover: CoverEdit) -> Unit,
    onResetAllOverrides: () -> Unit,
    onEditSeries: () -> Unit,
    onDismiss: () -> Unit,
) {
    val book = details.book
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val eInk = LocalEInk.current

    val initialTitle = if (book.titleOverridden) book.userTitle.orEmpty() else book.title
    val sourceTitle = book.catalogTitle ?: book.fileTitle ?: ""

    val initialAuthor = if (book.authorOverridden) book.userAuthor.orEmpty() else book.author.orEmpty()
    val sourceAuthor = book.catalogAuthor ?: book.fileAuthor ?: ""

    val initialDescription =
        if (book.descriptionOverridden) book.userDescription.orEmpty() else details.sourceDescription.orEmpty()
    val sourceDescription = details.sourceDescription.orEmpty()

    var titleText by rememberSaveable(book.url) { mutableStateOf(initialTitle) }
    var titleMode by rememberSaveable(book.url) { mutableStateOf(FieldEditMode.UNCHANGED) }

    var authorText by rememberSaveable(book.url) { mutableStateOf(initialAuthor) }
    var authorMode by rememberSaveable(book.url) { mutableStateOf(FieldEditMode.UNCHANGED) }

    var descriptionText by rememberSaveable(book.url) { mutableStateOf(initialDescription) }
    var descriptionMode by rememberSaveable(book.url) { mutableStateOf(FieldEditMode.UNCHANGED) }

    // The cover is a draft like the rest: nothing reaches the library until
    // the reader saves, and Cancel takes it all back.
    var coverMode by rememberSaveable(book.url) { mutableStateOf(CoverDraft.UNCHANGED) }
    var coverUri by rememberSaveable(book.url) { mutableStateOf<String?>(null) }

    // Only a value that is about to be sent has a wire limit: text the
    // reader leaves alone, or resets to, is never sent as a value.
    val titleTooLong = overWireLimit(titleMode, titleText, initialTitle, MetadataOverrideFields.TITLE)
    val authorTooLong = overWireLimit(authorMode, authorText, initialAuthor, MetadataOverrideFields.AUTHOR)
    val descriptionTooLong =
        overWireLimit(descriptionMode, descriptionText, initialDescription, MetadataOverrideFields.DESCRIPTION)
    val tooLong = titleTooLong || authorTooLong || descriptionTooLong

    val pickCover = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coverUri = uri.toString()
        coverMode = CoverDraft.SET
    }

    LiseurModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = contentWidthCap(windowWidth()))
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.book_metadata_edit_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 4.dp, bottom = 12.dp)
                    .semantics { heading() },
            )
            HorizontalDivider()
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    BookCover(
                        book = book,
                        modifier = Modifier
                            .width(76.dp)
                            .aspectRatio(2f / 3f),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.book_metadata_cover_label),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { pickCover.launchPicker(context, arrayOf("image/*")) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(R.string.book_metadata_choose_cover))
                            }
                            if (canRemoveCover(book, coverMode)) {
                                TextButton(
                                    onClick = {
                                        coverMode = CoverDraft.EMPTY
                                        coverUri = null
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text(stringResource(R.string.book_metadata_remove_cover))
                                }
                            }
                            if (canResetCover(book, coverMode)) {
                                TextButton(
                                    onClick = {
                                        coverMode = CoverDraft.RESET
                                        coverUri = null
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text(stringResource(R.string.book_metadata_field_reset))
                                }
                            }
                        }
                    }
                }

                MetadataTextFieldSection(
                    label = stringResource(R.string.book_metadata_title_label),
                    value = titleText,
                    onValueChange = {
                        titleText = it
                        titleMode = FieldEditMode.SET
                    },
                    canReset = (book.titleOverridden && titleMode != FieldEditMode.RESET) || titleMode == FieldEditMode.SET,
                    onReset = {
                        titleText = sourceTitle
                        titleMode = if (book.titleOverridden) FieldEditMode.RESET else FieldEditMode.UNCHANGED
                    },
                    singleLine = true,
                    error = if (titleTooLong) {
                        stringResource(
                            R.string.book_metadata_field_too_long,
                            BookMetadataOverrideRepository.MAX_TITLE_BYTES,
                        )
                    } else {
                        null
                    },
                )

                MetadataTextFieldSection(
                    label = stringResource(R.string.book_metadata_author_label),
                    value = authorText,
                    onValueChange = {
                        authorText = it
                        authorMode = FieldEditMode.SET
                    },
                    canReset = (book.authorOverridden && authorMode != FieldEditMode.RESET) || authorMode == FieldEditMode.SET,
                    onReset = {
                        authorText = sourceAuthor
                        authorMode = if (book.authorOverridden) FieldEditMode.RESET else FieldEditMode.UNCHANGED
                    },
                    singleLine = true,
                    error = if (authorTooLong) {
                        stringResource(
                            R.string.book_metadata_field_too_long,
                            BookMetadataOverrideRepository.MAX_AUTHOR_BYTES,
                        )
                    } else {
                        null
                    },
                )

                MetadataTextFieldSection(
                    label = stringResource(R.string.book_metadata_description_label),
                    value = descriptionText,
                    onValueChange = {
                        descriptionText = it
                        descriptionMode = FieldEditMode.SET
                    },
                    canReset =
                        (book.descriptionOverridden && descriptionMode != FieldEditMode.RESET) ||
                            descriptionMode == FieldEditMode.SET,
                    onReset = {
                        descriptionText = sourceDescription
                        descriptionMode = if (book.descriptionOverridden) FieldEditMode.RESET else FieldEditMode.UNCHANGED
                    },
                    singleLine = false,
                    minLines = 3,
                    maxLines = 6,
                    error = if (descriptionTooLong) {
                        stringResource(
                            R.string.book_metadata_field_too_long,
                            BookMetadataOverrideRepository.MAX_DESCRIPTION_BYTES,
                        )
                    } else {
                        null
                    },
                )

                val seriesName = book.seriesName?.takeIf { it.isNotBlank() }
                val seriesIndex = seriesIndexLabel(validSeriesPosition(book.seriesIndex))
                val seriesSummary = when {
                    seriesName == null -> stringResource(R.string.book_metadata_no_series)
                    seriesIndex == null -> seriesName
                    else -> stringResource(R.string.series_position, seriesName, seriesIndex)
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (eInk) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(
                        1.dp,
                        if (eInk) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClick = onEditSeries),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.sort_series),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = seriesSummary,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(
                            onClick = onEditSeries,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.book_metadata_edit_series))
                        }
                    }
                }
            }
            HorizontalDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                if (book.hasMetadataOverride) {
                    TextButton(
                        onClick = {
                            onResetAllOverrides()
                            onDismiss()
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.book_metadata_reset_all))
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val title = resolveFieldEdit(titleMode, titleText, initialTitle)
                        val author = resolveFieldEdit(authorMode, authorText, initialAuthor)
                        val description = resolveFieldEdit(descriptionMode, descriptionText, initialDescription)
                        scope.launch {
                            val bytes = coverUri?.let { uri -> readCoverBytes(context, Uri.parse(uri)) }
                            onSave(title, author, description, coverEditOf(coverMode, bytes))
                            onDismiss()
                        }
                    },
                    enabled = !tooLong,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}

@Composable
private fun MetadataTextFieldSection(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    canReset: Boolean,
    onReset: () -> Unit,
    singleLine: Boolean,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    error: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (canReset) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onReset,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.book_metadata_field_reset))
                }
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            isError = error != null,
            supportingText = error?.let { message -> { Text(message) } },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private suspend fun readCoverBytes(context: Context, uri: Uri): ByteArray? =
    withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_COVER_SOURCE_BYTES) return@use null
                    out.write(buffer, 0, read)
                }
                out.toByteArray().takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }
