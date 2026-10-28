package com.xiaozhu.zftransfer

/** A physical Wi-Fi is not automatically a camera network. */
data class CameraNetworkHint(val id: Long, val address: String, val ssid: String?, val validated: Boolean, val requested: Boolean)

object CameraNetworkSelector {
    private fun normalized(value: String?) = value.orEmpty().trim('"').takeUnless { it.isBlank() || it == "<unknown ssid>" }
    fun select(networks: List<CameraNetworkHint>, manualAddress: String, knownAddress: String, knownSsids: Set<String> = emptySet()): CameraNetworkHint? {
        val names = knownSsids.mapNotNull(::normalized).toSet()
        fun rank(network: CameraNetworkHint): Int {
            val ssid = normalized(network.ssid)
            return when {
                network.requested -> 100
                ssid != null && ssid in names -> 90
                ssid?.startsWith("NIKON", ignoreCase = true) == true -> 80
                manualAddress.isNotEmpty() -> 20 // Explicit advanced setting only.
                !network.validated && network.address == knownAddress -> 10
                else -> 0
            }
        }
        return networks.filter { CameraAddress.isUsable(it.address) && rank(it) > 0 }.maxByOrNull(::rank)
    }
}
