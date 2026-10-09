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
 * and its list of models that answer in text.
 */
class OpenAiChatClient(private val client: OkHttpClient = TranslationHttp.client()) {
    /**
     * [passage] translated by [model], with [context] as the text before it
     * when there is one. Throws [TranslationError]; cancelling the caller
     * cancels the request.
     */
    suspend fun translate(
        base: HttpUrl,
        apiKey: String?,
        model: String,
        source: String?,
        target: String,
        passage: String,
        context: String? = null,
    ): String {
        val body = JSONObject()
            .put("model", model)
            .put("stream", false)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", TranslationPrompt.system(source, target, context != null)))
                    .put(JSONObject().put("role", "user").put("content", TranslationPrompt.user(passage, context))),
            )
            .toString()
        val request = request(base, "chat/completions", apiKey).post(body.toRequestBody(JSON)).build()
        return TranslationHttp.execute(client, request, ::reply)
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

    private fun reply(response: Response): String {
        val text = TranslationHttp.boundedText(response, TranslationHttp.MAX_REPLY_BYTES)
        if (!response.isSuccessful) throw errorFor(response.code, text.orEmpty())
        if (text == null) throw TranslationError.Malformed("reply too large")
        return OpenAiChat.translation(text)
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
    fun translation(text: String): String {
        val root = try {
            JSONObject(text.trim())
        } catch (_: JSONException) {
            throw TranslationError.Malformed("not JSON")
        }
        // OpenRouter answers some failures with a 200 and an error object.
        root.optJSONObject("error")?.let { error -> throw errorFor(error.optInt("code", 500), root.toString()) }
        val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: throw TranslationError.Malformed("no choices")
        val message = choice.optJSONObject("message") ?: throw TranslationError.Malformed("no message")
        if ((message.opt("refusal") as? String)?.isNotBlank() == true) throw TranslationError.Refused()
        when (choice.optString("finish_reason")) {
            "content_filter" -> throw TranslationError.Refused()
            "length" -> throw TranslationError.Truncated()
        }
        val content = when (val raw = message.opt("content")) {
            is String -> raw
            is JSONArray -> (0 until raw.length()).mapNotNull { i ->
                raw.optJSONObject(i)?.takeIf { it.optString("type") == "text" }?.opt("text") as? String
            }.joinToString("")
            null, JSONObject.NULL -> ""
            else -> throw TranslationError.Malformed("unexpected content")
        }
        return TranslationPrompt.clean(content).ifEmpty { throw TranslationError.Empty() }
    }

    /**
     * The models in a list that answer in text, sorted by name: those that
     * say their output includes text, or say nothing and are not named for
     * speech, images or embeddings. Each keeps its price when the list has
     * one, as OpenRouter's does.
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

    // OpenRouter gives dollars per token, as text; a negative one means the price varies.
    private fun price(item: JSONObject?): TextPrice? {
        val pricing = item?.optJSONObject("pricing") ?: return null
        fun perMillion(key: String) = pricing.optString(key).toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.times(1_000_000)
        return TextPrice(perMillion("prompt") ?: return null, perMillion("completion") ?: return null)
    }

    private fun writesText(item: JSONObject?, id: String): Boolean {
        val output = item?.optJSONArray("output_modalities")
            ?: item?.optJSONObject("architecture")?.optJSONArray("output_modalities")
        if (output != null) return (0 until output.length()).any { output.optString(it).equals("text", ignoreCase = true) }
        val lower = id.lowercase()
        return NOT_TEXT.none { it in lower }
    }

    private val NOT_TEXT = listOf(
        "tts", "speech", "whisper", "transcribe", "embed", "dall-e", "image", "moderation", "kokoro", "rerank",
    )

    fun errorFor(code: Int, body: String): TranslationError = when {
        code == 401 || code == 403 -> TranslationError.InvalidKey(code)
        code == 429 || code == 402 -> TranslationError.RateLimited(code)
        else -> TranslationError.Service(code)
    }
}
