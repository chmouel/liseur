package com.chmouel.liseur.reader

/**
 * Whether a place need not be saved again. A settlement (an offer being
 * accepted or turned down) forces the save even at the stored place,
 * but only once: the same place queued again while that write is still
 * going would count as a new move and could be pushed over the server.
 */
internal fun shouldSkipPositionSave(
    sameAsBaseline: Boolean,
    awaitingPageCapture: Boolean,
    settlementPending: Boolean,
    sameAsSettlementInFlight: Boolean = false,
): Boolean = !awaitingPageCapture &&
    (sameAsSettlementInFlight || (sameAsBaseline && !settlementPending))

internal fun shouldApplyPositionSaveResult(
    completedGeneration: Long,
    currentGeneration: Long,
): Boolean = completedGeneration == currentGeneration
