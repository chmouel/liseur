package com.chmouel.liseur.domain

/** A portable filename for a copy of an offline EPUB. */
fun bookExportFileName(title: String, author: String?): String {
    fun clean(value: String): String = value.map { character ->
        when {
            character.isISOControl() || Character.getType(character) == Character.FORMAT.toInt() ||
                character in "\\/:*?\"<>|" -> '_'
            character.isWhitespace() || Character.isSpaceChar(character) -> ' '
            else -> character
        }
    }.joinToString("").trim().trim('.').trim()

    val name = clean(title).ifEmpty { "book" }
    val by = author?.let(::clean)?.takeIf { it.isNotEmpty() }
    val stem = if (by == null) name else "$name - $by"
    val limited = StringBuilder()
    var bytes = 0
    var index = 0
    while (index < stem.length) {
        val codePoint = stem.codePointAt(index)
        val next = String(Character.toChars(codePoint))
        val size = next.toByteArray(Charsets.UTF_8).size
        if (bytes + size > 200) break
        limited.append(next)
        bytes += size
        index += Character.charCount(codePoint)
    }
    return "${limited.toString().trim().trim('.')}.epub"
}
