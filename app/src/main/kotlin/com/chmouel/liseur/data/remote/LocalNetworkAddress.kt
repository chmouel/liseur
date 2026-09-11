package com.chmouel.liseur.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * Whether an address is one Android counts as being on the local
 * network, and so one the app may not reach without permission.
 *
 * A narrower question than [PrivateAddress]'s, and a different one. That
 * object asks whether an address is somewhere only the reader's own
 * network can reach, which is about what is safe to send in the clear
 * and what a catalog may point the phone at. This asks what the
 * platform will drop, and the platform's answer is not a table: it is
 * the directly connected routes of the networks the phone is on, and it
 * excludes whatever a VPN carries.
 *
 * So the judgement has two halves. The fixed half is everything
 * [PrivateAddress] knows, plus broadcast and multicast. The live half is
 * [onLink], a predicate over the phone's own routes, which
 * `LocalNetworkAccess` fills in and the tests supply directly. Both are
 * needed: a home server on a global IPv6 address in the phone's own /64
 * is on-link and blocked, and no list of private ranges will ever say
 * so.
 *
 * Carrier-grade NAT space, `100.64.0.0/10`, is deliberately absent from
 * the fixed half. That is where a Tailscale address lives; that traffic
 * goes down the tunnel and needs no permission, and asking for one
 * would be asking for nothing. An ISP that hands `100.64` out on the
 * LAN still gets asked about — through [onLink], which is the honest
 * reason.
 *
 * Loopback answers false throughout. The restriction exempts it, so a
 * reader pointed at `localhost` must not be asked a question about
 * their network.
 */
object LocalNetworkAddress {

    /** What can be said about an address without going to the network. */
    enum class Verdict {
        /** Local for certain: a literal in range, or an mDNS name. */
        LOCAL,

        /** Not local, or not reachable at all. */
        NOT_LOCAL,

        /** A name, which has to be resolved before it can be judged. */
        RESOLVE,
    }

    /** Resolving a name to the addresses it stands for, as text. */
    fun interface Resolver {
        fun addresses(host: String): List<String>
    }

    private val system = Resolver { host ->
        runCatching { InetAddress.getAllByName(host).mapNotNull { it.hostAddress } }
            .getOrDefault(emptyList())
    }

    /**
     * Whether one host — normally a literal, resolved or typed — is on
     * the local network.
     */
    fun hostIsLocal(host: String, onLink: (String) -> Boolean = { false }): Boolean {
        val cleaned = clean(host)
        if (cleaned.isEmpty()) return false
        if (isLoopback(cleaned)) return false
        if (PrivateAddress.matchesHost(cleaned)) return true
        if (isBroadcastOrMulticast(cleaned)) return true
        return isLiteral(cleaned) && onLink(cleaned)
    }

    /**
     * What the address says about itself, before anything is dialled or
     * looked up.
     *
     * An mDNS name answers [Verdict.LOCAL] without a lookup, and must:
     * resolving a `.local` name *is* mDNS, which is itself blocked
     * without the permission, so asking that way could only ever
     * answer no.
     */
    fun literalVerdict(url: String, onLink: (String) -> Boolean = { false }): Verdict {
        val host = hostOf(url) ?: return Verdict.NOT_LOCAL
        if (isLoopback(host)) return Verdict.NOT_LOCAL
        if (host == "localhost" || host.endsWith(".localhost")) return Verdict.NOT_LOCAL
        if (host.endsWith(".local") || host.endsWith(".internal")) return Verdict.LOCAL
        if (isLiteral(host)) {
            return if (hostIsLocal(host, onLink)) Verdict.LOCAL else Verdict.NOT_LOCAL
        }
        return Verdict.RESOLVE
    }

    /**
     * Whether connecting to [url] means touching the local network.
     *
     * A name is resolved — a DNS lookup, which the restriction exempts,
     * so it works from behind the block — and *any* address it stands
     * for being local is enough. A name that resolves to nothing is not
     * local; it is about to fail as an unknown host either way.
     *
     * This is a preflight, and the client will resolve again when it
     * dials. A record that rotates under the phone can put a different
     * address on the wire than the one judged here. The cost of being
     * wrong is one timed-out attempt in front of a reader who is being
     * told what to do about it, which is a better trade than teaching
     * the HTTP client to share a resolver.
     */
    suspend fun isLocal(
        url: String,
        onLink: (String) -> Boolean = { false },
        resolver: Resolver = system,
    ): Boolean = when (literalVerdict(url, onLink)) {
        Verdict.LOCAL -> true
        Verdict.NOT_LOCAL -> false
        Verdict.RESOLVE -> {
            val host = hostOf(url)
            withContext(Dispatchers.IO) {
                host?.let { resolver.addresses(it) }.orEmpty().any { hostIsLocal(it, onLink) }
            }
        }
    }

    /**
     * The host an address will actually be dialled at.
     *
     * Taken from [RemoteUrl.normaliseBase], because the form takes
     * `books.lan:8083` with no scheme and every setup client adds one
     * before dialling; reading the typed text as a URL answers null and
     * would call a LAN address public. The setup clients disagree about
     * defaults and paths, never about the host, so one normalisation
     * serves them all.
     */
    private fun hostOf(url: String): String? {
        val base = RemoteUrl.normaliseBase(url) ?: return null
        val authority = base.substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringAfterLast('@')
        val host = if (authority.startsWith("[")) {
            authority.drop(1).substringBefore(']')
        } else {
            authority.substringBefore(':')
        }
        return clean(host).takeIf { it.isNotEmpty() }
    }

    /** Lowercased, unbracketed, and without an IPv6 zone. */
    private fun clean(host: String): String =
        host.lowercase().trim('[', ']').substringBefore('%').trim()

    /**
     * Loopback, in any of the spellings this app can be handed.
     *
     * The IPv4-mapped form matters: `[::ffff:127.0.0.1]` is 127.0.0.1
     * with a longer name, loopback is exempt from the restriction, and
     * reading it as an ordinary private address would raise a prompt
     * for a connection that was never going to be blocked. The
     * all-zeros test is anchored on the last group, because `1::` is a
     * global address that the digits alone would have called loopback.
     */
    private fun isLoopback(host: String): Boolean {
        if (':' !in host) return host.substringBefore('.').toIntOrNull() == 127
        val tail = host.substringAfterLast(':')
        if ('.' in tail) return tail.substringBefore('.').toIntOrNull() == 127
        val groups = host.split(':')
        if (groups.last().trimStart('0') != "1") return false
        return groups.dropLast(1).all { it.trimStart('0').isEmpty() }
    }

    private fun isLiteral(host: String): Boolean =
        ':' in host || host.split('.').let { parts ->
            parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
        }

    private fun isBroadcastOrMulticast(host: String): Boolean {
        if (':' in host) return host.startsWith("ff")
        if (host == "255.255.255.255") return true
        val first = host.substringBefore('.').toIntOrNull() ?: return false
        return first in 224..239
    }
}
