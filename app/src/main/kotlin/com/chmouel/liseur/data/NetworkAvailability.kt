package com.chmouel.liseur.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * A cheap snapshot of whether Android currently has a network to use.
 *
 * The question is only ever "is there any point trying", and the wrong
 * answer in one direction is far worse than in the other: a false "no"
 * silently stops a book server on the same Wi-Fi from ever being
 * reached, while a false "yes" costs the timeout it would have cost
 * anyway. Everything here therefore leans towards saying yes.
 *
 * It is asked afresh at each boundary rather than observed, for the same
 * reason: a stale "yes" costs nothing that was not already being spent.
 */
fun interface NetworkAvailability {
    fun isAvailable(): Boolean
}

class AndroidNetworkAvailability(context: Context) : NetworkAvailability {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /**
     * With no ConnectivityManager to ask, this says yes. Guessing "offline"
     * would switch the whole app's networking off over a missing system
     * service; guessing "online" only gives up the shortcut.
     */
    override fun isAvailable(): Boolean {
        val manager = connectivityManager ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return true
        // Anything that can carry a packet to a book server counts.
        //
        // NET_CAPABILITY_INTERNET is not required and VALIDATED is
        // firmly not: a self-hosted server frequently lives on a LAN
        // with no route out, on a Wi-Fi Android knows perfectly well is
        // not reaching the internet. Asking for either would take that
        // reader's whole library away.
        return LOCAL_TRANSPORTS.any(capabilities::hasTransport) ||
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private companion object {
        /** Transports that can reach a machine on the same network. */
        val LOCAL_TRANSPORTS = intArrayOf(
            NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_ETHERNET,
            NetworkCapabilities.TRANSPORT_VPN,
            NetworkCapabilities.TRANSPORT_BLUETOOTH,
        ).asList()
    }
}
