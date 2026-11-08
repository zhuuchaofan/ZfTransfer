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
    // Film-body palette: warm black like a camera body, cream like paper, brass for the accent. Fixed on every
    // Android version; the look comes from these exact tones, so it does not follow the wallpaper.
    const val background = 0xff15120e.toInt()
    const val panel = 0xff1e1913.toInt()
    const val panelHigh = 0xff2a231a.toInt()
    /** Brass accent. The name is historical. */
    const val yellow = 0xffc9a25c.toInt()
    const val onAccent = 0xff241a08.toInt()
    const val accentContainer = 0xff3a2f1e.toInt()
    const val onAccentContainer = 0xffead3a0.toInt()
    /** Cream body text; used wherever plain white was before. */
    const val ink = 0xffede3cf.toInt()
    const val muted = 0xff9a8c72.toInt()
    const val line = 0xff4a3f30.toInt()
    /** Film base: the black band that carries the sprocket holes. */
    const val filmBase = 0xff0b0907.toInt()
    /** Translucent backing for badges laid over photos. */
    const val scrim = 0xcc15120e.toInt()
    const val danger = 0xffff847b.toInt()
    /** Corner radius for everything rectangular: cards, buttons, cells, chips. Film-era hardware has hard edges. */
    const val corner = 4
    /** Frame counters, dates and other numbers. */
    val mono: Typeface = Typeface.MONOSPACE
    /** Round check marker used by photo cells and day headings. */
    fun applySelectIcon(view: ImageView, selected: Boolean) {
        val ctx = view.context
        if (selected) {
            view.setImageResource(ctx.resources.getIdentifier("ic_check", "drawable", ctx.packageName))
            view.imageTintList = android.content.res.ColorStateList.valueOf(onAccent)
            view.background = android.graphics.drawable.InsetDrawable(
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(yellow) }, dp(ctx, 11))
            view.setPadding(dp(ctx, 15), dp(ctx, 15), dp(ctx, 15), dp(ctx, 15))
        } else {
            view.setImageResource(ctx.resources.getIdentifier("ic_unselected", "drawable", ctx.packageName))
            view.imageTintList = null; view.background = null
            view.setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
        }
    }
    /** Outlined selector, filled with brass tint when active. */
    fun chip(context: Context, active: Boolean) =
        shape(context, if (active) accentContainer else Color.TRANSPARENT, corner, if (active) yellow else line)
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun shape(context: Context, color: Int, radius: Int = corner, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(context, radius).toFloat()
        if (stroke != null) setStroke(dp(context, 1), stroke)
    }
    fun text(context: Context, value: String, size: Float = 14f, color: Int = ink) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        setPadding(0, dp(context, 6), 0, dp(context, 6))
    }
    fun button(context: Context, value: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = value; textSize = 15f; isAllCaps = false; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) onAccent else ink)
        backgroundTintList = null; stateListAnimator = null
        background = shape(context, if (primary) yellow else panelHigh, corner, if (primary) null else line)
        minHeight = dp(context, 52); minimumHeight = dp(context, 52)
        setPadding(dp(context, 16), dp(context, 8), dp(context, 12), dp(context, 8))
        setOnClickListener { action() }
    }
    fun input(context: Context, placeholder: String, password: Boolean = false) = EditText(context).apply {
        hint = placeholder; textSize = 15f; setSingleLine(true)
        setTextColor(ink); setHintTextColor(muted)
        background = shape(context, AppUi.background, corner, line)
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
            setColor(AppUi.panel); val r = dp(AppUi.corner * 2).toFloat(); cornerRadii = floatArrayOf(r,r,r,r,0f,0f,0f,0f)
        }
        body.addView(View(context).apply { background = AppUi.shape(context, AppUi.line, 1) },
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
        body.addView(AppUi.text(context, value, 15f, if (destructive) AppUi.danger else AppUi.ink).apply {
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
            setLayout(-1, -2); setGravity(Gravity.BOTTOM); setDimAmount(.6f)
            navigationBarColor = AppUi.panel
        }
    }
}

/** The edge of a strip of film: a dark band with evenly spaced sprocket holes, drawn along the top of the bounds. */
class FilmEdgeDrawable(context: Context) : android.graphics.drawable.Drawable() {
    private val band = AppUi.dp(context, 9).toFloat()
    private val holeW = AppUi.dp(context, 6).toFloat()
    private val holeH = AppUi.dp(context, 4).toFloat()
    private val pitch = AppUi.dp(context, 12).toFloat()
    private val round = AppUi.dp(context, 1).toFloat()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val b = bounds
        paint.color = AppUi.filmBase
        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.top + band, paint)
        paint.color = AppUi.background
        val top = b.top + (band - holeH) / 2
        var x = b.left + (pitch - holeW) / 2
        while (x + holeW <= b.right) {
            canvas.drawRoundRect(x, top, x + holeW, top + holeH, round, round, paint)
            x += pitch
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

class ProgressActionButton(context: Context) : Button(context) {
    private var percentage: Int? = null
    private var progressInitialized = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        isAllCaps = false; textSize = 15f; setTextColor(AppUi.onAccent); typeface = Typeface.DEFAULT_BOLD
        backgroundTintList = null; stateListAnimator = null; minHeight = AppUi.dp(context, 48)
        setPadding(AppUi.dp(context, 10), 0, AppUi.dp(context, 10), 0)
        setTransferProgress(null)
    }
    fun setTransferProgress(value: Int?) {
        val next = value?.coerceIn(0,100)
        if (progressInitialized && next == percentage) return
        percentage = next
        progressInitialized = true
        background = AppUi.shape(context, if (next == null) AppUi.yellow else AppUi.accentContainer)
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        percentage?.let {
            val saved = canvas.save(); val radius = AppUi.dp(context,AppUi.corner).toFloat()
            canvas.clipPath(Path().apply { addRoundRect(RectF(0f,0f,width.toFloat(),height.toFloat()),radius,radius,Path.Direction.CW) })
            paint.color = AppUi.yellow
            canvas.drawRect(0f,0f,width * (it / 100f),height.toFloat(),paint)
            canvas.restoreToCount(saved)
        }
        super.onDraw(canvas)
    }
}
