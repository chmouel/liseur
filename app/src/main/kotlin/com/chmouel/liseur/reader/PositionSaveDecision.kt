package com.chmouel.liseur.reader

internal fun shouldSkipPositionSave(
    sameAsBaseline: Boolean,
    awaitingPageCapture: Boolean,
    verifiedOpeningPending: Boolean,
): Boolean = sameAsBaseline && !awaitingPageCapture && !verifiedOpeningPending
