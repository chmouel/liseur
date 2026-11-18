package com.chmouel.liseur.ui.settings

import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.reader.dictionary.OfflineDictionary
import com.chmouel.liseur.translate.TranslationLanguages
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.launchPicker
import com.chmouel.liseur.ui.windowWidth
import java.text.Collator
import java.text.NumberFormat
import java.util.Locale

private const val READER_DICT_URL = "https://www.reader-dict.com"

private val ARCHIVE_MIME_TYPES = arrayOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/octet-stream",
)

/**
 * On-device dictionaries inside Settings → Reading & navigation → Dictionary.
 *
 * Installed dictionaries sit as clean rows inside the section card — language
 * name, entry count and disk footprint, a switch to turn each on or off, and
 * a delete button with confirmation — followed by one "Add offline dictionary"
 * row that opens a searchable sheet of reader.dict languages plus local
 * StarDict ZIP import.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OfflineDictionarySettings(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ui = LocalConfiguration.current.locales[0]
    val state by OfflineDictionary.state(context).collectAsState()
    var adding by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<OfflineDictionary.Pack?>(null) }

    val operation = state.operation
    val busy = operation is OfflineDictionary.Operation.Downloading ||
        operation is OfflineDictionary.Operation.Unpacking

    Column(modifier) {
        if (state.packs.isEmpty() && !busy) {
            ListItem(
                modifier = Modifier.clickable(role = Role.Button) {
                    OfflineDictionary.clearFailure()
                    adding = true
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = {
                    Icon(
                        Icons.Outlined.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                headlineContent = {
                    Text(
                        text = stringResource(R.string.offline_dictionary_title),
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                supportingContent = {
                    Text(stringResource(R.string.offline_dictionary_empty_detail))
                },
            )
        } else {
            state.packs.forEachIndexed { index, pack ->
                if (index > 0) RowDivider()
                InstalledPackRow(
                    pack = pack,
                    ui = ui,
                    enabled = !busy,
                    onToggle = { checked -> OfflineDictionary.setEnabled(context, pack.id, checked) },
                    onRemove = { removing = pack },
                )
            }
            if (!busy) {
                if (state.packs.isNotEmpty()) RowDivider()
                ListItem(
                    modifier = Modifier.clickable(role = Role.Button) {
                        OfflineDictionary.clearFailure()
                        adding = true
                    },
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
                            text = stringResource(R.string.offline_dictionary_add),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                )
            }
        }

        when (operation) {
            OfflineDictionary.Operation.Idle -> Unit
            is OfflineDictionary.Operation.Downloading -> {
                if (state.packs.isNotEmpty()) RowDivider()
                val languageName = dictionaryLanguageTitle(operation.language, ui)
                val doneLabel = Formatter.formatShortFileSize(context, operation.downloadedBytes)
                val status = if (operation.totalBytes != null && operation.totalBytes > 0L) {
                    stringResource(
                        R.string.offline_dictionary_downloading_progress,
                        languageName,
                        doneLabel,
                        Formatter.formatShortFileSize(context, operation.totalBytes),
                    )
                } else {
                    stringResource(R.string.offline_dictionary_downloading, languageName, doneLabel)
                }
                val fraction = operation.totalBytes?.takeIf { it > 0L }?.let { total ->
                    (operation.downloadedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                }
                ProgressRow(
                    label = status,
                    progress = fraction,
                    onCancel = OfflineDictionary::cancel,
                )
            }
            is OfflineDictionary.Operation.Unpacking -> {
                if (state.packs.isNotEmpty()) RowDivider()
                val target = operation.label.takeIf(String::isNotBlank)?.let { raw ->
                    if (raw in OfflineDictionary.downloadLanguages) dictionaryLanguageTitle(raw, ui) else raw
                }
                val status = if (target != null) {
                    stringResource(R.string.offline_dictionary_unpacking_named, target)
                } else {
                    stringResource(R.string.offline_dictionary_working)
                }
                ProgressRow(
                    label = status,
                    progress = null,
                    onCancel = OfflineDictionary::cancel,
                )
            }
            is OfflineDictionary.Operation.Failed -> {
                RowDivider()
                val message = when (operation.reason) {
                    OfflineDictionary.FailureReason.DOWNLOAD -> stringResource(R.string.offline_dictionary_download_error)
                    OfflineDictionary.FailureReason.IMPORT -> stringResource(R.string.offline_dictionary_error)
                    OfflineDictionary.FailureReason.REMOVE -> stringResource(R.string.offline_dictionary_error)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    if (operation.reason == OfflineDictionary.FailureReason.DOWNLOAD && operation.language != null) {
                        TextButton(onClick = { OfflineDictionary.startDownload(context, operation.language) }) {
                            Text(stringResource(R.string.offline_dictionary_retry))
                        }
                    } else {
                        IconButton(onClick = OfflineDictionary::clearFailure) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.close),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    removing?.let { pack ->
        val title = packDisplayTitle(pack, ui)
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.offline_dictionary_remove_title, title)) },
            text = { Text(stringResource(R.string.offline_dictionary_remove_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        OfflineDictionary.remove(context, pack.id)
                        removing = null
                    },
                ) {
                    Text(stringResource(R.string.remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (adding) {
        OfflineDictionaryPickerSheet(pinnedLanguage = null, onDismiss = { adding = false })
    }
}

/**
 * The "add an offline dictionary" sheet: download one from reader.dict or
 * import a StarDict archive. Shared by Settings and the definition card, so
 * a reader can set one up from the first word they look up.
 * [pinnedLanguage] (the book's language) is listed first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OfflineDictionaryPickerSheet(pinnedLanguage: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by OfflineDictionary.state(context).collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // The sheet stays up until here: leaving composition would unregister this launcher
        // while the system picker is still open and the result would be lost.
        onDismiss()
        if (uri == null) return@rememberLauncherForActivityResult
        val displayName = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        OfflineDictionary.startImport(context, uri, displayName)
    }
    LiseurModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        AddOfflineDictionarySheet(
            installed = state.packs,
            pinnedLanguage = pinnedLanguage,
            onDownload = { language ->
                onDismiss()
                OfflineDictionary.startDownload(context, language)
            },
            onImportZip = { picker.launchPicker(context, ARCHIVE_MIME_TYPES) },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun InstalledPackRow(
    pack: OfflineDictionary.Pack,
    ui: Locale,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val title = packDisplayTitle(pack, ui)
    val flag = pack.language.takeIf(String::isNotBlank)?.let(TranslationLanguages::flag)
    val details = buildList {
        if (pack.isReaderDict) {
            add("reader.dict")
        } else if (pack.language.isNotBlank() && !pack.name.equals(title, ignoreCase = true)) {
            add(dictionaryLanguageTitle(pack.language, ui))
        }
        if (pack.wordCount > 0) {
            add(
                stringResource(
                    R.string.offline_dictionary_entries,
                    NumberFormat.getIntegerInstance(ui).format(pack.wordCount),
                ),
            )
        }
        if (pack.sizeBytes > 0L) {
            add(Formatter.formatShortFileSize(context, pack.sizeBytes))
        }
    }.joinToString(" · ")

    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.toggleable(
            value = pack.enabled,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onToggle,
        ),
        headlineContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (flag != null) {
                    Text(flag, modifier = Modifier.clearAndSetSemantics {})
                }
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        supportingContent = details.takeIf(String::isNotBlank)?.let { { Text(it) } },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onRemove, enabled = enabled) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = stringResource(R.string.offline_dictionary_remove_title, title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = pack.enabled, onCheckedChange = null, enabled = enabled)
            }
        },
    )
}

@Composable
private fun ProgressRow(
    label: String,
    progress: Float?,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        }
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AddOfflineDictionarySheet(
    installed: List<OfflineDictionary.Pack>,
    pinnedLanguage: String?,
    onDownload: (String) -> Unit,
    onImportZip: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val ui = LocalConfiguration.current.locales[0]
    var query by rememberSaveable { mutableStateOf("") }
    val appPrimary = OfflineDictionary.primaryLanguage(ui.toLanguageTag())
    val installedReaderLangs = remember(installed) {
        installed.filter { it.isReaderDict }.map { OfflineDictionary.primaryLanguage(it.language) }.toSet()
    }

    val orderedCatalog = remember(ui, appPrimary, pinnedLanguage) {
        val collator = Collator.getInstance(ui)
        val (pinned, rest) = OfflineDictionary.catalog.partition {
            OfflineDictionary.samePrimary(it.language, appPrimary) ||
                (pinnedLanguage != null && OfflineDictionary.samePrimary(it.language, pinnedLanguage))
        }
        pinned.sortedByDescending {
            pinnedLanguage != null && OfflineDictionary.samePrimary(it.language, pinnedLanguage)
        } + rest.sortedWith { a, b ->
            collator.compare(dictionaryLanguageTitle(a.language, ui), dictionaryLanguageTitle(b.language, ui))
        }
    }

    val filtered = remember(orderedCatalog, query, ui) {
        val q = query.trim().lowercase(ui)
        if (q.isEmpty()) {
            orderedCatalog
        } else {
            orderedCatalog.filter { entry ->
                val localized = dictionaryLanguageTitle(entry.language, ui).lowercase(ui)
                val native = Locale.forLanguageTag(entry.language).let { it.getDisplayLanguage(it) }.lowercase(ui)
                localized.contains(q) || native.contains(q) || entry.language.lowercase(Locale.ROOT) == q
            }
        }
    }

    val windowHeight = LocalWindowInfo.current.containerSize.height
    val maxSheetHeight = with(LocalDensity.current) { windowHeight.toDp() } * 0.72f

    Column(
        modifier = Modifier
            .widthIn(max = contentWidthCap(windowWidth()))
            .fillMaxWidth()
            .imePadding()
            .padding(bottom = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
            Text(
                text = stringResource(R.string.offline_dictionary_sheet_title),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.clear))
                    }
                }
            },
            placeholder = { Text(stringResource(R.string.translation_picker_search)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        )

        LazyColumn(modifier = Modifier.heightIn(max = maxSheetHeight)) {
            if (query.isBlank()) {
                item(key = "import_zip") {
                    ListItem(
                        modifier = Modifier.clickable(role = Role.Button, onClick = onImportZip),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            Icon(
                                Icons.Outlined.FileOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.offline_dictionary_import),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        supportingContent = {
                            Text(stringResource(R.string.offline_dictionary_import_detail))
                        },
                    )
                    RowDivider()
                }
                item(key = "catalog_header") {
                    // Opens the site only when tapped; nothing is requested by showing the list.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clickable(role = Role.Button) { uriHandler.openUri(READER_DICT_URL) }
                            .heightIn(min = 48.dp)
                            .padding(start = 20.dp, end = 20.dp, top = 8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.offline_dictionary_catalog_heading),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Icon(
                            Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }

            if (filtered.isEmpty()) {
                item(key = "no_match") {
                    Text(
                        text = stringResource(R.string.translation_picker_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }
            } else {
                items(filtered, key = { it.language }) { entry ->
                    val alreadyInstalled = OfflineDictionary.primaryLanguage(entry.language) in installedReaderLangs
                    val languageTitle = dictionaryLanguageTitle(entry.language, ui)
                    val flag = TranslationLanguages.flag(entry.language)
                    val sizeLabel = Formatter.formatShortFileSize(context, entry.approxBytes)
                    val subtitle = if (alreadyInstalled) {
                        stringResource(R.string.offline_dictionary_installed_note, sizeLabel)
                    } else {
                        sizeLabel
                    }
                    ListItem(
                        modifier = Modifier.clickable(role = Role.Button) { onDownload(entry.language) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (flag != null) {
                                    Text(flag, modifier = Modifier.clearAndSetSemantics {})
                                }
                                Text(languageTitle)
                            }
                        },
                        supportingContent = { Text(subtitle) },
                        trailingContent = {
                            if (alreadyInstalled) {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Icon(
                                    Icons.Outlined.Download,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
            }

            item(key = "catalog_footer") {
                Text(
                    text = stringResource(R.string.offline_dictionary_catalog_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}

internal fun packDisplayTitle(pack: OfflineDictionary.Pack, ui: Locale): String {
    if (pack.isReaderDict && pack.language.isNotBlank()) {
        return dictionaryLanguageTitle(pack.language, ui)
    }
    return pack.name.ifBlank {
        if (pack.language.isNotBlank()) dictionaryLanguageTitle(pack.language, ui) else "StarDict"
    }
}

internal fun dictionaryLanguageTitle(tag: String, ui: Locale): String {
    val locale = Locale.forLanguageTag(tag)
    val raw = locale.getDisplayLanguage(ui).takeIf { it.isNotBlank() && !it.equals(tag, ignoreCase = true) }
        ?: TranslationLanguages.name(tag, ui)
    return raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase(ui) else it.toString() }
}
