package com.chmouel.liseur.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.BuildConfig
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.ConnectionsState
import com.chmouel.liseur.data.library.Inspection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.data.settings.EInkMode
import com.chmouel.liseur.data.settings.ThemeMode
import com.chmouel.liseur.domain.DictionaryUrl
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth

/** Everything about the app that is not about one particular book. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    dynamicColorAvailable: Boolean,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onVolumeKeys: (Boolean) -> Unit,
    onEInkMode: (EInkMode) -> Unit,
    onResumeLastBook: (Boolean) -> Unit,
    onDictionaryLookup: (Boolean) -> Unit,
    onDictionaryBaseUrl: (String) -> Unit,
    onOpenAccount: () -> Unit,
    onOpenSyncServer: () -> Unit,
    backup: AnnotationBackupUi,
    connections: ConnectionsState,
    onOpenSource: () -> Unit,
    onOpenLicences: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings)) },
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
                    // widthIn must come before fillMaxWidth: fillMaxSize would
                    // pin the width to the window first, leaving the cap with a
                    // fixed constraint it cannot narrow.
                    .widthIn(max = contentWidthCap(windowWidth()))
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    // Settings rows are a column of sentences. Run across a
                    // tablet they become a column of sentences with a foot of
                    // nothing after each one, and the switch at the end is a
                    // reach away from the label that explains it.
                    .padding(horizontal = 20.dp),
            ) {
                SectionTitle(stringResource(R.string.settings_appearance))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.settings_theme),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Row(
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = settings.themeMode == mode,
                                    onClick = { onThemeMode(mode) },
                                    label = { Text(stringResource(mode.label)) },
                                )
                            }
                        }
                    }
                }
                if (dynamicColorAvailable) {
                    SwitchRow(
                        title = stringResource(R.string.settings_dynamic_color),
                        subtitle = stringResource(R.string.settings_dynamic_color_detail),
                        checked = settings.dynamicColor,
                        onCheckedChange = onDynamicColor,
                    )
                }

                SectionTitle(stringResource(R.string.settings_reading))
                SwitchRow(
                    title = stringResource(R.string.settings_volume_keys),
                    subtitle = stringResource(R.string.settings_volume_keys_detail),
                    checked = settings.volumeKeysTurnPages,
                    onCheckedChange = onVolumeKeys,
                )
                SwitchRow(
                    title = stringResource(R.string.settings_resume),
                    subtitle = stringResource(R.string.settings_resume_detail),
                    checked = settings.resumeLastBook,
                    onCheckedChange = onResumeLastBook,
                )
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.settings_eink),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.settings_eink_detail),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            EInkMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = settings.eInkMode == mode,
                                    onClick = { onEInkMode(mode) },
                                    label = { Text(stringResource(mode.label)) },
                                )
                            }
                        }
                    }
                }

                SectionTitle(stringResource(R.string.settings_dictionary))
                SwitchRow(
                    title = stringResource(R.string.settings_dictionary_lookup),
                    subtitle = stringResource(R.string.settings_dictionary_lookup_detail),
                    checked = settings.dictionaryLookupEnabled,
                    onCheckedChange = onDictionaryLookup,
                )
                if (settings.dictionaryLookupEnabled) {
                    DictionarySiteCard(
                        baseUrl = settings.dictionaryBaseUrl,
                        onBaseUrl = onDictionaryBaseUrl,
                    )
                }

                SectionTitle(stringResource(R.string.settings_library))
                val catalog by connections.catalog.collectAsStateWithLifecycle(null)
                val sync by connections.sync.collectAsStateWithLifecycle(null)
                ConnectionRow(
                    icon = { Icon(Icons.Outlined.CloudDownload, contentDescription = null) },
                    title = stringResource(R.string.server_account),
                    // Connected: say to what. Not connected: say what it
                    // would do. A row that always reads as an invitation
                    // makes a connected server look unconnected.
                    subtitle = catalog?.let {
                        stringResource(
                            R.string.server_connected,
                            it.baseUrl,
                            it.username ?: "",
                        )
                    } ?: stringResource(R.string.settings_account_detail),
                    onClick = onOpenAccount,
                )
                ConnectionRow(
                    icon = { Icon(Icons.Outlined.CloudSync, contentDescription = null) },
                    title = stringResource(R.string.sync_server_account),
                    subtitle = sync?.let {
                        stringResource(R.string.server_connected, it.baseUrl, it.username)
                    } ?: stringResource(R.string.sync_server_account_detail),
                    onClick = onOpenSyncServer,
                )
                HighlightsBackupCard(backup = backup)

                SectionTitle(stringResource(R.string.settings_about))
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // The mark, on the one screen that says what the
                            // app is.  Which cut to draw is asked of the
                            // scheme in force rather than of the resource
                            // qualifiers, since those follow the system and
                            // the app's own dark setting may differ.
                            Image(
                                painter = painterResource(
                                    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
                                        R.drawable.ic_brand_emblem_night
                                    } else {
                                        R.drawable.ic_brand_emblem
                                    },
                                ),
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                            )
                            Column(Modifier.padding(start = 16.dp)) {
                                Text(
                                    text = stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = stringResource(R.string.about_tagline),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.about_version,
                                        BuildConfig.VERSION_NAME,
                                        BuildConfig.VERSION_CODE,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                        HorizontalDivider()
                        PlainRow(stringResource(R.string.about_source), onOpenSource)
                        HorizontalDivider()
                        PlainRow(stringResource(R.string.about_licences), onOpenLicences)
                    }
                }
                Text(
                    text = stringResource(R.string.about_licence_line),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 32.dp),
                )
            }
        }
    }
}

private val ThemeMode.label: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }

private val EInkMode.label: Int
    get() = when (this) {
        EInkMode.AUTO -> R.string.eink_auto
        EInkMode.ON -> R.string.eink_on
        EInkMode.OFF -> R.string.eink_off
    }

/**
 * Where definitions come from.
 *
 * Shown only once online lookups are on, because until then there is no
 * site to pick. The field keeps what is typed and only commits it once it
 * parses, so a half-typed address never becomes the stored one.
 */
@Composable
private fun DictionarySiteCard(baseUrl: String, onBaseUrl: (String) -> Unit) {
    var typed by remember(baseUrl) { mutableStateOf(baseUrl) }
    val normalised = remember(typed) { DictionaryUrl.normalise(typed) }
    val invalid = typed.isNotBlank() && normalised == null

    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_dictionary_site),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.settings_dictionary_site_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = typed,
                onValueChange = {
                    typed = it
                    DictionaryUrl.normalise(it)?.let(onBaseUrl)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                singleLine = true,
                isError = invalid,
                placeholder = { Text(stringResource(R.string.settings_dictionary_site_hint)) },
                supportingText = if (invalid) {
                    { Text(stringResource(R.string.settings_dictionary_site_invalid)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            if (normalised != DictionaryUrl.DEFAULT_BASE_URL) {
                TextButton(
                    onClick = {
                        typed = DictionaryUrl.DEFAULT_BASE_URL
                        onBaseUrl(DictionaryUrl.DEFAULT_BASE_URL)
                    },
                ) {
                    Text(stringResource(R.string.settings_dictionary_site_reset))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(Modifier.padding(top = 8.dp).fillMaxWidth()) {
        Row(
            modifier = Modifier
                // One tap target for the whole row, so the switch is not the
                // only thing you are allowed to hit. Toggleable rather than
                // merely clickable, so the row carries its own on-or-off state
                // and a screen reader can say which it is.
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.padding(top = 8.dp).fillMaxWidth()) {
        Column(
            Modifier
                .clickable(onClick = onClick)
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlainRow(title: String, onClick: () -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .clickable(onClick = onClick)
            .fillMaxWidth()
            .padding(16.dp),
    )
}

/**
 * Keeping and carrying marks, as one card rather than two blind rows.
 *
 * The summary says what an export would hold before the picker asks
 * where to put it, and a restore is previewed before it is applied:
 * a file is a leap otherwise, and "Restored 0 marks" is a poor way to
 * find out the books in it are not on this phone.
 */
@Composable
private fun HighlightsBackupCard(backup: AnnotationBackupUi) {
    // The confirm dialog is owed to whichever file is being asked about.
    (backup.pendingImport as? Inspection.Ready)?.let { ready ->
        AlertDialog(
            onDismissRequest = backup.dismissImport,
            title = { Text(stringResource(R.string.import_preview_title)) },
            text = {
                Text(
                    if (ready.preview.matchedBooks > 0) {
                        stringResource(
                            R.string.import_preview_body,
                            ready.preview.marks,
                            ready.preview.books,
                            ready.preview.matchedBooks,
                        )
                    } else {
                        stringResource(R.string.import_preview_none)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = backup.confirmImport,
                    enabled = ready.preview.matchedBooks > 0,
                ) {
                    Text(stringResource(R.string.import_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = backup.dismissImport) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Card(Modifier.padding(top = 8.dp).fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.annotations_backup_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = backup.summary?.let {
                        if (it.marks > 0) {
                            stringResource(R.string.annotations_backup_summary, it.marks, it.books)
                        } else {
                            stringResource(R.string.annotations_backup_none)
                        }
                    } ?: stringResource(R.string.annotations_backup_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BackupActionRow(
                icon = { Icon(Icons.Outlined.FileUpload, contentDescription = null) },
                title = stringResource(R.string.export_annotations),
                subtitle = stringResource(R.string.export_annotations_detail),
                enabled = (backup.summary?.marks ?: 0) > 0,
                onClick = backup.export,
            )
            BackupActionRow(
                icon = { Icon(Icons.Outlined.FileOpen, contentDescription = null) },
                title = stringResource(R.string.import_annotations),
                subtitle = stringResource(R.string.import_annotations_detail),
                enabled = true,
                onClick = backup.restore,
            )
            backup.status?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun BackupActionRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tint)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A server row that says where it points once it points anywhere.
 *
 * The icon is the same family the account screens themselves use, so
 * the row reads as a door into them rather than a new kind of thing.
 */
@Composable
private fun ConnectionRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(Modifier.padding(top = 8.dp).fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
            Column(Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
