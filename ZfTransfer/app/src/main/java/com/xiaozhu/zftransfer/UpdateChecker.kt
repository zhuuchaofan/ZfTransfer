package com.xiaozhu.zftransfer

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Release tags look like v0.5.11; a "-test" suffix on the installed version is ignored. */
data class AppVersion(val parts: List<Int>) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val diff = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }
    override fun toString() = parts.joinToString(".")

    companion object {
        private val pattern = Regex("^v?(\\d+(?:\\.\\d+)*)(?:-test)?$")
        fun parse(text: String): AppVersion? {
            val numbers = pattern.matchEntire(text.trim())?.groupValues?.get(1)?.split('.')?.map { it.toIntOrNull() } ?: return null
            return if (numbers.any { it == null }) null else AppVersion(numbers.filterNotNull())
        }
    }
}

sealed interface UpdateResult {
    data class Available(val version: AppVersion) : UpdateResult
    object UpToDate : UpdateResult
    data class Failed(val reason: String) : UpdateResult
}

/** Reads the public release list; every published release counts, pre-releases included. */
object ReleaseFeed {
    const val LIST_URL = "https://api.github.com/repos/zhuuchaofan/ZfTransfer/releases?per_page=10"
    const val PAGE_URL = "https://github.com/zhuuchaofan/ZfTransfer/releases/tag/"
    private val tagName = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")

    fun newest(json: String): AppVersion? = tagName.findAll(json).mapNotNull { AppVersion.parse(it.groupValues[1]) }.maxOrNull()

    fun compare(current: AppVersion, json: String): UpdateResult {
        val latest = newest(json) ?: return UpdateResult.Failed("没有读到发布信息")
        return if (latest > current) UpdateResult.Available(latest) else UpdateResult.UpToDate
    }
    fun pageFor(version: AppVersion) = "${PAGE_URL}v$version"
}

/** Only runs when the user asks. Uses its own thread so the camera command lane is never blocked. */
class UpdateChecker(private val userAgent: String) {
    private val worker = Executors.newSingleThreadExecutor()

    fun check(current: AppVersion, onResult: (UpdateResult) -> Unit) { worker.execute { onResult(fetch(current)) } }

    private fun fetch(current: AppVersion): UpdateResult = try {
        val connection = URL(ReleaseFeed.LIST_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS; connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", userAgent)
            when (val code = connection.responseCode) {
                200 -> ReleaseFeed.compare(current, connection.inputStream.bufferedReader().use { it.readText() })
                403, 429 -> UpdateResult.Failed("GitHub 暂时限制了查询，请稍后再试")
                else -> UpdateResult.Failed("GitHub 返回了 $code")
            }
        } finally { connection.disconnect() }
    } catch (e: IOException) {
        UpdateResult.Failed("无法连接 GitHub，请确认手机已连接互联网；若正连着相机热点，需先使用有网络的 Wi-Fi 或移动数据")
    }

    fun close() { worker.shutdownNow() }

    private companion object { const val TIMEOUT_MS = 8000 }
}
