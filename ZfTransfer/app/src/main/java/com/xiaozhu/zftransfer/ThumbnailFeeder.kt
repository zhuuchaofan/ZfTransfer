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
                    runCatching { fileFor(handle, imageSession).writeBytes(bytes) }.logFailure("Thumbnail cache write failed")
                    fetched[handle] = bitmap
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
