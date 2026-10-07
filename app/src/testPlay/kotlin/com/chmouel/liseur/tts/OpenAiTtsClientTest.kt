package com.chmouel.liseur.tts

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.HttpUrl
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenAiTtsClientTest {

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

    private val base: HttpUrl get() = OpenAiTts.baseUrl(server.url("/").toString())!!

    private fun pcm(bytes: ByteArray, type: String? = "audio/pcm") = MockResponse.Builder()
        .code(200)
        .apply { if (type != null) addHeader("Content-Type", type) }
        .body(Buffer().write(bytes))
        .build()

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
    fun `asks for raw PCM from the speech endpoint, with the key as a bearer token`(): Unit = runBlocking {
        server.enqueue(pcm(byteArrayOf(1, 0, 2, 0)))

        val audio = OpenAiTtsClient().synthesize(base, "secret-key", "Bonjour le monde.", "af_bella", "kokoro")

        assertArrayEquals(byteArrayOf(1, 0, 2, 0), audio.pcm)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/audio/speech", request.url.encodedPath)
        assertEquals("Bearer secret-key", request.headers["Authorization"])
        val body = JSONObject(request.body!!.utf8())
        assertEquals("kokoro", body.getString("model"))
        assertEquals("Bonjour le monde.", body.getString("input"))
        assertEquals("af_bella", body.getString("voice"))
        assertEquals("pcm", body.getString("response_format"))
    }

    @Test
    fun `sends no authorization header without a key`(): Unit = runBlocking {
        server.enqueue(pcm(byteArrayOf(1, 0)))
        server.enqueue(pcm(byteArrayOf(1, 0)))

        OpenAiTtsClient().synthesize(base, null, "t", "af_bella", "kokoro")
        OpenAiTtsClient().synthesize(base, "  ", "t", "af_bella", "kokoro")

        assertNull(server.takeRequest().headers["Authorization"])
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `octet streams and untyped bodies are taken as PCM`(): Unit = runBlocking {
        server.enqueue(pcm(byteArrayOf(1, 0), "application/octet-stream"))
        server.enqueue(pcm(byteArrayOf(3, 0), null))

        assertArrayEquals(byteArrayOf(1, 0), OpenAiTtsClient().synthesize(base, null, "t", "v", "kokoro").pcm)
        assertArrayEquals(byteArrayOf(3, 0), OpenAiTtsClient().synthesize(base, null, "t", "v", "kokoro").pcm)
    }

    @Test
    fun `empty, odd-sized, oversized or non-audio answers are errors, not silence`(): Unit = runBlocking {
        server.enqueue(pcm(ByteArray(0)))
        expect<SpeechError.InvalidResponse> { OpenAiTtsClient().synthesize(base, null, "t", "v", "kokoro") }

        server.enqueue(pcm(byteArrayOf(1, 0, 2)))
        expect<SpeechError.InvalidResponse> { OpenAiTtsClient().synthesize(base, null, "t", "v", "kokoro") }

        server.enqueue(pcm(ByteArray(SpeechAudio.MAX_PCM_BYTES + 2)))
        expect<SpeechError.InvalidResponse> { OpenAiTtsClient().synthesize(base, null, "t", "v", "kokoro") }

        server.enqueue(pcm("""{"ok":true}""".toByteArray(), "application/json"))
        expect<SpeechError.InvalidResponse> { OpenAiTtsClient().synthesize(base, null, "t", "v", "kokoro") }
    }

    @Test
    fun `maps refusals, a missing voice, throttling and failures`(): Unit = runBlocking {
        server.enqueue(MockResponse(code = 401, body = """{"detail":"Not authenticated"}"""))
        expect<SpeechError.InvalidKey> { OpenAiTtsClient().synthesize(base, "k", "t", "v", "kokoro") }

        server.enqueue(MockResponse(code = 403))
        expect<SpeechError.InvalidKey> { OpenAiTtsClient().synthesize(base, "k", "t", "v", "kokoro") }

        server.enqueue(MockResponse(code = 400, body = """{"detail":"Voice 'nope' is not available"}"""))
        expect<SpeechError.InvalidVoice> { OpenAiTtsClient().synthesize(base, null, "t", "nope", "kokoro") }

        server.enqueue(MockResponse(code = 400, body = """{"detail":"Unsupported language"}"""))
        assertEquals(400, expect<SpeechError.Service> { OpenAiTtsClient().synthesize(base, null, "t", "v", "kokoro") }.code)

        server.enqueue(MockResponse(code = 422, body = """{"detail":[{"msg":"voice field required"}]}"""))
        assertEquals(422, expect<SpeechError.Service> { OpenAiTtsClient().synthesize(base, null, "t", "v", "kokoro") }.code)

        server.enqueue(MockResponse(code = 429))
        expect<SpeechError.RateLimited> { OpenAiTtsClient().synthesize(base, null, "t", "v", "kokoro") }

        server.enqueue(MockResponse(code = 500, body = "<html>"))
        expect<SpeechError.Service> { OpenAiTtsClient().synthesize(base, null, "t", "v", "kokoro") }

        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
        expect<SpeechError.Network> { OpenAiTtsClient().synthesize(base, null, "t", "v", "kokoro") }
    }

    @Test
    fun `errors never repeat the key or the text`(): Unit = runBlocking {
        server.enqueue(MockResponse(code = 401, body = """{"detail":"bad key secret-key for Une phrase."}"""))
        val error = expect<SpeechError.InvalidKey> { OpenAiTtsClient().synthesize(base, "secret-key", "Une phrase.", "v", "kokoro") }
        assertFalse(error.toString().contains("secret-key"))
        assertFalse(error.toString().contains("Une phrase"))
    }

    @Test
    fun `a key that cannot be a header is an invalid key, without being repeated`(): Unit = runBlocking {
        val error = expect<SpeechError.InvalidKey> { OpenAiTtsClient().synthesize(base, "secret\nkey", "t", "v", "kokoro") }
        assertFalse(error.toString().contains("secret"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a redirect is not followed, so the key and text stay on the server`(): Unit = runBlocking {
        MockWebServer().use { elsewhere ->
            elsewhere.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(
                MockResponse.Builder()
                    .code(307)
                    .addHeader("Location", elsewhere.url("/stolen").toString())
                    .build(),
            )

            val error = expect<SpeechError.Service> { OpenAiTtsClient().synthesize(base, "secret-key", "Bonjour.", "v", "kokoro") }

            assertEquals(307, error.code)
            assertEquals(0, elsewhere.requestCount)
        }
    }

    @Test
    fun `lists voices from an object, a bare array or voice objects`(): Unit = runBlocking {
        server.enqueue(MockResponse(code = 200, body = """{"voices":["af_bella","am_adam","af_bella",""]}"""))
        server.enqueue(MockResponse(code = 200, body = """["ff_siwis"]"""))
        server.enqueue(MockResponse(code = 200, body = """{"voices":[{"id":"bf_emma"},{"name":"bm_george"}]}"""))

        assertEquals(listOf("af_bella", "am_adam"), OpenAiTtsClient().voices(base, "k"))
        assertEquals(listOf("ff_siwis"), OpenAiTtsClient().voices(base, null))
        assertEquals(listOf("bf_emma", "bm_george"), OpenAiTtsClient().voices(base, null))

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/audio/voices", request.url.encodedPath)
        assertEquals("Bearer k", request.headers["Authorization"])
    }

    @Test
    fun `a voice list that is not one is an error`(): Unit = runBlocking {
        server.enqueue(MockResponse(code = 200, body = "<html>"))
        expect<SpeechError.InvalidResponse> { OpenAiTtsClient().voices(base, null) }

        server.enqueue(MockResponse(code = 200, body = """{"models":[]}"""))
        expect<SpeechError.InvalidResponse> { OpenAiTtsClient().voices(base, null) }

        server.enqueue(MockResponse(code = 401))
        expect<SpeechError.InvalidKey> { OpenAiTtsClient().voices(base, "k") }
    }

    @Test
    fun `server addresses are read the way people type them`() {
        assertEquals("http://192.168.1.18:8880/v1", OpenAiTts.baseUrl(" 192.168.1.18:8880 ").toString())
        assertEquals("http://pi.lan:8880/v1", OpenAiTts.baseUrl("http://pi.lan:8880/").toString())
        assertEquals("https://tts.example.com/v1", OpenAiTts.baseUrl("https://tts.example.com/v1/").toString())
        assertEquals("https://example.com/kokoro/v1", OpenAiTts.baseUrl("https://example.com/kokoro").toString())
        assertEquals("https://api.example.com/v1/openai", OpenAiTts.baseUrl("https://api.example.com/v1/openai").toString())
        assertNull(OpenAiTts.baseUrl(""))
        assertNull(OpenAiTts.baseUrl("   "))
        assertNull(OpenAiTts.baseUrl("ftp://pi.lan"))
        assertNull(OpenAiTts.baseUrl("http://"))
    }

    @Test
    fun `a server under a path keeps it`(): Unit = runBlocking {
        server.enqueue(pcm(byteArrayOf(1, 0)))
        OpenAiTtsClient().synthesize(OpenAiTts.baseUrl(server.url("/kokoro/v1").toString())!!, null, "t", "v", "kokoro")
        assertEquals("/kokoro/v1/audio/speech", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `a hosted API root is used as is, with the model it names`(): Unit = runBlocking {
        server.enqueue(pcm(byteArrayOf(1, 0)))
        val root = OpenAiTts.baseUrl(server.url("/v1/openai").toString())!!

        OpenAiTtsClient().synthesize(root, "k", "t", "af_heart", "hexgrad/Kokoro-82M")

        val request = server.takeRequest()
        assertEquals("/v1/openai/audio/speech", request.url.encodedPath)
        assertEquals("hexgrad/Kokoro-82M", JSONObject(request.body!!.utf8()).getString("model"))
    }

    @Test
    fun `a service with no voice list offers OpenAI's voices`(): Unit = runBlocking {
        server.enqueue(MockResponse(code = 404, body = """{"detail":"Not Found"}"""))
        server.enqueue(MockResponse(code = 405))

        assertEquals(OpenAiTts.STANDARD_VOICES, OpenAiTtsClient().voices(base, "k"))
        assertEquals(OpenAiTts.STANDARD_VOICES, OpenAiTtsClient().voices(base, "k"))
    }

    @Test
    fun `lists models from OpenAI's shape or a bare array, with the key`(): Unit = runBlocking {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"object":"list","data":[{"id":"gpt-4o-mini-tts"},{"id":"whisper-1"},{"id":"tts-1"}]}""",
            ),
        )
        server.enqueue(MockResponse(code = 200, body = """["kokoro"]"""))

        assertEquals(listOf("gpt-4o-mini-tts", "whisper-1", "tts-1"), OpenAiTtsClient().models(base, "k"))
        val request = server.takeRequest()
        assertEquals("/v1/models", request.url.encodedPath)
        assertEquals("Bearer k", request.headers["Authorization"])
        assertEquals(listOf("kokoro"), OpenAiTtsClient().models(base, null))
    }

    @Test
    fun `a service with no model list offers none, and other failures are errors`(): Unit = runBlocking {
        server.enqueue(MockResponse(code = 404))
        server.enqueue(MockResponse(code = 401))
        server.enqueue(MockResponse(code = 200, body = """{"voices":[]}"""))

        assertEquals(emptyList<String>(), OpenAiTtsClient().models(base, null))
        expect<SpeechError.InvalidKey> { OpenAiTtsClient().models(base, "k") }
        expect<SpeechError.InvalidResponse> { OpenAiTtsClient().models(base, null) }
    }

    @Test
    fun `only models that look like they speak are offered, unless none does`() {
        assertEquals(
            listOf("gpt-4o-mini-tts", "tts-1", "hexgrad/Kokoro-82M", "speech-02"),
            OpenAiTts.speechModels(listOf("gpt-4o", "gpt-4o-mini-tts", "whisper-1", "tts-1", "hexgrad/Kokoro-82M", "speech-02")),
        )
        assertEquals(listOf("my-voice", "other"), OpenAiTts.speechModels(listOf("my-voice", "other")))
        assertEquals(emptyList<String>(), OpenAiTts.speechModels(emptyList()))
    }

    @Test
    fun `cancelling the caller cancels the request`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "audio/pcm")
                .body(Buffer().write(byteArrayOf(1, 0)))
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
        val client = OpenAiTtsClient(okhttp)
        val pending = async(kotlinx.coroutines.Dispatchers.Default) { client.synthesize(base, null, "t", "v", "kokoro") }
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
