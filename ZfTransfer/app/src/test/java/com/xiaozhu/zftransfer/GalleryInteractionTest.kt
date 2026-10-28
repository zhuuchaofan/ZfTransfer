package com.xiaozhu.zftransfer

import org.junit.Assert.*
import org.junit.Test

class GalleryInteractionTest {
    @Test fun automaticHandshakeRetriesAreBoundedAndRespectStop() {
        val gate = AutoConnectGate()
        assertTrue(gate.take(1, "192.168.1.1"))
        repeat(2) {
            assertTrue(gate.retryAfterFailure(1, "192.168.1.1"))
            assertTrue(gate.take(1, "192.168.1.1"))
        }
        assertFalse(gate.retryAfterFailure(1, "192.168.1.1"))
        gate.stop(2)
        assertFalse(gate.retryAfterFailure(2, "192.168.1.1"))
    }
    @Test fun wifiProfileStringDoesNotExposePassword() {
        assertFalse(WifiProfile("sample", "test-only-password").toString().contains("test-only-password"))
    }
    @Test fun autoConnectAttemptsOncePerNetworkAndAddress() {
        val gate = AutoConnectGate()
        assertTrue(gate.take(1, "192.168.1.1"))
        assertFalse(gate.take(1, "192.168.1.1"))
        assertTrue(gate.take(2, "192.168.1.1"))
    }
    @Test fun explicitStopPreventsImmediateAutomaticReconnect() {
        val gate = AutoConnectGate()
        gate.stop(1)
        assertFalse(gate.take(1, "192.168.1.1"))
        gate.lost(1)
        assertTrue(gate.take(1, "192.168.1.1"))
    }
    @Test fun ordinaryInternetWifiIsNotTreatedAsCamera() {
        assertFalse(AutoConnectGate.isCameraCandidate(true, null, "192.168.1.1", "192.168.1.1", false))
        assertFalse(AutoConnectGate.isCameraCandidate(false, null, "10.0.0.1", "192.168.1.1", false))
        assertTrue(AutoConnectGate.isCameraCandidate(false, null, "192.168.1.1", "192.168.1.1", false))
        assertTrue(AutoConnectGate.isCameraCandidate(true, "\"NIKON_Z_f_sample\"", "10.0.0.1", "192.168.1.1", false))
        assertTrue(AutoConnectGate.isCameraCandidate(false, null, "10.0.0.1", "192.168.1.1", true))
    }
    @Test fun wifiProfileRejectsInvalidCredentialsWithoutTrimmingPassword() {
        assertTrue(WifiProfile("NIKON_example", "test-only-password").valid())
        assertFalse(WifiProfile("", "test-only-password").valid())
        assertFalse(WifiProfile("x".repeat(33), "test-only-password").valid())
        assertFalse(WifiProfile("x", "short").valid())
        assertFalse(WifiProfile("x", "密码不在日志中").valid())
        assertTrue(WifiProfile("x", " 123456 ").valid())
    }
    @Test fun cameraAddressRejectsWildcardAndNonUnicastEndpoints() {
        listOf("0.0.0.0", "0.1.2.3", "127.0.0.1", "224.0.0.1", "255.255.255.255", "192.168.1.256", "1.2.3", "1.2.x.4").forEach {
            assertFalse(it, CameraAddress.isUsable(it))
        }
    }
    @Test fun cameraAddressAcceptsRealLanGateways() {
        assertTrue(CameraAddress.isUsable("192.168.1.1"))
        assertTrue(CameraAddress.isUsable("10.0.0.1"))
        assertTrue(CameraAddress.isUsable("172.16.1.1"))
    }
    @Test fun dragSelectsContiguousRangeAndPreservesOtherSelections() {
        val gesture = RangeSelection(setOf(9), 2)
        assertEquals(setOf(2, 3, 4, 5, 9), gesture.at(5))
    }
    @Test fun reversingDragRestoresOriginalStateOutsideCurrentRange() {
        val gesture = RangeSelection(setOf(9), 2)
        gesture.at(7)
        assertEquals(setOf(2, 3, 9), gesture.at(3))
        assertEquals(setOf(0, 1, 2, 9), gesture.at(0))
    }
    @Test fun draggingFromSelectedItemDeselectsRangeReversibly() {
        val gesture = RangeSelection(setOf(1, 2, 3, 4, 9), 2)
        assertEquals(setOf(1, 9), gesture.at(4))
        assertEquals(setOf(1, 3, 4, 9), gesture.at(2))
    }
    @Test fun onlyPublishedMatchingJpegCanConfirmImport() {
        assertTrue(ImportEvidence.matches(100, 100, 0, "image/jpeg"))
        assertFalse(ImportEvidence.matches(100, 99, 0, "image/jpeg"))
        assertFalse(ImportEvidence.matches(100, 100, 1, "image/jpeg"))
        assertFalse(ImportEvidence.matches(0, 0, 0, "image/jpeg"))
        assertFalse(ImportEvidence.matches(100, 100, 0, null))
        assertFalse(ImportEvidence.matches(100, 100, 0, "image/png"))
    }
}
