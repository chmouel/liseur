package com.chmouel.liseur.companion

import java.security.MessageDigest
import java.text.Normalizer

/**
 * A piece of the book's own text, small enough to quote and to send.
 *
 * [ref] names it stably within one catalog format; [hash] tells a stored
 * ref whose text has since changed. [range] is the chapters it may belong
 * to and [place] what the reader navigates to, which never leaves the
 * phone. [end] is the last element in document order of what holds it in
 * its resource, or null when that is not known; it never leaves either.
 */
data class Passage<P>(
    val ref: String,
    val hash: String,
    val resource: Int,
    val text: String,
    val range: ChapterRange,
    val place: P,
    val end: Int? = null,
    /** [text] folded for matching, surrounded by spaces so whole-word checks need no allocation. */
    val folded: String = " ${PassageText.fold(text)} ",
)

object PassageText {
    /** The longest passage sent or quoted as one; a longer element is cut. */
    const val MAX_LENGTH = 1_200
    private const val MIN_CUT = 400
    private val SENTENCE_ENDS = setOf('.', '!', '?', '…', '。', '！', '？')

    /** The format of refs and cuts; part of a stored recap's format so a change invalidates it. */
    const val VERSION = 1

    fun ref(resource: Int, ordinal: Int, part: Int?): String =
        "r$resource.$ordinal" + (part?.let { ".$it" } ?: "")

    fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .take(6)
        .joinToString("") { "%02x".format(it) }

    /**
     * [text] cut into pieces of at most [max] characters, after a sentence
     * when one ends late enough, else at a space, else anywhere but inside
     * a surrogate pair. Nothing is dropped but the whitespace at the cuts.
     */
    fun split(text: String, max: Int = MAX_LENGTH): List<String> {
        val trimmed = text.trim()
        if (trimmed.length <= max) return listOfNotNull(trimmed.takeIf { it.isNotEmpty() })
        val pieces = mutableListOf<String>()
        var start = 0
        while (trimmed.length - start > max) {
            val window = (start + minOf(MIN_CUT, max / 2)) until (start + max)
            val cut = window.reversed().firstOrNull { trimmed[it] in SENTENCE_ENDS && trimmed.getOrNull(it + 1)?.isWhitespace() == true }?.plus(1)
                ?: window.reversed().firstOrNull { trimmed[it].isWhitespace() }
                ?: (start + max).let { if (trimmed[it - 1].isHighSurrogate()) it - 1 else it }
            trimmed.substring(start, cut).trim().takeIf { it.isNotEmpty() }?.let(pieces::add)
            start = cut
        }
        trimmed.substring(start).trim().takeIf { it.isNotEmpty() }?.let(pieces::add)
        return pieces
    }

    /** [text] for matching: lower case, accents gone, runs of anything but letters and digits one space. */
    fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var space = true
        decomposed.forEach { c ->
            when {
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Unit
                c.isLetterOrDigit() -> {
                    out.append(c)
                    space = false
                }
                !space -> {
                    out.append(' ')
                    space = true
                }
            }
        }
        return out.toString().trim()
    }

    /** Whether [phrase] occurs in [text] as whole words, both folded. */
    fun mentions(text: String, phrase: String): Boolean {
        val needle = fold(phrase).takeIf { it.isNotEmpty() } ?: return false
        return hasWords(" ${fold(text)} ", needle)
    }

    /** Whether [phrase] occurs in [passage] as whole words, using its pre-folded text. */
    fun mentions(passage: Passage<*>, phrase: String): Boolean {
        val needle = fold(phrase).takeIf { it.isNotEmpty() } ?: return false
        return hasWords(passage.folded, needle)
    }

    /**
     * Whether the folded [needle] occurs in [padded], folded text with a
     * space at each end, as whole words. Chinese, Japanese, Thai and the
     * like do not put spaces between words, so a [needle] in one of them
     * is matched anywhere.
     */
    fun hasWords(padded: String, needle: String): Boolean =
        if (needle.any(::unspaced)) padded.contains(needle) else padded.contains(" $needle ")

    private fun unspaced(c: Char): Boolean = when (Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.THAI,
        Character.UnicodeScript.LAO,
        Character.UnicodeScript.KHMER,
        Character.UnicodeScript.MYANMAR,
        Character.UnicodeScript.TIBETAN,
        -> true
        else -> false
    }

    /**
     * The name the reader selected, tidied, or null when it cannot be a
     * name: empty, longer than [MAX_NAME_LENGTH] or more than
     * [MAX_NAME_WORDS] words.
     */
    fun name(selection: String): String? {
        val tidy = selection.trim().trim { !it.isLetterOrDigit() }.replace(Regex("\\s+"), " ")
        if (tidy.isEmpty() || tidy.length > MAX_NAME_LENGTH) return null
        if (tidy.split(' ').size > MAX_NAME_WORDS) return null
        if (fold(tidy).isEmpty()) return null
        return tidy
    }

    const val MAX_NAME_LENGTH = 80
    const val MAX_NAME_WORDS = 6
}
