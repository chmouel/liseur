package com.chmouel.liseur.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.DefinitionTarget
import com.chmouel.liseur.data.settings.EInkMode
import com.chmouel.liseur.data.settings.TapZones
import com.chmouel.liseur.domain.DictionaryUrl
import com.chmouel.liseur.domain.WiktionaryEditions
import com.chmouel.liseur.reader.dictionary.WiktionaryClient
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth

/**
 * How a book is turned, held and looked up — everything about reading
 * that is not how the page looks.
 *
 * Its own screen for the same reason the Advanced sheet is its own
 * sheet (`docs/adr/0001-advanced-reading-menu.md`): the settings list
 * grew one reasonable row at a time until the section a reader was
 * looking for was somewhere in the middle of eight switches. The
 * dictionary comes along because looking a word up is something you do
 * with a book open, not a thing the app does on its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingNavigationScreen(
    settings: AppSettings,
    vendorName: String?,
    onVolumeKeys: (Boolean) -> Unit,
    onTapZones: (TapZones) -> Unit,
    onPinchToResize: (Boolean) -> Unit,
    onResumeLastBook: (Boolean) -> Unit,
    onScrollMode: (Boolean) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onEInkMode: (EInkMode) -> Unit,
    onColorEInk: (Boolean) -> Unit,
    onVendorRefresh: (Boolean) -> Unit,
    onDefinitionTarget: (DefinitionTarget) -> Unit,
    onDictionaryLookup: (Boolean) -> Unit,
    onDictionaryBaseUrl: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings_reading_navigation)) },
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
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
            ) {
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
                        title = stringResource(R.string.settings_pinch_to_resize),
                        subtitle = stringResource(R.string.settings_pinch_to_resize_detail),
                        checked = settings.pinchToResize,
                        onCheckedChange = onPinchToResize,
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
                }

                // The e-ink rows are about the panel the book is drawn
                // on, not about reading, and under the reading header
                // they read as one more preference rather than as what
                // they are: a description of the hardware.
                SettingsGroup(stringResource(R.string.settings_screen)) {
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
            }
        }
    }
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

private val EInkMode.label: Int
    get() = when (this) {
        EInkMode.AUTO -> R.string.eink_auto
        EInkMode.ON -> R.string.eink_on
        EInkMode.OFF -> R.string.eink_off
    }

/** Internal: the Settings row summarises the current side in its subtitle. */
internal val TapZones.label: Int
    get() = when (this) {
        TapZones.STANDARD -> R.string.tap_zones_standard
        TapZones.SWAPPED -> R.string.tap_zones_swapped
    }

private val DefinitionTarget.label: Int
    get() = when (this) {
        DefinitionTarget.BUILT_IN -> R.string.definition_target_builtin
        DefinitionTarget.EXTERNAL_APP -> R.string.definition_target_external
    }
