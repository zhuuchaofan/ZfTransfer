package com.xiaozhu.zftransfer

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import java.net.Inet4Address

/** A physical Wi-Fi network that can reach a camera, with the address to dial. */
data class WifiEndpoint(val network: Network, val address: String, val caps: NetworkCapabilities)

/** Finds the camera's Wi-Fi among the phone's networks; never probes ordinary Wi-Fi or VPN. */
class CameraEndpointLocator(
    context: Context,
    private val prefs: SharedPreferences,
    private val wifiStore: WifiProfileStore,
    private val requestedNetwork: () -> Network?,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    /** The SSID is hidden without the Wi-Fi permission, so a failed read is an expected "unknown". */
    fun ssidOf(caps: NetworkCapabilities) = runCatching { (caps.transportInfo as? WifiInfo)?.ssid }.getOrNull()

    private fun wifiEndpoints(manual: String, only: Network?): List<WifiEndpoint> {
        val candidates = if (only != null) listOf(only) else listOfNotNull(requestedNetwork()) + cm.allNetworks.toList()
        return candidates.distinct().mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val links = cm.getLinkProperties(network) ?: return@mapNotNull null
            val gateway = links.routes.asSequence().filter { it.isDefaultRoute }
                .mapNotNull { (it.gateway as? Inet4Address)?.hostAddress }.firstOrNull { CameraAddress.isUsable(it) }
                ?: if (android.os.Build.VERSION.SDK_INT >= 30) links.dhcpServerAddress?.hostAddress?.takeIf { CameraAddress.isUsable(it) } else null
            val address = manual.ifEmpty { gateway ?: return@mapNotNull null }
            WifiEndpoint(network, address, caps)
        }
    }

    fun find(manual: String, only: Network? = null): WifiEndpoint? {
        val endpoints = wifiEndpoints(manual, only)
        val names = setOfNotNull(wifiStore.load()?.ssid, prefs.getString("last_camera_ssid", null))
        val chosen = CameraNetworkSelector.select(endpoints.map {
            CameraNetworkHint(it.network.networkHandle, it.address, ssidOf(it.caps),
                it.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), it.network == requestedNetwork())
        }, manual, prefs.getString("last_camera_address", "192.168.1.1").orEmpty(), names) ?: return null
        return endpoints.firstOrNull { it.network.networkHandle == chosen.id }
    }
}
