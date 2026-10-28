package com.xiaozhu.zftransfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import io.github.noisetta.nikonpull.ptp.CameraObject

/** Stateless cell rendering; selection and import evidence are supplied by the screen. */
class GalleryPhotoCell(private val context: Context) {
    private val yellow = 0xfff2c45b.toInt()
    private val muted = 0xffa1a4aa.toInt()
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    private fun resource(name: String, type: String) = context.resources.getIdentifier(name, type, context.packageName)
    private fun label(text: String, size: Float) = TextView(context).apply {
        this.text = text; textSize = size; setTextColor(Color.WHITE); setPadding(dp(2), dp(6), dp(2), dp(6))
    }
    fun create(obj: CameraObject, bitmap: Bitmap?, selected: Boolean, imported: Boolean,
        enabled: Boolean, gridHeight: Int, toggle: () -> Unit): View {
        val cell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val frame = FrameLayout(context).apply {
            background = GradientDrawable().apply { setColor(0xff242629.toInt()); cornerRadius = dp(5).toFloat() }
            clipToOutline = true
        }
        val width = ((context.resources.displayMetrics.widthPixels - dp(48)) / 3).coerceAtLeast(dp(60))
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(bitmap)
            setBackgroundColor(0xff242629.toInt())
            contentDescription = "预览 ${obj.filename}"
        }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        if (bitmap == null) frame.addView(label("无预览", 12f), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val check = ImageView(context).apply {
            setImageResource(resource(if (selected) "ic_selected" else "ic_unselected", "drawable"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "选择 ${obj.filename}"
            isEnabled = enabled
            setOnClickListener { toggle() }
        }
        frame.addView(check, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.RIGHT))
        if (imported) {
            val badge = label("已导入", 10f).apply {
                setPadding(dp(7), dp(3), dp(7), dp(3))
                background = GradientDrawable().apply { setColor(0xdd202225.toInt()); cornerRadius = dp(12).toFloat() }
            }
            frame.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.RIGHT))
        }
        if (selected) frame.foreground = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(2), yellow) }
        val frameHeight = if (gridHeight > 0) minOf(width, ((gridHeight - dp(36)) / 4 - dp(26)).coerceAtLeast(dp(68))) else width
        cell.addView(frame, LinearLayout.LayoutParams(-1, frameHeight))
        cell.addView(label(obj.filename, 10f).apply { setTextColor(muted); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        return cell
    }
}
