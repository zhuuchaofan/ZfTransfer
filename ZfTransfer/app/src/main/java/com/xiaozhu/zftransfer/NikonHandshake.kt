package com.xiaozhu.zftransfer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * The 4-step handshake of Nikon's smart-device Bluetooth mode, written down from the reverse engineering in
 * nsg (hurui200320, AGPL-3.0), nikongeotag (rjmendez, GPL-3.0) and furble (gkoh, MIT), and checked against a
 * capture of the official app talking to a Zf. Experiment only.
 *
 * Every message is 17 bytes: a stage number, an 8-byte timestamp and 8 more bytes. In stage 1 those are the
 * controller's identity, in stage 2 and 3 a Blowfish based proof.
 */
object NikonHandshake {
    private val KEY = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xAA.toByte(), 0x55.toByte(), 0x11, 0x22, 0x33, 0x00)
    private val SALTS = arrayOf(
        intArrayOf(0x704066e4, 0x0433d552), intArrayOf(0xed4b8fac.toInt(), 0x15f7e47b), intArrayOf(0x24471f11, 0x8b5ea1fc.toInt()),
        intArrayOf(0x05960c31, 0x2b8c7f41), intArrayOf(0xfda588c1.toInt(), 0xeba8b1f3.toInt()), intArrayOf(0x99166056.toInt(), 0x1bd3d550),
        intArrayOf(0xcd32687f.toInt(), 0xa9e28a30.toInt()), intArrayOf(0x2a8fe834, 0xdec7ebf4.toInt()),
    )

    private fun words(bytes: ByteArray, from: Int): IntArray {
        val b = ByteBuffer.wrap(bytes, from, 8).order(ByteOrder.BIG_ENDIAN)
        return intArrayOf(b.getInt(), b.getInt())
    }

    /** CBC-MAC like chain over big-endian words with Blowfish in ECB mode. */
    private fun hash(blocks: IntArray): IntArray {
        val cipher = Cipher.getInstance("Blowfish/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY, "Blowfish")) }
        var left = 0x01020304; var right = 0x05060708
        for (i in blocks.indices step 2) {
            val input = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(blocks[i] xor left).putInt(blocks[i + 1] xor right).array()
            val out = ByteBuffer.wrap(cipher.update(input)).order(ByteOrder.BIG_ENDIAN)
            left = out.getInt(); right = out.getInt()
        }
        return intArrayOf(left, right)
    }

    /** Stage 1: the controller's timestamp and its 8-byte identity. */
    fun stage1(timestamp: ByteArray, identity: ByteArray): ByteArray {
        require(timestamp.size == 8 && identity.size == 8)
        return byteArrayOf(1) + timestamp + identity
    }

    /** Checks the camera's stage 2 against our stage 1 and returns the stage 3 reply, or null if the camera's proof is wrong. */
    fun stage3(stage1: ByteArray, stage2: ByteArray): ByteArray? {
        if (stage1.size != 17 || stage2.size != 17 || stage2[0].toInt() != 2) return null
        val ours = words(stage1, 1); val camera = words(stage2, 1); val proof = words(stage2, 9)
        val salt = SALTS.firstOrNull { s ->
            val h = hash(intArrayOf(s[0], s[1], camera[0], camera[1], ours[0], ours[1])); h[0] == proof[0] && h[1] == proof[1]
        } ?: return null
        val reply = hash(intArrayOf(salt[0], salt[1], ours[0], ours[1], camera[0], camera[1]))
        return byteArrayOf(3) + stage1.copyOfRange(1, 9) + ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(reply[0]).putInt(reply[1]).array()
    }
}
