package com.chmouel.liseur.translate

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.chmouel.liseur.R
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth
import java.util.Locale
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A language list the sheet shows in place of the translation. */
private enum class Picking { Source, Target }

/** What came back for one request. */
private sealed interface Outcome {
    data class Done(val text: String) : Outcome
    data class Failed(val error: TranslationError) : Outcome
}

/** The stored target once it has been read, so the app's language is never asked for by mistake first. */
private data class StoredTarget(val tag: String?)

/**
 * The translation of a selected passage, set like a page of a bilingual
 * edition: the original above, quiet and in italics, the translation
 * below it in the reading type. The two languages sit under them as
 * buttons, so either can be changed in place, and the last line says
 * where the text went.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranslationSheet(
    feature: ServiceTranslate,
    passage: String,
    declared: List<String>,
    onDismiss: () -> Unit,
    onTranslatePage: ((source: String?, target: String) -> Unit)? = null,
) {
    val ui = LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    val service by feature.service.collectAsState(initial = null)
    val choice by feature.choice.collectAsState(initial = null)
    val stored by remember { feature.target.map(::StoredTarget) }.collectAsState(initial = null)
    val configured by remember(service) { service?.configured ?: kotlinx.coroutines.flow.flowOf(null) }
        .collectAsState(initial = null)

    val book = remember(declared) { TranslationLanguages.source(declared) }
    var pickedSource by rememberSaveable { mutableStateOf<String?>(null) }
    val source = pickedSource ?: book
    val target = stored?.let { TranslationLanguages.target(it.tag, ui) }
    var picking by rememberSaveable { mutableStateOf<Picking?>(null) }
    // Null when closed; true opens it on the Services page.
    var settings by remember { mutableStateOf<Boolean?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    // Asked again on returning, as from the system's settings with a language downloaded.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    var offered by remember(service, source) { mutableStateOf<Map<String, PairState>?>(null) }
    var offeredKnown by remember(service, source) { mutableStateOf(false) }
    LaunchedEffect(service, source, resumed) {
        val current = service ?: return@LaunchedEffect
        current.refresh()
        offered = current.targets(source)
        offeredKnown = true
    }

    val current = service
    val step = if (current == null || configured == null || target == null || !offeredKnown) {
        null
    } else {
        TranslationStep.of(passage, configured == true, current.detectsLanguage, source, target, offered)
    }
    // A passage already in the target asks for another one, once; backing out leaves a button to ask again.
    var askedTarget by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(step) {
        if (step == TranslationStep.Same && !askedTarget && picking == null) {
            askedTarget = true
            picking = Picking.Target
        }
    }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    LaunchedEffect(step, passage, source, target, choice, attempt) {
        outcome = null
        if (step != TranslationStep.Translate || current == null || target == null) return@LaunchedEffect
        outcome = try {
            Outcome.Done(feature.translate(current, passage, source, target))
        } catch (e: TranslationError) {
            Outcome.Failed(e)
        }
    }
    val destination by produceState<String?>(null, current, choice) { value = current?.let { feature.destination(it) } }

    LiseurModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        BackHandler(enabled = picking != null) { picking = null }
        Column(
            Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = contentWidthCap(windowWidth()))
                .fillMaxWidth(),
        ) {
            when (picking) {
                Picking.Source -> SourcePicker(
                    service = current,
                    selected = source,
                    pinned = TranslationLanguages.declared(declared),
                    onPick = {
                        pickedSource = it
                        picking = null
                    },
                    onBack = { picking = null },
                )
                Picking.Target -> TargetPicker(
                    service = current,
                    source = source,
                    selected = target,
                    pinned = listOfNotNull(stored?.tag?.let(TranslationLanguages::of), TranslationLanguages.of(ui.toLanguageTag())),
                    prompt = if (step == TranslationStep.Same && source != null) {
                        stringResource(R.string.translation_same_choose, TranslationLanguages.name(source, ui))
                    } else {
                        null
                    },
                    onPick = {
                        scope.launch { feature.setTarget(it) }
                        picking = null
                    },
                    onBack = { picking = null },
                )
                null -> Translation(
                    passage = passage,
                    step = step,
                    outcome = outcome,
                    service = current,
                    destination = destination,
                    source = source,
                    target = target,
                    ui = ui,
                    onPickSource = { picking = Picking.Source },
                    onPickTarget = { picking = Picking.Target },
                    onRetry = { attempt++ },
                    onSettings = { settings = it },
                    onDownload = { scope.launch { (current as? DeviceTranslationService)?.openDownloads() } },
                    onTranslatePage = onTranslatePage?.let { start ->
                        target?.let {
                            {
                                start(source, it)
                                onDismiss()
                            }
                        }
                    },
                )
            }
        }
    }

    settings?.let { services ->
        val close: () -> Unit = { settings = null }
        Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) { feature.SettingsScreen(onBack = close, services = services) }
        }
    }
}

@Composable
private fun Translation(
    passage: String,
    step: TranslationStep?,
    outcome: Outcome?,
    service: TranslationService?,
    destination: String?,
    source: String?,
    target: String?,
    ui: Locale,
    onPickSource: () -> Unit,
    onPickTarget: () -> Unit,
    onRetry: () -> Unit,
    onSettings: (services: Boolean) -> Unit,
    onDownload: () -> Unit,
    onTranslatePage: (() -> Unit)?,
) {
    val device = service as? DeviceTranslationService
    val hasDownloads by produceState(false, device) { value = device?.hasDownloads() == true }
    val sourceName = source?.let { TranslationLanguages.name(it, ui) }
    val targetName = target?.let { TranslationLanguages.name(it, ui) }.orEmpty()
    val name = destination ?: stringResource(R.string.translation_provider_device)
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
    ) {
        Original(passage)
        Spacer(Modifier.height(20.dp))
        val body: Body = when (step) {
            null -> Body.Busy
            TranslationStep.TooLong -> Body.Message(stringResource(R.string.translation_too_long))
            TranslationStep.NotSetUp -> Body.Message(
                stringResource(R.string.translation_not_set_up),
                stringResource(R.string.translation_open_settings),
            ) { onSettings(false) }
            TranslationStep.NeedsSource -> Body.Message(
                stringResource(R.string.translation_needs_source),
                stringResource(R.string.translation_choose_language),
                onAction = onPickSource,
            )
            TranslationStep.Same -> Body.Message(
                stringResource(R.string.translation_same, targetName),
                stringResource(R.string.translation_choose_language),
                onAction = onPickTarget,
            )
            TranslationStep.NotDownloaded -> notDownloaded(sourceName.orEmpty(), targetName, hasDownloads, onDownload)
            TranslationStep.Unsupported -> unsupported(sourceName.orEmpty(), targetName, onSettings)
            TranslationStep.Translate -> when (outcome) {
                null -> Body.Busy
                is Outcome.Done -> Body.Text(outcome.text)
                is Outcome.Failed -> failure(outcome.error, name, sourceName.orEmpty(), targetName, hasDownloads, onRetry, onSettings, onDownload)
            }
        }
        val eInk = LocalEInk.current
        // The translation fades in where "Translating…" was; e-paper shows it at once.
        Crossfade(targetState = body, animationSpec = if (eInk) snap() else tween(FADE_MS), label = "translation") {
            BodyContent(it)
        }
        Spacer(Modifier.height(12.dp))
        LanguageRow(
            sourceName = sourceName ?: stringResource(
                if (service?.detectsLanguage == true) R.string.translation_detected else R.string.translation_choose_language,
            ),
            targetName = targetName,
            copyable = (body as? Body.Text)?.text,
            onPickSource = onPickSource,
            onPickTarget = onPickTarget,
        )
        if (body is Body.Text) {
            Text(
                text = destination?.let { stringResource(R.string.translation_attribution_service, it) }
                    ?: stringResource(R.string.translation_attribution_device),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            onTranslatePage?.let {
                OutlinedButton(onClick = it, modifier = Modifier.padding(top = 16.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.translation_page_start))
                }
            }
        }
    }
}

/** What fills the space under the original. */
private sealed interface Body {
    data object Busy : Body
    data class Text(val text: String) : Body

    /** A sentence that says what stands in the way, and the one way forward. */
    data class Message(
        val text: String,
        val action: String? = null,
        val error: Boolean = false,
        val onAction: () -> Unit = {},
    ) : Body {
        // Two messages are the same when they say the same, whatever lambda they carry.
        override fun equals(other: Any?) = other is Message && other.text == text && other.action == action
        override fun hashCode() = text.hashCode() * 31 + action.hashCode()
    }
}

@Composable
private fun notDownloaded(source: String, target: String, hasDownloads: Boolean, onDownload: () -> Unit): Body =
    if (hasDownloads) {
        Body.Message(
            stringResource(R.string.translation_not_downloaded, source, target),
            stringResource(R.string.translation_download),
            onAction = onDownload,
        )
    } else {
        Body.Message(stringResource(R.string.translation_not_downloaded_system, source, target))
    }

@Composable
private fun unsupported(source: String, target: String, onSettings: (Boolean) -> Unit): Body = Body.Message(
    stringResource(R.string.translation_unsupported, source, target),
    stringResource(R.string.translation_choose_service),
    onAction = { onSettings(false) },
)

@Composable
private fun failure(
    error: TranslationError,
    name: String,
    source: String,
    target: String,
    hasDownloads: Boolean,
    onRetry: () -> Unit,
    onSettings: (Boolean) -> Unit,
    onDownload: () -> Unit,
): Body {
    val text = errorSentence(error, name, source, target)
    val retry = stringResource(R.string.read_aloud_settings_server_retry)
    val settings = stringResource(R.string.translation_open_settings)
    return when (error) {
        is TranslationError.NotDownloaded -> notDownloaded(source, target, hasDownloads, onDownload)
        is TranslationError.Unsupported -> unsupported(source, target, onSettings)
        is TranslationError.NotSetUp -> Body.Message(text, settings) { onSettings(false) }
        is TranslationError.InvalidKey ->
            Body.Message(text, stringResource(R.string.translation_open_services), error = true) { onSettings(true) }
        is TranslationError.LocalNetworkBlocked -> Body.Message(text, settings, error = true) { onSettings(false) }
        is TranslationError.Refused -> Body.Message(text, error = true)
        else -> Body.Message(text, retry, error = true, onAction = onRetry)
    }
}

@Composable
private fun BodyContent(body: Body) {
    when (body) {
        Body.Busy -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            BusyIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.translation_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is Body.Text -> SelectionContainer {
            Text(
                text = body.text,
                style = readingStyle(MaterialTheme.typography.bodyLarge).copy(lineHeight = 1.5.em),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        is Body.Message -> Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                text = body.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (body.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            body.action?.let { action -> Button(onClick = body.onAction) { Text(action) } }
        }
    }
}

/**
 * The passage as selected: quiet, in italics, with a rule at its start
 * edge as a bilingual edition marks the original. Long ones are held to
 * three lines with a plain way to see them all, as tapping text says
 * nothing on e-paper.
 */
@Composable
private fun Original(passage: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var clamped by remember { mutableStateOf(false) }
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = passage,
            style = readingStyle(MaterialTheme.typography.bodyMedium).copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else ORIGINAL_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) clamped = it.hasVisualOverflow },
        )
    }
    if (clamped || expanded) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.translation_show_less else R.string.translation_show_all))
        }
    }
}

/** The app's serif, which the headline styles carry, at [style]'s size. */
@Composable
private fun readingStyle(style: TextStyle): TextStyle = style.copy(fontFamily = MaterialTheme.typography.titleLarge.fontFamily)

@Composable
private fun LanguageRow(
    sourceName: String,
    targetName: String,
    copyable: String?,
    onPickSource: () -> Unit,
    onPickTarget: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val sourceLabel = stringResource(R.string.translation_source_label, sourceName)
    val targetLabel = stringResource(R.string.translation_target_label, targetName)
    val copied = stringResource(R.string.translation_copied)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            TextButton(
                onClick = onPickSource,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .semantics { contentDescription = sourceLabel },
            ) {
                Text(sourceName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            TextButton(
                onClick = onPickTarget,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .semantics { contentDescription = targetLabel },
            ) {
                Text(targetName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (copyable != null) {
            IconButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(targetName, copyable)))
                        // Android 13 confirms a copy itself.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.translation_copy))
            }
        }
    }
}

@Composable
private fun SourcePicker(
    service: TranslationService?,
    selected: String?,
    pinned: List<String>,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
) {
    val offered by produceState<Collection<String>?>(null, service) {
        value = service?.sources() ?: TranslationLanguages.all()
    }
    LanguagePicker(
        title = stringResource(R.string.translation_picker_source),
        languages = offered,
        pinned = pinned,
        selected = selected,
        needsDownload = emptySet(),
        onPick = onPick,
        onBack = onBack,
    )
}

@Composable
private fun TargetPicker(
    service: TranslationService?,
    source: String?,
    selected: String?,
    pinned: List<String>,
    prompt: String?,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
) {
    val offered by produceState<Map<String, PairState>?>(null, service, source) {
        value = service?.targets(source) ?: TranslationLanguages.all().associateWith { PairState.Ready }
    }
    LanguagePicker(
        title = stringResource(R.string.translation_settings_target),
        languages = offered?.keys?.let { TranslationLanguages.targets(it, source) },
        pinned = pinned,
        selected = selected,
        needsDownload = offered.orEmpty().filterValues { it == PairState.NeedsDownload }.keys,
        onPick = onPick,
        onBack = onBack,
        header = {
            prompt?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        },
    )
}

/**
 * Every language in [languages], [pinned] first and the rest by name as
 * the reader's language sorts them, with a field to find one. Null
 * [languages] are still being asked for.
 */
@Composable
internal fun LanguagePicker(
    title: String,
    languages: Collection<String>?,
    pinned: List<String>,
    selected: String?,
    needsDownload: Set<String>,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
    header: @Composable () -> Unit = {},
) {
    val ui = LocalConfiguration.current.locales[0]
    var query by rememberSaveable { mutableStateOf("") }
    val ordered = remember(languages, pinned, ui) { languages?.let { TranslationLanguages.ordered(it, pinned, ui) } }
    val shown = remember(ordered, query, ui) {
        val wanted = query.trim().lowercase(ui)
        ordered?.filter { wanted.isEmpty() || TranslationLanguages.name(it, ui).lowercase(ui).contains(wanted) || it.lowercase() == wanted }
    }
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val height = with(LocalDensity.current) { windowHeight.toDp() } * PICKER_HEIGHT
    // The e-paper sheet is a popup the keyboard does not resize, so the
    // search field has to lift itself; Material's sheet has already consumed
    // the keyboard inset, which makes this a no-op there.
    Column(Modifier.imePadding().padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.translation_picker_search)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (shown == null) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                BusyIndicator(modifier = Modifier.size(24.dp))
            }
            return@Column
        }
        LazyColumn(Modifier.heightIn(max = height).selectableGroup()) {
            item { header() }
            if (shown.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.translation_picker_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
            }
            items(shown, key = { it }) { tag ->
                val isSelected = selected != null && tag == selected
                ListItem(
                    modifier = Modifier.selectable(selected = isSelected, role = Role.RadioButton) { onPick(tag) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = {
                        Text(
                            TranslationLanguages.name(tag, ui),
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
                    },
                    supportingContent = if (tag in needsDownload) {
                        { Text(stringResource(R.string.translation_picker_needs_download)) }
                    } else {
                        null
                    },
                    trailingContent = if (isSelected) {
                        { Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

private const val ORIGINAL_LINES = 3
private const val FADE_MS = 220
private const val PICKER_HEIGHT = 0.6f
