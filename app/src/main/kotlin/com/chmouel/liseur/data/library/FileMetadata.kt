package com.chmouel.liseur.data.library

import com.chmouel.liseur.domain.BookAccessibility
import com.chmouel.liseur.domain.Contributor
import com.chmouel.liseur.domain.ContributorRoles
import com.chmouel.liseur.domain.MetadataSnapshot
import com.chmouel.liseur.domain.identifierOf
import org.readium.r2.shared.publication.Accessibility
import org.readium.r2.shared.publication.Publication

/**
 * What an EPUB's own package says about it, as one `file` snapshot
 * (ADR-0016). [sizeBytes] is what this device measured for the file.
 *
 * The publication date arrives from Readium as an instant, so a book
 * that said only `1962` is stored at whatever day Readium made of it.
 */
internal fun Publication.fileMetadata(sizeBytes: Long?): MetadataSnapshot {
    val m = metadata
    fun people(list: List<org.readium.r2.shared.publication.Contributor>, role: String) =
        list.map { Contributor(role, it.name) }
    return MetadataSnapshot(
        subtitle = m.localizedSubtitle?.string,
        description = descriptionText(m.description),
        publishers = m.publishers.map { it.name },
        imprints = m.imprints.map { it.name },
        published = m.published?.toString(),
        languages = m.languages,
        subjects = m.subjects.map { it.name },
        contributors = people(m.authors, ContributorRoles.AUTHOR) +
            people(m.translators, ContributorRoles.TRANSLATOR) +
            people(m.editors, ContributorRoles.EDITOR) +
            people(m.illustrators + m.artists + m.pencilers + m.inkers + m.colorists + m.letterers, ContributorRoles.ILLUSTRATOR) +
            people(m.narrators, ContributorRoles.NARRATOR) +
            m.contributors.map { Contributor(ContributorRoles.of(it.roles.firstOrNull() ?: "contributor"), it.name) },
        identifiers = listOfNotNull(identifierOf(m.identifier)),
        pageCount = m.numberOfPages,
        fileSizeBytes = sizeBytes,
        accessibility = m.accessibility?.let(::accessibilityOf),
    )
}

private fun accessibilityOf(a: Accessibility): BookAccessibility = BookAccessibility(
    conformsTo = a.conformsTo.map { it.uri },
    accessModes = a.accessModes.map { it.value },
    accessModesSufficient = a.accessModesSufficient.map { set -> set.map { it.value }.sorted().joinToString(",") },
    features = a.features.map { it.value },
    hazards = a.hazards.map { it.value },
    exemptions = a.exemptions.map { it.value },
    summary = a.summary,
    certifiedBy = a.certification?.certifiedBy,
    certifierReport = a.certification?.report,
)
