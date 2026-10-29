package com.chmouel.liseur.data.library

import androidx.core.text.HtmlCompat
import com.chmouel.liseur.domain.BookDetailsBounds
import com.chmouel.liseur.domain.capUtf16
import com.chmouel.liseur.domain.cleanDescription

/**
 * A book description as plain text, whatever markup it came in.
 *
 * The raw input is cut before it is parsed, because the parse is the
 * costly part and a catalog or OPF can carry anything. Images become
 * nothing, links become their text, and nothing from the markup is ever
 * rendered as markup.
 *
 * [html] says what the source declared; null means look for tags or
 * entities and decide.
 */
fun descriptionText(raw: String?, html: Boolean? = null): String? {
    val capped = capUtf16(raw ?: return null, BookDetailsBounds.RAW_DESCRIPTION_UTF16)
    if (capped.isBlank()) return null
    val markup = html ?: MARKUP.containsMatchIn(capped)
    val text = if (markup) {
        HtmlCompat.fromHtml(capped, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
    } else {
        capped
    }
    return cleanDescription(text)
}

private val MARKUP = Regex("""<[A-Za-z/!]|&(#\d+|#x[0-9A-Fa-f]+|[A-Za-z]+);""")
