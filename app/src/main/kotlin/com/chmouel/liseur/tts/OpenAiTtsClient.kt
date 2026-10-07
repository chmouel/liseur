package com.chmouel.liseur.tts

import com.chmouel.liseur.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A model a server lists: its [tags] when the list says what each model
 * does (DeepInfra tags speech models `tts`), and its [pricePerMillionChars]
 * in dollars when the list has one.
 */
data class SpeechModel(val id: String, val tags: List<String>? = null, val pricePerMillionChars: Double? = null)

object OpenAiTts {
    /**
     * OpenAI's own voices, offered for OpenAI, which has no voice list.
     * Any other name can still be typed.
     */
    val STANDARD_VOICES = listOf(
        "alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse",
    )

    private val SPEECH_MODEL_HINTS = listOf("tts", "speech", "kokoro")

    /**
     * The models of [all] that make speech, in the server's order: a
     * server's chat and transcription models cannot answer `audio/speech`.
     * A list that tags its models is taken at its word; otherwise the ones
     * whose names look like speech models, or every one when none does.
     */
    fun speechModels(all: List<SpeechModel>): List<SpeechModel> {
        if (all.any { it.tags != null }) return all.filter { m -> m.tags.orEmpty().any { it.equals("tts", ignoreCase = true) } }
        return all.filter { m -> SPEECH_MODEL_HINTS.any { m.id.contains(it, ignoreCase = true) } }.ifEmpty { all }
    }

    /** Whether [base] is OpenAI's own API, the one service whose voices are [STANDARD_VOICES]. */
    fun isOpenAi(base: HttpUrl): Boolean = base.host.equals(OPENAI_HOST, ignoreCase = true)

    /**
     * Where DeepInfra describes [model], voices included, when [base] is
     * DeepInfra's OpenAI root: its OpenAI API has no voice list. Null for
     * any other server, which is never sent this extra request, and for a
     * model id that cannot be a plain path.
     */
    fun modelDescription(base: HttpUrl, model: String): HttpUrl? {
        if (base.scheme != "https" || !base.host.equals(DEEPINFRA_HOST, ignoreCase = true)) return null
        if (base.pathSegments.filter { it.isNotEmpty() } != listOf("v1", "openai")) return null
        val parts = model.trim().split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return base.newBuilder()
            .encodedPath("/")
            .addPathSegment("models")
            .apply { parts.forEach { addPathSegment(it) } }
            .query(null)
            .fragment(null)
            .build()
    }

    private const val OPENAI_HOST = "api.openai.com"
    private const val DEEPINFRA_HOST = "api.deepinfra.com"

    /**
     * The OpenAI-style API root from what the reader typed, which the
     * `models` and `audio/...` paths are added to. A bare `host:port` is
     * taken as http, and an address with no `v1` in its path gets one, so a
     * self-hosted server's own address works as well as a hosted service's
     * API root, such as `/v1/openai`. Null when it is no http(s) address.
     */
    fun baseUrl(input: String): HttpUrl? {
        var text = input.trim()
        if (text.isEmpty()) return null
        if (!text.contains("://")) text = "http://$text"
        val url = text.toHttpUrlOrNull() ?: return null
        val segments = url.pathSegments.filter { it.isNotEmpty() }.toMutableList()
        if ("v1" !in segments) segments.add("v1")
        return url.newBuilder()
            .encodedPath("/")
            .apply { segments.forEach { addPathSegment(it) } }
            .query(null)
            .fragment(null)
            .build()
    }
}

/**
 * An OpenAI-compatible speech API (OpenAI's, a hosted service's, or a
 * self-hosted server such as Kokoro), asked for WAV, whose header says the
 * rate, and turned into the 24 kHz mono 16-bit PCM every speech service
 * hands the shared output. A reply without a WAV header is taken as that
 * PCM already, as some servers answer whatever was asked.
 *
 * There is no logging interceptor on purpose: the request may carry a key
 * in a header and carries the book's text in the body.
 */
class OpenAiTtsClient(private val client: OkHttpClient = default()) {

    /** Throws [SpeechError]; cancelling the caller cancels the request. */
    suspend fun synthesize(
        base: HttpUrl,
        apiKey: String?,
        text: String,
        voice: String,
        model: String,
    ): SpeechAudio {
        val body = JSONObject()
            .put("model", model)
            .put("input", text)
            .put("voice", voice)
            .put("response_format", "wav")
            .toString()
        val request = request(base, "audio/speech", apiKey).post(body.toRequestBody(JSON)).build()
        return execute(request, ::speech)
    }

    /**
     * The voices [model] has, in the server's order: the server's voice
     * list; when it has none, the ones DeepInfra describes for [model] (see
     * [OpenAiTts.modelDescription]), or OpenAI's own for OpenAI. Empty when
     * the server names none, so the voice is typed: nothing is guessed.
     * Throws [SpeechError].
     */
    suspend fun voices(base: HttpUrl, apiKey: String?, model: String?): List<String> {
        execute(request(base, "audio/voices", apiKey).get().build(), ::voiceList)?.let { return it }
        val description = model?.takeIf { it.isNotBlank() }?.let { OpenAiTts.modelDescription(base, it) }
        return when {
            // Public, so asked without the key.
            description != null -> execute(
                Request.Builder().url(description).header("User-Agent", USER_AGENT).get().build(),
                ::describedVoices,
            )
            OpenAiTts.isOpenAi(base) -> OpenAiTts.STANDARD_VOICES
            else -> emptyList()
        }
    }

    /**
     * Every model the server lists, in its order, or none when it has no
     * model list. Throws [SpeechError].
     */
    suspend fun models(base: HttpUrl, apiKey: String?): List<SpeechModel> =
        execute(request(base, "models", apiKey).get().build(), ::modelList)

    private fun request(base: HttpUrl, path: String, apiKey: String?): Request.Builder {
        val builder = Request.Builder()
            .url(base.newBuilder().addPathSegments(path).build())
            .header("User-Agent", USER_AGENT)
        if (apiKey.isNullOrBlank()) return builder
        try {
            builder.header("Authorization", "Bearer $apiKey")
        } catch (_: IllegalArgumentException) {
            // A pasted line break or non-ASCII character; OkHttp's message
            // would repeat the key, so it is not kept as the cause.
            throw SpeechError.InvalidKey()
        }
        return builder
    }

    private suspend fun <T> execute(request: Request, parse: (Response) -> T): T {
        val call = client.newCall(request)
        // A blocking execute() would carry on after its coroutine was
        // cancelled; an enqueued call is cancelled with it.
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(SpeechError.Network(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val outcome = try {
                            Result.success(response.use(parse))
                        } catch (e: SpeechError) {
                            Result.failure(e)
                        } catch (e: IOException) {
                            Result.failure(SpeechError.Network(e))
                        }
                        if (!continuation.isActive) return
                        outcome.fold(continuation::resume, continuation::resumeWithException)
                    }
                },
            )
        }
    }

    private fun speech(response: Response): SpeechAudio {
        if (!response.isSuccessful) throw errorFor(response)
        val type = response.body.contentType()
        if (type != null && "${type.type}/${type.subtype}".lowercase() !in AUDIO_TYPES) {
            throw SpeechError.InvalidResponse("unexpected format")
        }
        val source = response.body.source()
        if (source.request(MAX_AUDIO_BYTES + 1L)) throw SpeechError.InvalidResponse("audio too long")
        val body = source.buffer.readByteArray()
        if (WavPcm.isWav(body)) return SpeechAudio(WavPcm.toSpeechPcm(body))
        when {
            body.isEmpty() -> throw SpeechError.InvalidResponse("empty audio")
            body.size > SpeechAudio.MAX_PCM_BYTES -> throw SpeechError.InvalidResponse("audio too long")
            body.size % 2 != 0 -> throw SpeechError.InvalidResponse("odd byte count")
        }
        return SpeechAudio(body)
    }

    /** Null when the server has no voice list. */
    private fun voiceList(response: Response): List<String>? =
        if (response.code == 404 || response.code == 405) null else names(listItems(response, "voices"))

    private fun modelList(response: Response): List<SpeechModel> {
        if (response.code == 404 || response.code == 405) return emptyList()
        val items = listItems(response, "data")
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull items.optString(i).takeIf { it.isNotBlank() }?.let(::SpeechModel)
            val id = item.optString("id").ifEmpty { item.optString("name") }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val metadata = item.optJSONObject("metadata")
            val tags = metadata?.optJSONArray("tags")?.let { tags -> (0 until tags.length()).mapNotNull { tags.optString(it).takeIf(String::isNotBlank) } }
            val price = metadata?.optJSONObject("pricing")?.optDouble("input_characters")?.takeIf { it.isFinite() && it >= 0 }
            SpeechModel(id, tags, price)
        }.distinctBy { it.id }
    }

    /**
     * The voices in DeepInfra's description of a model: the values its
     * input schema allows for `voice` (or Kokoro's `preset_voice`), the
     * default first. None when the schema allows any text, as for a
     * cloned or described voice.
     */
    private fun describedVoices(response: Response): List<String> {
        if (!response.isSuccessful) throw errorFor(response)
        val text = boundedText(response, MAX_LIST_BYTES) ?: throw SpeechError.InvalidResponse("response too large")
        val schema = try {
            JSONObject(text).optJSONObject("in_schema")
        } catch (_: JSONException) {
            null
        } ?: throw SpeechError.InvalidResponse("no model description")
        val properties = schema.optJSONObject("properties") ?: return emptyList()
        val field = properties.optJSONObject("voice") ?: properties.optJSONObject("preset_voice") ?: return emptyList()
        val voices = SchemaEnum.values(schema, field)?.filter { it.isNotBlank() }?.distinct() ?: return emptyList()
        val default = when (val d = field.opt("default")) {
            is String -> d
            is JSONArray -> d.optString(0)
            else -> null
        }
        return if (default != null && default in voices) listOf(default) + (voices - default) else voices
    }

    /**
     * The list in a response: a bare array, or one under [field]. Throws
     * [SpeechError].
     */
    private fun listItems(response: Response, field: String): JSONArray {
        if (!response.isSuccessful) throw errorFor(response)
        val text = boundedText(response, MAX_LIST_BYTES) ?: throw SpeechError.InvalidResponse("response too large")
        return try {
            val trimmed = text.trimStart()
            if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).optJSONArray(field)
        } catch (_: JSONException) {
            null
        } ?: throw SpeechError.InvalidResponse("no list")
    }

    /** The names in [items]: strings, or objects with an `id` or `name`. */
    private fun names(items: JSONArray): List<String> =
        (0 until items.length()).mapNotNull { i ->
            items.optJSONObject(i)?.let { it.optString("id").ifEmpty { it.optString("name") } }
                ?: items.optString(i)
        }.filter { it.isNotBlank() }.distinct()

    private fun errorFor(response: Response): SpeechError {
        val code = response.code
        return when {
            code == 401 || code == 403 -> SpeechError.InvalidKey(code)
            code == 429 -> SpeechError.RateLimited(code)
            (code == 400 || code == 404) && detail(response).contains("voice", ignoreCase = true) ->
                SpeechError.InvalidVoice(code)
            // DeepInfra's Qwen3 and Higgs answer an unknown voice with a 500.
            code == 500 && detail(response).let { it.contains("voice", true) && it.contains("not found", true) } ->
                SpeechError.InvalidVoice(code)
            else -> SpeechError.Service(code)
        }
    }

    /** The FastAPI `detail` of an error, when it is a plain message. */
    private fun detail(response: Response): String {
        val text = try {
            boundedText(response, MAX_ERROR_BYTES)
        } catch (_: IOException) {
            null
        } ?: return ""
        return try {
            JSONObject(text).optString("detail")
        } catch (_: JSONException) {
            ""
        }
    }

    private fun boundedText(response: Response, limit: Long): String? {
        val source = response.body.source()
        if (source.request(limit + 1)) return null
        return source.buffer.readUtf8()
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val AUDIO_TYPES =
            setOf("audio/wav", "audio/x-wav", "audio/wave", "audio/pcm", "audio/l16", "application/octet-stream")

        /** A reply's bytes: the longest PCM, with room for a WAV header. */
        private const val MAX_AUDIO_BYTES = SpeechAudio.MAX_PCM_BYTES + 64 * 1024L
        private const val MAX_ERROR_BYTES = 64 * 1024L
        private const val MAX_LIST_BYTES = 1024 * 1024L
        private val USER_AGENT =
            "Liseur/${BuildConfig.VERSION_NAME} (+https://github.com/chmouel/liseur)"

        // A small server, a Raspberry Pi say, can take longer than the
        // sentence lasts to make it.
        fun default(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            // A redirect could carry the key header and the book text elsewhere.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
