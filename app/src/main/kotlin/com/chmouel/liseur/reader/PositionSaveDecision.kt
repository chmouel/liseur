package com.chmouel.liseur.reader

internal fun shouldSkipPositionSave(
    sameAsBaseline: Boolean,
    awaitingPageCapture: Boolean,
    settlementPending: Boolean,
): Boolean = sameAsBaseline && !awaitingPageCapture && !settlementPending

internal fun shouldApplyPositionSaveResult(
    completedGeneration: Long,
    currentGeneration: Long,
): Boolean = completedGeneration == currentGeneration
