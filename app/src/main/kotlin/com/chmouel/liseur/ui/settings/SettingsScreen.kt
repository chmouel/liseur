package com.chmouel.liseur.ui.settings

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
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.ConnectionsState
import com.chmouel.liseur.data.library.Inspection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.data.settings.DefinitionTarget
import com.chmouel.liseur.data.settings.EInkMode
import com.chmouel.liseur.data.settings.ReaderThemeChoice
import com.chmouel.liseur.data.settings.TapZones
import com.chmouel.liseur.data.settings.ThemeMode
import com.chmouel.liseur.domain.DictionaryUrl
import com.chmouel.liseur.domain.WiktionaryEditions
import com.chmouel.liseur.reader.dictionary.WiktionaryClient
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.reading.label
import com.chmouel.liseur.ui.windowWidth

private const val LISEUR_SYNC_REPO_URL = "https://github.com/chmouel/liseur-sync"

/** Everything about the app that is not about one particular book. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    readingThemeChoice: ReaderThemeChoice,
    dynamicColorAvailable: Boolean,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onVolumeKeys: (Boolean) -> Unit,
    onTapZones: (TapZones) -> Unit,
    onEInkMode: (EInkMode) -> Unit,
    onColorEInk: (Boolean) -> Unit,
    onVendorRefresh: (Boolean) -> Unit,
    vendorName: String?,
    onResumeLastBook: (Boolean) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onGroupSeries: (Boolean) -> Unit,
    onScrollMode: (Boolean) -> Unit,
    onDefinitionTarget: (DefinitionTarget) -> Unit,
    onDictionaryLookup: (Boolean) -> Unit,
    onDictionaryBaseUrl: (String) -> Unit,
    onOpenAccount: () -> Unit,
    onOpenReadingAppearance: () -> Unit,
    backup: AnnotationBackupUi,
    connections: ConnectionsState,
    onOpenAbout: () -> Unit,
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
                    RowDivider()
                    // The theme above is the app's, and the page has one of
                    // its own; a reader who turns this screen dark and finds
                    // their books still white has nowhere to look unless
                    // this row says where. Naming the "Aa" button here is
                    // half of what it is for.
                    ConnectionRow(
                        icon = { Icon(Icons.Outlined.TextFormat, contentDescription = null) },
                        title = stringResource(R.string.settings_reading_appearance),
                        subtitle = stringResource(
                            R.string.settings_reading_appearance_current,
                            stringResource(readingThemeChoice.label),
                        ),
                        onClick = onOpenReadingAppearance,
                    )
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
                    ConnectionRow(
                        icon = { Icon(Icons.Outlined.CloudDownload, contentDescription = null) },
                        title = stringResource(R.string.server_account),
                        // Connected: say to what. Not connected: say what it
                        // would do. A row that always reads as an invitation
                        // makes a connected server look unconnected.
                        subtitle = catalog?.let {
                            val user = it.username?.takeIf { name -> name.isNotBlank() }
                            if (user == null) {
                                // An open catalog, or a connection that
                                // only carries positions, has no name to
                                // be signed in under.
                                stringResource(R.string.server_connected_anon, it.baseUrl)
                            } else {
                                stringResource(R.string.server_connected, it.baseUrl, user)
                            }
                        } ?: stringResource(R.string.settings_account_detail),
                        onClick = onOpenAccount,
                    )
                }

                SettingsGroup(stringResource(R.string.settings_library)) {
                    SwitchRow(
                        title = stringResource(R.string.settings_group_series),
                        subtitle = stringResource(R.string.settings_group_series_detail),
                        checked = settings.libraryFilters.groupBySeries,
                        onCheckedChange = onGroupSeries,
                    )
                }

                SettingsGroup(stringResource(R.string.settings_reading)) {
                    SwitchRow(
                        title = stringResource(R.string.settings_volume_keys),
                        subtitle = stringResource(R.string.settings_volume_keys_detail),
                        checked = settings.volumeKeysTurnPages,
                        onCheckedChange = onVolumeKeys,
                    )
                    RowDivider()
                    ChipRow(
                        title = stringResource(R.string.settings_tap_zones),
                        subtitle = stringResource(R.string.settings_tap_zones_detail),
                        options = TapZones.entries,
                        selected = settings.tapZones,
                        label = { stringResource(it.label) },
                        onSelected = onTapZones,
                    )
                    RowDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_resume),
                        subtitle = stringResource(R.string.settings_resume_detail),
                        checked = settings.resumeLastBook,
                        onCheckedChange = onResumeLastBook,
                    )
                    RowDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_scroll_mode),
                        subtitle = stringResource(R.string.settings_scroll_mode_detail),
                        checked = settings.scrollMode,
                        onCheckedChange = onScrollMode,
                    )
                    RowDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_keep_screen_on),
                        subtitle = stringResource(R.string.settings_keep_screen_on_detail),
                        checked = settings.keepScreenOn,
                        onCheckedChange = onKeepScreenOn,
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
                    if (LocalEInk.current) {
                        RowDivider()
                        SwitchRow(
                            title = stringResource(R.string.settings_color_eink),
                            subtitle = stringResource(R.string.settings_color_eink_detail),
                            checked = settings.colorEInk,
                            onCheckedChange = onColorEInk,
                        )
                    }
                    vendorName?.let { vendor ->
                        RowDivider()
                        SwitchRow(
                            title = stringResource(R.string.settings_vendor_refresh),
                            subtitle = stringResource(
                                R.string.settings_vendor_refresh_found,
                                vendor,
                            ),
                            checked = settings.vendorRefresh,
                            onCheckedChange = onVendorRefresh,
                        )
                    }
                }

                SettingsGroup(stringResource(R.string.settings_dictionary)) {
                    ChipRow(
                        title = stringResource(R.string.settings_define_with),
                        options = DefinitionTarget.entries,
                        selected = settings.definitionTarget,
                        label = { stringResource(it.label) },
                        onSelected = onDefinitionTarget,
                    )
                    if (settings.definitionTarget == DefinitionTarget.BUILT_IN) {
                        RowDivider()
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
                }

                SettingsGroup(stringResource(R.string.settings_export_import)) {
                    HighlightsBackupCard(backup = backup, grouped = true)
                }

                SettingsGroup(stringResource(R.string.settings_about)) {
                    PlainRow(stringResource(R.string.about_open), onOpenAbout)
                }
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

private val TapZones.label: Int
    get() = when (this) {
        TapZones.STANDARD -> R.string.tap_zones_standard
        TapZones.SWAPPED -> R.string.tap_zones_swapped
    }

private val DefinitionTarget.label: Int
    get() = when (this) {
        DefinitionTarget.BUILT_IN -> R.string.definition_target_builtin
        DefinitionTarget.EXTERNAL_APP -> R.string.definition_target_external
    }

/**
 * Where definitions come from.
 *
 * Shown only once online lookups are on, because until then there is no
 * site to pick. A dropdown of editions by name replaced the bare URL
 * field after a reader misspelled fr.wiktionary.org and got nothing but
 * an HTTP 501 out of it — a name cannot be typo'd. Mirrors still fit
 * through the custom entry, which keeps what is typed and only commits
 * it on Done, once it parses — so a half-typed address never becomes
 * the stored one. Every committed choice is then tried against the site
 * once, right here, so a dead address fails under the field and not
 * later in the reader.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DictionarySiteRow(baseUrl: String, onBaseUrl: (String) -> Unit) {
    val storedEdition = remember(baseUrl) { WiktionaryEditions.editionOf(baseUrl) }
    var customChosen by remember(baseUrl) { mutableStateOf(storedEdition == null) }
    var expanded by remember { mutableStateOf(false) }
    var typed by remember(baseUrl) { mutableStateOf(if (storedEdition == null) baseUrl else "") }
    val normalised = remember(typed) { DictionaryUrl.normalise(typed) }
    val invalid = typed.isNotBlank() && normalised == null

    // Probed only when the reader commits a choice here — never on
    // merely opening the screen, because a request nobody asked for
    // has no place in this app.
    var probeTarget by remember { mutableStateOf<String?>(null) }
    var probeResult by remember { mutableStateOf<ProbeUi>(ProbeUi.Idle) }
    val client = remember { WiktionaryClient() }
    LaunchedEffect(baseUrl) {
        // The stored site moved under us (a restore, another writer):
        // whatever the probe said, it said it about somewhere else.
        if (probeTarget != null && probeTarget != baseUrl) {
            probeTarget = null
            probeResult = ProbeUi.Idle
        }
    }
    LaunchedEffect(probeTarget) {
        val target = probeTarget ?: return@LaunchedEffect
        probeResult = ProbeUi.Checking
        val error = client.probe(target)
        probeResult = if (error == null) {
            ProbeUi.Ok(DictionaryUrl.hostOf(target))
        } else {
            ProbeUi.Unreachable(DictionaryUrl.hostOf(target), error)
        }
    }
    val commit = { url: String ->
        onBaseUrl(url)
        probeTarget = url
    }

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
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            OutlinedTextField(
                value = if (customChosen) {
                    stringResource(R.string.settings_dictionary_site_custom)
                } else {
                    storedEdition?.nativeName.orEmpty()
                },
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                WiktionaryEditions.all.forEach { edition ->
                    DropdownMenuItem(
                        text = { Text(edition.nativeName) },
                        onClick = {
                            expanded = false
                            customChosen = false
                            commit(edition.baseUrl)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings_dictionary_site_custom)) },
                    onClick = {
                        expanded = false
                        customChosen = true
                    },
                )
            }
        }
        if (customChosen) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                singleLine = true,
                isError = invalid,
                placeholder = { Text(stringResource(R.string.settings_dictionary_site_hint)) },
                supportingText = if (invalid) {
                    { Text(stringResource(R.string.settings_dictionary_site_invalid)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                // Committed on Done, not per keystroke: a commit stores
                // the setting and probes the site, and a half-typed host
                // deserves neither.
                keyboardActions = KeyboardActions(onDone = { normalised?.let(commit) }),
            )
        }
        when (val probe = probeResult) {
            ProbeUi.Idle -> Unit
            ProbeUi.Checking -> Text(
                text = stringResource(R.string.settings_dictionary_site_checking),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            is ProbeUi.Ok -> Text(
                text = stringResource(R.string.settings_dictionary_site_ok, probe.host),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
            is ProbeUi.Unreachable -> Text(
                text = stringResource(
                    R.string.settings_dictionary_site_unreachable,
                    probe.host,
                    probe.error,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (baseUrl != DictionaryUrl.DEFAULT_BASE_URL) {
            TextButton(
                onClick = {
                    typed = ""
                    customChosen = false
                    commit(DictionaryUrl.DEFAULT_BASE_URL)
                },
            ) {
                Text(stringResource(R.string.settings_dictionary_site_reset))
            }
        }
    }
}

/** The one-shot check of a committed dictionary site, as the row shows it. */
private sealed interface ProbeUi {
    data object Idle : ProbeUi
    data object Checking : ProbeUi
    data class Ok(val host: String) : ProbeUi
    data class Unreachable(val host: String, val error: String) : ProbeUi
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
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            // One tap target for the whole row, so the switch is not the
            // only thing you are allowed to hit. Toggleable rather than
            // merely clickable, so the row carries its own on-or-off state
            // and a screen reader can say which it is.
            .toggleable(
                value = checked,
                enabled = enabled,
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
private fun HighlightsBackupCard(backup: AnnotationBackupUi, grouped: Boolean = false) {
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

    // Inside a section the card already exists; drawing another one
    // would nest a box in a box.
    val content: @Composable ColumnScope.() -> Unit = {
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
    if (grouped) {
        Column(content = content)
    } else {
        Card(Modifier.padding(top = 8.dp).fillMaxWidth(), content = content)
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
