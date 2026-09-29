package com.example.tvlauncher.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

enum class NetworkState { WIFI, ETHERNET, OTHER_ONLINE, OFFLINE }

/**
 * Pure classification logic, kept separate from [NetworkStatusMonitor] so
 * it's testable on a plain JVM without a real ConnectivityManager.
 */
fun classifyNetwork(hasInternet: Boolean, hasWifi: Boolean, hasEthernet: Boolean): NetworkState = when {
    !hasInternet -> NetworkState.OFFLINE
    hasEthernet -> NetworkState.ETHERNET
    hasWifi -> NetworkState.WIFI
    else -> NetworkState.OTHER_ONLINE
}

/**
 * Reflects the device's actual current connectivity -- Wi-Fi, Ethernet or
 * offline -- rather than assuming the launcher is always online. Registers
 * a live [ConnectivityManager.NetworkCallback] while [start] is active so
 * the header icon tracks real state changes (e.g. Wi-Fi dropping) without
 * the owner needing to reopen the launcher.
 */
class NetworkStatusMonitor(
    private val context: Context,
    private val onChange: (NetworkState) -> Unit
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            onChange(classify(capabilities))
        }

        override fun onLost(network: Network) {
            onChange(current())
        }

        override fun onAvailable(network: Network) {
            onChange(current())
        }
    }

    fun start() {
        onChange(current())
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, callback)
        } catch (e: SecurityException) {
            // No network permission on this build; report offline rather than lying "online".
            onChange(NetworkState.OFFLINE)
        }
    }

    fun stop() {
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            // Never started/already unregistered -- fine.
        }
    }

    private fun current(): NetworkState {
        val active = connectivityManager.activeNetwork ?: return NetworkState.OFFLINE
        val caps = connectivityManager.getNetworkCapabilities(active) ?: return NetworkState.OFFLINE
        return classify(caps)
    }

    private fun classify(caps: NetworkCapabilities): NetworkState = classifyNetwork(
        hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        hasEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    )
}
