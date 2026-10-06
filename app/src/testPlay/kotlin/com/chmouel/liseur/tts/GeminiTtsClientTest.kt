package com.chmouel.liseur.tts

import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class GeminiTtsClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stop() {
        server.close()
    }

    private fun client() = GeminiTtsClient(endpoint = server.url("/v1beta/interactions"))

    private fun audioBody(
        pcm: ByteArray,
        mime: String = "audio/l16; rate=24000; channels=1",
        type: String = "audio",
    ) = JSONObject()
        .put("status", "completed")
        .put("unknown", JSONObject().put("ignored", true))
        .put(
            "steps",
            org.json.JSONArray().put(
                JSONObject().put("type", "model_output").put(
                    "content",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("type", type)
                            .put("mime_type", mime)
                            .put("sample_rate", 24000)
                            .put("channels", 1)
                            .put("data", Base64.getEncoder().encodeToString(pcm)),
                    ),
                ),
            ),
        )
        .toString()

    private suspend inline fun <reified E : SpeechError> expect(noinline block: suspend () -> Unit): E {
        try {
            block()
        } catch (e: SpeechError) {
            if (e is E) return e
            fail("expected ${E::class.simpleName}, got $e")
        }
        fail("expected ${E::class.simpleName}")
        error("unreachable")
    }

    @Test
    fun `sends the model, voice, raw PCM format and store false, with the key as a header`() = runBlocking {
        server.enqueue(MockResponse(code = 200, body = audioBody(byteArrayOf(1, 0, 2, 0))))

        val audio = client().synthesize("secret-key", "Bonjour le monde.", "Kore")

        assertArrayEquals(byteArrayOf(1, 0, 2, 0), audio.pcm)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("secret-key", request.headers["x-goog-api-key"])
        assertFalse(request.url.toString().contains("secret-key"))
        val body = JSONObject(request.body!!.utf8())
        assertEquals(GeminiTts.MODEL, body.getString("model"))
        assertEquals(false, body.getBoolean("store"))
        assertEquals("Bonjour le monde.", body.getString("input"))
        val format = body.getJSONObject("response_format")
        assertEquals("audio", format.getString("type"))
        assertEquals("audio/l16", format.getString("mime_type"))
        assertEquals(24000, format.getInt("sample_rate"))
        val voice = body.getJSONObject("generation_config").getJSONArray("speech_config").getJSONObject(0)
        assertEquals("Kore", voice.getString("voice"))
    }

    @Test
    fun `text-only, empty, odd-sized, wrongly typed or malformed answers are errors, not silence`() = runBlocking {
        val bad = listOf(
            audioBody(byteArrayOf(1, 0), type = "text"),
            audioBody(byteArrayOf()),
            audioBody(byteArrayOf(1, 0, 2)),
            audioBody(byteArrayOf(1, 0), mime = "audio/mpeg"),
            """{"status":"completed","steps":[]}""",
            """{"steps":[{"content":[{"type":"audio","mime_type":"audio/l16","data":"@@@"}]}]}""",
            "not json",
        )
        for (body in bad) {
            server.enqueue(MockResponse(code = 200, body = body))
            expect<SpeechError.InvalidResponse> { client().synthesize("k", "t", "Kore") }
        }
    }

    @Test
    fun `an answer over the size cap is refused`(): Unit = runBlocking {
        val buffer = Buffer().writeUtf8("""{"steps":[{"content":[{"type":"audio","mime_type":"audio/l16","data":"""")
        val chunk = "A".repeat(1 shl 16)
        repeat((GeminiTts.MAX_PCM_BYTES / 3 * 4) / chunk.length + 4) { buffer.writeUtf8(chunk) }
        buffer.writeUtf8("\"}]}]}")
        server.enqueue(MockResponse.Builder().code(200).body(buffer).build())

        expect<SpeechError.InvalidResponse> { client().synthesize("k", "t", "Kore") }
    }

    @Test
    fun `maps the API's error shapes`(): Unit = runBlocking {
        server.enqueue(
            MockResponse(
                code = 400,
                body = """[{"error":{"code":400,"status":"INVALID_ARGUMENT",
                    "details":[{"reason":"API_KEY_INVALID"}]}}]""",
            ),
        )
        expect<SpeechError.InvalidKey> { client().synthesize("k", "t", "Kore") }

        server.enqueue(MockResponse(code = 403, body = """{"error":{"status":"PERMISSION_DENIED"}}"""))
        expect<SpeechError.InvalidKey> { client().synthesize("k", "t", "Kore") }

        server.enqueue(MockResponse(code = 429, body = """{"error":{"status":"RESOURCE_EXHAUSTED"}}"""))
        expect<SpeechError.RateLimited> { client().synthesize("k", "t", "Kore") }

        server.enqueue(MockResponse(code = 400, body = """{"error":{"message":"No voice","code":"invalid_request"}}"""))
        val service = expect<SpeechError.Service> { client().synthesize("k", "t", "Kore") }
        assertEquals(400, service.code)

        server.enqueue(MockResponse(code = 503, body = "<html>"))
        expect<SpeechError.Service> { client().synthesize("k", "t", "Kore") }

        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
        expect<SpeechError.Network> { client().synthesize("k", "t", "Kore") }
    }

    @Test
    fun `errors never repeat the key or the text`(): Unit = runBlocking {
        server.enqueue(MockResponse(code = 401, body = """{"error":{"message":"bad key secret-key for Une phrase."}}"""))
        val error = expect<SpeechError.InvalidKey> { client().synthesize("secret-key", "Une phrase.", "Kore") }
        assertFalse(error.toString().contains("secret-key"))
        assertFalse(error.toString().contains("Une phrase"))
    }

    @Test
    fun `cancelling the caller cancels the request`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(audioBody(byteArrayOf(1, 0)))
                .headersDelay(10, TimeUnit.SECONDS)
                .build(),
        )
        var cancelled = false
        val okhttp = okhttp3.OkHttpClient.Builder()
            .eventListener(
                object : okhttp3.EventListener() {
                    override fun canceled(call: okhttp3.Call) {
                        cancelled = true
                    }
                },
            )
            .build()
        val client = GeminiTtsClient(okhttp, server.url("/v1beta/interactions"))
        val pending = async(kotlinx.coroutines.Dispatchers.Default) { client.synthesize("k", "t", "Kore") }
        server.takeRequest()
        delay(100)
        val started = System.nanoTime()
        pending.cancel()
        pending.join()
        assertTrue(pending.isCancelled)
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2))
        assertTrue("the HTTP call itself was cancelled", cancelled)
    }
}
