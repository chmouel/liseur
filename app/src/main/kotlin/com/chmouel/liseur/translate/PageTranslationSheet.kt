package com.chmouel.liseur.translate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ServerConnection
import com.chmouel.liseur.data.settings.ServerList
import com.chmouel.liseur.data.settings.ServerSettings
import com.chmouel.liseur.providers.ServiceOptions
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What the page sheet holds before Apply: the languages, and the service, server and model to translate with. */
internal data class PageTranslationChoice(
    val source: String?,
    val target: String,
    val service: String,
    val server: String?,
    val model: String,
) {
    /**
     * Whether Apply may start the page again with this choice: something
     * differs from [seed], what the run uses, and [step] lets it translate.
     * A missing download is the bar's to fetch once the run asks.
     */
    fun applies(seed: PageTranslationChoice, step: TranslationStep?): Boolean =
        copy(model = model.trim()) != seed.copy(model = seed.model.trim()) &&
            (step == TranslationStep.Translate || step == TranslationStep.NotDownloaded)
}

private enum class PagePicking { Source, Target, Service }

/** The targets a service offers from a source, once asked; null [targets] takes any. */
private data class Offered(val targets: Map<String, PairState>?)

/**
 * Changes a page run's languages, service and model, as the voice sheet
 * does for read aloud. Looking saves nothing; Apply hands [onChange] the
 * languages and what saves the rest, and the page starts again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageTranslationSheet(
    feature: ServiceTranslate,
    translator: SentenceTranslator,
    onChange: (source: String?, target: String, save: suspend () -> Unit) -> Unit,
    onSettingsClosed: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ui = LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    val provider by feature.provider.collectAsState(initial = null)
    val servers by feature.servers.collectAsState(initial = ServerList.Empty)
    // Null when closed; true opens it on the Services page.
    var settings by remember { mutableStateOf<Boolean?>(null) }
    var reseed by remember { mutableIntStateOf(0) }
    var seed by remember { mutableStateOf<PageTranslationChoice?>(null) }
    var staged by remember { mutableStateOf<PageTranslationChoice?>(null) }
    // What is saved for the staged service, so Apply only stores a model that changed.
    var savedModel by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<PagePicking?>(null) }
    var agreeing by remember { mutableStateOf(false) }

    // Settings changed there are what the run uses from its next sentence; the languages picked here stay.
    LaunchedEffect(reseed) {
        val service = feature.service.first()
        val server = feature.server.first()
        val model = feature.savedModel(service, server)
        val saved = PageTranslationChoice(translator.source, translator.target, service.id, server?.id, model)
        seed = saved
        savedModel = model
        staged = staged?.copy(service = saved.service, server = saved.server, model = model) ?: saved
    }

    val choice = staged
    val service = choice?.let { c -> feature.services.firstOrNull { it.id == c.service } }
    val server = choice?.server?.let { id -> servers.readable.orEmpty().firstOrNull { it.id == id } }
    val configured by remember(service) { service?.configured ?: kotlinx.coroutines.flow.flowOf(null) }
        .collectAsState(initial = null)
    var offered by remember(service, choice?.source) { mutableStateOf<Offered?>(null) }
    LaunchedEffect(service, choice?.source) {
        val current = service ?: return@LaunchedEffect
        current.refresh()
        offered = Offered(current.targets(choice?.source))
    }
    val ready = configured
    val step = if (choice == null || service == null || ready == null || offered == null) {
        null
    } else {
        TranslationStep.languages(
            service.readyWith(ready, server, choice.model),
            service.detectsLanguage,
            choice.source,
            choice.target,
            offered?.targets,
        )
    }

    fun stage(picked: TranslationService, at: ServerConnection?) {
        picking = null
        scope.launch {
            val model = feature.savedModel(picked, at)
            savedModel = model
            staged = staged?.copy(service = picked.id, server = at?.id, model = model)
        }
    }

    fun apply() {
        val c = staged ?: return
        val s = seed ?: return
        val picked = service ?: return
        val at = server
        val modelChanged = c.model.trim() != savedModel.trim()
        onChange(c.source, c.target) {
            if (c.service != s.service || c.server != s.server) {
                if (at != null) feature.chooseServer(at.id) else feature.choose(picked)
            }
            if (picked.hasModels && modelChanged) picked.saveModel(at, c.model)
        }
        onDismiss()
    }

    LiseurModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onBack = if (picking != null) ({ picking = null }) else null,
    ) {
        Column(
            Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = contentWidthCap(windowWidth()))
                .fillMaxWidth(),
        ) {
            when (picking) {
                PagePicking.Source -> SourcePicker(
                    service = service,
                    selected = choice?.source,
                    pinned = listOfNotNull(translator.source),
                    onPick = {
                        staged = staged?.copy(source = it)
                        picking = null
                    },
                    onBack = { picking = null },
                )
                PagePicking.Target -> TargetPicker(
                    service = service,
                    source = choice?.source,
                    selected = choice?.target,
                    pinned = listOfNotNull(translator.target, TranslationLanguages.of(ui.toLanguageTag())),
                    prompt = null,
                    onPick = {
                        staged = staged?.copy(target = it)
                        picking = null
                    },
                    onBack = { picking = null },
                )
                PagePicking.Service -> ServiceOptions(
                    title = stringResource(R.string.translation_settings_provider),
                    options = translationServiceOptions(
                        services = offeredServices(feature, provider),
                        servers = servers.readable.orEmpty(),
                        selected = service,
                        selectedServer = choice?.server,
                        onPick = { stage(it, null) },
                        onPickServer = { at ->
                            feature.services.firstOrNull { it.id == ServerSettings.SERVER_PROVIDER }?.let { stage(it, at) }
                        },
                    ),
                    manageDetail = if (servers.readable.isNullOrEmpty()) stringResource(R.string.translation_settings_manage_detail) else null,
                    onManage = {
                        picking = null
                        settings = true
                    },
                    onBack = { picking = null },
                )
                null -> Choice(
                    choice = choice,
                    service = service,
                    server = server,
                    step = step,
                    onPick = { picking = it },
                    onModel = { model -> staged = staged?.copy(model = model) },
                    onSettings = { settings = false },
                    onCancel = onDismiss,
                    onApply = {
                        val picked = service
                        if (picked != null && picked.consent != null && picked.id != provider && choice?.service != seed?.service) {
                            agreeing = true
                        } else {
                            apply()
                        }
                    }.takeIf { choice != null && seed?.let { choice.applies(it, step) } == true },
                )
            }
        }
    }

    if (agreeing) {
        service?.let {
            TranslationConsent(
                service = it,
                onAgree = {
                    agreeing = false
                    apply()
                },
                onDismiss = { agreeing = false },
            )
        }
    }

    settings?.let { services ->
        val close: () -> Unit = {
            settings = null
            reseed++
            onSettingsClosed()
        }
        Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) { feature.SettingsScreen(onBack = close, services = services) }
        }
    }
}

@Composable
private fun ColumnScope.Choice(
    choice: PageTranslationChoice?,
    service: TranslationService?,
    server: ServerConnection?,
    step: TranslationStep?,
    onPick: (PagePicking) -> Unit,
    onModel: (String) -> Unit,
    onSettings: () -> Unit,
    onCancel: () -> Unit,
    onApply: (() -> Unit)?,
) {
    val ui = LocalConfiguration.current.locales[0]
    Column(
        Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(stringResource(R.string.translation_settings_title), style = MaterialTheme.typography.titleMedium)
        if (choice == null || service == null) return@Column
        val sourceName = choice.source?.let { TranslationLanguages.name(it, ui) }
        val targetName = TranslationLanguages.name(choice.target, ui)
        Field(
            label = stringResource(R.string.translation_picker_source),
            value = sourceName ?: stringResource(R.string.translation_detected),
            onClick = { onPick(PagePicking.Source) },
        )
        Field(
            label = stringResource(R.string.translation_settings_target),
            value = targetName,
            onClick = { onPick(PagePicking.Target) },
        )
        Field(
            label = stringResource(R.string.translation_settings_provider),
            value = server?.name ?: stringResource(service.label),
            icon = service.icon,
            onClick = { onPick(PagePicking.Service) },
        )
        if (service.hasModels) {
            service.ModelMenu(server, choice.model, onModel, Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        val source = sourceName.orEmpty()
        val note = when (step) {
            TranslationStep.NotSetUp -> stringResource(
                if (service.hasModels && choice.model.isBlank()) R.string.read_aloud_settings_server_model_choose else R.string.read_aloud_settings_entry_missing,
            )
            TranslationStep.NeedsSource -> stringResource(R.string.translation_needs_source)
            TranslationStep.Same -> stringResource(R.string.translation_same, targetName)
            TranslationStep.Unsupported -> stringResource(R.string.translation_unsupported, source, targetName)
            TranslationStep.NotDownloaded -> stringResource(R.string.translation_not_downloaded, source, targetName)
            else -> null
        }
        note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (step == TranslationStep.NotDownloaded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onSettings) { Text(stringResource(R.string.translation_page_sheet_settings)) }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = { onApply?.invoke() }, enabled = onApply != null) {
                    Text(stringResource(R.string.read_aloud_voice_sheet_apply))
                }
            }
        }
    }
}

/** A read-only field that opens a list in the sheet's place, as the voice sheet's language does. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Field(label: String, value: String, onClick: () -> Unit, icon: ImageVector? = null) {
    ExposedDropdownMenuBox(
        expanded = false,
        onExpandedChange = { if (it) onClick() },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            leadingIcon = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(20.dp)) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = false) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
    }
}
