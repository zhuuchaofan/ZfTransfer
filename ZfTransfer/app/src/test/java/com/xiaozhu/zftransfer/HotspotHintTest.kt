package com.xiaozhu.zftransfer

import org.junit.Assert.*
import org.junit.Test

class HotspotHintTest {
    @Test fun aFailedWifiRequestTellsTheUserThatTheCameraSwitchesItsHotspotOffAfterADisconnect() {
        val issue = UserIssues.wifiUnavailable()
        assertTrue(issue.message.contains("自动关闭"))
        assertTrue(issue.message.contains("重新开启"))
    }
    @Test fun theFailureStillOffersTheWifiSettingsAsRecovery() {
        assertEquals(Recovery.WIFI_SETTINGS, UserIssues.wifiUnavailable().recovery)
    }
}
