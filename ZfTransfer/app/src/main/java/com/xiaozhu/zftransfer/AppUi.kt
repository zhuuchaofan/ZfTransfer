package com.xiaozhu.zftransfer

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.*
import android.widget.*

/** Shared native styling for gallery, preview and every app-owned sheet. */
object AppUi {
    const val background = 0xff101112.toInt()
    const val panel = 0xff1b1d1f.toInt()
    const val yellow = 0xfff2c45b.toInt()
    const val muted = 0xffa1a4aa.toInt()
    const val line = 0xff35383c.toInt()
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun shape(context: Context, color: Int, radius: Int = 16, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(context, radius).toFloat()
        if (stroke != null) setStroke(dp(context, 1), stroke)
    }
    fun text(context: Context, value: String, size: Float = 14f, color: Int = Color.WHITE) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        setPadding(0, dp(context, 6), 0, dp(context, 6))
    }
    fun button(context: Context, value: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = value; textSize = 15f; isAllCaps = false
        setTextColor(if (primary) AppUi.background else Color.WHITE)
        backgroundTintList = null
        background = shape(context, if (primary) yellow else panel, 16, if (primary) null else line)
        minHeight = dp(context, 48); minimumHeight = dp(context, 48)
        setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
        setOnClickListener { action() }
    }
    fun input(context: Context, placeholder: String, password: Boolean = false) = EditText(context).apply {
        hint = placeholder; textSize = 15f; setSingleLine(true)
        setTextColor(Color.WHITE); setHintTextColor(muted)
        background = shape(context, AppUi.background, 10, line)
        setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
        inputType = InputType.TYPE_CLASS_TEXT or if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }
}

/** Bounded scrollable bottom sheet; the keyboard may resize it without hiding actions. */
class AppSheet(activity: Activity, title: String) : Dialog(activity) {
    val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val scroll = object : ScrollView(context) {
            override fun onMeasure(w: Int, h: Int) {
                val limit = (resources.displayMetrics.heightPixels * .9f).toInt()
                val available = if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED) limit else minOf(limit, MeasureSpec.getSize(h))
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
            }
        }.apply { isFillViewport = false; isVerticalScrollBarEnabled = false }
        body.setPadding(dp(22), dp(10), dp(22), dp(20))
        body.background = GradientDrawable().apply {
            setColor(AppUi.panel); val r = dp(24).toFloat(); cornerRadii = floatArrayOf(r,r,r,r,0f,0f,0f,0f)
        }
        body.addView(View(context).apply { background = AppUi.shape(context, 0xff53575c.toInt(), 3) },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16) })
        body.addView(AppUi.text(context, title, 21f).apply { typeface = Typeface.DEFAULT_BOLD },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        body.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(dp(22), dp(10), dp(22), dp(20) + insets.systemWindowInsetBottom); insets
        }
        scroll.addView(body); setContentView(scroll)
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    fun dp(value: Int) = AppUi.dp(context, value)
    fun message(value: String) = AppUi.text(context, value, 14f, AppUi.muted).apply {
        setLineSpacing(dp(3).toFloat(), 1f)
        body.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    }
    fun action(value: String, primary: Boolean = false, close: Boolean = true, run: () -> Unit = {}): Button =
        AppUi.button(context, value, primary) { if (close) dismiss(); run() }.also {
            body.addView(it, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })
        }
    fun row(value: String, destructive: Boolean = false, run: () -> Unit) {
        body.addView(AppUi.text(context, value, 15f, if (destructive) 0xffff847b.toInt() else Color.WHITE).apply {
            gravity = Gravity.CENTER_VERTICAL; minHeight = dp(52)
            val arrow = context.getDrawable(context.resources.getIdentifier("ic_chevron_right","drawable",context.packageName))
            setCompoundDrawablesWithIntrinsicBounds(null,null,arrow,null)
            setOnClickListener { dismiss(); run() }
        }, LinearLayout.LayoutParams(-1, dp(52)))
        body.addView(View(context).apply { setBackgroundColor(AppUi.line) }, LinearLayout.LayoutParams(-1, dp(1)))
    }
    override fun show() {
        super.show()
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(-1, -2); setGravity(Gravity.BOTTOM); setDimAmount(.65f)
            navigationBarColor = AppUi.panel
        }
    }
}

class ProgressActionButton(context: Context) : Button(context) {
    private var percentage: Int? = null
    private var progressInitialized = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        isAllCaps = false; textSize = 15f; setTextColor(AppUi.background)
        backgroundTintList = null; minHeight = AppUi.dp(context, 48)
        setPadding(AppUi.dp(context, 10), 0, AppUi.dp(context, 10), 0)
        setTransferProgress(null)
    }
    fun setTransferProgress(value: Int?) {
        val next = value?.coerceIn(0,100)
        if (progressInitialized && next == percentage) return
        percentage = next
        progressInitialized = true
        background = AppUi.shape(context, if (next == null) AppUi.yellow else 0xffaa8c48.toInt(), 16)
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        percentage?.let {
            val saved = canvas.save(); val radius = AppUi.dp(context,16).toFloat()
            canvas.clipPath(Path().apply { addRoundRect(RectF(0f,0f,width.toFloat(),height.toFloat()),radius,radius,Path.Direction.CW) })
            paint.color = AppUi.yellow
            canvas.drawRect(0f,0f,width * (it / 100f),height.toFloat(),paint)
            canvas.restoreToCount(saved)
        }
        super.onDraw(canvas)
    }
}
