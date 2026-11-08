package com.chmouel.liseur.ui.library

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.remote.DownloadFormat

/**
 * Which of a book's formats to download, when the catalog offers more
 * than one. The choice is the catalog's, not the book's, and says so.
 */
@Composable
internal fun DownloadFormatPicker(
    formats: List<DownloadFormat>,
    chosen: DownloadFormat?,
    enabled: Boolean,
    onChoose: (DownloadFormat) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (formats.size < 2) return
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            OutlinedButton(
                onClick = { open = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(
                    stringResource(R.string.download_format, formatLabel(chosen ?: formats.first())),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                formats.forEach { format ->
                    DropdownMenuItem(
                        text = { Text(formatLabel(format)) },
                        leadingIcon = {
                            if (format.key == chosen?.key) Icon(Icons.Outlined.Check, contentDescription = null)
                        },
                        trailingIcon = format.sizeBytes?.let { size ->
                            { Text(Formatter.formatShortFileSize(context, size)) }
                        },
                        onClick = {
                            open = false
                            onChoose(format)
                        },
                    )
                }
            }
        }
        Text(
            stringResource(R.string.download_format_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The link's own title when it has one; feeds that leave it out get the type's name. */
@Composable
private fun formatLabel(format: DownloadFormat): String =
    format.title ?: stringResource(
        if (format.type.orEmpty().lowercase().let { "kobo" in it || "kepub" in it }) {
            R.string.download_format_kepub
        } else {
            R.string.download_format_epub
        },
    )
