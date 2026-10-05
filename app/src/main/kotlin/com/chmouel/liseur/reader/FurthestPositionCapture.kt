package com.chmouel.liseur.reader

import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.remote.SyncPreview
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

/** Only the action's own same-place capture may advance the offered revision. */
internal fun SyncPreview.afterFurthestCapture(
    before: ReadingProgress?,
    after: ReadingProgress?,
    generationChange: Long,
    samePlace: (Locator, Locator) -> Boolean,
): SyncPreview? {
    before ?: return null
    after ?: return null
    if (before.localRevision != localRevision ||
        before.locatorJson != localLocatorJson || before.totalProgression != local
    ) return null
    val writes = after.localRevision - before.localRevision
    if (writes !in 0L..1L || generationChange != writes ||
        after.positionRevision - before.positionRevision != writes ||
        after.statusRevision != before.statusRevision
    ) return null
    if (before.locatorJson != after.locatorJson) {
        val original = runCatching { Locator.fromJSON(JSONObject(before.locatorJson)) }.getOrNull()
            ?: return null
        val captured = runCatching { Locator.fromJSON(JSONObject(after.locatorJson)) }.getOrNull()
            ?: return null
        if (!samePlace(original, captured)) return null
    } else if (before.totalProgression != after.totalProgression) {
        return null
    }
    return copy(localRevision = after.localRevision)
}
