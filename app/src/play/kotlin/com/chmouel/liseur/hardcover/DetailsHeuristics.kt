package com.chmouel.liseur.hardcover

import java.util.Locale

/**
 * A rough guess at which language a text is written in, from its most
 * common short words, for the languages Liseur is translated into.
 *
 * Hardcover keeps one description per book, usually in English, even
 * for a translated edition, so the guess keeps that text from being
 * offered over a description in the book's own language.
 */
object TextLanguage {
    private val COMMON = mapOf(
        "en" to "the and of to is in that with for his her she he was it as on but who by from this".words(),
        "fr" to "le la les et des un une du est dans qui que pour sur au aux elle il pas avec son sa ses ce".words(),
        "es" to "el los las y del una es en que por con para su sus se al no lo como pero".words(),
        "de" to "der die das und ist ein eine nicht mit von zu den dem sie er es auf für auch im".words(),
        "it" to "il lo gli e di una che per con non del della sono è nel alla si ma come".words(),
        "pt" to "o os e do da um uma que em para com não por seu sua no na mas como".words(),
        "cs" to "a je se na v že to s z do jako ale jeho její pro by od po který jsou".words(),
    )

    private val THREE_LETTER = mapOf(
        "eng" to "en", "fre" to "fr", "fra" to "fr", "spa" to "es", "ger" to "de", "deu" to "de",
        "ita" to "it", "por" to "pt", "cze" to "cs", "ces" to "cs", "rus" to "ru", "chi" to "zh", "zho" to "zh",
    )

    private const val MIN_WORDS = 12
    private const val MIN_HITS = 4
    private const val LEAD = 1.5

    /** A two-letter code, or null when the text is too short or too mixed to tell. */
    fun guess(text: String?): String? {
        val plain = text?.replace(TAG, " ") ?: return null
        val letters = plain.filter(Char::isLetter)
        if (letters.length >= MIN_WORDS) {
            val cyrillic = letters.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.CYRILLIC }
            val han = letters.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
            if (cyrillic * 2 > letters.length) return "ru"
            if (han * 2 > letters.length) return "zh"
        }
        val words = WORD.findAll(plain.lowercase(Locale.ROOT)).map { it.value }.toList()
        if (words.size < MIN_WORDS) return null
        val scores = COMMON.mapValues { (_, common) -> words.count { it in common } }
            .entries.sortedByDescending { it.value }
        val best = scores[0]
        val second = scores.getOrNull(1)?.value ?: 0
        if (best.value < MIN_HITS || best.value < second * LEAD) return null
        return best.key
    }

    /** The two-letter code a language tag such as `fr-FR` or `fre` names, or null. */
    fun codeOf(tag: String?): String? {
        val primary = tag?.trim()?.substringBefore('-')?.substringBefore('_')?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() } ?: return null
        return when (primary.length) {
            2 -> primary
            3 -> THREE_LETTER[primary]
            else -> null
        }
    }

    /**
     * True when [incoming] reads as another language than the book: its
     * current description when that can be told, else its declared language.
     */
    fun differs(incoming: String?, currentDescription: String?, bookLanguage: String?): Boolean {
        val theirs = guess(incoming) ?: return false
        val ours = guess(currentDescription) ?: codeOf(bookLanguage) ?: return false
        return ours != theirs
    }

    private val TAG = Regex("<[^>]+>")
    private val WORD = Regex("\\p{L}+")

    private fun String.words(): Set<String> = split(' ').toSet()
}

/**
 * A 64-bit difference hash of a cover, so the same picture at another
 * size or compression reads as the same cover.
 */
object CoverSimilarity {
    const val WIDTH = 9
    const val HEIGHT = 8

    /** At most this many differing bits still count as the same picture. */
    private const val SAME_MAX_DISTANCE = 8

    /** [luma] is [WIDTH] × [HEIGHT] brightness values, row by row. */
    fun hash(luma: IntArray): Long {
        require(luma.size == WIDTH * HEIGHT)
        var bits = 0L
        var bit = 0
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH - 1) {
                if (luma[y * WIDTH + x] > luma[y * WIDTH + x + 1]) bits = bits or (1L shl bit)
                bit++
            }
        }
        return bits
    }

    fun looksSame(a: Long, b: Long): Boolean = java.lang.Long.bitCount(a xor b) <= SAME_MAX_DISTANCE
}
