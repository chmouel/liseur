package com.chmouel.liseur.data.library

import com.chmouel.liseur.domain.Contributor
import com.chmouel.liseur.domain.ContributorRoles
import org.junit.Assert.assertEquals
import org.junit.Test
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.Contributor as ReadiumContributor

class FileMetadataTest {
    @Test
    fun `a translator with other roles is shown as the translator`() {
        val publication = publication(
            contributors = listOf(ReadiumContributor(LocalizedString("Leonard A. Magnus"), roles = setOf("ann"))),
        )
        val declared = listOf(
            EpubContributor("Leonard A. Magnus", listOf("ann", "trl", "win"), setOf("Leonard A. Magnus")),
        )

        assertEquals(
            listOf(Contributor(ContributorRoles.TRANSLATOR, "Leonard A. Magnus")),
            publication.fileMetadata(null, emptyList(), declared).contributors,
        )
    }

    @Test
    fun `two people declared under the same name keep their own roles`() {
        val publication = publication(
            translators = listOf(ReadiumContributor("Alex Smith")),
            illustrators = listOf(ReadiumContributor("Alex Smith")),
        )
        val declared = listOf(
            EpubContributor("Alex Smith", listOf("trl"), setOf("Alex Smith")),
            EpubContributor("Alex Smith", listOf("ill"), setOf("Alex Smith")),
        )

        assertEquals(
            listOf(
                Contributor(ContributorRoles.TRANSLATOR, "Alex Smith"),
                Contributor(ContributorRoles.ILLUSTRATOR, "Alex Smith"),
            ),
            publication.fileMetadata(null, emptyList(), declared).contributors,
        )
    }

    @Test
    fun `a name another declaration shares as an alias is not taken for that person`() {
        val publication = publication(
            translators = listOf(ReadiumContributor("Anna Ivanova")),
            illustrators = listOf(ReadiumContributor("Анна")),
        )
        val declared = listOf(
            EpubContributor("Anna Ivanova", listOf("trl"), setOf("Anna Ivanova", "Анна")),
            EpubContributor("Анна", listOf("ill"), setOf("Анна")),
        )

        assertEquals(
            listOf(
                Contributor(ContributorRoles.TRANSLATOR, "Anna Ivanova"),
                Contributor(ContributorRoles.ILLUSTRATOR, "Анна"),
            ),
            publication.fileMetadata(null, emptyList(), declared).contributors,
        )
    }

    @Test
    fun `roles the screen does not name keep Readium's reading`() {
        val publication = publication(
            contributors = listOf(
                ReadiumContributor(LocalizedString("Type Foundry"), roles = setOf("tyd")),
                ReadiumContributor(LocalizedString("Not in the package"), roles = setOf("ann")),
            ),
            illustrators = listOf(ReadiumContributor("Painter")),
        )
        val declared = listOf(
            EpubContributor("Type Foundry", listOf("tyd"), setOf("Type Foundry")),
            EpubContributor("Painter", listOf("ill"), setOf("Painter")),
        )

        assertEquals(
            listOf(
                Contributor(ContributorRoles.ILLUSTRATOR, "Painter"),
                Contributor(ContributorRoles.CONTRIBUTOR, "Type Foundry"),
                Contributor(ContributorRoles.CONTRIBUTOR, "Not in the package"),
            ),
            publication.fileMetadata(null, emptyList(), declared).contributors,
        )
    }

    private fun publication(
        translators: List<ReadiumContributor> = emptyList(),
        contributors: List<ReadiumContributor> = emptyList(),
        illustrators: List<ReadiumContributor> = emptyList(),
    ) = Publication(
        Manifest(
            metadata = Metadata(
                localizedTitle = LocalizedString("Book"),
                translators = translators,
                contributors = contributors,
                illustrators = illustrators,
            ),
        ),
    )
}
