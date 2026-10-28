package com.xiaozhu.zftransfer

import android.graphics.BitmapFactory
import android.os.SystemClock
import io.github.noisetta.nikonpull.ptp.CameraObject
import io.github.noisetta.nikonpull.ptp.PtpException
import io.github.noisetta.nikonpull.ptp.PtpIpClient

/** Diagnostic reads only: no gallery, cache or import-record writes. Use the serial command lane. */
object PreviewProbe {
    fun measure(client: PtpIpClient, photo: CameraObject, log: (String) -> Unit): String {
        val rows = mutableListOf(photo.filename, "原图：${"%.2f".format(photo.size / 1048576.0)} MB（未下载）")
        val info = client.getDeviceInfo()
        log("Preview probe advertised: large=${0x90c4 in info.operationsSupported}; fhd=${0x920f in info.operationsSupported}")
        val variants: List<Pair<String, () -> ByteArray>> = listOf(
            "小缩略图" to { client.getThumbnail(photo.handle) },
            "大缩略图" to { client.getNikonPreview(photo.handle, false) },
            "全高清候选" to { client.getNikonPreview(photo.handle, true) },
        )
        for ((label, read) in variants) {
            val started = SystemClock.elapsedRealtime()
            try {
                val bytes = read()
                val elapsed = SystemClock.elapsedRealtime() - started
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val dimensions = if (bounds.outWidth > 0 && bounds.outHeight > 0) "${bounds.outWidth} × ${bounds.outHeight}" else "非可识别图片"
                val row = "$label：$dimensions · ${"%.1f".format(bytes.size / 1024.0)} KB · $elapsed 毫秒"
                rows.add(row); log("Preview probe $label bytes=${bytes.size} width=${bounds.outWidth} height=${bounds.outHeight} elapsedMs=$elapsed")
            } catch (e: PtpException) {
                // Only a fully consumed rejection is safe to follow with another command.
                if (!e.message.orEmpty().startsWith("Camera returned PTP error")) throw e
                rows.add("$label：相机拒绝此读取")
                log("Preview probe $label rejected: ${e.message}")
            }
        }
        return rows.joinToString("\n\n") + "\n\n仅作预览接口比较，未写入相册，也未改变已导入记录。"
    }
}
