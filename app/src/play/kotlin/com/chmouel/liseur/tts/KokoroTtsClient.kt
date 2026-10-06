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

object KokoroTts {
    /** The model a Kokoro server answers to; a hosted service names its own. */
    const val MODEL = "kokoro"

    /** DeepInfra's OpenAI-compatible API and its name for Kokoro, offered as a preset. */
    const val DEEPINFRA_URL = "https://api.deepinfra.com/v1/openai"
    const val DEEPINFRA_MODEL = "hexgrad/Kokoro-82M"

    /**
     * Kokoro-82M's own voices, offered when a service does not list its
     * voices, as DeepInfra does not.
     */
    val BUILT_IN_VOICES = listOf(
        "af_heart", "af_alloy", "af_aoede", "af_bella", "af_jessica", "af_kore", "af_nicole", "af_nova",
        "af_river", "af_sarah", "af_sky", "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam",
        "am_michael", "am_onyx", "am_puck", "am_santa", "bf_alice", "bf_emma", "bf_isabella", "bf_lily",
        "bm_daniel", "bm_fable", "bm_george", "bm_lewis", "ef_dora", "em_alex", "em_santa", "ff_siwis",
        "hf_alpha", "hf_beta", "hm_omega", "hm_psi", "if_sara", "im_nicola", "jf_alpha", "jf_gongitsune",
        "jf_nezumi", "jf_tebukuro", "jm_kumo", "pf_dora", "pm_alex", "pm_santa", "zf_xiaobei", "zf_xiaoni",
        "zf_xiaoxiao", "zf_xiaoyi", "zm_yunjian", "zm_yunxi", "zm_yunxia", "zm_yunyang",
    )

    /**
     * The OpenAI-style API root from what the reader typed, which the
     * `audio/...` paths are added to. A bare `host:port` is taken as http,
     * and an address with no `v1` in its path gets one, so a Kokoro
     * server's own address works as well as a hosted service's API root
     * such as DeepInfra's `/v1/openai`. Null when it is no http(s) address.
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

    /** The model to ask for: the one the reader set, else Kokoro's. */
    fun model(input: String?): String = input?.trim()?.takeIf { it.isNotEmpty() } ?: MODEL
}

/**
 * A Kokoro server's, or a hosted Kokoro's, OpenAI-style speech API, asked for raw PCM: 24 kHz mono
 * 16-bit little-endian, as Gemini's, so it plays through the same output.
 *
 * There is no logging interceptor on purpose: the request may carry a key
 * in a header and carries the book's text in the body.
 */
class KokoroTtsClient(private val client: OkHttpClient = default()) {

    /** Throws [SpeechError]; cancelling the caller cancels the request. */
    suspend fun synthesize(
        base: HttpUrl,
        apiKey: String?,
        text: String,
        voice: String,
        model: String = KokoroTts.MODEL,
    ): SpeechAudio {
        val body = JSONObject()
            .put("model", model)
            .put("input", text)
            .put("voice", voice)
            .put("response_format", "pcm")
            .toString()
        val request = request(base, "audio/speech", apiKey).post(body.toRequestBody(JSON)).build()
        return execute(request, ::speech)
    }

    /**
     * The voices the server offers, in its order, or Kokoro's own when it
     * has no voice list. Throws [SpeechError].
     */
    suspend fun voices(base: HttpUrl, apiKey: String?): List<String> =
        execute(request(base, "audio/voices", apiKey).get().build(), ::voiceList)

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
        if (source.request(SpeechAudio.MAX_PCM_BYTES + 1L)) throw SpeechError.InvalidResponse("audio too long")
        val pcm = source.buffer.readByteArray()
        when {
            pcm.isEmpty() -> throw SpeechError.InvalidResponse("empty audio")
            pcm.size % 2 != 0 -> throw SpeechError.InvalidResponse("odd byte count")
        }
        return SpeechAudio(pcm)
    }

    private fun voiceList(response: Response): List<String> {
        if (response.code == 404 || response.code == 405) return KokoroTts.BUILT_IN_VOICES
        if (!response.isSuccessful) throw errorFor(response)
        val text = boundedText(response, MAX_VOICES_BYTES) ?: throw SpeechError.InvalidResponse("response too large")
        val voices = try {
            val trimmed = text.trimStart()
            if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).optJSONArray("voices")
        } catch (_: JSONException) {
            null
        } ?: throw SpeechError.InvalidResponse("no voices")
        return (0 until voices.length()).mapNotNull { i ->
            voices.optJSONObject(i)?.let { it.optString("id").ifEmpty { it.optString("name") } }
                ?: voices.optString(i)
        }.filter { it.isNotBlank() }.distinct()
    }

    private fun errorFor(response: Response): SpeechError {
        val code = response.code
        return when {
            code == 401 || code == 403 -> SpeechError.InvalidKey(code)
            code == 429 -> SpeechError.RateLimited(code)
            (code == 400 || code == 404) && detail(response).contains("voice", ignoreCase = true) ->
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
        private val AUDIO_TYPES = setOf("audio/pcm", "audio/l16", "application/octet-stream")
        private const val MAX_ERROR_BYTES = 64 * 1024L
        private const val MAX_VOICES_BYTES = 1024 * 1024L
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
