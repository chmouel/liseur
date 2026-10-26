package com.chmouel.liseur.reader

import java.text.BreakIterator
import java.util.Locale

internal class SelectionLookupGesture {
    private var handled = false

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
                if (++count > 1) return false
            }
            start = end
            end = words.next()
        }
        return count == 1
    }
}
