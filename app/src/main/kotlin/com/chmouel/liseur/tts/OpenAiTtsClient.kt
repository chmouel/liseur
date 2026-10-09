package com.chmouel.liseur.tts

import com.chmouel.liseur.BuildConfig
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
 * A model a server lists. [speech] says whether it makes speech, null when
 * the list does not say; [pricePerMillionChars] is in dollars when the
 * list has a price per character; [voices] are the ones the list names for
 * it (OpenRouter's `supported_voices`), null when it names none.
 */
data class SpeechModel(
    val id: String,
    val speech: Boolean? = null,
    val pricePerMillionChars: Double? = null,
    val voices: List<String>? = null,
)

object OpenAiTts {
    /**
     * OpenAI's own voices for [model], offered for OpenAI, which has no
     * voice list: `tts-1` and `tts-1-hd` take fewer than its newer models,
     * whose list starts with the two OpenAI recommends. Any other name can
     * still be typed.
     */
    fun openAiVoices(model: String?): List<String> =
        if (model?.trim()?.startsWith(LEGACY_OPENAI_MODEL) == true) LEGACY_OPENAI_VOICES else OPENAI_VOICES

    private const val LEGACY_OPENAI_MODEL = "tts-1"
    private val LEGACY_OPENAI_VOICES = listOf(
        "alloy", "ash", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer",
    )
    private val OPENAI_VOICES = listOf(
        "marin", "cedar", "alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse",
    )

    private val SPEECH_MODEL_HINTS = listOf("tts", "speech", "kokoro")

    /**
     * The models of [all] that make speech, in the server's order: a
     * server's chat and transcription models cannot answer `audio/speech`.
     * A list that says what its models do is taken at its word; otherwise
     * the ones whose names look like speech models, or every one when none
     * does.
     */
    fun speechModels(all: List<SpeechModel>): List<SpeechModel> {
        if (all.any { it.speech != null }) return all.filter { it.speech == true }
        return all.filter { m -> SPEECH_MODEL_HINTS.any { m.id.contains(it, ignoreCase = true) } }.ifEmpty { all }
    }

    /** Whether [base] is OpenAI's own API, the one service whose voices are [openAiVoices]. */
    fun isOpenAi(base: HttpUrl): Boolean = base.host.equals(OPENAI_HOST, ignoreCase = true)

    /**
     * OpenAI's [models] with `gpt-4o-mini-tts` first, the one that takes
     * every voice, so a new OpenAI server starts on it; the rest keep the
     * server's order.
     */
    fun openAiModels(models: List<SpeechModel>): List<SpeechModel> = models.sortedBy { it.id != OPENAI_MODEL }

    private const val OPENAI_MODEL = "gpt-4o-mini-tts"

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

    /** Whether [base] is Groq's API, which bills speech per character and has no voice list. */
    fun isGroq(base: HttpUrl): Boolean = base.scheme == "https" && base.host.equals(GROQ_HOST, ignoreCase = true)

    /**
     * The voices Groq's speech models take, as its documentation lists them
     * and as each was heard to answer: Groq has no voice list. Null for
     * another server or a model not listed here.
     */
    fun groqVoices(base: HttpUrl, model: String): List<String>? = if (isGroq(base)) GROQ_VOICES[model.trim()] else null

    private val GROQ_VOICES = mapOf(
        "canopylabs/orpheus-v1-english" to listOf("autumn", "diana", "hannah", "austin", "daniel", "troy"),
        "canopylabs/orpheus-arabic-saudi" to listOf("abdullah", "fahad", "sultan", "lulwa", "noura", "aisha"),
    )

    private const val OPENAI_HOST = "api.openai.com"
    private const val DEEPINFRA_HOST = "api.deepinfra.com"
    private const val GROQ_HOST = "api.groq.com"

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
class OpenAiTtsClient(
    private val client: OkHttpClient = default(),
    private val decodeMp3: suspend (ByteArray) -> ByteArray = Mp3Pcm::toSpeechPcm,
) {
    /** Models, per server, that refused WAV and answered MP3 instead (OpenRouter's). */
    private val mp3Models: MutableSet<Pair<HttpUrl, String>> = ConcurrentHashMap.newKeySet()

    /**
     * Throws [SpeechError]; cancelling the caller cancels the request. WAV
     * is asked first; a server that refuses the format is asked for MP3,
     * which is remembered for that model once it worked.
     */
    suspend fun synthesize(
        base: HttpUrl,
        apiKey: String?,
        text: String,
        voice: String,
        model: String,
    ): SpeechAudio {
        val key = base to model
        if (key in mp3Models) return decode(speak(base, apiKey, text, voice, model, MP3).orThrow(), MP3)
        return when (val reply = speak(base, apiKey, text, voice, model, WAV)) {
            is Reply.Audio -> decode(reply, WAV)
            is Reply.Failure -> {
                if (reply.code != 400 && reply.code != 422 || !reply.error.text.contains("response_format", ignoreCase = true)) {
                    throw errorFor(reply.code, reply.error)
                }
                decode(speak(base, apiKey, text, voice, model, MP3).orThrow(), MP3).also { mp3Models += key }
            }
        }
    }

    private suspend fun speak(
        base: HttpUrl,
        apiKey: String?,
        text: String,
        voice: String,
        model: String,
        format: String,
    ): Reply {
        val body = JSONObject()
            .put("model", model)
            .put("input", text)
            .put("voice", voice)
            .put("response_format", format)
            .toString()
        val request = request(base, "audio/speech", apiKey).post(body.toRequestBody(JSON)).build()
        return execute(request, ::speech)
    }

    /**
     * The voices [model] has, in the server's order: the server's voice
     * list; when it has none, the ones DeepInfra describes for [model] (see
     * [OpenAiTts.modelDescription]), OpenAI's own for OpenAI, Groq's (see
     * [OpenAiTts.groqVoices]), or the ones the server's model list names
     * for it (OpenRouter's). Empty when the
     * server names none, so the voice is typed: nothing is guessed.
     * Throws [SpeechError].
     */
    suspend fun voices(base: HttpUrl, apiKey: String?, model: String?): List<String> {
        voiceList(base, apiKey)?.let { return it }
        val named = model?.takeIf { it.isNotBlank() }
        val description = named?.let { OpenAiTts.modelDescription(base, it) }
        return when {
            // Public, so asked without the key.
            description != null -> execute(
                Request.Builder().url(description).header("User-Agent", USER_AGENT).get().build(),
                ::describedVoices,
            )
            OpenAiTts.isOpenAi(base) -> OpenAiTts.openAiVoices(named)
            OpenAiTts.isGroq(base) -> named?.let { OpenAiTts.groqVoices(base, it) }.orEmpty()
            named != null -> models(base, apiKey).firstOrNull { it.id == named }?.voices.orEmpty()
            else -> emptyList()
        }
    }

    /**
     * Every model the server lists, in its order, or none when it has no
     * model list. Speech models are asked for, as OpenRouter lists them
     * only then; other servers ignore the question, and one that refuses
     * it is asked again without. Throws [SpeechError].
     */
    suspend fun models(base: HttpUrl, apiKey: String?): List<SpeechModel> {
        val speech = base.newBuilder().addPathSegment("models").addQueryParameter("output_modalities", "speech").build()
        return try {
            execute(request(speech, apiKey).get().build()) { modelList(it, base) }
        } catch (e: SpeechError.Service) {
            if (e.code != 400 && e.code != 422) throw e
            execute(request(base, "models", apiKey).get().build()) { modelList(it, base) }
        }
    }

    /**
     * Every model the server lists, whatever it outputs, in its order: a
     * check that the server answers and takes the key. Throws [SpeechError].
     */
    suspend fun allModels(base: HttpUrl, apiKey: String?): List<SpeechModel> =
        execute(request(base, "models", apiKey).get().build()) { modelList(it, base) }

    /**
     * The server's own voice list, every page of it, or null when it has
     * none. A list that says its total is read to the end (Mistral's comes
     * ten at a time); one that stops short is an error, never taken as
     * complete.
     */
    private suspend fun voiceList(base: HttpUrl, apiKey: String?): List<String>? {
        val first = execute(request(base, "audio/voices", apiKey).get().build(), ::voicePage) ?: return null
        val total = first.total ?: return first.names
        val names = LinkedHashSet(first.names)
        var read = first.items
        var pages = 1
        while (read < total) {
            if (pages >= MAX_VOICE_PAGES) throw SpeechError.InvalidResponse("voice list too long")
            val url = base.newBuilder().addPathSegments("audio/voices").addQueryParameter("offset", read.toString()).build()
            val page = execute(request(url, apiKey).get().build(), ::voicePage)
            if (page == null || page.items == 0) throw SpeechError.InvalidResponse("voice list cut short")
            names += page.names
            read += page.items
            pages++
        }
        return names.toList()
    }

    /** Turns a reply into speech, off the network thread so it can be cancelled. */
    private suspend fun decode(reply: Reply.Audio, asked: String): SpeechAudio = withContext(Dispatchers.Default) {
        try {
            val wrapped = reply.type == "application/json"
            val bytes = if (wrapped) audioData(reply.body) else reply.body
            when {
                WavPcm.isWav(bytes) -> SpeechAudio(WavPcm.toSpeechPcm(bytes))
                reply.type in MP3_TYPES || asked == MP3 -> SpeechAudio(decodeMp3(bytes))
                wrapped -> throw SpeechError.InvalidResponse("unexpected format")
                else -> SpeechAudio(rawPcm(bytes))
            }
        } catch (e: SpeechError) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The platform's words are not shown: they may quote the reply.
            throw SpeechError.InvalidResponse("undecodable audio")
        }
    }

    /** The audio in a JSON reply, as Mistral sends it: base64 under `audio_data`. */
    private fun audioData(body: ByteArray): ByteArray {
        val data = try {
            JSONObject(String(body, Charsets.UTF_8)).opt("audio_data") as? String
        } catch (_: JSONException) {
            null
        } ?: throw SpeechError.InvalidResponse("unexpected format")
        val audio = try {
            Base64.getDecoder().decode(data)
        } catch (_: IllegalArgumentException) {
            throw SpeechError.InvalidResponse("bad audio encoding")
        }
        if (audio.size > MAX_AUDIO_BYTES) throw SpeechError.InvalidResponse("audio too long")
        return audio
    }

    /** A reply without a header: 24 kHz mono 16-bit PCM already (DeepInfra's Higgs). */
    private fun rawPcm(body: ByteArray): ByteArray {
        when {
            body.isEmpty() -> throw SpeechError.InvalidResponse("empty audio")
            body.size > SpeechAudio.MAX_PCM_BYTES -> throw SpeechError.InvalidResponse("audio too long")
            body.size % 2 != 0 -> throw SpeechError.InvalidResponse("odd byte count")
        }
        return body
    }

    private fun request(base: HttpUrl, path: String, apiKey: String?): Request.Builder =
        request(base.newBuilder().addPathSegments(path).build(), apiKey)

    private fun request(url: HttpUrl, apiKey: String?): Request.Builder {
        val builder = Request.Builder()
            .url(url)
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

    private fun speech(response: Response): Reply {
        if (!response.isSuccessful) return Reply.Failure(response.code, errorBody(response))
        val type = response.body.contentType()?.let { "${it.type}/${it.subtype}".lowercase() }
        if (type != null && type !in AUDIO_TYPES && type != "application/json") {
            throw SpeechError.InvalidResponse("unexpected format")
        }
        val limit = if (type == "application/json") MAX_ENVELOPE_BYTES else MAX_AUDIO_BYTES
        val source = response.body.source()
        if (source.request(limit + 1L)) throw SpeechError.InvalidResponse("audio too long")
        return Reply.Audio(source.buffer.readByteArray(), type)
    }

    /** What the server answered to a speech request, read but not yet decoded. */
    private sealed interface Reply {
        class Audio(val body: ByteArray, val type: String?) : Reply
        class Failure(val code: Int, val error: ErrorBody) : Reply

        fun orThrow(): Audio = when (this) {
            is Audio -> this
            is Failure -> throw errorFor(code, error)
        }
    }

    private class VoicePage(val names: List<String>, val items: Int, val total: Int?)

    /** Null when the server has no voice list. */
    private fun voicePage(response: Response): VoicePage? {
        if (response.code == 404 || response.code == 405) return null
        if (!response.isSuccessful) throw errorFor(response)
        val text = boundedText(response, MAX_LIST_BYTES) ?: throw SpeechError.InvalidResponse("response too large")
        val trimmed = text.trimStart()
        return try {
            if (trimmed.startsWith("[")) {
                JSONArray(trimmed).let { VoicePage(names(it), it.length(), null) }
            } else {
                val reply = JSONObject(trimmed)
                val paged = reply.optJSONArray("items")
                val items = reply.optJSONArray("voices") ?: paged ?: throw SpeechError.InvalidResponse("no list")
                val total = (reply.opt("total") as? Int)?.takeIf { paged != null && items === paged && it >= 0 }
                VoicePage(names(items), items.length(), total)
            }
        } catch (_: JSONException) {
            throw SpeechError.InvalidResponse("no list")
        }
    }

    private fun modelList(response: Response, base: HttpUrl): List<SpeechModel> {
        if (response.code == 404 || response.code == 405) return emptyList()
        val items = listItems(response, "data")
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull items.optString(i).takeIf { it.isNotBlank() }?.let(::SpeechModel)
            val id = item.optString("id").ifEmpty { item.optString("name") }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val voices = item.optJSONArray("supported_voices")?.let(::strings)?.distinct()
            SpeechModel(id, makesSpeech(item), price(item, OpenAiTts.isGroq(base)), voices)
        }.distinctBy { it.id }.let { if (OpenAiTts.isOpenAi(base)) OpenAiTts.openAiModels(it) else it }
    }

    /**
     * Whether a listed model makes speech, from the first thing its entry
     * says: its output (Groq, OpenRouter), its capabilities (Mistral), or
     * its tags (DeepInfra). Null when it says none of these.
     */
    private fun makesSpeech(item: JSONObject): Boolean? {
        val output = item.optJSONArray("output_modalities")
            ?: item.optJSONObject("architecture")?.optJSONArray("output_modalities")
        if (output != null) return strings(output).any { it.equals("speech", ignoreCase = true) }
        val capability = item.optJSONObject("capabilities")?.opt("audio_speech")
        if (capability is Boolean) return capability
        val tags = item.optJSONObject("metadata")?.optJSONArray("tags") ?: return null
        return strings(tags).any { it.equals("tts", ignoreCase = true) }
    }

    /**
     * The price per million characters: DeepInfra's, or OpenRouter's
     * prompt price when that is all it bills (its per-character models).
     * Groq's speech models bill the prompt per character and list no
     * completion price; elsewhere a missing one could hide token billing.
     */
    private fun price(item: JSONObject, groq: Boolean): Double? {
        item.optJSONObject("metadata")?.optJSONObject("pricing")?.optDouble("input_characters")
            ?.takeIf { it.isFinite() && it >= 0 }?.let { return it }
        val pricing = item.optJSONObject("pricing") ?: return null
        val perCharacter = pricing.optString("completion").toDoubleOrNull() == 0.0 || groq && !pricing.has("completion")
        if (!perCharacter) return null
        return pricing.optString("prompt").toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.times(1_000_000)
    }

    private fun strings(array: JSONArray): List<String> =
        (0 until array.length()).mapNotNull { (array.opt(it) as? String)?.takeIf(String::isNotBlank) }

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

    /** The names in [items]: strings, or objects with a `slug` (Mistral's), `id` or `name`. */
    private fun names(items: JSONArray): List<String> =
        (0 until items.length()).mapNotNull { i ->
            items.optJSONObject(i)?.let { o -> NAME_FIELDS.firstNotNullOfOrNull { (o.opt(it) as? String)?.takeIf(String::isNotBlank) } }
                ?: (items.opt(i) as? String)
        }.filter { it.isNotBlank() }.distinct()

    private fun errorFor(response: Response): SpeechError = errorFor(response.code, errorBody(response))

    /**
     * What an error reply says, to recognise it, never to show it: its
     * message (FastAPI's `detail`, a top-level `message` as Mistral's, or
     * OpenAI's `error.message`; else the reply as it is), and OpenAI's
     * `error.code` when it has one.
     */
    private class ErrorBody(val text: String, val code: String? = null)

    private fun errorBody(response: Response): ErrorBody {
        val text = try {
            boundedText(response, MAX_ERROR_BYTES)
        } catch (_: IOException) {
            null
        } ?: return ErrorBody("")
        val reply = try {
            JSONObject(text)
        } catch (_: JSONException) {
            return ErrorBody(text)
        }
        val error = reply.optJSONObject("error")
        val message = reply.optString("detail").ifEmpty { reply.opt("message") as? String ?: "" }
            .ifEmpty { error?.opt("message") as? String ?: "" }
            .ifEmpty { text }
        return ErrorBody(message, error?.opt("code") as? String)
    }

    private fun boundedText(response: Response, limit: Long): String? {
        val source = response.body.source()
        if (source.request(limit + 1)) return null
        return source.buffer.readUtf8()
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val AUDIO_TYPES = setOf(
            "audio/wav", "audio/x-wav", "audio/wave", "audio/pcm", "audio/l16", "application/octet-stream",
            "audio/mpeg", "audio/mp3",
        )
        private val MP3_TYPES = setOf("audio/mpeg", "audio/mp3")
        private const val WAV = "wav"
        private const val MP3 = "mp3"
        private val NAME_FIELDS = listOf("slug", "id", "name")
        private const val MAX_VOICE_PAGES = 20

        /** A reply's bytes: the longest PCM, with room for a WAV header. */
        private const val MAX_AUDIO_BYTES = SpeechAudio.MAX_PCM_BYTES + 64 * 1024L

        /** That audio in base64 inside JSON, with room for the rest of the object. */
        private const val MAX_ENVELOPE_BYTES = (MAX_AUDIO_BYTES + 2) / 3 * 4 + 64 * 1024L
        private const val MAX_ERROR_BYTES = 64 * 1024L
        private const val MAX_LIST_BYTES = 1024 * 1024L
        private val USER_AGENT =
            "Liseur/${BuildConfig.VERSION_NAME} (+https://github.com/chmouel/liseur)"

        private fun errorFor(code: Int, error: ErrorBody): SpeechError = errorFor(code, error.text, error.code)

        private fun errorFor(code: Int, text: String, kind: String?): SpeechError = when {
            code == 401 || code == 403 -> SpeechError.InvalidKey(code)
            code == 429 -> SpeechError.RateLimited(code)
            // Groq's, until the model's terms are accepted in its console.
            kind == "model_terms_required" -> SpeechError.TermsRequired(code)
            (code == 400 || code == 404) && text.contains("voice", ignoreCase = true) -> SpeechError.InvalidVoice(code)
            // DeepInfra's Qwen3 and Higgs answer an unknown voice with a 500.
            code == 500 && text.contains("voice", true) && text.contains("not found", true) -> SpeechError.InvalidVoice(code)
            else -> SpeechError.Service(code)
        }

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
