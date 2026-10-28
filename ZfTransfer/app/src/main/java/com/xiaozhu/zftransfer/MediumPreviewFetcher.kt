package com.xiaozhu.zftransfer

import android.graphics.BitmapFactory
import io.github.noisetta.nikonpull.ptp.CameraObject
import io.github.noisetta.nikonpull.ptp.PtpResponseException
import java.io.File

/** Fetches the camera's built-in medium preview on the shared command lane, caching usable results. */
class MediumPreviewFetcher(
    val cache: OriginalPreviewCache,
    private val session: CameraSession,
    private val log: (String) -> Unit,
    private val onSessionLost: () -> Unit,
    private val ui: (() -> Unit) -> Unit,
) {
    fun request(photo: CameraObject, key: String, current: () -> Boolean, ready: (File?) -> Unit) {
        session.worker.execute {
            if (!current()) return@execute
            var result: File? = null
            val c = session.client
            try {
                result = cache.findCompleted(key)
                if (result == null && c != null) {
                    for (fullHd in listOf(true, false)) {
                        if (!current() || session.client !== c) break
                        val bytes = try { c.getNikonPreview(photo.handle, fullHd) } catch (e: PtpResponseException) {
                            log("Medium preview rejected fullHd=$fullHd: ${e.message}")
                            continue
                        }
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        if (MediumPreviewPolicy.usable(bounds.outWidth, bounds.outHeight, bytes.size)) {
                            result = cache.store(key, bytes.size.toLong()) { it.write(bytes) }
                            log("Medium preview bytes=${bytes.size} width=${bounds.outWidth} height=${bounds.outHeight}")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                log("Medium preview failed: ${e.stackTraceToString()}")
                session.recoverAfterFailure()
                onSessionLost()
            }
            val completed = result
            ui { if (current()) ready(completed) }
        }
    }
}
