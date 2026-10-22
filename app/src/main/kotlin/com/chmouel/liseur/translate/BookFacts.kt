package com.chmouel.liseur.translate

import java.security.MessageDigest

/**
 * What an AI translator or speech service may be told about where a
 * passage comes from: the book, its author, its source language, the
 * chapter, and how far into the book that chapter starts. Any of them may
 * be unknown.
 */
data class BookFacts(
    val title: String? = null,
    val author: String? = null,
    val chapter: String? = null,
    val percent: Int? = null,
    val language: String? = null,
) {
    companion object {
        /**
         * The facts of each resource, by the canonical path a [PageSentence]
         * names it with. [titles] are the contents' titles by reading-order
         * index; a resource without one continues the chapter before it, as
         * split chapters do, and so shares its start. [starts] is where each
         * resource starts in the book, from 0 to 1.
         */
        fun byResource(
            title: String?,
            author: String?,
            hrefs: List<String>,
            titles: Map<Int, String>,
            starts: List<Double?>,
            language: String? = null,
        ): Map<String, BookFacts> {
            var chapter: String? = null
            var chapterStart: Double? = null
            return hrefs.mapIndexed { index, href ->
                val start = starts.getOrNull(index)
                titles[index]?.let {
                    chapter = it
                    chapterStart = start
                }
                val percent = (if (chapter != null) chapterStart else start)?.let { (it.coerceIn(0.0, 1.0) * 100).toInt() }
                href to BookFacts(title, author, chapter, percent, language)
            }.toMap()
        }
    }
}

/** The reader's template of what an AI translator or speech service is told about the book. */
internal object TranslationBookContext {
    const val TITLE = "{title}"
    const val AUTHOR = "{author}"
    const val CHAPTER = "{chapter}"
    const val PERCENT = "{percent}"
    const val LANGUAGE = "{language}"

    /** Used until the reader writes their own; [PERCENT] and [LANGUAGE] are left for readers who add them. */
    const val DEFAULT_TEMPLATE = "Book: $TITLE\nAuthor: $AUTHOR\nChapter: $CHAPTER"

    /** The longest template kept. */
    const val MAX_CHARACTERS = 1_000

    // A fact longer than this is not a title or a name.
    private const val LONGEST_FACT = 200

    private val PLACEHOLDER = Regex("""\{(title|author|chapter|percent|language)\}""")

    /** The template in effect for what is [stored]: the default when unset, null when the reader cleared it. */
    fun template(stored: String?): String? = (stored ?: DEFAULT_TEMPLATE).trim().take(MAX_CHARACTERS).ifEmpty { null }

    /** What keys the answers translated with [template], so changing it asks again. */
    fun identity(template: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(template.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * [template] with [facts] in place of its placeholders. A line whose
     * placeholder has no value is left out, so nothing reads "Author: ".
     * Null when nothing is left to say.
     */
    fun render(template: String?, facts: BookFacts?): String? {
        if (template == null) return null
        val lines = template.lines().mapNotNull { line ->
            var missing = false
            val filled = PLACEHOLDER.replace(line) { match ->
                val value = when (match.value) {
                    TITLE -> facts?.title
                    AUTHOR -> facts?.author
                    CHAPTER -> facts?.chapter
                    LANGUAGE -> facts?.language
                    else -> facts?.percent?.let { "$it%" }
                }?.let(::fact)
                if (value == null) missing = true
                value.orEmpty()
            }
            filled.trimEnd().takeUnless { missing }
        }
        return lines.joinToString("\n").trim().ifEmpty { null }
    }

    // One line, of a reasonable length: a book's metadata is not the reader's to vouch for.
    private fun fact(value: String): String? =
        value.replace(Regex("\\s+"), " ").trim().take(LONGEST_FACT).ifEmpty { null }
}
