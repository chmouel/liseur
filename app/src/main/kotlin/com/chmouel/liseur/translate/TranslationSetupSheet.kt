package com.chmouel.liseur.translate

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ServerSettings
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The service the setup sheet turns on: the first of [candidates], in the
 * build's order, that can translate on this phone. Each is asked in turn,
 * so the phone's translator is only passed over once the system has said
 * it has none. Listed servers are never candidates: they are chosen by name.
 */
internal suspend fun setupDefault(candidates: List<Pair<String, Flow<Boolean>>>): String? =
    candidates.firstOrNull { (id, configured) -> id != ServerSettings.SERVER_PROVIDER && configured.first() }?.first

/**
 * Turning translation on from the library in one tap: the service this
 * phone can use, what agreeing to it means, and the language passages go
 * into, all filled in. Turning it on is choosing the service, so a
 * service that asks for agreement shows its terms here first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranslationSetupSheet(feature: ServiceTranslate, onDismiss: () -> Unit, onMore: () -> Unit) {
    val ui = LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    val default by produceState<TranslationService?>(null) {
        val id = setupDefault(feature.services.map { it.id to it.configured })
        value = feature.services.firstOrNull { it.id == id }
    }
    val stored by feature.target.collectAsState(initial = null)
    var picking by rememberSaveable { mutableStateOf(false) }
    val app = TranslationLanguages.of(ui.toLanguageTag()) ?: TranslationLanguages.target(null, ui)
    LiseurModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        val service = default
        if (picking && service != null) {
            // A service that asks for agreement is not asked anything before it.
            val offered by produceState<Collection<String>?>(null, service) {
                value = service.takeIf { it.consent == null }?.targets(null)?.keys ?: TranslationLanguages.all()
            }
            LanguagePicker(
                title = stringResource(R.string.translation_settings_target),
                languages = offered,
                pinned = listOf(app),
                selected = stored,
                needsDownload = emptySet(),
                onPick = {
                    scope.launch { feature.setTarget(it) }
                    picking = false
                },
                onBack = { picking = false },
                header = {
                    FollowAppRow(selected = stored == null) {
                        scope.launch { feature.setTarget(null) }
                        picking = false
                    }
                },
            )
            return@LiseurModalBottomSheet
        }
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.translation_setup_title), style = MaterialTheme.typography.titleLarge)
            if (service == null) {
                // Asking the phone's translator takes a moment at most.
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    BusyIndicator(modifier = Modifier.size(24.dp))
                }
            } else {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Icon(service.icon, contentDescription = null) },
                    overlineContent = { Text(stringResource(R.string.translation_settings_provider)) },
                    headlineContent = { Text(stringResource(service.label)) },
                    supportingContent = { Text(stringResource(service.summary)) },
                )
                service.consent?.let {
                    Text(
                        stringResource(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val target = stored ?: app
                ListItem(
                    modifier = Modifier.clickable(role = Role.Button) { picking = true },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    overlineContent = { Text(stringResource(R.string.translation_settings_target)) },
                    headlineContent = { LanguageLabel(target) },
                    supportingContent = if (stored == null) {
                        { Text(stringResource(R.string.translation_settings_target_follow)) }
                    } else {
                        null
                    },
                )
                Button(
                    onClick = {
                        scope.launch {
                            feature.choose(service)
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.translation_setup_turn_on)) }
            }
            TextButton(
                onClick = {
                    onDismiss()
                    onMore()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.translation_setup_choose_service)) }
        }
    }
}

/** The flag and language name, with the flag kept out of accessibility speech. */
@Composable
internal fun LanguageLabel(
    tag: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val ui = LocalConfiguration.current.locales[0]
    val flag = TranslationLanguages.flag(tag)
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (flag != null) Text(flag, modifier = Modifier.clearAndSetSemantics { })
        Text(
            TranslationLanguages.name(tag, ui),
            modifier = Modifier.weight(1f, fill = false),
            color = color,
            maxLines = maxLines,
            overflow = overflow,
        )
    }
}

/** The list's first row, which leaves the language to follow the app's. */
@Composable
internal fun FollowAppRow(selected: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                stringResource(R.string.translation_settings_target_follow),
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
        },
        trailingContent = if (selected) {
            { Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        } else {
            null
        },
    )
}
