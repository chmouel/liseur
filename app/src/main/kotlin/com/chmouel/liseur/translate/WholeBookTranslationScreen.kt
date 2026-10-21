package com.chmouel.liseur.translate

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.LiseurApplication
import com.chmouel.liseur.R
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth
import com.chmouel.liseur.ui.theme.SystemBarIcons
import com.chmouel.liseur.ui.theme.isDarkSurface
import java.text.NumberFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WholeBookTranslationScreen(
    bookUrl: String,
    onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onBack ?: onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(Modifier.fillMaxSize()) {
            SystemBarIcons(dark = isDarkSurface(MaterialTheme.colorScheme.surface))
            WholeBookTranslationContent(bookUrl, onDismiss, onBack, onOpenSettings)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WholeBookTranslationContent(
    bookUrl: String,
    onDismiss: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val container = (context.applicationContext as LiseurApplication).container
    val jobs = container.wholeBookTranslations
    val preparation = remember(bookUrl) { jobs.preparation(bookUrl) }
    val preparationState by preparation.state.collectAsStateWithLifecycle()
    val preview = (preparationState as? PreparationState.Ready)?.preview
    val appSettings by container.appSettings.settings.collectAsState(initial = null)
    val records by jobs.records.collectAsState(initial = emptyList())
    val record = records.firstOrNull { it.bookUrl == bookUrl }
    val books by container.libraryRepository.books.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val locale = LocalConfiguration.current.locales[0]
    val numbers = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    var info by remember(bookUrl) { mutableStateOf<WholeBookInfo?>(null) }
    var inspecting by remember(bookUrl) { mutableStateOf(true) }
    var inspectionFailed by remember(bookUrl) { mutableStateOf(false) }
    var unsupported by remember(bookUrl) { mutableStateOf(false) }
    var refresh by remember(bookUrl) { mutableIntStateOf(0) }
    var seenState by remember(bookUrl) { mutableStateOf<String?>(null) }
    var actionFailure by remember(bookUrl) { mutableStateOf<Throwable?>(null) }
    val failure = actionFailure ?: (preparationState as? PreparationState.Failed)?.error
    var resuming by remember(bookUrl) { mutableStateOf(false) }
    val checking = preparationState is PreparationState.Checking || resuming
    var details by remember(bookUrl) { mutableStateOf(false) }
    var manage by remember(bookUrl) { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var serviceName by remember(bookUrl) { mutableStateOf<String?>(null) }
    var permissionPreview by remember(bookUrl) { mutableStateOf<WholeBookPreview?>(null) }
    var notificationsDenied by remember {
        mutableStateOf(!NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    val leave = onBack ?: onDismiss
    val startApproved: (WholeBookPreview) -> Unit = { approved ->
        actionFailure = null
        try {
            jobs.start(approved, retryFailures = approved.retryFailures)
            starting = true
        } catch (e: Exception) {
            starting = false
            actionFailure = e
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsDenied = !granted || !NotificationManagerCompat.from(context).areNotificationsEnabled()
        permissionPreview?.let(startApproved)
        permissionPreview = null
    }
    val check: (Boolean) -> Unit = { retry ->
        actionFailure = null
        details = false
        manage = false
        preparation.prepare(retry)
    }
    val resume: () -> Unit = {
        preparation.cancel()
        actionFailure = null
        resuming = true
        scope.launch {
            try {
                if (jobs.resume(bookUrl)) starting = true else check(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                actionFailure = e
            } finally {
                resuming = false
            }
        }
    }
    LifecycleResumeEffect(bookUrl) {
        preparation.validate()
        onPauseOrDispose { }
    }
    LaunchedEffect(bookUrl, appSettings, refresh, record?.generation, record?.state) {
        preparation.validate()
    }
    LaunchedEffect(bookUrl, refresh, appSettings) {
        inspecting = true
        inspectionFailed = false
        unsupported = false
        try {
            info = jobs.inspect(bookUrl)
            (container.translate as? ServiceTranslate)?.let { feature ->
                val service = feature.service.first()
                val server = feature.server.first()
                val model = service.model(container.appSettings.settings.first())
                serviceName = listOfNotNull(server?.name ?: resources.getString(service.label), model).joinToString(" · ")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            unsupported = e is TranslationError.Unsupported
            inspectionFailed = true
        } finally {
            inspecting = false
        }
    }
    LaunchedEffect(record?.state) {
        if (record?.state == "running") {
            preparation.cancel()
            actionFailure = null
            starting = false
            seenState = "running"
        } else if (record != null) {
            starting = false
            // The first delivery only fills the page in; a later change (a
            // running record finishing) is what asks for a fresh check.
            if (seenState != null && seenState != record.state) refresh++
            seenState = record.state
        }
    }
    LaunchedEffect(bookUrl) {
        jobs.startFailures.collect {
            if (it.bookUrl == bookUrl) {
                starting = false
                actionFailure = it.error
            }
        }
    }
    if (settings) {
        container.translate.SettingsScreen(onBack = { settings = false; refresh++ })
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.whole_book_entry)) },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = contentWidthCap(windowWidth())).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    preview?.title ?: info?.title ?: books.firstOrNull { it.url == bookUrl }?.title.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                )
                val source = preview?.source ?: info?.source ?: record?.source
                val target = preview?.target ?: info?.target ?: record?.target
                target?.let {
                    Text(
                        stringResource(
                            R.string.whole_book_languages,
                            source?.let { tag -> TranslationLanguages.name(tag, locale) } ?: stringResource(R.string.whole_book_source_auto),
                            TranslationLanguages.name(it, locale),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                val checked = preview
                val missing = checked?.let { (it.total - it.cached - if (it.retryFailures) 0 else it.failed).coerceAtLeast(0) } ?: 0
                when {
                    record?.state == "running" -> {
                        serviceName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text(
                            stringResource(R.string.whole_book_saved_progress, numbers.format(record.done), numbers.format(record.total)),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        if (record.failed > 0) Text(stringResource(R.string.whole_book_original_count, numbers.format(record.failed)))
                        LinearProgressIndicator(
                            progress = { if (record.total > 0) (record.done + record.failed).toFloat() / record.total else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = { jobs.pause(bookUrl) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.whole_book_pause))
                        }
                        if (notificationsDenied) Text(stringResource(R.string.whole_book_notification_denied), style = MaterialTheme.typography.bodySmall)
                    }
                    starting -> BusyIndicator(modifier = Modifier.size(24.dp))
                    checking -> {
                        Text(stringResource(R.string.whole_book_scanning))
                        val progress = (preparationState as? PreparationState.Checking)?.progress ?: PreparationProgress()
                        Text(
                            stringResource(when (progress.stage) {
                                PreparationStage.OPENING -> R.string.whole_book_opening
                                PreparationStage.VALIDATING -> R.string.whole_book_validating
                                PreparationStage.SCANNING -> R.string.whole_book_checking_sentences
                                PreparationStage.FINISHING -> R.string.whole_book_finishing
                            }),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        val fraction = progress.fraction
                        if (fraction == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        if (progress.sections > 0) {
                            Text(stringResource(R.string.whole_book_sections, numbers.format(progress.section), numbers.format(progress.sections)))
                        }
                        if (progress.stage == PreparationStage.SCANNING || progress.stage == PreparationStage.FINISHING) {
                            Text(stringResource(R.string.whole_book_sentences_checked, numbers.format(progress.sentences)))
                        }
                        Text(stringResource(R.string.whole_book_check_background), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { preparation.cancel() }, enabled = !resuming) {
                            Text(stringResource(R.string.whole_book_cancel_check))
                        }
                    }
                    failure != null -> {
                        Text(
                            when (val error = failure) {
                                is TranslationError.Unsupported -> stringResource(R.string.whole_book_file_unsupported)
                                is TranslationError.NotSetUp -> stringResource(R.string.whole_book_ineligible)
                                is TranslationError -> errorSentence(error, serviceName ?: stringResource(R.string.translation_settings_provider), "", "")
                                is WholeBookBusyException -> stringResource(R.string.whole_book_already_running)
                                is WholeBookSameLanguageException ->
                                    stringResource(R.string.whole_book_same_language, TranslationLanguages.name(error.target, locale))
                                else -> stringResource(R.string.whole_book_scan_failed)
                            },
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (failure is TranslationError.NotSetUp || failure is WholeBookSameLanguageException) {
                            Button(onClick = { onOpenSettings?.invoke() ?: run { settings = true } }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.translation_open_settings))
                            }
                        } else {
                            Button(
                                onClick = { check((preparationState as? PreparationState.Failed)?.retryFailures ?: checked?.retryFailures ?: false) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.whole_book_try_again))
                            }
                        }
                    }
                    checked != null -> {
                        Text(stringResource(R.string.whole_book_counts, numbers.format(checked.total), numbers.format(checked.cached), numbers.format(checked.failed), numbers.format(missing)))
                        if (missing > 0) {
                            serviceName?.let { Text(it) }
                            checked.destination?.let { Text(stringResource(R.string.translation_settings_privacy, it), style = MaterialTheme.typography.bodySmall) }
                            val cost = checked.cost
                            Text(if (cost == null) stringResource(R.string.whole_book_price_unknown) else {
                                val currency = NumberFormat.getCurrencyInstance(locale).apply {
                                    this.currency = java.util.Currency.getInstance("USD")
                                    maximumFractionDigits = 4
                                }
                                stringResource(R.string.whole_book_cost, currency.format(cost.start), currency.format(cost.endInclusive))
                            })
                            Text(stringResource(R.string.whole_book_consent), style = MaterialTheme.typography.bodySmall)
                            if (checked.retryFailures) Text(stringResource(R.string.whole_book_retry_notice), style = MaterialTheme.typography.bodySmall)
                            Button(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= 33 &&
                                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        permissionPreview = checked
                                        starting = true
                                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        notificationsDenied = !NotificationManagerCompat.from(context).areNotificationsEnabled()
                                        startApproved(checked)
                                    }
                                },
                                enabled = !starting,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.whole_book_confirm)) }
                        } else {
                            Text(stringResource(R.string.whole_book_checked))
                            if (record == null) Text(stringResource(R.string.whole_book_cache_only), style = MaterialTheme.typography.bodySmall)
                            if (record?.state == "restored" || record?.state == "paused") {
                                Text(stringResource(R.string.whole_book_state_unchanged), style = MaterialTheme.typography.bodySmall)
                            }
                            if (record == null || record.state == "paused") {
                                Button(
                                    enabled = !starting,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        starting = true
                                        scope.launch {
                                            try {
                                                jobs.retain(checked)
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                actionFailure = e
                                            } finally {
                                                starting = false
                                            }
                                        }
                                    },
                                ) { Text(stringResource(R.string.whole_book_keep_local)) }
                            }
                        }
                        TextButton(onClick = { details = !details }) { Text(stringResource(R.string.whole_book_details)) }
                        TextButton(onClick = { check(checked.retryFailures) }) { Text(stringResource(R.string.whole_book_check)) }
                        if (details) {
                            Text(stringResource(R.string.whole_book_characters, numbers.format(checked.characters)), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.whole_book_notice), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    record?.state == "done" -> {
                        Text(stringResource(R.string.whole_book_saved_progress, numbers.format(record.done), numbers.format(record.total)))
                        if (record.failed > 0) Text(stringResource(R.string.whole_book_original_count, numbers.format(record.failed)))
                        Text(
                            stringResource(
                                when {
                                    inspectionFailed && unsupported -> R.string.whole_book_file_unsupported
                                    inspectionFailed -> R.string.whole_book_scan_failed
                                    info?.identityMatches == false -> R.string.whole_book_mismatch
                                    else -> R.string.whole_book_read_hint
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    record?.state == "restored" -> {
                        Text(stringResource(R.string.whole_book_imported))
                        Button(onClick = { check(false) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.whole_book_check)) }
                    }
                    record?.state == "paused" -> {
                        Text(stringResource(R.string.whole_book_saved_progress, numbers.format(record.done), numbers.format(record.total)))
                        Text(stringResource(wholeBookPauseReason(record.reason)), style = MaterialTheme.typography.bodyMedium)
                        wholeBookPauseCode(record.reason)?.let { code ->
                            Text(stringResource(R.string.whole_book_reason_code, code), style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = { resume() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.whole_book_resume)) }
                    }
                    inspecting -> BusyIndicator(modifier = Modifier.size(24.dp))
                    inspectionFailed -> {
                        Text(stringResource(if (unsupported) R.string.whole_book_file_unsupported else R.string.whole_book_scan_failed))
                        if (!unsupported) {
                            Button(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.whole_book_try_again)) }
                        }
                    }
                    info?.eligible == false -> {
                        Text(stringResource(R.string.whole_book_ineligible))
                        TextButton(onClick = { onOpenSettings?.invoke() ?: run { settings = true } }) {
                            Text(stringResource(R.string.translation_open_settings))
                        }
                    }
                    else -> {
                        Text(stringResource(R.string.whole_book_prepare_detail))
                        Button(onClick = { check(false) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.whole_book_prepare)) }
                    }
                }
                if (record != null && !checking && (checked == null || missing == 0)) {
                    TextButton(onClick = { manage = !manage }) { Text(stringResource(R.string.whole_book_manage)) }
                    if (manage) {
                        if (info?.eligible == false) {
                            TextButton(onClick = { onOpenSettings?.invoke() ?: run { settings = true } }) {
                                Text(stringResource(R.string.translation_open_settings))
                            }
                        }
                        if (record.state == "done" && checked == null) {
                            TextButton(onClick = { check(false) }) { Text(stringResource(R.string.whole_book_check)) }
                        }
                        if (record.failed > 0 && record.state != "running") {
                            TextButton(onClick = { check(true) }) { Text(stringResource(R.string.whole_book_retry)) }
                        }
                        TextButton(onClick = { deleting = true }) { Text(stringResource(R.string.whole_book_delete)) }
                    }
                }
            }
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.whole_book_delete)) },
            text = { Text(stringResource(R.string.whole_book_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    scope.launch {
                        try {
                            jobs.delete(bookUrl)
                            onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            actionFailure = e
                        }
                    }
                }) { Text(stringResource(R.string.whole_book_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

internal fun wholeBookStateLabel(state: String): Int = when (state) {
    "running" -> R.string.whole_book_running
    "done" -> R.string.whole_book_saved_short
    "restored" -> R.string.whole_book_imported_short
    else -> R.string.whole_book_paused
}

internal fun wholeBookPauseReason(reason: String?): Int = when (reason) {
    "settings_changed" -> R.string.whole_book_settings_changed
    "network" -> R.string.whole_book_reason_network
    "timeout" -> R.string.whole_book_reason_timeout
    "interrupted" -> R.string.whole_book_reason_interrupted
    "storage" -> R.string.whole_book_reason_storage
    else -> when (reason?.substringBefore(':')) {
        "rate_limited" -> R.string.whole_book_reason_rate
        "service" -> R.string.whole_book_reason_service
        else -> R.string.whole_book_paused
    }
}

/** The HTTP status a service or rate-limit pause kept, as in `service:503` or `rate_limited:402`. */
internal fun wholeBookPauseCode(reason: String?): Int? =
    reason?.takeIf { it.startsWith("service:") || it.startsWith("rate_limited:") }?.split(':')?.getOrNull(1)?.toIntOrNull()
