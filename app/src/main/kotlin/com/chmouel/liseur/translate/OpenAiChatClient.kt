package com.chmouel.liseur.translate

import com.chmouel.liseur.BuildConfig
import com.chmouel.liseur.translate.TranslationHttp.secret
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * An OpenAI-compatible server's chat completions, asked to translate,
 * and its list of models that answer in text. A server known to take a
 * way of turning thinking off is asked with it, as [Thinking] says;
 * [thinking] gives those ways for a server.
 */
class OpenAiChatClient(
    private val client: OkHttpClient = TranslationHttp.client(),
    private val thinking: (HttpUrl) -> List<JSONObject> = Thinking::openAiCompatible,
) {
    /**
     * [passage] translated by [model], with [context] as the text before it
     * when there is one, and [book] as what is known about the book.
     * TranslateGemma is asked in its own format, which has no room for
     * [context] or [book]. [instructions] are the reader's own words
     * for the prompt; [maxTokens] and [keepAlive] (Ollama's, as seconds or
     * a duration) are sent only when set, and [temperature] unless it is
     * null. A server that refuses the temperature, as OpenAI's reasoning
     * models refuse all but their own, is asked once more without it, and
     * [onTemperatureRefused] is told once that worked. Throws [TranslationError];
     * cancelling the caller cancels the request.
     */
    suspend fun translate(
        base: HttpUrl,
        apiKey: String?,
        model: String,
        source: String?,
        target: String,
        passage: String,
        context: String? = null,
        temperature: Double? = 0.0,
        instructions: String? = null,
        maxTokens: Int? = null,
        keepAlive: String? = null,
        onTemperatureRefused: suspend () -> Unit = {},
        book: String? = null,
        beforeRequest: suspend () -> Unit = {},
        allowTransportRetry: Boolean = true,
        usage: ((ChatUsage) -> Unit)? = null,
    ): String {
        val body = translationBody(model, temperature, maxTokens, keepAlive)
        if (TranslationPrompt.isTranslateGemma(model)) {
            val prompt = TranslationPrompt.translateGemma(source, target, passage, instructions)
            body.put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        } else {
            body.put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", TranslationPrompt.system(source, target, context != null, instructions, book != null)))
                    .put(JSONObject().put("role", "user").put("content", TranslationPrompt.user(passage, context, book))),
            )
        }
        val reply = send(base, apiKey, model, body, onTemperatureRefused, beforeRequest, allowTransportRetry)
        usage?.invoke(OpenAiChat.usage(reply))
        return OpenAiChat.translation(reply)
    }

    /**
     * [sentences] translated by [model] in one request, in order, with
     * [context] as the text before the first and [book] as in [translate].
     * Each sentence must be [TranslationPrompt.batchable], and the model
     * must not be TranslateGemma. Throws [TranslationError], and
     * [TranslationError.Malformed] when the reply does not give one answer
     * per sentence.
     */
    suspend fun translateBatch(
        base: HttpUrl,
        apiKey: String?,
        model: String,
        source: String?,
        target: String,
        sentences: List<String>,
        context: String? = null,
        temperature: Double? = 0.0,
        instructions: String? = null,
        maxTokens: Int? = null,
        keepAlive: String? = null,
        onTemperatureRefused: suspend () -> Unit = {},
        book: String? = null,
        beforeRequest: suspend () -> Unit = {},
        allowTransportRetry: Boolean = true,
        usage: ((ChatUsage) -> Unit)? = null,
    ): List<String> {
        require(!TranslationPrompt.isTranslateGemma(model))
        require(sentences.isNotEmpty() && sentences.all(TranslationPrompt::batchable))
        val body = translationBody(model, temperature, maxTokens, keepAlive)
        body.put(
            "messages",
            JSONArray()
                .put(JSONObject().put("role", "system").put("content", TranslationPrompt.batchSystem(source, target, context != null, instructions, book != null)))
                .put(JSONObject().put("role", "user").put("content", TranslationPrompt.batchUser(sentences, context, book))),
        )
        val reply = send(base, apiKey, model, body, onTemperatureRefused, beforeRequest, allowTransportRetry)
        usage?.invoke(OpenAiChat.usage(reply))
        return OpenAiChat.batch(reply, sentences.size)
    }

    private fun translationBody(model: String, temperature: Double?, maxTokens: Int?, keepAlive: String?): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("stream", false)
        // Translation wants the likeliest wording, and the same sentence then gets the same translation.
        temperature?.let { body.put("temperature", if (it == 0.0) 0 else it) }
        maxTokens?.let { body.put("max_tokens", it) }
        // Ollama reads a bare number as seconds but a string only as a duration with its unit.
        keepAlive?.let { body.put("keep_alive", it.toLongOrNull() ?: it) }
        return body
    }

    /**
     * What [model] answers [messages], each a role and its content, with
     * any reasoning a thinking model wrote first taken off. [temperature]
     * is sent unless null, and a server refusing it is asked once more
     * without, as [translate] does. [beforeRequest] is called before each
     * request goes, so a caller can bound how many it makes. Throws
     * [TranslationError]; cancelling the caller cancels the request.
     */
    suspend fun chat(
        base: HttpUrl,
        apiKey: String?,
        model: String,
        messages: List<Pair<String, String>>,
        temperature: Double? = 0.0,
        beforeRequest: suspend () -> Unit = {},
        onTemperatureRefused: suspend () -> Unit = {},
    ): String {
        val body = JSONObject()
            .put("model", model)
            .put("stream", false)
        temperature?.let { body.put("temperature", if (it == 0.0) 0 else it) }
        body.put(
            "messages",
            JSONArray().apply { messages.forEach { (role, content) -> put(JSONObject().put("role", role).put("content", content)) } },
        )
        return OpenAiChat.answer(send(base, apiKey, model, body, onTemperatureRefused, beforeRequest))
    }

    private suspend fun send(
        base: HttpUrl,
        apiKey: String?,
        model: String,
        body: JSONObject,
        onTemperatureRefused: suspend () -> Unit,
        beforeRequest: suspend () -> Unit = {},
        allowTransportRetry: Boolean = true,
    ): String {
        val hadTemperature = body.has("temperature")
        val reply = Thinking.climb(
            "$base|$model",
            thinking(base),
            { it is ChatReply.Failure && Thinking.refused(it.code, it.body) },
            { it is ChatReply.Text },
        ) { way ->
            way?.keys()?.forEach { body.put(it, way.get(it)) }
            try {
                ask(base, apiKey, body, beforeRequest, allowTransportRetry)
            } finally {
                way?.keys()?.forEach { body.remove(it) }
            }
        }
        if (reply is ChatReply.Text && hadTemperature && !body.has("temperature")) onTemperatureRefused()
        return when (reply) {
            is ChatReply.Text -> reply.text
            is ChatReply.Failure -> throw OpenAiChat.errorFor(reply.code, reply.body, model).retryingAfter(reply.retryAfter)
        }
    }

    private suspend fun ask(
        base: HttpUrl,
        apiKey: String?,
        body: JSONObject,
        beforeRequest: suspend () -> Unit,
        allowTransportRetry: Boolean,
    ): ChatReply {
        beforeRequest()
        val first = complete(base, apiKey, body, allowTransportRetry)
        val refused = first is ChatReply.Failure && first.code == 400 && body.has("temperature") &&
            first.body.contains("temperature", ignoreCase = true)
        if (!refused) return first
        body.remove("temperature")
        beforeRequest()
        return complete(base, apiKey, body, allowTransportRetry)
    }

    private val noRecovery by lazy { client.newBuilder().retryOnConnectionFailure(false).authenticator(okhttp3.Authenticator.NONE).build() }

    private suspend fun complete(base: HttpUrl, apiKey: String?, body: JSONObject, allowTransportRetry: Boolean): ChatReply {
        val payload = body.toString().toRequestBody(JSON).let { if (allowTransportRetry) it else with(TranslationHttp) { it.oneShot() } }
        val request = request(base, "chat/completions", apiKey).post(payload).build()
        return TranslationHttp.execute(if (allowTransportRetry) client else noRecovery, request, ::reply)
    }

    /**
     * The models the server lists that answer in text, by name; empty when
     * it lists none. Throws [TranslationError].
     */
    suspend fun textModels(base: HttpUrl, apiKey: String?): List<TextModel> =
        TranslationHttp.execute(client, request(base, "models", apiKey).get().build(), ::models)

    private fun request(base: HttpUrl, path: String, apiKey: String?): Request.Builder {
        val builder = Request.Builder().url(base.newBuilder().addPathSegments(path).build()).header("User-Agent", USER_AGENT)
        return if (apiKey.isNullOrBlank()) builder else builder.secret("Authorization", "Bearer $apiKey")
    }

    private fun reply(response: Response): ChatReply {
        val text = TranslationHttp.boundedText(response, TranslationHttp.MAX_REPLY_BYTES)
        if (!response.isSuccessful) return ChatReply.Failure(response.code, text.orEmpty().take(TranslationHttp.MAX_ERROR_BYTES.toInt()), response.header("Retry-After"))
        if (text == null) throw TranslationError.Protocol("reply too large")
        return ChatReply.Text(text)
    }

    /** A chat completion's reply, read but not yet judged, so a refused option can be asked again without. */
    private sealed interface ChatReply {
        class Text(val text: String) : ChatReply
        class Failure(val code: Int, val body: String, val retryAfter: String? = null) : ChatReply
    }

    private fun models(response: Response): List<TextModel> {
        if (response.code == 404 || response.code == 405) return emptyList()
        val text = TranslationHttp.boundedText(response, TranslationHttp.MAX_LIST_BYTES)
        if (!response.isSuccessful) throw errorFor(response.code, text.orEmpty())
        if (text == null) throw TranslationError.Malformed("list too large")
        return OpenAiChat.textModels(text)
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val USER_AGENT = "Liseur/${BuildConfig.VERSION_NAME} (+https://github.com/chmouel/liseur)"

        private fun errorFor(code: Int, body: String): TranslationError =
            OpenAiChat.errorFor(code, body.take(TranslationHttp.MAX_ERROR_BYTES.toInt()))
    }
}

/** A model a server lists that writes text, with its [price] when the list gives one. */
data class TextModel(val id: String, val price: TextPrice? = null)

/** Dollars per million tokens sent to a model and written by it. */
data class TextPrice(val input: Double, val output: Double)

/** Reading what an OpenAI-compatible server answers, apart from the network for testing. */
internal object OpenAiChat {
    /**
     * The translation in a chat completion. Throws [TranslationError] for a
     * refusal, a filtered or empty answer, or anything not a completion.
     */
    fun translation(text: String): String = TranslationPrompt.clean(content(text)).ifEmpty { throw TranslationError.Empty() }

    /** The [count] translations in a reply to a batch, as [TranslationPrompt.batchReply] reads them. */
    fun batch(text: String, count: Int): List<String> = TranslationPrompt.batchReply(content(text), count)

    /**
     * The token counts a completion reports, each null when it does not:
     * a server that says nothing of its cache is unknown, not uncached.
     * Never throws; [translation] judges the reply itself.
     */
    fun usage(text: String): ChatUsage {
        val usage = try {
            JSONObject(text.trim()).optJSONObject("usage")
        } catch (_: JSONException) {
            null
        }
        fun tokens(from: JSONObject?, name: String): Long? = when (val value = from?.opt(name)) {
            is Int -> value.toLong().takeIf { it >= 0 }
            is Long -> value.takeIf { it in 0..Int.MAX_VALUE.toLong() }
            else -> null
        }
        return ChatUsage(
            tokens(usage, "prompt_tokens"),
            tokens(usage?.optJSONObject("prompt_tokens_details"), "cached_tokens"),
            tokens(usage, "completion_tokens"),
        )
    }

    /**
     * The answer in a chat completion, without the reasoning a thinking
     * model may have opened it with. Throws [TranslationError] as
     * [translation] does.
     */
    fun answer(text: String): String =
        TranslationPrompt.withoutReasoning(content(text).trim()).ifEmpty { throw TranslationError.Empty() }

    private fun content(text: String): String {
        val root = try {
            JSONObject(text.trim())
        } catch (_: JSONException) {
            throw TranslationError.Protocol("not JSON")
        }
        // OpenRouter answers some failures with a 200 and an error object.
        root.optJSONObject("error")?.let { error -> throw errorFor(error.optInt("code", 500), root.toString()) }
        val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: throw TranslationError.Protocol("no choices")
        val message = choice.optJSONObject("message") ?: throw TranslationError.Protocol("no message")
        if ((message.opt("refusal") as? String)?.isNotBlank() == true) throw TranslationError.Refused()
        when (choice.optString("finish_reason")) {
            "content_filter" -> throw TranslationError.Refused()
            "length" -> throw TranslationError.Truncated()
        }
        return when (val raw = message.opt("content")) {
            is String -> raw
            is JSONArray -> {
                val parts = (0 until raw.length()).mapNotNull { i ->
                    val part = raw.optJSONObject(i) ?: throw TranslationError.Protocol("invalid content part")
                    if (part.optString("type") != "text") null
                    else part.opt("text") as? String ?: throw TranslationError.Protocol("invalid text part")
                }
                if (parts.isEmpty()) throw TranslationError.Protocol("no text parts")
                parts.joinToString("")
            }
            JSONObject.NULL -> ""
            null -> throw TranslationError.Protocol("no content")
            else -> throw TranslationError.Protocol("unexpected content")
        }
    }

    /**
     * The models in a list that answer in text, sorted by name: those that
     * say their output includes text or their type writes it, or say
     * nothing and are not named for speech, images or embeddings.
     * Each keeps its price when the list has
     * one, as OpenRouter's, DeepInfra's and Together's do.
     */
    fun textModels(text: String): List<TextModel> {
        val items = try {
            val trimmed = text.trimStart()
            if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).optJSONArray("data")
        } catch (_: JSONException) {
            null
        } ?: throw TranslationError.Malformed("no list")
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i)
            val id = item?.let { (it.opt("id") as? String) ?: (it.opt("name") as? String) } ?: items.opt(i) as? String
            id?.takeIf { it.isNotBlank() && writesText(item, it) }?.let { TextModel(it, price(item)) }
        }.distinctBy { it.id }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.id })
    }

    /**
     * Dollars per million tokens sent and written when the list gives
     * both: DeepInfra's, in its model metadata and already per million,
     * Together's `pricing.input`/`output`, also per million, or
     * OpenRouter's `pricing`, dollars per token as text, where a
     * negative one means the price varies. A model that bills by the
     * character, second or hour has no token price, and one side alone is
     * none: half a price is not worth showing as the whole one.
     */
    private fun price(item: JSONObject?): TextPrice? {
        val metadata = item?.optJSONObject("metadata")?.optJSONObject("pricing")
        if (metadata != null) {
            fun perMillion(key: String) = metadata.optDouble(key).takeIf { it.isFinite() && it >= 0 }
            return TextPrice(perMillion("input_tokens") ?: return null, perMillion("output_tokens") ?: return null)
        }
        val pricing = item?.optJSONObject("pricing") ?: return null
        if (pricing.has("input") || pricing.has("output")) {
            if (pricing.optDouble("hourly", 0.0) > 0) return null
            fun perMillion(key: String) = pricing.optDouble(key).takeIf { it.isFinite() && it >= 0 }
            return TextPrice(perMillion("input") ?: return null, perMillion("output") ?: return null)
        }
        fun perMillion(key: String) = pricing.optString(key).toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.times(1_000_000)
        return TextPrice(perMillion("prompt") ?: return null, perMillion("completion") ?: return null)
    }

    private fun writesText(item: JSONObject?, id: String): Boolean {
        val output = item?.optJSONArray("output_modalities")
            ?: item?.optJSONObject("architecture")?.optJSONArray("output_modalities")
        if (output != null) return (0 until output.length()).any { output.optString(it).equals("text", ignoreCase = true) }
        when (item?.optString("type")?.lowercase()) {
            "chat", "language", "code" -> return true
            "image", "embedding", "moderation", "rerank", "audio", "transcribe", "video" -> return false
        }
        val lower = id.lowercase()
        return NOT_TEXT.none { it in lower }
    }

    private val NOT_TEXT = listOf(
        "tts", "speech", "whisper", "transcribe", "embed", "dall-e", "image", "moderation", "kokoro", "rerank",
    )

    /**
     * A failed request to [model], when it was one, from its status and
     * reply. A 404 is the model missing only when the reply says so, as
     * Ollama's "model not found" and OpenAI's `model_not_found` do; one
     * without an OpenAI-style error object is the route missing, as a
     * wrong address gets, and one with any other error, such as
     * OpenRouter's data policy, is the service's own failure.
     */
    fun errorFor(code: Int, body: String, model: String? = null): TranslationError {
        val said = TranslationError.serverMessage(body)
        return when {
            code == 401 || code == 403 -> TranslationError.InvalidKey(code, said)
            code == 429 || code == 402 -> TranslationError.RateLimited(code, said)
            code == 404 && model != null && missingModel(body) -> TranslationError.ModelNotFound(model, said)
            code == 404 && model != null && !hasErrorObject(body) -> TranslationError.NoEndpoint(said)
            else -> TranslationError.Service(code, said)
        }
    }

    private fun hasErrorObject(body: String): Boolean = try {
        JSONObject(body.trim()).optJSONObject("error") != null
    } catch (_: JSONException) {
        false
    }

    private fun missingModel(body: String): Boolean {
        val code = try {
            JSONObject(body.trim()).optJSONObject("error")?.opt("code") as? String
        } catch (_: JSONException) {
            null
        }
        if (code == "model_not_found") return true
        val text = body.lowercase()
        return "model" in text && ("not found" in text || "does not exist" in text)
    }
}
