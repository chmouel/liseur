package com.chmouel.liseur.reader

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Whether a web view is showing the resource a locator names.
 *
 * Readium lays a resource out and puts it on screen before it publishes
 * having moved to it, so the position on hand can still name the
 * resource the reader has just left. Anything that captures a place to
 * come back to has to know which of the two it is holding, because
 * restoring a position belonging to the previous resource carries the
 * reader back out of the chapter they turned into.
 *
 * Readium serves each resource from `https://readium_package/<href>`,
 * where the href is the publication-relative one a locator carries. So
 * the two are compared as paths: the origin dropped when there is one,
 * the query and the fragment dropped from either side, and percent
 * escapes resolved, because a spelling difference in a filename with a
 * space in it is not a difference in resource.
 */
internal object ResourceAddress {
    /** True when [webUrl] addresses the resource [href] names. */
    fun shows(webUrl: String?, href: String): Boolean {
        val shown = path(webUrl) ?: return false
        val named = path(href) ?: return false
        return shown == named
    }

    private fun path(raw: String?): String? {
        val address = raw?.substringBefore('#')?.substringBefore('?')?.trim() ?: return null
        if (address.isEmpty()) return null
        val origin = address.indexOf("://")
        val path = if (origin < 0) {
            address
        } else {
            address.substring(origin + 3).substringAfter('/', "")
        }
        return decode(path).trimStart('/').takeIf { it.isNotEmpty() }
    }

    /**
     * Percent decoding, rather than `URLDecoder`, which reads `+` as a
     * space: that is form encoding, and a book is free to ship a file
     * whose name has a plus in it.
     */
    private fun decode(value: String): String {
        if (!value.contains('%')) return value
        val bytes = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val escape = value.getOrNull(i)
                ?.takeIf { it == '%' && i + 2 < value.length }
                ?.let { value.substring(i + 1, i + 3).toIntOrNull(16) }
            if (escape != null) {
                bytes.write(escape)
                i += 3
            } else {
                bytes.write(value[i].toString().toByteArray(StandardCharsets.UTF_8))
                i++
            }
        }
        return bytes.toString(StandardCharsets.UTF_8.name())
    }
}
