package com.xiaozhu.zftransfer

import org.junit.Assert.*
import org.junit.Test

class BleWakeTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test fun handshakeReproducesValidStage3AndRejectsTamperedProof() {
        val id = hex("0102030405060708")
        val ts = hex("1122334455667788")
        val s1 = NikonHandshake.stage1(ts, id)
        assertEquals(17, s1.size)
        assertEquals(1, s1[0].toInt())
        assertEquals("0111223344556677880102030405060708", s1.hex())

        // Synthetic test vector computed with salt[0] (0x704066e4, 0x0433d552)
        val stage1 = NikonHandshake.stage1(ts, id)
        val hashMethod = NikonHandshake::class.java.getDeclaredMethod("hash", IntArray::class.java).apply { isAccessible = true }
        val proof = hashMethod.invoke(NikonHandshake, intArrayOf(0x704066e4, 0x0433d552, 0xaabbccdd.toInt(), 0xeeff0011.toInt(), 0x11223344, 0x55667788)) as IntArray
        val expectedReply = hashMethod.invoke(NikonHandshake, intArrayOf(0x704066e4, 0x0433d552, 0x11223344, 0x55667788, 0xaabbccdd.toInt(), 0xeeff0011.toInt())) as IntArray
        val stage2 = hex("02aabbccddeeff0011" + "%08x%08x".format(proof[0], proof[1]))
        val stage3 = hex("031122334455667788" + "%08x%08x".format(expectedReply[0], expectedReply[1]))
        assertEquals(stage3.hex(), NikonHandshake.stage3(stage1, stage2)!!.hex())

        val bad = stage2.copyOf().also { it[12] = (it[12] + 1).toByte() }
        assertNull(NikonHandshake.stage3(stage1, bad))
        assertNull(NikonHandshake.stage3(stage1, ByteArray(10)))
    }

    @Test fun snapBridgeHotspotUsesAndroidDevicePtpNameWhileOrdinaryHotspotKeepsZfTransfer() {
        assertEquals("Android Device", BleWakeRules.ptpClientNameFor("NIKON_Z_f_08408_SnapBridge"))
        assertEquals("Android Device", BleWakeRules.ptpClientNameFor("\"NIKON_Z_f_08408_SnapBridge\""))
        assertEquals("Android Device", BleWakeRules.ptpClientNameFor("MyCamera_snapbridge"))
        assertEquals("ZfTransfer", BleWakeRules.ptpClientNameFor("NIKON_Z_f_08408"))
        assertEquals("ZfTransfer", BleWakeRules.ptpClientNameFor(null))
        assertEquals("ZfTransfer", BleWakeRules.ptpClientNameFor("<unknown ssid>"))
    }

    @Test fun matchesWokenHotspotBySnapBridgeKeywordOrSavedCameraPrefix() {
        val scans = listOf(
            "Home_WiFi_5G" to -40,
            "NIKON_Z_f_08408_SnapBridge" to -55,
            "Other_SnapBridge" to -75,
        )
        assertEquals("NIKON_Z_f_08408_SnapBridge", BleWakeRules.matchHotspotSsid(scans, emptySet()))

        val prefixScans = listOf(
            "Home_WiFi" to -40,
            "MY_ZF_CAM_2" to -50,
        )
        assertEquals("MY_ZF_CAM_2", BleWakeRules.matchHotspotSsid(prefixScans, setOf("MY_ZF_CAM_SnapBridge")))
        assertNull(BleWakeRules.matchHotspotSsid(listOf("Home_WiFi" to -40), setOf("NIKON_Z_f")))
    }

    @Test fun fallbackWakeSsidAppendsSnapBridgeOnce() {
        assertEquals("NIKON_Z_f_08408_SnapBridge", BleWakeRules.fallbackWakeSsid("NIKON_Z_f_08408", null))
        assertEquals("NIKON_Z_f_08408_SnapBridge", BleWakeRules.fallbackWakeSsid("NIKON_Z_f_08408_SnapBridge", null))
        assertEquals("Z_f_8008408_SnapBridge", BleWakeRules.fallbackWakeSsid(null, "Z_f_8008408"))
        assertNull(BleWakeRules.fallbackWakeSsid(null, null))
    }

    @Test fun matchesAdvertisedManufacturerPrefixAgainstFirstFourBytesOfIdentity() {
        val identity = hex("a1b2c3d4e5f60718")
        assertTrue(BleWakeRules.matchesIdentityPrefix(hex("a1b2c3d40011"), identity))
        assertFalse(BleWakeRules.matchesIdentityPrefix(hex("a1b2c3ff0011"), identity))
        assertFalse(BleWakeRules.matchesIdentityPrefix(hex("a1b2"), identity))
        assertFalse(BleWakeRules.matchesIdentityPrefix(null, identity))
    }

    @Test fun recognizesNikonCameraInPairingOrClassicDiscovery() {
        assertTrue(BleWakeRules.looksLikeNikonCamera("Z_f_8008408"))
        assertTrue(BleWakeRules.looksLikeNikonCamera("NIKON Z f"))
        assertTrue(BleWakeRules.looksLikeNikonCamera(null, hasNikonService = true))
        assertTrue(BleWakeRules.looksLikeNikonCamera(null, hasNikonManufacturer = true))
        assertFalse(BleWakeRules.looksLikeNikonCamera("Pixel 8"))
        assertFalse(BleWakeRules.looksLikeNikonCamera(null))
    }

    @Test fun identityHexParsingAndFormattingRoundTrips() {
        val raw = hex("0123456789abcdef")
        val formatted = BleWakeRules.formatIdentityHex(raw)
        assertEquals("0123456789abcdef", formatted)
        assertArrayEquals(raw, BleWakeRules.parseIdentityHex(formatted))
        assertNull(BleWakeRules.parseIdentityHex("short"))
        assertNull(BleWakeRules.parseIdentityHex("0123456789abcdeg"))
    }

    @Test fun selectorAcceptsSnapBridgeHotspotEvenWithCustomPrefix() {
        val net = CameraNetworkHint(5, "192.168.1.1", "\"CustomCam_SnapBridge\"", false, false)
        assertEquals(net, CameraNetworkSelector.select(listOf(net), "", "192.168.1.1"))
    }
}

