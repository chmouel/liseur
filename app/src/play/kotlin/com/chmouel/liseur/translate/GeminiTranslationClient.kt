package com.chmouel.liseur.translate

import com.chmouel.liseur.BuildConfig
import com.chmouel.liseur.translate.TranslationHttp.secret
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The Gemini API's generateContent, asked to translate or to chat, and its list of text models. */
class GeminiTranslationClient(
    private val client: OkHttpClient = TranslationHttp.client(),
    private val base: HttpUrl = GeminiTranslation.ENDPOINT.toHttpUrl(),
) {
    /**
     * [passage] translated by [model], with [context] as the text before it
     * when there is one, and [book] as what is known about the book. Throws [TranslationError]; cancelling the caller
     * cancels the request.
     */
    internal suspend fun translate(
        apiKey: String,
        model: String,
        source: String?,
        target: String,
        passage: String,
        context: String? = null,
        book: String? = null,
        beforeRequest: suspend () -> Unit = {},
        allowTransportRetry: Boolean = true,
        diagnostics: GeminiCacheDiagnostics? = null,
    ): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", TranslationPrompt.system(source, target, context != null, book = book != null)))))
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", TranslationPrompt.user(passage, context, book)))),
                ),
            )
            // Translation wants the likeliest wording, and the same sentence then gets the same translation.
            .put("generationConfig", JSONObject().put("temperature", 0))
        val url = base.newBuilder().addPathSegment("$model:generateContent").build()
        return unthinking(model, body, beforeRequest) {
            val payload = it.toRequestBody(JSON).let { bytes -> if (allowTransportRetry) bytes else with(TranslationHttp) { bytes.oneShot() } }
            diagnostics?.started(body, book)
            val started = System.nanoTime()
            var completed = false
            try {
                TranslationHttp.execute(if (allowTransportRetry) client else noRecovery, request(url, apiKey).post(payload).build()) { response ->
                    reply(response, diagnostics)
                }.also { completed = true }
            } finally {
                diagnostics?.finished((System.nanoTime() - started) / 1_000_000, completed)
            }
        }
    }

    /**
     * What [send] answers for [body] with the first of [Thinking.GEMINI]
     * [model] does not refuse, or with none. [beforeRequest] guards
     * every attempt, including retries with another thinking option.
     */
    private val noRecovery by lazy { client.newBuilder().retryOnConnectionFailure(false).authenticator(okhttp3.Authenticator.NONE).build() }

    private suspend fun unthinking(model: String, body: JSONObject, beforeRequest: suspend () -> Unit = {}, send: suspend (String) -> String): String {
        val config = body.getJSONObject("generationConfig")
        return Thinking.climb(
            "gemini|$model",
            Thinking.GEMINI,
            { it.exceptionOrNull().let { e -> e is TranslationError.Service && Thinking.refused(e.code, e.serverMessage) } },
            { it.isSuccess },
        ) { way ->
            if (way == null) config.remove("thinkingConfig") else config.put("thinkingConfig", way)
            beforeRequest()
            try {
                Result.success(send(body.toString()))
            } catch (e: TranslationError) {
                Result.failure(e)
            }
        }.getOrThrow()
    }

    /**
     * What [model] answers [messages], each a role and its content: system
     * messages become the system instruction, and an assistant's turns the
     * model's. [beforeRequest] is called before the request goes. Throws
     * [TranslationError]; cancelling the caller cancels the request.
     */
    suspend fun chat(apiKey: String, model: String, messages: List<Pair<String, String>>, beforeRequest: suspend () -> Unit = {}): String {
        val body = GeminiTranslation.chatBody(messages)
        val url = base.newBuilder().addPathSegment("$model:generateContent").build()
        return unthinking(model, body, beforeRequest) { TranslationHttp.execute(client, request(url, apiKey).post(it.toRequestBody(JSON)).build(), ::answer) }
    }

    /** The models the key can use that write text, by id, sorted. Throws [TranslationError]. */
    suspend fun models(apiKey: String): List<String> {
        val url = base.newBuilder().addQueryParameter("pageSize", "1000").build()
        return TranslationHttp.execute(client, request(url, apiKey).get().build(), ::models)
    }

    private fun request(url: HttpUrl, apiKey: String): Request.Builder =
        Request.Builder().url(url).secret("x-goog-api-key", apiKey).header("User-Agent", USER_AGENT)

    private fun reply(response: Response, diagnostics: GeminiCacheDiagnostics?): String {
        val text = TranslationHttp.boundedText(response, TranslationHttp.MAX_REPLY_BYTES)
        if (!response.isSuccessful) throw GeminiTranslation.errorFor(response.code, text.orEmpty())
        if (text == null) throw TranslationError.Protocol("reply too large")
        if (diagnostics != null) {
            val root = try {
                JSONObject(text)
            } catch (_: org.json.JSONException) {
                throw TranslationError.Protocol("not JSON")
            }
            diagnostics.response(GeminiUsage.parse(root))
        }
        return GeminiTranslation.translation(text)
    }

    private fun answer(response: Response): String {
        val text = TranslationHttp.boundedText(response, TranslationHttp.MAX_REPLY_BYTES)
        if (!response.isSuccessful) throw GeminiTranslation.errorFor(response.code, text.orEmpty())
        if (text == null) throw TranslationError.Malformed("reply too large")
        return GeminiTranslation.content(text).ifBlank { throw TranslationError.Empty() }
    }

    private fun models(response: Response): List<String> {
        val text = TranslationHttp.boundedText(response, TranslationHttp.MAX_LIST_BYTES)
        if (!response.isSuccessful) throw GeminiTranslation.errorFor(response.code, text.orEmpty())
        if (text == null) throw TranslationError.Malformed("list too large")
        return GeminiTranslation.textModels(text)
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val USER_AGENT = "Liseur/${BuildConfig.VERSION_NAME} (+https://github.com/chmouel/liseur)"
    }
}

/** Reading what the Gemini API answers, apart from the network for testing. */
internal object GeminiTranslation {
    const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"

    /** Quick and cheap, which suits a passage at a time. */
    const val DEFAULT_MODEL = "gemini-flash-lite-latest"

    /** The model a stored name stands for; none stored is the default. */
    fun modelOf(stored: String?): String = stored?.trim()?.removePrefix("models/")?.takeIf { it.isNotEmpty() } ?: DEFAULT_MODEL

    private val BLOCKED = setOf("SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "IMAGE_SAFETY")

    /** The translation in a generateContent reply. Throws [TranslationError] for a block, an empty answer or anything else. */
    fun translation(text: String): String = TranslationPrompt.clean(content(text)).ifEmpty { throw TranslationError.Empty() }

    /**
     * A generateContent body for a chat: system messages joined as the
     * system instruction, the rest as turns, an assistant's as the model's.
     * The reply is asked for as JSON, which every chat expects.
     */
    fun chatBody(messages: List<Pair<String, String>>): JSONObject {
        val system = messages.filter { it.first == "system" }.joinToString("\n\n") { it.second }
        val contents = JSONArray()
        messages.filter { it.first != "system" }.forEach { (role, content) ->
            contents.put(
                JSONObject()
                    .put("role", if (role == "assistant") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", content))),
            )
        }
        val body = JSONObject()
        if (system.isNotEmpty()) body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        return body
            .put("contents", contents)
            // The same question about the same passages then gets the same answer. Without the JSON
            // type, Gemini reads the prompt's {"op":…} actions as a tool call and fails the reply
            // with MALFORMED_FUNCTION_CALL.
            .put("generationConfig", JSONObject().put("temperature", 0).put("responseMimeType", "application/json"))
    }

    /** The text of a generateContent reply, thoughts left out. Throws [TranslationError] for a block or an unfinished answer. */
    fun content(text: String): String {
        val root = try {
            JSONObject(text.trim())
        } catch (_: JSONException) {
            throw TranslationError.Protocol("not JSON")
        }
        if (root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty().isNotEmpty()) {
            throw TranslationError.Refused()
        }
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw TranslationError.Protocol("no candidates")
        val finish = candidate.optString("finishReason")
        if (finish.isEmpty()) throw TranslationError.Protocol("no finish reason")
        if (finish in BLOCKED) throw TranslationError.Refused()
        if (finish == "MAX_TOKENS") throw TranslationError.Truncated()
        // Anything else that is not a normal stop, such as OTHER or LANGUAGE, may come with partial text;
        // no reason at all means the model had not finished.
        if (finish != "STOP") throw TranslationError.Malformed("finished with ${finish.ifEmpty { "no reason" }}")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            ?: throw TranslationError.Protocol("no content parts")
        return (0 until parts.length()).mapNotNull { i ->
            // A thinking model's thoughts come as parts of their own.
            val part = parts.optJSONObject(i) ?: throw TranslationError.Protocol("invalid content part")
            if (part.optBoolean("thought")) null
            else part.opt("text") as? String ?: throw TranslationError.Protocol("invalid text part")
        }.joinToString("")
    }

    /** The models in a list that generate content and are not for speech, images, music or embeddings, sorted by id. */
    fun textModels(text: String): List<String> {
        val models = try {
            JSONObject(text).optJSONArray("models")
        } catch (_: JSONException) {
            throw TranslationError.Malformed("not JSON")
        } ?: return emptyList()
        return (0 until models.length()).mapNotNull { i ->
            val model = models.optJSONObject(i) ?: return@mapNotNull null
            val methods = model.optJSONArray("supportedGenerationMethods") ?: return@mapNotNull null
            if ((0 until methods.length()).none { methods.optString(it) == "generateContent" }) return@mapNotNull null
            val id = model.optString("name").removePrefix("models/")
            id.takeIf { it.isNotEmpty() && NOT_TEXT.none { word -> word in it.lowercase() } }
        }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    private val NOT_TEXT = listOf(
        "tts", "image", "imagen", "veo", "lyria", "embedding", "aqa", "robotics", "computer-use", "deep-research",
        "transcribe", "audio", "live", "antigravity", "learnlm",
    )

    fun errorFor(code: Int, body: String): TranslationError {
        val bounded = body.take(TranslationHttp.MAX_ERROR_BYTES.toInt())
        val (status, reasons) = describe(bounded)
        val said = TranslationError.serverMessage(bounded)
        return when {
            code == 429 || status == "RESOURCE_EXHAUSTED" -> TranslationError.RateLimited(code, said)
            code == 401 || code == 403 || status == "UNAUTHENTICATED" ||
                reasons.any { it.startsWith("API_KEY_") } -> TranslationError.InvalidKey(code, said)
            else -> TranslationError.Service(code, said)
        }
    }

    /** The RPC status and error reasons, from either error shape the API answers with. */
    private fun describe(body: String): Pair<String?, List<String>> {
        val error = try {
            val trimmed = body.trimStart()
            val root = if (trimmed.startsWith("[")) JSONArray(trimmed).optJSONObject(0) else JSONObject(trimmed)
            root?.optJSONObject("error")
        } catch (_: JSONException) {
            null
        } ?: return null to emptyList()
        val details = error.optJSONArray("details")
        val reasons = (0 until (details?.length() ?: 0)).mapNotNull { details?.optJSONObject(it)?.optString("reason") }
        return error.optString("status").takeIf { it.isNotEmpty() } to reasons
    }
}
