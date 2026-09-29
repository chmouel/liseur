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
    private val browseCredentialsFor: (BrowseCoverSource, String) -> RemoteCredentials? = { _, _ -> null },
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
        val browse = request.tag(BrowseCoverSource::class.java)
        val credentials = if (browse != null) {
            browseCredentialsFor(browse, request.url.toString())
        } else {
            credentialsFor(request.url.toString())
        }
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
            browseCredentialsFor: (BrowseCoverSource, String) -> RemoteCredentials? = { _, _ -> null },
        ): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(BrowseCoverTaggingInterceptor())
            .apply {
                if (bookOrbitAuth != null) addInterceptor(bookOrbitAuth)
            }
            .addNetworkInterceptor(RemoteAuthInterceptor(credentialsFor, browseCredentialsFor, requestPolicy))
            .build()
    }
}

/** The saved connection and its cover cache scope, kept on redirects as an OkHttp request tag. */
data class BrowseCoverSource(val serverId: Long, val scope: String?)

internal fun browseCoverRequestHeader(serverId: Long, url: String): String =
    "$serverId:${browseCoverScopeFromUrl(url).orEmpty()}"

private fun browseCoverScopeFromUrl(url: String): String? =
    url.substringAfter('#', "").split('&')
        .lastOrNull { it.startsWith("liseur-browse=") }
        ?.substringAfter('=')
        ?.takeIf { it.matches(Regex("[0-9a-f]{24}")) }

/** Coil sends this header; it is removed before the request reaches the network. */
class BrowseCoverTaggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val header = request.header(HEADER)
        val id = header?.substringBefore(':')?.toLongOrNull()
        val builder = request.newBuilder().removeHeader(HEADER)
        if (id != null) {
            builder.tag(BrowseCoverSource::class.java, BrowseCoverSource(id, header.substringAfter(':', "").ifEmpty { null }))
        }
        return chain.proceed(builder.build())
    }

    companion object {
        const val HEADER = "X-Liseur-Browse-Source"
    }
}

/** How the shared cover interceptor handles a provider-owned request. */
enum class RequestCredentialPolicy {
    DEFAULT,
    PRESERVE,
    STRIP,
}
