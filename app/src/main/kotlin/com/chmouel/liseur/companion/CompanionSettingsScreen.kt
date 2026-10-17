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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ServerList
import com.chmouel.liseur.providers.ServiceRow
import com.chmouel.liseur.providers.ServiceSheet
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.settings.ConnectionRow
import com.chmouel.liseur.ui.settings.NestedPage
import com.chmouel.liseur.ui.settings.PaneBackButton
import com.chmouel.liseur.ui.settings.RowDivider
import com.chmouel.liseur.ui.settings.SettingsExpandableGroup
import com.chmouel.liseur.ui.settings.SwitchRow
import com.chmouel.liseur.ui.windowWidth
import kotlinx.coroutines.launch

/** The reading companion's row in Settings: the service and model it uses. */
@Composable
internal fun CompanionSettingsEntry(server: CompanionServer, onClick: () -> Unit) {
    val codex by server.codexEnabled.collectAsState(initial = true)
    val service by remember { server.selected }.collectAsState(initial = null)
    ConnectionRow(
        icon = { Icon(Icons.Outlined.PersonSearch, contentDescription = null) },
        title = stringResource(R.string.companion_settings_title),
        subtitle = service?.summary() ?: if (codex) stringResource(R.string.codex_settings_available) else stringResource(R.string.read_aloud_settings_entry_missing),
        onClick = onClick,
    )
}

/**
 * The reading companion's own screen: which service and model describe
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
    val selected by remember { server.selected }.collectAsState(initial = server.server)
    val offered by remember { server.offered }.collectAsState(initial = listOf(server.server))
    val servers by server.server.servers.collectAsState(initial = ServerList.Empty)
    val codex by server.codexEnabled.collectAsState(initial = true)
    val recaps by remember { server.recapsEnabled }.collectAsState(initial = false)
    val storyAuto by remember { server.storyAutoOk }.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var advancedOpen by remember { mutableStateOf(false) }

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
                // With only servers to choose from, the row names a server, as before there were other services.
                val many = offered.size > 1
                val title = stringResource(if (many) R.string.companion_settings_service else R.string.companion_settings_server)
                val heading = selected?.heading()
                ServiceRow(
                    icon = heading?.icon ?: Icons.Outlined.Dns,
                    overline = title,
                    headline = heading?.headline ?: stringResource(R.string.companion_settings_server_none),
                    supporting = heading?.supporting ?: stringResource(R.string.companion_settings_server_none_detail),
                    changeLabel = stringResource(if (many) R.string.companion_settings_service_change else R.string.companion_settings_server_change),
                    onClick = { picking = true },
                )
                if (picking) {
                    ServiceSheet(
                        title = title,
                        options = offered.flatMap { service ->
                            key(service.id) {
                                service.options(inUse = service.id == selected?.id) { choose ->
                                    picking = false
                                    scope.launch { choose() }
                                }
                            }
                        },
                        manageDetail = if (servers.readable.isNullOrEmpty()) stringResource(R.string.companion_settings_manage_detail) else null,
                        onManage = {
                            picking = false
                            managing = true
                        },
                        onDismiss = { picking = false },
                    )
                }
                selected?.SettingsRows(onManageServices = { managing = true })
                SettingsExpandableGroup(
                    title = stringResource(R.string.settings_advanced),
                    expanded = advancedOpen,
                    onExpandedChange = { advancedOpen = it },
                ) {
                    Text(
                        text = stringResource(R.string.companion_settings_recaps_group),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                    SwitchRow(
                        title = stringResource(R.string.companion_settings_recaps),
                        subtitle = stringResource(R.string.companion_settings_recaps_summary),
                        checked = recaps,
                        onCheckedChange = { on -> scope.launch { server.setRecaps(on, store) } },
                    )
                    RowDivider()
                    SwitchRow(
                        title = stringResource(R.string.companion_settings_story_auto),
                        subtitle = stringResource(R.string.companion_settings_story_auto_summary),
                        checked = storyAuto,
                        onCheckedChange = { on -> scope.launch { server.setStoryAutoOk(on) } },
                    )
                    RowDivider()
                    SwitchRow(
                        title = stringResource(R.string.codex_settings_enabled),
                        subtitle = stringResource(R.string.codex_settings_summary),
                        checked = codex,
                        onCheckedChange = { on -> scope.launch { server.setCodexEnabled(on) } },
                    )
                }
                Text(
                    stringResource(selected?.privacy ?: R.string.companion_settings_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}
