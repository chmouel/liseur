package com.chmouel.liseur.reader

import com.chmouel.liseur.readaloud.ReadAloudFeature
import com.chmouel.liseur.reader.progress.ExactLocatorAnchor
import com.chmouel.liseur.translate.SentenceTranslations
import com.chmouel.liseur.tts.SpeechTranslation
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
)

internal fun readAloudStartLocator(selected: Locator, original: Locator?, translated: Boolean): Locator =
    original ?: if (translated) ExactLocatorAnchor.coarse(selected) else selected

/** Transfers a hold on the page's translations to read aloud once its start place is ready. */
internal suspend fun handoffPageTranslationToReadAloud(
    start: (Locator, SpeechTranslation?) -> Unit,
    translations: SentenceTranslations,
    source: String?,
    target: String,
    startingPoint: suspend () -> Locator,
): Boolean {
    if (!translations.acquire()) return false
    val speechTranslation = SpeechTranslation(translations, source, target)
    var handedOver = false
    try {
        val locator = startingPoint()
        handedOver = true
        start(locator, speechTranslation)
    } finally {
        if (!handedOver) speechTranslation.translations.release()
    }
    return true
}
