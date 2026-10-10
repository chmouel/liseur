package com.chmouel.liseur.translate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.reader.chrome.ChromeCard
import com.chmouel.liseur.ui.LocalEInk
import kotlinx.coroutines.launch

/** The one thing to do about what stopped a run, and how. */
private data class HaltAction(val label: String, val run: () -> Unit)

/**
 * The bar under a translated page: which languages, how far it got or
 * where the text goes, and Stop. It stays up without the reader's
 * controls only while something stopped the run, saying what and the way
 * on, since the page otherwise just stops changing.
 */
@Composable
internal fun PageTranslationBar(
    feature: ServiceTranslate,
    translator: SentenceTranslator,
    state: PageTranslationState,
    theme: ReaderTheme,
    controls: Boolean,
    onRetry: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier,
) {
    val eInk = LocalEInk.current
    val ui = LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    val service by feature.service.collectAsState(initial = null)
    val device = service as? DeviceTranslationService
    val hasDownloads by produceState(false, device) { value = device?.hasDownloads() == true }
    // Null when closed; true opens it on the Services page.
    var settings by remember { mutableStateOf<Boolean?>(null) }
    val halted = state as? PageTranslationState.Halted
    // Back from the system's language downloads, or anything else that may have fixed it.
    // The service looks again first: it remembers the languages it found missing.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (halted != null) {
            scope.launch {
                feature.refresh()
                onRetry()
            }
        }
    }

    val sourceName = translator.source?.let { TranslationLanguages.name(it, ui) }
    val targetName = TranslationLanguages.name(translator.target, ui)
    val name = translator.destination ?: stringResource(R.string.translation_provider_device)
    val status = when (state) {
        PageTranslationState.Translating -> stringResource(R.string.translation_loading)
        PageTranslationState.Ahead -> translator.destination?.let { stringResource(R.string.translation_attribution_service, it) }
            ?: stringResource(R.string.translation_attribution_device)
        PageTranslationState.Ended -> stringResource(R.string.translation_page_ended)
        is PageTranslationState.Halted -> errorSentence(state.error, name, sourceName.orEmpty(), targetName)
    }
    val action = halted?.let { haltAction(it.error, hasDownloads, onRetry, { settings = it }) { scope.launch { device?.openDownloads() } } }

    AnimatedVisibility(
        visible = controls || halted != null,
        enter = if (eInk) EnterTransition.None else fadeIn(),
        exit = if (eInk) ExitTransition.None else fadeOut(),
        modifier = modifier,
    ) {
        ChromeCard(theme = theme) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Icon(Icons.Outlined.Translate, contentDescription = null, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            R.string.translation_page_languages,
                            sourceName ?: stringResource(R.string.translation_detected),
                            targetName,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (halted != null || eInk) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.72f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                action?.let {
                    TextButton(
                        onClick = it.run,
                        colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                    ) { Text(it.label) }
                }
                IconButton(onClick = onStop) {
                    Icon(Icons.Filled.Close, stringResource(R.string.translation_page_stop))
                }
            }
        }
    }

    settings?.let { services ->
        val close: () -> Unit = {
            settings = null
            onRetry()
        }
        Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) { feature.SettingsScreen(onBack = close, services = services) }
        }
    }
}

@Composable
private fun haltAction(
    error: TranslationError,
    hasDownloads: Boolean,
    onRetry: () -> Unit,
    onSettings: (services: Boolean) -> Unit,
    onDownload: () -> Unit,
): HaltAction? = when (error) {
    is TranslationError.NotDownloaded ->
        if (hasDownloads) {
            HaltAction(stringResource(R.string.translation_download), onDownload)
        } else {
            // Nowhere on this phone to get the language from, but another service can still translate.
            HaltAction(stringResource(R.string.translation_choose_service)) { onSettings(false) }
        }
    is TranslationError.Unsupported -> HaltAction(stringResource(R.string.translation_choose_service)) { onSettings(false) }
    is TranslationError.NotSetUp, is TranslationError.LocalNetworkBlocked ->
        HaltAction(stringResource(R.string.translation_open_settings)) { onSettings(false) }
    is TranslationError.InvalidKey -> HaltAction(stringResource(R.string.translation_open_services)) { onSettings(true) }
    else -> HaltAction(stringResource(R.string.read_aloud_settings_server_retry), onRetry)
}
