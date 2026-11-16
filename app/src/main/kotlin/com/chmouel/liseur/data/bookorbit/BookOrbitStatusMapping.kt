package com.chmouel.liseur.data.bookorbit

import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.domain.ReadingStatus

data class MappedBookOrbitStatus(
    val status: ReadingStatus,
    val override: FinishedOverride,
)

/** Maps only the BookOrbit statuses Liseur can represent without guessing. */
object BookOrbitStatusMapping {
    fun fromRemote(
        status: String,
        source: String?,
        progression: Double?,
    ): MappedBookOrbitStatus? {
        if (source != "manual") return null
        return when (status) {
            "read" -> MappedBookOrbitStatus(ReadingStatus.FINISHED, FinishedOverride.FINISHED)
            "unread" ->
                MappedBookOrbitStatus(ReadingStatus.READY_TO_READ, FinishedOverride.UNREAD)
            "want_to_read" ->
                MappedBookOrbitStatus(ReadingStatus.WANT_TO_READ, FinishedOverride.WANT_TO_READ)
            "on_hold" -> MappedBookOrbitStatus(ReadingStatus.PAUSED, FinishedOverride.PAUSED)
            "abandoned" ->
                MappedBookOrbitStatus(ReadingStatus.DID_NOT_FINISH, FinishedOverride.DID_NOT_FINISH)
            // Liseur itself says `reading` for a resumed book with no
            // choice made, so it stays derived from the position rather
            // than pinning a READING choice that would stop it finishing.
            "reading" ->
                if (progression != null &&
                    progression > 0.0 && ReadingStatus.forProgression(progression) == ReadingStatus.READING
                ) {
                    MappedBookOrbitStatus(ReadingStatus.READING, FinishedOverride.NONE)
                } else null
            else -> null
        }
    }

    fun toRemote(override: FinishedOverride): String? = when (override) {
        FinishedOverride.FINISHED -> "read"
        FinishedOverride.UNREAD -> "unread"
        FinishedOverride.NONE -> null
        FinishedOverride.WANT_TO_READ -> "want_to_read"
        FinishedOverride.READING -> "reading"
        FinishedOverride.PAUSED -> "on_hold"
        FinishedOverride.DID_NOT_FINISH -> "abandoned"
    }
}
