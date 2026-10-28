package com.xiaozhu.zftransfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper

/** Watches physical Wi-Fi; optional specifier requests are scoped to this app. */
class CameraWifi(context: Context, private val changed: () -> Unit,
                 private val lost: (Network) -> Unit, private val status: (String) -> Unit) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val notify = Runnable { changed() }
    var requestedNetwork: Network? = null
        private set
    var requesting = false
        private set
    private var requestCallback: ConnectivityManager.NetworkCallback? = null
    private var watching = false
    private val observer = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = schedule()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = schedule()
        override fun onLinkPropertiesChanged(network: Network, links: LinkProperties) = schedule()
        override fun onLost(network: Network) { main.post { lost(network); schedule() } }
    }
    fun schedule() { main.removeCallbacks(notify); main.postDelayed(notify, 800) }
    fun watch() {
        if (watching) return
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), observer)
        watching = true
    }
    fun request(profile: WifiProfile) {
        require(profile.valid())
        releaseRequest()
        val specifier = WifiNetworkSpecifier.Builder().setSsid(profile.ssid).setWpa2Passphrase(profile.password).build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { main.post {
                if (requestCallback !== this) return@post
                requesting = false; requestedNetwork = network
                android.util.Log.i("ZfTransfer", "Wi-Fi profile request available; network=${network.networkHandle}")
                status("相机 Wi-Fi 已连接，正在识别相机…"); schedule()
            } }
            override fun onLinkPropertiesChanged(network: Network, links: LinkProperties) = schedule()
            override fun onUnavailable() { main.post {
                if (requestCallback !== this) return@post
                requesting = false; requestCallback = null
                android.util.Log.i("ZfTransfer", "Wi-Fi profile request unavailable")
                status("未能连接相机 Wi-Fi。请检查相机热点、名称和密码，或在系统设置中连接。")
            } }
            override fun onLost(network: Network) { main.post {
                if (requestCallback !== this) return@post
                requestedNetwork = null; lost(network)
                status("相机 Wi-Fi 已断开。请重新连接后重试。")
            } }
        }
        requestCallback = callback; requesting = true
        try {
            cm.requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build(), callback, 45000)
            status("等待系统确认连接相机 Wi-Fi…")
        } catch (e: Exception) {
            releaseRequest()
            // No exception details: OEM messages may contain credentials.
            status("系统未接受 Wi-Fi 连接请求。请检查附近设备权限，或使用系统 Wi-Fi 设置。")
        }
    }
    fun releaseRequest() {
        requestCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        requestCallback = null; requestedNetwork = null; requesting = false
    }
    fun close() {
        main.removeCallbacks(notify)
        if (watching) runCatching { cm.unregisterNetworkCallback(observer) }
        watching = false; releaseRequest()
    }
}
