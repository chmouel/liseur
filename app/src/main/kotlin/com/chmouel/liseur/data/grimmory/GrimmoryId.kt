package com.chmouel.liseur.data.grimmory

/**
 * A Grimmory book id, checked before it is ever put in a URL.
 *
 * Grimmory's ids are database `Long`s, but they reach this app as
 * untrusted JSON, are then embedded in request paths, and are persisted
 * as `remoteUuid` — so a row read back months later is no more trusted
 * than the response it came from.
 *
 * This follows the rule already written down for annotation ids: an id
 * is opaque to *carry*, but it must be checked before it is *addressed*.
 */
object GrimmoryId {

    private val DIGITS = Regex("^[1-9][0-9]*$")

    /**
     * [raw] if it is an id Grimmory could actually have issued, else
     * null.
     *
     * Two checks, and both are load-bearing:
     *
     * The pattern rejects anything that is not a bare positive decimal —
     * `..`, `/`, an empty string, a leading zero, trailing junk — so an
     * id can never steer a request at a path of the attacker's choosing.
     *
     * The `Long` round-trip then rejects a number too large for Grimmory
     * to hold. `99999999999999999999` passes the pattern and looks
     * entirely plausible, but no row has that id, so carrying it only
     * buys a confusing 404 much later.
     */
    fun parse(raw: String?): String? {
        val trimmed = raw ?: return null
        if (!DIGITS.matches(trimmed)) return null
        val value = trimmed.toLongOrNull() ?: return null
        // Re-emitting rather than returning `trimmed` means the caller
        // gets one canonical spelling, whatever it was handed.
        return value.toString()
    }
}
