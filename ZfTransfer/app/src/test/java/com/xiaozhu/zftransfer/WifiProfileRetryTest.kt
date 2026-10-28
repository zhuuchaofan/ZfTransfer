package com.xiaozhu.zftransfer

import org.junit.Assert.*
import org.junit.Test

class WifiProfileRetryTest {
    @Test fun firstRequestIsAlwaysDue() {
        assertTrue(WifiProfileRetry(60_000).due(0))
    }
    @Test fun afterAnAttemptNothingIsDueUntilTheCameraWifiWasSeen() {
        val retry = WifiProfileRetry(60_000).apply { attempted = true; requested(0) }
        assertFalse(retry.due(120_000))
    }
    @Test fun retryWaitsForTheIntervalOnceTheCameraWifiWasSeen() {
        val retry = WifiProfileRetry(60_000).apply { attempted = true; requested(1_000); networkAvailable() }
        assertFalse(retry.due(60_999))
        assertTrue(retry.due(61_000))
    }
    @Test fun resettingAttemptedMakesTheNextRequestDueAgain() {
        val retry = WifiProfileRetry(60_000).apply { attempted = true; requested(1_000) }
        retry.attempted = false
        assertTrue(retry.due(1_001))
    }
}
