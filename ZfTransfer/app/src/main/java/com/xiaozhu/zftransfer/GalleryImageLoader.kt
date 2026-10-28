package com.xiaozhu.zftransfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Handler
import android.os.Looper
import io.github.noisetta.nikonpull.ptp.CameraObject
import java.io.File
import java.util.concurrent.Executors

/** Page-scoped disk/decode worker. Camera I/O stays on the runtime command lane. */
class GalleryImageLoader(context: Context, private val runtime: ConnectionRuntime,
    private val changed: () -> Unit) : AutoCloseable {
    data class Preview(val bitmap: Bitmap, val original: Boolean, val quality: String)
    private val resolver = context.applicationContext.contentResolver
    private val cacheDir = context.cacheDir
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val pending = mutableSetOf<String>() // Accessed only on the main thread.
    @Volatile private var closed = false
    val originals = OriginalPreviewCache(File(cacheDir, "original_previews"))
    private fun file(handle: Long, session: String) = File(cacheDir, "thumb_${session}_$handle.jpg")
    fun key(obj: CameraObject) = runtime.imports.key(obj)
        ?: "${runtime.imageSession}|${obj.storageId}|${obj.handle}|${obj.filename}|${obj.captureDate}|${obj.size}"
    private fun decode(source: ImageDecoder.Source, maxEdge: Int): Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val scale = minOf(1.0, maxEdge.toDouble() / maxOf(info.size.width, info.size.height))
        decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    /** Memory hits only on the UI thread; disk misses are deduplicated and decoded asynchronously. */
    fun thumbnail(handle: Long): Bitmap? {
        runtime.thumbs.get(handle)?.let { return it }
        if (closed) return null
        val session = runtime.imageSession
        val request = "$session|$handle"
        if (pending.add(request)) worker.execute {
            val bitmap = runCatching {
                file(handle, session).takeIf { it.isFile }?.let { decode(ImageDecoder.createSource(it), 1024) }
            }.getOrNull()
            main.post {
                pending.remove(request)
                if (bitmap != null) {
                    if (!closed && session == runtime.imageSession) {
                        if (runtime.thumbs.get(handle) == null) { runtime.thumbs.put(handle, bitmap); changed() }
                        else bitmap.recycle()
                    } else bitmap.recycle()
                }
            }
        }
        return null
    }
    fun upgradeGrid(obj: CameraObject, recordKey: String?, session: String) {
        if (closed) return
        worker.execute {
            if (closed || runtime.imageSession != session) return@execute
            val uri = runtime.imports.verifiedUri(obj, recordKey) ?: return@execute
            val bitmap = runCatching { decode(ImageDecoder.createSource(resolver, uri), 512) }.getOrNull() ?: return@execute
            if (closed || runtime.imageSession != session) { bitmap.recycle(); return@execute }
            runCatching { file(obj.handle, session).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) } }
            main.post { publishThumbnail(obj.handle, session, bitmap) }
        }
    }
    private fun publishThumbnail(handle: Long, session: String, bitmap: Bitmap) {
        if (!closed && session == runtime.imageSession) { runtime.thumbs.put(handle, bitmap); changed() }
        else bitmap.recycle()
    }
    fun loadPreview(obj: CameraObject, current: () -> Boolean, ready: (Preview?) -> Unit) {
        loadPreview(obj, current, ready, allowFetch = true)
    }
    private fun loadPreview(obj: CameraObject, current: () -> Boolean, ready: (Preview?) -> Unit, allowFetch: Boolean) {
        if (closed) return
        val session = runtime.imageSession
        val key = key(obj)
        val recordKey = runtime.imports.key(obj)
        worker.execute {
            if (closed || !current()) return@execute
            val uri = runtime.imports.verifiedUri(obj, recordKey)
            val original = if (uri == null) originals.find(key, obj.size) else null
            val medium = if (uri == null && original == null) runtime.mediumPreviews.findCompleted(key) else null
            val source = when {
                uri != null -> ImageDecoder.createSource(resolver, uri)
                original != null -> ImageDecoder.createSource(original)
                medium != null -> ImageDecoder.createSource(medium)
                else -> null
            }
            val bitmap = source?.let { runCatching { decode(it, 2560) }
                .onFailure { runtime.log("Preview decode failed: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull() }
            if (bitmap == null && medium != null) medium.delete()
            val grid = bitmap?.takeIf { maxOf(it.width, it.height) > 512 }?.let {
                val scale = 512.0 / maxOf(it.width, it.height)
                Bitmap.createScaledBitmap(it, maxOf(1, (it.width * scale).toInt()), maxOf(1, (it.height * scale).toInt()), true)
            }
            if (grid != null && !closed && session == runtime.imageSession) runCatching {
                file(obj.handle, session).outputStream().use { grid.compress(Bitmap.CompressFormat.JPEG, 94, it) }
            }
            main.post {
                if (grid != null) publishThumbnail(obj.handle, session, grid)
                if (closed || !current()) { bitmap?.recycle(); return@post }
                if (bitmap != null) ready(Preview(bitmap, uri != null || original != null, when {
                    uri != null -> "本地原图预览 · 双击或双指缩放"
                    original != null -> "原图预览 · 双击或双指缩放"
                    else -> "清晰预览 ${bitmap.width} × ${bitmap.height} · 双击或双指缩放"
                })) else if (allowFetch) {
                    runtime.requestMediumPreview(obj, key, { !closed && current() && session == runtime.imageSession }) { result ->
                        if (!closed && current()) {
                            if (result != null) loadPreview(obj, current, ready, allowFetch = false) else ready(null)
                        }
                    }
                } else ready(null)
            }
        }
    }
    override fun close() { closed = true; pending.clear(); worker.shutdownNow() }
}
