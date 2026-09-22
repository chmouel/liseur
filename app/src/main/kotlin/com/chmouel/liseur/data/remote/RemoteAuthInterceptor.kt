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
 *
 * This runs as a *network* interceptor, once per hop, rather than once
 * per call. A redirect is a hop: OkHttp builds the follow-up request
 * from the original headers, and while it knows to drop `Authorization`
 * when the host changes, it has no idea that `X-API-Key` is a secret and
 * carries it wherever it is sent. So both headers are stripped from
 * every outgoing request first and re-added only when the URL actually
 * being fetched belongs to the account's server.
 */
class RemoteAuthInterceptor(
    private val credentialsFor: (String) -> RemoteCredentials?,
    private val requestPolicy: (okhttp3.Request) -> RequestCredentialPolicy = {
        RequestCredentialPolicy.DEFAULT
    },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        when (requestPolicy(request)) {
            RequestCredentialPolicy.PRESERVE -> return chain.proceed(request)
            RequestCredentialPolicy.STRIP -> {
                val unsigned = request.newBuilder()
                    .removeHeader(BASIC_HEADER)
                    .removeHeader(RemoteCredentials.ApiKey.HEADER)
                return chain.proceed(unsigned.build())
            }
            RequestCredentialPolicy.DEFAULT -> Unit
        }
        val unsigned = request.newBuilder()
            .removeHeader(BASIC_HEADER)
            .removeHeader(RemoteCredentials.ApiKey.HEADER)
        val credentials = credentialsFor(request.url.toString())
        return chain.proceed(credentials?.signInto(unsigned)?.build() ?: unsigned.build())
    }

    companion object {
        private const val BASIC_HEADER = "Authorization"

        /** Compatibility overload for the existing providers and tests. */
        fun imageLoaderClient(
            credentialsFor: (String) -> RemoteCredentials?,
        ): OkHttpClient = imageLoaderClient(
            credentialsFor = credentialsFor,
            bookOrbitAuth = null,
            requestPolicy = { RequestCredentialPolicy.DEFAULT },
        )

        fun imageLoaderClient(
            credentialsFor: (String) -> RemoteCredentials?,
            bookOrbitAuth: com.chmouel.liseur.data.bookorbit.BookOrbitNetworkAuth? = null,
            requestPolicy: (okhttp3.Request) -> RequestCredentialPolicy = {
                RequestCredentialPolicy.DEFAULT
            },
        ): OkHttpClient = OkHttpClient.Builder()
            .apply {
                if (bookOrbitAuth != null) addInterceptor(bookOrbitAuth)
            }
            .addNetworkInterceptor(RemoteAuthInterceptor(credentialsFor, requestPolicy))
            .build()
    }
}

/** How the shared cover interceptor handles a provider-owned request. */
enum class RequestCredentialPolicy {
    DEFAULT,
    PRESERVE,
    STRIP,
}
