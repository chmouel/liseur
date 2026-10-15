package com.chmouel.liseur.companion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
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
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth
import org.readium.r2.shared.publication.Locator

/**
 * The character panel, in the reading page's colours. Claims are grouped
 * into distinct cards with compact chapter pills that expand the book's
 * own quotes on demand; tapping a quote shows it in the book and hides
 * the panel until the reader comes back.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CompanionSheet(
    panel: CompanionPanel<Locator>,
    onSource: (Passage<Locator>) -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onChoose: (Candidate<Locator>) -> Unit,
    onSetUp: () -> Unit,
    onChooseModel: () -> Unit,
    onDismiss: () -> Unit,
) {
    LiseurModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        CompositionLocalProvider(LocalChapterTitles provides panel.titles) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = contentWidthCap(windowWidth()))
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val view = panel.view
                val profile = (view as? CompanionView.Profile)?.profile
                val title = profile?.name ?: panel.name ?: panel.selection
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    panel.also?.let {
                        Text(
                            stringResource(R.string.companion_also_from, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        stringResource(R.string.companion_scope),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (profile != null && profile.aliases.isNotEmpty()) {
                        AliasPills(profile.aliases, onSource)
                    }
                }
                Column(
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (view) {
                        CompanionView.Setup -> Message(R.string.companion_setup) {
                            Button(onClick = onSetUp) { Text(stringResource(R.string.companion_setup_action)) }
                        }
                        CompanionView.FirstChapter -> Message(R.string.companion_first_chapter)
                        CompanionView.Unplaced -> Message(R.string.companion_unplaced)
                        CompanionView.NotAName -> Message(R.string.companion_not_a_name)
                        CompanionView.Insufficient -> Message(
                            stringResource(R.string.companion_insufficient, panel.name ?: panel.selection),
                        ) { Retry(onRetry) }
                        is CompanionView.Loading -> Loading(view.stage, onCancel)
                        is CompanionView.Failed -> FailureMessage(view, onRetry, onChooseModel)
                        is CompanionView.Ambiguous -> Ambiguous(view.candidates, onChoose)
                        is CompanionView.Profile -> Profile(view.profile, onSource)
                    }
                }
            }
        }
    }
}

/** Shown over the book while a source is on screen, with the two ways back. */
@Composable
internal fun CompanionPeekBar(
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
                    stringResource(R.string.companion_peek_return),
                    color = theme.background,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            TextButton(onClick = onShow, enabled = !returning) {
                Icon(Icons.Outlined.PersonSearch, contentDescription = null, tint = theme.background)
                Text(
                    stringResource(R.string.companion_peek_show),
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
    OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.companion_retry)) }
}

@Composable
private fun Loading(stage: CompanionView.Stage, onCancel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        BusyIndicator(Modifier.size(24.dp))
        Text(
            stringResource(
                when (stage) {
                    CompanionView.Stage.READING -> R.string.companion_loading_reading
                    CompanionView.Stage.ASKING -> R.string.companion_loading_asking
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
    }
    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
}

@Composable
private fun Ambiguous(candidates: List<Candidate<Locator>>, onChoose: (Candidate<Locator>) -> Unit) {
    Text(stringResource(R.string.companion_ambiguous), style = MaterialTheme.typography.bodyLarge)
    SectionCard {
        candidates.forEachIndexed { index, candidate ->
            if (index > 0) CardDivider()
            ListItem(
                modifier = Modifier.clickable(role = Role.Button) { onChoose(candidate) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text(candidate.name, fontWeight = FontWeight.Medium) },
                supportingContent = candidate.description.takeIf { it.isNotBlank() }?.let { { Text(it) } },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AliasPills(aliases: List<Alias<Locator>>, onSource: (Passage<Locator>) -> Unit) {
    val eInk = LocalEInk.current
    var selectedAlias by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.companion_aliases),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(end = 2.dp),
            )
            aliases.forEach { alias ->
                val active = selectedAlias == alias.name
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (active) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (eInk) 1f else 0.55f)
                    },
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    border = if (eInk || active) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (eInk) 1f else 0.5f))
                    } else {
                        null
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(role = Role.Button) {
                            selectedAlias = if (active) null else alias.name
                        },
                ) {
                    Text(
                        text = alias.name,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
        val activeAlias = aliases.firstOrNull { it.name == selectedAlias }
        if (activeAlias != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                activeAlias.sources.distinctBy { it.ref }.forEach { Quote(it, onSource) }
            }
        }
    }
}

@Composable
private fun Profile(profile: CompanionAnswer.Profile<Locator>, onSource: (Passage<Locator>) -> Unit) {
    if (profile.identity.isNotEmpty()) {
        SectionCard(title = R.string.companion_identity) {
            profile.identity.forEachIndexed { index, claim ->
                if (index > 0) CardDivider()
                ClaimItem(key = "id-$index", text = claim.text, sources = claim.sources, onSource = onSource)
            }
        }
    }
    if (profile.backstory.isNotEmpty()) {
        SectionCard(title = R.string.companion_backstory) {
            profile.backstory.forEachIndexed { index, claim ->
                if (index > 0) CardDivider()
                ClaimItem(key = "back-$index", text = claim.text, sources = claim.sources, onSource = onSource)
            }
        }
    }
    if (profile.relationships.isNotEmpty()) {
        SectionCard(title = R.string.companion_relationships) {
            profile.relationships.forEachIndexed { index, relation ->
                if (index > 0) CardDivider()
                ClaimItem(
                    key = "rel-$index",
                    heading = relation.name,
                    text = relation.text,
                    sources = relation.sources,
                    onSource = onSource,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: Int? = null, content: @Composable () -> Unit) {
    val eInk = LocalEInk.current
    val border = if (eInk) {
        MaterialTheme.colorScheme.outline
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (title != null) {
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            content()
        }
    }
}

@Composable
private fun CardDivider() {
    val eInk = LocalEInk.current
    HorizontalDivider(
        color = if (eInk) {
            MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        },
    )
}

@Composable
private fun ClaimItem(
    key: String,
    text: String,
    sources: List<Passage<Locator>>,
    onSource: (Passage<Locator>) -> Unit,
    heading: String? = null,
) {
    val distinct = sources.distinctBy { it.ref }
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    val eInk = LocalEInk.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (heading != null) {
            Text(
                text = heading,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (distinct.isNotEmpty()) {
            SourceToggleChip(
                passages = distinct,
                expanded = expanded,
                onClick = { expanded = !expanded },
            )
            if (eInk) {
                if (expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        distinct.forEach { Quote(it, onSource) }
                    }
                }
            } else {
                AnimatedVisibility(visible = expanded) {
                    Column(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        distinct.forEach { Quote(it, onSource) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceToggleChip(
    passages: List<Passage<Locator>>,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val eInk = LocalEInk.current
    val summary = chapterSummary(passages)
    val countLabel = pluralStringResource(R.plurals.companion_sources_count, passages.size, passages.size)
    val label = if (passages.size == 1) summary else "$countLabel · $summary"
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (expanded) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (eInk) 1f else 0.7f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = if (eInk) 1f else 0.85f)
        },
        contentColor = MaterialTheme.colorScheme.primary,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (eInk) 1f else 0.6f),
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** The open book's chapter titles, by chapter number, where its contents give one. */
private val LocalChapterTitles = staticCompositionLocalOf<List<String?>> { emptyList() }

@Composable
private fun chapterSummary(passages: List<Passage<Locator>>): String {
    val ranges = passages.map { it.range }.distinct().sortedWith(compareBy({ it.lo }, { it.hi }))
    return ranges.map { chapterLabel(it) }.joinToString(", ")
}

/** The contents' own titles for [range], or chapter numbers where it has none. */
@Composable
private fun chapterLabel(range: ChapterRange): String {
    val titles = LocalChapterTitles.current
    val first = titles.getOrNull(range.lo)
    val last = titles.getOrNull(range.hi)
    return when {
        range.lo == range.hi && first != null -> first
        range.lo != range.hi && first != null && last != null -> "$first – $last"
        range.lo == range.hi -> stringResource(R.string.companion_source_chapter, range.lo + 1)
        else -> stringResource(R.string.companion_source_chapters, range.lo + 1, range.hi + 1)
    }
}

/** A passage the claim rests on, in the book's own words, opening it in the book. */
@Composable
private fun Quote(passage: Passage<Locator>, onSource: (Passage<Locator>) -> Unit) {
    val where = chapterLabel(passage.range)
    val rule = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(6.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.companion_source_open),
            ) { onSource(passage) }
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(rule),
        )
        Column(
            modifier = Modifier.padding(start = 12.dp).weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                passage.text,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    where,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

