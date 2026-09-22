package com.chmouel.liseur.data.bookorbit

import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.RemoteUrl
import com.chmouel.liseur.data.remote.RemoteOrigin
import com.chmouel.liseur.data.remote.ServerKind

/**
 * Which published BookOrbit connection owns a network request.
 *
 * A URL is not enough. The reader can sign out and sign in as somebody
 * else on the same address while an old catalog page or cover is still
 * queued, and looking up "the current token" at execution time would
 * put the new account's credential on the old account's request. Every
 * request therefore captures the account and connection epoch as well
 * as the address it is allowed to reach.
 */
data class BookOrbitRequestContext(
    val accountKey: String,
    val epoch: Long,
    val baseUrl: String,
) {
    /** Whether [server] is still the connection this request began for. */
    fun matches(server: RemoteServer?): Boolean =
        server?.kind == ServerKind.BOOKORBIT &&
            server.accountKey == accountKey &&
            server.orbitEpoch == epoch &&
            RemoteUrl.sameAddress(server.baseUrl, baseUrl)

    /** Whether [url] is inside the server prefix this request captured. */
    fun covers(url: String): Boolean = RemoteOrigin.of(baseUrl)?.covers(url) == true

    companion object {
        fun from(server: RemoteServer): BookOrbitRequestContext? =
            server.takeIf { it.kind == ServerKind.BOOKORBIT }?.let {
                BookOrbitRequestContext(
                    accountKey = it.accountKey,
                    epoch = it.orbitEpoch,
                    baseUrl = it.baseUrl,
                )
            }
    }
}
