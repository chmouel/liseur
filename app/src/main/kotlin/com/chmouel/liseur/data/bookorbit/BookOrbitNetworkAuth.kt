package com.chmouel.liseur.data.bookorbit

import com.chmouel.liseur.data.remote.RemoteCredentials
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Signs an OkHttp call at execution time and renews it once on 401.
 *
 * Downloads can wait behind a bulk-transfer slot for longer than an
 * access token lives, so putting a bearer onto the request when the
 * worker constructs it is already too early. The request instead carries
 * a non-secret [BookOrbitRequestContext], and this interceptor asks for a
 * token immediately before the network hop. The authenticator spends a
 * refresh only for the same context and rejected token.
 */
class BookOrbitNetworkAuth(
    private val session: BookOrbitSession,
    /** Covers have no request tag until the network interceptor sees them. */
    private val inferCurrentContext: Boolean = false,
) : Interceptor, Authenticator {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val context = request.tag(BookOrbitRequestContext::class.java)
            ?: if (inferCurrentContext) {
                runBlocking { session.contextForUrl(request.url.toString()) }
            } else {
                null
            }
            ?: return chain.proceed(request)
        if (!context.covers(request.url.toString())) throw IOException("BookOrbit request left its server scope")
        val token = runBlocking { session.token(context) }
        val signed =
            signed(request, token).newBuilder()
                .tag(BookOrbitRequestContext::class.java, context)
                .build()
        val response = chain.proceed(signed)
        if (response.code != 401 || request.tag(Retried::class.java) != null) return response

        val fresh = runBlocking {
            runCatching { session.afterRejection(context, token) }.getOrNull()
        } ?: return response
        response.close()
        return chain.proceed(
            signed(request, fresh).newBuilder()
                .tag(BookOrbitRequestContext::class.java, context)
                .tag(Retried::class.java, Retried)
                .build(),
        )
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        // The application interceptor above owns the one retry. Leaving
        // OkHttp's challenge path inert keeps a 401 with no challenge
        // header (which BookOrbit emits) and one with a proxy-added
        // challenge under the same rule.
        return null
    }

    private fun signed(request: Request, token: String): Request =
        RemoteCredentials.Bearer(token).signInto(
            request.newBuilder().removeHeader(AUTHORIZATION),
        ).build()

    companion object {
        private const val AUTHORIZATION = "Authorization"
    }

    /** Marks the one authenticated retry without sending a header. */
    private data object Retried
}
