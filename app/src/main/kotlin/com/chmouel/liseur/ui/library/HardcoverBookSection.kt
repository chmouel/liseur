package com.chmouel.liseur.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.R
import com.chmouel.liseur.data.hardcover.HardcoverEdition
import com.chmouel.liseur.data.hardcover.HardcoverRepository
import com.chmouel.liseur.data.remote.RemoteResult
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.settings.hardcoverMessageRes
import kotlinx.coroutines.launch

/**
 * One book's standing with Hardcover: which edition it is, whether it
 * is reported at all, and a way to report it now.
 *
 * Shown only while an account is connected. The edition is the reader's
 * to confirm: an ISBN picks it without asking, and anything less exact
 * is offered here as a list rather than decided behind their back.
 */
@Composable
fun HardcoverBookSection(
    bookUrl: String,
    hardcover: HardcoverRepository,
    modifier: Modifier = Modifier,
) {
    val account by hardcover.account.collectAsStateWithLifecycle(null)
    if (account == null) return
    val link by remember(bookUrl) { hardcover.link(bookUrl) }.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var choices by remember { mutableStateOf<List<HardcoverEdition>?>(null) }

    Column(modifier) {
        HorizontalDivider(
            Modifier.padding(top = 24.dp, bottom = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            text = stringResource(R.string.hardcover_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = link?.takeIf { it.matched }?.editionTitle
                ?.let { stringResource(R.string.hardcover_book_matched, it) }
                ?: stringResource(R.string.hardcover_book_unmatched),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        val excluded = link?.excluded == true
        ListItem(
            headlineContent = { Text(stringResource(R.string.hardcover_book_sync)) },
            trailingContent = { Switch(checked = !excluded, onCheckedChange = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.toggleable(
                value = !excluded,
                role = Role.Switch,
                onValueChange = { on -> scope.launch { hardcover.setExcluded(bookUrl, !on) } },
            ),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        when (val found = hardcover.candidates(bookUrl)) {
                            is RemoteResult.Ok -> choices = found.value
                            is RemoteResult.Failed -> message = found.reason.hardcoverMessageRes()
                        }
                        busy = false
                    }
                },
            ) {
                Text(stringResource(R.string.hardcover_book_choose))
            }
            OutlinedButton(
                enabled = !busy && !excluded,
                onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        message = hardcover.push(bookUrl, asked = true).hardcoverMessageRes()
                        busy = false
                    }
                },
            ) {
                Text(stringResource(R.string.hardcover_push))
            }
            if (busy) BusyIndicator()
        }
        message?.let {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    choices?.let { editions ->
        AlertDialog(
            onDismissRequest = { choices = null },
            title = { Text(stringResource(R.string.hardcover_book_choose)) },
            text = {
                if (editions.isEmpty()) {
                    Text(stringResource(R.string.hardcover_editions_none))
                } else {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        editions.forEach { edition ->
                            ListItem(
                                headlineContent = {
                                    Text(edition.title.ifBlank { edition.bookTitle.orEmpty() })
                                },
                                supportingContent = {
                                    Text(
                                        listOfNotNull(
                                            edition.format,
                                            edition.releaseYear?.toString(),
                                            edition.publisher,
                                            edition.isbn13,
                                        ).joinToString(" · "),
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.fillMaxWidth().clickable {
                                    choices = null
                                    scope.launch { hardcover.choose(bookUrl, edition) }
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choices = null }) {
                    Text(stringResource(R.string.reader_sync_cancel))
                }
            },
        )
    }
}
