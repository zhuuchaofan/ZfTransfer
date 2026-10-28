package com.xiaozhu.zftransfer

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.*

/** Presentation only: no camera, storage or task ownership. */
class PhotoPreviewDialog(activity: Activity, onHigh: () -> Unit, onPrevious: () -> Unit,
    onNext: () -> Unit, onImport: () -> Unit, onSelect: () -> Unit) : Dialog(activity, android.R.style.Theme_Material_NoActionBar) {
    private val yellow = 0xfff2c45b.toInt()
    private val surface = 0xff101112.toInt()
    private val muted = 0xffa1a4aa.toInt()
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float) = TextView(context).apply {
        this.text = text; textSize = size; setTextColor(Color.WHITE); setPadding(dp(2), dp(6), dp(2), dp(6))
    }
    private fun action(text: String, callback: () -> Unit) = label(text, 14f).apply {
        setTextColor(yellow); gravity = Gravity.CENTER; setPadding(dp(12), dp(12), dp(12), dp(12)); minHeight = dp(48)
        setOnClickListener { callback() }
    }
    private fun button(text: String, callback: () -> Unit) = Button(context).apply {
        this.text = text; isAllCaps = false; setTextColor(yellow)
        background = GradientDrawable().apply { setColor(0xff242629.toInt()); cornerRadius = dp(12).toFloat() }
        backgroundTintList = ColorStateList.valueOf(0xff242629.toInt()); setPadding(dp(12), dp(8), dp(12), dp(8)); setOnClickListener { callback() }
    }
    val image = ZoomPhotoView(context)
    val strip = PhotoFilmstrip(context)
    val name = label("", 20f)
    val quality = label("快速缩略预览", 12f).apply { setTextColor(muted) }
    val size = label("", 13f).apply { setTextColor(muted) }
    val high = action("原图", onHigh).apply { contentDescription = "查看原图" }
    val previous = action("上一张", onPrevious)
    val next = action("下一张", onNext)
    val import = button("导入这张原图", onImport).apply { backgroundTintList = ColorStateList.valueOf(yellow); setTextColor(surface) }
    val select = action("", onSelect)
    init {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(surface) }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageButton(context).apply {
            setImageResource(context.resources.getIdentifier("ic_back", "drawable", context.packageName)); setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "返回照片"; setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        header.addView(label("照片预览", 18f), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(high, LinearLayout.LayoutParams(-2, dp(48)))
        root.addView(header)
        root.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(strip, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        root.addView(quality); root.addView(size)
        root.addView(LinearLayout(context).apply {
            addView(previous, LinearLayout.LayoutParams(0, dp(44), 1f)); addView(next, LinearLayout.LayoutParams(0, dp(44), 1f))
        })
        root.addView(import, LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(select, LinearLayout.LayoutParams(-1, dp(48)))
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(16), insets.systemWindowInsetTop + dp(8), dp(16), insets.systemWindowInsetBottom + dp(8)); insets
        }
        setContentView(root)
    }
    override fun show() { super.show(); window?.setLayout(-1, -1) }
}
