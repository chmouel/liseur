package com.chmouel.liseur.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.ui.reading.ReadingSectionLabel
import com.chmouel.liseur.ui.reading.ReadingSupportingText

/**
 * The app's own language, at the bottom of Reading appearance's Advanced
 * section: few readers want the app in another language than their phone,
 * so it sits out of the way, drawn like the footer dropdowns beside it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppLanguageControl(
    selected: AppLanguage,
    onSelected: (AppLanguage) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ReadingSectionLabel(stringResource(R.string.app_language))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = selected.label(),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                AppLanguage.entries.forEach { language ->
                    DropdownMenuItem(
                        text = { Text(language.label()) },
                        trailingIcon = {
                            if (language == selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            // System stays tappable: it is also what an
                            // unlisted language set elsewhere shows as.
                            if (language != selected || language == AppLanguage.SYSTEM) {
                                onSelected(language)
                            }
                        },
                    )
                }
            }
        }
        ReadingSupportingText(stringResource(R.string.app_language_detail))
    }
}

@Composable
private fun AppLanguage.label(): String =
    nativeName ?: stringResource(R.string.app_language_system)
