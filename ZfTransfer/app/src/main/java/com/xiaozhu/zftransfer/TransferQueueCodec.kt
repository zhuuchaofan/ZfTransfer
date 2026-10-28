package com.xiaozhu.zftransfer

import io.github.noisetta.nikonpull.ptp.CameraObject
import java.io.*
import java.util.Base64

/** Versioned local task format. No credentials or image data. */
object TransferQueueCodec {
    fun encode(queue: TransferQueue): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(1); out.writeUTF(requireNotNull(queue.identity))
            val items = queue.remaining; require(items.size <= 10000); out.writeInt(items.size)
            items.forEach {
                out.writeLong(it.handle); out.writeLong(it.storageId); out.writeInt(it.format)
                out.writeLong(it.size); out.writeLong(it.thumbnailSize); out.writeUTF(it.filename); out.writeUTF(it.captureDate)
            }
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }
    fun decode(value: String): TransferQueue {
        require(value.length <= 8 * 1024 * 1024)
        return DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(value))).use { input ->
            require(input.readInt() == 1)
            val identity = input.readUTF(); require(identity.isNotBlank())
            val count = input.readInt(); require(count in 0..10000)
            val items = (0 until count).map {
                CameraObject(input.readLong(), input.readLong(), input.readInt(), input.readLong(), input.readLong(), input.readUTF(), input.readUTF())
            }
            require(input.available() == 0)
            TransferQueue().apply { replace(identity, items) }
        }
    }
}
