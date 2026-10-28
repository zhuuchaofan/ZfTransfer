package com.xiaozhu.zftransfer

object CameraAddress {
    /** Camera endpoints must be usable IPv4 unicast addresses, never wildcard/VPN gateways. */
    fun isUsable(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { part ->
            if (part.isEmpty() || part.any { it !in '0'..'9' }) return false
            part.toIntOrNull()?.takeIf { it in 0..255 } ?: return false
        }
        return octets[0] in 1..223 && octets[0] != 127 && value != "255.255.255.255"
    }
}
