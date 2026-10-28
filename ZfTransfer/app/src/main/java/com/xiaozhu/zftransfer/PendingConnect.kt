package com.xiaozhu.zftransfer

import android.net.Network
import android.os.Handler

/** One delayed connect waiting for the camera endpoint to settle; a new schedule replaces the old one. */
class PendingConnect(private val handler: Handler) {
    private var task: Runnable? = null
    var network: Network? = null
        private set
    val active get() = task != null

    fun schedule(target: Network, delayMs: Long, action: () -> Unit) {
        cancel()
        network = target
        task = Runnable { clear(); action() }.also { handler.postDelayed(it, delayMs) }
    }
    fun cancel() {
        task?.let { handler.removeCallbacks(it) }
        clear()
    }
    private fun clear() { task = null; network = null }
}
