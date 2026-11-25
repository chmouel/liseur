package com.chmouel.liseur.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.SyncAbility
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import com.chmouel.liseur.ui.LocalEInk

/** Explicit connection status tone for Book server & Google Drive status pills. */
internal enum class ConnectionStatusTone {
    ACTIVE,
    SYNCING,
    PAUSED,
    ERROR,
}

/**
 * Compact status pill used on both Google Drive and RemoteServer cards.
 */
@Composable
internal fun ConnectionStatusBadge(
    label: String,
    tone: ConnectionStatusTone,
    modifier: Modifier = Modifier,
) {
    val eInk = LocalEInk.current
    val scheme = MaterialTheme.colorScheme
    val (container, content, border) = when {
        eInk -> Triple(
            scheme.surfaceContainerHigh,
            scheme.onSurface,
            scheme.outline,
        )
        tone == ConnectionStatusTone.ACTIVE -> Triple(
            scheme.tertiaryContainer.copy(alpha = 0.55f),
            scheme.tertiary,
            scheme.tertiary.copy(alpha = 0.35f),
        )
        tone == ConnectionStatusTone.SYNCING -> Triple(
            scheme.secondaryContainer.copy(alpha = 0.7f),
            scheme.onSecondaryContainer,
            scheme.secondary.copy(alpha = 0.35f),
        )
        tone == ConnectionStatusTone.PAUSED -> Triple(
            scheme.surfaceContainerHighest,
            scheme.onSurfaceVariant,
            scheme.outlineVariant,
        )
        else -> Triple(
            scheme.errorContainer.copy(alpha = 0.65f),
            scheme.error,
            scheme.error.copy(alpha = 0.4f),
        )
    }

    Surface(
        shape = CircleShape,
        color = container,
        contentColor = content,
        border = BorderStroke(1.dp, border),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            when (tone) {
                ConnectionStatusTone.SYNCING -> {
                    if (eInk) {
                        Icon(
                            imageVector = Icons.Outlined.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                        )
                    } else {
                        BusyIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.75.dp,
                            color = content,
                        )
                    }
                }
                ConnectionStatusTone.ACTIVE -> Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                )
                ConnectionStatusTone.PAUSED -> Icon(
                    imageVector = Icons.Outlined.PauseCircleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                )
                ConnectionStatusTone.ERROR -> Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

/**
 * Choosing which kind of server the library lives on.
 *
 * A choice most readers make once, so it is a single row that says what
 * is currently picked and opens a sheet, rather than a control laid out
 * across the top of the form.
 */
@Composable
internal fun ServerKindRow(
    kind: ServerKind,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showSync: Boolean = true,
) {
    ServerProviderSelectorCard(
        title = stringResource(kind.labelRes()),
        description = stringResource(kind.taglineRes()),
        syncLine = if (showSync) stringResource(kind.syncLineRes()) else null,
        syncIcon = if (showSync) kind.syncIcon() else null,
        leadingIcon = { ServerKindLogo(kind, Modifier.size(28.dp)) },
        enabled = enabled,
        onClick = onClick,
        modifier = modifier,
    )
}

/**
 * Shared top provider selector card used by both [ServerKindRow] and Play's
 * Google Drive selector row so the two never drift visually.
 */
@Composable
internal fun ServerProviderSelectorCard(
    title: String,
    description: String,
    leadingIcon: @Composable () -> Unit,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    syncLine: String? = null,
    syncIcon: ImageVector? = null,
    showSelectedBadge: Boolean = true,
) {
    val eInk = LocalEInk.current
    val scheme = MaterialTheme.colorScheme
    val cardShape = RoundedCornerShape(18.dp)
    OutlinedCard(
        onClick = onClick,
        enabled = enabled,
        shape = cardShape,
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (eInk) scheme.surface else scheme.surfaceContainerLow,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (eInk) scheme.outline else scheme.outlineVariant.copy(alpha = 0.7f),
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            ServerLogoTile(leadingIcon)
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.server_kind),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    if (showSelectedBadge) {
                        SelectedProviderPill()
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 20.sp,
                        lineHeight = 25.sp,
                    ),
                    color = scheme.onSurface,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                if (syncLine != null) {
                    ProviderSyncLine(text = syncLine, icon = syncIcon ?: Icons.Outlined.Sync)
                }
            }
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = stringResource(R.string.server_kind_change),
                tint = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SelectedProviderPill(modifier: Modifier = Modifier) {
    val eInk = LocalEInk.current
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (eInk) scheme.surfaceContainerHigh else scheme.secondaryContainer.copy(alpha = 0.75f),
        contentColor = if (eInk) scheme.onSurface else scheme.onSecondaryContainer,
        border = if (eInk) BorderStroke(1.dp, scheme.outline) else null,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.server_selected_badge),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
internal fun ServerLogoTile(
    content: @Composable () -> Unit,
) {
    val eInk = LocalEInk.current
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (eInk) scheme.surfaceContainerLow else scheme.surfaceContainerHighest.copy(alpha = 0.75f),
        border = BorderStroke(
            width = 1.dp,
            color = if (eInk) scheme.outlineVariant else scheme.outlineVariant.copy(alpha = 0.45f),
        ),
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@Composable
internal fun ProviderSyncLine(
    text: String,
    icon: ImageVector = Icons.Outlined.Sync,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.padding(top = 2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = scheme.onSurfaceVariant.copy(alpha = 0.85f),
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant.copy(alpha = 0.85f),
        )
    }
}

/**
 * Shared option row inside [ServerKindSheet] for both [ServerKind] entries
 * and Play's Google Drive option.
 */
@Composable
internal fun ServerProviderPickerOptionRow(
    title: String,
    description: String,
    selected: Boolean,
    onPick: () -> Unit,
    leadingIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    syncLine: String? = null,
    syncIcon: ImageVector? = null,
) {
    val eInk = LocalEInk.current
    val scheme = MaterialTheme.colorScheme
    val rowShape = RoundedCornerShape(16.dp)
    val selectedBackground = when {
        !selected -> Color.Transparent
        eInk -> scheme.surfaceContainerHigh
        else -> scheme.secondaryContainer.copy(alpha = 0.32f)
    }
    val selectedBorder = when {
        !selected -> null
        eInk -> BorderStroke(1.5.dp, scheme.outline)
        else -> BorderStroke(1.dp, scheme.secondary.copy(alpha = 0.45f))
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(rowShape)
            .background(selectedBackground, rowShape)
            .let { base ->
                if (selectedBorder != null) {
                    base.border(selectedBorder, rowShape)
                } else {
                    base
                }
            }
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onPick,
            )
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        ServerLogoTile(leadingIcon)
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 18.sp,
                    lineHeight = 23.sp,
                ),
                color = scheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            if (syncLine != null) {
                ProviderSyncLine(text = syncLine, icon = syncIcon ?: Icons.Outlined.Sync)
            }
        }
        // Null handler so TalkBack sees a single selectable row target.
        RadioButton(selected = selected, onClick = null)
    }
}

/**
 * Every kind at once: the tagline says what the server is for, and the
 * line under it says whether your place in a book will follow you there.
 *
 * Opens expanded rather than half-way so all providers are visible without
 * hiding the last options below a half-expanded fold (issue #179).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerKindSheet(
    selected: ServerKind,
    onPick: (ServerKind) -> Unit,
    onDismiss: () -> Unit,
    showSync: Boolean = true,
    extraSelected: Boolean = false,
    extraFirstOption: (@Composable (selected: Boolean, onPick: () -> Unit) -> Unit)? = null,
    onPickExtra: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    LiseurModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
                .selectableGroup(),
        ) {
            Text(
                stringResource(R.string.server_kind),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                ),
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
            )
            val entries = ServerKind.entries
            if (extraFirstOption != null && onPickExtra != null) {
                extraFirstOption(extraSelected, onPickExtra)
                val firstEntrySelected = !extraSelected && entries.firstOrNull() == selected
                if (!extraSelected && !firstEntrySelected) {
                    SheetOptionDivider()
                }
            }
            entries.forEachIndexed { index, kind ->
                val isSelected = !extraSelected && kind == selected
                ServerProviderPickerOptionRow(
                    title = stringResource(kind.labelRes()),
                    description = stringResource(kind.taglineRes()),
                    selected = isSelected,
                    onPick = { onPick(kind) },
                    leadingIcon = { ServerKindLogo(kind, Modifier.size(28.dp)) },
                    syncLine = if (showSync) stringResource(kind.syncLineRes()) else null,
                    syncIcon = if (showSync) kind.syncIcon() else null,
                )
                val nextKind = entries.getOrNull(index + 1)
                val nextSelected = nextKind != null && !extraSelected && nextKind == selected
                if (nextKind != null && !isSelected && !nextSelected) {
                    SheetOptionDivider()
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun SheetOptionDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        modifier = Modifier.padding(horizontal = 26.dp),
    )
}

/** Redistributable logos or original provider glyphs, without copying restricted artwork. */
@Composable
internal fun ServerKindLogo(kind: ServerKind, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(kind.logoRes()),
        contentDescription = null,
        modifier = modifier.size(32.dp),
    )
}

private fun ServerKind.logoRes(): Int = when (this) {
    ServerKind.CALIBRE -> R.drawable.ic_server_calibre_web
    ServerKind.KOMGA -> R.drawable.ic_server_komga
    ServerKind.LISEUR_SYNC -> R.drawable.ic_server_liseur_sync
    ServerKind.BOOKORBIT -> R.drawable.ic_server_bookorbit
    ServerKind.CUSTOM -> R.drawable.ic_server_custom
}

private fun ServerKind.syncIcon(): ImageVector = when (this) {
    ServerKind.CUSTOM -> Icons.Outlined.Sync
    else -> when (syncAbility) {
        SyncAbility.EXACT -> Icons.Outlined.Sync
        SyncAbility.PROGRESSION -> Icons.Outlined.Timeline
        SyncAbility.NONE -> Icons.Outlined.Sync
    }
}

/**
 * What the picker says about keeping your place.
 */
internal fun ServerKind.syncLineRes(): Int = when (this) {
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
