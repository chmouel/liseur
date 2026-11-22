package com.chmouel.liseur.hardcover

/** What a reader typed or pasted into the edition search, decided before anything is sent. */
sealed interface HardcoverRef {
    data object Blank : HardcoverRef

    /** A `hardcover.app/books/<slug>` link; only the slug is ever sent. */
    data class Slug(val slug: String) : HardcoverRef

    /** A link to anywhere else: nothing is sent for it. */
    data object NotHardcover : HardcoverRef

    data class Isbn(val isbn: com.chmouel.liseur.hardcover.Isbn) : HardcoverRef

    data class BookId(val id: Int) : HardcoverRef

    /** Digits that cannot be a Hardcover book id. */
    data object BadId : HardcoverRef

    data class Text(val text: String) : HardcoverRef

    companion object {
        private val BOOK_LINK = Regex(
            """^(?:(?:https?:)?//)?(?:www\.)?hardcover\.app/books/([^/?#\s]+)(?:[/?#].*)?$""",
            RegexOption.IGNORE_CASE,
        )
        private val SLUG = Regex("""[A-Za-z0-9_.-]+""")
        private val HOST_FIRST = Regex("""^[^\s/]+\.[^\s/]+/""")

        /**
         * Whether the field changing from [before] to [after] was a paste of
         * something recognised, whose inserted text is two or more characters, which is submitted at once. Typing one
         * character at a time never is: unfinished text stays on the device.
         */
        fun submitsOnPaste(before: String, after: String): Boolean {
            val limit = minOf(before.length, after.length)
            var prefix = 0
            while (prefix < limit && before[prefix] == after[prefix]) prefix++
            var suffix = 0
            while (suffix < limit - prefix && before[before.length - 1 - suffix] == after[after.length - 1 - suffix]) suffix++
            if (after.length - prefix - suffix < 2) return false
            return when (parse(after)) {
                is Slug, is Isbn, is BookId, NotHardcover, BadId -> true
                Blank, is Text -> false
            }
        }

        fun parse(input: String): HardcoverRef {
            val text = input.trim()
            if (text.isEmpty()) return Blank
            BOOK_LINK.matchEntire(text)?.let { match ->
                val slug = match.groupValues[1]
                return if (SLUG.matches(slug)) Slug(slug) else NotHardcover
            }
            if (text.startsWith("//") || "://" in text || HOST_FIRST.containsMatchIn(text)) return NotHardcover
            com.chmouel.liseur.hardcover.Isbn.from(text)?.let { return Isbn(it) }
            if (text.all { it in '0'..'9' }) {
                val id = text.toIntOrNull()
                return if (id != null && id >= 1) BookId(id) else BadId
            }
            return Text(text)
        }
    }
}
