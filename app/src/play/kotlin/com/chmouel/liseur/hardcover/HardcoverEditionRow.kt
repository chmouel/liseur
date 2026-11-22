package com.chmouel.liseur.hardcover

enum class EditionIcon {
    BOOK,
    AUDIOBOOK,
}

/** One small label under an edition's title; counts are plural strings, so they stay numbers here. */
sealed interface EditionChip {
    data class Label(val text: String) : EditionChip
    data class Pages(val count: Int) : EditionChip
    data class Readers(val count: Int) : EditionChip
}

data class EditionRow(
    val editionId: Long,
    val icon: EditionIcon,
    val formatLabel: String?,
    val headline: String,
    /** At most two authors, then "+N" for the rest. */
    val authors: String?,
    val coverUrl: String?,
    val chips: List<EditionChip>,
    val current: Boolean,
    val selected: Boolean,
)

fun editionRow(edition: HardcoverEdition, currentEditionId: Long?, selectedEditionId: Long? = null): EditionRow {
    val format = edition.format?.trim()?.takeIf { it.isNotEmpty() }
    val icon = if (format.equals("audiobook", ignoreCase = true)) {
        EditionIcon.AUDIOBOOK
    } else {
        EditionIcon.BOOK
    }
    val pages = edition.pages?.takeIf { it > 0 }
    val readers = edition.usersCount.takeIf { it > 0 }
    return EditionRow(
        editionId = edition.id,
        icon = icon,
        formatLabel = format,
        headline = edition.title.ifBlank { edition.bookTitle.orEmpty() },
        authors = authorLine(edition.authors),
        coverUrl = HardcoverImages.validCoverUrl(edition.coverUrl),
        chips = listOfNotNull(
            format?.let { EditionChip.Label(it) },
            edition.releaseYear?.let { EditionChip.Label(it.toString()) },
            pages?.let { EditionChip.Pages(it) },
            readers?.let { EditionChip.Readers(it) },
        ),
        current = currentEditionId != null && edition.id == currentEditionId,
        selected = selectedEditionId != null && edition.id == selectedEditionId,
    )
}

private fun authorLine(authors: List<String>): String? {
    val names = authors.map { it.trim() }.filter { it.isNotEmpty() }
    if (names.isEmpty()) return null
    val shown = names.take(MAX_AUTHORS).joinToString(", ")
    val more = names.size - MAX_AUTHORS
    return if (more > 0) "$shown +$more" else shown
}

private const val MAX_AUTHORS = 2
