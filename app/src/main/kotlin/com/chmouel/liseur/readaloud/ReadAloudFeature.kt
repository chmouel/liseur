package com.chmouel.liseur.readaloud

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.reader.OpenBookHandle
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

    /** The chosen service has no key, server or voice yet; nothing was read. */
    NotSetUp,

    /** The selected sentence was not found; reading from the start of its paragraph. */
    SelectionNotFound,

    /** The book could not be read aloud from there. */
    Unavailable,
}

/** A [notice] about [bookId], shown only in that book's reader. */
data class ReadAloudBookNotice(val bookId: String, val notice: ReadAloudNotice)

/**
 * Reading aloud, as the flavor-neutral reader sees it. The Play build
 * provides Gemini and OpenAI-compatible voices; the F-Droid build provides [None],
 * which is never available and draws nothing.
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
     * [reader] reopens this book from the playback notification.
     */
    fun start(handle: OpenBookHandle, selection: Locator, reader: Intent)

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
     */
    @Composable
    fun Player(bookId: String, theme: ReaderTheme, controls: Boolean, modifier: Modifier)

    /** The selection bar's button that reads aloud from the selected passage. */
    @Composable
    fun SelectionButton(onClick: () -> Unit)

    object None : ReadAloudFeature {
        override val isAvailable = false
        override val configured: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
        override val session: StateFlow<ReadAloudUi?> = MutableStateFlow<ReadAloudUi?>(null).asStateFlow()
        override val notices: SharedFlow<ReadAloudBookNotice> = MutableSharedFlow<ReadAloudBookNotice>().asSharedFlow()

        override fun start(handle: OpenBookHandle, selection: Locator, reader: Intent) = Unit
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
        override fun Player(bookId: String, theme: ReaderTheme, controls: Boolean, modifier: Modifier) = Unit

        @Composable
        override fun SelectionButton(onClick: () -> Unit) = Unit
    }
}
