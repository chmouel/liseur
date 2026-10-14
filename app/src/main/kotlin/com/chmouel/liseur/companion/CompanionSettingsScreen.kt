package com.chmouel.liseur.companion

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ServerList
import com.chmouel.liseur.providers.ServerConnections
import com.chmouel.liseur.providers.ServiceOption
import com.chmouel.liseur.providers.ServiceRow
import com.chmouel.liseur.providers.ServiceSheet
import com.chmouel.liseur.translate.TextPrice
import com.chmouel.liseur.translate.TranslationError
import com.chmouel.liseur.tts.ListedField
import com.chmouel.liseur.tts.Listing
import com.chmouel.liseur.tts.SpeechLocalNetworkPrompt
import com.chmouel.liseur.tts.priceText
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.settings.ConnectionRow
import com.chmouel.liseur.ui.settings.NestedPage
import com.chmouel.liseur.ui.settings.PaneBackButton
import com.chmouel.liseur.ui.settings.SettingsGroup
import com.chmouel.liseur.ui.settings.SwitchRow
import com.chmouel.liseur.ui.windowWidth
import kotlinx.coroutines.launch

/** The reading companion's row in Settings: the server and model it uses. */
@Composable
internal fun CompanionSettingsEntry(server: CompanionServer, onClick: () -> Unit) {
    val chosen by server.chosenServer.collectAsState(initial = null)
    val model by server.chosenModel.collectAsState(initial = "")
    ConnectionRow(
        icon = { Icon(Icons.Outlined.PersonSearch, contentDescription = null) },
        title = stringResource(R.string.companion_settings_title),
        subtitle = chosen?.takeIf { model.isNotBlank() }?.let {
            stringResource(R.string.companion_settings_entry_summary, it.name, model)
        } ?: stringResource(R.string.read_aloud_settings_entry_missing),
        onClick = onClick,
    )
}

/**
 * The reading companion's own screen: which listed server and model describe
 * characters, and whether chapters are recapped in the background. Kept
 * apart from translation's, so each can use a different model.
 * [servicesPage] is the Services page, opened in place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompanionSettingsScreen(
    server: CompanionServer,
    store: CompanionStore,
    onBack: () -> Unit,
    servicesPage: @Composable (onBack: () -> Unit) -> Unit,
) {
    var managing by rememberSaveable { mutableStateOf(false) }
    if (managing) {
        BackHandler { managing = false }
        NestedPage { servicesPage { managing = false } }
        return
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val chosen by server.chosenServer.collectAsState(initial = null)
    val servers by server.servers.collectAsState(initial = ServerList.Empty)
    val recaps by remember { server.recapsEnabled }.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.companion_settings_title)) },
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
                    stringResource(R.string.companion_settings_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val listed = servers.readable.orEmpty()
                ServiceRow(
                    icon = Icons.Outlined.Dns,
                    overline = stringResource(R.string.companion_settings_server),
                    headline = chosen?.name ?: stringResource(R.string.companion_settings_server_none),
                    supporting = chosen?.host ?: stringResource(R.string.companion_settings_server_none_detail),
                    changeLabel = stringResource(R.string.companion_settings_server_change),
                    onClick = { picking = true },
                )
                if (picking) {
                    ServiceSheet(
                        title = stringResource(R.string.companion_settings_server),
                        options = listed.map { connection ->
                            ServiceOption(
                                selected = connection.id == chosen?.id,
                                icon = Icons.Outlined.Dns,
                                headline = connection.name,
                                supporting = connection.host,
                                onClick = {
                                    picking = false
                                    scope.launch { server.select(connection.id) }
                                },
                            )
                        },
                        manageDetail = if (listed.isEmpty()) stringResource(R.string.companion_settings_manage_detail) else null,
                        onManage = {
                            picking = false
                            managing = true
                        },
                        onDismiss = { picking = false },
                    )
                }
                chosen?.let { connection -> ModelField(server, connection.url) }
                SettingsGroup(title = stringResource(R.string.companion_settings_recaps_group)) {
                    SwitchRow(
                        title = stringResource(R.string.companion_settings_recaps),
                        subtitle = stringResource(R.string.companion_settings_recaps_summary),
                        checked = recaps,
                        onCheckedChange = { on -> scope.launch { server.setRecaps(on, store) } },
                    )
                }
                Text(
                    stringResource(R.string.companion_settings_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun ModelField(server: CompanionServer, url: String) {
    val stored by server.chosenModel.collectAsState(initial = "")
    val allowed = SpeechLocalNetworkPrompt(server.localNetwork, url)
    val origin = ServerConnections.originOf(url).orEmpty()
    val generation by remember(origin) { server.generation(origin) }.collectAsState()
    var listing by remember(url, generation) { mutableStateOf<Listing?>(null) }
    var prices by remember(url, generation) { mutableStateOf(emptyMap<String, TextPrice>()) }
    var asked by remember(url, generation) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(url, generation, asked, allowed) {
        if (!allowed) return@LaunchedEffect
        listing = Listing.Loading(url)
        listing = server.models(url).fold(
            onSuccess = { models ->
                prices = models.mapNotNull { m -> m.price?.let { m.id to it } }.toMap()
                if (models.isEmpty()) {
                    Listing.Failed(url, R.string.read_aloud_settings_server_models_none)
                } else {
                    Listing.Loaded(url, models.map { it.id })
                }
            },
            onFailure = {
                Listing.Failed(
                    url,
                    when (it) {
                        is TranslationError.LocalNetworkBlocked -> R.string.server_local_network_blocked
                        is TranslationError.Network -> R.string.read_aloud_settings_server_unreachable
                        is TranslationError.InvalidKey -> R.string.read_aloud_settings_server_refused
                        else -> R.string.read_aloud_settings_server_models_failed
                    },
                )
            },
        )
    }
    Box(Modifier.padding(top = 16.dp)) {
        ListedField(
            title = stringResource(R.string.companion_settings_model),
            placeholder = stringResource(R.string.read_aloud_settings_server_model_choose),
            loading = stringResource(R.string.read_aloud_settings_server_models_loading),
            stored = stored,
            enabled = true,
            listing = listing?.takeIf { it.url == url },
            onSave = { model -> scope.launch { server.setModel(url, model) } },
            onRetry = { asked++ },
            detail = { id ->
                prices[id]?.let {
                    val locale = LocalConfiguration.current.locales[0]
                    if (it.input == 0.0 && it.output == 0.0) {
                        stringResource(R.string.translation_settings_model_free)
                    } else {
                        stringResource(R.string.translation_settings_model_price, priceText(it.input, locale), priceText(it.output, locale))
                    }
                }
            },
        )
    }
}
