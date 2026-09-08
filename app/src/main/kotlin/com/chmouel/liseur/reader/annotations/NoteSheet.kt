package com.chmouel.liseur.reader.annotations

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import com.chmouel.liseur.ui.LocalEInk

/** What the reader can do with a note they have opened. */
class NoteSheetActions(
    val onEdit: () -> Unit,
    val onRecolour: (HighlightTint) -> Unit,
    val onShare: () -> Unit,
    val onDelete: () -> Unit,
)

/**
 * A note, opened to be read.
 *
 * Tapping a plain highlight brings up the bar of things to do with it.
 * A mark that carries a note is different: the reader put words there,
 * and the first thing they want on tapping it is to see them, not a row
 * of buttons with the note hidden behind one. So this is a sheet — the
 * passage, quoted, with the mark's colour running down its side, and the
 * note under it in full — and the things to do with it come after.
 *
 * It is painted in the reading theme rather than in Material colours,
 * for the reason the footnote card is: it sits over the page, and a
 * white sheet over a black page at night is a lamp in the face.
 *
 * Recolouring does not close it. The stripe changes where the reader is
 * looking, which is the confirmation; closing would send them back to
 * the page to check.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteSheet(
    annotation: BookAnnotation,
    passage: String,
    theme: ReaderTheme,
    palette: HighlightPalette,
    actions: NoteSheetActions,
    onDismiss: () -> Unit,
) {
    val eInk = LocalEInk.current
    val ink = theme.foreground
    // A wash under half ink dithers to grey on electronic paper; there the
    // quieter text is simply the ink.
    val quiet = if (eInk) ink else ink.copy(alpha = 0.7f)
    val faint = if (eInk) ink else ink.copy(alpha = 0.55f)
    val tint = HighlightTint.fromName(annotation.tint)

    LiseurModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = theme.background,
        contentColor = ink,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.annotation_note_sheet_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = faint,
            )

            if (passage.isNotBlank()) {
                Row(
                    Modifier
                        .padding(top = 12.dp)
                        .heightIn(max = 160.dp)
                        .height(IntrinsicSize.Min),
                ) {
                    Spacer(
                        Modifier
                            .width(4.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(tint.color),
                    )
                    SelectionContainer {
                        Text(
                            text = passage.trim(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic,
                            color = quiet,
                            modifier = Modifier
                                .padding(start = 14.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }

            SelectionContainer {
                Text(
                    text = annotation.note.orEmpty().trim(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = ink,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }

            Text(
                text = stamps(annotation),
                style = MaterialTheme.typography.labelSmall,
                color = faint,
                modifier = Modifier.padding(top = 12.dp),
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    palette.chipsFor(tint).forEach { chip ->
                        TintChip(
                            tint = chip,
                            selected = chip == tint,
                            onClick = { actions.onRecolour(chip) },
                            ringColor = ink,
                        )
                    }
                }
                IconButton(onClick = actions.onShare) {
                    Icon(
                        Icons.Outlined.Share,
                        contentDescription = stringResource(R.string.annotation_share),
                        tint = quiet,
                    )
                }
                IconButton(onClick = actions.onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.annotation_delete),
                        tint = quiet,
                    )
                }
                TextButton(onClick = actions.onEdit) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = null,
                        tint = ink,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Text(stringResource(R.string.annotation_note_edit), color = ink)
                }
            }
        }
    }
}

/**
 * When the note was made, and when it was last changed if that was a
 * later sitting. The chapter comes first when the mark kept one, because
 * that is how the reader remembers where a thought came from.
 *
 * The times read "at 8:59" today and "on 5 Sept" after that: a note is
 * a thing written, and a written thing has a date rather than an age.
 */
@Composable
private fun stamps(annotation: BookAnnotation): String {
    val context = LocalContext.current
    val parts = mutableListOf<String>()
    val noteCreatedAt = annotation.noteCreatedAt ?: annotation.createdAt
    val noteUpdatedAt = annotation.noteUpdatedAt
    annotation.chapter?.takeIf { it.isNotBlank() }?.let(parts::add)
    parts += stringResource(R.string.annotation_note_added, dated(context, noteCreatedAt))
    if (noteUpdatedAt != null && NoteText.edited(noteCreatedAt, noteUpdatedAt)) {
        parts += stringResource(
            R.string.annotation_note_edited,
            dated(context, noteUpdatedAt / 1000),
        )
    }
    return parts.joinToString(" \u00B7 ")
}

private fun dated(context: Context, atMs: Long): CharSequence =
    DateUtils.getRelativeTimeSpanString(context, atMs, true)
