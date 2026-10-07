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
    fun `asks for WAV from the speech endpoint, with the key as a bearer token`(): Unit = runBlocking {
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
        assertEquals("wav", body.getString("response_format"))
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
    fun `a WAV answer is read by its header, at any rate`(): Unit = runBlocking {
        server.enqueue(pcm(WavPcmTest.wav(rate = 24_000, channels = 1, data = byteArrayOf(1, 0, 2, 0)), "audio/wav"))
        server.enqueue(pcm(WavPcmTest.wav(rate = 48_000, channels = 1, data = ByteArray(400)), "audio/x-wav"))

        assertArrayEquals(byteArrayOf(1, 0, 2, 0), OpenAiTtsClient().synthesize(base, null, "t", "v", "m").pcm)
        assertEquals(100, OpenAiTtsClient().synthesize(base, null, "t", "v", "m").frames)
    }

    @Test
    fun `a cut WAV header is an error, not noise`(): Unit = runBlocking {
        server.enqueue(pcm("RIFF\u0000\u0000\u0000\u0000WA".toByteArray(Charsets.ISO_8859_1), "audio/wav"))

        expect<SpeechError.InvalidResponse> { OpenAiTtsClient().synthesize(base, null, "t", "v", "m") }
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

        assertEquals(listOf("af_bella", "am_adam"), OpenAiTtsClient().voices(base, "k", "kokoro"))
        assertEquals(listOf("ff_siwis"), OpenAiTtsClient().voices(base, null, "kokoro"))
        assertEquals(listOf("bf_emma", "bm_george"), OpenAiTtsClient().voices(base, null, null))

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/audio/voices", request.url.encodedPath)
        assertEquals("Bearer k", request.headers["Authorization"])
    }

    @Test
    fun `a voice list that is not one is an error`(): Unit = runBlocking {
        server.enqueue(MockResponse(code = 200, body = "<html>"))
        expect<SpeechError.InvalidResponse> { OpenAiTtsClient().voices(base, null, "kokoro") }

        server.enqueue(MockResponse(code = 200, body = """{"models":[]}"""))
        expect<SpeechError.InvalidResponse> { OpenAiTtsClient().voices(base, null, "kokoro") }

        server.enqueue(MockResponse(code = 401))
        expect<SpeechError.InvalidKey> { OpenAiTtsClient().voices(base, "k", "kokoro") }
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

    /** A client whose requests to [host] reach the mock server instead, so a hosted service can be played. */
    private fun clientFor(host: String) = OpenAiTtsClient(
        okhttp3.OkHttpClient.Builder()
            .followRedirects(false)
            .addInterceptor { chain ->
                val url = chain.request().url
                val moved = if (url.host == host) {
                    url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
                } else {
                    url
                }
                chain.proceed(chain.request().newBuilder().url(moved).build())
            }
            .build(),
    )

    private val deepInfra = OpenAiTts.baseUrl("https://api.deepinfra.com/v1/openai")!!

    @Test
    fun `a service with no voice list has none to offer, so the voice is typed`(): Unit = runBlocking {
        server.enqueue(MockResponse(code = 404, body = """{"detail":"Not Found"}"""))
        server.enqueue(MockResponse(code = 404))
        server.enqueue(MockResponse(code = 405))
        server.enqueue(MockResponse(code = 200, body = """{"data":[{"id":"m"}]}"""))

        assertEquals(emptyList<String>(), OpenAiTtsClient().voices(base, "k", "kokoro"))
        assertEquals(emptyList<String>(), OpenAiTtsClient().voices(OpenAiTts.baseUrl(server.url("/v1/openai").toString())!!, "k", "m"))
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `OpenAI, which has no voice list, offers its own voices for the model`(): Unit = runBlocking {
        val openAi = OpenAiTts.baseUrl("https://api.openai.com/v1")!!
        val legacy = listOf("alloy", "ash", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer")
        val current = listOf(
            "marin", "cedar", "alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse",
        )
        repeat(4) { server.enqueue(MockResponse(code = 404)) }

        assertEquals(current, clientFor("api.openai.com").voices(openAi, "k", "gpt-4o-mini-tts"))
        assertEquals(legacy, clientFor("api.openai.com").voices(openAi, "k", "tts-1"))
        assertEquals(legacy, clientFor("api.openai.com").voices(openAi, "k", "tts-1-hd"))
        assertEquals(current, clientFor("api.openai.com").voices(openAi, "k", null))
    }

    @Test
    fun `OpenAI's models start with gpt-4o-mini-tts`(): Unit = runBlocking {
        server.enqueue(
            MockResponse(code = 200, body = """{"data":[{"id":"tts-1"},{"id":"whisper-1"},{"id":"tts-1-hd"},{"id":"gpt-4o-mini-tts"}]}"""),
        )

        assertEquals(
            listOf("gpt-4o-mini-tts", "tts-1", "tts-1-hd"),
            OpenAiTts.speechModels(clientFor("api.openai.com").models(OpenAiTts.baseUrl("https://api.openai.com/v1")!!, "k")).map { it.id },
        )
    }

    @Test
    fun `DeepInfra's voices come from the model's description, asked without the key`(): Unit = runBlocking {
        server.enqueue(MockResponse(code = 404, body = """{"detail":"Not Found"}"""))
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"in_schema":{"properties":{"preset_voice":{"type":"array","default":["af_bella"],
                    "items":{"${'$'}ref":"#/definitions/KokoroTtsVoice"}}},
                    "definitions":{"KokoroTtsVoice":{"enum":["af_alloy","af_bella","am_adam"]}}}}""",
            ),
        )

        assertEquals(listOf("af_bella", "af_alloy", "am_adam"), clientFor("api.deepinfra.com").voices(deepInfra, "secret", "hexgrad/Kokoro-82M"))

        assertEquals("/v1/openai/audio/voices", server.takeRequest().url.encodedPath)
        val described = server.takeRequest()
        assertEquals("/models/hexgrad/Kokoro-82M", described.url.encodedPath)
        assertNull(described.headers["Authorization"])
    }

    @Test
    fun `a voice that may be a preset or any text offers the presets`(): Unit = runBlocking {
        server.enqueue(MockResponse(code = 404))
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"in_schema":{"properties":{"voice":{"default":"Vivian","anyOf":[
                    {"${'$'}ref":"#/${'$'}defs/Qwen3TtsVoice"},{"type":"string"}]}},
                    "${'$'}defs":{"Qwen3TtsVoice":{"enum":["Serena","Vivian"]}}}}""",
            ),
        )

        assertEquals(listOf("Vivian", "Serena"), clientFor("api.deepinfra.com").voices(deepInfra, null, "Qwen/Qwen3-TTS"))
    }

    @Test
    fun `a model with no preset voices offers none, and a broken description is an error`(): Unit = runBlocking {
        val client = clientFor("api.deepinfra.com")
        server.enqueue(MockResponse(code = 404))
        server.enqueue(MockResponse(code = 200, body = """{"in_schema":{"properties":{"voice_id":{"type":"string"}}}}"""))
        assertEquals(emptyList<String>(), client.voices(deepInfra, null, "ResembleAI/chatterbox-multilingual"))

        server.enqueue(MockResponse(code = 404))
        server.enqueue(MockResponse(code = 200, body = """{"in_schema":{"properties":{"voice":{"type":"string"}}}}"""))
        assertEquals(emptyList<String>(), client.voices(deepInfra, null, "Qwen/Qwen3-TTS-VoiceDesign"))

        server.enqueue(MockResponse(code = 404))
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"in_schema":{"properties":{"voice":{"${'$'}ref":"#/definitions/A"}},
                    "definitions":{"A":{"${'$'}ref":"#/definitions/A"}}}}""",
            ),
        )
        expect<SpeechError.InvalidResponse> { client.voices(deepInfra, null, "m") }

        server.enqueue(MockResponse(code = 404))
        server.enqueue(MockResponse(code = 200, body = """{"in_schema":{"properties":{"voice":{"${'$'}ref":"https://evil.example/x"}}}}"""))
        expect<SpeechError.InvalidResponse> { client.voices(deepInfra, null, "m") }

        server.enqueue(MockResponse(code = 404))
        server.enqueue(MockResponse(code = 200, body = "<html>"))
        expect<SpeechError.InvalidResponse> { client.voices(deepInfra, null, "m") }

        server.enqueue(MockResponse(code = 404))
        server.enqueue(MockResponse(code = 503))
        expect<SpeechError.Service> { client.voices(deepInfra, null, "m") }
        assertEquals(12, server.requestCount)
    }

    @Test
    fun `only DeepInfra's own root is asked for a model's description`() {
        assertEquals(
            "https://api.deepinfra.com/models/hexgrad/Kokoro-82M",
            OpenAiTts.modelDescription(deepInfra, "hexgrad/Kokoro-82M").toString(),
        )
        assertEquals(
            "https://api.deepinfra.com/models/a%20b/c%3Fd",
            OpenAiTts.modelDescription(deepInfra, "a b/c?d").toString(),
        )
        assertNull(OpenAiTts.modelDescription(deepInfra, "../admin"))
        assertNull(OpenAiTts.modelDescription(deepInfra, "a//b"))
        assertNull(OpenAiTts.modelDescription(OpenAiTts.baseUrl("http://api.deepinfra.com/v1/openai")!!, "m"))
        assertNull(OpenAiTts.modelDescription(OpenAiTts.baseUrl("https://api.deepinfra.com/v1")!!, "m"))
        assertNull(OpenAiTts.modelDescription(OpenAiTts.baseUrl("https://proxy.example/v1/openai")!!, "m"))
    }

    @Test
    fun `an unknown voice answered with a server error is still a missing voice`(): Unit = runBlocking {
        server.enqueue(MockResponse(code = 500, body = """{"detail":"Voice ID 'af_bella' not found"}"""))
        server.enqueue(MockResponse(code = 500, body = """{"detail":"Internal error"}"""))

        expect<SpeechError.InvalidVoice> { OpenAiTtsClient().synthesize(base, null, "t", "af_bella", "Qwen/Qwen3-TTS") }
        expect<SpeechError.Service> { OpenAiTtsClient().synthesize(base, null, "t", "Vivian", "Qwen/Qwen3-TTS") }
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

        assertEquals(listOf("gpt-4o-mini-tts", "whisper-1", "tts-1"), OpenAiTtsClient().models(base, "k").map { it.id })
        val request = server.takeRequest()
        assertEquals("/v1/models", request.url.encodedPath)
        assertEquals("Bearer k", request.headers["Authorization"])
        assertEquals(listOf(SpeechModel("kokoro")), OpenAiTtsClient().models(base, null))
    }

    @Test
    fun `a service with no model list offers none, and other failures are errors`(): Unit = runBlocking {
        server.enqueue(MockResponse(code = 404))
        server.enqueue(MockResponse(code = 401))
        server.enqueue(MockResponse(code = 200, body = """{"voices":[]}"""))

        assertEquals(emptyList<SpeechModel>(), OpenAiTtsClient().models(base, null))
        expect<SpeechError.InvalidKey> { OpenAiTtsClient().models(base, "k") }
        expect<SpeechError.InvalidResponse> { OpenAiTtsClient().models(base, null) }
    }

    @Test
    fun `only models that look like they speak are offered, unless none does`() {
        fun ids(vararg ids: String) = ids.map(::SpeechModel)
        assertEquals(
            ids("gpt-4o-mini-tts", "tts-1", "hexgrad/Kokoro-82M", "speech-02"),
            OpenAiTts.speechModels(ids("gpt-4o", "gpt-4o-mini-tts", "whisper-1", "tts-1", "hexgrad/Kokoro-82M", "speech-02")),
        )
        assertEquals(ids("my-voice", "other"), OpenAiTts.speechModels(ids("my-voice", "other")))
        assertEquals(emptyList<SpeechModel>(), OpenAiTts.speechModels(emptyList()))
    }

    @Test
    fun `a list that tags its models offers the speech ones, with their price`(): Unit = runBlocking {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"data":[
                    {"id":"ResembleAI/chatterbox-multilingual","metadata":{"tags":["tts"],"pricing":{"input_characters":1.0}}},
                    {"id":"openai/whisper-large-v3","metadata":{"tags":["stt"],"pricing":{"input_length":0.0045}}},
                    {"id":"hexgrad/Kokoro-82M","metadata":{"tags":["tts"],"pricing":{"input_characters":0.62}}},
                    {"id":"some/tts-chat","metadata":{"tags":["chat"]}},
                    {"id":"sesame/csm-1b","metadata":{"tags":["TTS"]}}]}""",
            ),
        )

        assertEquals(
            listOf(
                SpeechModel("ResembleAI/chatterbox-multilingual", true, 1.0),
                SpeechModel("hexgrad/Kokoro-82M", true, 0.62),
                SpeechModel("sesame/csm-1b", true, null),
            ),
            OpenAiTts.speechModels(OpenAiTtsClient().models(base, null)),
        )
        // A list that says none of its models speak has none, not every one.
        assertEquals(emptyList<SpeechModel>(), OpenAiTts.speechModels(listOf(SpeechModel("gpt-4o", false))))
    }

    @Test
    fun `asks for speech models, and asks plainly a server that refuses the question`(): Unit = runBlocking {
        server.enqueue(MockResponse(code = 200, body = """{"data":[{"id":"kokoro"}]}"""))
        server.enqueue(MockResponse(code = 400, body = """{"detail":"unknown parameter"}"""))
        server.enqueue(MockResponse(code = 200, body = """{"data":[{"id":"tts-1"}]}"""))
        server.enqueue(MockResponse(code = 500))

        assertEquals(listOf(SpeechModel("kokoro")), OpenAiTtsClient().models(base, null))
        assertEquals("speech", server.takeRequest().url.queryParameter("output_modalities"))
        assertEquals(listOf(SpeechModel("tts-1")), OpenAiTtsClient().models(base, null))
        assertEquals("speech", server.takeRequest().url.queryParameter("output_modalities"))
        val plain = server.takeRequest().url
        assertEquals("/v1/models", plain.encodedPath)
        assertNull(plain.query)
        expect<SpeechError.Service> { OpenAiTtsClient().models(base, null) }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `a model's own word on speech comes from its output, capabilities or tags, in that order`(): Unit = runBlocking {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"data":[
                    {"id":"canopylabs/orpheus-v1-english","output_modalities":["speech"]},
                    {"id":"openai/gpt-oss-20b","output_modalities":["text"]},
                    {"id":"voxtral-mini-tts-2603","capabilities":{"audio_speech":true}},
                    {"id":"mistral-small","capabilities":{"audio_speech":false}},
                    {"id":"codestral","capabilities":{"completion_chat":true}},
                    {"id":"odd/tts","output_modalities":["text"],"metadata":{"tags":["tts"]}},
                    {"id":"plain"}]}""",
            ),
        )

        val models = OpenAiTtsClient().models(base, null)

        assertEquals(listOf(true, false, true, false, null, false, null), models.map { it.speech })
        assertEquals(
            listOf("canopylabs/orpheus-v1-english", "voxtral-mini-tts-2603"),
            OpenAiTts.speechModels(models).map { it.id },
        )
    }

    @Test
    fun `OpenRouter's speech models come with their voices, and a price when billed by character`(): Unit = runBlocking {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"data":[
                    {"id":"hexgrad/kokoro-82m","architecture":{"output_modalities":["speech"]},
                     "pricing":{"prompt":"0.00000062","completion":"0"},"supported_voices":["af_heart","ff_siwis",7]},
                    {"id":"google/gemini-tts","architecture":{"output_modalities":["speech"]},
                     "pricing":{"prompt":"0.0000005","completion":"0.00001"},"supported_voices":null},
                    {"id":"openai/gpt-4o","architecture":{"output_modalities":["text"]},"pricing":{"prompt":"0.0000025","completion":"0.00001"}}]}""",
            ),
        )

        val models = OpenAiTts.speechModels(OpenAiTtsClient().models(base, "k"))

        assertEquals(listOf("hexgrad/kokoro-82m", "google/gemini-tts"), models.map { it.id })
        assertEquals(0.62, models[0].pricePerMillionChars!!, 1e-9)
        assertEquals(listOf("af_heart", "ff_siwis"), models[0].voices)
        assertNull(models[1].pricePerMillionChars)
        assertNull(models[1].voices)
    }

    private val groq = OpenAiTts.baseUrl("https://api.groq.com/openai/v1")!!

    @Test
    fun `Groq's speech models are priced by character, and the same list elsewhere is not`(): Unit = runBlocking {
        val body = """{"data":[
            {"id":"canopylabs/orpheus-v1-english","output_modalities":["speech"],"pricing":{"prompt":"0.000022"}},
            {"id":"canopylabs/orpheus-arabic-saudi","output_modalities":["speech"],"pricing":{"prompt":"0.00004"}}]}"""
        server.enqueue(MockResponse(code = 200, body = body))
        server.enqueue(MockResponse(code = 200, body = body))

        val prices = clientFor("api.groq.com").models(groq, "k").map { it.pricePerMillionChars }
        assertEquals(22.0, prices[0]!!, 1e-9)
        assertEquals(40.0, prices[1]!!, 1e-9)
        // Without a completion price, another server's prompt price may be per token.
        assertEquals(listOf(null, null), OpenAiTtsClient().models(base, "k").map { it.pricePerMillionChars })
    }

    @Test
    fun `Groq, which has no voice list, offers each model's documented voices`(): Unit = runBlocking {
        val client = clientFor("api.groq.com")
        server.enqueue(MockResponse(code = 404))
        assertEquals(
            listOf("autumn", "diana", "hannah", "austin", "daniel", "troy"),
            client.voices(groq, "k", "canopylabs/orpheus-v1-english"),
        )
        server.enqueue(MockResponse(code = 404))
        assertEquals(
            listOf("abdullah", "fahad", "sultan", "lulwa", "noura", "aisha"),
            client.voices(groq, "k", "canopylabs/orpheus-arabic-saudi"),
        )
        // A model it does not document takes a typed voice; nothing is guessed.
        server.enqueue(MockResponse(code = 404))
        assertEquals(emptyList<String>(), client.voices(groq, "k", "canopylabs/orpheus-v2"))
        assertEquals(3, server.requestCount)
        // Only Groq's own server has them.
        server.enqueue(MockResponse(code = 404))
        server.enqueue(MockResponse(code = 200, body = """{"data":[{"id":"canopylabs/orpheus-v1-english"}]}"""))
        assertEquals(emptyList<String>(), OpenAiTtsClient().voices(base, "k", "canopylabs/orpheus-v1-english"))
    }

    @Test
    fun `a model whose terms are not accepted says so, even when its message names the voice`(): Unit = runBlocking {
        val terms = """{"error":{"message":"The model requires terms acceptance before any voice can be used.",
            "type":"invalid_request_error","code":"model_terms_required"}}"""
        server.enqueue(MockResponse(code = 400, body = terms))
        server.enqueue(MockResponse(code = 400, body = terms))
        server.enqueue(MockResponse(code = 400, body = """{"error":{"message":"voice must be one of [autumn]","code":"invalid_voice"}}"""))

        val error = expect<SpeechError.TermsRequired> { OpenAiTtsClient().synthesize(base, "k", "t", "autumn", "m") }
        assertFalse(error.message!!.contains("acceptance"))
        expect<SpeechError.TermsRequired> { OpenAiTtsClient().models(base, "k") }
        expect<SpeechError.InvalidVoice> { OpenAiTtsClient().synthesize(base, "k", "t", "nope", "m") }
    }

    @Test
    fun `a server without a voice list offers the voices its model list names`(): Unit = runBlocking {
        val models = """{"data":[{"id":"hexgrad/kokoro-82m","supported_voices":["af_heart","ff_siwis"]},
            {"id":"google/gemini-tts","supported_voices":null},{"id":"mute","supported_voices":[]}]}"""
        for (model in listOf("hexgrad/kokoro-82m", "google/gemini-tts", "mute", "unlisted")) {
            server.enqueue(MockResponse(code = 404))
            server.enqueue(MockResponse(code = 200, body = models))
        }

        assertEquals(listOf("af_heart", "ff_siwis"), OpenAiTtsClient().voices(base, "k", "hexgrad/kokoro-82m"))
        assertEquals("/v1/audio/voices", server.takeRequest().url.encodedPath)
        val listing = server.takeRequest()
        assertEquals("/v1/models", listing.url.encodedPath)
        assertEquals("Bearer k", listing.headers["Authorization"])
        assertEquals(emptyList<String>(), OpenAiTtsClient().voices(base, "k", "google/gemini-tts"))
        assertEquals(emptyList<String>(), OpenAiTtsClient().voices(base, "k", "mute"))
        assertEquals(emptyList<String>(), OpenAiTtsClient().voices(base, "k", "unlisted"))
    }

    @Test
    fun `Mistral's voices are read page by page, by their slug`(): Unit = runBlocking {
        fun page(vararg slugs: String?) = MockResponse(
            code = 200,
            body = JSONObject()
                .put("items", org.json.JSONArray(slugs.map { s -> JSONObject().put("name", "N").apply { if (s != null) put("slug", s) } }))
                .put("total", 5).put("page_size", 2).toString(),
        )
        server.enqueue(page("fr_marie_neutral", "en_paul_happy"))
        server.enqueue(page("en_paul_happy", null))
        server.enqueue(page("fr_marie_sad"))

        assertEquals(
            listOf("fr_marie_neutral", "en_paul_happy", "N", "fr_marie_sad"),
            OpenAiTtsClient().voices(base, "k", "voxtral-mini-tts-2603"),
        )
        assertNull(server.takeRequest().url.queryParameter("offset"))
        // A duplicate still counts towards the total.
        assertEquals("2", server.takeRequest().url.queryParameter("offset"))
        assertEquals("4", server.takeRequest().url.queryParameter("offset"))
    }

    @Test
    fun `a voice list that stops short of its total is an error, never a partial list`(): Unit = runBlocking {
        server.enqueue(MockResponse(code = 200, body = """{"items":[{"slug":"a"}],"total":3}"""))
        server.enqueue(MockResponse(code = 200, body = """{"items":[],"total":3}"""))
        expect<SpeechError.InvalidResponse> { OpenAiTtsClient().voices(base, "k", "m") }

        repeat(20) { server.enqueue(MockResponse(code = 200, body = """{"items":[{"slug":"v$it"}],"total":1000}""")) }
        expect<SpeechError.InvalidResponse> { OpenAiTtsClient().voices(base, "k", "m") }
        assertEquals(22, server.requestCount)
    }

    private fun failure(code: Int, body: String) = MockResponse(code = code, body = body)

    private val openRouterRefusal =
        """{"success":false,"error":{"name":"ZodError","message":"[{\"code\":\"invalid_value\",\"values\":[\"mp3\",\"pcm\"],\"path\":[\"response_format\"]}]"}}"""

    @Test
    fun `a server that refuses WAV is asked for MP3, which is then asked first for that model`(): Unit = runBlocking {
        val decoded = byteArrayOf(5, 0, 6, 0)
        val mp3s = mutableListOf<ByteArray>()
        val client = OpenAiTtsClient(decodeMp3 = { mp3s += it; decoded })
        server.enqueue(failure(400, openRouterRefusal))
        server.enqueue(pcm(byteArrayOf(1, 2, 3), "audio/mpeg"))
        server.enqueue(pcm(byteArrayOf(4, 5), "audio/mpeg"))
        server.enqueue(pcm(byteArrayOf(7, 0)))

        assertArrayEquals(decoded, client.synthesize(base, "k", "t", "af_heart", "hexgrad/kokoro-82m").pcm)
        assertArrayEquals(decoded, client.synthesize(base, "k", "t", "af_heart", "hexgrad/kokoro-82m").pcm)
        assertArrayEquals(byteArrayOf(7, 0), client.synthesize(base, "k", "t", "v", "other").pcm)

        val formats = (0 until 4).map { JSONObject(server.takeRequest().body!!.utf8()).getString("response_format") }
        assertEquals(listOf("wav", "mp3", "mp3", "wav"), formats)
        assertEquals(2, mp3s.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), mp3s[0])
    }

    @Test
    fun `a FastAPI refusal of the format also falls back, and other refusals do not`(): Unit = runBlocking {
        val client = OpenAiTtsClient(decodeMp3 = { byteArrayOf(9, 0) })
        server.enqueue(failure(422, """{"detail":[{"loc":["body","response_format"],"msg":"Input should be 'mp3'"}]}"""))
        server.enqueue(pcm(byteArrayOf(1), "audio/mp3"))
        assertArrayEquals(byteArrayOf(9, 0), client.synthesize(base, null, "t", "v", "a").pcm)

        server.enqueue(failure(400, """{"error":{"message":"input too long"}}"""))
        assertEquals(400, expect<SpeechError.Service> { client.synthesize(base, null, "t", "v", "b") }.code)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a failed MP3 retry is reported, and WAV is asked again next time`(): Unit = runBlocking {
        val client = OpenAiTtsClient(decodeMp3 = { byteArrayOf(9, 0) })
        server.enqueue(failure(400, openRouterRefusal))
        server.enqueue(failure(403, """{"error":{"message":"Key limit exceeded"}}"""))
        server.enqueue(failure(400, openRouterRefusal))
        server.enqueue(pcm(byteArrayOf(1), "audio/mpeg"))

        expect<SpeechError.InvalidKey> { client.synthesize(base, "k", "t", "v", "m") }
        client.synthesize(base, "k", "t", "v", "m")
        val decoder = OpenAiTtsClient(decodeMp3 = { throw IllegalStateException("codec said: secret text") })
        server.enqueue(failure(400, openRouterRefusal))
        server.enqueue(pcm(byteArrayOf(1), "audio/mpeg"))
        val error = expect<SpeechError.InvalidResponse> { decoder.synthesize(base, "k", "t", "v", "m") }
        assertFalse(error.toString().contains("secret"))

        val formats = (0 until 6).map { JSONObject(server.takeRequest().body!!.utf8()).getString("response_format") }
        assertEquals(listOf("wav", "mp3", "wav", "mp3", "wav", "mp3"), formats)
    }

    private fun mistral(audio: String) = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body("""{"audio_data":$audio}""")
        .build()

    @Test
    fun `audio sent as base64 in JSON is unwrapped, whatever format was asked`(): Unit = runBlocking {
        val wav = WavPcmTest.wav(rate = 24_000, channels = 1, data = byteArrayOf(1, 0, 2, 0))
        val encoded = "\"${java.util.Base64.getEncoder().encodeToString(wav)}\""
        server.enqueue(mistral(encoded))
        assertArrayEquals(byteArrayOf(1, 0, 2, 0), OpenAiTtsClient().synthesize(base, "k", "t", "fr_marie_neutral", "voxtral-mini-tts-2603").pcm)

        val client = OpenAiTtsClient(decodeMp3 = { throw AssertionError("a WAV is not decoded as MP3") })
        server.enqueue(failure(400, openRouterRefusal))
        server.enqueue(mistral(encoded))
        assertArrayEquals(byteArrayOf(1, 0, 2, 0), client.synthesize(base, "k", "t", "v", "m").pcm)
    }

    @Test
    fun `JSON that does not carry WAV or asked MP3 is an error`(): Unit = runBlocking {
        for (audio in listOf("\"not base64!\"", "42", "null", "\"${java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 0))}\"")) {
            server.enqueue(mistral(audio))
            expect<SpeechError.InvalidResponse> { OpenAiTtsClient().synthesize(base, "k", "t", "v", "m") }
        }
        server.enqueue(mistral("\"${"A".repeat(12 * 1024 * 1024)}\""))
        expect<SpeechError.InvalidResponse> { OpenAiTtsClient().synthesize(base, "k", "t", "v", "m") }
    }

    @Test
    fun `an MP3 answer to a WAV request is decoded as MP3`(): Unit = runBlocking {
        server.enqueue(pcm(byteArrayOf(1, 2, 3), "audio/mpeg"))
        assertArrayEquals(byteArrayOf(8, 0), OpenAiTtsClient(decodeMp3 = { byteArrayOf(8, 0) }).synthesize(base, null, "t", "v", "m").pcm)
    }

    @Test
    fun `Mistral's unknown voice is a missing voice`(): Unit = runBlocking {
        server.enqueue(failure(404, """{"object":"error","message":"Voice 'x' not found.","type":"invalid_voice"}"""))
        expect<SpeechError.InvalidVoice> { OpenAiTtsClient().synthesize(base, "k", "t", "x", "voxtral-mini-tts-2603") }
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
