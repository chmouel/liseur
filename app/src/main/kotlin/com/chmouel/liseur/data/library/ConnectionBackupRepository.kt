package com.chmouel.liseur.data.library

import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.KosyncPeer
import com.chmouel.liseur.data.kosync.KosyncAccountRepository
import com.chmouel.liseur.data.remote.AccountBackup
import com.chmouel.liseur.data.remote.RemoteAccountRepository
import com.chmouel.liseur.data.remote.UnreadableCredentialException
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.ServerSettings
import com.chmouel.liseur.providers.ServerConnections
import com.chmouel.liseur.providers.ServiceAccounts
import kotlinx.coroutines.flow.first
import org.json.JSONObject

internal class ConnectionBackupRepository(
    private val accounts: RemoteAccountRepository,
    private val kosync: KosyncAccountRepository,
    private val settings: AppSettingsRepository,
    private val services: ServerConnections,
    private val serviceAccounts: ServiceAccounts,
    private val beforeReplace: suspend () -> Unit,
) {
    suspend fun export(): JSONObject {
        val keys = JSONObject()
        val servers = requireNotNull(settings.settings.first().servers.readable)
        servers.mapNotNull { ServerConnections.originOf(it.url) }.distinct().forEach { origin ->
            services.key(origin)?.let { keys.put(origin, it) }
        }
        return JSONObject().put("version", 1)
            .put("account", accounts.current()?.let(AccountBackup::encode) ?: JSONObject.NULL)
            .put("kosync", kosync.current()?.let { peer ->
                JSONObject().put("baseUrl", peer.baseUrl).put("username", peer.username)
                    .put("key", (peer.credentials ?: throw UnreadableCredentialException()).key)
            } ?: JSONObject.NULL)
            .put("keys", keys)
            .put("gemini", serviceAccounts.backupKey() ?: JSONObject.NULL)
    }

    fun prepareRestore(payload: JSONObject, app: JSONObject): suspend () -> Unit {
        // Seal account credentials before retiring any account, so Keystore failures cannot drop it.
        val account = payload.nullableObject("account")?.let { AccountBackup.decode(it, CredentialCipher::encrypt) }
        val peer = payload.nullableObject("kosync")?.let {
            KosyncPeer(baseUrl = it.getString("baseUrl"), username = it.getString("username"),
                keyCipher = KosyncPeer.seal(it.getString("key")), addedAt = System.currentTimeMillis())
        }
        val keys = payload.getJSONObject("keys").let { json ->
            json.keys().asSequence().associateWith { json.getString(it) }
        }
        return {
            beforeReplace()
            services.restoreCredentials(app, keys) {
                settings.restoreBackupValues(app, replaceServices = true)
                serviceAccounts.restoreKey(payload.nullableText("gemini"))
                accounts.restoreBackup(account)
                kosync.restoreBackup(peer)
            }
        }
    }

    companion object {
        fun validate(payload: JSONObject, app: JSONObject) {
            require(payload.opt("version") == 1)
            payload.nullableObject("account")?.let { AccountBackup.decode(it) }
            payload.nullableObject("kosync")?.let {
                AccountBackup.validateUrl(it.getString("baseUrl"))
                require((it.get("username") as? String)?.isNotBlank() == true)
                require((it.get("key") as? String)?.matches(Regex("[0-9a-f]{32}")) == true)
            }
            val servers = ServerSettings.decodeServers(app.getString(ServerSettings.SERVERS_NAME))
            val origins = servers.mapNotNull { ServerConnections.originOf(it.url) }.toSet()
            val keys = payload.getJSONObject("keys")
            keys.keys().asSequence().forEach { origin ->
                require(origin in origins && (keys.get(origin) as? String)?.isNotBlank() == true)
            }
            payload.nullableText("gemini")?.let { require(it.isNotBlank()) }
        }
    }
}

private fun JSONObject.nullableObject(key: String): JSONObject? =
    if (get(key) == JSONObject.NULL) null else get(key) as? JSONObject ?: error("Invalid connection object")

private fun JSONObject.nullableText(key: String): String? =
    if (get(key) == JSONObject.NULL) null else get(key) as? String ?: error("Invalid credential")
