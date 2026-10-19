package com.chmouel.liseur.translate

import com.chmouel.liseur.tts.OpenAiTts
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenAiChatClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        Thinking.forget()
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stop() {
        server.close()
    }

    private val base: HttpUrl get() = OpenAiTts.baseUrl(server.url("/v1").toString())!!
    private val client = OpenAiChatClient()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun completion(content: String) =
        """{"choices":[{"message":{"role":"assistant","content":${JSONObject.quote(content)}},"finish_reason":"stop"}]}"""

    private inline fun <reified E : TranslationError> expect(block: () -> Unit): E {
        try {
            block()
        } catch (e: TranslationError) {
            if (e is E) return e
            fail("expected ${E::class.simpleName}, got $e")
        }
        fail("expected ${E::class.simpleName}")
        error("unreachable")
    }

    @Test
    fun `pinned paid requests do not follow a retry after zero 503`(): Unit = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "0").body("{}").build())
        server.enqueue(json(completion("Should not be sent")))
        expect<TranslationError.Service> {
            client.translate(base, "secret", "m", "en", "fr", "x", allowTransportRetry = false)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `missing response envelopes are endpoint failures not sentence refusals`() {
        for (body in listOf(
            "not JSON", "{}", """{"choices":[{}]}""", """{"choices":[{"message":{}}]}""",
            """{"choices":[{"message":{"content":[{"type":"text","text":42}]}}]}""",
            """{"choices":[{"message":{"content":[{"type":"unknown"}]}}]}""",
        )) {
            expect<TranslationError.Protocol> { OpenAiChat.translation(body) }
        }
    }

    @Test
    fun `translation guard stops a temperature retry before it submits`(): Unit = runBlocking {
        server.enqueue(json("""{"error":{"message":"Unsupported temperature"}}""", 400))
        var attempts = 0
        expect<TranslationError.SettingsChanged> {
            client.translate(base, "secret", "m", "en", "fr", "It rains.", beforeRequest = {
                if (++attempts == 2) throw TranslationError.SettingsChanged()
            })
        }
        assertEquals(2, attempts)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `translation guard stops a thinking retry before it submits`(): Unit = runBlocking {
        val client = OpenAiChatClient(thinking = { listOf(JSONObject("""{"enable_thinking":false}""")) })
        server.enqueue(json("""{"error":{"message":"Unsupported enable_thinking"}}""", 400))
        var attempts = 0
        expect<TranslationError.SettingsChanged> {
            client.translate(base, "secret", "m", "en", "fr", "It rains.", beforeRequest = {
                if (++attempts == 2) throw TranslationError.SettingsChanged()
            })
        }
        assertEquals(2, attempts)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `asks the chat endpoint with the task apart from the passage`(): Unit = runBlocking {
        server.enqueue(json(completion("The sun was setting.")))

        val text = client.translate(base, "secret", "gpt-4o-mini", "fr", "en", "Le soleil se couchait.")

        assertEquals("The sun was setting.", text)
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.url.encodedPath)
        assertEquals("Bearer secret", request.headers["Authorization"])
        val body = JSONObject(request.body!!.utf8())
        assertEquals("gpt-4o-mini", body.getString("model"))
        assertEquals(false, body.getBoolean("stream"))
        assertEquals(0, body.getInt("temperature"))
        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("<passage>\nLe soleil se couchait.\n</passage>", messages.getJSONObject(1).getString("content"))
    }

    @Test
    fun `TranslateGemma gets its own single prompt, greedy and without context`(): Unit = runBlocking {
        server.enqueue(json(completion("Il pleut.")))

        val text = client.translate(base, null, "translategemma:4b", "en", "fr", "It rains.", context = "It was grey.")

        assertEquals("Il pleut.", text)
        val body = JSONObject(server.takeRequest().body!!.utf8())
        assertEquals(0, body.getInt("temperature"))
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        val content = messages.getJSONObject(0).getString("content")
        assertTrue(content, content.endsWith("into French:\n\n\nIt rains."))
        assertTrue("It was grey" !in content)
    }

    @Test
    fun `the reader's options go with the request, and only once set`(): Unit = runBlocking {
        server.enqueue(json(completion("a")))
        client.translate(base, null, "m", "en", "fr", "x")
        val plain = JSONObject(server.takeRequest().body!!.utf8())
        assertFalse(plain.has("max_tokens"))
        assertFalse(plain.has("keep_alive"))

        server.enqueue(json(completion("b")))
        client.translate(
            base, null, "m", "en", "fr", "x",
            temperature = 0.3, instructions = "Use tu.", maxTokens = 200, keepAlive = "600",
        )
        val tuned = JSONObject(server.takeRequest().body!!.utf8())
        assertEquals(0.3, tuned.getDouble("temperature"), 0.0)
        assertEquals(200, tuned.getInt("max_tokens"))
        // Ollama reads a number as seconds but refuses a string without a unit.
        assertEquals(600, tuned.get("keep_alive"))
        val system = tuned.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system, "Use tu." in system)

        server.enqueue(json(completion("c")))
        client.translate(base, null, "translategemma:4b", "en", "fr", "x", instructions = "Use tu.", keepAlive = "10m")
        val gemma = JSONObject(server.takeRequest().body!!.utf8())
        assertEquals("10m", gemma.get("keep_alive"))
        val prompt = gemma.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(prompt, prompt.indexOf("Use tu.") in 0 until prompt.indexOf("Please translate"))
    }

    @Test
    fun `a server that refuses the temperature is asked once more without it, and says so`(): Unit = runBlocking {
        server.enqueue(
            json(
                """{"error":{"message":"Unsupported value: 'temperature' does not support 0 with this model. Only the default (1) value is supported.","type":"invalid_request_error","param":"temperature","code":"unsupported_value"}}""",
                400,
            ),
        )
        server.enqueue(json(completion("Il pleut.")))
        var refused = 0

        val text = client.translate(base, "k", "o4-mini", "en", "fr", "It rains.", onTemperatureRefused = { refused++ })

        assertEquals("Il pleut.", text)
        assertEquals(1, refused)
        assertEquals(2, server.requestCount)
        assertEquals(0, JSONObject(server.takeRequest().body!!.utf8()).getInt("temperature"))
        val retry = JSONObject(server.takeRequest().body!!.utf8())
        assertFalse(retry.has("temperature"))
        assertEquals("o4-mini", retry.getString("model"))
    }

    @Test
    fun `a refusal that is not about the temperature is not asked again`(): Unit = runBlocking {
        server.enqueue(json("""{"error":{"message":"messages too long"}}""", 400))
        var refused = 0
        val error = expect<TranslationError.Service> {
            runBlocking { client.translate(base, "k", "m", "en", "fr", "x", onTemperatureRefused = { refused++ }) }
        }
        assertEquals(400, error.code)
        assertEquals(1, server.requestCount)
        assertEquals(0, refused)
    }

    @Test
    fun `a retry without the temperature that fails too is not taken as the refusal`(): Unit = runBlocking {
        server.enqueue(json("""{"error":{"message":"bad temperature"}}""", 400))
        server.enqueue(json("""{"error":{"message":"busy"}}""", 503))
        var refused = 0
        expect<TranslationError.Service> {
            runBlocking { client.translate(base, "k", "m", "en", "fr", "x", onTemperatureRefused = { refused++ }) }
        }
        assertEquals(2, server.requestCount)
        assertEquals(0, refused)
    }

    @Test
    fun `no temperature is sent when there is none, and a refusal then is not retried`(): Unit = runBlocking {
        server.enqueue(json("""{"error":{"message":"temperature is required"}}""", 400))
        expect<TranslationError.Service> { runBlocking { client.translate(base, "k", "m", "en", "fr", "x", temperature = null) } }
        assertEquals(1, server.requestCount)
        assertFalse(JSONObject(server.takeRequest().body!!.utf8()).has("temperature"))
    }

    @Test
    fun `thinking is turned off only where a service is known to take it`() {
        val zai = Thinking.openAiCompatible(OpenAiTts.baseUrl("https://api.z.ai/api/paas/v4/")!!)
        assertEquals("disabled", zai[0].getJSONObject("thinking").getString("type"))
        assertEquals("low", zai[1].getString("reasoning_effort"))
        assertTrue(Thinking.openAiCompatible(OpenAiTts.baseUrl("http://192.168.1.10:11434")!!).isEmpty())
    }

    @Test
    fun `a model that refuses a way of not thinking is asked the next, and that one is kept`(): Unit = runBlocking {
        Thinking.forget()
        val ways = listOf(JSONObject().put("thinking", "off"), JSONObject().put("reasoning_effort", "low"))
        val client = OpenAiChatClient(thinking = { ways })
        server.enqueue(json("""{"error":{"code":"1210","message":"This model always engages in thinking"}}""", 400))
        server.enqueue(json(completion("Il pleut.")))
        server.enqueue(json(completion("Il neige.")))
        var counted = 0

        assertEquals("Il pleut.", client.chat(base, "k", "glm", listOf("user" to "It rains."), beforeRequest = { counted++ }))
        assertEquals("Il neige.", client.translate(base, "k", "glm", "en", "fr", "It snows."))

        assertEquals(2, counted)
        val first = JSONObject(server.takeRequest().body!!.utf8())
        assertEquals("off", first.getString("thinking"))
        val second = JSONObject(server.takeRequest().body!!.utf8())
        assertFalse(second.has("thinking"))
        assertEquals("low", second.getString("reasoning_effort"))
        assertEquals(0, second.getInt("temperature"))
        assertEquals("low", JSONObject(server.takeRequest().body!!.utf8()).getString("reasoning_effort"))
    }

    @Test
    fun `a request refused with every way and with none keeps nothing`(): Unit = runBlocking {
        Thinking.forget()
        val client = OpenAiChatClient(thinking = { listOf(JSONObject().put("thinking", "off")) })
        repeat(2) { server.enqueue(json("""{"error":{"message":"thinking is unsupported"}}""", 400)) }
        server.enqueue(json(completion("Il pleut.")))

        expect<TranslationError.Service> { runBlocking { client.translate(base, "k", "m", "en", "fr", "x") } }
        assertEquals("Il pleut.", client.translate(base, "k", "m", "en", "fr", "It rains."))

        assertTrue(JSONObject(server.takeRequest().body!!.utf8()).has("thinking"))
        assertFalse(JSONObject(server.takeRequest().body!!.utf8()).has("thinking"))
        assertTrue(JSONObject(server.takeRequest().body!!.utf8()).has("thinking"))
    }

    @Test
    fun `a temperature refusal is remembered when a thinking retry finally succeeds`(): Unit = runBlocking {
        val client = OpenAiChatClient(thinking = { listOf(JSONObject().put("thinking", "off")) })
        server.enqueue(json("""{"error":{"message":"temperature is unsupported"}}""", 400))
        server.enqueue(json("""{"error":{"message":"thinking is unsupported"}}""", 400))
        server.enqueue(json(completion("Bonjour.")))
        var refused = 0

        assertEquals("Bonjour.", client.translate(base, "k", "m", "en", "fr", "Hello.", onTemperatureRefused = { refused++ }))

        assertEquals(1, refused)
        assertEquals(0, JSONObject(server.takeRequest().body!!.utf8()).getInt("temperature"))
        assertFalse(JSONObject(server.takeRequest().body!!.utf8()).has("temperature"))
        val last = JSONObject(server.takeRequest().body!!.utf8())
        assertFalse(last.has("temperature"))
        assertFalse(last.has("thinking"))
    }

    @Test
    fun `thinking retries recheck the request guard before sending`(): Unit = runBlocking {
        val client = OpenAiChatClient(thinking = { listOf(JSONObject().put("thinking", "off")) })
        server.enqueue(json("""{"error":{"message":"thinking is unsupported"}}""", 400))
        var attempts = 0

        expect<TranslationError.Changed> {
            runBlocking {
                client.chat(base, "k", "m", listOf("user" to "q"), beforeRequest = {
                    if (++attempts == 2) throw TranslationError.Changed()
                })
            }
        }

        assertEquals(2, attempts)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `unrelated bad requests do not retry thinking options`(): Unit = runBlocking {
        val client = OpenAiChatClient(thinking = { listOf(JSONObject().put("thinking", "off")) })
        server.enqueue(json("""{"error":{"message":"messages too long"}}""", 400))

        expect<TranslationError.Service> { runBlocking { client.translate(base, "k", "m", "en", "fr", "x") } }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a failed request does not remember a thinking option`(): Unit = runBlocking {
        val ways = listOf(JSONObject().put("thinking", "off"), JSONObject().put("reasoning_effort", "low"))
        val client = OpenAiChatClient(thinking = { ways })
        server.enqueue(json("""{"error":{"message":"thinking is unsupported"}}""", 400))
        server.enqueue(json("""{"error":{"message":"invalid key"}}""", 401))
        server.enqueue(json(completion("Bonjour.")))

        expect<TranslationError.InvalidKey> { runBlocking { client.translate(base, "k", "m", "en", "fr", "x") } }
        assertEquals("Bonjour.", client.translate(base, "new-key", "m", "en", "fr", "Hello."))

        assertTrue(JSONObject(server.takeRequest().body!!.utf8()).has("thinking"))
        assertTrue(JSONObject(server.takeRequest().body!!.utf8()).has("reasoning_effort"))
        assertTrue(JSONObject(server.takeRequest().body!!.utf8()).has("thinking"))
    }

    @Test
    fun `a model the server does not have is named, and a missing route is not taken for one`(): Unit = runBlocking {
        // Ollama's.
        server.enqueue(json("""{"error":{"message":"model \"qwen9\" not found, try pulling it first","type":"api_error","param":null,"code":null}}""", 404))
        assertEquals("qwen9", expect<TranslationError.ModelNotFound> { runBlocking { client.translate(base, null, "qwen9", "en", "fr", "x") } }.model)
        // OpenAI's.
        server.enqueue(
            json(
                """{"error":{"message":"The model `gpt-9` does not exist or you do not have access to it.","type":"invalid_request_error","param":null,"code":"model_not_found"}}""",
                404,
            ),
        )
        assertEquals("gpt-9", expect<TranslationError.ModelNotFound> { runBlocking { client.translate(base, "k", "gpt-9", "en", "fr", "x") } }.model)
        // A server with no chat endpoint there.
        server.enqueue(MockResponse.Builder().code(404).body("404 page not found").build())
        expect<TranslationError.NoEndpoint> { runBlocking { client.translate(base, null, "m", "en", "fr", "x") } }
        // Z.ai's, asked at a path it does not serve.
        server.enqueue(json("""{"timestamp":"2026-10-15T17:42:08.807+00:00","status":404,"error":"Not Found","path":"/v4/v1/chat/completions"}""", 404))
        expect<TranslationError.NoEndpoint> { runBlocking { client.translate(base, "k", "glm-4.7", "en", "fr", "x") } }
        // OpenRouter's 404 for a data policy names no missing model.
        server.enqueue(json("""{"error":{"message":"No endpoints found matching your data policy (Free model publication)","code":404}}""", 404))
        assertEquals(404, expect<TranslationError.Service> { runBlocking { client.translate(base, "k", "m", "en", "fr", "x") } }.code)
    }

    @Test
    fun `a versioned API root is asked as it is`(): Unit = runBlocking {
        server.enqueue(json(completion("Bonjour.")))
        val zai = OpenAiTts.baseUrl(server.url("/api/paas/v4/").toString())!!
        assertEquals("Bonjour.", client.translate(zai, "k", "glm-4.7", "en", "fr", "Good morning."))
        assertEquals("/api/paas/v4/chat/completions", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `no key, no authorization header`(): Unit = runBlocking {
        server.enqueue(json(completion("ok")))
        client.translate(base, null, "m", null, "en", "x")
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `model discovery sends the configured key as a bearer token`(): Unit = runBlocking {
        server.enqueue(json("""{"data":[{"id":"gpt-4o-mini"}]}"""))

        assertEquals(listOf(TextModel("gpt-4o-mini")), client.textModels(base, "discovery-key"))

        val request = server.takeRequest()
        assertEquals("/v1/models", request.url.encodedPath)
        assertEquals("Bearer discovery-key", request.headers["Authorization"])
    }

    @Test
    fun `a key that cannot be a header is rejected without repeating it`(): Unit = runBlocking {
        val error = expect<TranslationError.InvalidKey> {
            runBlocking { client.translate(base, "secret\nkey", "m", "fr", "en", "x") }
        }
        assertFalse(error.message.orEmpty().contains("secret"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a redirect does not send the key or passage to another server`(): Unit = runBlocking {
        MockWebServer().use { elsewhere ->
            elsewhere.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(
                MockResponse.Builder()
                    .code(307)
                    .addHeader("Location", elsewhere.url("/elsewhere").toString())
                    .build(),
            )

            expect<TranslationError.Service> {
                runBlocking { client.translate(base, "secret", "m", "fr", "en", "private passage") }
            }

            assertEquals(1, server.requestCount)
            assertEquals(0, elsewhere.requestCount)
        }
    }

    @Test
    fun `an oversized reply is not read`(): Unit = runBlocking {
        server.enqueue(json(completion("a".repeat(TranslationHttp.MAX_REPLY_BYTES.toInt() + 10))))
        expect<TranslationError.Malformed> { runBlocking { client.translate(base, null, "m", "fr", "en", "x") } }
    }

    @Test
    fun `refused keys and quotas say so`(): Unit = runBlocking {
        server.enqueue(json("""{"error":{"message":"bad key"}}""", 401))
        expect<TranslationError.InvalidKey> { runBlocking { client.translate(base, "k", "m", "fr", "en", "x") } }
        server.enqueue(json("""{"error":{"message":"slow down"}}""", 429))
        expect<TranslationError.RateLimited> { runBlocking { client.translate(base, "k", "m", "fr", "en", "x") } }
        server.enqueue(json("{}", 500))
        assertEquals(500, expect<TranslationError.Service> { runBlocking { client.translate(base, "k", "m", "fr", "en", "x") } }.code)
    }

    @Test
    fun `what the server said is kept apart from the message`(): Unit = runBlocking {
        server.enqueue(json("""{"error":{"message":"response_format json_object is not supported by this model"}}""", 400))
        val refused = expect<TranslationError.Service> { runBlocking { client.chat(base, "k", "m", listOf("user" to "x")) } }
        assertEquals("response_format json_object is not supported by this model", refused.serverMessage)
        assertFalse(refused.message.orEmpty().contains("json_object"))

        server.enqueue(MockResponse.Builder().code(502).body("  Bad Gateway\n").build())
        assertEquals("Bad Gateway", expect<TranslationError.Service> { runBlocking { client.chat(base, "k", "m", listOf("user" to "x")) } }.serverMessage)

        server.enqueue(json("""{"detail":"Model is loading"}""", 503))
        assertEquals("Model is loading", expect<TranslationError.Service> { runBlocking { client.chat(base, "k", "m", listOf("user" to "x")) } }.serverMessage)

        server.enqueue(json("", 500))
        assertNull(expect<TranslationError.Service> { runBlocking { client.chat(base, "k", "m", listOf("user" to "x")) } }.serverMessage)

        server.enqueue(MockResponse.Builder().code(500).body("x".repeat(10_000)).build())
        assertEquals(
            TranslationError.MAX_SERVER_MESSAGE,
            expect<TranslationError.Service> { runBlocking { client.chat(base, "k", "m", listOf("user" to "x")) } }.serverMessage?.length,
        )
    }

    @Test
    fun `a server that cannot list models is not an error`(): Unit = runBlocking {
        server.enqueue(json("", 404))
        assertEquals(emptyList<TextModel>(), client.textModels(base, null))
        assertEquals("/v1/models", server.takeRequest().url.encodedPath)
    }

    // -- Parsing ------------------------------------------------------------

    @Test
    fun `content comes as a string or as parts`() {
        assertEquals("Hello", OpenAiChat.translation(completion(" Hello ")))
        val parts = """{"choices":[{"message":{"content":[{"type":"text","text":"Hel"},{"type":"image"},{"type":"text","text":"lo"}]}}]}"""
        assertEquals("Hello", OpenAiChat.translation(parts))
    }

    @Test
    fun `a refusal or a filtered answer is a refusal`() {
        expect<TranslationError.Refused> {
            OpenAiChat.translation("""{"choices":[{"message":{"content":null,"refusal":"I can't help with that."}}]}""")
        }
        expect<TranslationError.Refused> {
            OpenAiChat.translation("""{"choices":[{"message":{"content":""},"finish_reason":"content_filter"}]}""")
        }
    }

    @Test
    fun `an answer cut at the length limit is not a translation`() {
        expect<TranslationError.Truncated> {
            OpenAiChat.translation("""{"choices":[{"message":{"content":"The sun was"},"finish_reason":"length"}]}""")
        }
    }

    @Test
    fun `an empty answer is not a translation`() {
        expect<TranslationError.Empty> { OpenAiChat.translation(completion("  ")) }
        expect<TranslationError.Empty> { OpenAiChat.translation(completion("<passage></passage>")) }
        expect<TranslationError.Empty> { OpenAiChat.translation("""{"choices":[{"message":{"content":null}}]}""") }
    }

    @Test
    fun `anything else is malformed, and an error sent with a 200 is an error`() {
        expect<TranslationError.Malformed> { OpenAiChat.translation("<html>") }
        expect<TranslationError.Malformed> { OpenAiChat.translation("""{"choices":[]}""") }
        expect<TranslationError.Malformed> { OpenAiChat.translation("""{"choices":[{"message":{"content":3}}]}""") }
        expect<TranslationError.RateLimited> { OpenAiChat.translation("""{"error":{"code":429,"message":"busy"}}""") }
    }

    @Test
    fun `models that say they write text are kept, and the rest by name`() {
        val list = """{"data":[
            {"id":"gpt-4o-mini","architecture":{"output_modalities":["text"]}},
            {"id":"gpt-4o-mini-tts","architecture":{"output_modalities":["audio"]}},
            {"id":"tts-is-in-the-name-but-writes-text","output_modalities":["text"]},
            {"id":"llama-3"},
            {"id":"kokoro"},
            {"id":"whisper-1"},
            {"id":"text-embedding-3-small"},
            {"id":"llama-3"}
        ]}"""
        assertEquals(
            listOf("gpt-4o-mini", "llama-3", "tts-is-in-the-name-but-writes-text"),
            OpenAiChat.textModels(list).map { it.id },
        )
        assertEquals(listOf("A", "b"), OpenAiChat.textModels("""[{"name":"b"}, "A"]""").map { it.id })
        expect<TranslationError.Malformed> { OpenAiChat.textModels("nope") }
    }

    @Test
    fun `a listed price is kept per million tokens, and a partial or varying one is dropped`() {
        val list = """{"data":[
            {"id":"paid","pricing":{"prompt":"0.00000015","completion":"0.0000006"}},
            {"id":"free","pricing":{"prompt":"0","completion":"0"}},
            {"id":"router","pricing":{"prompt":"-1","completion":"-1"}},
            {"id":"half","pricing":{"prompt":"0.000001"}},
            {"id":"plain"}
        ]}"""
        val prices = OpenAiChat.textModels(list).associate { it.id to it.price }
        val paid = prices.getValue("paid")!!
        assertEquals(0.15, paid.input, 1e-9)
        assertEquals(0.6, paid.output, 1e-9)
        assertEquals(TextPrice(0.0, 0.0), prices["free"])
        assertEquals(null, prices["router"])
        assertEquals(null, prices["half"])
        assertEquals(null, prices["plain"])
    }

    @Test
    fun `a model's metadata prices it too, already per million tokens`() {
        val list = """{"data":[
            {"id":"google/gemma-3-12b-it","metadata":{"tags":["chat"],"pricing":{"input_tokens":0.05,"output_tokens":0.15}}},
            {"id":"ResembleAI/chatterbox-turbo","metadata":{"tags":["tts"],"pricing":{"input_characters":1}}},
            {"id":"half","metadata":{"pricing":{"input_tokens":0.05}}},
            {"id":"varying","metadata":{"pricing":{"input_tokens":-1,"output_tokens":-1}}},
            {"id":"plain","metadata":{"tags":["chat"]}}
        ]}"""
        val prices = OpenAiChat.textModels(list).associate { it.id to it.price }
        assertEquals(TextPrice(0.05, 0.15), prices.getValue("google/gemma-3-12b-it"))
        // A model that bills by the character has no token price, and one side alone or a varying one is none.
        assertEquals(null, prices["ResembleAI/chatterbox-turbo"])
        assertEquals(null, prices["half"])
        assertEquals(null, prices["varying"])
        assertEquals(null, prices["plain"])
    }

    @Test
    fun `Together lists token prices per million and keeps only text model types`() {
        val list = """[
            {"id":"chat","type":"chat","pricing":{"input":0.3,"output":0.6}},
            {"id":"speech-named-code","type":"code","pricing":{"input":0,"output":0}},
            {"id":"language","type":"language"},
            {"id":"a","type":"image","pricing":{"input":0.3,"output":0.6}},
            {"id":"b","type":"embedding"},
            {"id":"c","type":"moderation"},
            {"id":"d","type":"rerank"},
            {"id":"e","type":"audio"},
            {"id":"f","type":"transcribe"},
            {"id":"g","type":"video"},
            {"id":"explicit-text","type":"audio","output_modalities":["text"]},
            {"id":"explicit-audio","type":"chat","output_modalities":["audio"]},
            {"id":"unknown","type":"model"},
            {"id":"plain"}
        ]"""
        val models = OpenAiChat.textModels(list)
        assertEquals(listOf("chat", "explicit-text", "language", "plain", "speech-named-code", "unknown"), models.map { it.id })
        val prices = models.associate { it.id to it.price }
        assertEquals(TextPrice(0.3, 0.6), prices["chat"])
        assertEquals(TextPrice(0.0, 0.0), prices["speech-named-code"])
        assertEquals(null, prices["language"])
        assertEquals(null, prices["plain"])
    }

    @Test
    fun `Together partial invalid varying and hourly prices are not token prices`() {
        val list = """[
            {"id":"input-only","pricing":{"input":0.3}},
            {"id":"output-only","pricing":{"output":0.6}},
            {"id":"varying","pricing":{"input":-1,"output":0.6}},
            {"id":"invalid","pricing":{"input":"unknown","output":0.6}},
            {"id":"infinite","pricing":{"input":"Infinity","output":0.6}},
            {"id":"null","pricing":{"input":null,"output":0.6}},
            {"id":"hourly","pricing":{"input":0,"output":0,"hourly":2}},
            {"id":"hourly-only","pricing":{"hourly":2}}
        ]"""
        val models = OpenAiChat.textModels(list)
        assertEquals(8, models.size)
        assertTrue(models.all { it.price == null })
    }

    @Test
    fun `errors map to what the reader can do`() {
        assertTrue(OpenAiChat.errorFor(403, "") is TranslationError.InvalidKey)
        assertTrue(OpenAiChat.errorFor(402, "") is TranslationError.RateLimited)
        assertTrue(OpenAiChat.errorFor(503, "") is TranslationError.Service)
        // Without the model asked for, a 404 cannot name it.
        assertTrue(OpenAiChat.errorFor(404, """{"error":{"code":"model_not_found"}}""") is TranslationError.Service)
        assertTrue(OpenAiChat.errorFor(404, """{"error":{"code":"model_not_found"}}""", "m") is TranslationError.ModelNotFound)
        // A list or a check without a model is not told a chat endpoint is missing.
        assertTrue(OpenAiChat.errorFor(404, "Not Found") is TranslationError.Service)
        assertTrue(OpenAiChat.errorFor(404, "Not Found", "m") is TranslationError.NoEndpoint)
    }
}
