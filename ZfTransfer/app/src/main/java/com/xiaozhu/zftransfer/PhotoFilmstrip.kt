package com.xiaozhu.zftransfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import io.github.noisetta.nikonpull.ptp.CameraObject

/** Full loaded list with a bounded set of visible thumbnail views. */
class PhotoFilmstrip(context: Context) : HorizontalScrollView(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private val step get() = dp(78)
    private val content = FrameLayout(context)
    private val visible = mutableMapOf<Int, ImageView>()
    private val recycled = ArrayDeque<ImageView>()
    private var photos = emptyList<CameraObject>()
    private var selected = -1
    private var thumbnail: (Long) -> Bitmap? = { null }
    private var choose: (Int) -> Unit = {}
    var canReadMore: () -> Boolean = { false }
    var onReadMore: () -> Unit = {}
    var onBrowseGesture: () -> Unit = {}
    private var userBrowsing = false
    private var revealing = false
    private var revealPending = false
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            userBrowsing = true; onBrowseGesture()
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) userBrowsing = false
        return super.dispatchTouchEvent(event)
    }
    init {
        isHorizontalScrollBarEnabled = false
        contentDescription = "全部已读取照片缩略图，可左右滑动"
        addView(content, LayoutParams(0, dp(56)))
    }
    fun submit(items: List<CameraObject>, index: Int, thumbnail: (Long) -> Bitmap?, choose: (Int) -> Unit) {
        val changed = photos != items
        if (changed) {
            photos = items.toList()
            visible.values.forEach { it.setImageDrawable(null) }
            content.removeAllViews(); recycled.addAll(visible.values); visible.clear()
            content.minimumWidth = photos.size * step
            content.layoutParams = content.layoutParams.apply { width = photos.size * step; height = dp(56) }
        }
        this.thumbnail = thumbnail; this.choose = choose
        if (selected != index) revealPending = true
        selected = index
        render()
        if (width > 0) revealSelection() else post { revealSelection() }
    }
    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt); render()
        if (userBrowsing && !revealing && l > oldl && canReadMore() &&
            FilmstripWindow.nearEnd(l, width, step, photos.size)) {
            onReadMore()
        }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        // Add recycled children after the current layout pass, so they get measured.
        post { render(); content.requestLayout(); revealSelection() }
    }
    private fun revealSelection() {
        if (!revealPending || width <= 0 || selected !in photos.indices) return
        revealPending = false
        val target = FilmstripWindow.reveal(scrollX, width, step, selected, photos.size)
        revealing = true
        try { scrollTo(target, 0); render() } finally { revealing = false }
    }
    private fun render() {
        if (width <= 0) return
        val range = FilmstripWindow.visible(scrollX, width, step, photos.size)
        for (position in visible.keys.toList()) if (position !in range) {
            val image = visible.remove(position)!!
            content.removeView(image); image.setImageDrawable(null); recycled.addLast(image)
        }
        for (position in range) {
            val image = visible.getOrPut(position) {
                val view = if (recycled.isEmpty()) ImageView(context) else recycled.removeFirst()
                view.scaleType = ImageView.ScaleType.CENTER_CROP
                view.setPadding(dp(2), dp(2), dp(2), dp(2))
                view.setImageBitmap(thumbnail(photos[position].handle))
                view.contentDescription = "预览 ${photos[position].filename}"
                view.setOnClickListener { choose(position) }
                content.addView(view, FrameLayout.LayoutParams(dp(72), dp(56)).apply { leftMargin = position * step })
                view
            }
            image.setImageBitmap(thumbnail(photos[position].handle))
            image.setBackgroundColor(if (position == selected) 0xfff2c45b.toInt() else Color.TRANSPARENT)
            image.isSelected = position == selected
        }
    }
}
