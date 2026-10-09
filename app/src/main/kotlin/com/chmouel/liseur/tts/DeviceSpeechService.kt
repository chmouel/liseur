package com.chmouel.liseur.tts

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.ui.settings.RowDivider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The voices of the device's own speech engine, the one chosen in
 * Android's settings. Only voices that work offline are offered, so no
 * text leaves the device; nothing needs setting up.
 */
internal class DeviceSpeechService(
    private val context: Context,
    private val settings: AppSettingsRepository,
    private val control: SessionControl,
) : SpeechService {
    override val id = DEVICE_SPEECH_PROVIDER_ID
    override val label = R.string.read_aloud_provider_device
    override val summary = R.string.read_aloud_provider_device_summary
    override val icon = Icons.Outlined.PhoneAndroid

    private val engine = DeviceTts(context.applicationContext)
    private val mutableConfigured = MutableStateFlow(false)

    override val configured: Flow<Boolean> = flow {
        voices()
        emitAll(mutableConfigured)
    }

    val voice: Flow<String> = settings.settings.map { it.deviceVoice.orEmpty() }.distinctUntilChanged()

    /** The voices last listed, so labels can be shown without asking the engine again. */
    private val listed = MutableStateFlow<List<DeviceVoice>?>(null)

    /** The engine's own default voice, read when [listed] is. */
    val defaultVoice = MutableStateFlow<String?>(null)

    /** The voice the last session or sample read with, for when none is saved. */
    private val used = MutableStateFlow<String?>(null)

    /** The engine's offline voices, or a failure when there is no engine. */
    suspend fun voices(): Result<List<DeviceVoice>> {
        val result = try {
            val voices = engine.voices()
            defaultVoice.value = engine.defaultVoice()
            listed.value = voices
            Result.success(voices)
        } catch (e: SpeechError) {
            Result.failure(e)
        }
        mutableConfigured.value = deviceSpeechConfigured(result)
        return result
    }

    override suspend fun voice(s: AppSettings, voice: String?): SessionVoice? {
        val voices = voices().getOrNull() ?: return null
        val chosen = DeviceVoices.pick(voices, voice ?: s.deviceVoice, defaultVoice.value, Locale.getDefault())
            ?: return null
        used.value = chosen.id
        return SessionVoice(chosen.id, 1) { text -> engine.synthesize(text, chosen.id) }
    }

    /** Saves [voice], also as the one for its language, or the one being read when the engine does not say. */
    suspend fun setVoice(voice: String) {
        val before = settings.settings.first()
        val language = listed.value?.firstOrNull { it.id == voice }?.let(::languagesOf)
        val scope = scope()
        val remembered = scope != null &&
            remember(scope, VoiceResolver.settingsLanguage(language, control.sessionLanguage(this)), voice)
        if (!remembered) settings.setDeviceVoice(voice)
        if (settings.settings.first() != before) control.switchVoice()
    }

    private suspend fun scope(): VoiceScope? = engine.engine()?.let { VoiceScope(id, it, "") }

    override suspend fun catalogue(s: AppSettings): VoiceCatalogue? {
        val voices = voices().getOrNull() ?: return null
        val scope = scope() ?: return null
        return VoiceCatalogue(
            scope = scope,
            voices = voices.map { CatalogueVoice(it.id, languagesOf(it)) },
            default = defaultVoice.value,
            global = s.deviceVoice,
            defaults = DeviceVoices.preferred(voices),
        )
    }

    override suspend fun remember(scope: VoiceScope, language: String?, voice: String): Boolean {
        // The engine is chosen in Android's settings, so it is checked before the write rather than in it.
        if (scope() != scope) return false
        return settings.editReadAloudVoice {
            setDeviceVoice(voice)
            language?.let { remember(scope.preference(it, voice)) }
            true
        }
    }

    @Composable
    override fun voiceLabel(voice: String): String {
        val listed by listed.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { if (listed == null) voices() }
        return listed?.firstOrNull { it.id == voice }?.let { deviceVoiceLabel(it) } ?: voice
    }

    /** The voice reading: the saved one, else the one last used, else the one a session would pick. */
    @Composable
    private fun current(): DeviceVoice? {
        val stored by voice.collectAsStateWithLifecycle("")
        val used by used.collectAsStateWithLifecycle()
        val listed by listed.collectAsStateWithLifecycle()
        val default by defaultVoice.collectAsStateWithLifecycle()
        val locale = LocalConfiguration.current.locales[0]
        LaunchedEffect(Unit) { if (listed == null) voices() }
        return DeviceVoices.pick(listed.orEmpty(), stored.ifBlank { used }, default, locale)
    }

    @Composable
    override fun voiceName(): String = current()?.let { deviceVoiceLabel(it) }.orEmpty()

    @Composable
    override fun SettingsRows(feature: SpeechReadAloud, onManageServices: () -> Unit) = DeviceRows(feature, this)
}

internal fun deviceSpeechConfigured(voices: Result<List<DeviceVoice>>): Boolean =
    voices.getOrNull()?.isNotEmpty() == true

@Composable
private fun deviceVoiceLabel(voice: DeviceVoice): String =
    stringResource(R.string.read_aloud_settings_device_voice_number, voice.number)

private sealed interface DeviceListing {
    data object Loading : DeviceListing
    data class Loaded(val voices: List<DeviceVoice>) : DeviceListing
    data object Failed : DeviceListing
}

@Composable
private fun DeviceRows(feature: SpeechReadAloud, service: DeviceSpeechService) {
    val context = LocalContext.current
    val stored by service.voice.collectAsState(initial = "")
    val default by service.defaultVoice.collectAsState()
    val locale = LocalConfiguration.current.locales[0]
    val preview = rememberVoicePreview(feature)
    val sample = sampleSentence()
    var listing by remember { mutableStateOf<DeviceListing>(DeviceListing.Loading) }
    val scope = rememberCoroutineScope()

    // Coming back from Android's settings may bring new voices.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch {
            listing = service.voices().fold({ DeviceListing.Loaded(it) }, { DeviceListing.Failed })
        }
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(text = stringResource(R.string.read_aloud_settings_voice), style = MaterialTheme.typography.bodyLarge)
        when (val l = listing) {
            DeviceListing.Loading -> Text(
                text = stringResource(R.string.read_aloud_settings_device_voices_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DeviceListing.Failed -> Text(
                text = stringResource(R.string.read_aloud_settings_device_voices_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            is DeviceListing.Loaded -> if (l.voices.isEmpty()) {
                Text(
                    text = stringResource(R.string.read_aloud_settings_device_voices_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    text = stringResource(R.string.read_aloud_settings_voice_pick_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val selected = DeviceVoices.pick(l.voices, stored, default, locale)?.id
                DeviceVoices.grouped(l.voices, locale).forEach { (language, voices) ->
                    LanguageHeader(language, locale)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        voices.forEach { voice ->
                            val speaking = preview.playing == voice.id
                            FilterChip(
                                selected = voice.id == selected,
                                onClick = {
                                    if (speaking) {
                                        preview.stop()
                                    } else {
                                        scope.launch { service.setVoice(voice.id) }
                                        preview.play(sample(language), voice.id)
                                    }
                                },
                                label = { Text(deviceVoiceLabel(voice)) },
                                leadingIcon = {
                                    Icon(
                                        if (speaking) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
        PreviewError(preview, Modifier.padding(top = 4.dp))
    }
    RowDivider()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.read_aloud_settings_device_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                try {
                    context.startActivity(Intent(TTS_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                }
            },
        ) {
            Text(stringResource(R.string.read_aloud_settings_device_open))
        }
    }
}

/** Android's text-to-speech settings, where voices are added; not a public constant. */
private const val TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"

/**
 * One connection to the default speech engine, opened when a voice is
 * needed and closed once nothing has used it for a while. Each sentence
 * is synthesized to a throwaway file, the audio being taken as the engine
 * makes it.
 */
private class DeviceTts(private val context: Context) {
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tts: TextToSpeech? = null
    private var users = 0
    private var closing: Job? = null

    /** Voice and request go together; the engine reads the voice when the request is queued. */
    private val queueing = Mutex()
    private val pending = ConcurrentHashMap<String, Utterance>()
    private val ids = AtomicLong()

    private class Utterance {
        var sampleRate = 0
        var encoding = 0
        var channels = 0
        val audio = ByteArrayOutputStream()
        val done = CompletableDeferred<Unit>()
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = Unit

        override fun onBeginSynthesis(utteranceId: String, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
            pending[utteranceId]?.apply {
                sampleRate = sampleRateInHz
                encoding = audioFormat
                channels = channelCount
            }
        }

        override fun onAudioAvailable(utteranceId: String, audio: ByteArray) {
            val utterance = pending[utteranceId] ?: return
            if (utterance.audio.size() + audio.size > SpeechAudio.MAX_PCM_BYTES * 4) {
                utterance.done.completeExceptionally(SpeechError.InvalidResponse("too much audio"))
            } else {
                utterance.audio.write(audio)
            }
        }

        override fun onDone(utteranceId: String) {
            pending[utteranceId]?.done?.complete(Unit)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) = onError(utteranceId, TextToSpeech.ERROR)

        override fun onError(utteranceId: String, errorCode: Int) {
            pending[utteranceId]?.done?.completeExceptionally(SpeechError.InvalidResponse("engine error $errorCode"))
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) {
            pending[utteranceId]?.done?.completeExceptionally(SpeechError.InvalidResponse("stopped"))
        }
    }

    private suspend fun <T> use(block: suspend (TextToSpeech) -> T): T {
        val engine = lock.withLock {
            closing?.cancel()
            (tts ?: open().also { tts = it }).also { users++ }
        }
        try {
            return block(engine)
        } finally {
            lock.withLock {
                users--
                if (users == 0) {
                    closing = scope.launch {
                        delay(IDLE_MILLIS)
                        lock.withLock {
                            if (users == 0) {
                                tts?.shutdown()
                                tts = null
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun open(): TextToSpeech = withContext(Dispatchers.Main) {
        val status = CompletableDeferred<Int>()
        val engine = TextToSpeech(context) { status.complete(it) }
        if (status.await() != TextToSpeech.SUCCESS) {
            engine.shutdown()
            throw SpeechError.InvalidResponse("no speech engine")
        }
        engine.setOnUtteranceProgressListener(listener)
        engine
    }

    suspend fun voices(): List<DeviceVoice> = use { tts -> DeviceVoices.offline(engineVoices(tts)) }

    /** The package of the engine voices come from, or null when there is no engine. */
    suspend fun engine(): String? = try {
        use { it.defaultEngine }
    } catch (_: SpeechError) {
        null
    }

    suspend fun defaultVoice(): String? = try {
        use { it.defaultVoice?.name }
    } catch (_: SpeechError) {
        null
    }

    suspend fun synthesize(text: String, voiceId: String): SpeechAudio = use { tts ->
        val id = "liseur-${ids.incrementAndGet()}"
        val utterance = Utterance()
        val file = File.createTempFile("speech", ".wav", context.cacheDir)
        pending[id] = utterance
        try {
            queueing.withLock {
                val voice = tts.voices?.firstOrNull { it.name == voiceId } ?: throw SpeechError.InvalidVoice()
                tts.voice = voice
                if (tts.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) {
                    throw SpeechError.InvalidResponse("engine refused the request")
                }
            }
            utterance.done.await()
            val pcm = DeviceVoices.toSpeechPcm(
                utterance.audio.toByteArray(),
                utterance.sampleRate,
                utterance.encoding,
                utterance.channels,
            ) ?: throw SpeechError.InvalidResponse("unknown audio format ${utterance.encoding}")
            if (pcm.isEmpty()) throw SpeechError.InvalidResponse("no audio")
            SpeechAudio(pcm)
        } finally {
            pending.remove(id)
            file.delete()
        }
    }

    private fun engineVoices(tts: TextToSpeech): List<EngineVoice> = tts.voices.orEmpty().map { voice ->
        EngineVoice(
            name = voice.name,
            language = voice.locale.toLanguageTag(),
            needsNetwork = voice.isNetworkConnectionRequired,
            installed = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty(),
        )
    }

    companion object {
        /** Long enough to stay open between sentences and across a short pause. */
        const val IDLE_MILLIS = 60_000L
    }
}

/** What the engine says [voice] speaks, normalized; null when its tag is not a language. */
private fun languagesOf(voice: DeviceVoice): Set<String>? = SpeechLanguage.normalize(voice.language)?.let(::setOf)
