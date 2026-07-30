package com.chmouel.liseur.data.remote

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Attaches the account's credentials to cover requests.
 *
 * Coil loads covers straight from the server, and those URLs need
 * signing like everything else. Only requests to the configured server
 * are signed, so a cover URL that points somewhere else never carries
 * the credentials.
 */
class RemoteAuthInterceptor(
    private val credentialsFor: (String) -> RemoteCredentials?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val credentials = credentialsFor(request.url.toString())
            ?: return chain.proceed(request)

        return chain.proceed(credentials.signInto(request.newBuilder()).build())
    }

    companion object {
        fun imageLoaderClient(credentialsFor: (String) -> RemoteCredentials?): OkHttpClient =
            OkHttpClient.Builder()
                .addInterceptor(RemoteAuthInterceptor(credentialsFor))
                .build()
    }
}
