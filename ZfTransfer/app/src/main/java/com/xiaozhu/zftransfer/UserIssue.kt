package com.xiaozhu.zftransfer

import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException

enum class UserOperation { CONNECT, READ_PHOTOS, IMPORT, PREVIEW, WIFI }
enum class Recovery { WIFI_SETTINGS, APP_SETTINGS, STORAGE_SETTINGS, NONE }
data class UserIssue(val title: String, val message: String, val recovery: Recovery)

/** Raw provider/socket messages belong in diagnostics, never in user-facing copy. */
object UserIssues {
    fun wifiUnavailable() = UserIssue("未能连接相机 Wi-Fi", "请先确认相机的 Wi-Fi 已开启：相机在断开连接后会自动关闭热点，需要在相机上重新开启。再检查名称和密码是否正确，并允许系统连接请求。也可以手动连接相机 Wi-Fi 后返回应用。", Recovery.WIFI_SETTINGS)

    fun from(error: Throwable, operation: UserOperation): UserIssue {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        val detail = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
        return when {
            "请连接原来的相机" in detail -> UserIssue("请连接原来的相机", "未完成任务属于另一台相机。请连接原相机后重试，或重新选择照片开始新任务。", Recovery.WIFI_SETTINGS)
            "相机照片已变化" in detail || "剩余任务已变化" in detail -> UserIssue("照片信息发生变化", "为避免下载错误照片，本次重试已停止。请重新浏览相机并选择需要的照片。", Recovery.NONE)
            "无法保存传输任务记录" in detail || "无法保存导入记录" in detail -> UserIssue("无法保存传输记录", "请检查手机存储空间。已发布到相册的照片仍保留，但可能无法确认导入历史，请先在相册核对。", Recovery.STORAGE_SETTINGS)
            chain.any { it is SecurityException } -> UserIssue("需要访问权限", "请在系统设置中检查本应用的相关权限。连接相机 Wi-Fi 也可以在系统设置中手动完成。", Recovery.APP_SETTINGS)
            "enospc" in detail || "no space left" in detail || "disk full" in detail -> UserIssue("手机存储空间不足", "请清理手机空间后重试。已经成功导入的照片仍保留，不需要全部重新导入。", Recovery.STORAGE_SETTINGS)
            "文件大小不一致" in detail || "文件不完整" in detail || "ended early" in detail || "without an end data" in detail || "disconnected during transfer" in detail -> UserIssue("照片没有完整传输", "本次照片未能完整接收，不会标记为已导入。请确认相机连接后，重新选择未完成的照片。", Recovery.WIFI_SETTINGS)
            "无法创建相册" in detail || "无法写入相册" in detail || "相册文件发布失败" in detail -> UserIssue("无法保存到手机相册", "请检查手机存储空间和系统相册是否可用。已经成功保存的照片仍保留。", Recovery.STORAGE_SETTINGS)
            "预览缓存" in detail -> UserIssue("高清预览暂时无法保存", "请检查手机剩余空间后重新加载。临时预览不会写入相册，也不会改变已导入记录。", Recovery.STORAGE_SETTINGS)
            chain.any { it is SocketTimeoutException } || "timed out" in detail || "timeout" in detail -> UserIssue("等待相机响应超时", "请保持相机开机并连接相机 Wi-Fi，然后重试。传输失败前已经成功导入的照片仍保留。", Recovery.WIFI_SETTINGS)
            chain.any { it is ConnectException } || "econnrefused" in detail || "connection refused" in detail -> UserIssue("未找到相机服务", "当前网络未接受相机连接。请确认手机连接的是相机热点；若已确认，可重新开启相机 Wi-Fi，并退出其他占用相机的应用后再试。", Recovery.WIFI_SETTINGS)
            "wi-fi 尚未就绪" in detail || "请先连接相机" in detail || "not connected to the camera" in detail -> UserIssue("还没有连接到相机", "请先连接相机 Wi-Fi，等待手机获取网络地址。返回应用后会自动识别，也可以点击相机状态重试。", Recovery.WIFI_SETTINGS)
            "camera rejected" in detail || "ptp error" in detail || "ptp/ip" in detail -> UserIssue("相机暂时无法完成操作", "请确认相机处于智能设备连接模式，并退出其他连接应用。必要时重新开启相机 Wi-Fi 后再试。", Recovery.WIFI_SETTINGS)
            chain.any { it is SocketException } || "econnaborted" in detail || "disconnected" in detail || "broken pipe" in detail -> UserIssue("相机连接已中断", "请检查手机是否仍连接相机热点。重新连接后，可继续选择未完成的照片；已成功导入的照片仍保留。", Recovery.WIFI_SETTINGS)
            operation == UserOperation.WIFI -> wifiUnavailable()
            else -> UserIssue(when (operation) {
                UserOperation.CONNECT -> "暂时无法连接相机"
                UserOperation.READ_PHOTOS -> "暂时无法读取照片"
                UserOperation.IMPORT -> "本次照片未能全部导入"
                UserOperation.PREVIEW -> "暂时无法加载高清预览"
                UserOperation.WIFI -> "未能连接相机 Wi-Fi"
            }, "本次操作没有正常完成，已成功导入的照片仍保留。请检查连接和手机空间后重试。", Recovery.NONE)
        }
    }
}
