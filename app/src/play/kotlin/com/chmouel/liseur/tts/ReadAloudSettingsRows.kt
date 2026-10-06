package com.chmouel.liseur.tts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.ui.settings.RowDivider
import kotlinx.coroutines.launch

/**
 * Read aloud's rows in Reading navigation → Advanced: the key, the voice,
 * and what reading aloud sends where. Nothing here reaches the network;
 * the key is first used on the first play.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadAloudSettingsRows(feature: GeminiReadAloud) {
    val configured by feature.configured.collectAsState()
    val voice by feature.voice.collectAsState(initial = GeminiVoice.Default)
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    // Plain remember on purpose: a key half pasted must not outlive the
    // screen in saved instance state.
    var typed by remember { mutableStateOf("") }
    var voicesOpen by remember { mutableStateOf(false) }

    RowDivider()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            text = stringResource(R.string.read_aloud_settings_key),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(
                if (configured) R.string.read_aloud_settings_key_saved else R.string.read_aloud_settings_key_missing,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            singleLine = true,
            placeholder = {
                Text(
                    stringResource(
                        if (configured) R.string.read_aloud_settings_key_replace else R.string.read_aloud_settings_key_hint,
                    ),
                )
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    val key = typed.trim()
                    if (key.isNotEmpty()) {
                        typed = ""
                        focus.clearFocus()
                        scope.launch { feature.setKey(key) }
                    }
                },
            ),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.read_aloud_settings_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (configured) {
                TextButton(onClick = { scope.launch { feature.clearKey() } }) {
                    Text(stringResource(R.string.read_aloud_settings_key_clear))
                }
            }
        }
    }
    RowDivider()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            text = stringResource(R.string.read_aloud_settings_voice),
            style = MaterialTheme.typography.bodyLarge,
        )
        ExposedDropdownMenuBox(
            expanded = voicesOpen,
            onExpandedChange = { voicesOpen = it },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            OutlinedTextField(
                value = voiceLabel(voice),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = voicesOpen) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = voicesOpen, onDismissRequest = { voicesOpen = false }) {
                GeminiVoice.entries.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(voiceLabel(choice)) },
                        onClick = {
                            voicesOpen = false
                            scope.launch { feature.setVoice(choice) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun voiceLabel(voice: GeminiVoice): String =
    stringResource(R.string.read_aloud_settings_voice_choice, voice.id, stringResource(voice.style))
