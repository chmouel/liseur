package com.chmouel.liseur.reader.chrome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.reader.ReaderNarrateEntry
import com.chmouel.liseur.reader.ReaderTranslateEntry
import com.chmouel.liseur.translate.PageTranslationModes
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth
import java.util.Locale

private class MoreToolItem(
    val label: String,
    val subtitle: String?,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val trailing: (@Composable () -> Unit)? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderMoreSheet(
    scrollMode: Boolean,
    scrollModeEnabled: Boolean,
    onScrollModeChanged: (Boolean) -> Unit,
    effectiveScrolling: Boolean,
    autoScrollArmed: Boolean,
    onAutoScrollChanged: (Boolean) -> Unit,
    codexOrStoryAvailable: Boolean,
    onOpenCodex: () -> Unit,
    translateEntry: ReaderTranslateEntry?,
    uiLocale: Locale,
    onTranslateFromHere: (PageTranslationModes.Mode) -> Unit,
    onOpenTranslationSettings: () -> Unit,
    onStopTranslatingBook: () -> Unit,
    onOpenWholeBook: () -> Unit,
    listening: Boolean,
    hasReadAloudFromHere: Boolean,
    narrateEntry: ReaderNarrateEntry?,
    onReadAloudFromHere: () -> Unit,
    onStopReadAloud: () -> Unit,
    onOpenReadAloudSetup: () -> Unit,
    syncable: Boolean,
    onSyncBook: () -> Unit,
    rememberTrackerSync: (@Composable () -> (() -> Unit)?)?,
    onOpenBookInfo: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val eInk = LocalEInk.current
    val trackerSync = rememberTrackerSync?.invoke()
    val canSync = syncable || trackerSync != null
    val toolItems = buildList {
        if (codexOrStoryAvailable) {
            add(
                MoreToolItem(
                    label = stringResource(R.string.codex_title),
                    subtitle = stringResource(R.string.reader_menu_companion_detail),
                    icon = Icons.Outlined.Hub,
                    onClick = {
                        onDismiss()
                        onOpenCodex()
                    },
                ),
            )
        }
        translateEntry?.let { entry ->
            val label = when (entry) {
                is ReaderTranslateEntry.Into -> {
                    val target = entry.languages.target
                    stringResource(
                        R.string.reader_translate_into,
                        Locale.forLanguageTag(target).getDisplayName(uiLocale).ifBlank { target },
                    )
                }
                is ReaderTranslateEntry.SetUp -> stringResource(R.string.reader_menu_translate)
                ReaderTranslateEntry.Stop -> stringResource(R.string.translation_page_stop)
            }
            val subtitle = when (entry) {
                ReaderTranslateEntry.Stop -> null
                else -> stringResource(R.string.reader_menu_translate_detail)
            }
            val icon = if (entry == ReaderTranslateEntry.Stop) {
                Icons.Filled.Close
            } else {
                Icons.Outlined.Translate
            }
            add(
                MoreToolItem(
                    label = label,
                    subtitle = subtitle,
                    icon = icon,
                    onClick = {
                        onDismiss()
                        when (entry) {
                            is ReaderTranslateEntry.Into -> onTranslateFromHere(entry.languages)
                            is ReaderTranslateEntry.SetUp -> onOpenTranslationSettings()
                            ReaderTranslateEntry.Stop -> onStopTranslatingBook()
                        }
                    },
                    trailing = if (entry.wholeBook) {
                        {
                            FilledTonalButton(
                                onClick = {
                                    onDismiss()
                                    onOpenWholeBook()
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.defaultMinSize(minHeight = 36.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.reader_menu_whole_book),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    } else {
                        null
                    },
                ),
            )
        }
        if (listening) {
            if (hasReadAloudFromHere) {
                add(
                    MoreToolItem(
                        label = stringResource(R.string.read_aloud_from_this_page),
                        subtitle = stringResource(R.string.reader_menu_read_aloud_detail),
                        icon = Icons.AutoMirrored.Outlined.VolumeUp,
                        onClick = {
                            onDismiss()
                            onReadAloudFromHere()
                        },
                    ),
                )
            }
            add(
                MoreToolItem(
                    label = stringResource(R.string.read_aloud_stop),
                    subtitle = null,
                    icon = Icons.AutoMirrored.Outlined.VolumeOff,
                    onClick = {
                        onDismiss()
                        onStopReadAloud()
                    },
                ),
            )
        } else narrateEntry?.let { entry ->
            add(
                MoreToolItem(
                    label = stringResource(R.string.reader_menu_read_aloud),
                    subtitle = stringResource(R.string.reader_menu_read_aloud_detail),
                    icon = Icons.AutoMirrored.Outlined.VolumeUp,
                    onClick = {
                        onDismiss()
                        when (entry) {
                            ReaderNarrateEntry.Start -> onReadAloudFromHere()
                            ReaderNarrateEntry.SetUp -> onOpenReadAloudSetup()
                        }
                    },
                ),
            )
        }
    }

    LiseurModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .align(Alignment.CenterHorizontally)
                .widthIn(max = contentWidthCap(windowWidth()))
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MoreTogglePill(
                    label = stringResource(R.string.reader_menu_scroll_mode),
                    icon = Icons.Outlined.SwapVert,
                    checked = scrollMode,
                    enabled = scrollModeEnabled,
                    eInk = eInk,
                    onClick = { onScrollModeChanged(!scrollMode) },
                    modifier = Modifier.weight(1f),
                )
                if (effectiveScrolling) {
                    MoreTogglePill(
                        label = stringResource(R.string.reader_menu_auto_scroll),
                        icon = Icons.Outlined.PlayArrow,
                        checked = autoScrollArmed,
                        enabled = true,
                        eInk = eInk,
                        onClick = { onAutoScrollChanged(!autoScrollArmed) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (toolItems.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (eInk) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        toolItems.forEachIndexed { index, item ->
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(
                                        alpha = if (eInk) 1f else 0.45f,
                                    ),
                                )
                            }
                            MoreToolRow(
                                label = item.label,
                                subtitle = item.subtitle,
                                icon = item.icon,
                                eInk = eInk,
                                onClick = item.onClick,
                                trailing = item.trailing,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(
                    alpha = if (eInk) 1f else 0.45f,
                ),
            )

            if (canSync) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MoreUtilityButton(
                        label = stringResource(R.string.reader_menu_sync),
                        icon = Icons.Outlined.Sync,
                        accented = true,
                        eInk = eInk,
                        onClick = {
                            onDismiss()
                            if (syncable) onSyncBook()
                            trackerSync?.invoke()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    MoreUtilityButton(
                        label = stringResource(R.string.reader_menu_book_info),
                        icon = Icons.Outlined.Info,
                        accented = false,
                        eInk = eInk,
                        onClick = onOpenBookInfo,
                        modifier = Modifier.weight(1f),
                    )
                }

                MoreSettingsRow(
                    label = stringResource(R.string.reader_menu_reading_settings),
                    eInk = eInk,
                    onClick = onOpenSettings,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MoreUtilityButton(
                        label = stringResource(R.string.reader_menu_book_info),
                        icon = Icons.Outlined.Info,
                        accented = false,
                        eInk = eInk,
                        onClick = onOpenBookInfo,
                        modifier = Modifier.weight(1f),
                    )
                    MoreUtilityButton(
                        label = stringResource(R.string.reader_menu_reading_settings),
                        icon = Icons.Outlined.Tune,
                        accented = false,
                        eInk = eInk,
                        onClick = onOpenSettings,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MoreTogglePill(
    label: String,
    icon: ImageVector,
    checked: Boolean,
    enabled: Boolean,
    eInk: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = when {
        eInk -> MaterialTheme.colorScheme.surface
        checked -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }
    val contentColor = when {
        eInk -> MaterialTheme.colorScheme.onSurface
        checked -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val border = when {
        eInk && checked -> BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
        eInk -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        checked -> null
        else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }

    Surface(
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        border = border,
        modifier = modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            }
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            Icon(
                imageVector = if (checked) Icons.Filled.Check else icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MoreToolRow(
    label: String,
    subtitle: String?,
    icon: ImageVector,
    eInk: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (eInk) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHighest,
            border = if (eInk) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun MoreUtilityButton(
    label: String,
    icon: ImageVector,
    accented: Boolean,
    eInk: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = when {
        eInk -> MaterialTheme.colorScheme.onSurface
        accented -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val borderColor = when {
        eInk -> MaterialTheme.colorScheme.outline
        accented -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (eInk) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MoreSettingsRow(
    label: String,
    eInk: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (eInk) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            1.dp,
            if (eInk) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
