package com.chmouel.liseur.hardcover

enum class EditionIcon {
    BOOK,
    AUDIOBOOK,
}

/** What the picker shows for one edition; counts stay numbers because they are plural strings. */
data class EditionRow(
    val icon: EditionIcon,
    val formatLabel: String?,
    val headline: String,
    val supporting: String,
    val pages: Int?,
    val readers: Int?,
    val current: Boolean,
)

fun editionRow(edition: HardcoverEdition, currentEditionId: Long?): EditionRow {
    val format = edition.format?.trim()?.takeIf { it.isNotEmpty() }
    val icon = if (format.equals("audiobook", ignoreCase = true)) {
        EditionIcon.AUDIOBOOK
    } else {
        EditionIcon.BOOK
    }
    return EditionRow(
        icon = icon,
        formatLabel = format,
        headline = edition.title.ifBlank { edition.bookTitle.orEmpty() },
        supporting = listOfNotNull(
            format,
            edition.releaseYear?.toString(),
            edition.publisher,
            edition.isbn13,
        ).joinToString(" · "),
        pages = edition.pages?.takeIf { it > 0 },
        readers = edition.usersCount.takeIf { it > 0 },
        current = currentEditionId != null && edition.id == currentEditionId,
    )
}
