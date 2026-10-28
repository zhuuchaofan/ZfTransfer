package com.xiaozhu.zftransfer

/** All coordinates are image-view content pixels, independent of Android gesture APIs. */
class PhotoZoomState {
    var zoom = 1f; private set
    var x = 0f; private set
    var y = 0f; private set
    var baseScale = 1f; private set
    private var width = 0f
    private var height = 0f
    private var imageWidth = 0f
    private var imageHeight = 0f
    val ready get() = width > 0 && height > 0 && imageWidth > 0 && imageHeight > 0

    fun fit(width: Float, height: Float, imageWidth: Float, imageHeight: Float) {
        this.width = width; this.height = height
        this.imageWidth = imageWidth; this.imageHeight = imageHeight
        baseScale = if (ready) minOf(width / imageWidth, height / imageHeight) else 1f
        reset()
    }
    fun reset() {
        zoom = 1f
        x = (width - imageWidth * baseScale) / 2
        y = (height - imageHeight * baseScale) / 2
        clamp()
    }
    fun scale(factor: Float, focusX: Float, focusY: Float, moveX: Float = 0f, moveY: Float = 0f) {
        if (!ready || !factor.isFinite() || factor <= 0 || !focusX.isFinite() || !focusY.isFinite() || !moveX.isFinite() || !moveY.isFinite()) return
        val next = (zoom * factor).coerceIn(1f, 5f)
        val ratio = next / zoom
        x = focusX - (focusX - x) * ratio + moveX
        y = focusY - (focusY - y) * ratio + moveY
        zoom = next
        clamp()
    }
    fun pan(dx: Float, dy: Float) {
        if (!ready || !dx.isFinite() || !dy.isFinite()) return
        x += dx; y += dy; clamp()
    }
    fun doubleTap(focusX: Float, focusY: Float) {
        if (zoom > 1.01f) reset() else scale(2f, focusX, focusY)
    }
    private fun clamp() {
        if (!ready) return
        val displayedWidth = imageWidth * baseScale * zoom
        val displayedHeight = imageHeight * baseScale * zoom
        x = if (displayedWidth <= width) (width - displayedWidth) / 2 else x.coerceIn(width - displayedWidth, 0f)
        y = if (displayedHeight <= height) (height - displayedHeight) / 2 else y.coerceIn(height - displayedHeight, 0f)
    }
}
