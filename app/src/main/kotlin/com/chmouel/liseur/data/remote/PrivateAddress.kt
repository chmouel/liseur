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
        // ::1, and the fc00::/7 and fe80::/10 prefixes.
        val compact = host.replace(":", "").trimStart('0')
        if (compact.isEmpty() || compact == "1") return true
        val first = host.substringBefore(':').takeIf { it.isNotEmpty() } ?: return false
        val padded = first.padStart(4, '0')
        val leading = padded.take(2).toIntOrNull(16) ?: return false
        if (leading in 0xfc..0xfd) return true
        return padded.take(3) in setOf("fe8", "fe9", "fea", "feb")
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
