package com.chmouel.liseur.data.remote

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which addresses cost a reader a permission prompt.
 *
 * Getting this wrong is expensive in both directions: too eager and a
 * reader whose library is on the open internet is asked to let the app
 * see their home network for no reason, too shy and they wait out a
 * timeout with nothing to show for it. The route predicate and the
 * resolver are both injected, so none of this touches a network or an
 * emulator.
 */
class LocalNetworkAddressTest {

    private val nothingOnLink: (String) -> Boolean = { false }

    private fun resolving(vararg answers: Pair<String, List<String>>) =
        LocalNetworkAddress.Resolver { host -> answers.toMap()[host].orEmpty() }

    private val refusing = LocalNetworkAddress.Resolver {
        error("nothing here should be resolved")
    }

    @Test
    fun `private literals are local`() = runTest {
        assertTrue(LocalNetworkAddress.isLocal("192.168.1.20", resolver = refusing))
        assertTrue(LocalNetworkAddress.isLocal("http://10.0.0.5:8083", resolver = refusing))
        assertTrue(LocalNetworkAddress.isLocal("https://172.16.4.1/opds", resolver = refusing))
        assertTrue(LocalNetworkAddress.isLocal("http://[fd00::1]:8083", resolver = refusing))
        assertTrue(LocalNetworkAddress.isLocal("http://[fe80::1]", resolver = refusing))
    }

    @Test
    fun `broadcast and multicast are local`() = runTest {
        assertTrue(LocalNetworkAddress.isLocal("255.255.255.255", resolver = refusing))
        assertTrue(LocalNetworkAddress.isLocal("224.0.0.251", resolver = refusing))
        assertTrue(LocalNetworkAddress.isLocal("http://[ff02::fb]", resolver = refusing))
    }

    @Test
    fun `an interface zone does not hide a link-local address`() = runTest {
        assertTrue(LocalNetworkAddress.isLocal("http://[fe80::1%25wlan0]:8083", resolver = refusing))
    }

    @Test
    fun `loopback is exempt and must not raise a prompt`() = runTest {
        assertFalse(LocalNetworkAddress.isLocal("http://127.0.0.1:8083", resolver = refusing))
        assertFalse(LocalNetworkAddress.isLocal("http://[::1]:8083", resolver = refusing))
        assertFalse(LocalNetworkAddress.isLocal("http://localhost:8083", resolver = refusing))
    }

    /**
     * `::ffff:127.0.0.1` is 127.0.0.1 with a longer name, and reading
     * it as an ordinary private address would ask for a permission
     * that the connection was never going to need. `1::` is the
     * opposite mistake: a global address that a digits-only test calls
     * loopback, and then never judges at all.
     */
    @Test
    fun `loopback is still loopback in its longer spellings`() = runTest {
        assertFalse(
            LocalNetworkAddress.isLocal("http://[::ffff:127.0.0.1]:8083", resolver = refusing),
        )
        assertFalse(
            LocalNetworkAddress.isLocal("http://[0:0:0:0:0:0:0:1]:8083", resolver = refusing),
        )
        // The same address in hex, which is what a resolver returns as
        // readily as the dotted form.
        assertFalse(
            LocalNetworkAddress.isLocal("http://[::ffff:7f00:1]:8083", resolver = refusing),
        )
        assertFalse(LocalNetworkAddress.isLocal("http://127.1.2.3:8083", resolver = refusing))
        assertTrue(
            LocalNetworkAddress.isLocal(
                "http://[1::]:8083",
                onLink = { true },
                resolver = refusing,
            ),
        )
    }

    /**
     * A name spelled out to its root is the same name. Reading the
     * trailing dot as part of it would send an mDNS address off to be
     * resolved by mDNS, which is what the permission is blocking.
     */
    @Test
    fun `a name written out to the root label is the same name`() = runTest {
        assertTrue(LocalNetworkAddress.isLocal("http://books.local.:8083", resolver = refusing))
        assertFalse(LocalNetworkAddress.isLocal("http://localhost.:8083", resolver = refusing))
    }

    /**
     * A tailnet address is carried by a tunnel, which the restriction
     * does not reach. An ISP that hands the same range out on the LAN
     * is a different matter, and the routes are what tell them apart.
     */
    @Test
    fun `carrier-grade NAT is judged by the routes and not by the range`() = runTest {
        assertFalse(LocalNetworkAddress.isLocal("100.64.0.1", resolver = refusing))
        assertTrue(
            LocalNetworkAddress.isLocal(
                "100.64.0.1",
                onLink = { it == "100.64.0.1" },
                resolver = refusing,
            ),
        )
    }

    /** The reported bug with a different address in it. */
    @Test
    fun `a global address in the phone's own prefix is local`() = runTest {
        val address = "2001:db8:1::10"
        assertFalse(LocalNetworkAddress.isLocal("http://[$address]", resolver = refusing))
        assertTrue(
            LocalNetworkAddress.isLocal(
                "http://[$address]",
                onLink = { it == address },
                resolver = refusing,
            ),
        )
    }

    @Test
    fun `a scheme-less address with a port is still read as a literal`() {
        assertEquals(
            LocalNetworkAddress.Verdict.LOCAL,
            LocalNetworkAddress.literalVerdict("192.168.1.20:8083"),
        )
        assertEquals(
            LocalNetworkAddress.Verdict.LOCAL,
            LocalNetworkAddress.literalVerdict("HTTP://192.168.1.20/opds"),
        )
        assertEquals(
            LocalNetworkAddress.Verdict.RESOLVE,
            LocalNetworkAddress.literalVerdict("books.example.com:8083"),
        )
    }

    /**
     * Resolving a `.local` name is mDNS, which is the very thing the
     * permission gates, so asking that way could only answer no.
     */
    @Test
    fun `an mDNS name answers without a lookup`() = runTest {
        assertEquals(
            LocalNetworkAddress.Verdict.LOCAL,
            LocalNetworkAddress.literalVerdict("http://books.local:8083"),
        )
        assertTrue(LocalNetworkAddress.isLocal("http://books.local:8083", resolver = refusing))
        assertTrue(LocalNetworkAddress.isLocal("http://books.internal", resolver = refusing))
    }

    @Test
    fun `a public name resolving into public space is not local`() = runTest {
        val resolver = resolving("books.example.com" to listOf("203.0.113.10"))
        assertFalse(LocalNetworkAddress.isLocal("https://books.example.com", resolver = resolver))
    }

    @Test
    fun `a public name resolving into private space is local`() = runTest {
        val resolver = resolving("books.example.com" to listOf("192.168.1.20"))
        assertTrue(LocalNetworkAddress.isLocal("https://books.example.com", resolver = resolver))
    }

    @Test
    fun `one local answer among several is enough`() = runTest {
        val resolver = resolving(
            "books.example.com" to listOf("203.0.113.10", "2001:db8::1", "10.1.2.3"),
        )
        assertTrue(LocalNetworkAddress.isLocal("https://books.example.com", resolver = resolver))
    }

    @Test
    fun `a name that resolves to nothing is not local`() = runTest {
        assertFalse(
            LocalNetworkAddress.isLocal("https://nowhere.example.com", resolver = resolving()),
        )
    }

    @Test
    fun `an address the app could never dial is not local`() = runTest {
        assertFalse(LocalNetworkAddress.isLocal("", resolver = refusing))
        assertFalse(LocalNetworkAddress.isLocal("   ", resolver = refusing))
        assertFalse(LocalNetworkAddress.isLocal("http://", resolver = refusing))
    }

    @Test
    fun `a host on its own is judged the same way as a URL`() {
        assertTrue(LocalNetworkAddress.hostIsLocal("192.168.1.20"))
        assertTrue(LocalNetworkAddress.hostIsLocal("FD00::1"))
        assertFalse(LocalNetworkAddress.hostIsLocal("203.0.113.10"))
        assertFalse(LocalNetworkAddress.hostIsLocal(""))
    }
}
