package com.chmouel.liseur.reader

import com.chmouel.liseur.readaloud.ReadAloudFeature
import com.chmouel.liseur.reader.progress.ExactLocatorAnchor
import com.chmouel.liseur.translate.SentenceTranslations
import com.chmouel.liseur.tts.SpeechTranslation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.readium.r2.shared.publication.Locator

/** What the reader screen needs to read its book aloud. */
class ReaderReadAloud(
    val feature: ReadAloudFeature,
    val bookId: String,
    /**
     * Reads aloud from the sentence the locator starts in; in translation
     * when given one, whose hold the session takes over.
     */
    val start: (Locator, SpeechTranslation?) -> Unit,
    /**
     * The place listening last saved, while it still waits for the page
     * to be measured for BookOrbit; null when there is nothing to measure.
     */
    val awaitingCapture: () -> Locator?,
    /** The page showing the place listening saved was saved as [Locator]. */
    val captured: (Locator) -> Unit = {},
    /**
     * Carries on the book's paused listening once the page has landed,
     * playing it when the shelf's Continue listening opened the reader.
     */
    val continueListening: suspend () -> Unit = {},
    /** Emits when read aloud is unblocked by any BookOrbit choice or catch-up. */
    val unblocked: Flow<Unit> = flowOf(Unit),
)

/**
 * Defers [consumeResume] while a BookOrbit choice or catch-up blocks read aloud,
 * so the shelf's Continue-listening request stays pending until the guard clears.
 */
internal suspend fun continueListeningWhenUnblocked(
    blocked: Boolean,
    resumeRequested: Boolean,
    consumeResume: () -> Unit,
    resumeIfPaused: () -> Boolean,
    restorePaused: suspend (resumeAfterRestore: Boolean) -> Unit,
) {
    if (blocked) return
    consumeResume()
    if (resumeRequested && resumeIfPaused()) return
    restorePaused(resumeRequested)
}

internal fun readAloudStartLocator(selected: Locator, original: Locator?, translated: Boolean): Locator =
    original ?: if (translated) ExactLocatorAnchor.coarse(selected) else selected

internal suspend fun startReadAloudIfCurrent(
    startingPoint: suspend () -> Locator,
    isCurrent: () -> Boolean,
    start: (Locator) -> Unit,
): Boolean {
    val locator = startingPoint()
    if (!isCurrent()) return false
    start(locator)
    return true
}

/** Transfers a hold on the page's translations to read aloud once its start place is ready. */
internal suspend fun handoffPageTranslationToReadAloud(
    start: (Locator, SpeechTranslation?) -> Unit,
    translations: SentenceTranslations,
    source: String?,
    target: String,
    startingPoint: suspend () -> Locator,
    isCurrent: () -> Boolean,
): Boolean {
    if (!translations.acquire()) return false
    val speechTranslation = SpeechTranslation(translations, source, target)
    var handedOver = false
    try {
        val locator = startingPoint()
        if (!isCurrent()) return false
        handedOver = true
        start(locator, speechTranslation)
    } finally {
        if (!handedOver) speechTranslation.translations.release()
    }
    return true
}
