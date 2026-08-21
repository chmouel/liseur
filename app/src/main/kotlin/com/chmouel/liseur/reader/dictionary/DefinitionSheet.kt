package com.chmouel.liseur.reader.dictionary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.chmouel.liseur.ui.BusyIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.domain.DictionaryUrl
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth

/**
 * The Kindle-style definition card: the word, its senses, and a way out to a
 * real dictionary app or to the full entry when the short answer is not
 * enough.
 *
 * Nothing is fetched until [enabled] is true. Until then the card explains
 * which site would be asked and offers to turn the lookup on, so the first
 * word anyone defines is also the moment they decide whether the app may
 * talk to a dictionary at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DefinitionSheet(
    word: String,
    languages: List<String>,
    enabled: Boolean,
    baseUrl: String,
    onEnable: () -> Unit,
    onOpenInDictionaryApp: (String) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onDismiss: () -> Unit,
    client: WiktionaryClient = remember { WiktionaryClient() },
) {
    val term = remember(word) { normaliseLookupTerm(word) }
    var state by remember(term, enabled, baseUrl) {
        mutableStateOf<DictionaryState>(
            if (enabled) DictionaryState.Loading else DictionaryState.Disabled,
        )
    }

    LaunchedEffect(term, languages, enabled, baseUrl) {
        if (!enabled) return@LaunchedEffect
        state = client.define(term, languages, baseUrl)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = contentWidthCap(windowWidth()))
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = term,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )

            when (val current = state) {
                DictionaryState.Disabled -> Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.dictionary_disabled_explanation,
                            DictionaryUrl.hostOf(baseUrl),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onEnable) {
                        Text(stringResource(R.string.dictionary_enable))
                    }
                }

                DictionaryState.Loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BusyIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.dictionary_loading),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                is DictionaryState.Found -> current.senses.forEach { sense ->
                    Sense(sense)
                }

                DictionaryState.NotFound -> Text(
                    text = stringResource(R.string.dictionary_not_found, term),
                    style = MaterialTheme.typography.bodyMedium,
                )

                is DictionaryState.Failed -> Text(
                    text = when {
                        current.host != null && current.message != null ->
                            stringResource(
                                R.string.dictionary_failed_host,
                                current.host,
                                current.message,
                            )
                        current.message != null ->
                            stringResource(R.string.dictionary_failed_reason, current.message)
                        else -> stringResource(R.string.dictionary_failed)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onOpenInDictionaryApp(term) }) {
                    Text(stringResource(R.string.dictionary_other_app))
                }
                TextButton(onClick = { onOpenInBrowser(term) }) {
                    Text(stringResource(R.string.dictionary_open_full_entry))
                }
            }
        }
    }
}

@Composable
private fun Sense(sense: DictionarySense) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (sense.partOfSpeech.isNotBlank()) {
            Text(
                text = sense.partOfSpeech,
                style = MaterialTheme.typography.labelLarge,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        sense.definitions.take(MAX_DEFINITIONS).forEachIndexed { index, definition ->
            Text(
                text = "${index + 1}. $definition",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Wiktionary lists dozens of senses for common words; a card is not a page. */
private const val MAX_DEFINITIONS = 4
