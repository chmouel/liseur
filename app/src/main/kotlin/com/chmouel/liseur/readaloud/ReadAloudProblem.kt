package com.chmouel.liseur.readaloud

/** A playback or setup problem for [bookId] that stays visible in its reader until resolved. */
data class ReadAloudProblem(
    val bookId: String,
    val notice: ReadAloudNotice,
    val recoverable: Boolean,
)

/** Pure reducer for [ReadAloudProblem] lifecycle transitions. */
object ReadAloudProblems {
    sealed interface Event {
        data class Notice(val bookId: String, val notice: ReadAloudNotice) : Event

        /** A start was asked for, before any of its audio is heard. */
        data class Started(val bookId: String) : Event

        /** Audio of a session actually began: what a retry has to reach to have worked. */
        data class Playing(val bookId: String) : Event

        /** Dismissed in [bookId]'s reader, or in whichever one is showing it when null. */
        data class Dismissed(val bookId: String? = null) : Event

        /** The voice or service the problem is about changed. */
        data object SettingsChanged : Event
    }

    fun next(current: ReadAloudProblem?, event: Event): ReadAloudProblem? = when (event) {
        is Event.Notice -> {
            val recoverable = when (event.notice) {
                ReadAloudNotice.Network,
                ReadAloudNotice.RateLimited,
                ReadAloudNotice.Service,
                ReadAloudNotice.Output,
                ReadAloudNotice.TranslationFailed -> true
                ReadAloudNotice.InvalidKey,
                ReadAloudNotice.InvalidVoice,
                ReadAloudNotice.TermsRequired,
                ReadAloudNotice.NoSpeech,
                ReadAloudNotice.NotSetUp,
                ReadAloudNotice.Unavailable -> false
                ReadAloudNotice.SelectionNotFound -> return current
            }
            ReadAloudProblem(event.bookId, event.notice, recoverable)
        }
        is Event.Started -> null
        is Event.Playing -> current?.takeUnless { it.bookId == event.bookId }
        is Event.Dismissed -> if (event.bookId == null || current?.bookId == event.bookId) null else current
        Event.SettingsChanged -> null
    }

    /**
     * [Event.Playing] as it is read once collected: [failedWhenPlayed] is
     * the session's failure count when the audio began, [failedNow] the
     * count as it arrives. A failure reported in between means that audio
     * never played, so the problem stays.
     */
    fun playingNow(current: ReadAloudProblem?, bookId: String, failedWhenPlayed: Int, failedNow: Int): ReadAloudProblem? =
        if (failedWhenPlayed == failedNow) next(current, Event.Playing(bookId)) else current
}
