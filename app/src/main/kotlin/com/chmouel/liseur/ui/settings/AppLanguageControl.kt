package com.chmouel.liseur.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.ui.LiseurModalBottomSheet

/**
 * The app's own language, in Settings > Appearance: shows the language
 * in force and opens a bottom sheet to choose another.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppLanguageRow(
    selected: AppLanguage,
    onSelected: (AppLanguage) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.app_language)) },
        supportingContent = { Text(selected.label()) },
        leadingContent = {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Language, contentDescription = null)
            }
        },
        trailingContent = {
            Icon(Icons.Outlined.UnfoldMore, contentDescription = null)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(role = Role.Button, onClick = { open = true }),
    )
    if (open) {
        LiseurModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .selectableGroup(),
            ) {
                Text(
                    text = stringResource(R.string.app_language),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
                )
                Text(
                    text = stringResource(R.string.app_language_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                )
                AppLanguage.entries.forEach { language ->
                    val chosen = language == selected
                    ListItem(
                        modifier = Modifier.selectable(
                            selected = chosen,
                            role = Role.RadioButton,
                            onClick = {
                                open = false
                                // System stays tappable: it is also what an
                                // unlisted language set elsewhere shows as.
                                if (!chosen || language == AppLanguage.SYSTEM) {
                                    onSelected(language)
                                }
                            },
                        ),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(language.label()) },
                        trailingContent = {
                            RadioButton(selected = chosen, onClick = null)
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun AppLanguage.label(): String =
    nativeName ?: stringResource(R.string.app_language_system)
