package com.chmouel.liseur.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live half of the local-network judgement: which addresses the
 * phone's own interfaces put on the local network.
 */
class OnLinkPrefixesTest {

    private fun link(address: String, prefixLength: Int) =
        OnLinkPrefixes.Link(address, prefixLength)

    /**
     * A phone on mobile data, as the modem actually describes one: a
     * point-to-point IPv4 address on a /32 and a global IPv6 address in
     * a /64.
     *
     * This is #241. The route form of this check called every address
     * in the world local here, because the cellular default route is
     * `0.0.0.0/0` and `::/0` with no gateway, and a gateway-less route
     * matching an address was the whole test. A reader on LTE could not
     * reach Project Gutenberg, or anything else.
     */
    @Test
    fun `a mobile connection does not put the whole internet on the local network`() {
        val cellular = listOf(
            link("10.99.61.153", 32),
            link("2405:dc00:ec25:199a:a7dd:11ef:c0aa:319a", 64),
        )
        assertFalse(OnLinkPrefixes.contains(cellular, "152.19.134.47"))
        assertFalse(OnLinkPrefixes.contains(cellular, "2610:28:3090:3000:0:bad:cafe:47"))
        // A DNS64 phone synthesises an AAAA for an IPv4-only host, in
        // the carrier's NAT64 prefix rather than the phone's own /64.
        assertFalse(OnLinkPrefixes.contains(cellular, "2405:dc00:0:3::9813:862f"))
    }

    /**
     * A prefix length of zero arms nothing. It is what a default route
     * would have carried, and it is the guard the platform writes down
     * explicitly.
     */
    @Test
    fun `a zero length prefix puts nothing on the local network`() {
        assertFalse(OnLinkPrefixes.contains(listOf(link("::", 0)), "2001:db8:1::10"))
        assertFalse(OnLinkPrefixes.contains(listOf(link("0.0.0.0", 0)), "152.19.134.47"))
    }

    /**
     * #195, which is why the live half exists at all: a home server on
     * a global IPv6 address in the phone's own prefix is blocked, and
     * no list of private ranges will ever say so.
     */
    @Test
    fun `a global address in the phone's own prefix is on link`() {
        val wifi = listOf(link("2001:db8:1:0:1c2d:3e4f:5a6b:7c8d", 64))
        assertTrue(OnLinkPrefixes.contains(wifi, "2001:db8:1::10"))
        // The neighbouring prefix is somebody else's.
        assertFalse(OnLinkPrefixes.contains(wifi, "2001:db8:2::10"))
    }

    /**
     * An interface's own IPv4 address arms the whole private range it
     * sits in rather than its own subnet, which is how the platform
     * reads it. A second subnet behind the same router is reachable and
     * is blocked in the same way, so the widening is the right shape.
     */
    @Test
    fun `an IPv4 address arms the whole private range it sits in`() {
        val wifi = listOf(link("192.168.1.5", 24))
        assertTrue(OnLinkPrefixes.contains(wifi, "192.168.1.20"))
        assertTrue(OnLinkPrefixes.contains(wifi, "192.168.9.9"))
        assertFalse(OnLinkPrefixes.contains(wifi, "10.0.0.5"))
        assertFalse(OnLinkPrefixes.contains(wifi, "152.19.134.47"))

        // The point-to-point mobile address from the capture above
        // arms the whole of 10.0.0.0/8 the same way.
        assertTrue(OnLinkPrefixes.contains(listOf(link("10.99.61.153", 32)), "10.0.0.5"))
    }

    /**
     * Carrier-grade NAT is the range this half exists to decide, and
     * the reason it is missing from the fixed half. A tailnet address
     * lives there, is carried by a tunnel, and must raise no prompt; an
     * ISP handing the same range out on the LAN puts one on a real
     * interface, and gets asked about.
     */
    @Test
    fun `carrier-grade NAT is on link only where an interface holds one`() {
        assertFalse(OnLinkPrefixes.contains(listOf(link("192.168.1.5", 24)), "100.64.0.1"))
        assertTrue(OnLinkPrefixes.contains(listOf(link("100.64.3.7", 16)), "100.64.0.1"))
    }

    /**
     * The two ranges that do not end on a byte are where masking goes
     * wrong, so both edges of each are pinned down. `100.64.0.0/10`
     * runs to `100.127.255.255` and `172.16.0.0/12` to
     * `172.31.255.255`; the addresses either side of those belong to
     * somebody else.
     */
    @Test
    fun `a range that does not end on a byte stops where it should`() {
        val cgnat = listOf(link("100.64.3.7", 16))
        assertTrue(OnLinkPrefixes.contains(cgnat, "100.64.0.0"))
        assertTrue(OnLinkPrefixes.contains(cgnat, "100.127.255.255"))
        assertFalse(OnLinkPrefixes.contains(cgnat, "100.63.255.255"))
        assertFalse(OnLinkPrefixes.contains(cgnat, "100.128.0.0"))

        val private12 = listOf(link("172.20.1.1", 16))
        assertTrue(OnLinkPrefixes.contains(private12, "172.16.0.0"))
        assertTrue(OnLinkPrefixes.contains(private12, "172.31.255.255"))
        assertFalse(OnLinkPrefixes.contains(private12, "172.15.255.255"))
        assertFalse(OnLinkPrefixes.contains(private12, "172.32.0.0"))
    }

    /**
     * An interface whose own prefix is wider than the range cannot arm
     * it. The platform asks whether the fixed range contains the
     * interface's prefix, not merely its address, and a /9 spills out
     * of a /10 on both sides.
     */
    @Test
    fun `an interface prefix wider than the range arms nothing`() {
        assertFalse(OnLinkPrefixes.contains(listOf(link("100.64.1.1", 9)), "100.64.0.1"))
        assertTrue(OnLinkPrefixes.contains(listOf(link("100.64.1.1", 10)), "100.64.0.1"))
    }

    /** IPv6 prefixes that do not end on a byte, and the two extremes. */
    @Test
    fun `IPv6 prefixes of every length are masked correctly`() {
        assertTrue(OnLinkPrefixes.contains(listOf(link("2001:db8::1", 1)), "3fff::1"))
        assertFalse(OnLinkPrefixes.contains(listOf(link("2001:db8::1", 1)), "8000::1"))

        val sixtyFive = listOf(link("2001:db8:1:2::1", 65))
        assertTrue(OnLinkPrefixes.contains(sixtyFive, "2001:db8:1:2:7fff::9"))
        assertFalse(OnLinkPrefixes.contains(sixtyFive, "2001:db8:1:2:8000::9"))

        val oneTwentySeven = listOf(link("2001:db8::4", 127))
        assertTrue(OnLinkPrefixes.contains(oneTwentySeven, "2001:db8::5"))
        assertFalse(OnLinkPrefixes.contains(oneTwentySeven, "2001:db8::6"))

        val host = listOf(link("2001:db8::4", 128))
        assertTrue(OnLinkPrefixes.contains(host, "2001:db8::4"))
        assertFalse(OnLinkPrefixes.contains(host, "2001:db8::5"))
    }

    /**
     * An interface with a public address arms nothing. Only the
     * platform's five ranges can be armed, and a public address is in
     * none of them.
     */
    @Test
    fun `a public IPv4 interface arms nothing`() {
        val links = listOf(link("152.19.134.47", 24))
        assertFalse(OnLinkPrefixes.contains(links, "152.19.134.48"))
        assertFalse(OnLinkPrefixes.contains(links, "192.168.1.20"))
    }

    /** The two families are judged apart, and never against each other. */
    @Test
    fun `an address is never judged against the other family`() {
        assertFalse(OnLinkPrefixes.contains(listOf(link("192.168.1.5", 24)), "2001:db8:1::10"))
        assertFalse(OnLinkPrefixes.contains(listOf(link("2001:db8:1::5", 64)), "192.168.1.20"))
    }

    /**
     * An interface spells its own link-local address with the zone it
     * belongs to, and the zone says nothing about which prefix it is
     * in. An IPv4 address wearing an IPv6 coat is still that address.
     */
    @Test
    fun `an address is read in the spelling the phone uses`() {
        assertTrue(OnLinkPrefixes.contains(listOf(link("fe80::1%wlan0", 64)), "fe80::abcd"))
        assertTrue(
            OnLinkPrefixes.contains(listOf(link("192.168.1.5", 24)), "::ffff:192.168.1.20"),
        )
        assertTrue(OnLinkPrefixes.contains(listOf(link("::ffff:192.168.1.5", 24)), "192.168.1.20"))
    }

    /** Nothing to judge against, and nothing that parses, both answer no. */
    @Test
    fun `an empty or unreadable address is not on link`() {
        assertFalse(OnLinkPrefixes.contains(emptyList(), "192.168.1.20"))
        assertFalse(OnLinkPrefixes.contains(listOf(link("192.168.1.5", 24)), "books.local"))
        assertFalse(OnLinkPrefixes.contains(listOf(link("192.168.1.5", 24)), ""))
        assertFalse(OnLinkPrefixes.contains(listOf(link("not an address", 24)), "192.168.1.20"))
    }

    /**
     * Nothing malformed throws. The phone is asked for these addresses
     * and answers in its own spelling, and a crash in a preflight would
     * be a worse outcome than the timeout it exists to avoid.
     */
    @Test
    fun `a malformed address is refused rather than thrown at`() {
        val links = listOf(link("2001:db8::1", 64), link("192.168.1.5", 24))
        listOf(
            "2001:db8::1::2",
            "2001:db8:::1",
            "fe80::gggg",
            "192.168.1",
            "192.168.1.256",
            "192.168.1.1.1",
            "::ffff:192.168.1.300",
            "192.168.1.-1",
            "%wlan0",
            "[]",
        ).forEach { assertFalse(it, OnLinkPrefixes.contains(links, it)) }
        assertFalse(OnLinkPrefixes.contains(listOf(link("2001:db8::1::2", 64)), "2001:db8::5"))
    }
}
