package com.chmouel.liseur.translate

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ServerConnection
import com.chmouel.liseur.data.settings.ServerList
import com.chmouel.liseur.data.settings.ServerSettings
import com.chmouel.liseur.providers.ServiceOption
import com.chmouel.liseur.providers.ServiceRow
import com.chmouel.liseur.providers.ServiceSheet
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.settings.ConnectionRow
import com.chmouel.liseur.ui.settings.SettingsGroup
import com.chmouel.liseur.ui.windowWidth
import java.text.NumberFormat
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/** Translation's row on the main settings screen: which service, and into what. */
@Composable
internal fun TranslationSettingsEntry(feature: ServiceTranslate, onClick: () -> Unit) {
    val ui = LocalConfiguration.current.locales[0]
    val service by feature.service.collectAsState(initial = feature.services.first())
    val server by feature.server.collectAsState(initial = null)
    val ready by feature.ready.collectAsState()
    val stored by feature.target.collectAsState(initial = null)
    val target = TranslationLanguages.name(TranslationLanguages.target(stored, ui), ui)
    ConnectionRow(
        icon = { Icon(Icons.Outlined.Translate, contentDescription = null) },
        title = stringResource(R.string.translation_settings_title),
        subtitle = if (ready) {
            stringResource(R.string.translation_settings_entry_summary, server?.name ?: stringResource(service.label), target)
        } else {
            stringResource(R.string.read_aloud_settings_entry_missing)
        },
        onClick = onClick,
    )
}

/**
 * Translation's own screen: which service translates, its rows, the
 * language passages go into, and a test sentence that proves the whole
 * way works. [services] opens it on the Services page, as for a refused key.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranslationSettingsScreen(feature: ServiceTranslate, onBack: () -> Unit, services: Boolean) {
    // Opened in place, so it works the same from the reader.
    var managing by rememberSaveable { mutableStateOf(services) }
    if (managing) {
        // Opened straight onto the Services page, back leaves altogether.
        val leave = if (services) onBack else ({ managing = false })
        BackHandler(onBack = leave)
        feature.ServicesPage(onBack = leave)
        return
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val service by feature.service.collectAsState(initial = feature.services.first())
    val server by feature.server.collectAsState(initial = null)
    val servers by feature.servers.collectAsState(initial = ServerList.Empty)
    val configured by remember(service) { service.configured }.collectAsState(initial = true)
    val destination by produceState<String?>(null, service, server) { value = service.destination() }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.translation_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
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
                var picking by remember { mutableStateOf(false) }
                ServiceRow(
                    icon = service.icon,
                    overline = stringResource(R.string.translation_settings_provider),
                    headline = server?.name ?: stringResource(service.label),
                    supporting = when {
                        !configured -> stringResource(R.string.read_aloud_settings_entry_missing)
                        server != null -> server!!.host
                        else -> stringResource(service.summary)
                    },
                    changeLabel = stringResource(R.string.translation_settings_provider_change),
                    onClick = { picking = true },
                )
                if (picking) {
                    TranslationServiceSheet(
                        services = feature.services,
                        servers = servers.readable.orEmpty(),
                        selected = service,
                        selectedServer = server?.id,
                        onPick = {
                            picking = false
                            scope.launch { feature.choose(it) }
                        },
                        onPickServer = {
                            picking = false
                            scope.launch { feature.chooseServer(it.id) }
                        },
                        onManage = {
                            picking = false
                            managing = true
                        },
                        onDismiss = { picking = false },
                    )
                }
                SettingsGroup(server?.name ?: stringResource(service.label)) {
                    service.SettingsRows(onManageServices = { managing = true })
                    destination?.let {
                        Text(
                            text = stringResource(R.string.translation_settings_privacy, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
                SettingsGroup(stringResource(R.string.translation_settings_target)) {
                    TargetRow(feature, service, configured)
                    TestRow(feature, service, configured)
                }
                feature.saved?.let { SavedTranslationsGroup(it.value) }
            }
        }
    }
}

/** Every service, each listed server by name in place of the server kind, then the way to the Services page. */
@Composable
private fun TranslationServiceSheet(
    services: List<TranslationService>,
    servers: List<ServerConnection>,
    selected: TranslationService,
    selectedServer: String?,
    onPick: (TranslationService) -> Unit,
    onPickServer: (ServerConnection) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val options = services.flatMap { service ->
        if (service.id == ServerSettings.SERVER_PROVIDER) {
            servers.map { server ->
                ServiceOption(
                    selected = selected.id == service.id && server.id == selectedServer,
                    icon = service.icon,
                    headline = server.name,
                    supporting = server.host,
                    onClick = { onPickServer(server) },
                )
            }
        } else {
            listOf(
                ServiceOption(
                    selected = service.id == selected.id,
                    icon = service.icon,
                    headline = stringResource(service.label),
                    supporting = stringResource(service.summary),
                    onClick = { onPick(service) },
                ),
            )
        }
    }
    ServiceSheet(
        title = stringResource(R.string.translation_settings_provider),
        options = options,
        manageDetail = if (servers.isEmpty()) stringResource(R.string.translation_settings_manage_detail) else null,
        onManage = onManage,
        onDismiss = onDismiss,
    )
}

/**
 * The language passages go into: the app's until one is picked. Shut
 * while [service] cannot translate, such as the phone with no languages
 * installed, since it would offer nothing to pick.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetRow(feature: ServiceTranslate, service: TranslationService, enabled: Boolean) {
    val ui = LocalConfiguration.current.locales[0]
    val stored by feature.target.collectAsState(initial = null)
    val app = TranslationLanguages.of(ui.toLanguageTag()) ?: TranslationLanguages.target(null, ui)
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(enabled = enabled) { picking = true },
        colors = if (enabled) {
            ListItemDefaults.colors(containerColor = Color.Transparent)
        } else {
            ListItemDefaults.colors(
                containerColor = Color.Transparent,
                headlineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                supportingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        },
        headlineContent = { Text(stringResource(R.string.translation_settings_target)) },
        supportingContent = {
            Text(
                stored?.let { TranslationLanguages.name(it, ui) }
                    ?: stringResource(R.string.translation_settings_target_app, TranslationLanguages.name(app, ui)),
            )
        },
    )
    if (!picking || !enabled) return
    val offered by produceState<Map<String, PairState>?>(null, service) {
        value = service.targets(null) ?: TranslationLanguages.all().associateWith { PairState.Ready }
    }
    LiseurModalBottomSheet(
        onDismissRequest = { picking = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LanguagePicker(
            title = stringResource(R.string.translation_settings_target),
            languages = offered?.keys,
            pinned = listOf(app),
            selected = stored,
            needsDownload = offered.orEmpty().filterValues { it == PairState.NeedsDownload }.keys,
            onPick = {
                scope.launch { feature.setTarget(it) }
                picking = false
            },
            onBack = { picking = false },
            header = {
                ListItem(
                    modifier = Modifier.selectable(selected = stored == null, role = Role.RadioButton) {
                        scope.launch { feature.setTarget(null) }
                        picking = false
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = {
                        Text(
                            stringResource(R.string.translation_settings_target_follow),
                            color = if (stored == null) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
                    },
                    trailingContent = if (stored == null) {
                        { Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    } else {
                        null
                    },
                )
            },
        )
    }
}

/** What the test asked last. */
private sealed interface Test {
    data object Running : Test
    data class Passed(val text: String) : Test
    data class Failed(val error: TranslationError) : Test
}

/**
 * Translates one English sentence with the chosen service, so a reader
 * knows the whole way works, model and key included, before a book
 * needs it. Into the reader's language, or French for an English reader.
 */
@Composable
private fun TestRow(feature: ServiceTranslate, service: TranslationService, configured: Boolean) {
    val ui = LocalConfiguration.current.locales[0]
    val stored by feature.target.collectAsState(initial = null)
    val choice by feature.choice.collectAsState(initial = null)
    val wanted = TranslationLanguages.target(stored, ui)
    val target = if (TranslationLanguages.same(wanted, TEST_SOURCE)) TEST_FALLBACK else wanted
    var test by remember(service, choice, target) { mutableStateOf<Test?>(null) }
    var asked by remember(service, choice, target) { mutableIntStateOf(0) }
    LaunchedEffect(service, choice, target, asked) {
        if (asked == 0) return@LaunchedEffect
        test = Test.Running
        test = try {
            Test.Passed(feature.translate(service, TEST_SENTENCE, TEST_SOURCE, target))
        } catch (e: TranslationError) {
            Test.Failed(e)
        }
    }
    val name = produceState<String?>(null, service, choice) { value = service.destination() }.value
        ?: stringResource(R.string.translation_provider_device)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { asked++ }, enabled = configured && test != Test.Running) {
                Text(stringResource(R.string.translation_settings_test))
            }
            if (test == Test.Running) BusyIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        val result = when (val t = test) {
            is Test.Passed -> stringResource(R.string.translation_settings_test_passed, t.text)
            is Test.Failed -> errorSentence(t.error, name, TranslationLanguages.name(TEST_SOURCE, ui), TranslationLanguages.name(target, ui))
            else -> null
        }
        result?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (test is Test.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * How many translated sentences are kept on this phone and the room they
 * take, with the way to forget them all.
 */
@Composable
private fun SavedTranslationsGroup(saved: SavedTranslations) {
    val context = LocalContext.current
    val stats by saved.stats.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    SettingsGroup(stringResource(R.string.translation_settings_saved)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val count = stats?.sentences ?: 0
            stats?.let {
                Text(
                    text = if (count == 0) {
                        stringResource(R.string.translation_settings_saved_none)
                    } else {
                        pluralStringResource(
                            R.plurals.translation_settings_saved_count,
                            count,
                            NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0]).format(count),
                            Formatter.formatShortFileSize(context, it.bytes),
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { confirming = true }, enabled = count > 0 && !clearing) {
                    Text(stringResource(R.string.translation_settings_saved_clear))
                }
                if (clearing) BusyIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (failed) {
                Text(
                    text = stringResource(R.string.translation_settings_saved_clear_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Text(
                text = stringResource(R.string.translation_settings_saved_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.translation_settings_saved_clear_title)) },
            text = { Text(stringResource(R.string.translation_settings_saved_clear_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        clearing = true
                        failed = false
                        scope.launch {
                            failed = try {
                                saved.clear()
                                false
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                true
                            } finally {
                                clearing = false
                            }
                        }
                    },
                ) { Text(stringResource(R.string.translation_settings_saved_clear_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** The sentence that says what went wrong with [name], from [source] into [target]. */
@Composable
internal fun errorSentence(error: TranslationError, name: String, source: String, target: String): String = when (error) {
    is TranslationError.NotDownloaded -> stringResource(R.string.translation_not_downloaded, source, target)
    is TranslationError.Unsupported -> stringResource(R.string.translation_unsupported, source, target)
    is TranslationError.NotSetUp -> stringResource(R.string.translation_not_set_up)
    is TranslationError.InvalidKey -> stringResource(R.string.translation_key_refused, name)
    is TranslationError.LocalNetworkBlocked -> stringResource(R.string.server_local_network_blocked)
    is TranslationError.Network -> stringResource(R.string.translation_network, name)
    is TranslationError.RateLimited -> stringResource(R.string.translation_rate_limited, name)
    is TranslationError.Refused -> stringResource(R.string.translation_refused, name)
    is TranslationError.Empty -> stringResource(R.string.translation_empty, name)
    is TranslationError.Truncated -> stringResource(R.string.translation_truncated, name)
    is TranslationError.DeviceFailed -> stringResource(R.string.translation_device_failed)
    is TranslationError.Service, is TranslationError.Malformed, is TranslationError.Changed ->
        stringResource(R.string.translation_failed, name)
}

/**
 * The device's rows: that the phone cannot translate, or the way to its
 * language downloads. Asked again on returning from them.
 */
@Composable
internal fun DeviceTranslationRows(service: DeviceTranslationService) {
    val configured by service.configured.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    LaunchedEffect(resumed) { if (resumed > 0) service.refresh() }
    val hasDownloads by produceState(false) { value = service.hasDownloads() }
    when (configured) {
        null -> Unit
        false -> Text(
            text = stringResource(R.string.translation_settings_device_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        true -> if (hasDownloads) {
            ListItem(
                modifier = Modifier.clickable(role = Role.Button) { scope.launch { service.openDownloads() } },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text(stringResource(R.string.translation_download)) },
                supportingContent = { Text(stringResource(R.string.translation_settings_device_downloads_detail)) },
            )
        }
    }
}

private const val TEST_SENTENCE = "The lamp was still burning when she came down the stairs."
private const val TEST_SOURCE = "en"
private const val TEST_FALLBACK = "fr"
