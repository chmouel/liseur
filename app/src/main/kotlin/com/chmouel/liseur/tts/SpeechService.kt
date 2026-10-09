package com.chmouel.liseur.tts

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.ReaderTheme
import kotlinx.coroutines.flow.Flow

/** What a speech service may ask of the session in progress when its settings change. */
internal interface SessionControl {
    /** Ends the session: it reads with something that is gone, such as a key. */
    fun stop()

    /** Has the session read on in the voice now saved for its language, from the start of the sentence. */
    suspend fun switchVoice()

    /** The language the session in progress reads in, when it reads with [service]; null otherwise. */
    fun sessionLanguage(service: SpeechService): String?
}

/**
 * One speech service reading aloud can use: what it needs set up, the
 * voice it reads in, and its part of the Read aloud screen and the player.
 * A build offers the services its factory passes to [SpeechReadAloud].
 */
internal interface SpeechService {
    /** Stored as the reader's choice; never changes once released. */
    val id: String

    @get:StringRes
    val label: Int

    /** One line on what it is and where the text goes, under [label] in the picker. */
    @get:StringRes
    val summary: Int

    /** Shown before [label] in the picker, to tell the services apart at a glance. */
    val icon: ImageVector

    /** Whether it can read with what is saved. */
    val configured: Flow<Boolean>

    /** The voice it reads in, as the settings row names it; blank for none. */
    @Composable
    fun voiceName(): String

    /**
     * Whose address or key on the Services page it reads with, as
     * [com.chmouel.liseur.providers.ServerConnections] names them; null
     * for none.
     */
    fun owner(s: AppSettings): String? = null

    /** What names it in a notice instead of [label], such as a server's host; null for the label. */
    fun noticeName(s: AppSettings): String? = null

    /** Its voice, or its [voice] of that name instead; null when it is not set up. */
    suspend fun voice(s: AppSettings, voice: String? = null): SessionVoice?

    /**
     * The voices it can read with now, with the languages each speaks when
     * known; null when it is not set up. Asking may reach the service.
     */
    suspend fun catalogue(s: AppSettings): VoiceCatalogue?

    /**
     * Saves [voice] as its voice and, with a [language], as the one for
     * that language, in one write; nothing is written, and false returned,
     * once [scope] is no longer what it reads with.
     */
    suspend fun remember(scope: VoiceScope, language: String?, voice: String): Boolean

    /** [voice] as the reader knows it, without its language. */
    @Composable
    fun voiceLabel(voice: String): String

    /** Permission needed to reach this service, shown in the reader. */
    @Composable
    fun AccessPrompt(theme: ReaderTheme) = Unit

    /** Its rows on the Read aloud screen; [onManageServices] opens the Services page where keys and servers are set up. */
    @Composable
    fun SettingsRows(feature: SpeechReadAloud, onManageServices: () -> Unit)
}
