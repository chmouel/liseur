package com.chmouel.liseur.hardcover

import com.chmouel.liseur.data.db.MetadataOverrideFields
import com.chmouel.liseur.data.library.BookMetadataOverrideRepository
import com.chmouel.liseur.domain.validSeriesPosition

enum class DetailField {
    COVER,
    TITLE,
    AUTHOR,
    DESCRIPTION,
    SERIES,
}

data class LocalDetails(
    val title: String?,
    val author: String?,
    val description: String?,
    val seriesName: String?,
    val seriesIndex: Double?,
    /** The Hardcover cover URL the current cover was imported from, while that import is still the cover. */
    val importedCoverUrl: String? = null,
)

data class DetailProposal(
    val field: DetailField,
    val currentText: String? = null,
    val newText: String? = null,
    val currentSeriesIndex: Double? = null,
    val newSeriesIndex: Double? = null,
    val newCoverUrl: String? = null,
    val defaultChecked: Boolean,
)

fun proposeUpdates(local: LocalDetails, remote: HardcoverEditionDetails): List<DetailProposal> {
    val proposals = mutableListOf<DetailProposal>()

    val coverUrl = HardcoverImages.validCoverUrl(remote.coverUrl)
    if (coverUrl != null && coverUrl != local.importedCoverUrl) {
        proposals += DetailProposal(
            field = DetailField.COVER,
            newCoverUrl = coverUrl,
            defaultChecked = true,
        )
    }

    textProposal(
        field = DetailField.TITLE,
        wireField = MetadataOverrideFields.TITLE,
        current = local.title,
        incoming = remote.title,
    )?.let { proposals += it }

    textProposal(
        field = DetailField.AUTHOR,
        wireField = MetadataOverrideFields.AUTHOR,
        current = local.author,
        incoming = remote.author,
    )?.let { proposals += it }

    textProposal(
        field = DetailField.DESCRIPTION,
        wireField = MetadataOverrideFields.DESCRIPTION,
        current = local.description,
        incoming = remote.description,
    )?.let { proposals += it }

    val remoteSeries = remote.seriesName?.trim()?.takeIf { it.isNotEmpty() }
    if (remoteSeries != null) {
        val localSeries = local.seriesName?.trim()?.takeIf { it.isNotEmpty() }
        val localIndex = validSeriesPosition(local.seriesIndex)
        val remoteIndex = validSeriesPosition(remote.seriesPosition)
        if (localSeries != remoteSeries || localIndex != remoteIndex) {
            proposals += DetailProposal(
                field = DetailField.SERIES,
                currentText = localSeries,
                newText = remoteSeries,
                currentSeriesIndex = localIndex,
                newSeriesIndex = remoteIndex,
                defaultChecked = localSeries == null,
            )
        }
    }

    return proposals
}

private fun textProposal(
    field: DetailField,
    wireField: String,
    current: String?,
    incoming: String?,
): DetailProposal? {
    val newClean = incoming?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (!BookMetadataOverrideRepository.withinWireLimit(wireField, newClean)) return null
    val currentClean = current?.trim()?.takeIf { it.isNotEmpty() }
    if (currentClean == newClean) return null
    return DetailProposal(
        field = field,
        currentText = currentClean,
        newText = newClean,
        defaultChecked = currentClean == null,
    )
}
