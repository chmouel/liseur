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
import com.chmouel.liseur.data.settings.VoicePreference
import com.chmouel.liseur.readaloud.ListeningCheckpoints
import com.chmouel.liseur.readaloud.ReadAloudBookNotice
import com.chmouel.liseur.readaloud.ReadAloudFeature
import com.chmouel.liseur.readaloud.ReadAloudNotice
import com.chmouel.liseur.readaloud.ReadAloudUi
import com.chmouel.liseur.reader.OpenBookHandle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
 *   the session; device speech is the default when offered.
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
    private var startingBookId: String? = null
    private var startingToken: Any? = null

    override val isAvailable = true

    private val control = object : SessionControl {
        override fun stop() = this@SpeechReadAloud.stop()
        override suspend fun switchVoice() = this@SpeechReadAloud.switchVoice()
        override fun sessionLanguage(service: SpeechService): String? =
            reading()?.takeIf { it.service == service }?.session?.choice?.value?.language
    }

    /** The session in progress, with the service it reads with and the language its book declares. */
    private class Reading(val session: ReadAloudSession, val service: SpeechService, val book: BookLanguage)

    private var reading: Reading? = null

    private fun reading(): Reading? = reading?.takeIf { it.session === mutableCurrent.value }

    /** The voice and language the session reads [bookId] with, which the settings may no longer hold. */
    fun sessionChoice(bookId: String): Flow<VoicePick?> = mutableCurrent.flatMapLatest { session ->
        if (session?.handle?.bookId == bookId) session.choice else flowOf(null)
    }

    private val mutablePending = MutableStateFlow<PendingChoice?>(null)

    /** A book waiting for the reader to pick the language or voice it is read in; nothing is spoken until then. */
    val pendingChoice: StateFlow<PendingChoice?> = mutablePending.asStateFlow()

    val services: List<SpeechService> = offered(control)
    private val providerIds: List<String> = services.map { it.id }

    init {
        require(services.isNotEmpty()) { "Read aloud needs a speech service" }
    }

    /** The stored choice, or the default for none or one this build does not offer. */
    private fun serviceOf(s: AppSettings): SpeechService =
        services.first { it.id == speechProviderId(providerIds, s.readAloudProvider) }

    val service: Flow<SpeechService> = settings.settings.map(::serviceOf).distinctUntilChanged()

    init {
        // A choice asked of one service means nothing to another.
        scope.launch {
            service.collect { chosen -> if (mutablePending.value.let { it != null && it.service != chosen }) starting?.cancel() }
        }
    }

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
        val startToken = Any()
        startingToken = startToken
        startingBookId = handle.bookId
        starting = scope.launch {
            // Taken here rather than before launching: a start cancelled
            // before it runs never reaches the finally that gives it back.
            if (!handle.acquire()) return@launch
            var held = true
            try {
                val s = settings.settings.first()
                val service = serviceOf(s)
                noticeLabel = service.label
                noticeHost = service.noticeName(s)
                val book = SpeechLanguage.ofBook(handle.publication.metadata.languages)
                val catalogue = service.catalogue(s) ?: return@launch notSetUp(handle.bookId)
                val pick = when (val resolved = VoiceResolver.resolve(book, catalogue, s.voicePreferences)) {
                    is VoiceResolution.Resolved -> resolved.pick
                    is VoiceResolution.NeedsChoice -> ask(PendingChoice(handle.bookId, service, book, resolved.reason))
                }
                val voice = service.voice(settings.settings.first(), pick.voice) ?: return@launch notSetUp(handle.bookId)
                val session = ReadAloudSession(
                    application = application,
                    handle = handle,
                    reader = reader,
                    voice = voice,
                    language = pick.language,
                    speed = { speed.value },
                    checkpoints = checkpoints,
                    onNotice = { mutableNotices.tryEmit(ReadAloudBookNotice(handle.bookId, it)) },
                    onEnded = { ended -> if (mutableCurrent.value === ended) mutableCurrent.value = null },
                    onChapterEnded = { setSleepTimer(null) },
                )
                held = false
                reading = Reading(session, service, book)
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
                if (startingToken === startToken) {
                    startingToken = null
                    startingBookId = null
                }
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

    private fun notSetUp(bookId: String) {
        mutableNotices.tryEmit(ReadAloudBookNotice(bookId, ReadAloudNotice.NotSetUp))
    }

    /** Waits for the reader to answer [pending]; cancelling the start takes the question away. */
    private suspend fun ask(pending: PendingChoice): VoicePick {
        mutablePending.value = pending
        try {
            return pending.answer.await()
        } finally {
            mutablePending.compareAndSet(pending, null)
        }
    }

    /** Gives up starting [bookId] while its language or voice is being asked, leaving its place as it was. */
    override fun cancelChoice(bookId: String) {
        if (startingBookId == bookId) starting?.cancel()
    }

    /**
     * What the voice sheet offers for [bookId]: the service's voices, the
     * languages to pick among, and what is picked now. Null when the
     * service is not set up.
     */
    suspend fun voiceSheet(bookId: String): VoiceSheet? {
        val s = settings.settings.first()
        val pending = mutablePending.value?.takeIf { it.bookId == bookId }
        val reading = reading()?.takeIf { it.session.handle.bookId == bookId }
        val service = pending?.service ?: reading?.service ?: serviceOf(s)
        val book = pending?.book ?: reading?.book ?: BookLanguage.Missing
        val catalogue = service.catalogue(s) ?: return null
        val preferences = s.voicePreferences.filter(catalogue.scope::owns)
        val choice = reading?.session?.choice?.value
        val language = choice?.language ?: (book as? BookLanguage.Known)?.tag
        val reason = pending?.reason ?: if (catalogue.failed && catalogue.voices.isEmpty()) ChoiceReason.CatalogueFailed else null
        return VoiceSheet(
            bookId = bookId,
            service = service,
            catalogue = catalogue,
            preferences = preferences,
            book = book,
            languages = VoiceResolver.languages(
                catalogue,
                preferences,
                book,
                choice?.language,
                SpeechLanguage.normalize(Locale.getDefault().toLanguageTag()),
            ),
            language = language?.let(SpeechLanguage::primary),
            choice = choice,
            reason = reason,
            pending = pending,
            session = reading?.session,
        )
    }

    /**
     * Reads [sheet]'s book in [voice] for [language], a primary language,
     * and remembers it for that language. A book waiting to start starts
     * with it; one being read reads on in it from the start of the
     * sentence, playing or paused as it was. False when the service, its
     * server or its model changed since the sheet was shown, so nothing was
     * saved and the sheet must be shown again.
     */
    suspend fun applyVoice(sheet: VoiceSheet, language: String, voice: String): Boolean {
        if (!sheet.service.remember(sheet.catalogue.scope, language, voice)) return false
        val tag = sheet.choice?.language?.takeIf { SpeechLanguage.primary(it) == language }
            ?: VoiceResolver.sessionTag(language, sheet.book, sheet.catalogue.voices.firstOrNull { it.id == voice })
        val pick = VoicePick(voice, tag)
        val pending = sheet.pending
        if (pending != null) {
            pending.answer.complete(pick)
            return true
        }
        val reading = reading()?.takeIf { it.session === sheet.session && it.service == sheet.service } ?: return true
        if (reading.session.choice.value == pick) return true
        val switch = ++switches
        val chosen = reading.service.voice(settings.settings.first(), voice) ?: return true
        if (reading() === reading && switches == switch) reading.session.switchVoice(chosen, tag)
        return true
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

    /** Bumped by every voice change, so a slow one never undoes a later one. */
    private var switches = 0

    /**
     * Has the session read on in the voice now saved for its language,
     * staying in that language; nothing changes when the service has none
     * for it.
     */
    private suspend fun switchVoice() {
        val reading = reading() ?: return
        val switch = ++switches
        val s = settings.settings.first()
        if (serviceOf(s) != reading.service) return
        val language = reading.session.choice.value.language
        val catalogue = reading.service.catalogue(s) ?: return
        val name = VoiceResolver.voiceFor(language, catalogue, s.voicePreferences) ?: return
        val voice = reading.service.voice(s, name) ?: return
        // The service may have changed, and the session with it, while the voices were listed.
        if (reading() === reading && switches == switch) reading.session.switchVoice(voice)
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

internal const val DEVICE_SPEECH_PROVIDER_ID = "device"

/** A saved choice wins; otherwise prefer on-device speech over network services. */
internal fun speechProviderId(available: List<String>, saved: String?): String {
    require(available.isNotEmpty()) { "Read aloud needs a speech service" }
    return saved?.takeIf(available::contains)
        ?: DEVICE_SPEECH_PROVIDER_ID.takeIf(available::contains)
        ?: available.first()
}

/**
 * A start waiting for the reader to say which language [bookId] is read in,
 * or with which voice, and why it could not be decided alone.
 */
internal class PendingChoice(
    val bookId: String,
    val service: SpeechService,
    val book: BookLanguage,
    val reason: ChoiceReason,
) {
    val answer = CompletableDeferred<VoicePick>()
}

/** What the voice sheet shows: see [SpeechReadAloud.voiceSheet]. */
internal class VoiceSheet(
    val bookId: String,
    val service: SpeechService,
    val catalogue: VoiceCatalogue,
    /** The voices remembered in the catalogue's scope. */
    val preferences: List<VoicePreference>,
    val book: BookLanguage,
    /** Primary languages, the book's and the session's first. */
    val languages: List<String>,
    /** The primary language picked to begin with, or null when there is none to suggest. */
    val language: String?,
    /** What the session reads with now, if there is one. */
    val choice: VoicePick?,
    val reason: ChoiceReason?,
    val pending: PendingChoice?,
    val session: ReadAloudSession?,
)
