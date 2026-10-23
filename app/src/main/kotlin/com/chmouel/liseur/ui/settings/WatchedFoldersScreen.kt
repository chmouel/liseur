package com.chmouel.liseur.ui.settings

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.LibraryFolder
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth

/**
 * The folders Liseur watches for EPUBs, and the way to add or stop
 * watching one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchedFoldersScreen(
    folders: List<LibraryFolder>,
    onAddFolder: () -> Unit,
    onRemoveFolder: (LibraryFolder) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var folderToRemove by remember { mutableStateOf<LibraryFolder?>(null) }

    folderToRemove?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToRemove = null },
            title = { Text(stringResource(R.string.remove_folder)) },
            text = {
                Text(
                    stringResource(
                        R.string.remove_folder_warning,
                        watchedFolderName(folder),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveFolder(folder)
                    folderToRemove = null
                }) {
                    Text(stringResource(R.string.remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { folderToRemove = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.library_folders)) },
                navigationIcon = { PaneBackButton(onBack) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = contentWidthCap(windowWidth()))
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    text = stringResource(R.string.add_folder_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 16.dp),
                )
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        if (folders.isEmpty()) {
                            Text(
                                text = stringResource(R.string.library_folders_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                            )
                            RowDivider()
                        } else {
                            folders.forEach { folder ->
                                ListItem(
                                    headlineContent = { Text(watchedFolderName(folder)) },
                                    supportingContent = { Text(folder.url) },
                                    leadingContent = {
                                        Icon(Icons.Outlined.FileOpen, contentDescription = null)
                                    },
                                    trailingContent = {
                                        IconButton(onClick = { folderToRemove = folder }) {
                                            Icon(
                                                Icons.Outlined.Delete,
                                                contentDescription = stringResource(
                                                    R.string.remove_folder_a11y,
                                                    watchedFolderName(folder),
                                                ),
                                            )
                                        }
                                    },
                                    colors = ListItemDefaults.colors(
                                        containerColor = Color.Transparent,
                                    ),
                                )
                                RowDivider()
                            }
                        }
                        ListItem(
                            modifier = Modifier.clickable(role = Role.Button, onClick = onAddFolder),
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = {
                                Icon(
                                    Icons.Outlined.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            headlineContent = {
                                Text(
                                    text = stringResource(R.string.add_folder),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

internal fun watchedFolderName(folder: LibraryFolder): String {
    val segment = Uri.parse(folder.url).lastPathSegment ?: return folder.url
    return segment.substringAfterLast(':').takeIf { it.isNotBlank() } ?: folder.url
}
