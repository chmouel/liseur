package com.chmouel.liseur.tts

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.readaloud.ListeningCheckpoints
import com.chmouel.liseur.readaloud.ReadAloudBookNotice
import com.chmouel.liseur.readaloud.ReadAloudFeature
import com.chmouel.liseur.readaloud.ReadAloudNotice
import com.chmouel.liseur.readaloud.ReadAloudUi
import com.chmouel.liseur.reader.OpenBookHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import org.readium.r2.shared.publication.Locator

/**
 * Reading aloud, one book at a time, with the voice of the chosen
 * [ReadAloudProvider]: Gemini on the reader's key, or an OpenAI-compatible
 * service of their choice.
 *
 * Holds the session in progress and keeps [ReadAloudService] running for
 * it, so playback carries on with the reader gone and the screen off.
 * Changing the provider or a key ends the session; a new server or voice
 * applies from the next one. Main thread only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SpeechReadAloud(
    private val application: Application,
    private val geminiKeys: ApiKeyStore,
    private val openAiKeys: ApiKeyStore,
    private val settings: AppSettingsRepository,
    private val checkpoints: ListeningCheckpoints,
    private val gemini: GeminiTtsClient = GeminiTtsClient(),
    private val openAi: OpenAiTtsClient = OpenAiTtsClient(),
) : ReadAloudFeature {

    private val scope = MainScope()
    private val mutableCurrent = MutableStateFlow<ReadAloudSession?>(null)

    /** The session in progress, for the service. */
    val current: StateFlow<ReadAloudSession?> = mutableCurrent.asStateFlow()

    private var starting: Job? = null

    override val isAvailable = true

    val provider: Flow<ReadAloudProvider> =
        settings.settings.map { ReadAloudProvider.of(it.readAloudProvider) }.distinctUntilChanged()

    /** Gemini needs a key; an OpenAI-compatible service an address, a model and a voice, its key being optional. */
    override val configured: StateFlow<Boolean> =
        combine(settings.settings, geminiKeys.configured) { s, geminiKey ->
            when (ReadAloudProvider.of(s.readAloudProvider)) {
                ReadAloudProvider.GEMINI -> geminiKey
                ReadAloudProvider.OPENAI ->
                    OpenAiTts.baseUrl(s.speechServerUrl.orEmpty()) != null &&
                        !s.speechServerModel.isNullOrBlank() && !s.speechServerVoice.isNullOrBlank()
            }
        }.stateIn(scope, SharingStarted.Eagerly, false)

    val geminiKeyConfigured: StateFlow<Boolean> = geminiKeys.configured
    val openAiKeyConfigured: StateFlow<Boolean> = openAiKeys.configured

    /** The provider the last session read with, for naming it in a notice. */
    var noticeProvider: ReadAloudProvider = ReadAloudProvider.Default
        private set

    /**
     * The host the last OpenAI-compatible session read from, which names
     * it in a notice better than "OpenAI-compatible" does; null for Gemini.
     */
    var noticeHost: String? = null
        private set

    override val session: StateFlow<ReadAloudUi?> =
        mutableCurrent.flatMapLatest { it?.ui ?: flowOf(null) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    private val mutableNotices = MutableSharedFlow<ReadAloudBookNotice>(extraBufferCapacity = 8)
    override val notices: SharedFlow<ReadAloudBookNotice> = mutableNotices.asSharedFlow()

    override fun start(handle: OpenBookHandle, selection: Locator, reader: Intent) {
        stop()
        starting = scope.launch {
            // Taken here rather than before launching: a start cancelled
            // before it runs never reaches the finally that gives it back.
            if (!handle.acquire()) return@launch
            var held = true
            try {
                val voice = voiceFor(settings.settings.first(), handle.bookId) ?: return@launch
                val session = ReadAloudSession(
                    application = application,
                    handle = handle,
                    reader = reader,
                    voice = voice,
                    checkpoints = checkpoints,
                    onNotice = { mutableNotices.tryEmit(ReadAloudBookNotice(handle.bookId, it)) },
                    onEnded = { ended -> if (mutableCurrent.value === ended) mutableCurrent.value = null },
                )
                held = false
                mutableCurrent.value = session
                // The reads above suspend, so the reader may have left the
                // foreground and Android may refuse the start.
                try {
                    startService()
                } catch (e: RuntimeException) {
                    session.stop()
                    mutableNotices.tryEmit(ReadAloudBookNotice(handle.bookId, ReadAloudNotice.Output))
                    return@launch
                }
                session.start(selection)
            } catch (e: CancellationException) {
                throw e
            } finally {
                if (held) handle.release()
            }
        }
    }

    override fun pause() {
        mutableCurrent.value?.pause()
    }

    override fun resume() {
        val session = mutableCurrent.value ?: return
        // The service may have been destroyed under a paused session.
        startService()
        session.resume()
    }

    // Called from the reader, so in the foreground; the service turns
    // foreground itself once the voice plays.
    private fun startService() {
        application.startService(Intent(application, ReadAloudService::class.java))
    }

    override fun stop() {
        starting?.cancel()
        mutableCurrent.value?.stop()
    }

    override fun skipForward() {
        mutableCurrent.value?.skipForward()
    }

    override fun skipBackward() {
        mutableCurrent.value?.skipBackward()
    }

    /** The voice the next session reads with, or null, with the reason told, when there is none. */
    private suspend fun voiceFor(s: AppSettings, bookId: String): SessionVoice? {
        val provider = ReadAloudProvider.of(s.readAloudProvider)
        noticeProvider = provider
        noticeHost = if (provider == ReadAloudProvider.OPENAI) OpenAiTts.baseUrl(s.speechServerUrl.orEmpty())?.host else null
        return voiceOf(s) ?: null.also { mutableNotices.tryEmit(ReadAloudBookNotice(bookId, ReadAloudNotice.NotSetUp)) }
    }

    /** The chosen provider's voice, or [voice] of that provider instead; null when it is not set up. */
    private suspend fun voiceOf(s: AppSettings, voice: String? = null): SessionVoice? {
        val provider = ReadAloudProvider.of(s.readAloudProvider)
        return when (provider) {
            ReadAloudProvider.GEMINI -> {
                val key = geminiKeys.get() ?: return null
                val name = GeminiVoice.of(voice ?: s.readAloudVoice).id
                SessionVoice(name, provider.maxConcurrent) { text -> gemini.synthesize(key, text, name) }
            }
            ReadAloudProvider.OPENAI -> {
                val base = OpenAiTts.baseUrl(s.speechServerUrl.orEmpty()) ?: return null
                val model = s.speechServerModel?.takeIf { it.isNotBlank() } ?: return null
                val name = (voice ?: s.speechServerVoice)?.takeIf { it.isNotBlank() } ?: return null
                val key = openAiKeys.get()
                SessionVoice(name, provider.maxConcurrent) { text -> openAi.synthesize(base, key, text, name, model) }
            }
        }
    }

    /**
     * Says [sample] with the chosen provider, in [voice] or the one chosen,
     * pausing the book being read. Fails with a [SpeechError], with
     * [IllegalStateException] when the provider is not set up, or with the
     * output's own exception when the device cannot play it. Cancelling
     * silences it.
     */
    suspend fun preview(sample: String, voice: String? = null): Result<Unit> {
        pause()
        val chosen = voiceOf(settings.settings.first(), voice)
            ?: return Result.failure(IllegalStateException("Read aloud is not set up"))
        val output = AudioTrackPcmOutput()
        return try {
            output.play(chosen.synthesizer.synthesize(sample).pcm)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // AudioTrack throws its own exceptions besides ours.
            Result.failure(e)
        } finally {
            output.release()
        }
    }

    /** Switches provider, ending a session read with the other one. */
    suspend fun setProvider(provider: ReadAloudProvider) {
        if (ReadAloudProvider.of(settings.settings.first().readAloudProvider) != provider) stop()
        settings.setReadAloudProvider(provider.id)
    }

    /** Saves the Gemini [key], ending the session read with the old one. */
    suspend fun setGeminiKey(key: String) {
        stop()
        geminiKeys.set(key)
    }

    suspend fun clearGeminiKey() {
        stop()
        geminiKeys.clear()
    }

    suspend fun setGeminiVoice(voice: GeminiVoice) {
        settings.setReadAloudVoice(voice.id)
    }

    val geminiVoice: Flow<GeminiVoice> =
        settings.settings.map { GeminiVoice.of(it.readAloudVoice) }.distinctUntilChanged()

    val openAiUrl: Flow<String> = settings.settings.map { it.speechServerUrl.orEmpty() }.distinctUntilChanged()

    val openAiModel: Flow<String> = settings.settings.map { it.speechServerModel.orEmpty() }.distinctUntilChanged()

    val openAiVoice: Flow<String> = settings.settings.map { it.speechServerVoice.orEmpty() }.distinctUntilChanged()

    suspend fun setOpenAiUrl(url: String) = settings.setSpeechServerUrl(url)

    suspend fun setOpenAiModel(model: String) = settings.setSpeechServerModel(model)

    suspend fun setOpenAiVoice(voice: String) = settings.setSpeechServerVoice(voice)

    /** Saves the service's [key], ending the session read with the old one. */
    suspend fun setOpenAiKey(key: String) {
        stop()
        openAiKeys.set(key)
    }

    suspend fun clearOpenAiKey() {
        stop()
        openAiKeys.clear()
    }

    /** The models the service at [url] lists that look like they speak, asked with the saved key. */
    suspend fun openAiModels(url: String): Result<List<String>> =
        ask(url) { base, key -> OpenAiTts.speechModels(openAi.models(base, key)) }

    /** The voices the service at [url] offers, asked with the saved key. */
    suspend fun openAiVoices(url: String): Result<List<String>> = ask(url) { base, key -> openAi.voices(base, key) }

    private suspend fun ask(url: String, block: suspend (HttpUrl, String?) -> List<String>): Result<List<String>> {
        val base = OpenAiTts.baseUrl(url) ?: return Result.failure(IllegalArgumentException("Not a server address"))
        return try {
            Result.success(block(base, openAiKeys.get()))
        } catch (e: SpeechError) {
            Result.failure(e)
        }
    }

    @Composable
    override fun SettingsEntry(onClick: () -> Unit) = ReadAloudSettingsEntry(this, onClick)

    @Composable
    override fun SettingsScreen(onBack: () -> Unit) = ReadAloudSettingsScreen(this, onBack)

    @Composable
    override fun Player(bookId: String, theme: ReaderTheme, modifier: Modifier) =
        ReadAloudPlayer(this, bookId, theme, modifier)

    @Composable
    override fun SelectionButton(onClick: () -> Unit) = ReadAloudSelectionButton(onClick)
}
