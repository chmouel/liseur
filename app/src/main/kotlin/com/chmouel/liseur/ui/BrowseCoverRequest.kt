package com.chmouel.liseur.ui

import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.chmouel.liseur.data.remote.BrowseCoverTaggingInterceptor
import com.chmouel.liseur.data.remote.browseCoverRequestHeader

/**
 * Marks a cover fetched from a saved catalog so it is signed with that
 * catalog's login and no other. Every screen that draws a book's
 * [coverUrl] goes through here; one that skipped it would ask the
 * catalog with the main connection's login, or with none.
 */
internal fun ImageRequest.Builder.browseCover(
    artwork: String,
    coverUrl: String?,
    browseServerId: Long?,
): ImageRequest.Builder = apply {
    if (browseServerId != null && artwork == coverUrl) {
        httpHeaders(
            NetworkHeaders.Builder()
                .set(BrowseCoverTaggingInterceptor.HEADER, browseCoverRequestHeader(browseServerId, artwork))
                .build(),
        )
    }
}
