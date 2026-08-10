package com.chmouel.liseur.data.remote

import okhttp3.Credentials
import okhttp3.Request

/**
 * How to prove who we are to a server.
 *
 * Each kind knows how to write itself onto a request, which is the whole
 * point: the catalog walk, the cover loader and the download worker all
 * just ask the credentials to sign the request, and none of them has to
 * know whether that means a Basic header or an API key.
 */
sealed interface RemoteCredentials {

    /** Signs [builder], and returns it so calls can be chained. */
    fun signInto(builder: Request.Builder): Request.Builder

    /** Username and password, as calibre-web wants them. */
    data class Basic(val username: String, val password: String) : RemoteCredentials {
        val header: String get() = Credentials.basic(username, password)

        override fun signInto(builder: Request.Builder): Request.Builder =
            builder.header("Authorization", header)
    }

    /**
     * A Komga API key. Revocable from the server without changing the
     * account password, which is why it is the only way Liseur signs
     * into Komga.
     */
    data class ApiKey(val key: String) : RemoteCredentials {
        override fun signInto(builder: Request.Builder): Request.Builder =
            builder.header(HEADER, key)

        companion object {
            const val HEADER = "X-API-Key"
        }
    }

    /**
     * A bearer token, as liseur-sync wants it.
     *
     * Two quite different secrets travel this way: the short-lived token
     * a sign-in returns, which may only manage tokens, and the device
     * token that does the syncing afterwards. They are the same shape on
     * the wire and are deliberately not told apart here — what a token
     * is allowed to do is the server's business, and guessing at it
     * client-side would only be a second, wronger answer.
     */
    data class Bearer(val token: String) : RemoteCredentials {
        override fun signInto(builder: Request.Builder): Request.Builder =
            builder.header("Authorization", "Bearer $token")
    }
}
