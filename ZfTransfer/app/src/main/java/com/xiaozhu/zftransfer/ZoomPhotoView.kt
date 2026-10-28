package com.xiaozhu.zftransfer

import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.ImageView
import kotlin.math.abs

/** Only the detailed photo can zoom; grid and filmstrip keep their existing gestures. */
class ZoomPhotoView(context: Context) : ImageView(context) {
    private val geometry = PhotoZoomState()
    private val transform = Matrix()
    var onPage: (Int) -> Unit = {}
    private var pageBlocked = false
    private var zoomEnabled = false
    private var zoomAnimation: ValueAnimator? = null
    private var pointer = -1
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var focusX = 0f
    private var focusY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { performClick(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            animateDoubleTap(e.x - paddingLeft, e.y - paddingTop)
            return true
        }
    })
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            cancelZoomAnimation()
            focusX = detector.focusX - paddingLeft; focusY = detector.focusY - paddingTop
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val nextX = detector.focusX - paddingLeft
            val nextY = detector.focusY - paddingTop
            geometry.scale(detector.scaleFactor, focusX, focusY, nextX - focusX, nextY - focusY)
            focusX = nextX; focusY = nextY
            applyTransform(); return true
        }
    }).apply { isQuickScaleEnabled = false }

    init { scaleType = ScaleType.MATRIX }

    fun showPhoto(bitmap: Bitmap?, detailed: Boolean) {
        cancelZoomAnimation()
        // A pending tap from the old photo must never zoom the next photo.
        val cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        taps.onTouchEvent(cancel); pinch.onTouchEvent(cancel); cancel.recycle()
        pointer = -1; dragging = false
        parent?.requestDisallowInterceptTouchEvent(false)
        zoomEnabled = detailed
        super.setImageBitmap(bitmap)
        fit()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh); fit()
    }
    private fun fit() {
        cancelZoomAnimation()
        val image = drawable
        geometry.fit((width - paddingLeft - paddingRight).toFloat(), (height - paddingTop - paddingBottom).toFloat(),
            image?.intrinsicWidth?.toFloat() ?: 0f, image?.intrinsicHeight?.toFloat() ?: 0f)
        applyTransform()
    }
    private fun cancelZoomAnimation() { zoomAnimation?.cancel(); zoomAnimation = null }
    private fun animateDoubleTap(x: Float, y: Float) {
        cancelZoomAnimation()
        val start = geometry.snapshot()
        geometry.doubleTap(x, y)
        val target = geometry.snapshot()
        if (!ValueAnimator.areAnimatorsEnabled()) { applyTransform(); return }
        geometry.restore(start)
        zoomAnimation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                geometry.restore(start.towards(target, it.animatedValue as Float))
                applyTransform()
            }
            start()
        }
    }
    override fun onDetachedFromWindow() { cancelZoomAnimation(); super.onDetachedFromWindow() }
    private fun applyTransform() {
        val scale = geometry.baseScale * geometry.zoom
        transform.setValues(floatArrayOf(scale, 0f, geometry.x, 0f, scale, geometry.y, 0f, 0f, 1f))
        imageMatrix = transform
        if (android.os.Build.VERSION.SDK_INT >= 30) stateDescription = if (zoomEnabled) "缩放 ${(geometry.zoom * 100).toInt()}%" else "快速缩略预览"
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN) pageBlocked=geometry.zoom>1.01f
        if(event.actionMasked==MotionEvent.ACTION_POINTER_DOWN) pageBlocked=true
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) cancelZoomAnimation()
        if(zoomEnabled && drawable!=null) { pinch.onTouchEvent(event); taps.onTouchEvent(event) }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointer = event.getPointerId(0)
                lastX = event.x; lastY = event.y; downX = lastX; downY = lastY; dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && !pinch.isInProgress) {
                val index = event.findPointerIndex(pointer)
                if (index >= 0) {
                    val x = event.getX(index); val y = event.getY(index)
                    if (abs(x - downX) > slop || abs(y - downY) > slop) dragging = true
                    if (dragging) { cancelZoomAnimation(); if (geometry.zoom > 1f) { geometry.pan(x - lastX, y - lastY); applyTransform() } }
                    lastX = x; lastY = y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Re-anchor to a surviving finger to prevent a jump when one finger lifts.
                if (event.pointerCount > 1) {
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    pointer = event.getPointerId(remaining)
                    lastX = event.getX(remaining); lastY = event.getY(remaining)
                    downX = lastX; downY = lastY; dragging = geometry.zoom > 1f
                } else pointer = -1
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) cancelZoomAnimation()
                val direction=if(event.actionMasked==MotionEvent.ACTION_UP) SwipeNavigation.direction(
                    event.x-downX,event.y-downY,maxOf(AppUi.dp(context,48).toFloat(),width*.12f),pageBlocked || geometry.zoom>1.01f) else 0
                pointer = -1; dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if(direction!=0) onPage(direction)
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
