package com.chmouel.liseur.translate

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.GTranslate
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.LocalEInk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Google's ML Kit, translating on the phone with language models it
 * downloads once the reader agrees, over Wi-Fi. Passages never leave the
 * phone; ML Kit itself reports usage to Google, which the privacy notes say.
 */
internal class MlKitTranslationService(
    private val engine: MlKitEngine,
    private val onWifi: () -> Boolean,
    private val background: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : TranslationService {
    constructor(context: Context) : this(SdkMlKitEngine(context), { onWifi(context.applicationContext) })

    override val id = ID
    override val label = R.string.translation_provider_mlkit
    override val summary = R.string.translation_provider_mlkit_summary
    override val icon = Icons.Outlined.GTranslate
    override val detectsLanguage = false
    override val configured: Flow<Boolean> = flowOf(true)
    override val credited = true
    override val action = R.string.translation_mlkit_action
    override val pageAction = R.string.translation_mlkit_page_action
    override val consent = R.string.translation_mlkit_consent

    internal val codes: Set<String> by lazy { engine.languages() }

    /** What is on the phone, as last asked; null until then. */
    internal val models = MutableStateFlow<Set<String>?>(null)

    /** What is being done to each language, by ML Kit code, kept while the reader is elsewhere. */
    internal val changes = MutableStateFlow<Map<String, Change>>(emptyMap())

    // A deletion and a download are not run over each other.
    private val changing = Mutex()

    // Listings are taken one after another, so an older one never lands last.
    private val listing = Mutex()

    /** Asks ML Kit what is on the phone. Throws [TranslationError.Failed]. */
    internal suspend fun load(): Set<String> = listing.withLock {
        try {
            engine.downloaded().also { have ->
                models.value = have
                // A change can fail only in confirming it; a listing that shows it done settles that.
                changes.update { now ->
                    now.filterNot { (code, change) -> change is Change.Failed && (change.why == Change.Why.Delete) != (code in have) }
                }
            }
        } catch (e: TranslationError) {
            // A failed listing leaves nothing known, so the next question asks again.
            models.value = null
            throw e
        }
    }

    override suspend fun refresh() {
        try {
            load()
        } catch (e: TranslationError) {
            // Translating asks again and reports the failure.
        }
    }

    private suspend fun downloaded(): Set<String> = models.value ?: load()

    override fun destination(s: AppSettings): String? = null

    override fun owner(s: AppSettings): String? = null

    override suspend fun targets(source: String?): Map<String, PairState> {
        // When ML Kit cannot list its models, every target is offered as ready:
        // translating then asks again and shows the failure with a retry.
        val have = try {
            downloaded()
        } catch (e: TranslationError) {
            codes
        }
        return MlKitLanguages.targets(codes, have, source)
    }

    override suspend fun sources(): Set<String> = MlKitLanguages.sources(codes)

    override suspend fun hasDownloads() = true

    override suspend fun translate(passage: String, source: String?, target: String, context: String?, book: String?): String {
        val (from, to) = ready(source, target)
        val translator = engine.open(from, to)
        try {
            return within { translator.translate(passage) }
        } finally {
            translator.close()
        }
    }

    /** One translator for the whole run, made at the first sentence and closed with the run. */
    override fun open(source: String?, target: String, s: AppSettings): TranslationRun = object : TranslationRun {
        private val lock = Mutex()
        private var held: MlKitTranslator? = null
        private var closed = false

        override suspend fun translate(sentence: String, context: String?, book: String?): String = lock.withLock {
            if (closed) throw CancellationException("closed")
            try {
                // Asked at each sentence, so a language deleted mid-run stops it.
                val (from, to) = ready(source, target)
                val translator = held ?: engine.open(from, to).also { held = it }
                within { translator.translate(sentence) }
            } catch (e: TranslationError) {
                // A failing translator, or one whose language went away, is not
                // trusted with the next sentence.
                held?.close()
                held = null
                throw e
            }
        }

        override fun close() {
            closed = true
            // Closed once a sentence still being translated lets go of it.
            background.launch {
                lock.withLock {
                    held?.close()
                    held = null
                }
            }
        }
    }

    /** The ML Kit pair for [source] into [target], once both models are on the phone. Never downloads. */
    private suspend fun ready(source: String?, target: String): Pair<String, String> {
        val pair = MlKitLanguages.pair(codes, source, target) ?: throw TranslationError.Unsupported()
        val have = downloaded()
        if (pair.first !in have || pair.second !in have) throw TranslationError.NotDownloaded()
        return pair
    }

    private suspend fun within(block: suspend () -> String): String {
        val text = try {
            withTimeout(TRANSLATE_TIMEOUT_MS) { block() }
        } catch (e: TimeoutCancellationException) {
            throw TranslationError.Failed("timed out")
        } catch (e: TranslationError.NotDownloaded) {
            refresh()
            throw e
        }
        return text.ifBlank { throw TranslationError.Empty() }
    }

    /**
     * Fetches what [source] into [target] needs. Throws [MlKitDownloadFailed].
     * The work runs on in [background]: a caller that stops waiting leaves
     * ML Kit's download, which cannot be called back, still guarded and
     * followed by a fresh listing.
     */
    internal suspend fun download(source: String, target: String) =
        guarded(listOf(source, target).filter { it != BUILT_IN && it !in models.value.orEmpty() }, Change.Downloading) {
            for (code in listOf(source, target)) engine.download(code)
        }

    internal suspend fun download(code: String) = guarded(listOf(code), Change.Downloading) { engine.download(code) }

    internal suspend fun delete(code: String) = guarded(listOf(code), Change.Deleting) { engine.delete(code) }

    /** Starts fetching [code] for the languages page; how it goes shows in [changes]. */
    internal fun fetch(code: String) {
        if (!onWifi()) {
            mark(listOf(code), Change.Failed(Change.Why.Wifi))
            return
        }
        mark(listOf(code), Change.Waiting)
        background.launch {
            try {
                download(code)
            } catch (e: Exception) {
                // Shown in changes.
            }
        }
    }

    /** Starts deleting [code] for the languages page; how it goes shows in [changes]. */
    internal fun remove(code: String) {
        mark(listOf(code), Change.Waiting)
        background.launch {
            try {
                delete(code)
            } catch (e: Exception) {
                // Shown in changes.
            }
        }
    }

    private fun mark(codes: List<String>, change: Change?) = changes.update { now ->
        if (change == null) now - codes.toSet() else now + codes.associateWith { change }
    }

    private suspend fun guarded(marked: List<String>, doing: Change, work: suspend () -> Unit) = background.async {
        mark(marked, Change.Waiting)
        try {
            changing.withLock {
                mark(marked, doing)
                try {
                    work()
                } catch (e: Throwable) {
                    refresh()
                    throw e
                }
                // Done only once ML Kit says so: a failed listing fails the change.
                load()
            }
            mark(marked, null)
        } catch (e: CancellationException) {
            mark(marked, null)
            throw e
        } catch (e: Exception) {
            val why = when {
                doing == Change.Deleting -> Change.Why.Delete
                e is MlKitDownloadFailed && e.reason == MlKitDownloadFailed.Reason.Space -> Change.Why.Space
                else -> Change.Why.Download
            }
            mark(marked, Change.Failed(why))
            throw e
        }
        Unit
    }.await()

    @Composable
    override fun Downloads(source: String?, target: String, onReady: () -> Unit, onDismiss: () -> Unit) {
        val pair = remember(source, target) { MlKitLanguages.pair(codes, source, target) }
        if (pair == null) {
            LaunchedEffect(Unit) { onDismiss() }
            return
        }
        val scope = rememberCoroutineScope()
        var stage by remember(pair) { mutableStateOf<Stage>(Stage.Asking) }
        var missing by remember(pair) { mutableStateOf<List<String>?>(null) }
        LaunchedEffect(pair) {
            missing = try {
                val have = downloaded()
                pair.toList().filter { it !in have }
            } catch (e: TranslationError) {
                stage = Stage.Unlisted
                pair.toList()
            }
            if (missing?.isEmpty() == true) onReady()
        }
        val names = missing ?: return
        if (names.isEmpty()) return
        val ui = LocalConfiguration.current.locales[0]
        val listed = names.joinToString { TranslationLanguages.name(MlKitLanguages.tag(it), ui) }
        val go: () -> Unit = {
            if (!onWifi()) {
                stage = Stage.NoWifi
            } else {
                stage = Stage.Downloading
                scope.launch {
                    stage = try {
                        download(pair.first, pair.second)
                        onReady()
                        Stage.Asking
                    } catch (e: MlKitDownloadFailed) {
                        Stage.Failed(e.reason)
                    } catch (e: TranslationError) {
                        Stage.Failed(MlKitDownloadFailed.Reason.Other)
                    }
                }
            }
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.translation_mlkit_download_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.translation_mlkit_download_body, listed))
                    when (val now = stage) {
                        Stage.Asking -> Unit
                        Stage.NoWifi -> Text(stringResource(R.string.translation_mlkit_download_wifi), color = MaterialTheme.colorScheme.error)
                        Stage.Unlisted -> Text(stringResource(R.string.translation_mlkit_models_failed), color = MaterialTheme.colorScheme.error)
                        Stage.Downloading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            BusyIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.translation_mlkit_downloading))
                        }
                        is Stage.Failed -> Text(
                            stringResource(
                                when (now.reason) {
                                    MlKitDownloadFailed.Reason.Space -> R.string.translation_mlkit_download_space
                                    else -> R.string.translation_mlkit_download_failed
                                },
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                if (stage != Stage.Downloading) {
                    TextButton(onClick = go) { Text(stringResource(R.string.translation_mlkit_download_confirm)) }
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
    }

    private sealed interface Stage {
        data object Asking : Stage
        data object NoWifi : Stage
        data object Unlisted : Stage
        data object Downloading : Stage
        data class Failed(val reason: MlKitDownloadFailed.Reason) : Stage
    }

    @Composable
    override fun Page(target: String, onBack: () -> Unit) = MlKitLanguagesPage(this, target, onBack)

    @Composable
    override fun Credit(background: Color, modifier: Modifier) {
        val badge = when {
            background.luminance() < 0.5f -> R.drawable.mlkit_credit_white
            LocalEInk.current -> R.drawable.mlkit_credit_greyscale
            else -> R.drawable.mlkit_credit_color
        }
        Image(
            painter = painterResource(badge),
            contentDescription = stringResource(R.string.translation_mlkit_credit),
            modifier = modifier.height(16.dp),
        )
    }

    @Composable
    override fun SettingsRows(onManageServices: () -> Unit, onOpenPage: () -> Unit) {
        val have by models.collectAsState()
        val busy by changes.collectAsState()
        var failed by remember { mutableStateOf(false) }
        var legal by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            failed = try {
                load()
                false
            } catch (e: TranslationError) {
                true
            }
        }
        val ui = LocalConfiguration.current.locales[0]
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.translation_mlkit_help), style = MaterialTheme.typography.bodyMedium, color = muted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Credit(MaterialTheme.colorScheme.surface, Modifier)
                TextButton(onClick = { legal = true }) { Text(stringResource(R.string.translation_mlkit_legal)) }
            }
        }
        val downloading = busy.filterValues { it == Change.Downloading || it == Change.Waiting }.keys
            .filter { it !in have.orEmpty() }
        ListItem(
            modifier = Modifier.clickable(role = Role.Button, onClick = onOpenPage),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(Icons.Outlined.Language, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.translation_mlkit_languages)) },
            supportingContent = {
                Text(
                    when {
                        downloading.isNotEmpty() -> stringResource(
                            R.string.translation_mlkit_languages_downloading,
                            downloading.joinToString { TranslationLanguages.name(MlKitLanguages.tag(it), ui) },
                        )
                        failed -> stringResource(R.string.translation_mlkit_models_failed)
                        else -> have?.let { stringResource(R.string.translation_mlkit_languages_summary, it.size, (codes - it).size) }.orEmpty()
                    },
                    color = if (failed && downloading.isEmpty()) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            },
            trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
        )
        if (legal) {
            val uri = LocalUriHandler.current
            AlertDialog(
                onDismissRequest = { legal = false },
                title = { Text(stringResource(R.string.translation_mlkit_legal)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.translation_mlkit_acknowledgement))
                        TextButton(onClick = { uri.openUri(GOOGLE_TRANSLATE) }) { Text(stringResource(R.string.translation_mlkit_cloud)) }
                        Text(stringResource(R.string.translation_mlkit_disclaimer), style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = { legal = false }) { Text(stringResource(R.string.close)) } },
                dismissButton = { TextButton(onClick = { uri.openUri(TERMS) }) { Text(stringResource(R.string.translation_mlkit_terms)) } },
            )
        }
    }

    companion object {
        const val ID = "mlkit"

        // ML Kit ships English and keeps it; asking to delete it does nothing.
        internal const val BUILT_IN = "en"
        private const val TRANSLATE_TIMEOUT_MS = 30_000L
        private const val TERMS = "https://developers.google.com/ml-kit/terms"
        private const val GOOGLE_TRANSLATE = "https://cloud.google.com/translate"

        private fun onWifi(context: Context): Boolean {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
            // A VPN hides the network under it; any Wi-Fi with internet will do,
            // and ML Kit's own Wi-Fi condition still holds the download to it.
            @Suppress("DEPRECATION")
            return manager.allNetworks.any { network ->
                manager.getNetworkCapabilities(network)?.let {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                } == true
            }
        }
    }
}

/** A language on its way to or off the phone, or what stopped it. */
internal sealed interface Change {
    data object Waiting : Change
    data object Downloading : Change
    data object Deleting : Change
    data class Failed(val why: Why) : Change

    enum class Why { Wifi, Space, Download, Delete }
}
