package com.chmouel.liseur.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
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

private const val LISEUR_SYNC_REPO_URL = "https://github.com/chmouel/liseur-sync"

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
                SettingsGroup(stringResource(R.string.settings_appearance)) {
                    ChipRow(
                        title = stringResource(R.string.settings_theme),
                        options = ThemeMode.entries,
                        selected = settings.themeMode,
                        label = { stringResource(it.label) },
                        onSelected = onThemeMode,
                    )
                    if (dynamicColorAvailable) {
                        RowDivider()
                        SwitchRow(
                            title = stringResource(R.string.settings_dynamic_color),
                            subtitle = stringResource(R.string.settings_dynamic_color_detail),
                            checked = settings.dynamicColor,
                            onCheckedChange = onDynamicColor,
                        )
                    }
                }

                SettingsGroup(stringResource(R.string.settings_reading)) {
                    SwitchRow(
                        title = stringResource(R.string.settings_volume_keys),
                        subtitle = stringResource(R.string.settings_volume_keys_detail),
                        checked = settings.volumeKeysTurnPages,
                        onCheckedChange = onVolumeKeys,
                    )
                    RowDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_resume),
                        subtitle = stringResource(R.string.settings_resume_detail),
                        checked = settings.resumeLastBook,
                        onCheckedChange = onResumeLastBook,
                    )
                    RowDivider()
                    ChipRow(
                        title = stringResource(R.string.settings_eink),
                        subtitle = stringResource(R.string.settings_eink_detail),
                        options = EInkMode.entries,
                        selected = settings.eInkMode,
                        label = { stringResource(it.label) },
                        onSelected = onEInkMode,
                    )
                }

                SettingsGroup(stringResource(R.string.settings_dictionary)) {
                    SwitchRow(
                        title = stringResource(R.string.settings_dictionary_lookup),
                        subtitle = stringResource(R.string.settings_dictionary_lookup_detail),
                        checked = settings.dictionaryLookupEnabled,
                        onCheckedChange = onDictionaryLookup,
                    )
                    if (settings.dictionaryLookupEnabled) {
                        RowDivider()
                        DictionarySiteRow(
                            baseUrl = settings.dictionaryBaseUrl,
                            onBaseUrl = onDictionaryBaseUrl,
                        )
                    }
                }

                val showLibraryHelp = remember { mutableStateOf(false) }
                if (showLibraryHelp.value) {
                    val uriHandler = LocalUriHandler.current
                    val helpBody = stringResource(
                        R.string.settings_library_help_body,
                        LISEUR_SYNC_REPO_URL,
                    )
                    AlertDialog(
                        onDismissRequest = { showLibraryHelp.value = false },
                        title = { Text(stringResource(R.string.settings_library_help_title)) },
                        text = {
                            // The address is part of the sentence; mark it
                            // up after the fact so translations can move it.
                            val linkColor = MaterialTheme.colorScheme.primary
                            val annotated = remember(helpBody) {
                                buildAnnotatedString {
                                    append(helpBody)
                                    val start = helpBody.indexOf(LISEUR_SYNC_REPO_URL)
                                    if (start >= 0) {
                                        addLink(
                                            LinkAnnotation.Url(LISEUR_SYNC_REPO_URL),
                                            start,
                                            start + LISEUR_SYNC_REPO_URL.length,
                                        )
                                        addStyle(
                                            SpanStyle(color = linkColor),
                                            start,
                                            start + LISEUR_SYNC_REPO_URL.length,
                                        )
                                    }
                                }
                            }
                            ClickableText(
                                text = annotated,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                onClick = { offset ->
                                    annotated
                                        .getLinkAnnotations(offset, offset)
                                        .firstOrNull()
                                        ?.let { uriHandler.openUri(LISEUR_SYNC_REPO_URL) }
                                },
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { showLibraryHelp.value = false }) {
                                Text(stringResource(R.string.dismiss))
                            }
                        },
                    )
                }
                SettingsGroup(
                    title = stringResource(R.string.settings_remote_library),
                    onHelp = { showLibraryHelp.value = true },
                ) {
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
                    RowDivider()
                    ConnectionRow(
                        icon = { Icon(Icons.Outlined.CloudSync, contentDescription = null) },
                        title = stringResource(R.string.sync_server_account),
                        subtitle = sync?.let {
                            stringResource(R.string.server_connected, it.baseUrl, it.username)
                        } ?: stringResource(R.string.sync_server_account_detail),
                        onClick = onOpenSyncServer,
                    )
                }
                HighlightsBackupCard(backup = backup)

                SettingsGroup(stringResource(R.string.settings_about)) {
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
                        RowDivider()
                        PlainRow(stringResource(R.string.about_source), onOpenSource)
                        RowDivider()
                        PlainRow(stringResource(R.string.about_licences), onOpenLicences)
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
private fun DictionarySiteRow(baseUrl: String, onBaseUrl: (String) -> Unit) {
    var typed by remember(baseUrl) { mutableStateOf(baseUrl) }
    val normalised = remember(typed) { DictionaryUrl.normalise(typed) }
    val invalid = typed.isNotBlank() && normalised == null

    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
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

/**
 * A section of the settings: a title over one rounded card, with the
 * rows inside it divided by hairlines.
 *
 * The screen used to put every switch in a card of its own, which read
 * as a stack of boxed islands whose padding changed from section to
 * section. One card per section puts the rows on a single axis and
 * makes the sections themselves the unit of the screen.
 */
@Composable
private fun SettingsGroup(
    title: String,
    onHelp: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        onHelp?.let {
            Icon(
                Icons.Outlined.HelpOutline,
                contentDescription = stringResource(R.string.settings_library_help_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(32.dp)
                    .clickable(onClick = it)
                    .padding(6.dp),
            )
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(content = content)
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            // One tap target for the whole row, so the switch is not the
            // only thing you are allowed to hit. Toggleable rather than
            // merely clickable, so the row carries its own on-or-off state
            // and a screen reader can say which it is.
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
    )
}

@Composable
private fun PlainRow(title: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * A row whose answer is one of a few chips — the theme, the e-ink
 * behaviour. Shaped like the other rows so it sits on the same axis:
 * the label where a headline would be, the chips where supporting
 * text would go.
 */
@Composable
private fun <T> ChipRow(
    title: String,
    subtitle: String? = null,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier
                .padding(top = 8.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelected(option) },
                    label = { Text(label(option)) },
                )
            }
        }
    }
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
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() } },
        colors = if (enabled) {
            ListItemDefaults.colors(containerColor = Color.Transparent)
        } else {
            ListItemDefaults.colors(
                containerColor = Color.Transparent,
                headlineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                supportingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
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
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() } },
        trailingContent = {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
