package com.chmouel.liseur.readaloud

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.reader.OpenBookHandle
import com.chmouel.liseur.tts.SpeechTranslation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.readium.r2.shared.publication.Locator

/** What the reader shows of the book being read aloud. */
data class ReadAloudUi(
    val bookId: String,
    /** Whether the voice is meant to be speaking: false once paused or failed. */
    val playing: Boolean,
    /** The sentence being read, for highlighting and following the voice. */
    val utterance: Locator?,
    /** Waiting for the voice: the first sentence, or a translation or audio that has not arrived yet. */
    val preparing: Boolean = false,
    /** Read in translation: the voice says the translated page, and the page stays translated. */
    val translated: Boolean = false,
)

/** Something the reader should tell the listener about, once. */
enum class ReadAloudNotice {
    /** The speech service could not be reached; paused on the sentence that failed. */
    Network,

    /** The key's quota is used up; paused on the sentence that failed. */
    RateLimited,

    /** The speech service failed or answered without audio; paused on the sentence that failed. */
    Service,

    /** The device could not play the audio; paused. */
    Output,

    /** The speech service rejected the key; reading aloud stopped. */
    InvalidKey,

    /** The speech service has no such voice; reading aloud stopped. */
    InvalidVoice,

    /** The speech service wants the model's terms accepted on its website first; reading aloud stopped. */
    TermsRequired,

    /** The server makes no speech, as a text-only one does not; reading aloud stopped. */
    NoSpeech,

    /** The chosen service has no key, server or voice yet; nothing was read. */
    NotSetUp,

    /** The selected sentence was not found; reading from the start of its paragraph. */
    SelectionNotFound,

    /** The book could not be read aloud from there. */
    Unavailable,

    /** A sentence of a translated book could not be translated; paused on it until retried or skipped. */
    TranslationFailed,
}

/** A [notice] about [bookId], shown only in that book's reader. */
data class ReadAloudBookNotice(val bookId: String, val notice: ReadAloudNotice)

/**
 * Reading aloud, as the flavor-neutral reader sees it. Both builds provide
 * device voices and a speech server; the Play build adds Gemini. [None] is
 * never available and draws nothing, for callers given no feature.
 */
interface ReadAloudFeature {
    val isAvailable: Boolean

    /** Whether the chosen voice is set up, so a book can be read aloud at all. */
    val configured: StateFlow<Boolean>

    /** The session in progress, whichever book it is reading. */
    val session: StateFlow<ReadAloudUi?>

    val notices: SharedFlow<ReadAloudBookNotice>

    /**
     * Reads [handle]'s book aloud from the sentence [selection] starts in,
     * ending any other session. The session takes its own hold on the book;
     * [reader] reopens this book from the playback notification. With a
     * [translation], whose hold the session takes over and lets go of when
     * it ends, the book is read in translation.
     */
    fun start(handle: OpenBookHandle, selection: Locator, reader: Intent, translation: SpeechTranslation? = null)

    /**
     * Puts back, paused at [selection], the session [record] kept when the
     * app closed, while nothing else is being read. It saves nothing until
     * the listener plays, and gives up quietly unless it lands on the very
     * sentence heard.
     */
    fun restorePaused(handle: OpenBookHandle, record: PausedReadAloud, selection: Locator, reader: Intent) = Unit

    /**
     * The translation [bookId] is being read aloud in, for a translated
     * page to share, or null when it is not being read translated.
     */
    fun translation(bookId: String): SpeechTranslation? = null

    /**
     * Gives up a start of [bookId] still waiting for its language or voice
     * to be chosen, as its reader is going; its place stays as it was.
     */
    fun cancelChoice(bookId: String) = Unit

    /**
     * Pauses. On the main thread, the place heard so far is queued and
     * handed back to the reader before this returns, so a move the
     * reader makes next is saved after it.
     */
    fun pause()

    /** Plays on; after a failure, from the sentence that failed. */
    fun resume()
    fun stop()
    fun skipForward()
    fun skipBackward()

    /** The row on the main settings screen that opens [SettingsScreen]. */
    @Composable
    fun SettingsEntry(onClick: () -> Unit)

    /** Read aloud's own settings screen. */
    @Composable
    fun SettingsScreen(onBack: () -> Unit)

    /**
     * The player laid over [bookId]'s page while it is being read aloud,
     * painted in the reading [theme], with whatever the listener should
     * be told. Draws nothing for another book or no session. The controls
     * show only while [controls] is set, which is when the reader has
     * raised the chrome; otherwise the page shows just the highlight.
     * [onReturnToVoice], set while the reader has moved away from the
     * sentence being read, offers to take the page back to it. Reading
     * that has to start again, as after changing the service from its
     * voice sheet, goes through the reader's own [start].
     */
    @Composable
    fun Player(
        bookId: String,
        theme: ReaderTheme,
        controls: Boolean,
        onReturnToVoice: (() -> Unit)?,
        modifier: Modifier,
        start: (Locator, SpeechTranslation?) -> Unit,
    )

    object None : ReadAloudFeature {
        override val isAvailable = false
        override val configured: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
        override val session: StateFlow<ReadAloudUi?> = MutableStateFlow<ReadAloudUi?>(null).asStateFlow()
        override val notices: SharedFlow<ReadAloudBookNotice> = MutableSharedFlow<ReadAloudBookNotice>().asSharedFlow()

        override fun start(handle: OpenBookHandle, selection: Locator, reader: Intent, translation: SpeechTranslation?) {
            translation?.translations?.release()
        }
        override fun pause() = Unit
        override fun resume() = Unit
        override fun stop() = Unit
        override fun skipForward() = Unit
        override fun skipBackward() = Unit

        @Composable
        override fun SettingsEntry(onClick: () -> Unit) = Unit

        @Composable
        override fun SettingsScreen(onBack: () -> Unit) = Unit

        @Composable
        override fun Player(
            bookId: String,
            theme: ReaderTheme,
            controls: Boolean,
            onReturnToVoice: (() -> Unit)?,
            modifier: Modifier,
            start: (Locator, SpeechTranslation?) -> Unit,
        ) = Unit
    }
}
