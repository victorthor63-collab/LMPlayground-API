package com.druk.lmplayground.apiserver

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address

/**
 * Resolves the device's WiFi interface address so the API server can bind to
 * it **only** — never `0.0.0.0` (which would also expose the server over
 * cellular / USB tethering / VPN interfaces).
 *
 * The idiomatic path on minSdk 30: ask [ConnectivityManager] for the network
 * whose [NetworkCapabilities] declare [NetworkCapabilities.TRANSPORT_WIFI],
 * read its [LinkProperties], and pick the IPv4 link address. Binding a
 * `ServerSocket` to that [Inet4Address] restricts the listener to the WiFi
 * interface.
 */
object WifiInterface {

    /**
     * The current WiFi IPv4 address, or null when the device is not on WiFi
     * (or the address could not be determined). Callers should treat null as
     * "cannot start the server".
     */
    fun currentWifiIpv4(context: Context): Inet4Address? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val ipv4 = linkIpv4(cm.getLinkProperties(network)) ?: continue
            return ipv4
        }
        return null
    }

    private fun linkIpv4(props: LinkProperties?): Inet4Address? {
        val addresses = props?.linkAddresses ?: return null
        return addresses
            .mapNotNull { it.address as? Inet4Address }
            .firstOrNull { !it.isLoopbackAddress }
    }

    /**
     * Watches for WiFi availability / address changes and invokes [onChanged]
     * (on a binder thread) whenever the WiFi network appears, disappears, or
     * its link properties change. The API server uses this to (re)bind or shut
     * its socket down when the WiFi connection changes underneath it.
     *
     * Returns a handle; call [Registration.unregister] to stop watching.
     */
    fun watchWifi(context: Context, onChanged: () -> Unit): Registration {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChanged()
            override fun onLost(network: Network) = onChanged()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                onChanged()
        }
        cm.registerNetworkCallback(request, callback)
        return Registration { runCatching { cm.unregisterNetworkCallback(callback) } }
    }

    fun interface Registration {
        fun unregister()
    }
}
