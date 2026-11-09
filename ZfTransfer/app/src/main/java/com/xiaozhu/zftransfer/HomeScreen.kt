package com.xiaozhu.zftransfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/**
 * 取片台: the first screen. It answers "is the camera here, and how many new photos are on it" and takes them in
 * one tap. Browsing every photo lives on the contact sheet ([GalleryScreen]); this screen only shows the latest roll.
 */
class HomeScreen(private val context: Context, private val actions: Actions) {
    interface Actions {
        fun settings(); fun prints(); fun connect(); fun wifiProfile(); fun wifiSettings()
        fun takeFresh(); fun browse(); fun retry(); fun openRecent(index: Int)
    }
    /** Everything the screen shows, computed by the activity from the runtime. */
    data class State(
        val connected: Boolean, val connecting: Boolean, val busy: Boolean, val cameraLabel: String,
        val catalogReady: Boolean, val summary: String, val fresh: FreshPhotos.Summary,
        val remaining: Int, val message: String,
        /** "最近一卷" (the camera's newest photos) or "最近冲印" (the newest ones on the phone); empty title hides it. */
        val recentTitle: String, val recentCount: Int,
        val blePaired: Boolean = false,
    )
    private fun dp(n: Int) = AppUi.dp(context, n)
    private fun text(value: String, size: Float = 14f, color: Int = AppUi.ink) = AppUi.text(context, value, size, color)
    private fun link(value: String, run: () -> Unit) = text(value, 14f, AppUi.yellow).apply {
        gravity = Gravity.CENTER; minHeight = dp(44); setOnClickListener { run() }
    }

    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val dot = View(context)
    private val cameraLabel = text("相机 · 尚未连接", 13f).apply { typeface = AppUi.mono }
    private val summary = text("", 11f, AppUi.muted).apply { typeface = AppUi.mono }
    private val bigCount = text("", 34f, AppUi.yellow).apply { typeface = AppUi.mono; setPadding(0, dp(2), 0, 0); includeFontPadding = true }
    /** "张新照片" next to the number, sharing its baseline. */
    private val countUnit = text("", 15f)
    private val countRow = LinearLayout(context).apply {
        isBaselineAligned = true
        addView(bigCount)
        addView(countUnit, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
    }
    private val caption = text("", 13f, AppUi.muted).apply { setLineSpacing(dp(2).toFloat(), 1f) }
    private val primary = AppUi.button(context, "连接相机", true) { onPrimary() }
    private val browse = link("挑着取 ›") { actions.browse() }
    private val wifiProfile = link("在应用内连接 Wi-Fi") { actions.wifiProfile() }
    private val wifiSettings = link("打开 Wi-Fi 设置") { actions.wifiSettings() }
    private val message = text("", 12f, AppUi.muted).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val retry = AppUi.button(context, "继续取回未完成的照片") { actions.retry() }
    private val recentTitle = text("最近一卷", 12f, AppUi.muted)
    private val recentEdge = View(context).apply { background = FilmEdgeDrawable(context) }
    private val recentGrid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val recentCells = List(RECENT) { ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP; scaleX = 1.15f; scaleY = 1.15f
    } }
    private val recentFrames = recentCells.map { image -> FrameLayout(context).apply {
        background = AppUi.shape(context, AppUi.panel, 2); clipToOutline = true
        addView(image, FrameLayout.LayoutParams(-1, -1))
    } }
    private var state: State? = null

    init {
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(8), 0, 0) }
        header.addView(text("取片", 24f).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(link("已冲印 ›") { actions.prints() }.apply { setTextColor(AppUi.muted); setPadding(dp(8), 0, dp(8), 0) },
            LinearLayout.LayoutParams(-2, dp(48)))
        header.addView(ImageButton(context).apply {
            setImageResource(context.resources.getIdentifier("ic_more", "drawable", context.packageName))
            background = AppUi.shape(context, android.graphics.Color.TRANSPARENT, 22)
            contentDescription = "相机与应用设置"; setOnClickListener { actions.settings() }
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        root.addView(header)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(12))
            background = AppUi.shape(context, AppUi.panel, AppUi.corner, AppUi.line)
        }
        card.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; contentDescription = "相机连接状态"
            setOnClickListener { actions.settings() }
            addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) })
            addView(cameraLabel)
        })
        card.addView(summary)
        card.addView(countRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        card.addView(caption, LinearLayout.LayoutParams(-1, -2))
        card.addView(primary, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
        card.addView(browse, LinearLayout.LayoutParams(-1, dp(44)))
        card.addView(wifiProfile, LinearLayout.LayoutParams(-1, dp(44)))
        card.addView(wifiSettings, LinearLayout.LayoutParams(-1, dp(44)))
        root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        root.addView(message, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); leftMargin = dp(4); rightMargin = dp(4) })
        root.addView(retry, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })

        root.addView(recentTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); leftMargin = dp(4) })
        root.addView(recentEdge, LinearLayout.LayoutParams(-1, dp(9)).apply { bottomMargin = dp(4) })
        for (row in 0 until RECENT / 3) {
            val line = LinearLayout(context)
            for (col in 0 until 3) {
                val index = row * 3 + col
                line.addView(recentFrames[index], LinearLayout.LayoutParams(0, -1, 1f).apply { if (col > 0) leftMargin = dp(4) })
            }
            recentGrid.addView(line, LinearLayout.LayoutParams(-1, recentCellHeight()).apply { if (row > 0) topMargin = dp(4) })
        }
        root.addView(recentGrid)
    }
    /** Same square size as the contact sheet's cells: page margins 24dp and two 4dp gaps. */
    private fun recentCellHeight() = (context.resources.displayMetrics.widthPixels - dp(32)) / 3

    private fun onPrimary() {
        val s = state ?: return
        when {
            !s.connected -> actions.connect()
            s.fresh.fresh.isNotEmpty() -> actions.takeFresh()
            else -> actions.browse()
        }
    }

    fun render(s: State, thumbnail: (Int) -> Bitmap?) {
        state = s
        dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (s.connected) AppUi.yellow else AppUi.line) }
        cameraLabel.text = s.cameraLabel
        cameraLabel.setTextColor(if (s.connected) AppUi.ink else AppUi.muted)
        summary.text = s.summary; summary.visibility = if (s.connected && s.summary.isNotEmpty()) View.VISIBLE else View.GONE
        val loading = s.connected && !s.catalogReady
        val freshCount = s.fresh.fresh.size
        countRow.visibility = if (s.connected && s.catalogReady) View.VISIBLE else View.GONE
        bigCount.text = if (s.fresh.hasHistory) "%02d".format(freshCount) else "%02d".format(s.fresh.notImported)
        countUnit.text = if (s.fresh.hasHistory) "张新照片" else "张还没取到手机"
        caption.text = when {
            !s.connected && s.connecting -> s.message.ifEmpty { "正在连接相机，请保持手机连接相机 Wi-Fi。" }
            !s.connected && s.blePaired -> "相机开机即可，点「连接相机」会通过蓝牙打开相机 Wi-Fi。"
            !s.connected -> "先在相机上开启 Wi-Fi，应用会自动识别并连接。在右上角「更多」里配对蓝牙后，就不用每次手动开了。"
            loading -> "正在读取相机里的照片…"
            !s.fresh.hasHistory -> "第一次使用，先挑出要的几张吧。"
            freshCount > 0 -> "上次取片之后拍的。"
            s.fresh.notImported > 0 -> "还有 ${s.fresh.notImported} 张更早的没取，可以去挑着取。"
            else -> "相机里的照片都已取到手机。"
        }
        primary.text = when {
            !s.connected -> "连接相机"
            freshCount > 0 -> "取回这 $freshCount 张"
            else -> "挑着取"
        }
        val primaryEnabled = !s.busy && !(s.connected && !s.catalogReady) && !(!s.connected && s.connecting)
        primary.isEnabled = primaryEnabled; primary.alpha = if (primaryEnabled) 1f else 0.4f
        browse.visibility = if (s.connected && s.catalogReady && freshCount > 0) View.VISIBLE else View.GONE
        browse.isEnabled = !s.busy
        wifiProfile.visibility = if (!s.connected) View.VISIBLE else View.GONE
        wifiSettings.visibility = wifiProfile.visibility
        message.text = s.message
        // While connecting the card caption already carries the message.
        message.visibility = if (s.message.isNotEmpty() && !s.busy && (s.connected || !s.connecting)) View.VISIBLE else View.GONE
        retry.text = "继续取回未完成的 ${s.remaining} 张"
        retry.visibility = if (s.remaining > 0 && !s.busy) View.VISIBLE else View.GONE
        retry.isEnabled = s.connected
        val showRecent = s.recentTitle.isNotEmpty() && s.recentCount > 0
        recentTitle.text = s.recentTitle
        recentTitle.visibility = if (showRecent) View.VISIBLE else View.GONE
        recentEdge.visibility = recentTitle.visibility; recentGrid.visibility = recentTitle.visibility
        for (row in 0 until recentGrid.childCount) {
            recentGrid.getChildAt(row).visibility = if (row * 3 < s.recentCount) View.VISIBLE else View.GONE
        }
        recentFrames.forEachIndexed { i, frame ->
            val present = i < s.recentCount
            frame.visibility = if (present) View.VISIBLE else View.INVISIBLE
            recentCells[i].setImageBitmap(if (present) thumbnail(i) else null)
            recentCells[i].contentDescription = if (present) "${s.recentTitle}第 ${i + 1} 张" else null
            frame.setOnClickListener(if (present) View.OnClickListener { actions.openRecent(i) } else null)
        }
    }
    private companion object { const val RECENT = 9 }
}

/**
 * The batch being taken, pinned under every screen so browsing continues while it runs.
 * Hidden whenever no import is running.
 */
class TransferPanel(private val context: Context) {
    private fun dp(n: Int) = AppUi.dp(context, n)
    private val counter = AppUi.text(context, "", 13f, AppUi.yellow).apply { typeface = AppUi.mono }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100; progressTintList = android.content.res.ColorStateList.valueOf(AppUi.yellow)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(AppUi.line)
    }
    private val detail = AppUi.text(context, "", 11f, AppUi.muted).apply {
        typeface = AppUi.mono; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
    }
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; visibility = View.GONE
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = AppUi.shape(context, AppUi.panel, AppUi.corner, AppUi.line)
        contentDescription = "正在取回照片"
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(AppUi.text(context, "正在取回", 14f).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(counter)
        })
        addView(progress, LinearLayout.LayoutParams(-1, dp(4)))
        addView(detail)
    }
    fun render(task: TaskState) {
        val transfer = task.transfer
        if (task.operation != UserOperation.IMPORT || transfer == null) { root.visibility = View.GONE; return }
        root.visibility = View.VISIBLE
        counter.text = if (transfer.total > 0) "%02d / %02d".format(transfer.index + 1, transfer.total) else ""
        // Overall progress: finished photos plus the share of the current one.
        val overall = if (transfer.total > 0) ((transfer.index * 100 + transfer.percentage) / transfer.total) else transfer.percentage
        progress.progress = overall.coerceIn(0, 100)
        detail.text = listOf(transfer.filename, if (transfer.size > 0) "%.1f MB".format(transfer.size / 1048576.0) else "", "${transfer.percentage}%")
            .filter { it.isNotEmpty() }.joinToString(" · ")
    }
}
