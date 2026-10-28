package com.xiaozhu.zftransfer

import io.github.noisetta.nikonpull.ptp.CameraObject

/** Pure batch policy, exercised without Android or a physical camera. */
class TransferRunner(private val queue: TransferQueue, private val persist: () -> Unit) {
    fun run(identity: String?, items: List<CameraObject>, retry: Boolean, skipExisting: Boolean,
        checkActive: () -> Unit, currentPhoto: (CameraObject) -> CameraObject,
        exists: (CameraObject) -> Boolean, save: (Int, CameraObject) -> Unit,
        completed: (CameraObject, Boolean) -> Unit): Pair<Int, Int> {
        if (retry) {
            check(queue.canRetry(identity)) { "请连接原来的相机后重试" }
            check(items == queue.remaining) { "剩余任务已变化，请重新重试" }
        }
        else { queue.replace(identity, items); persist() }
        var done = 0; var skipped = 0
        for ((index, obj) in items.withIndex()) {
            checkActive()
            if (retry) check(queue.matches(obj, currentPhoto(obj))) { "相机照片已变化，请重新浏览并选择照片" }
            if (skipExisting && exists(obj)) skipped++ else { save(index, obj); done++ }
            queue.completed(obj); persist()
            completed(obj, exists(obj))
        }
        return done to skipped
    }
}
