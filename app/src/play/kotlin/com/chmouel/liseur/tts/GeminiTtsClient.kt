package com.chmouel.liseur.tts

import com.chmouel.liseur.BuildConfig
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
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

object GeminiTts {
    /** Fast and cheap, which suits a whole book read aloud. */
    const val DEFAULT_MODEL = "gemini-3.8-flash-lite-tts"
    const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
    const val MODELS_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"

    /** The model a stored name stands for; none stored is the default. */
    fun modelOf(stored: String?): String = stored?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MODEL

    /** The speech models among the ids the API lists, in its order. */
    fun speechModels(ids: List<String>): List<String> =
        ids.map { it.removePrefix("models/") }.filter { it.contains("tts", ignoreCase = true) }.distinct()
}

/**
 * The Gemini Interactions API, asked for raw PCM.
 *
 * There is no logging interceptor on purpose: the request carries the key
 * in a header and the book's text in the body, and neither belongs in a log.
 */
class GeminiTtsClient(
    private val client: OkHttpClient = default(),
    private val endpoint: HttpUrl = GeminiTts.ENDPOINT.toHttpUrl(),
    private val modelsEndpoint: HttpUrl = GeminiTts.MODELS_ENDPOINT.toHttpUrl(),
) {

    /** Throws [SpeechError]; cancelling the caller cancels the request. */
    suspend fun synthesize(
        apiKey: String,
        text: String,
        voice: String,
        model: String = GeminiTts.DEFAULT_MODEL,
    ): SpeechAudio {
        val request = request(endpoint, apiKey)
            .post(requestBody(text, voice, model).toRequestBody(JSON))
            .build()
        return execute(request, ::parse)
    }

    /**
     * The speech models the key can use, by id, in the API's order.
     * Throws [SpeechError].
     */
    suspend fun models(apiKey: String): List<String> {
        val url = modelsEndpoint.newBuilder().addQueryParameter("pageSize", MODELS_PAGE.toString()).build()
        return execute(request(url, apiKey).get().build(), ::modelList)
    }

    private fun request(url: HttpUrl, apiKey: String): Request.Builder {
        val builder = Request.Builder().url(url)
        try {
            builder.header("x-goog-api-key", apiKey)
        } catch (_: IllegalArgumentException) {
            // A pasted line break or non-ASCII character; OkHttp's message
            // would repeat the key, so it is not kept as the cause.
            throw SpeechError.InvalidKey()
        }
        return builder.header("User-Agent", USER_AGENT)
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

    private fun modelList(response: Response): List<String> {
        val source = response.body.source()
        if (source.request(MODELS_BYTES + 1)) {
            if (!response.isSuccessful) throw errorFor(response.code, "")
            throw SpeechError.InvalidResponse("response too large")
        }
        val text = source.buffer.readUtf8()
        if (!response.isSuccessful) throw errorFor(response.code, text)
        val models = try {
            JSONObject(text).optJSONArray("models")
        } catch (_: JSONException) {
            throw SpeechError.InvalidResponse("not JSON")
        } ?: return emptyList()
        return GeminiTts.speechModels((0 until models.length()).mapNotNull { models.optJSONObject(it)?.optString("name") })
    }

    private fun requestBody(text: String, voice: String, model: String): String = JSONObject()
        .put("model", model)
        // The API keeps interactions unless told not to. This does not
        // change Google's general retention terms; PRIVACY.md says so.
        .put("store", false)
        .put("input", text)
        .put(
            "response_format",
            JSONObject()
                .put("type", "audio")
                .put("mime_type", "audio/l16")
                .put("sample_rate", SpeechAudio.SAMPLE_RATE),
        )
        .put(
            "generation_config",
            JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", voice))),
        )
        .toString()

    private fun parse(response: Response): SpeechAudio {
        val source = response.body.source()
        // Base64 is four bytes for every three, plus room for the envelope.
        val limit = SpeechAudio.MAX_PCM_BYTES.toLong() / 3 * 4 + ENVELOPE_BYTES
        if (source.request(limit + 1)) {
            if (!response.isSuccessful) throw errorFor(response.code, "")
            throw SpeechError.InvalidResponse("response too large")
        }
        val text = source.buffer.readUtf8()
        if (!response.isSuccessful) throw errorFor(response.code, text)
        return audioFrom(text)
    }

    private fun audioFrom(text: String): SpeechAudio {
        val root = try {
            JSONObject(text)
        } catch (_: JSONException) {
            throw SpeechError.InvalidResponse("not JSON")
        }
        val steps = root.optJSONArray("steps") ?: throw SpeechError.InvalidResponse("no steps")
        var audio: JSONObject? = null
        for (i in 0 until steps.length()) {
            val content = steps.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val item = content.optJSONObject(j) ?: continue
                if (item.optString("type") == "audio") audio = item
            }
        }
        if (audio == null) throw SpeechError.InvalidResponse("no audio")
        if (!audio.optString("mime_type").lowercase().startsWith("audio/l16")) {
            throw SpeechError.InvalidResponse("unexpected format")
        }
        val rate = audio.optInt("sample_rate", SpeechAudio.SAMPLE_RATE)
        val channels = audio.optInt("channels", 1)
        if (rate != SpeechAudio.SAMPLE_RATE || channels != 1) {
            throw SpeechError.InvalidResponse("unexpected sample rate or channels")
        }
        // Labelled L16, which RFC 2586 makes big-endian, but what arrives
        // is little-endian: the byte order AudioTrack wants as it is.
        val pcm = try {
            Base64.getDecoder().decode(audio.optString("data"))
        } catch (_: IllegalArgumentException) {
            throw SpeechError.InvalidResponse("bad base64")
        }
        when {
            pcm.isEmpty() -> throw SpeechError.InvalidResponse("empty audio")
            pcm.size % 2 != 0 -> throw SpeechError.InvalidResponse("odd byte count")
            pcm.size > SpeechAudio.MAX_PCM_BYTES -> throw SpeechError.InvalidResponse("audio too long")
        }
        return SpeechAudio(pcm)
    }

    private fun errorFor(code: Int, body: String): SpeechError {
        val (status, reasons) = describe(body)
        return when {
            code == 429 || status == "RESOURCE_EXHAUSTED" -> SpeechError.RateLimited(code)
            code == 401 || code == 403 || status == "UNAUTHENTICATED" ||
                reasons.any { it.startsWith("API_KEY_") } -> SpeechError.InvalidKey(code)
            else -> SpeechError.Service(code)
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
        val reasons = (0 until (details?.length() ?: 0)).mapNotNull {
            details?.optJSONObject(it)?.optString("reason")
        }
        return error.optString("status").takeIf { it.isNotEmpty() } to reasons
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val ENVELOPE_BYTES = 64 * 1024L
        private const val MODELS_BYTES = 1024 * 1024L
        private const val MODELS_PAGE = 1000
        private val USER_AGENT =
            "Liseur/${BuildConfig.VERSION_NAME} (+https://github.com/chmouel/liseur)"

        fun default(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            // A redirect could carry the key header and the book text elsewhere.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
