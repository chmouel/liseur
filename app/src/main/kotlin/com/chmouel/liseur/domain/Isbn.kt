package com.chmouel.liseur.domain

/**
 * An ISBN a catalogue can be asked about.
 *
 * [isbn13] is always present: every ISBN-10 has exactly one ISBN-13
 * spelling (the `978` prefix and a new check digit). [isbn10] is kept
 * beside it when there is one, because a catalogue row may carry only
 * the older number.
 */
data class Isbn(val isbn13: String, val isbn10: String?) {

    companion object {

        /**
         * The ISBN an EPUB's `dc:identifier` holds, or null when it holds
         * something else.
         *
         * Identifiers arrive as `urn:isbn:9780547928227`, `isbn:0-261-10221-4`
         * or the bare digits, and far more often as a UUID or a calibre id
         * that is no ISBN at all. Only a value whose check digit holds is
         * accepted: thirteen digits that fail it are an EAN for something
         * else or a typo, and looking either up names the wrong book.
         */
        fun from(identifier: String?): Isbn? {
            val raw = identifier?.trim()?.lowercase() ?: return null
            val body = PREFIXES.firstOrNull { raw.startsWith(it) }
                ?.let { raw.substring(it.length) } ?: raw
            val digits = body.filterNot { it == '-' || it == ' ' }
            return when (digits.length) {
                13 -> digits.takeIf(::validIsbn13)?.let { Isbn(it, isbn10Of(it)) }
                10 -> digits.takeIf(::validIsbn10)?.let { Isbn(isbn13Of(it), it.uppercase()) }
                else -> null
            }
        }

        private fun validIsbn13(value: String): Boolean {
            if (!value.all(Char::isDigit)) return false
            if (!value.startsWith("978") && !value.startsWith("979")) return false
            return check13(value.substring(0, 12)) == value[12]
        }

        private fun validIsbn10(value: String): Boolean {
            if (!value.substring(0, 9).all(Char::isDigit)) return false
            val last = value[9]
            if (!last.isDigit() && last != 'x') return false
            return check10(value.substring(0, 9)) == last.uppercaseChar()
        }

        private fun isbn13Of(isbn10: String): String {
            val stem = "978" + isbn10.substring(0, 9)
            return stem + check13(stem)
        }

        /** Only `978` numbers have an ISBN-10; `979` ones were never issued one. */
        private fun isbn10Of(isbn13: String): String? {
            if (!isbn13.startsWith("978")) return null
            val stem = isbn13.substring(3, 12)
            return stem + check10(stem)
        }

        private fun check13(stem: String): Char {
            val sum = stem.withIndex().sumOf { (index, digit) ->
                (digit - '0') * if (index % 2 == 0) 1 else 3
            }
            return '0' + (10 - sum % 10) % 10
        }

        private fun check10(stem: String): Char {
            val sum = stem.withIndex().sumOf { (index, digit) -> (digit - '0') * (10 - index) }
            val check = (11 - sum % 11) % 11
            return if (check == 10) 'X' else '0' + check
        }

        private val PREFIXES = listOf("urn:isbn:", "isbn:", "isbn")
    }
}
