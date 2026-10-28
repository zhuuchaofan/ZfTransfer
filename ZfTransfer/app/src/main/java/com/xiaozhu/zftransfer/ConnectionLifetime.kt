package com.xiaozhu.zftransfer

/** User intent survives screen recreation, but never overrides an explicit disconnect. */
class ConnectionLifetime {
    @Volatile var desired = false
        private set
    private var failures = 0
    private var nextAttempt = 0L
    @Synchronized fun request() { if (!desired) { desired = true; failures = 0; nextAttempt = 0 } }
    @Synchronized fun stop() { desired = false; failures = 0; nextAttempt = Long.MAX_VALUE }
    @Synchronized fun connected() { failures = 0; nextAttempt = 0 }
    @Synchronized fun failed(now: Long) {
        if (!desired) return
        val delay = (5000L shl failures.coerceAtMost(4)).coerceAtMost(60000L)
        failures = (failures + 1).coerceAtMost(5)
        nextAttempt = now + delay
    }
    @Synchronized fun canReconnect(now: Long) = desired && now >= nextAttempt
}
