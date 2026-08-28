package com.chmouel.liseur.data.kosync

import com.chmouel.liseur.data.remote.RemoteHttp
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.RemoteResult
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.failureForCode
import com.chmouel.liseur.data.remote.remoteCall
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/**
 * How a kosync request proves who it is.
 *
 * The protocol's `x-auth-key` is the hex MD5 of the password, as
 * KOReader derives it. Only the derived key ever exists here: the
 * password is hashed the moment it is typed and never stored, so what
 * is at risk in the database is a credential for this one protocol and
 * not something the reader may have reused.
 */
data class KosyncCredentials(val username: String, val key: String) {
    companion object {
        /** KOReader's derivation: the auth key is the hex MD5 of the password. */
        fun keyFor(password: String): String {
            val digest = MessageDigest.getInstance("MD5").digest(password.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

/** Where the other side last was, as the kosync protocol says it. */
data class KosyncProgress(
    /** How far through the book, 0..1. */
    val percentage: Double,
    /** The device that reported it, when the server says. */
    val device: String?,
    val deviceId: String?,
    /** The server's own timestamp, in epoch milliseconds. Display only. */
    val timestamp: Long?,
)

/**
 * KOReader's kosync protocol: three endpoints under a mount root.
 *
 * The root differs per server — Grimmory serves it at `/api/koreader`,
 * liseur-sync at `/adapter/kosync`, a stock kosync server at `/` — so
 * the stored URL is the mount root and the endpoints are appended here.
 *
 * Positions travel as a percentage plus KOReader's own `progress`
 * string. This client reads and writes only the percentage: `progress`
 * is an engine-specific position (a CRe xpointer, a page number) that
 * means nothing to Readium, so on the way out it carries the percentage
 * as a bare string — the shape KOReader itself accepts for engines
 * without an xpointer, and the one liseur-sync answers with.
 *
 * Blocking I/O moves to [Dispatchers.IO] here, not in callers: the
 * suspend signature is a promise that the thread is safe.
 *
 * Redirects are never followed. The shared client follows them because
 * calibre-web 308-redirects sloppy download paths, and OkHttp protects
 * that case by stripping `Authorization` when a redirect changes host —
 * but kosync signs with custom `x-auth-*` headers, which OkHttp would
 * forward wholesale, and a register body even carries the raw password.
 * No kosync endpoint legitimately redirects (the mount root is typed by
 * the reader), so a 3xx reads as the wrong address, not a hop to take.
 */
class KosyncClient(
    private val http: RemoteHttp = RemoteHttp(
        RemoteHttp.default().newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build(),
    ),
) {

    /** Whether the server accepts these credentials. */
    suspend fun authorize(
        rootUrl: String,
        credentials: KosyncCredentials,
    ): RemoteResult<Unit> = withContext(Dispatchers.IO) {
        remoteCall {
            call(request(rootUrl, "users/auth", credentials).get().build()).use { response ->
                if (!response.isSuccessful) throw RemoteHttpFailure(failureFor(response.code))
            }
        }
    }

    /**
     * Registers this credential with the server, for servers that take
     * one — liseur-sync redeems a pairing code this way; Grimmory
     * forbids the route outright.
     *
     * The body carries the password as typed, per the protocol; only
     * the derived key is ever kept afterwards.
     */
    suspend fun register(
        rootUrl: String,
        username: String,
        password: String,
    ): RemoteResult<Unit> = withContext(Dispatchers.IO) {
        remoteCall {
            val body = JSONObject()
                .put("username", username)
                .put("password", password)
                .toString()
                .toRequestBody(JSON)
            val request = Request.Builder()
                .url(url(rootUrl, "users/create"))
                .post(body)
                .build()
            call(request).use { response ->
                if (!response.isSuccessful) throw RemoteHttpFailure(failureFor(response.code))
            }
        }
    }

    /**
     * Where the server thinks the reader is in one document, or null
     * when it has never heard of it — kosync says that with an empty
     * 200 body rather than a 404.
     */
    suspend fun getProgress(
        rootUrl: String,
        credentials: KosyncCredentials,
        document: String,
    ): RemoteResult<KosyncProgress?> = withContext(Dispatchers.IO) {
        remoteCall {
            val request = request(rootUrl, "syncs/progress/${document(document)}", credentials)
                .get()
                .build()
            call(request).use { response ->
                if (!response.isSuccessful) throw RemoteHttpFailure(failureFor(response.code))
                parseProgress(JSONObject(response.body?.string().orEmpty().ifBlank { "{}" }))
            }
        }
    }

    /** Sends where the reader is in one document. */
    suspend fun putProgress(
        rootUrl: String,
        credentials: KosyncCredentials,
        document: String,
        percentage: Double,
        device: String,
        deviceId: String,
        /** From stored state, in epoch milliseconds; the wire wants seconds. */
        timestampMs: Long,
    ): RemoteResult<Unit> = withContext(Dispatchers.IO) {
        remoteCall {
            document(document)
            val clamped = percentage.coerceIn(0.0, 1.0)
            val body = JSONObject()
                .put("document", document)
                // The percentage said twice: once as the number the
                // protocol reads, once as the string KOReader shows a
                // reader whose engine has no anchor of its own.
                .put("percentage", clamped)
                .put("progress", clamped.toString())
                .put("device", device)
                .put("device_id", deviceId)
                .put("timestamp", timestampMs / 1000)
                .toString()
                .toRequestBody(JSON)
            val request = request(rootUrl, "syncs/progress", credentials).put(body).build()
            call(request).use { response ->
                if (!response.isSuccessful) throw RemoteHttpFailure(failureFor(response.code))
            }
        }
    }

    private fun call(request: Request): Response = http.client.newCall(request).execute()

    /**
     * What an unsuccessful answer means here. On top of the shared
     * mapping: with redirects disabled a 3xx arrives as the answer
     * itself, and since no kosync endpoint redirects, it means the
     * mount root is not a kosync server — malformed, not retryable.
     */
    private fun failureFor(code: Int): SyncFailure =
        if (code in 300..399) SyncFailure.Malformed else failureForCode(code)

    private fun request(
        rootUrl: String,
        path: String,
        credentials: KosyncCredentials,
    ): Request.Builder = Request.Builder()
        .url(url(rootUrl, path))
        .header("x-auth-user", credentials.username)
        .header("x-auth-key", credentials.key)

    private fun url(rootUrl: String, path: String): String =
        rootUrl.trimEnd('/') + "/" + path

    /**
     * A document name fit to put in a URL path.
     *
     * It is a hex hash and nothing else. The check is what stands
     * between an id that is carried and an id that is *addressed*: a
     * value like `..` would be resolved by the URL before the server
     * ever saw it, so anything but hex is refused rather than sent.
     */
    private fun document(value: String): String {
        if (!DOCUMENT.matches(value)) throw RemoteHttpFailure(SyncFailure.Malformed)
        return value
    }

    private fun parseProgress(body: JSONObject): KosyncProgress? {
        // An unknown document is `{}`; some servers spell it with an
        // "error" field instead. Either way there is no percentage, and
        // no percentage means nothing to sync rather than a fault.
        if (!body.has("percentage")) return null
        val percentage = body.optDouble("percentage")
        if (percentage.isNaN() || percentage < 0.0 || percentage > 1.0) {
            throw RemoteHttpFailure(SyncFailure.Malformed)
        }
        return KosyncProgress(
            percentage = percentage,
            device = body.optString("device").takeIf { it.isNotBlank() },
            deviceId = body.optString("device_id").takeIf { it.isNotBlank() },
            timestamp = body.optLong("timestamp", 0L).takeIf { it > 0 }?.times(1000),
        )
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        // Exactly KOReader's partial-MD5 shape: this client only ever
        // addresses documents it computed itself, and those are always
        // 32 lowercase hex characters.
        val DOCUMENT = Regex("^[0-9a-f]{32}$")
    }
}
