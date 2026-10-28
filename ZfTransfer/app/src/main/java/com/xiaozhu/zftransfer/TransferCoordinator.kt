package com.xiaozhu.zftransfer

import io.github.noisetta.nikonpull.ptp.CameraObject
import io.github.noisetta.nikonpull.ptp.PtpIpClient

/** Runs on CameraSession's serial worker. Never touches views. */
class TransferCoordinator(private val imports: ImportRepository, private val store: TransferQueueStore) {
    val queue = store.load()
    fun run(client: PtpIpClient, items: List<CameraObject>, skipExisting: Boolean, retry: Boolean,
        checkActive: () -> Unit, progress: (Int, CameraObject, Int) -> Unit, completed: (CameraObject, Boolean) -> Unit): Pair<Int, Int> {
        return TransferRunner(queue) { store.save(queue) }.run(imports.identity, items, retry, skipExisting,
            checkActive, { client.getObjectInfo(it.handle) }, { imports.verifiedUri(it) != null },
            save = { index, obj ->
                progress(index, obj, 0)
                imports.save(obj, { progress(index, obj, it) }) { out, update ->
                    client.downloadObject(obj.handle, out) { update(it.bytesTransferred) }
                }
            }, completed = completed)
    }
}
