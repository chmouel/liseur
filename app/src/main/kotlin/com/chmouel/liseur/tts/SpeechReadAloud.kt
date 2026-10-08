package com.chmouel.liseur.tts

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import androidx.annotation.StringRes
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator

/**
 * Reading aloud, one book at a time, with the voice of the chosen
 * [SpeechService], among those the build offers.
 *
 * Holds the session in progress and keeps [ReadAloudService] running for
 * it, so playback carries on with the reader gone and the screen off.
 * Changing the service or a key ends the session; a new voice is heard
 * at once, from the start of the sentence being read, a new speed at
 * once, and a new server or model from the next session. Main thread only.
 *
 * @param offered The services offered, given the control they have over
 *   the session; the first is the default.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SpeechReadAloud(
    private val application: Application,
    private val settings: AppSettingsRepository,
    private val checkpoints: ListeningCheckpoints,
    offered: (SessionControl) -> List<SpeechService>,
) : ReadAloudFeature {

    private val scope = MainScope()
    private val mutableCurrent = MutableStateFlow<ReadAloudSession?>(null)

    /** The session in progress, for the service. */
    val current: StateFlow<ReadAloudSession?> = mutableCurrent.asStateFlow()

    private var starting: Job? = null

    override val isAvailable = true

    private val control = object : SessionControl {
        override fun stop() = this@SpeechReadAloud.stop()
        override suspend fun switchVoice() = this@SpeechReadAloud.switchVoice()
    }

    val services: List<SpeechService> = offered(control)

    init {
        require(services.isNotEmpty()) { "Read aloud needs a speech service" }
    }

    /** The stored choice, or the default for none or one this build does not offer. */
    private fun serviceOf(s: AppSettings): SpeechService =
        services.firstOrNull { it.id == s.readAloudProvider } ?: services.first()

    val service: Flow<SpeechService> = settings.settings.map(::serviceOf).distinctUntilChanged()

    override val configured: StateFlow<Boolean> =
        service.flatMapLatest { it.configured }.stateIn(scope, SharingStarted.Eagerly, false)

    /** The service the last session read with, for naming it in a notice. */
    @StringRes
    var noticeLabel: Int = services.first().label
        private set

    /** What names that service in a notice better than its label, such as a server's host; null for the label. */
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
                    speed = { speed.value },
                    checkpoints = checkpoints,
                    onNotice = { mutableNotices.tryEmit(ReadAloudBookNotice(handle.bookId, it)) },
                    onEnded = { ended -> if (mutableCurrent.value === ended) mutableCurrent.value = null },
                    onChapterEnded = { setSleepTimer(null) },
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

    private val mutableSleepTimer = MutableStateFlow<SleepTimer?>(null)

    /** When reading aloud pauses by itself, or null for never. */
    val sleepTimer: StateFlow<SleepTimer?> = mutableSleepTimer.asStateFlow()

    private var sleeping: Job? = null

    init {
        // The timer belongs to the session it was set in.
        scope.launch { mutableCurrent.collect { if (it == null) setSleepTimer(null) } }
    }

    /** Pauses reading aloud in [minutes], or never when null. */
    fun setSleepTimer(minutes: Int?) {
        applySleepTimer(minutes?.let { SleepTimer.starting(it, SystemClock.elapsedRealtime()) })
    }

    fun setSleepTimerToChapterEnd() = applySleepTimer(SleepTimer.EndOfChapter)

    private fun applySleepTimer(timer: SleepTimer?) {
        sleeping?.cancel()
        mutableSleepTimer.value = timer
        mutableCurrent.value?.playback?.stopAtChapterEnd(timer == SleepTimer.EndOfChapter)
        if (timer !is SleepTimer.Timed) return
        sleeping = scope.launch {
            // Measured on the clock that counts deep sleep, which a delay alone does not.
            while (true) {
                val left = timer.endsAt - SystemClock.elapsedRealtime()
                if (left <= 0) break
                delay(left.coerceAtMost(SLEEP_CHECK_MS))
            }
            mutableSleepTimer.value = null
            pause()
        }
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
        val service = serviceOf(s)
        noticeLabel = service.label
        noticeHost = service.noticeName(s)
        return service.voice(s) ?: null.also { mutableNotices.tryEmit(ReadAloudBookNotice(bookId, ReadAloudNotice.NotSetUp)) }
    }

    /**
     * Says [sample] with the chosen service, in [voice] or the one chosen,
     * pausing the book being read. Fails with a [SpeechError], with
     * [IllegalStateException] when the service is not set up, or with the
     * output's own exception when the device cannot play it. Cancelling
     * silences it.
     */
    suspend fun preview(sample: String, voice: String? = null): Result<Unit> {
        pause()
        val s = settings.settings.first()
        val chosen = serviceOf(s).voice(s, voice)
            ?: return Result.failure(IllegalStateException("Read aloud is not set up"))
        val output = AudioTrackPcmOutput({ speed.value })
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

    /** Switches service, ending a session read with another. */
    suspend fun setService(service: SpeechService) {
        if (serviceOf(settings.settings.first()) != service) stop()
        settings.setReadAloudProvider(service.id)
    }

    /** Has the session read on in the voice now saved. */
    private suspend fun switchVoice() {
        val session = mutableCurrent.value ?: return
        val s = settings.settings.first()
        val voice = serviceOf(s).voice(s) ?: return
        // The service may have changed, and the session with it, while the settings were read.
        if (mutableCurrent.value === session) session.switchVoice(voice)
    }

    /** How fast reading aloud plays, one of [ReadAloudSpeed.STEPS]. */
    val speed: StateFlow<Float> = settings.settings.map { ReadAloudSpeed.of(it.readAloudSpeed) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, 1f)

    suspend fun setSpeed(speed: Float) = settings.setReadAloudSpeed(ReadAloudSpeed.of(speed))

    @Composable
    override fun SettingsEntry(onClick: () -> Unit) = ReadAloudSettingsEntry(this, onClick)

    @Composable
    override fun SettingsScreen(onBack: () -> Unit) = ReadAloudSettingsScreen(this, onBack)

    @Composable
    override fun Player(bookId: String, theme: ReaderTheme, controls: Boolean, modifier: Modifier) =
        ReadAloudPlayer(this, bookId, theme, controls, modifier)

    @Composable
    override fun SelectionButton(onClick: () -> Unit) = ReadAloudSelectionButton(onClick)
}

private const val SLEEP_CHECK_MS = 30_000L
