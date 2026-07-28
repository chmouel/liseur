package com.chmouel.liseur.data.calibre

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Attaches the calibre-web login to cover requests.
 *
 * Coil loads covers straight from the server, and those URLs need Basic
 * auth like everything else. The header is only added for the configured
 * server, so a cover URL that points somewhere else never carries it.
 */
class CalibreAuthInterceptor(
    private val credentialsFor: (String) -> CalibreCredentials?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val credentials = credentialsFor(request.url.toString())
            ?: return chain.proceed(request)

        return chain.proceed(
            request.newBuilder().header("Authorization", credentials.basic).build(),
        )
    }

    companion object {
        fun imageLoaderClient(credentialsFor: (String) -> CalibreCredentials?): OkHttpClient =
            OkHttpClient.Builder()
                .addInterceptor(CalibreAuthInterceptor(credentialsFor))
                .build()
    }
}
