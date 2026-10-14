package com.chmouel.liseur.characters

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.reader.chrome.ChromePill
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth
import org.readium.r2.shared.publication.Locator

/**
 * The character panel, in the reading page's colours. Every quote under a
 * claim is the book's own text, held on the phone; tapping one shows it
 * in the book and hides the panel until the reader comes back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CharacterSheet(
    panel: CharacterPanel<Locator>,
    onSource: (Passage<Locator>) -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onChoose: (Candidate<Locator>) -> Unit,
    onSetUp: () -> Unit,
    onDismiss: () -> Unit,
) {
    LiseurModalBottomSheet(
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
            val view = panel.view
            val title = (view as? CharacterView.Profile)?.profile?.name ?: panel.name ?: panel.selection
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                panel.also?.let {
                    Text(
                        stringResource(R.string.character_also_from, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.character_scope),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (view) {
                    CharacterView.Setup -> Message(R.string.character_setup) {
                        Button(onClick = onSetUp) { Text(stringResource(R.string.character_setup_action)) }
                    }
                    CharacterView.FirstChapter -> Message(R.string.character_first_chapter)
                    CharacterView.Unplaced -> Message(R.string.character_unplaced)
                    CharacterView.NotAName -> Message(R.string.character_not_a_name)
                    CharacterView.Insufficient -> Message(
                        stringResource(R.string.character_insufficient, panel.name ?: panel.selection),
                    ) { Retry(onRetry) }
                    is CharacterView.Loading -> Loading(view.stage, onCancel)
                    is CharacterView.Failed -> Message(failureText(view.failure)) { Retry(onRetry) }
                    is CharacterView.Ambiguous -> Ambiguous(view.candidates, onChoose)
                    is CharacterView.Profile -> Profile(view.profile, onSource)
                }
            }
        }
    }
}

/** Shown over the book while a source is on screen, with the two ways back. */
@Composable
internal fun CharacterPeekBar(
    theme: ReaderTheme,
    returning: Boolean,
    onReturn: () -> Unit,
    onShow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ChromePill(theme = theme, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            TextButton(onClick = onReturn, enabled = !returning) {
                Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null, tint = theme.background)
                Text(
                    stringResource(R.string.character_peek_return),
                    color = theme.background,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            TextButton(onClick = onShow, enabled = !returning) {
                Icon(Icons.Outlined.PersonSearch, contentDescription = null, tint = theme.background)
                Text(
                    stringResource(R.string.character_peek_show),
                    color = theme.background,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Message(text: Int, action: @Composable () -> Unit = {}) = Message(stringResource(text), action)

@Composable
private fun Message(text: String, action: @Composable () -> Unit = {}) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
    action()
}

@Composable
private fun Retry(onRetry: () -> Unit) {
    OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.character_retry)) }
}

@Composable
private fun Loading(stage: CharacterView.Stage, onCancel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        BusyIndicator(Modifier.size(24.dp))
        Text(
            stringResource(
                when (stage) {
                    CharacterView.Stage.READING -> R.string.character_loading_reading
                    CharacterView.Stage.ASKING -> R.string.character_loading_asking
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
    }
    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
}

@Composable
private fun failureText(failure: CharacterFailure): String = stringResource(
    when (failure) {
        CharacterFailure.CANCELLED -> R.string.character_failed_cancelled
        CharacterFailure.KEY -> R.string.character_failed_key
        CharacterFailure.RATE_LIMITED -> R.string.character_failed_rate_limited
        CharacterFailure.LOCAL_NETWORK -> R.string.character_failed_local_network
        CharacterFailure.NETWORK -> R.string.character_failed_network
        CharacterFailure.MODEL_MISSING -> R.string.character_failed_model_missing
        CharacterFailure.SERVER -> R.string.character_failed_server
        CharacterFailure.MALFORMED -> R.string.character_failed_malformed
        CharacterFailure.LIMIT -> R.string.character_failed_limit
        CharacterFailure.UNREADABLE -> R.string.character_failed_unreadable
    },
)

@Composable
private fun Ambiguous(candidates: List<Candidate<Locator>>, onChoose: (Candidate<Locator>) -> Unit) {
    Text(stringResource(R.string.character_ambiguous), style = MaterialTheme.typography.bodyLarge)
    Column {
        candidates.forEach { candidate ->
            ListItem(
                modifier = Modifier.clickable(role = Role.Button) { onChoose(candidate) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text(candidate.name) },
                supportingContent = candidate.description.takeIf { it.isNotBlank() }?.let { { Text(it) } },
            )
        }
    }
}

@Composable
private fun Profile(profile: CharacterAnswer.Profile<Locator>, onSource: (Passage<Locator>) -> Unit) {
    if (profile.aliases.isNotEmpty()) {
        Section(R.string.character_aliases)
        Text(profile.aliases.joinToString { it.name }, style = MaterialTheme.typography.bodyLarge)
    }
    if (profile.identity.isNotEmpty()) {
        Section(R.string.character_identity)
        profile.identity.forEach { Claim(it.text, it.sources, onSource) }
    }
    if (profile.backstory.isNotEmpty()) {
        Section(R.string.character_backstory)
        profile.backstory.forEach { Claim(it.text, it.sources, onSource) }
    }
    if (profile.relationships.isNotEmpty()) {
        Section(R.string.character_relationships)
        profile.relationships.forEach { relation ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(relation.name, style = MaterialTheme.typography.titleSmall)
                Claim(relation.text, relation.sources, onSource)
            }
        }
    }
}

@Composable
private fun Section(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun Claim(text: String, sources: List<Passage<Locator>>, onSource: (Passage<Locator>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        sources.distinctBy { it.ref }.forEach { Quote(it, onSource) }
    }
}

/** A passage the claim rests on, in the book's own words, opening it in the book. */
@Composable
private fun Quote(passage: Passage<Locator>, onSource: (Passage<Locator>) -> Unit) {
    val where = passage.range.let {
        if (it.lo == it.hi) {
            stringResource(R.string.character_source_chapter, it.lo + 1)
        } else {
            stringResource(R.string.character_source_chapters, it.lo + 1, it.hi + 1)
        }
    }
    val rule = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.character_source_open),
            ) { onSource(passage) }
            .padding(vertical = 4.dp),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(rule),
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                passage.text,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                where,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
