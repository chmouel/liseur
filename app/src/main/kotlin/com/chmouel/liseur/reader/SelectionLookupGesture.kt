package com.chmouel.liseur.reader

import java.text.BreakIterator
import java.util.Locale

// Long enough for most idioms a dictionary has an entry for; a longer
// selection is a passage and keeps the selection bar.
internal const val MAX_LOOKUP_WORDS = 6

internal class SelectionLookupGesture {
    var handled = false
        private set

    fun clear() {
        handled = false
    }

    fun selectText() {
        handled = true
    }

    fun select(text: String, locale: Locale): Boolean {
        if (handled) return false
        handled = true
        val words = BreakIterator.getWordInstance(locale)
        words.setText(text)
        var start = words.first()
        var count = 0
        var end = words.next()
        while (end != BreakIterator.DONE) {
            if (text.substring(start, end).codePoints().anyMatch { Character.isLetterOrDigit(it) }) {
                if (++count > MAX_LOOKUP_WORDS) return false
            }
            start = end
            end = words.next()
        }
        return count >= 1
    }
}
