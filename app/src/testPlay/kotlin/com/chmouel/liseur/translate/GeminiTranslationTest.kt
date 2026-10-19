package com.chmouel.liseur.translate

import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class GeminiTranslationTest {

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

    private fun reply(vararg parts: String, finish: String = "STOP") = JSONObject()
        .put(
            "candidates",
            org.json.JSONArray().put(
                JSONObject()
                    .put("finishReason", finish)
                    .put("content", JSONObject().put("parts", org.json.JSONArray().apply { parts.forEach { put(JSONObject().put("text", it)) } })),
            ),
        ).toString()

    @Test
    fun `pinned Gemini requests do not replay 503 responses`(): Unit = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "0").body("{}").build())
        server.enqueue(MockResponse.Builder().code(200).body(reply("Should not be sent")).build())
        val client = GeminiTranslationClient(base = server.url("/v1beta/models"))
        expect<TranslationError.Service> {
            client.translate("secret", "gemini-flash-lite-latest", "en", "fr", "x", allowTransportRetry = false)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `Gemini malformed envelopes are protocol failures`() {
        for (body in listOf(
            "not JSON", "{}", """{"candidates":[{}]}""", """{"candidates":[{"finishReason":"STOP"}]}""",
            """{"candidates":[{"finishReason":"STOP","content":{"parts":[{}]}}]}""",
        )) {
            expect<TranslationError.Protocol> { GeminiTranslation.translation(body) }
        }
    }

    @Test
    fun `translation guard stops a thinking retry before it submits`(): Unit = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(400)
                .body("""{"error":{"code":400,"message":"Thinking level MINIMAL is not supported.","status":"INVALID_ARGUMENT"}}""").build(),
        )
        val client = GeminiTranslationClient(base = server.url("/v1beta/models"))
        var attempts = 0
        expect<TranslationError.SettingsChanged> {
            client.translate("secret", "gemini-flash-latest", "en", "fr", "It rains.", beforeRequest = {
                if (++attempts == 2) throw TranslationError.SettingsChanged()
            })
        }
        assertEquals(2, attempts)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `asks generateContent with the key in a header and the task as the system instruction`(): Unit = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(reply("Hello")).build())
        val client = GeminiTranslationClient(base = server.url("/v1beta/models"))

        assertEquals("Hello", client.translate("secret", "gemini-flash-lite-latest", "fr", "en", "Bonjour"))

        val request = server.takeRequest()
        assertEquals("/v1beta/models/gemini-flash-lite-latest:generateContent", request.url.encodedPath)
        assertEquals("secret", request.headers["x-goog-api-key"])
        assertEquals(null, request.url.queryParameter("key"))
        val body = JSONObject(request.body!!.utf8())
        assertTrue("into English (en)" in body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals(0, body.getJSONObject("generationConfig").getInt("temperature"))
        assertEquals("minimal", body.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getString("thinkingLevel"))
    }

    @Test
    fun `a model that refuses the lowest thinking level is asked with no thinking budget`(): Unit = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(400)
                .body("""{"error":{"code":400,"message":"Thinking level MINIMAL is not supported for this model.","status":"INVALID_ARGUMENT"}}""").build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body(reply("Hello")).build())
        val client = GeminiTranslationClient(base = server.url("/v1beta/models"))

        assertEquals("Hello", client.translate("k", "gemini-flash-latest", "fr", "en", "Bonjour"))

        server.takeRequest()
        val config = JSONObject(server.takeRequest().body!!.utf8()).getJSONObject("generationConfig")
        assertEquals(0, config.getJSONObject("thinkingConfig").getInt("thinkingBudget"))
        assertTrue(!config.getJSONObject("thinkingConfig").has("thinkingLevel"))
        assertEquals(0, config.getInt("temperature"))
    }

    @Test
    fun `a model that must think uses low effort and remembers it`(): Unit = runBlocking {
        repeat(2) {
            server.enqueue(MockResponse.Builder().code(400).body("""{"error":{"message":"This thinking option is unsupported"}}""").build())
        }
        repeat(2) { server.enqueue(MockResponse.Builder().code(200).body(reply("Hello")).build()) }
        val client = GeminiTranslationClient(base = server.url("/v1beta/models"))

        repeat(2) { assertEquals("Hello", client.translate("k", "m", "fr", "en", "Bonjour")) }

        server.takeRequest()
        server.takeRequest()
        repeat(2) {
            val config = JSONObject(server.takeRequest().body!!.utf8()).getJSONObject("generationConfig").getJSONObject("thinkingConfig")
            assertEquals("low", config.getString("thinkingLevel"))
            assertTrue(!config.has("thinkingBudget"))
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `thinking retries recheck the request guard before sending`(): Unit = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(400)
                .body("""{"error":{"message":"Thinking level MINIMAL is not supported for this model."}}""").build(),
        )
        val client = GeminiTranslationClient(base = server.url("/v1beta/models"))
        var attempts = 0

        expect<TranslationError.Changed> {
            runBlocking {
                client.chat("k", "m", listOf("user" to "q")) {
                    if (++attempts == 2) throw TranslationError.Changed()
                }
            }
        }

        assertEquals(2, attempts)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a chat sends system messages as the instruction and the assistant's turns as the model's`(): Unit = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(reply("""{"answer":"x"}""")).build())
        val client = GeminiTranslationClient(base = server.url("/v1beta/models"))
        var asked = false

        val answer = client.chat(
            "secret",
            "gemini-flash-latest",
            listOf("system" to "Rules", "user" to "Who?", "assistant" to "search", "user" to "Passages"),
        ) { asked = true }

        assertEquals("""{"answer":"x"}""", answer)
        assertTrue(asked)
        val request = server.takeRequest()
        assertEquals("/v1beta/models/gemini-flash-latest:generateContent", request.url.encodedPath)
        assertEquals("secret", request.headers["x-goog-api-key"])
        val body = JSONObject(request.body!!.utf8())
        assertEquals("Rules", body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        val contents = body.getJSONArray("contents")
        assertEquals(listOf("user", "model", "user"), (0 until contents.length()).map { contents.getJSONObject(it).getString("role") })
        assertEquals("search", contents.getJSONObject(1).getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals("application/json", body.getJSONObject("generationConfig").getString("responseMimeType"))
    }

    @Test
    fun `a chat reply keeps its text as written, and an empty one is empty`(): Unit = runBlocking {
        val client = GeminiTranslationClient(base = server.url("/v1beta/models"))
        server.enqueue(MockResponse.Builder().code(200).body(reply("```json\n{}\n```")).build())
        assertEquals("```json\n{}\n```", client.chat("k", "m", listOf("user" to "q")))
        server.enqueue(MockResponse.Builder().code(200).body(reply(" ")).build())
        expect<TranslationError.Empty> { runBlocking { client.chat("k", "m", listOf("user" to "q")) } }
    }

    @Test
    fun `a refused key and a spent quota say so`(): Unit = runBlocking {
        val client = GeminiTranslationClient(base = server.url("/v1beta/models"))
        server.enqueue(
            MockResponse.Builder().code(400)
                .body("""{"error":{"code":400,"status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}""").build(),
        )
        expect<TranslationError.InvalidKey> { runBlocking { client.translate("k", "m", "fr", "en", "x") } }
        server.enqueue(MockResponse.Builder().code(429).body("""{"error":{"status":"RESOURCE_EXHAUSTED"}}""").build())
        expect<TranslationError.RateLimited> { runBlocking { client.translate("k", "m", "fr", "en", "x") } }
    }

    @Test
    fun `what Gemini said is kept, in either error shape`() {
        assertEquals(
            "Developer instruction is not enabled for models/gemma-3-1b-it",
            GeminiTranslation.errorFor(
                400,
                """{"error":{"code":400,"message":"Developer instruction is not enabled for models/gemma-3-1b-it","status":"INVALID_ARGUMENT"}}""",
            ).serverMessage,
        )
        assertEquals(
            "Quota exceeded",
            GeminiTranslation.errorFor(429, """[{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED"}}]""").serverMessage,
        )
    }

    @Test
    fun `thoughts are not part of the translation`() {
        val text = JSONObject(reply("Hel", "lo")).apply {
            getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                .put(0, JSONObject().put("text", "Let me think").put("thought", true))
        }.toString()
        assertEquals("lo", GeminiTranslation.translation(text))
    }

    @Test
    fun `a block is a refusal, an empty answer is empty, anything else malformed`() {
        expect<TranslationError.Refused> { GeminiTranslation.translation("""{"promptFeedback":{"blockReason":"SAFETY"}}""") }
        expect<TranslationError.Refused> { GeminiTranslation.translation(reply("partial", finish = "RECITATION")) }
        expect<TranslationError.Truncated> { GeminiTranslation.translation(reply("The sun was", finish = "MAX_TOKENS")) }
        expect<TranslationError.Malformed> { GeminiTranslation.translation(reply("The sun", finish = "OTHER")) }
        expect<TranslationError.Malformed> { GeminiTranslation.translation(reply("The sun", finish = "LANGUAGE")) }
        expect<TranslationError.Malformed> { GeminiTranslation.translation(reply("The sun", finish = "")) }
        expect<TranslationError.Empty> { GeminiTranslation.translation(reply(" ")) }
        expect<TranslationError.Malformed> { GeminiTranslation.translation("""{"candidates":[]}""") }
        expect<TranslationError.Malformed> { GeminiTranslation.translation("nope") }
    }

    @Test
    fun `only models that write text are offered, sorted`() {
        val list = """{"models":[
            {"name":"models/gemini-flash-lite-latest","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent","countTokens"]},
            {"name":"models/gemini-2.5-flash-preview-tts","supportedGenerationMethods":["generateContent"]},
            {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]},
            {"name":"models/imagen-3","supportedGenerationMethods":["generateContent"]}
        ]}"""
        assertEquals(listOf("gemini-2.5-flash", "gemini-flash-lite-latest"), GeminiTranslation.textModels(list))
    }

    @Test
    fun `a stored model name is read without its prefix, and none is the default`() {
        assertEquals("gemini-2.5-flash", GeminiTranslation.modelOf(" models/gemini-2.5-flash "))
        assertEquals(GeminiTranslation.DEFAULT_MODEL, GeminiTranslation.modelOf(" "))
        assertEquals(GeminiTranslation.DEFAULT_MODEL, GeminiTranslation.modelOf(null))
    }
}
