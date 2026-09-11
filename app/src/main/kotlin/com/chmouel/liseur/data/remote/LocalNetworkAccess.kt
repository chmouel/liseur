package com.chmouel.liseur.data.remote

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.InetAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Whether the phone will let the app reach a given address, and what it
 * is called when it will not.
 *
 * Android 17 blocks local network traffic for every app targeting it
 * until the reader allows it, and blocks it below the HTTP client and
 * without an error: a connection to a server on the reader's own
 * network hangs until it times out. Liseur's readers self-host, so this
 * is not an edge case for them — but plenty of them reach their library
 * over the open internet, where the permission is neither needed nor
 * welcome. So it is asked for only where the address is actually local.
 *
 * Everything here answers false below Android 17, where nothing is
 * blocked and nothing may be asked.
 */
interface LocalNetworkAccess {

    /** Whether this phone gates the local network at all. */
    val required: Boolean

    /** Whether the reader has already allowed it. */
    val granted: Boolean

    /**
     * Whether connecting to [url] would be blocked as things stand.
     *
     * Null or blank is not an address and cannot be blocked — a
     * connection with no kosync partner, say, is asked about with
     * exactly that.
     */
    suspend fun blocks(url: String?): Boolean

    /**
     * Bumped when the answer to [blocks] may have changed, so a
     * connection that outlives one question can ask it again.
     *
     * A background sync asks afresh every run and needs none of this.
     * A live notification stream is opened once and held for as long as
     * the app is on screen, so a reader who allows the permission from
     * the connected card would otherwise wait out the rest of that
     * session with the stream still down.
     */
    val grants: Flow<Long> get() = flowOf(0L)

    /**
     * Look again, after the reader has answered a permission prompt.
     *
     * Answers whether the answer actually moved, so a caller can tell a
     * grant from a resume that changed nothing.
     */
    fun recheck(): Boolean = false

    companion object {
        /**
         * Spelled out rather than taken from `Manifest.permission`,
         * which would inline a constant from an SDK above `minSdk` and
         * read as a call nothing older can make. It is the same string
         * the manifest declares.
         */
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

        /** A phone that gates nothing, which is every phone below 17. */
        val Unrestricted = object : LocalNetworkAccess {
            override val required = false
            override val granted = true
            override suspend fun blocks(url: String?) = false
        }
    }
}

/** The real thing, reading the phone's permission and its routes. */
class AndroidLocalNetworkAccess(
    context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : LocalNetworkAccess {

    private val context = context.applicationContext

    private val _grants = MutableStateFlow(0L)

    override val grants = _grants.asStateFlow()

    private var lastGranted: Boolean = granted

    override val required: Boolean get() = sdkInt >= Build.VERSION_CODES.CINNAMON_BUN

    /**
     * Only an answer that actually changed is announced. A denial, or a
     * grant on a phone that was never going to block anything, must not
     * tear down a healthy connection to reopen it unchanged.
     */
    override fun recheck(): Boolean {
        val now = granted
        if (lastGranted == now) return false
        lastGranted = now
        _grants.value = _grants.value + 1
        return true
    }

    override val granted: Boolean
        get() = !required ||
            context.checkSelfPermission(LocalNetworkAccess.PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun blocks(url: String?): Boolean {
        if (!required || granted) return false
        if (url.isNullOrBlank()) return false
        return LocalNetworkAddress.isLocal(url, onLink = ::onLink)
    }

    /**
     * Whether an address sits on one of this phone's own links.
     *
     * Every network is consulted, not only the default one, because a
     * library on Wi-Fi is still on Wi-Fi while cellular carries the
     * rest. VPN transports are left out: Android's restriction does not
     * reach what a tunnel carries, and a tailnet address must not raise
     * a prompt for a permission it never needed.
     *
     * This is not a routing table and does not pretend to be one. It
     * can name an address local that the socket would in fact have
     * reached another way, and the cost of that is one dialog on a
     * screen the reader opened to connect a server. The cost of the
     * other direction is the fifteen seconds of silence this whole file
     * exists to prevent.
     */
    private fun onLink(host: String): Boolean {
        // The routes are read through APIs newer than `minSdk`, and are
        // only ever worth reading where the restriction exists at all.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return false
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return manager.allNetworks.any { network ->
            val capabilities = manager.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != false) {
                return@any false
            }
            manager.getLinkProperties(network)?.routes.orEmpty().any {
                !it.hasGateway() && it.matches(address)
            }
        }
    }
}
