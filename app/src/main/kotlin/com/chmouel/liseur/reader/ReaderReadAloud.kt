package com.chmouel.liseur.reader

import com.chmouel.liseur.readaloud.ReadAloudFeature
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
