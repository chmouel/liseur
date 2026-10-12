package com.chmouel.liseur.tts

import com.chmouel.liseur.reader.ResourceAddress
import com.chmouel.liseur.translate.PageSentence
import org.readium.r2.shared.publication.Locator

/**
 * What one speech request says. Read in the book's own words, [text]
 * alone names it: the same words sound the same anywhere. Read translated,
 * [place] names the book's sentence too, since the same words can be
 * translated differently elsewhere; the navigator's request and the
 * prefetcher's walk build it from the same Readium locator, so they meet
 * on one entry.
 */
data class SpeechKey(val text: String, val place: PageSentence? = null) {
    companion object {
        private const val CSS_SELECTOR = "cssSelector"

        /** [text], spoken at Readium's [locator], as the page names a sentence. */
        fun at(text: String, locator: Locator): SpeechKey = SpeechKey(text, sentenceAt(text, locator))

        /** The book's sentence [text] at [locator], named the way the translated page names it. */
        fun sentenceAt(text: String, locator: Locator): PageSentence = PageSentence(
            text = text,
            href = ResourceAddress.canonicalPath(locator.href.toString()) ?: locator.href.toString(),
            selector = locator.locations.otherLocations[CSS_SELECTOR] as? String,
            before = locator.text.before,
        )
    }
}
