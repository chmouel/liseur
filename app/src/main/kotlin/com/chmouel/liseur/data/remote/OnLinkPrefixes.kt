package com.chmouel.liseur.data.remote

/**
 * Which addresses the phone's own interfaces put on the local network.
 *
 * This is the live half of [LocalNetworkAddress]: what no fixed list of
 * private ranges can answer, because it depends on the addresses this
 * phone happens to be holding right now.
 *
 * It mirrors the rule the platform itself enforces, which is written
 * over *link addresses* and not over routes. An earlier version of this
 * read routes instead, calling an address on-link when a gateway-less
 * route matched it. That is true of a Wi-Fi subnet, and it is also true
 * of the default route on a mobile network, where the modem frequently
 * reports no gateway at all: `0.0.0.0/0` and `::/0` then match every
 * address in the world, and every server the reader owns and every
 * server they do not was judged local and refused before it was dialled
 * (#241). The platform never had that problem because the platform
 * never looked at routes.
 *
 * The rule has two halves of its own:
 *
 * IPv6 is taken as it comes. An interface holding an address in a
 * prefix puts that whole prefix on the local network, which is how a
 * home server on a global address in the phone's own /64 is blocked
 * without appearing in any private range (#195). A prefix length of
 * zero arms nothing — that is the guard the route form was missing, and
 * it is the one the platform writes down explicitly.
 *
 * IPv4 is widened rather than taken literally. An interface holding
 * `192.168.1.5/24` arms the whole of `192.168.0.0/16`, not its own /24,
 * and a mobile interface holding a point-to-point `10.99.61.153/32`
 * arms the whole of `10.0.0.0/8`. Only the prefixes in [V4_PREFIXES]
 * can be armed this way, so an interface with a public address arms
 * nothing.
 *
 * Almost all of the IPv4 half is already in [PrivateAddress], which
 * [LocalNetworkAddress] consults first; in practice the only range this
 * adds is carrier-grade NAT. That is deliberate, and it is the reason
 * `100.64.0.0/10` is left out of the fixed half: it is where a Tailscale
 * address lives, that traffic goes down a tunnel and needs no
 * permission, and a reader on a tailnet must not be asked for one. An
 * ISP that hands `100.64` out on the LAN puts such an address on a real
 * interface, and gets asked about — through here, which is the honest
 * reason. The rest is written out anyway rather than deferred to
 * [PrivateAddress], so that this reads as the platform's rule and can be
 * checked against it.
 *
 * Everything here is literals and arithmetic. Nothing is resolved: the
 * callers only ever arrive with an address.
 */
object OnLinkPrefixes {

    /** One address an interface holds, with the prefix it sits in. */
    data class Link(val address: String, val prefixLength: Int)

    /**
     * The IPv4 ranges the platform will arm, and only when an interface
     * actually holds an address inside one.
     */
    private val V4_PREFIXES = listOf(
        "169.254.0.0" to 16, // Link-local
        "100.64.0.0" to 10, // Carrier-grade NAT
        "10.0.0.0" to 8, // RFC 1918
        "172.16.0.0" to 12, // RFC 1918
        "192.168.0.0" to 16, // RFC 1918
    ).map { (prefix, length) -> requireNotNull(v4Bytes(prefix)) to length }

    /**
     * Whether [host] sits in a prefix that [links] puts on the local
     * network.
     *
     * [host] is an address, not a name. Anything that does not parse as
     * one answers false, as does an empty set of links.
     */
    fun contains(links: List<Link>, host: String): Boolean {
        val target = bytes(host) ?: return false
        return links.any { link -> arms(link, target) }
    }

    private fun arms(link: Link, target: ByteArray): Boolean {
        val address = bytes(link.address) ?: return false
        if (address.size != target.size) return false
        if (address.size == 16) {
            return link.prefixLength in 1..128 &&
                sharesPrefix(address, target, link.prefixLength)
        }
        return V4_PREFIXES.any { (range, length) ->
            link.prefixLength >= length &&
                sharesPrefix(range, address, length) &&
                sharesPrefix(range, target, length)
        }
    }

    /** Whether two addresses agree on their first [bits] bits. */
    private fun sharesPrefix(one: ByteArray, other: ByteArray, bits: Int): Boolean {
        val whole = bits / 8
        for (index in 0 until whole) {
            if (one[index] != other[index]) return false
        }
        val spare = bits % 8
        if (spare == 0) return true
        val mask = (0xff shl (8 - spare)) and 0xff
        return (one[whole].toInt() and mask) == (other[whole].toInt() and mask)
    }

    /**
     * An address as its bytes: four for IPv4, sixteen for IPv6, null
     * for anything else.
     *
     * An IPv6 zone is dropped, because `fe80::1%wlan0` is how an
     * interface spells its own link-local address and the zone says
     * nothing about which prefix it is in. An IPv4-mapped literal is
     * unwrapped to the four bytes it stands for, so that the two
     * spellings of one address cannot answer differently — the
     * unwrapping is [PrivateAddress]'s, so there is only ever one IPv6
     * parser here to get wrong.
     */
    private fun bytes(host: String): ByteArray? {
        val cleaned = host.lowercase().trim('[', ']').substringBefore('%').trim()
        if (cleaned.isEmpty()) return null
        if (':' !in cleaned) return v4Bytes(cleaned)
        PrivateAddress.mappedV4(cleaned)?.let { return v4Bytes(it) }
        val groups = PrivateAddress.expandV6(cleaned) ?: return null
        if (groups.size != 8) return null
        val out = ByteArray(16)
        groups.forEachIndexed { index, group ->
            out[index * 2] = (group shr 8).toByte()
            out[index * 2 + 1] = (group and 0xff).toByte()
        }
        return out
    }

    private fun v4Bytes(host: String): ByteArray? {
        val octets = host.split('.')
        if (octets.size != 4) return null
        val out = ByteArray(4)
        octets.forEachIndexed { index, octet ->
            val value = octet.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            out[index] = value.toByte()
        }
        return out
    }
}
