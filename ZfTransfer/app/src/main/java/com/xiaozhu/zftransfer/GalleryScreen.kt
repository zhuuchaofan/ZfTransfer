package com.xiaozhu.zftransfer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/** Builds the gallery. Camera operations and selection policy stay outside the views. */
class GalleryScreen(private val context: Context, address: String?, photoAdapter: BaseAdapter, private val actions: Actions) {
    interface Actions {
        fun settings(); fun cameraStatus(); fun connect(); fun selectAll(); fun cancelSelection()
        fun preview(index: Int); fun rangeStart(index: Int); fun rangeMove(index: Int)
        fun wifiProfile(); fun wifiSettings(); fun readMore(); fun importSelected(); fun retry()
    }
    private val yellow = 0xfff2c45b.toInt()
    private val surfaceColor = 0xff101112.toInt()
    private val muted = 0xffa1a4aa.toInt()
    val root: LinearLayout
    val status: TextView
    val grid: PhotoGrid
    val selectionLabel: TextView
    val cameraLabel: TextView
    val selectAll: TextView
    val cancelSelection: TextView
    val footer: LinearLayout
    val emptyTitle: TextView
    val emptyHint: TextView
    val host: EditText
    val connect: Button
    val more: Button
    val download: Button
    val progress: ProgressBar
    val retryTransfer: Button
    init {
        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surfaceColor)
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(dp(16), insets.systemWindowInsetTop, dp(16), insets.systemWindowInsetBottom)
                insets
            }
        }
        val title = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(14)) }
        val brand = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        brand.addView(label("Zf", 29f).apply { typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.ITALIC) })
        brand.addView(label(" 传图", 24f))
        title.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        title.addView(ImageButton(context).apply {
            setImageResource(resource("ic_more", "drawable"))
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "相机设置与日志"
            setOnClickListener { actions.settings() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(title)
        val cameraRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
            contentDescription = "相机连接状态"
            setOnClickListener { actions.cameraStatus() }
        }
        cameraRow.addView(ImageView(context).apply {
            setImageResource(resource("ic_launcher", "mipmap"))
            contentDescription = "Zf 传图"
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        cameraLabel = label("Nikon Zf · 尚未连接", 14f).apply { setTextColor(muted); setPadding(dp(12), 0, 0, 0) }
        cameraRow.addView(cameraLabel)
        root.addView(cameraRow)
        host = EditText(context).apply {
            setText(address); setSingleLine(true)
            hint = "相机 IPv4 地址（留空自动识别）"
            setTextColor(Color.WHITE); setHintTextColor(muted)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        connect = button("连接相机") { actions.connect() }.apply {
            backgroundTintList = ColorStateList.valueOf(yellow)
            setTextColor(surfaceColor); textSize = 16f
        }
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        heading.addView(label("相机照片", 28f).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
        selectAll = textAction("全选") { actions.selectAll() }
        cancelSelection = textAction("取消") { actions.cancelSelection() }
        selectAll.visibility = View.GONE; cancelSelection.visibility = View.GONE
        heading.addView(selectAll); heading.addView(cancelSelection)
        root.addView(heading)
        root.addView(label("JPEG 原图", 13f).apply { setTextColor(muted); setPadding(dp(2), 0, 0, dp(16)) })
        status = label("", 12f).apply { setTextColor(muted); visibility = View.GONE; maxLines = 3 }
        root.addView(status)
        progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = ColorStateList.valueOf(yellow)
            visibility = View.GONE
        }
        root.addView(progress)
        grid = PhotoGrid(context).apply {
            numColumns = 3; horizontalSpacing = dp(8); verticalSpacing = dp(12)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            adapter = photoAdapter
            setOnItemClickListener { _, _, position, _ -> actions.preview(position) }
            onRangeStart = { actions.rangeStart(it) }
            onRangeMove = { actions.rangeMove(it) }
        }
        val gallery = FrameLayout(context)
        val empty = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), dp(48))
        }
        empty.addView(ImageView(context).apply {
            setImageResource(resource("ic_launcher", "mipmap"))
            contentDescription = "相机连接"
        }, LinearLayout.LayoutParams(dp(76), dp(76)).apply { bottomMargin = dp(24) })
        emptyTitle = label("让照片，回到你的生活", 22f).apply { gravity = Gravity.CENTER }
        emptyHint = label("连接相机 Wi-Fi 后，将自动识别\n也可在应用内设置 Wi-Fi 名和密码", 14f).apply {
            gravity = Gravity.CENTER; setTextColor(muted); setLineSpacing(dp(5).toFloat(), 1f)
        }
        empty.addView(emptyTitle)
        empty.addView(emptyHint, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(24) })
        empty.addView(connect, LinearLayout.LayoutParams(-1, dp(52)))
        empty.addView(textAction("在应用内连接 Wi-Fi") { actions.wifiProfile() }.apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(48)))
        empty.addView(textAction("打开 Wi-Fi 设置") { actions.wifiSettings() }.apply {
            gravity = Gravity.CENTER; setTextColor(muted)
        }, LinearLayout.LayoutParams(-1, dp(52)))
        gallery.addView(grid, FrameLayout.LayoutParams(-1, -1))
        gallery.addView(empty, FrameLayout.LayoutParams(-1, -1))
        grid.emptyView = empty
        root.addView(gallery, LinearLayout.LayoutParams(-1, 0, 1f))
        more = button("继续读取照片") { actions.readMore() }.apply {
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            textSize = 13f; visibility = View.GONE
        }
        grid.onReadMore = { actions.readMore() }
        root.addView(more, LinearLayout.LayoutParams(-1, dp(44)))
        footer = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12)); visibility = View.GONE
        }
        selectionLabel = label("长按滑动多选", 13f).apply { setTextColor(muted) }
        footer.addView(selectionLabel, LinearLayout.LayoutParams(0, -2, 1f))
        download = button("导入原图") { actions.importSelected() }.apply {
            backgroundTintList = ColorStateList.valueOf(yellow)
            setTextColor(surfaceColor); textSize = 16f
        }
        footer.addView(download, LinearLayout.LayoutParams(dp(154), dp(52)))
        root.addView(footer)
        retryTransfer = button("重试未完成") { actions.retry() }
        root.addView(retryTransfer, LinearLayout.LayoutParams(-1, dp(48)))
        download.isEnabled = false; download.alpha = 0.4f
    }
    private fun resource(name: String, type: String) = context.resources.getIdentifier(name, type, context.packageName)
    private fun textAction(text: String, action: () -> Unit) = label(text, 14f).apply {
        setTextColor(yellow); gravity = Gravity.CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        minHeight = dp(48); isClickable = true
        setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float) = TextView(context).apply {
        this.text = text; textSize = size; setTextColor(Color.WHITE)
        setPadding(dp(2), dp(6), dp(2), dp(6))
    }
    private fun button(text: String, action: () -> Unit) = Button(context).apply {
        this.text = text; isAllCaps = false; setTextColor(yellow)
        background = GradientDrawable().apply { setColor(0xff242629.toInt()); cornerRadius = dp(12).toFloat() }
        backgroundTintList = ColorStateList.valueOf(0xff242629.toInt())
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { action() }
    }
}
