package com.chmouel.liseur.tts

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.readaloud.ListeningCheckpoints
import com.chmouel.liseur.readaloud.ReadAloudFeature
import com.chmouel.liseur.readaloud.ReadAloudBookNotice
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator

/**
 * Reading aloud with a Gemini voice, one book at a time.
 *
 * Holds the session in progress and keeps [ReadAloudService] running for
 * it, so playback carries on with the reader gone and the screen off.
 * Changing or clearing the key ends the session. Main thread only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class GeminiReadAloud(
    private val application: Application,
    private val keys: GeminiKeyStore,
    private val settings: AppSettingsRepository,
    private val checkpoints: ListeningCheckpoints,
    private val client: GeminiTtsClient = GeminiTtsClient(),
) : ReadAloudFeature {

    private val scope = MainScope()
    private val mutableCurrent = MutableStateFlow<ReadAloudSession?>(null)

    /** The session in progress, for the service. */
    val current: StateFlow<ReadAloudSession?> = mutableCurrent.asStateFlow()

    private var starting: Job? = null

    override val isAvailable = true
    override val configured: StateFlow<Boolean> = keys.configured

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
                val key = keys.get() ?: run {
                    mutableNotices.tryEmit(ReadAloudBookNotice(handle.bookId, ReadAloudNotice.InvalidKey))
                    return@launch
                }
                val voice = GeminiVoice.of(settings.settings.first().readAloudVoice)
                val session = ReadAloudSession(
                    application = application,
                    handle = handle,
                    reader = reader,
                    key = key,
                    voice = voice,
                    client = client,
                    checkpoints = checkpoints,
                    onNotice = { mutableNotices.tryEmit(ReadAloudBookNotice(handle.bookId, it)) },
                    onEnded = { ended -> if (mutableCurrent.value === ended) mutableCurrent.value = null },
                )
                held = false
                mutableCurrent.value = session
                startService()
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

    /** Saves [key], ending the session read with the old one. */
    suspend fun setKey(key: String) {
        stop()
        keys.set(key)
    }

    suspend fun clearKey() {
        stop()
        keys.clear()
    }

    suspend fun setVoice(voice: GeminiVoice) {
        settings.setReadAloudVoice(voice.id)
    }

    val voice: Flow<GeminiVoice> =
        settings.settings.map { GeminiVoice.of(it.readAloudVoice) }.distinctUntilChanged()

    @Composable
    override fun SettingsRows() = ReadAloudSettingsRows(this)

    @Composable
    override fun Player(bookId: String, theme: ReaderTheme, modifier: Modifier) =
        ReadAloudPlayer(this, bookId, theme, modifier)

    @Composable
    override fun SelectionButton(onClick: () -> Unit) = ReadAloudSelectionButton(onClick)
}
