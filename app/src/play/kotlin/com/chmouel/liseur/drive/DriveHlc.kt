package com.chmouel.liseur.drive

import java.util.Locale

/**
 * Monotonic Hybrid Logical Clock (HLC) for Google Drive sync (ADR-0078).
 *
 * Wire format: `"%013d-%04x-%s".format(wallMs, counter, deviceId)`, compared
 * lexicographically with strict total order across all devices. The wall field
 * is exactly 13 digits, so `wallMs` is capped at [MAX_WALL_MS]; advancing past
 * it throws instead of producing a wider field that would sort out of order.
 */
data class DriveHlc(
    val wallMs: Long,
    val counter: Int,
    val deviceId: String,
) : Comparable<DriveHlc> {

    init {
        require(wallMs in 0L..MAX_WALL_MS) { "HLC wallMs out of range 0..$MAX_WALL_MS: $wallMs" }
        require(counter in 0..MAX_COUNTER) { "HLC counter out of range: $counter" }
        require(deviceId.isNotBlank()) { "HLC deviceId must not be blank" }
    }

    fun format(): String = String.format(Locale.ROOT, "%013d-%04x-%s", wallMs, counter, deviceId)

    override fun compareTo(other: DriveHlc): Int = format().compareTo(other.format())

    override fun toString(): String = format()

    companion object {
        const val MAX_COUNTER = 0xFFFF
        const val MAX_WALL_MS = 9_999_999_999_999L
        private val PATTERN = Regex("""^(\d{13})-([0-9a-fA-F]{4})-(.+)$""")

        fun parse(raw: String): DriveHlc? {
            val match = PATTERN.matchEntire(raw.trim()) ?: return null
            val wall = match.groupValues[1].toLongOrNull() ?: return null
            val counter = match.groupValues[2].toIntOrNull(16) ?: return null
            val deviceId = match.groupValues[3]
            if (wall !in 0L..MAX_WALL_MS || counter !in 0..MAX_COUNTER || deviceId.isBlank()) return null
            return DriveHlc(wallMs = wall, counter = counter, deviceId = deviceId)
        }

        private fun overflowWall(wallMs: Long): Long {
            check(wallMs < MAX_WALL_MS) { "HLC cannot advance past wallMs $MAX_WALL_MS" }
            return wallMs + 1L
        }

        /** Advances the local HLC for an outgoing event. */
        fun next(
            currentWallMs: Long,
            currentCounter: Int,
            nowMs: Long,
            deviceId: String,
        ): DriveHlc {
            val clampedNow = nowMs.coerceAtLeast(0L)
            val clampedWall = currentWallMs.coerceAtLeast(0L)
            return if (clampedNow > clampedWall) {
                DriveHlc(wallMs = clampedNow, counter = 0, deviceId = deviceId)
            } else if (currentCounter < MAX_COUNTER) {
                DriveHlc(wallMs = clampedWall, counter = currentCounter + 1, deviceId = deviceId)
            } else {
                DriveHlc(wallMs = overflowWall(clampedWall), counter = 0, deviceId = deviceId)
            }
        }

        /** Merges a remote HLC into the local clock state (`local = max(local, now, remote)`). */
        fun observe(
            currentWallMs: Long,
            currentCounter: Int,
            remote: DriveHlc,
            nowMs: Long,
            localDeviceId: String,
        ): DriveHlc {
            val clampedNow = nowMs.coerceAtLeast(0L)
            val clampedLocal = currentWallMs.coerceAtLeast(0L)
            val maxWall = maxOf(clampedLocal, clampedNow, remote.wallMs)
            val nextCounter = when {
                maxWall == clampedLocal && maxWall == remote.wallMs -> maxOf(currentCounter, remote.counter) + 1
                maxWall == clampedLocal -> currentCounter + 1
                maxWall == remote.wallMs -> remote.counter + 1
                else -> 0
            }
            return if (nextCounter <= MAX_COUNTER) {
                DriveHlc(wallMs = maxWall, counter = nextCounter, deviceId = localDeviceId)
            } else {
                DriveHlc(wallMs = overflowWall(maxWall), counter = 0, deviceId = localDeviceId)
            }
        }
    }
}
