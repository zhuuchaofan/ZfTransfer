package com.xiaozhu.zftransfer

import android.net.Network
import io.github.noisetta.nikonpull.ptp.PtpIpClient
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Single command lane shared by discovery, heartbeat, previews and imports. */
class CameraSession : AutoCloseable {
    val worker = Executors.newSingleThreadScheduledExecutor()
    val busy = AtomicBoolean(false)
    @Volatile var client: PtpIpClient? = null
    @Volatile var network: Network? = null

    fun disconnect() {
        val previous = client
        client = null
        network = null
        previous?.abort()
    }

    /** A failed local write must not automatically destroy a healthy camera connection.
     * Ping consumes a complete PTP response; an interrupted transfer fails and is disconnected.
     */
    fun recoverAfterFailure() {
        val current = client ?: return
        try { current.ping() } catch (_: Exception) {
            if (client === current) disconnect()
        }
    }

    override fun close() { disconnect(); worker.shutdownNow() }
}
