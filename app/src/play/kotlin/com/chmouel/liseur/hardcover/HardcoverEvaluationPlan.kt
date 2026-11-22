package com.chmouel.liseur.hardcover

import com.chmouel.liseur.domain.PersonalEvaluation
import kotlin.math.roundToInt

/** Last values both Liseur and Hardcover agreed on for a field. */
data class HardcoverEvaluationBase(
    val local: String?,
    val remote: String?,
)

sealed interface HardcoverEvaluationDecision {
    /** Nothing to write; [recordBase] records a newly agreed or normalized base. */
    data class NoChange(val recordBase: HardcoverEvaluationBase? = null) : HardcoverEvaluationDecision

    /** Push Liseur's value (or clear when [value] is null) to Hardcover, then re-read to set the base. */
    data class PushToRemote(val value: String?) : HardcoverEvaluationDecision

    /** Pull Hardcover's value (or clear when [value] is null) into Liseur via CAS, then set [base]. */
    data class PullToLocal(val value: String?, val base: HardcoverEvaluationBase) : HardcoverEvaluationDecision

    /** Both sides diverged (or remote cannot be imported); record conflict and leave existing base untouched. */
    data class Conflict(
        val conflictLocal: String?,
        val conflictRemote: String?,
        val remoteTooLong: Boolean = false,
    ) : HardcoverEvaluationDecision
}

/**
 * Pure two-way sync planning for ratings and reviews between Liseur and Hardcover (ADR 0077).
 *
 * Local rating is Liseur's wire string `"2"`..`"10"` (1..5 stars in half-star steps).
 * Remote rating is Hardcover's half-star Double `0.5`..`5.0`, encoded in base/conflict rows
 * as `"0.5"`..`"5.0"` (one decimal place). A remote rating `< 1.0` maps to local `"2"` (1 star)
 * when pulled; the base records `("2", "0.5")` so the next check sees neither side changed.
 */
object HardcoverEvaluationPlan {

    fun normalizeRemoteRating(rating: Double?): Double? {
        if (rating == null || !rating.isFinite()) return null
        val halves = (rating * 2.0).roundToInt()
        if (halves !in 1..10) return null
        return halves / 2.0
    }

    fun encodeRemoteRating(rating: Double?): String? =
        normalizeRemoteRating(rating)?.let { r ->
            val halves = (r * 2.0).roundToInt()
            if (halves % 2 == 0) "${halves / 2}.0" else "${halves / 2}.5"
        }

    fun decodeRemoteRating(encoded: String?): Double? =
        encoded?.toDoubleOrNull()?.let(::normalizeRemoteRating)

    /** Maps a Hardcover half-star rating to Liseur's `"2"`..`"10"` scale, coercing `< 1.0` to `"2"`. */
    fun ratingToLocal(remote: Double?): String? {
        val norm = normalizeRemoteRating(remote) ?: return null
        val halves = (norm * 2.0).roundToInt().coerceIn(2, 10)
        return halves.toString()
    }

    /** Maps a Liseur `"2"`..`"10"` rating string to Hardcover's `1.0`..`5.0` half-star scale. */
    fun ratingFromLocal(local: String?): Double? =
        local?.toIntOrNull()?.takeIf { it in 2..10 }?.let { it / 2.0 }

    fun normalizeReview(text: String?): String? =
        text?.replace("\r\n", "\n")?.trim()?.takeIf { it.isNotEmpty() }

    fun ratingsEquivalent(local: String?, remote: Double?): Boolean {
        val l = ratingFromLocal(local)
        val r = normalizeRemoteRating(remote)
        return l == r
    }

    fun reviewsEquivalent(local: String?, remote: String?): Boolean =
        normalizeReview(local) == normalizeReview(remote)

    fun migrationRatingBase(
        storedBase: HardcoverEvaluationBase?,
        pushedRating: Double?,
    ): HardcoverEvaluationBase? {
        if (storedBase != null) return storedBase
        val remote = encodeRemoteRating(pushedRating) ?: return null
        val local = ratingToLocal(pushedRating) ?: return null
        return HardcoverEvaluationBase(local = local, remote = remote)
    }

    fun planRating(
        onShelf: Boolean,
        localRaw: String?,
        remoteRating: Double?,
        storedBase: HardcoverEvaluationBase?,
        pushedRating: Double? = null,
    ): HardcoverEvaluationDecision {
        val local = localRaw?.takeIf { PersonalEvaluation.valid(PersonalEvaluation.RATING, it) }
        val remoteNorm = normalizeRemoteRating(remoteRating)
        val remoteEncoded = encodeRemoteRating(remoteNorm)
        val base = migrationRatingBase(storedBase, pushedRating)

        if (!onShelf) {
            return if (local != null) {
                HardcoverEvaluationDecision.PushToRemote(local)
            } else {
                HardcoverEvaluationDecision.NoChange()
            }
        }

        if (ratingsEquivalent(local, remoteNorm)) {
            val agreed = HardcoverEvaluationBase(local, remoteEncoded)
            return HardcoverEvaluationDecision.NoChange(recordBase = agreed.takeIf { it != storedBase })
        }

        if (base == null) {
            return when {
                local == null && remoteNorm != null -> {
                    val pulledLocal = checkNotNull(ratingToLocal(remoteNorm))
                    HardcoverEvaluationDecision.PullToLocal(
                        value = pulledLocal,
                        base = HardcoverEvaluationBase(pulledLocal, remoteEncoded),
                    )
                }
                remoteNorm == null && local != null -> HardcoverEvaluationDecision.PushToRemote(local)
                else -> HardcoverEvaluationDecision.Conflict(
                    conflictLocal = local,
                    conflictRemote = remoteEncoded,
                )
            }
        }

        val localChanged = local != base.local
        val remoteChanged = remoteEncoded != base.remote
        return when {
            !localChanged && !remoteChanged -> HardcoverEvaluationDecision.NoChange()
            localChanged && !remoteChanged -> HardcoverEvaluationDecision.PushToRemote(local)
            !localChanged && remoteChanged -> {
                val pulledLocal = ratingToLocal(remoteNorm)
                HardcoverEvaluationDecision.PullToLocal(
                    value = pulledLocal,
                    base = HardcoverEvaluationBase(pulledLocal, remoteEncoded),
                )
            }
            else -> HardcoverEvaluationDecision.Conflict(
                conflictLocal = local,
                conflictRemote = remoteEncoded,
            )
        }
    }

    fun planReview(
        onShelf: Boolean,
        localRaw: String?,
        remoteRaw: String?,
        storedBase: HardcoverEvaluationBase?,
    ): HardcoverEvaluationDecision {
        val local = normalizeReview(localRaw)?.takeIf { PersonalEvaluation.valid(PersonalEvaluation.REVIEW, it) }
        val remote = normalizeReview(remoteRaw)

        if (!onShelf) {
            return if (local != null) {
                HardcoverEvaluationDecision.PushToRemote(local)
            } else {
                HardcoverEvaluationDecision.NoChange()
            }
        }

        if (reviewsEquivalent(local, remote)) {
            val agreed = HardcoverEvaluationBase(local, remote)
            return HardcoverEvaluationDecision.NoChange(recordBase = agreed.takeIf { it != storedBase })
        }

        val remoteValid = remote == null || PersonalEvaluation.valid(PersonalEvaluation.REVIEW, remote)

        if (storedBase == null) {
            return when {
                local == null && remote != null -> {
                    // A review Liseur cannot hold stays on Hardcover; asking would only offer to delete it.
                    if (!remoteValid) {
                        HardcoverEvaluationDecision.NoChange(recordBase = HardcoverEvaluationBase(null, remote))
                    } else {
                        HardcoverEvaluationDecision.PullToLocal(
                            value = remote,
                            base = HardcoverEvaluationBase(remote, remote),
                        )
                    }
                }
                remote == null && local != null -> HardcoverEvaluationDecision.PushToRemote(local)
                else -> HardcoverEvaluationDecision.Conflict(
                    conflictLocal = local,
                    conflictRemote = remote,
                    remoteTooLong = !remoteValid,
                )
            }
        }

        val localChanged = !reviewsEquivalent(local, storedBase.local)
        val remoteChanged = !reviewsEquivalent(remote, storedBase.remote)
        return when {
            !localChanged && !remoteChanged -> HardcoverEvaluationDecision.NoChange()
            localChanged && !remoteChanged -> HardcoverEvaluationDecision.PushToRemote(local)
            !localChanged && remoteChanged -> {
                if (!remoteValid) {
                    HardcoverEvaluationDecision.NoChange(recordBase = HardcoverEvaluationBase(storedBase.local, remote))
                } else {
                    HardcoverEvaluationDecision.PullToLocal(
                        value = remote,
                        base = HardcoverEvaluationBase(remote, remote),
                    )
                }
            }
            else -> HardcoverEvaluationDecision.Conflict(
                conflictLocal = local,
                conflictRemote = remote,
                remoteTooLong = !remoteValid,
            )
        }
    }
}
