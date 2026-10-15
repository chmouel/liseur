package com.chmouel.liseur.data.remote

import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.RemoteServer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** A saved credential this device can no longer decrypt, so it cannot go into a backup. */
internal class UnreadableCredentialException : IllegalStateException("Unreadable saved credential")

/** Explicit connection allowlist: no cursors, acknowledgements, or queued work. */
internal object AccountBackup {
    fun encode(server: RemoteServer): JSONObject = JSONObject().apply {
        put("kind", server.kind.name)
        put("baseUrl", server.baseUrl)
        put("catalogUrl", server.catalogUrl ?: JSONObject.NULL)
        put("username", server.username ?: JSONObject.NULL)
        put(
            "accountId",
            server.accountId.takeUnless { server.kind == ServerKind.LISEUR_SYNC } ?: JSONObject.NULL,
        )
        put("userId", server.userId ?: JSONObject.NULL)
        put("liseurAccountId", server.liseurAccountId ?: JSONObject.NULL)
        put("orbitSessionId", server.orbitSessionId ?: JSONObject.NULL)
        put("orbitAccessExpires", server.orbitAccessExpires)
        put("canDownload", server.canDownload)
        put("canManageLibrary", server.canManageLibrary)
        put("canUpload", server.canUpload)
        put("canDelete", server.canDelete)
        put("canReadInsights", server.canReadInsights)
        put("canAdmin", server.canAdmin)
        mapOf("password" to server.passwordCipher, "apiKey" to server.apiKeyCipher,
            "koboToken" to server.koboTokenCipher,
            "liseurToken" to server.liseurTokenCipher.takeUnless { server.kind == ServerKind.LISEUR_SYNC },
            "orbitAccess" to server.orbitAccessCipher, "orbitRefresh" to server.orbitRefreshCipher,
        ).forEach { (name, cipher) ->
            put(name, cipher?.let { CredentialCipher.decrypt(it) ?: throw UnreadableCredentialException() } ?: JSONObject.NULL)
        }
    }

    /** With the identity seal this only validates; the restore seals on the destination device. */
    fun decode(json: JSONObject, seal: (String) -> String = { it }): RemoteServer {
        fun text(name: String): String? = if (json.get(name) == JSONObject.NULL) null else
            (json.get(name) as? String ?: error("Invalid account text"))
        fun secret(name: String): String? = text(name)?.also { require(name == "password" || it.isNotEmpty()) }?.let(seal)
        fun bool(name: String): Boolean = json.get(name) as? Boolean ?: error("Invalid account capability")
        fun number(name: String): Long {
            val value = json.get(name) as? Number ?: error("Invalid account number")
            val number = value.toDouble()
            require(number.isFinite() && number >= 0 && number % 1.0 == 0.0 && number < Long.MAX_VALUE.toDouble())
            return value.toLong()
        }
        fun int(name: String): Int? = if (json.get(name) == JSONObject.NULL) null else
            number(name).also { require(it <= Int.MAX_VALUE) }.toInt()
        val kind = ServerKind.valueOf(json.getString("kind"))
        val base = requireNotNull(text("baseUrl"))
        validateUrl(base)
        val catalog = text("catalogUrl")?.also(::validateUrl)
        val accountId = if (kind == ServerKind.LISEUR_SYNC) null else text("accountId")
        val liseurToken = text("liseurToken")?.also { require(it.isNotEmpty()) }
            ?.takeUnless { kind == ServerKind.LISEUR_SYNC }
            ?.let(seal)
        val server = RemoteServer(
            kind = kind, baseUrl = base, catalogUrl = catalog, username = text("username"),
            passwordCipher = secret("password"), apiKeyCipher = secret("apiKey"), accountId = accountId,
            userId = int("userId"), koboTokenCipher = secret("koboToken"),
            canDownload = bool("canDownload"), canManageLibrary = bool("canManageLibrary"),
            canUpload = bool("canUpload"), canDelete = bool("canDelete"), canReadInsights = bool("canReadInsights"),
            canAdmin = bool("canAdmin"), addedAt = System.currentTimeMillis(),
            catalogSyncedAt = null, positionSyncedAt = null, syncToken = null,
            liseurTokenCipher = liseurToken, liseurAccountId = text("liseurAccountId"),
            orbitAccessCipher = secret("orbitAccess"), orbitRefreshCipher = secret("orbitRefresh"),
            orbitAccessExpires = number("orbitAccessExpires"), orbitSessionId = int("orbitSessionId"),
        )
        require(when (kind) {
            ServerKind.CALIBRE -> server.username != null && server.passwordCipher != null
            ServerKind.KOMGA -> server.apiKeyCipher != null
            ServerKind.LISEUR_SYNC -> server.accountId == null && server.liseurTokenCipher == null
            ServerKind.BOOKORBIT -> server.orbitAccessCipher != null && server.orbitRefreshCipher != null
            ServerKind.CUSTOM -> server.passwordCipher == null || server.username != null
        })
        return server
    }

    fun validateUrl(value: String) {
        val url = requireNotNull(value.toHttpUrlOrNull())
        require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null)
    }
}
