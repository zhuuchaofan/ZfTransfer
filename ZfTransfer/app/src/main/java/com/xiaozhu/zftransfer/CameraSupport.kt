package com.xiaozhu.zftransfer

import io.github.noisetta.nikonpull.ptp.CameraDeviceInfo

/** What the app knows about a connected camera. It is built for the Nikon Zf; other Nikon bodies are tried, other brands are refused. */
object CameraSupport {
    enum class Level { ZF, OTHER_NIKON, NOT_NIKON }

    private fun squash(value: String) = value.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }

    /** A camera that reports no manufacturer is given the benefit of the doubt. */
    fun levelOf(manufacturer: String, model: String): Level = when {
        manufacturer.isNotBlank() && !squash(manufacturer).contains("nikon") -> Level.NOT_NIKON
        squash(model).let { it == "zf" || it == "nikonzf" } -> Level.ZF
        else -> Level.OTHER_NIKON
    }
    fun levelOf(info: CameraDeviceInfo) = levelOf(info.manufacturer, info.model)

    fun refusal(info: CameraDeviceInfo) =
        "这不是尼康相机（${listOf(info.manufacturer, info.model).filter { it.isNotBlank() }.joinToString(" ")}），本应用只支持尼康相机。"
    const val OTHER_NIKON_NOTE = "本应用主要为 Nikon Zf 适配，其他尼康机型可能有部分功能不可用。"
}
