package com.chmouel.liseur.data.bookorbit

/** A reader-verified CFI for the exact locator published alongside it. */
data class BookOrbitLocalCandidate(
    val context: BookOrbitCfiContext,
    val href: String,
    val locatorJson: String,
    val rawCfi: String,
)
