package com.chmouel.liseur.translate

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class TranslationRequestsTest {

    @Test
    fun `a reply whose connection changed while it was asked is asked again`() = runTest {
        var generation = 0
        val requests = TranslationRequests { generation }
        var calls = 0
        val reply = requests.run({ "https://openrouter.ai" }) {
            calls++
            // The key is replaced while the first request is out.
            if (calls == 1) generation++
            "reply $calls"
        }
        assertEquals("reply 2", reply)
        assertEquals(2, calls)
    }

    @Test
    fun `a failure whose connection changed while it was asked is asked again`() = runTest {
        var generation = 0
        val requests = TranslationRequests { generation }
        var calls = 0
        val reply = requests.run({ "https://openrouter.ai" }) {
            calls++
            // The old key is refused while a new one is saved.
            if (calls == 1) {
                generation++
                throw TranslationError.InvalidKey(401)
            }
            "reply $calls"
        }
        assertEquals("reply 2", reply)
    }

    @Test
    fun `a failure on an unchanged connection is the answer`() = runTest {
        val requests = TranslationRequests { 0 }
        try {
            requests.run({ "https://openrouter.ai" }) { throw TranslationError.InvalidKey(401) }
            fail("Swallowed the failure")
        } catch (_: TranslationError.InvalidKey) {
        }
    }

    @Test
    fun `a connection that keeps changing gives up`() = runTest {
        var generation = 0
        val requests = TranslationRequests { generation }
        try {
            requests.run({ "gemini" }) {
                generation++
                "late"
            }
            fail("Returned a reply from an old connection")
        } catch (_: TranslationError.Changed) {
        }
    }

    @Test
    fun `the device has no connection to change`() = runTest {
        val requests = TranslationRequests { error("asked for a generation") }
        assertEquals("ok", requests.run({ null }) { "ok" })
    }

    @Test
    fun `a retry on another server is judged by that server's connection`() = runTest {
        val generations = mutableMapOf("https://a.example" to 0, "https://b.example" to 0)
        val requests = TranslationRequests { generations.getValue(it) }
        var owner = "https://a.example"
        var calls = 0
        val reply = requests.run({ owner }) {
            calls++
            when (calls) {
                // A's address is moved to B while the first request is out.
                1 -> {
                    generations["https://a.example"] = 1
                    owner = "https://b.example"
                }
                // B's key is replaced while the retry is out.
                2 -> generations["https://b.example"] = 1
            }
            "reply $calls"
        }
        assertEquals("reply 3", reply)
    }

    @Test
    fun `a reply is not kept when the service moved to another server during it`() = runTest {
        val requests = TranslationRequests { 0 }
        var owner = "https://a.example"
        var calls = 0
        val reply = requests.run({ owner }) {
            calls++
            if (calls == 1) owner = "https://b.example"
            "reply $calls"
        }
        assertEquals("reply 2", reply)
    }
}
