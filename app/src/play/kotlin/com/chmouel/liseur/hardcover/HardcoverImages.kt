package com.chmouel.liseur.hardcover

import android.content.Context
import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageResult
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

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
        val client = newClient()
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
