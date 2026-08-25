package com.chmouel.liseur.reader.annotations

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.domain.MarkedPassage
import com.chmouel.liseur.domain.isSamePassage
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator

/**
 * The colours a passage can be marked in.
 *
 * Four were what Kindle offers and about the limit of what stays
 * legible over text; they are kept light enough that words read cleanly
 * through them. Purple and orange joined them for liseur-sync, whose
 * palette is six: a highlight another device made in a colour this one
 * could not name would otherwise arrive rewritten, and a sync that
 * quietly changes what it carries is worse than one colour too many.
 */
enum class HighlightTint(val color: Color, @param:StringRes val label: Int) {
    YELLOW(Color(0xFFFFD54F), R.string.annotation_tint_yellow),
    GREEN(Color(0xFF9CCC65), R.string.annotation_tint_green),
    BLUE(Color(0xFF64B5F6), R.string.annotation_tint_blue),
    PINK(Color(0xFFF06292), R.string.annotation_tint_pink),
    PURPLE(Color(0xFFB39DDB), R.string.annotation_tint_purple),
    ORANGE(Color(0xFFFFB74D), R.string.annotation_tint_orange),
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

/** A locator in the terms [isSamePassage] compares. */
fun Locator.markedPassage(): MarkedPassage = MarkedPassage(
    href = href.toString(),
    progression = locations.progression,
    text = text.highlight,
)
