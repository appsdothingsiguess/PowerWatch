package com.joey.powerwatch

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import java.net.Inet4Address

data class WifiPath(val network: Network, val interfaceName: String?, val gateway: String?)

/** Callback state is confined to the service worker. Connectivity is never probe success. */
class NetworkMonitor(
    context: Context,
    private val handler: Handler,
    private val onChange: (WifiPath?) -> Unit
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val paths = linkedMapOf<Network, WifiPath>()
    private val eligible = mutableSetOf<Network>()
    private var registered = false
    private var selected: WifiPath? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            paths[network] = WifiPath(network, null, null)
            // Wait for capabilities before accepting a path, including carrier-merged networks.
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            if (!paths.containsKey(network)) return
            val gateways = properties.routes.filter { it.isDefaultRoute && it.gateway?.isAnyLocalAddress == false }
                .map { it.gateway }.filterNotNull()
            val gateway = gateways.firstOrNull { it is Inet4Address } ?: gateways.firstOrNull()
            paths[network] = WifiPath(network, properties.interfaceName, gateway?.hostAddress)
            publish()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (ProbeResultPolicy.isWifiOnly(
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))) {
                eligible.add(network)
            } else {
                eligible.remove(network)
            }
            publish()
        }

        override fun onLost(network: Network) {
            paths.remove(network)
            eligible.remove(network)
            publish()
        }
    }

    private fun publish() {
        val next = paths.values.lastOrNull { it.network in eligible }
        if (next != selected) {
            selected = next
            onChange(next)
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connectivity.registerNetworkCallback(request, callback, handler)
        registered = true
    }

    fun stop() {
        if (registered) connectivity.unregisterNetworkCallback(callback)
        registered = false
        paths.clear()
        eligible.clear()
        selected = null
    }
}
