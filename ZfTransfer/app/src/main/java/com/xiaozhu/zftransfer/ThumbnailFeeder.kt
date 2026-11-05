package com.xiaozhu.zftransfer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import io.github.noisetta.nikonpull.ptp.PtpIpClient
import io.github.noisetta.nikonpull.ptp.PtpResponseException
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

object ThumbnailCodec {
    private const val MAX_EDGE = 1024

    fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            var sample = 1
            while (bounds.outWidth / sample > MAX_EDGE || bounds.outHeight / sample > MAX_EDGE) sample *= 2
            inSampleSize = sample
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** The camera's thumbnails carry no orientation of their own; the photo's header says how it was held. */
    fun upright(bitmap: Bitmap, tag: Int): Bitmap {
        val turn = ExifOrientation.degrees(tag); val flip = ExifOrientation.mirrored(tag)
        if (turn == 0 && !flip) return bitmap
        val matrix = android.graphics.Matrix().apply { if (flip) postScale(-1f, 1f); postRotate(turn.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun encode(bitmap: Bitmap): ByteArray = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
}

/** The EXIF orientation tag (1 to 8) as a clockwise turn in degrees and a horizontal mirror applied before it. */
object ExifOrientation {
    /**
     * Orientation tag (1 to 8) from the start of a JPEG or TIFF/NEF file, or 1 when the header is too short or has none.
     * Only IFD0 is read, which sits right behind the Exif header.
     */
    fun read(head: ByteArray): Int {
        val capI = 'I'.code.toByte(); val capM = 'M'.code.toByte()
        var tiff = -1
        if (head.size >= 12 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()) {
            var p = 2
            while (p + 4 <= head.size && head[p] == 0xFF.toByte()) {
                val marker = head[p + 1].toInt() and 0xFF
                val length = ((head[p + 2].toInt() and 0xFF) shl 8) or (head[p + 3].toInt() and 0xFF)
                if (marker == 0xE1 && p + 10 <= head.size && head[p + 4] == 'E'.code.toByte() && head[p + 5] == 'x'.code.toByte()) { tiff = p + 10; break }
                if (marker == 0xDA || length < 2) break
                p += 2 + length
            }
        } else if (head.size >= 8 && ((head[0] == capI && head[1] == capI) || (head[0] == capM && head[1] == capM))) tiff = 0
        if (tiff < 0 || tiff + 8 > head.size) return 1
        val little = head[tiff] == capI
        fun u16(at: Int): Int = if (at < 0 || at + 2 > head.size) -1 else if (little) (head[at].toInt() and 0xFF) or ((head[at + 1].toInt() and 0xFF) shl 8) else ((head[at].toInt() and 0xFF) shl 8) or (head[at + 1].toInt() and 0xFF)
        fun u32(at: Int): Long = if (at + 4 > head.size) -1L else if (little) (u16(at).toLong() and 0xFFFF) or ((u16(at + 2).toLong() and 0xFFFF) shl 16) else ((u16(at).toLong() and 0xFFFF) shl 16) or (u16(at + 2).toLong() and 0xFFFF)
        val offset = u32(tiff + 4)
        if (offset < 0 || tiff + offset + 2 > head.size) return 1
        val ifd = tiff + offset.toInt()
        val count = u16(ifd)
        if (count <= 0) return 1
        for (i in 0 until count) {
            val entry = ifd + 2 + i * 12
            if (entry + 12 > head.size) return 1
            if (u16(entry) == 0x0112) return u16(entry + 8).takeIf { it in 1..8 } ?: 1
        }
        return 1
    }

    fun degrees(tag: Int) = when (tag) { 3, 4 -> 180; 5, 6 -> 90; 7, 8 -> 270; else -> 0 }
    fun mirrored(tag: Int) = tag == 2 || tag == 4 || tag == 5 || tag == 7
}

/**
 * Reads camera thumbnails in the background, what is on screen first. The work runs on the camera's
 * single command thread in small batches and steps aside while an import, preview or other task is busy,
 * so it never delays what the user asked for.
 */
class ThumbnailFeeder(
    private val session: CameraSession,
    private val thumbs: LruCache<Long, Bitmap>,
    private val fileFor: (handle: Long, imageSession: String) -> File,
    private val currentSession: () -> String,
    private val publish: (Map<Long, Bitmap>) -> Unit,
    private val log: (String) -> Unit,
) {
    /** Handles near the viewport; set from the main thread as the user scrolls. */
    @Volatile var focus: List<Long> = emptyList()
    @Volatile private var generation = 0
    private val unavailable = ConcurrentHashMap.newKeySet<Long>()

    /** Main thread. Starts over for a new catalog; any earlier run stops at its next step. */
    fun start(client: PtpIpClient, order: List<Long>, imageSession: String) {
        val run = ++generation
        unavailable.clear()
        focus = emptyList()
        schedule(0) { step(run, client, order, imageSession, 0) }
    }

    private fun schedule(delayMs: Long, work: () -> Unit) {
        try { session.worker.schedule(work, delayMs, TimeUnit.MILLISECONDS) } catch (_: RejectedExecutionException) { }
    }

    private fun alive(run: Int, client: PtpIpClient, imageSession: String) =
        run == generation && session.client === client && currentSession() == imageSession

    private fun have(handle: Long, imageSession: String) =
        handle in unavailable || thumbs.get(handle) != null || fileFor(handle, imageSession).exists()

    private fun step(run: Int, client: PtpIpClient, order: List<Long>, imageSession: String, from: Int) {
        if (!alive(run, client, imageSession)) return
        if (session.busy.get()) { schedule(BUSY_RETRY_MS) { step(run, client, order, imageSession, from) }; return }
        val batch = ThumbnailPlan.nextBatch(focus, order, from, BATCH) { have(it, imageSession) }
        if (batch.handles.isEmpty()) return
        val fetched = HashMap<Long, Bitmap>()
        for (handle in batch.handles) {
            if (!alive(run, client, imageSession)) return
            try {
                val bytes = client.getThumbnail(handle)
                val bitmap = ThumbnailCodec.decode(bytes)
                if (bitmap == null) unavailable.add(handle) else {
                    val tag = try { ExifOrientation.read(client.getObjectHead(handle)) } catch (e: PtpResponseException) { 1 }
                    val shown = ThumbnailCodec.upright(bitmap, tag)
                    // The cached copy is what gets reloaded later, so it is stored the right way up.
                    val stored = if (shown === bitmap) bytes else ThumbnailCodec.encode(shown)
                    runCatching { fileFor(handle, imageSession).writeBytes(stored) }.logFailure("Thumbnail cache write failed")
                    fetched[handle] = shown
                }
            } catch (e: PtpResponseException) {
                // The camera answered with an error and the exchange is complete: this file has no thumbnail.
                unavailable.add(handle)
            } catch (e: Exception) {
                log("Thumbnail reading stopped: ${e.javaClass.simpleName}: ${e.message}")
                publish(fetched)
                return
            }
        }
        publish(fetched)
        schedule(0) { step(run, client, order, imageSession, batch.next) }
    }

    private companion object {
        const val BATCH = 8
        const val BUSY_RETRY_MS = 250L
    }
}
