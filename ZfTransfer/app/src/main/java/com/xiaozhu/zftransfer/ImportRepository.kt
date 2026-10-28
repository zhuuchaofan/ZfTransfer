package com.xiaozhu.zftransfer

import android.content.ContentResolver
import android.content.ContentValues
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore
import io.github.noisetta.nikonpull.ptp.CameraObject
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Owns gallery publication and existing transfer record format. Cache is never import evidence. */
class ImportRepository(private val resolver: ContentResolver, private val prefs: SharedPreferences, private val log: (String) -> Unit) {
    @Volatile var identity: String? = null
    fun verifiedUri(obj: CameraObject, recordKey: String? = key(obj)): Uri? {
        val key = recordKey ?: return null
        val saved = prefs.getString(key, null)?.let(Uri::parse) ?: return null
        return runCatching {
            val valid = resolver.query(saved, arrayOf(MediaStore.Images.Media.SIZE, MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.MIME_TYPE), null, null, null)?.use {
                it.moveToFirst() && ImportEvidence.matches(obj.size, it.getLong(0), it.getInt(1), it.getString(2))
            } ?: false
            saved.takeIf { valid && (resolver.openFileDescriptor(saved, "r")?.use { it.statSize == obj.size } ?: false) }
        }.getOrNull()
    }
    fun key(obj: CameraObject): String? = identity?.takeIf { obj.captureDate.isNotBlank() && obj.size > 0 }?.let {
        val content = "$it|${obj.storageId}|${obj.handle}|${obj.filename}|${obj.captureDate}|${obj.size}"
        "saved_" + MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { b -> "%02x".format(b) }
    }

    fun save(obj: CameraObject, onProgress: (Int) -> Unit, download: (OutputStream, (Long) -> Unit) -> Unit) {
        val key = key(obj)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, obj.filename.replace(Regex("[\\\\/\\p{Cntrl}]"), "_"))
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ZfTransfer")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建相册文件")
        var published = false
        try {
            var count = 0L
            val raw = resolver.openOutputStream(uri, "w") ?: error("无法写入相册")
            val output = object : FilterOutputStream(raw) {
                override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
                override fun write(b: Int) { out.write(b); count++ }
            }
            output.use {
                var lastPercent = -1
                download(it) { bytes ->
                    val percentage = if (obj.size > 0) (bytes * 100 / obj.size).coerceIn(0, 100).toInt() else 0
                    if (percentage != lastPercent) { lastPercent = percentage; onProgress(percentage) }
                }
            }
            check(count == obj.size) { "文件大小不一致：$count / ${obj.size}，未保留不完整文件" }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1) {
                "相册文件发布失败"
            }
            published = true
            val record = prefs.edit().putString("import_uri_" + uri.lastPathSegment, uri.toString())
            if (key != null) record.putString(key, uri.toString())
            check(record.commit()) { "无法保存导入记录" }
            log("Saved original JPEG; bytes=$count")
        } finally {
            if (!published) resolver.delete(uri, null, null)
        }
    }
}
