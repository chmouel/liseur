package com.chmouel.liseur.data.bookorbit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The names a BookOrbit book carries in the library.
 *
 * These are as good as schema: `books.url` is what reading positions and
 * highlights hang off, and `remote_uuid` is both a catalog key and a
 * download filename. What is being pinned here is that two servers — or
 * two accounts on one server — cannot answer to the same name.
 */
class BookOrbitScopeTest {

    @Test
    fun `two servers issuing the same book id name two books`() {
        val first = BookOrbitScope.remoteId("https://a.example", "1", 113)
        val second = BookOrbitScope.remoteId("https://b.example", "1", 113)

        assertNotEquals(first, second)
    }

    @Test
    fun `two accounts on one server name two books`() {
        // Otherwise a login change would let one reader's highlights be
        // adopted by the next person to sign in.
        val mine = BookOrbitScope.remoteId("https://a.example", "1", 113)
        val theirs = BookOrbitScope.remoteId("https://a.example", "2", 113)

        assertNotEquals(mine, theirs)
    }

    @Test
    fun `the same book read the same way is always the same name`() {
        val once = BookOrbitScope.remoteId("https://a.example/", "7", 4)
        val again = BookOrbitScope.remoteId("https://a.example", "7", 4)

        assertEquals(once, again)
    }

    @Test
    fun `a name is safe to spell into a filename`() {
        val id = BookOrbitScope.remoteId("https://a.example", "1", 113)

        assertTrue(id, id.all { it.isLetterOrDigit() || it == '_' })
        assertTrue(id, id.length < 80)
    }

    /**
     * The two halves are length-delimited, so a book cannot be made to
     * collide by moving a character across the boundary between them.
     */
    @Test
    fun `a longer address and a longer account do not run together`() {
        val one = BookOrbitScope.fingerprint("https://a.example/1", "2")
        val other = BookOrbitScope.fingerprint("https://a.example/12", "")

        assertNotEquals(one, other)
    }

    @Test
    fun `a scheme and a host are compared without case, a path is kept`() {
        assertEquals(
            "https://a.example/proxy",
            BookOrbitScope.canonicalBase("HTTPS://A.EXAMPLE/proxy/"),
        )
        assertTrue(BookOrbitScope.sameServer("https://a.example", "https://a.example/"))
        assertEquals(false, BookOrbitScope.sameServer("https://a.example", "https://a.example/proxy"))
    }

    @Test
    fun `an id that is not a BookOrbit one has no scope`() {
        assertNull(ServerKindRemoteId.probe("komga:113"))
    }

    private object ServerKindRemoteId {
        fun probe(url: String): String? =
            com.chmouel.liseur.data.remote.ServerKind.BOOKORBIT.remoteId(url)
    }
}
