package com.chmouel.liseur.translate

import android.app.ActivityOptions
import android.content.Context
import android.icu.util.ULocale
import android.os.Build
import android.os.CancellationSignal
import android.view.translation.TranslationCapability
import android.view.translation.TranslationContext
import android.view.translation.TranslationManager
import android.view.translation.TranslationRequest
import android.view.translation.TranslationRequestValue
import android.view.translation.TranslationResponse
import android.view.translation.TranslationSpec
import android.view.translation.Translator
import androidx.annotation.RequiresApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.runtime.Composable
import androidx.core.util.isNotEmpty
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.AppSettings
import java.util.concurrent.Executors
import java.util.function.Consumer
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** A pair the device's translator offers, by tags as translation keeps them and as the system named them. */
internal data class DevicePair(
    val source: String,
    val target: String,
    val state: PairState,
    val systemSource: String = source,
    val systemTarget: String = target,
)

/** Reading the device translator's pairs, apart from the platform for testing. */
internal object DevicePairs {
    /** The pairs [capabilities] name, as (source tag, target tag, state); pairs it calls not available are left out. */
    fun of(capabilities: List<Triple<String, String, PairState?>>): List<DevicePair> =
        capabilities.mapNotNull { (source, target, state) ->
            val from = TranslationLanguages.of(source) ?: return@mapNotNull null
            val into = TranslationLanguages.of(target) ?: return@mapNotNull null
            if (state == null || from == into) null else DevicePair(from, into, state, source, target)
        }

    /** The pair from [source] into [target], the most usable when the system lists it twice. */
    fun find(pairs: List<DevicePair>, source: String, target: String): DevicePair? =
        pairs.filter { TranslationLanguages.same(it.source, source) && TranslationLanguages.same(it.target, target) }
            .minByOrNull { it.state.ordinal }

    /** Every language [source] translates into, or any source when null, with the most usable state. */
    fun targets(pairs: List<DevicePair>, source: String?): Map<String, PairState> =
        pairs.filter { source == null || TranslationLanguages.same(it.source, source) }
            .groupBy { it.target }
            .mapValues { (_, same) -> same.minOf { it.state } }

    fun sources(pairs: List<DevicePair>): Set<String> = pairs.mapTo(mutableSetOf()) { it.source }
}

/**
 * The translator Android offers apps from Android 12, when the phone has
 * one, such as Android System Intelligence on Pixels. Nothing leaves the
 * phone; a language pair may first need downloading in the system's
 * settings. The system service can block, so it is only asked off the
 * main thread, and never for long.
 */
internal class DeviceTranslationService(private val context: Context) : TranslationService {
    override val id = ID
    override val label = R.string.translation_provider_device
    override val summary = R.string.translation_provider_device_summary
    override val icon = Icons.Outlined.PhoneAndroid
    override val detectsLanguage = false

    // Null until first asked, then what the system last said.
    private val pairs = MutableStateFlow<List<DevicePair>?>(null)

    // What the system last answered in time; a query that timed out or failed is not kept.
    @Volatile
    private var known: List<DevicePair>? = null

    // Asked the first time; the reader and settings ask again on resuming.
    override val configured: Flow<Boolean> = pairs
        .onStart { if (pairs.value == null) refresh() }
        .filterNotNull()
        .map { it.isNotEmpty() }
        .distinctUntilChanged()

    private val executor = Executors.newSingleThreadExecutor()

    override suspend fun refresh() {
        val found = query(context)
        if (found != null) known = found
        pairs.value = found ?: known ?: emptyList()
    }

    private suspend fun load(): List<DevicePair> =
        known ?: query(context)?.also { known = it; pairs.value = it } ?: emptyList()

    override fun destination(s: AppSettings): String? = null

    override fun owner(s: AppSettings): String? = null

    override suspend fun targets(source: String?): Map<String, PairState> = DevicePairs.targets(load(), source)

    override suspend fun sources(): Set<String> = DevicePairs.sources(load())

    override suspend fun translate(passage: String, source: String?, target: String, context: String?): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) throw TranslationError.Unsupported()
        val pair = ready(source, target)
        return withContext(Dispatchers.IO) {
            val translator = create(pair)
            try {
                translateWith(translator, passage)
            } finally {
                release(translator)
            }
        }
    }

    /** One translator for the whole run, made at the first sentence and destroyed on [TranslationRun.close]. */
    override fun open(source: String?, target: String, s: AppSettings): TranslationRun = object : TranslationRun {
        private val lock = Mutex()
        private var held: Any? = null
        private var closed = false

        override suspend fun translate(sentence: String, context: String?): String = lock.withLock {
            if (closed) throw CancellationException("closed")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) throw TranslationError.Unsupported()
            val pair = ready(source, target)
            withContext(Dispatchers.IO) {
                val translator = held as? Translator ?: create(pair).also { held = it }
                try {
                    translateWith(translator, sentence)
                } catch (e: TranslationError) {
                    // A failing translator is not trusted with the next sentence.
                    held = null
                    release(translator)
                    throw e
                }
            }
        }

        override fun close() {
            closed = true
            // Destroyed once a sentence still being translated lets go of it.
            background.launch {
                lock.withLock {
                    @Suppress("NewApi")
                    (held as? Translator)?.let(::release)
                    held = null
                }
            }
        }
    }

    /** The pair from [source] into [target], once it is downloaded. */
    private suspend fun ready(source: String?, target: String): DevicePair {
        if (source == null) throw TranslationError.Unsupported()
        val pair = DevicePairs.find(load(), source, target) ?: throw TranslationError.Unsupported()
        if (pair.state != PairState.Ready) throw TranslationError.NotDownloaded()
        return pair
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun create(pair: DevicePair): Translator {
        val manager = context.getSystemService(TranslationManager::class.java)
            ?: throw TranslationError.DeviceFailed("no translation service")
        val translationContext = TranslationContext.Builder(
            TranslationSpec(ULocale.forLanguageTag(pair.systemSource), TranslationSpec.DATA_FORMAT_TEXT),
            TranslationSpec(ULocale.forLanguageTag(pair.systemTarget), TranslationSpec.DATA_FORMAT_TEXT),
        ).build()
        return try {
            withTimeout(CREATE_TIMEOUT_MS) {
                suspendCancellableCoroutine<Translator?> { continuation ->
                    manager.createOnDeviceTranslator(
                        translationContext,
                        executor,
                        Consumer { created ->
                            // One made after its caller gave up is destroyed at once.
                            continuation.resume(created) { _, made, _ -> made?.let(::release) }
                        },
                    )
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw TranslationError.DeviceFailed("no translator in time")
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // A system service that is missing or dying answers with an exception.
            throw TranslationError.DeviceFailed(e.javaClass.simpleName)
        } ?: throw TranslationError.DeviceFailed("no translator")
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun translateWith(translator: Translator, passage: String): String {
        try {
            val request = TranslationRequest.Builder()
                .setTranslationRequestValues(listOf(TranslationRequestValue.forText(passage)))
                .build()
            val response = try {
                withTimeout(TRANSLATE_TIMEOUT_MS) {
                    suspendCancellableCoroutine<TranslationResponse> { continuation ->
                        val signal = CancellationSignal()
                        continuation.invokeOnCancellation { signal.cancel() }
                        translator.translate(
                            request,
                            signal,
                            executor,
                            Consumer { response -> if (continuation.isActive) continuation.resume(response) },
                        )
                    }
                }
            } catch (_: TimeoutCancellationException) {
                throw TranslationError.DeviceFailed("no translation in time")
            }
            if (response.translationStatus != TranslationResponse.TRANSLATION_STATUS_SUCCESS) {
                throw TranslationError.DeviceFailed("status ${response.translationStatus}")
            }
            val values = response.translationResponseValues
            val text = (if (values.isNotEmpty()) values.valueAt(0)?.text else null)?.toString()
                ?: throw TranslationError.DeviceFailed("no text")
            return text.trim().ifEmpty { throw TranslationError.Empty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // A system service that is missing or dying answers with an exception.
            throw TranslationError.DeviceFailed(e.javaClass.simpleName)
        }
    }

    // Failing to let go of a translator must not hide the translation or the cancellation, nor crash.
    @RequiresApi(Build.VERSION_CODES.S)
    private fun release(translator: Translator) {
        try {
            translator.destroy()
        } catch (_: RuntimeException) {
        }
    }

    /**
     * Opens the system's screen for downloading languages. False when the
     * phone has none, or it could not be opened.
     */
    suspend fun openDownloads(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val pending = withContext(Dispatchers.IO) {
            try {
                context.getSystemService(TranslationManager::class.java)?.onDeviceTranslationSettingsActivityIntent
            } catch (_: RuntimeException) {
                null
            }
        } ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                pending.send(sendOptions())
            } else {
                pending.send()
            }
            true
        } catch (_: android.app.PendingIntent.CanceledException) {
            false
        }
    }

    /** Whether the phone has a screen for downloading languages, for showing the button at all. */
    suspend fun hasDownloads(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return withContext(Dispatchers.IO) {
            try {
                context.getSystemService(TranslationManager::class.java)?.onDeviceTranslationSettingsActivityIntent != null
            } catch (_: RuntimeException) {
                false
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun sendOptions() = ActivityOptions.makeBasic().apply {
        // The reader is on screen and asked for it; Android 14 wants that said.
        @Suppress("DEPRECATION")
        setPendingIntentBackgroundActivityStartMode(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
            } else {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            },
        )
    }.toBundle()

    @Composable
    override fun SettingsRows(onManageServices: () -> Unit) = DeviceTranslationRows(this)

    companion object {
        const val ID = "device"

        private const val QUERY_TIMEOUT_MS = 5_000L
        // One question at a time: a stuck system call cannot pile up threads, and answers arrive in order.
        private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

        /**
         * Whether this phone has a translator apps can use, whether or not a
         * language is downloaded; null when the system did not answer.
         */
        suspend fun available(context: Context): Boolean? = query(context)?.isNotEmpty()

        /** The device's pairs; null when the system did not answer in time or failed. */
        private suspend fun query(context: Context): List<DevicePair>? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
            // Asked apart, as a stuck system service would hold any thread that asks it.
            val asked = background.async {
                try {
                    capabilities(context)
                } catch (_: RuntimeException) {
                    // A system service that is missing or dying answers with an exception.
                    null
                }
            }
            return withTimeoutOrNull(QUERY_TIMEOUT_MS) { asked.await() } ?: null.also { asked.cancel() }
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun capabilities(context: Context): List<DevicePair> {
            val manager = context.getSystemService(TranslationManager::class.java) ?: return emptyList()
            val found = manager.getOnDeviceTranslationCapabilities(TranslationSpec.DATA_FORMAT_TEXT, TranslationSpec.DATA_FORMAT_TEXT)
            return DevicePairs.of(
                found.map { Triple(it.sourceSpec.locale.toLanguageTag(), it.targetSpec.locale.toLanguageTag(), stateOf(it.state)) },
            )
        }

        private fun stateOf(state: Int): PairState? = when (state) {
            TranslationCapability.STATE_ON_DEVICE -> PairState.Ready
            TranslationCapability.STATE_AVAILABLE_TO_DOWNLOAD, TranslationCapability.STATE_DOWNLOADING -> PairState.NeedsDownload
            else -> null
        }

        private const val CREATE_TIMEOUT_MS = 10_000L
        private const val TRANSLATE_TIMEOUT_MS = 30_000L
    }
}
