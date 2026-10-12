package com.chmouel.liseur.translate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ServerTranslationState
import com.chmouel.liseur.tts.TypedField
import com.chmouel.liseur.tts.onLeaving
import com.chmouel.liseur.ui.settings.RowDivider
import com.chmouel.liseur.ui.settings.SwitchRow

/**
 * How a server is asked to translate, under its Advanced section: each
 * option starts at the default and [onChange] saves one at a time.
 * [translateGemma] greys out the sentence before, which that model never
 * gets.
 */
@Composable
internal fun ServerTranslationOptions(
    state: ServerTranslationState,
    translateGemma: Boolean,
    onChange: ((ServerTranslationState) -> ServerTranslationState) -> Unit,
) {
    TemperatureRow(state.temperature ?: 0.0, state.noTemperature) { value -> onChange { it.copy(temperature = value) } }
    RowDivider()
    SwitchRow(
        title = stringResource(R.string.translation_options_context),
        subtitle = stringResource(
            if (translateGemma) R.string.translation_options_context_gemma else R.string.translation_options_context_detail,
        ),
        checked = !translateGemma && state.context != false,
        onCheckedChange = { on -> onChange { it.copy(context = if (on) null else false) } },
        enabled = !translateGemma,
    )
    RowDivider()
    OptionField(
        title = stringResource(R.string.translation_options_instructions),
        detail = stringResource(R.string.translation_options_instructions_detail),
        placeholder = stringResource(R.string.translation_options_instructions_hint),
        stored = state.instructions.orEmpty(),
        singleLine = false,
        maxLength = ServerTranslationState.MAX_INSTRUCTIONS,
        valid = { it.length <= ServerTranslationState.MAX_INSTRUCTIONS },
        onSave = { text -> onChange { it.copy(instructions = text) } },
    )
    RowDivider()
    OptionField(
        title = stringResource(R.string.translation_options_max_tokens),
        detail = stringResource(R.string.translation_options_max_tokens_detail),
        placeholder = stringResource(R.string.translation_options_server_default),
        stored = state.maxTokens?.toString().orEmpty(),
        keyboardType = KeyboardType.Number,
        valid = { it.toIntOrNull() in ServerTranslationState.MAX_TOKENS },
        onSave = { text -> onChange { it.copy(maxTokens = text?.toIntOrNull()) } },
    )
    RowDivider()
    OptionField(
        title = stringResource(R.string.translation_options_keep_alive),
        detail = stringResource(R.string.translation_options_keep_alive_detail),
        placeholder = stringResource(R.string.translation_options_server_default),
        stored = state.keepAlive.orEmpty(),
        keyboardType = KeyboardType.Ascii,
        valid = ServerTranslationState::validKeepAlive,
        error = stringResource(R.string.translation_options_keep_alive_invalid),
        onSave = { text -> onChange { it.copy(keepAlive = text) } },
    )
    if (!state.untuned) {
        RowDivider()
        TextButton(
            onClick = { onChange { ServerTranslationState(model = it.model, noTemperature = it.noTemperature) } },
            modifier = Modifier.padding(horizontal = 4.dp),
        ) { Text(stringResource(R.string.translation_options_reset)) }
    }
}

@Composable
private fun TemperatureRow(value: Double, refused: Boolean, onChanged: (Double) -> Unit) {
    var sliding by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val locale = LocalConfiguration.current.locales[0]
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = stringResource(R.string.translation_options_temperature),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = String.format(locale, "%.1f", ServerTranslationState.temperature(sliding.toDouble())),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Text(
            text = stringResource(
                if (refused) R.string.translation_options_temperature_refused else R.string.translation_options_temperature_detail,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val range = ServerTranslationState.TEMPERATURES
        Slider(
            value = sliding,
            onValueChange = { sliding = it },
            onValueChangeFinished = { onChanged(ServerTranslationState.temperature(sliding.toDouble())) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            // Tenths from 0 to 2: 21 stops, 19 between the ends.
            steps = 19,
        )
    }
}

/**
 * A field saved when the reader leaves it, presses Done, or leaves the
 * screen. Blank saves null, the default; a value [valid] refuses shows
 * [error] while typed and goes back to the saved one when left. Typing
 * stops at [maxLength].
 */
@Composable
private fun OptionField(
    title: String,
    detail: String,
    placeholder: String,
    stored: String,
    valid: (String) -> Boolean,
    onSave: (String?) -> Unit,
    singleLine: Boolean = true,
    maxLength: Int = Int.MAX_VALUE,
    keyboardType: KeyboardType = KeyboardType.Text,
    error: String? = null,
) {
    val focus = LocalFocusManager.current
    var typed by remember { mutableStateOf(stored) }
    var synced by remember { mutableStateOf(stored) }
    LaunchedEffect(stored) {
        if (TypedField.follows(typed, synced, stored)) {
            typed = stored
            synced = stored
        }
    }
    // Done and leaving the field follow one another: one value is saved once.
    var saved by remember(stored) { mutableStateOf<String?>(null) }
    val commit = {
        val text = typed.trim()
        when {
            text == stored || text == saved -> Unit
            text.isEmpty() -> {
                saved = text
                onSave(null)
            }
            valid(text) -> {
                saved = text
                onSave(text)
            }
            else -> typed = stored
        }
    }
    val latestCommit by rememberUpdatedState(commit)
    DisposableEffect(Unit) { onDispose { latestCommit() } }
    val wrong = typed.isNotBlank() && !valid(typed.trim())

    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it.take(maxLength) },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 2,
            maxLines = if (singleLine) 1 else 6,
            placeholder = { Text(placeholder) },
            isError = wrong,
            supportingText = error?.takeIf { wrong }?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                autoCorrectEnabled = !singleLine,
                imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    commit()
                    focus.clearFocus()
                },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .onLeaving { commit() },
        )
    }
}
