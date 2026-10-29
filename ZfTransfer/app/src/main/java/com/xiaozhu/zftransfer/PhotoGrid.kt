package com.xiaozhu.zftransfer

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.GridView
import kotlin.math.abs

/** Normal scrolling remains native; only a stationary long press captures a drag. */
class PhotoGrid(context: Context) : GridView(context) {
    /** Called whenever the visible rows change, so the caller can read what is on screen first. */
    var onVisibleRange: (first: Int, visible: Int) -> Unit = { _, _ -> }
    init {
        setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: android.widget.AbsListView, state: Int) {}
            override fun onScroll(view: android.widget.AbsListView, first: Int, visible: Int, total: Int) {
                if (visible > 0) onVisibleRange(first, visible)
            }
        })
    }
    var editable = true
    var onRangeStart: (Int) -> Unit = {}
    var onRangeMove: (Int) -> Unit = {}
    var onToggle: (Int) -> Unit = {}
    private var checkTapPosition = INVALID_POSITION
    private var checkTapObject: Any? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val startDrag = Runnable {
        val position = pointToPosition(downX.toInt(), downY.toInt())
        if (editable && position != INVALID_POSITION) {
            dragging = true
            parent.requestDisallowInterceptTouchEvent(true)
            val cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, downX, downY, 0)
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onRangeStart(position)
            post(edgeScroll)
        }
    }
    private val edgeScroll = object : Runnable {
        override fun run() {
            if (!dragging) return
            val edge = 56 * resources.displayMetrics.density
            val distance = when {
                lastY < edge -> -16
                lastY > height - edge -> 16
                else -> 0
            }
            if (distance != 0) {
                smoothScrollBy((distance * resources.displayMetrics.density).toInt(), 30)
                selectAtPointer()
            }
            postDelayed(this, 32)
        }
    }
    private fun selectAtPointer() {
        val p = pointToPosition(lastX.toInt().coerceIn(0, (width - 1).coerceAtLeast(0)), lastY.toInt().coerceIn(0, (height - 1).coerceAtLeast(0)))
        if (p != INVALID_POSITION) onRangeMove(p)
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        lastX = event.x; lastY = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                checkTapPosition = INVALID_POSITION
                checkTapObject = null
                val hit = pointToPosition(event.x.toInt(),event.y.toInt())
                val child = getChildAt(hit-firstVisiblePosition)
                val touch = 44 * resources.displayMetrics.density
                if(editable && hit!=INVALID_POSITION && child!=null && event.x>=child.right-touch && event.y<=child.top+touch) {
                    checkTapPosition=hit;checkTapObject=adapter.getItem(hit)
                }
                if (editable) postDelayed(startDrag, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) { selectAtPointer(); return true }
                if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                    removeCallbacks(startDrag);checkTapPosition=INVALID_POSITION
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(startDrag); removeCallbacks(edgeScroll)
                if (!dragging && event.actionMasked==MotionEvent.ACTION_UP && checkTapPosition!=INVALID_POSITION) {
                    val position=checkTapPosition
                    checkTapPosition=INVALID_POSITION
                    val cancel=MotionEvent.obtain(event);cancel.action=MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel);cancel.recycle()
                    if(editable && position<adapter.count && adapter.getItem(position)==checkTapObject) onToggle(position)
                    checkTapObject=null
                    return true
                }
                checkTapPosition=INVALID_POSITION;checkTapObject=null
                if (dragging) {
                    dragging = false
                    parent.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> { removeCallbacks(startDrag);checkTapPosition=INVALID_POSITION }
        }
        return super.dispatchTouchEvent(event)
    }
    override fun onDetachedFromWindow() {
        removeCallbacks(startDrag); removeCallbacks(edgeScroll)
        super.onDetachedFromWindow()
    }
}
