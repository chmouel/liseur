package com.chmouel.liseur.reader.annotations

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.BookAnnotation
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator

/**
 * The colours a passage can be marked in.
 *
 * Four is what Kindle offers and it is about the limit of what stays
 * legible over text; they are kept light enough that words read cleanly
 * through them.
 */
enum class HighlightTint(val color: Color) {
    YELLOW(Color(0xFFFFD54F)),
    GREEN(Color(0xFF9CCC65)),
    BLUE(Color(0xFF64B5F6)),
    PINK(Color(0xFFF06292)),
    ;

    companion object {
        val DEFAULT = YELLOW

        fun fromName(name: String?): HighlightTint =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** The group decorations are applied under; annotations own theirs alone. */
const val DECORATION_GROUP = "annotations"

/**
 * Turns stored annotations into what the navigator draws over the page.
 *
 * Bookmarks are deliberately left out: they mark a page, not a passage, and
 * are shown by the corner ribbon instead. A note is drawn like a highlight
 * so the passage it belongs to stays visible — a note with nothing
 * underneath it cannot be found again by eye.
 */
@OptIn(ExperimentalReadiumApi::class)
fun List<BookAnnotation>.toDecorations(): List<Decoration> =
    mapNotNull { annotation ->
        if (annotation.kind == AnnotationKind.BOOKMARK.name) return@mapNotNull null
        val locator = annotation.locator() ?: return@mapNotNull null
        Decoration(
            id = annotation.id,
            locator = locator,
            style = Decoration.Style.Highlight(
                tint = HighlightTint.fromName(annotation.tint).color.toArgb(),
                isActive = false,
            ),
        )
    }

/** The stored locator, or null when it cannot be read back. */
fun BookAnnotation.locator(): Locator? =
    runCatching { Locator.fromJSON(JSONObject(locatorJson)) }.getOrNull()
