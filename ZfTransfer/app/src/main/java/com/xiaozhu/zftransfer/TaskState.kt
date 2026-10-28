package com.xiaozhu.zftransfer

/** Immutable UI snapshot; the command lane still uses its atomic admission lock. */
data class TaskState(val operation: UserOperation? = null, val transfer: ImportProgress? = null) {
    val running get() = operation != null
    val percentage get() = transfer?.percentage ?: 0
    fun previewImportLabel(handle: Long, imported: Boolean): String = when {
        running && transfer?.handle == handle -> "正在导入 ${transfer.percentage}%"
        running && transfer != null -> "其他照片导入 ${transfer.percentage}%"
        running -> "正在处理…"
        imported -> "再次导入"
        else -> "导入原图"
    }
    fun showRemaining(remaining: Int) = !running && remaining > 0
}
data class ImportProgress(val handle: Long, val percentage: Int) {
    init { require(percentage in 0..100) }
}

/** Each observer keeps its own cursor; reading never consumes another screen's events. */
class ImportCompletions {
    data class Event(val sequence: Long, val handle: Long)
    private var sequence = 0L
    private val events = mutableListOf<Event>()
    fun beginBatch() { events.clear() }
    fun record(handle: Long) { events.add(Event(++sequence, handle)) }
    fun since(cursor: Long): List<Event> = events.filter { it.sequence > cursor }
}
