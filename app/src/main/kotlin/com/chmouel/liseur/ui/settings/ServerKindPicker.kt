package com.chmouel.liseur.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.SyncAbility
import com.chmouel.liseur.ui.LiseurModalBottomSheet

/**
 * Choosing which kind of server the library lives on.
 *
 * A choice most readers make once, so it is a single row that says what
 * is currently picked and opens a sheet, rather than a control laid out
 * across the top of the form. It used to be a segmented button, then a
 * `FlowRow` of chips once four labels stopped fitting a phone's width,
 * and a fifth kind would have wrapped it to a third row (issue #96).
 * A row and a sheet cost the same height whatever the count, and the
 * sheet is the only place any of this has ever been comparable: the
 * chips carried bare names, and the card under them explained only the
 * kind already selected.
 *
 * Kept out of `ServerAccountScreen.kt`, which is long enough.
 */
@Composable
internal fun ServerKindRow(
    kind: ServerKind,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            overlineContent = { Text(stringResource(R.string.server_kind)) },
            headlineContent = { Text(stringResource(kind.labelRes())) },
            supportingContent = { KindSupport(kind) },
            leadingContent = { ServerKindLogo(kind) },
            trailingContent = {
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = stringResource(R.string.server_kind_change),
                )
            },
        )
    }
}

/**
 * Every kind at once, which is the thing the reader could not do
 * before: the tagline says what the server is for, and the line under
 * it says whether your place in a book will follow you there.
 *
 * It opens expanded rather than half-way. A Material sheet's default
 * resting height is about half the window, which fitted four kinds and
 * hid the fifth below a fold with nothing to mark it, so OPDS read as
 * unsupported (issue #179). Scrolling is not the same as being seen.
 * The scroll stays for a short screen or a large font size, where the
 * sheet is capped at the window and the list runs on inside it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerKindSheet(
    selected: ServerKind,
    onPick: (ServerKind) -> Unit,
    onDismiss: () -> Unit,
) {
    LiseurModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .selectableGroup(),
        ) {
            Text(
                stringResource(R.string.server_kind),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            ServerKind.entries.forEach { kind ->
                ListItem(
                    modifier = Modifier.selectable(
                        selected = kind == selected,
                        role = Role.RadioButton,
                        onClick = { onPick(kind) },
                    ),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(stringResource(kind.labelRes())) },
                    supportingContent = { KindSupport(kind) },
                    leadingContent = { ServerKindLogo(kind) },
                    trailingContent = {
                        // Null, not a second handler: the row already
                        // carries the click, and giving the button one
                        // too makes TalkBack read two targets.
                        RadioButton(selected = kind == selected, onClick = null)
                    },
                )
            }
        }
    }
}

/** Redistributable logos or original provider glyphs, without copying restricted artwork. */
@Composable
private fun ServerKindLogo(kind: ServerKind) {
    val size = Modifier.size(32.dp)
    when (kind) {
        // BookOrbit retains its neutral placeholder.
        ServerKind.BOOKORBIT -> Icon(
            painter = painterResource(R.drawable.ic_server_generic),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = size,
        )

        else -> Image(
            painter = painterResource(kind.logoRes()),
            contentDescription = null,
            modifier = size,
        )
    }
}

private fun ServerKind.logoRes(): Int = when (this) {
    ServerKind.CALIBRE -> R.drawable.ic_server_calibre_web
    ServerKind.KOMGA -> R.drawable.ic_server_komga
    ServerKind.LISEUR_SYNC -> R.drawable.ic_server_liseur_sync
    ServerKind.BOOKORBIT -> R.drawable.ic_server_generic
    ServerKind.CUSTOM -> R.drawable.ic_server_custom
}

/** What a kind is for, and whether it keeps your place. */
@Composable
private fun KindSupport(kind: ServerKind) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(kind.taglineRes()))
        Text(
            stringResource(kind.syncLineRes()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What the picker says about keeping your place.
 */
private fun ServerKind.syncLineRes(): Int = when (this) {
    ServerKind.CUSTOM -> R.string.server_sync_custom
    else -> when (syncAbility) {
        SyncAbility.EXACT -> R.string.server_sync_exact
        SyncAbility.PROGRESSION -> R.string.server_sync_progression
        SyncAbility.NONE -> R.string.server_sync_none
    }
}

internal fun ServerKind.labelRes(): Int = when (this) {
    ServerKind.CALIBRE -> R.string.server_kind_calibre
    ServerKind.KOMGA -> R.string.server_kind_komga
    ServerKind.LISEUR_SYNC -> R.string.server_kind_liseur_sync
    ServerKind.BOOKORBIT -> R.string.server_kind_bookorbit
    ServerKind.CUSTOM -> R.string.server_kind_custom
}

internal fun ServerKind.taglineRes(): Int = when (this) {
    ServerKind.CALIBRE -> R.string.server_tagline_calibre
    ServerKind.KOMGA -> R.string.server_tagline_komga
    ServerKind.LISEUR_SYNC -> R.string.server_tagline_liseur_sync
    ServerKind.BOOKORBIT -> R.string.server_tagline_bookorbit
    ServerKind.CUSTOM -> R.string.server_tagline_custom
}

/** Where to get a liseur-sync server, for a reader who has not got one yet. */
private const val LISEUR_SYNC_SERVER_URL = "https://github.com/chmouel/liseur-sync"

/**
 * Where a reader who has not got this kind of server yet can read about
 * it. Every kind gets one: the form otherwise gives them nowhere to go.
 */
internal fun ServerKind.homeUrl(): String = when (this) {
    ServerKind.CALIBRE -> "https://github.com/janeczku/calibre-web"
    ServerKind.KOMGA -> "https://komga.org"
    ServerKind.LISEUR_SYNC -> LISEUR_SYNC_SERVER_URL
    ServerKind.BOOKORBIT -> "https://bookorbit.app"
    // The specification rather than a product: a Custom connection is
    // not a server anyone can go and get, it is whatever the reader
    // already runs.
    ServerKind.CUSTOM -> "https://specs.opds.io/opds-1.2"
}

internal fun ServerKind.linkRes(): Int = when (this) {
    ServerKind.CALIBRE -> R.string.server_link_calibre
    ServerKind.KOMGA -> R.string.server_link_komga
    ServerKind.LISEUR_SYNC -> R.string.liseur_sync_get_one
    ServerKind.BOOKORBIT -> R.string.server_link_bookorbit
    ServerKind.CUSTOM -> R.string.server_link_custom
}
