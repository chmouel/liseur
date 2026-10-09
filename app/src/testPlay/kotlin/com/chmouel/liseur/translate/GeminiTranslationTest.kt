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
        expect<TranslationError.Empty> { GeminiTranslation.translation(reply(" ")) }
        expect<TranslationError.Malformed> { GeminiTranslation.translation("""{"candidates":[]}""") }
        expect<TranslationError.Malformed> { GeminiTranslation.translation("nope") }
    }

    @Test
    fun `only models that write text are offered`() {
        val list = """{"models":[
            {"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent","countTokens"]},
            {"name":"models/gemini-2.5-flash-preview-tts","supportedGenerationMethods":["generateContent"]},
            {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]},
            {"name":"models/imagen-3","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-flash-lite-latest","supportedGenerationMethods":["generateContent"]}
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
