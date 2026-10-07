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

    /** Has the session read on in the voice now saved, from the start of the sentence. */
    suspend fun switchVoice()
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

    /** What names it in a notice instead of [label], such as a server's host; null for the label. */
    fun noticeName(s: AppSettings): String? = null

    /** Its voice, or its [voice] of that name instead; null when it is not set up. */
    suspend fun voice(s: AppSettings, voice: String? = null): SessionVoice?

    /** Which voice reads, under the player's controls: the text, and the same for a screen reader. */
    @Composable
    fun voiceStatus(): Pair<String, String>?

    /** The entries of the player's voice menu; [onPicked] closes it. */
    @Composable
    fun VoiceMenuItems(onPicked: () -> Unit)

    /** Permission needed to reach this service, shown in the reader. */
    @Composable
    fun AccessPrompt(theme: ReaderTheme) = Unit

    /** Its rows on the Read aloud screen. */
    @Composable
    fun SettingsRows(feature: SpeechReadAloud)
}
