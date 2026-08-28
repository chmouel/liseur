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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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
 * it says whether your place in a book will follow you there. Grimmory
 * cannot carry one at all, and that is worth knowing before typing a
 * password rather than after.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerKindSheet(
    selected: ServerKind,
    onPick: (ServerKind) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
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

/**
 * A kind's own logo, where we are allowed to ship it.
 *
 * Komga's is not: its repository is MIT, but the icon is, by their own
 * README, "based on an icon made by Freepik from flaticon.com", whose
 * licence is neither transferable nor sublicensable — so Komga cannot
 * pass it on to us and F-Droid, which requires every bundled asset to
 * be redistributable, would not take it. Drawing a lookalike would be
 * worse than either shipping theirs or shipping none, since it puts an
 * invented mark under their name. So Komga gets the neutral glyph, and
 * it is tinted rather than left in its own colours to read as a
 * placeholder rather than as a brand.
 *
 * If Komga ever relicenses the icon, this is a one-line change.
 */
@Composable
private fun ServerKindLogo(kind: ServerKind) {
    val size = Modifier.size(32.dp)
    when (kind) {
        ServerKind.KOMGA -> Icon(
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
    ServerKind.KOMGA -> R.drawable.ic_server_generic
    ServerKind.GRIMMORY -> R.drawable.ic_server_grimmory
    ServerKind.LISEUR_SYNC -> R.drawable.ic_server_liseur_sync
}

/** What a kind is for, and whether it keeps your place. */
@Composable
private fun KindSupport(kind: ServerKind) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(kind.taglineRes()))
        Text(
            stringResource(kind.syncAbility.lineRes()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun SyncAbility.lineRes(): Int = when (this) {
    SyncAbility.EXACT -> R.string.server_sync_exact
    SyncAbility.PROGRESSION -> R.string.server_sync_progression
    SyncAbility.NONE -> R.string.server_sync_none
}

internal fun ServerKind.labelRes(): Int = when (this) {
    ServerKind.CALIBRE -> R.string.server_kind_calibre
    ServerKind.KOMGA -> R.string.server_kind_komga
    ServerKind.GRIMMORY -> R.string.server_kind_grimmory
    ServerKind.LISEUR_SYNC -> R.string.server_kind_liseur_sync
}

internal fun ServerKind.taglineRes(): Int = when (this) {
    ServerKind.CALIBRE -> R.string.server_tagline_calibre
    ServerKind.KOMGA -> R.string.server_tagline_komga
    ServerKind.GRIMMORY -> R.string.server_tagline_grimmory
    ServerKind.LISEUR_SYNC -> R.string.server_tagline_liseur_sync
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
    ServerKind.GRIMMORY -> "https://github.com/grimmory-tools/grimmory"
    ServerKind.LISEUR_SYNC -> LISEUR_SYNC_SERVER_URL
}

internal fun ServerKind.linkRes(): Int = when (this) {
    ServerKind.CALIBRE -> R.string.server_link_calibre
    ServerKind.KOMGA -> R.string.server_link_komga
    ServerKind.GRIMMORY -> R.string.server_link_grimmory
    ServerKind.LISEUR_SYNC -> R.string.liseur_sync_get_one
}
