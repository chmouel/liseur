package com.chmouel.liseur.translate

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** HTTP as the translation clients use it. There is no logging interceptor: requests carry a key and a passage. */
internal object TranslationHttp {
    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        // A redirect could carry the key header and the passage elsewhere.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * Runs [request] and [parse]s its response, cancelled with the caller.
     * Throws [TranslationError].
     */
    suspend fun <T> execute(client: OkHttpClient, request: Request, parse: (Response) -> T): T {
        val call = client.newCall(request)
        // A blocking execute() would carry on after its coroutine was
        // cancelled; an enqueued call is cancelled with it.
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(TranslationError.Network(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val outcome = try {
                            Result.success(response.use(parse))
                        } catch (e: TranslationError) {
                            Result.failure(e)
                        } catch (e: IOException) {
                            Result.failure(TranslationError.Network(e))
                        }
                        if (!continuation.isActive) return
                        outcome.fold(continuation::resume, continuation::resumeWithException)
                    }
                },
            )
        }
    }

    /** At most [limit] bytes of [response]'s body as text, or null when there are more. */
    fun boundedText(response: Response, limit: Long): String? {
        val source = response.body.source()
        if (source.request(limit + 1)) return null
        return source.buffer.readUtf8()
    }

    /** A header value that cannot be sent, such as a key with a pasted line break, is an invalid key. */
    fun Request.Builder.secret(name: String, value: String): Request.Builder = try {
        header(name, value)
    } catch (_: IllegalArgumentException) {
        // OkHttp's message would repeat the key, so it is not kept as the cause.
        throw TranslationError.InvalidKey()
    }

    const val MAX_REPLY_BYTES = 256 * 1024L
    const val MAX_LIST_BYTES = 1024 * 1024L
    const val MAX_ERROR_BYTES = 64 * 1024L
}
