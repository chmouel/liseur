package com.chmouel.liseur.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class Sha1HexTest {

    @Test
    fun `hashes known value`() {
        // Reference value from `printf 'hello' | sha1sum`.
        assertEquals("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d", sha1Hex("hello"))
    }

    @Test
    fun `is stable for the same input`() {
        val url = "content://com.android.externalstorage.documents/tree/primary%3ABooks/book.epub"
        assertEquals(sha1Hex(url), sha1Hex(url))
    }

    @Test
    fun `differs for different inputs`() {
        assertNotEquals(sha1Hex("a.epub"), sha1Hex("b.epub"))
    }

    @Test
    fun `produces lowercase hex of 40 chars`() {
        val hash = sha1Hex("Pride and Prejudice")
        assertEquals(40, hash.length)
        assertEquals(hash.lowercase(), hash)
    }
}
