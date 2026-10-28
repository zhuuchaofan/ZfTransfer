package com.xiaozhu.zftransfer

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout

/** Intercepts only an early, unambiguous horizontal drag; long-press selection stays with the grid. */
class SwipeTabsLayout(context: Context) : LinearLayout(context) {
    var onPage: (Int) -> Unit = {}
    private var startX=0f
    private var startY=0f
    private var blocked=false
    private var captured=false
    private val threshold=AppUi.dp(context,48).toFloat()
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX=event.x;startY=event.y;blocked=false;captured=false }
            MotionEvent.ACTION_POINTER_DOWN -> blocked=true
            MotionEvent.ACTION_MOVE -> {
                if(event.eventTime-event.downTime>=ViewConfiguration.getLongPressTimeout()) blocked=true
                if(kotlin.math.abs(event.y-startY)>ViewConfiguration.get(context).scaledTouchSlop &&
                    kotlin.math.abs(event.y-startY)>=kotlin.math.abs(event.x-startX)) blocked=true
                if(SwipeNavigation.direction(event.x-startX,event.y-startY,threshold,blocked)!=0) {
                    captured=true;return true
                }
            }
        }
        return super.onInterceptTouchEvent(event)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(event.actionMasked==MotionEvent.ACTION_MOVE && !captured) {
            if(event.eventTime-event.downTime>=ViewConfiguration.getLongPressTimeout()) blocked=true
            if(kotlin.math.abs(event.y-startY)>ViewConfiguration.get(context).scaledTouchSlop &&
                kotlin.math.abs(event.y-startY)>=kotlin.math.abs(event.x-startX)) blocked=true
            captured=SwipeNavigation.direction(event.x-startX,event.y-startY,threshold,blocked)!=0
        }
        if(event.actionMasked==MotionEvent.ACTION_POINTER_DOWN) blocked=true
        if(event.actionMasked==MotionEvent.ACTION_UP) {
            val direction=SwipeNavigation.direction(event.x-startX,event.y-startY,threshold,blocked)
            val wasCaptured=captured
            captured=false
            if(direction!=0 && (wasCaptured || event.eventTime-event.downTime<ViewConfiguration.getLongPressTimeout())) onPage(direction)
        }
        if(event.actionMasked==MotionEvent.ACTION_CANCEL) captured=false
        return true
    }
}
