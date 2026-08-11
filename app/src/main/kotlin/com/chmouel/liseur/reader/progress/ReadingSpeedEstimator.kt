package com.chmouel.liseur.reader.progress

import kotlin.math.ceil
import kotlin.math.min

/** One forward stretch that looked like reading rather than navigation. */
data class PaceSample(
    val secondsPerPosition: Double,
    val positions: Double,
    val elapsedMs: Long,
) {
    val isUsable: Boolean
        get() = secondsPerPosition.isFinite() &&
            secondsPerPosition > 0 &&
            positions.isFinite() &&
            positions > 0 &&
            elapsedMs > 0
}

/**
 * A resumable estimate of reading time per stable Readium position.
 *
 * Time per position is deliberately averaged instead of positions per
 * minute. A very fast page can approach zero seconds but cannot explode
 * toward infinity, so it cannot pull the estimate down as aggressively as
 * the old speed-domain average did.
 */
data class ReadingPace(
    val secondsPerPosition: Double,
    val samples: Int,
    val elapsedMs: Long,
    val evidence: Double,
) {
    val isKnown: Boolean
        get() = secondsPerPosition.isFinite() &&
            secondsPerPosition > 0 &&
            samples > 0 &&
            elapsedMs > 0 &&
            evidence.isFinite() &&
            evidence > 0

    /** Adds one accepted reading sample with symmetric outlier control. */
    fun after(sample: PaceSample): ReadingPace {
        if (!sample.isUsable) return this
        val broad = sample.secondsPerPosition.coerceIn(
            MIN_SECONDS_PER_POSITION,
            MAX_SECONDS_PER_POSITION,
        )
        if (!isKnown) {
            return ReadingPace(
                secondsPerPosition = broad,
                samples = 1,
                elapsedMs = sample.elapsedMs.coerceAtMost(MAX_EVIDENCE_MS),
                evidence = sample.positions.coerceAtMost(MAX_EVIDENCE),
            )
        }

        val bounded = broad.coerceIn(
            secondsPerPosition / RELATIVE_OUTLIER_FACTOR,
            secondsPerPosition * RELATIVE_OUTLIER_FACTOR,
        )
        val addedEvidence = sample.positions.coerceAtMost(MAX_SAMPLE_EVIDENCE)
        val nextEvidence = min(MAX_EVIDENCE, evidence + addedEvidence)
        val weight = (addedEvidence / nextEvidence).coerceIn(MIN_WEIGHT, MAX_WEIGHT)
        return ReadingPace(
            secondsPerPosition = secondsPerPosition * (1 - weight) + bounded * weight,
            samples = (samples + 1).coerceAtMost(MAX_SAMPLES),
            elapsedMs = (elapsedMs + sample.elapsedMs).coerceAtMost(MAX_EVIDENCE_MS),
            evidence = nextEvidence,
        )
    }

    companion object {
        val Unknown = ReadingPace(
            secondsPerPosition = 0.0,
            samples = 0,
            elapsedMs = 0,
            evidence = 0.0,
        )

        /** Reads persisted v2 state, rejecting partial or malformed values. */
        fun of(
            secondsPerPosition: Double?,
            samples: Int?,
            elapsedMs: Long?,
            evidence: Double?,
        ): ReadingPace {
            val pace = ReadingPace(
                secondsPerPosition = secondsPerPosition ?: 0.0,
                samples = (samples ?: 0).coerceIn(0, MAX_SAMPLES),
                elapsedMs = (elapsedMs ?: 0).coerceIn(0, MAX_EVIDENCE_MS),
                evidence = (evidence ?: 0.0).coerceIn(0.0, MAX_EVIDENCE),
            )
            return pace.takeIf { it.isKnown } ?: Unknown
        }

        const val WARM_UP_SAMPLES = 5
        const val WARM_UP_MS = 3 * 60_000L
        const val DEFAULT_SECONDS_PER_POSITION = 40.0

        private const val MIN_SECONDS_PER_POSITION = 5.0
        private const val MAX_SECONDS_PER_POSITION = 10.0 * 60
        private const val RELATIVE_OUTLIER_FACTOR = 3.0
        private const val MIN_WEIGHT = 0.02
        private const val MAX_WEIGHT = 0.25
        private const val MAX_SAMPLE_EVIDENCE = 2.0
        private const val MAX_EVIDENCE = 20.0
        private const val MAX_SAMPLES = 1000
        private const val MAX_EVIDENCE_MS = 24 * 60 * 60_000L
    }
}

/**
 * Watches forward movement through stable Readium positions and estimates
 * the remaining reading time.
 */
class ReadingSpeedEstimator(
    learned: ReadingPace = ReadingPace.Unknown,
    bookPace: ReadingPace = ReadingPace.Unknown,
) {
    var pace: ReadingPace = blend(learned, bookPace)
        private set

    private var lastPosition: Double? = null
    private var lastAt: Long? = null

    val secondsPerPosition: Double?
        get() = pace.secondsPerPosition.takeIf { pace.isKnown }

    val effectiveSecondsPerPosition: Double
        get() = secondsPerPosition ?: ReadingPace.DEFAULT_SECONDS_PER_POSITION

    val isMeasured: Boolean
        get() = pace.isKnown &&
            pace.samples >= ReadingPace.WARM_UP_SAMPLES &&
            pace.elapsedMs >= ReadingPace.WARM_UP_MS

    /**
     * Records arrival at [position] at a monotonic [atMillis].
     *
     * Rejected transitions still become the new baseline, preventing a
     * burst of flicks or a backwards move from being joined to a later page
     * and mistaken for one long reading sample.
     */
    fun record(position: Double, atMillis: Long): PaceSample? {
        if (!position.isFinite() || atMillis < 0) {
            forgetLastPosition()
            return null
        }
        val previousPosition = lastPosition
        val previousAt = lastAt
        lastPosition = position
        lastAt = atMillis
        if (previousPosition == null || previousAt == null) return null

        val advanced = position - previousPosition
        val elapsedMs = atMillis - previousAt
        if (advanced <= 0 || advanced > MAX_ADVANCE) return null
        if (elapsedMs < MIN_ELAPSED_MS || elapsedMs > MAX_ELAPSED_MS) return null

        val sample = PaceSample(
            secondsPerPosition = elapsedMs / 1000.0 / advanced,
            positions = advanced,
            elapsedMs = elapsedMs,
        )
        if (!sample.isUsable) return null
        pace = pace.after(sample)
        return sample
    }

    fun forgetLastPosition() {
        lastPosition = null
        lastAt = null
    }

    fun minutesFor(positions: Double): Int {
        if (!positions.isFinite() || positions <= 0) return 0
        val minutes = positions * effectiveSecondsPerPosition / 60.0
        return ceil(minutes.coerceAtMost(MAX_ESTIMATE_MINUTES)).toInt()
    }

    private fun blend(learned: ReadingPace, book: ReadingPace): ReadingPace = when {
        learned.isKnown && book.isKnown -> {
            val learnedWeight = learned.evidence.coerceAtMost(PRIOR_EVIDENCE)
            val bookWeight = book.evidence.coerceAtMost(PRIOR_EVIDENCE)
            val total = learnedWeight + bookWeight
            ReadingPace(
                secondsPerPosition =
                    (learned.secondsPerPosition * learnedWeight +
                        book.secondsPerPosition * bookWeight) / total,
                samples = learned.samples + book.samples,
                elapsedMs = (learned.elapsedMs + book.elapsedMs)
                    .coerceAtMost(24 * 60 * 60_000L),
                evidence = total,
            )
        }

        book.isKnown -> book
        learned.isKnown -> learned
        else -> ReadingPace.Unknown
    }

    companion object {
        private const val MIN_ELAPSED_MS = 8_000L
        private const val MAX_ELAPSED_MS = 10 * 60_000L
        private const val MAX_ADVANCE = 3.0
        private const val PRIOR_EVIDENCE = 5.0
        private const val MAX_ESTIMATE_MINUTES = 60.0 * 99
    }
}
