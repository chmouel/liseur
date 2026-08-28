package com.chmouel.liseur.data.remote

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Whether an address is somewhere only the reader's own network can
 * reach: loopback, link-local (which is where a cloud metadata service
 * sits), the private IPv4 ranges, IPv6 unique-local and link-local, and
 * the hostname suffixes that mean the same thing.
 *
 * Two very different questions lean on this, and both need the same
 * answer:
 *
 * A self-hosted library is the ordinary case in Liseur, so plain HTTP to
 * one is a considered choice about a network the reader controls, not a
 * password posted to the internet. Cleartext to a *public* host is
 * neither, and is refused.
 *
 * And a catalog naming one of these addresses in a link is asking the
 * phone to go and knock on the reader's router. Where the reader typed
 * the address that is exactly right; where a feed chose it, it is a
 * reachability probe nobody asked for.
 *
 * Judged from the address as written, without resolving it. A name that
 * resolves into private space still reads as public here, and closing
 * that needs the check to happen against the address actually dialled,
 * at connect time. That is worth doing and is not done here; this
 * answers the cheap and common shape, where the address is a literal.
 */
object PrivateAddress {

    fun matches(url: String): Boolean = url.toHttpUrlOrNull()?.let(::matches) ?: false

    fun matches(url: HttpUrl): Boolean {
        val host = url.host.lowercase().trim('[', ']')
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (host.endsWith(".local") || host.endsWith(".internal")) return true
        if (':' in host) return isPrivateV6(host)
        return isPrivateV4(host)
    }

    private fun isPrivateV6(host: String): Boolean {
        // An IPv4 address wearing an IPv6 coat is still that address.
        // `::ffff:192.168.1.1` and `::ffff:c0a8:0101` are both 192.168.1.1,
        // and read as a v6 prefix neither matches anything private.
        mappedV4(host)?.let { return isPrivateV4(it) }
        // ::1, and the fc00::/7 and fe80::/10 prefixes.
        val compact = host.replace(":", "").trimStart('0')
        if (compact.isEmpty() || compact == "1") return true
        val first = host.substringBefore(':').takeIf { it.isNotEmpty() } ?: return false
        val padded = first.padStart(4, '0')
        val leading = padded.take(2).toIntOrNull(16) ?: return false
        if (leading in 0xfc..0xfd) return true
        return padded.take(3) in setOf("fe8", "fe9", "fea", "feb")
    }

    /**
     * The IPv4 address inside an IPv4-mapped or IPv4-compatible IPv6
     * literal, in dotted form, or null if this is not one.
     *
     * Both spellings are accepted: the trailing dotted quad that
     * `::ffff:192.168.1.1` uses, and the two hex groups of
     * `::ffff:c0a8:0101`. The `::ffff:` prefix may itself be written
     * out as `0:0:0:0:0:ffff:`, so the groups are counted rather than
     * matched as text.
     */
    private fun mappedV4(host: String): String? {
        val expanded = expandV6(host) ?: return null
        if (expanded.size != 8) return null
        if (expanded.take(5).any { it != 0 }) return null
        if (expanded[5] != 0xffff && expanded[5] != 0) return null
        // `::` and `::1` are loopback, not a mapped 0.0.0.x.
        if (expanded[5] == 0 && expanded[6] == 0 && expanded[7] <= 1) return null
        val high = expanded[6]
        val low = expanded[7]
        return "${high shr 8}.${high and 0xff}.${low shr 8}.${low and 0xff}"
    }

    /** The eight 16-bit groups of an IPv6 literal, or null if it is not one. */
    private fun expandV6(host: String): List<Int>? {
        var text = host
        var trailing = emptyList<Int>()
        val dotted = text.substringAfterLast(':', "")
        if ('.' in dotted) {
            val octets = dotted.split('.').map { it.toIntOrNull() ?: return null }
            if (octets.size != 4 || octets.any { it !in 0..255 }) return null
            trailing = listOf((octets[0] shl 8) or octets[1], (octets[2] shl 8) or octets[3])
            // The quad stood in for the last two groups; drop it and the
            // colon before it, unless that colon was half of a `::`.
            text = text.removeSuffix(dotted)
            if (!text.endsWith("::")) text = text.dropLast(1)
        }
        val want = 8 - trailing.size
        val halves = text.split("::")
        if (halves.size > 2) return null
        fun groups(part: String): List<Int>? =
            if (part.isEmpty()) {
                emptyList()
            } else {
                part.split(':').map { g ->
                    if (g.isEmpty() || g.length > 4) return null
                    g.toIntOrNull(16) ?: return null
                }
            }
        val head = groups(halves[0]) ?: return null
        if (halves.size == 1) return (head + trailing).takeIf { head.size == want }
        val tail = groups(halves[1]) ?: return null
        val gap = want - head.size - tail.size
        if (gap < 1) return null
        return head + List(gap) { 0 } + tail + trailing
    }

    private fun isPrivateV4(host: String): Boolean {
        val octets = host.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return when {
            octets[0] == 0 || octets[0] == 127 -> true
            octets[0] == 10 -> true
            octets[0] == 169 && octets[1] == 254 -> true
            octets[0] == 172 && octets[1] in 16..31 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            else -> false
        }
    }
}
