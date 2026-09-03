package com.chmouel.liseur.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth

/**
 * Books taken off the shelf whose files were kept, and the way back.
 *
 * Taking an entry off the shelf leaves no trace anywhere else, and a
 * removal a reader cannot see is one they cannot undo once the snackbar
 * has gone. So the list is here, and so is the only thing anyone comes
 * here to do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenBooksScreen(
    hidden: List<Book>,
    onUnhide: (Book) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.hidden_books)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
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
                if (hidden.isEmpty()) {
                    Text(
                        text = stringResource(R.string.hidden_books_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                } else {
                    SettingsGroup(stringResource(R.string.hidden_books)) {
                        hidden.forEachIndexed { index, book ->
                            if (index > 0) RowDivider()
                            ListItem(
                                headlineContent = { Text(book.title) },
                                supportingContent = book.author?.let { { Text(it) } },
                                trailingContent = {
                                    TextButton(onClick = { onUnhide(book) }) {
                                        Text(stringResource(R.string.hidden_books_put_back))
                                    }
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = Color.Transparent,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
