package com.chmouel.liseur.translate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.settings.PaneBackButton
import com.chmouel.liseur.ui.windowWidth
import java.text.Collator

/**
 * Every language Google's translator has: those on the phone, which can be
 * deleted, and the others, which can be downloaded ahead of reading.
 * [target] is the language passages go into, named on its row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MlKitLanguagesPage(service: MlKitTranslationService, target: String, onBack: () -> Unit) {
    val ui = LocalConfiguration.current.locales[0]
    val have by service.models.collectAsState()
    val busy by service.changes.collectAsState()
    var failed by remember { mutableStateOf(false) }
    var asked by remember { mutableIntStateOf(0) }
    LaunchedEffect(asked) {
        failed = try {
            service.load()
            false
        } catch (e: TranslationError) {
            true
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<MlKitLanguages.Entry?>(null) }
    val targetCode = MlKitLanguages.code(target, service.codes, asTarget = true)
    val order = remember(ui) { Collator.getInstance(ui) }
    val entries = remember(have, query, ui) {
        MlKitLanguages.entries(service.codes, have.orEmpty(), { TranslationLanguages.name(MlKitLanguages.tag(it), ui) }, query, order)
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.translation_mlkit_languages)) },
                navigationIcon = { PaneBackButton(onBack) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = contentWidthCap(windowWidth())).fillMaxWidth()) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.translation_mlkit_languages_search)) },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.clear))
                                }
                            }
                        },
                    )
                }
                when {
                    // A change whose confirming listing failed leaves nothing listed either.
                    have == null && (failed || busy.values.any { it is Change.Failed }) -> item {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.translation_mlkit_models_failed), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { asked++ }) { Text(stringResource(R.string.translation_mlkit_try_again)) }
                        }
                    }
                    have == null -> Unit
                    entries.isEmpty() -> item {
                        Text(
                            stringResource(R.string.translation_mlkit_languages_no_match),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        )
                    }
                    else -> {
                        val (onPhone, others) = entries.partition { it.onPhone }
                        if (onPhone.isNotEmpty()) {
                            item { Heading(stringResource(R.string.translation_mlkit_languages_on_phone)) }
                            items(onPhone, key = { it.code }) { entry ->
                                LanguageRow(entry, busy[entry.code], entry.code == targetCode, onDownload = service::fetch, onDelete = { deleting = entry })
                            }
                        }
                        if (others.isNotEmpty()) {
                            item { Heading(stringResource(R.string.translation_mlkit_languages_available)) }
                            items(others, key = { it.code }) { entry ->
                                LanguageRow(entry, busy[entry.code], entry.code == targetCode, onDownload = service::fetch, onDelete = { deleting = entry })
                            }
                        }
                    }
                }
                item {
                    Text(
                        stringResource(R.string.translation_mlkit_languages_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
                    )
                }
            }
        }
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.translation_mlkit_delete_title, entry.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.translation_mlkit_models_kept))
                    if (entry.code == targetCode) Text(stringResource(R.string.translation_mlkit_delete_target, entry.name))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        service.remove(entry.code)
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun LanguageRow(
    entry: MlKitLanguages.Entry,
    change: Change?,
    target: Boolean,
    onDownload: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val builtIn = entry.code == MlKitTranslationService.BUILT_IN
    val working = change == Change.Waiting || change == Change.Downloading || change == Change.Deleting
    val status = when (change) {
        Change.Waiting -> stringResource(R.string.translation_mlkit_language_waiting)
        Change.Downloading -> stringResource(R.string.translation_mlkit_downloading)
        Change.Deleting -> stringResource(R.string.translation_mlkit_language_deleting)
        is Change.Failed -> stringResource(
            when (change.why) {
                Change.Why.Wifi -> R.string.translation_mlkit_language_wifi
                Change.Why.Space -> R.string.translation_mlkit_language_space
                Change.Why.Download -> R.string.translation_mlkit_language_failed
                Change.Why.Delete -> R.string.translation_mlkit_language_delete_failed
            },
        )
        null -> null
    }
    val notes = listOfNotNull(
        when {
            builtIn -> stringResource(R.string.translation_mlkit_language_built_in)
            else -> stringResource(R.string.translation_mlkit_language_size)
        },
        stringResource(R.string.translation_mlkit_language_target).takeIf { target },
        stringResource(R.string.translation_mlkit_language_source_only).takeIf { entry.sourceOnly },
    ).joinToString(" · ")
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 4.dp),
        headlineContent = { Text(entry.name) },
        supportingContent = {
            Column {
                Text(notes)
                if (status != null) {
                    Text(
                        status,
                        color = if (change is Change.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        trailingContent = {
            when {
                working -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    BusyIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                builtIn -> Unit
                entry.onPhone -> IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.translation_mlkit_delete, entry.name))
                }
                else -> IconButton(onClick = { onDownload(entry.code) }) {
                    Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.translation_mlkit_language_download, entry.name))
                }
            }
        },
    )
}
