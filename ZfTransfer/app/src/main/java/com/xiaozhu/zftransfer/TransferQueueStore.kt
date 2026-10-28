package com.xiaozhu.zftransfer

import android.content.SharedPreferences

/** Separate preferences preserve compatibility with existing saved_ import records. */
class TransferQueueStore(private val prefs: SharedPreferences) {
    fun load(): TransferQueue = runCatching {
        prefs.getString("pending", null)?.let(TransferQueueCodec::decode) ?: TransferQueue()
    }.logFailure("Transfer queue unreadable, starting empty").getOrElse { TransferQueue() }

    fun save(queue: TransferQueue) {
        val edit = prefs.edit()
        if (queue.identity == null || queue.remaining.isEmpty()) edit.remove("pending")
        else edit.putString("pending", TransferQueueCodec.encode(queue))
        check(edit.commit()) { "无法保存传输任务记录" }
    }
}
