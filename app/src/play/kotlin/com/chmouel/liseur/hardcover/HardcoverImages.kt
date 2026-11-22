package com.chmouel.liseur.hardcover

import android.content.Context
import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.chmouel.liseur.data.library.BookMetadataOverrideRepository.Companion.MAX_COVER_SOURCE_BYTES
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Cover images for the edition picker.
 *
 * They come from Hardcover's public image host and nowhere else, over a
 * client of their own: the app's shared loader signs requests for other
 * servers and keeps files on disk, neither of which a cover may touch.
 */
object HardcoverImages {
    const val HOST = "assets.hardcover.app"

    /** [url] when it is https on [HOST] with no userinfo and the default port; else null. */
    fun validCoverUrl(url: String?): String? {
        val parsed = url?.trim()?.toHttpUrlOrNull() ?: return null
        val ok = parsed.isHttps && parsed.host == HOST && parsed.username.isEmpty() &&
            parsed.password.isEmpty() && parsed.port == 443
        return parsed.toString().takeIf { ok }
    }

    /** No interceptors, authenticator, cookie jar or redirects: a bare client for a public host. */
    fun newClient(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var sharedClient: OkHttpClient? = null

    private fun client(): OkHttpClient = sharedClient ?: synchronized(this) {
        sharedClient ?: newClient().also { sharedClient = it }
    }

    /** Downloads a validated cover URL over [newClient], capped at [MAX_COVER_SOURCE_BYTES]. */
    suspend fun downloadCover(url: String): ByteArray? {
        val validated = validCoverUrl(url) ?: return null
        return fetchBytes(validated, client())
    }

    internal suspend fun fetchBytes(
        url: String,
        client: OkHttpClient = newClient(),
        maxBytes: Int = MAX_COVER_SOURCE_BYTES,
    ): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body
                val contentLength = body.contentLength()
                if (contentLength > maxBytes) return@use null
                body.byteStream().use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) return@use null
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray().takeIf { it.isNotEmpty() }
                }
            }
        }.getOrNull()
    }

    @Volatile
    private var shared: ImageLoader? = null

    /**
     * The picker's own loader: memory cache only, the bare [newClient],
     * and nothing but a validated cover URL gets through. Never the app's
     * shared loader, whose client signs requests for the reader's servers.
     */
    fun loader(context: Context): ImageLoader = shared ?: synchronized(this) {
        shared ?: build(context.applicationContext).also { shared = it }
    }

    private fun build(context: Context): ImageLoader {
        val client = client()
        return ImageLoader.Builder(context)
            .components {
                add(CoverOnly)
                add(OkHttpNetworkFetcherFactory(callFactory = { client }))
            }
            .diskCache(null)
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, MEMORY_SHARE).build() }
            .build()
    }

    private object CoverOnly : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val data = chain.request.data
            if (data is String && validCoverUrl(data) == data) return chain.proceed()
            return ErrorResult(
                image = null,
                request = chain.request,
                throwable = IllegalArgumentException("Not a Hardcover cover URL"),
            )
        }
    }

    private const val MEMORY_SHARE = 0.1
}
