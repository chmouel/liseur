package com.chmouel.liseur.data.komga

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turning a place in a book into something Komga will accept, and back.
 *
 * Both sides speak the same locator format, so this is mostly a matter
 * of the three things Komga is strict about and Readium is not. All of
 * them were found by having a real server refuse a real position, not by
 * reading the schema:
 *
 * - the `href` must not start with a slash, and must otherwise be the
 *   name of a file inside the EPUB exactly as the EPUB spells it;
 * - `locations.progression` must be there, even at the very start of a
 *   resource, where Readium leaves it out;
 * - a progression past the last one Komga knows about in that resource
 *   is refused outright.
 *
 * The work is done on the JSON rather than on Readium's `Locator`,
 * because the JSON is what is already in the database and what goes on
 * the wire; going through the model in between would only be a chance to
 * lose something.
 */
object KomgaLocator {

    private const val DEFAULT_TYPE = "application/xhtml+xml"

    /**
     * A stored Readium locator, said the way Komga will take it.
     *
     * Returns null when there is no `href` to speak of, which is the one
     * thing that cannot be filled in for the reader.
     */
    fun toKomga(readium: JSONObject): JSONObject? {
        val href = readium.stringOrNull("href")?.trimStart('/')?.takeIf { it.isNotEmpty() }
            ?: return null

        val locations = JSONObject(readium.optJSONObject("locations")?.toString() ?: "{}")
        if (!locations.has("fragments")) locations.put("fragments", JSONArray())
        if (locations.isNull("progression")) locations.put("progression", 0.0)

        return JSONObject()
            .put("href", href)
            .put("type", readium.stringOrNull("type") ?: DEFAULT_TYPE)
            .put("locations", locations)
            .apply {
                readium.stringOrNull("title")?.let { put("title", it) }
                readium.optJSONObject("text")?.let { put("text", it) }
            }
    }

    /**
     * A locator from Komga, said the way Readium stores one.
     *
     * Komga adds `koboSpan`, which means nothing here and is dropped
     * rather than carried around in every saved position.
     */
    fun toReadium(komga: JSONObject): JSONObject? {
        val href = komga.stringOrNull("href")?.takeIf { it.isNotEmpty() } ?: return null
        return JSONObject()
            .put("href", href)
            .put("type", komga.stringOrNull("type") ?: DEFAULT_TYPE)
            .apply {
                komga.optJSONObject("locations")?.let { put("locations", it) }
                komga.stringOrNull("title")?.let { put("title", it) }
                komga.optJSONObject("text")?.let { put("text", it) }
            }
    }

    /** The whole-book progression a locator claims, if it claims one. */
    fun totalProgression(locator: JSONObject): Double? =
        locator.optJSONObject("locations")
            ?.takeIf { !it.isNull("totalProgression") }
            ?.optDouble("totalProgression")
            ?.takeIf { !it.isNaN() }

    /**
     * The nearest place Komga already agrees exists.
     *
     * Used when the server has refused a position. Komga only accepts
     * progressions it can find in its own index of the book, so the way
     * back is to ask what it does know and send one of those instead.
     *
     * Staying inside the same resource is tried first, taking the last
     * position at or before where the reader actually is: landing
     * slightly behind is a page they have read, while landing ahead is a
     * page they have not.
     *
     * If the resource is not in the index at all — which is what an
     * href that does not line up looks like from here — the whole book
     * is searched by overall progression instead. That is a worse
     * answer, but it is still the right chapter, and it is much better
     * than losing the position.
     *
     * Failing both, nothing is returned. Guessing would mean writing a
     * place the reader has never been into the one record that says
     * where they got to, and on a server their other devices read from.
     */
    fun snap(positions: List<JSONObject>, wanted: JSONObject): JSONObject? {
        if (positions.isEmpty()) return null
        val href = wanted.stringOrNull("href")?.trimStart('/')
        val progression = wanted.optJSONObject("locations")
            ?.optDouble("progression")
            ?.takeIf { !it.isNaN() }
            ?: 0.0

        val inResource = positions.filter { it.stringOrNull("href") == href }
        if (inResource.isNotEmpty()) {
            return inResource.lastOrNull { it.progression() <= progression + EPSILON }
                ?: inResource.first()
        }

        val total = totalProgression(wanted) ?: return null
        return positions.minByOrNull { kotlin.math.abs(it.totalProgression() - total) }
    }

    /** The positions in a `GET /positions` answer, in the order given. */
    fun positionsOf(json: JSONObject): List<JSONObject> = json.objects("positions")

    private fun JSONObject.progression(): Double =
        optJSONObject("locations")?.optDouble("progression")?.takeIf { !it.isNaN() } ?: 0.0

    private fun JSONObject.totalProgression(): Double =
        optJSONObject("locations")?.optDouble("totalProgression")?.takeIf { !it.isNaN() } ?: 0.0

    /**
     * Komga rounds the progressions in its index, so an exact comparison
     * would reject the very position it just handed out.
     */
    private const val EPSILON = 1e-6
}
