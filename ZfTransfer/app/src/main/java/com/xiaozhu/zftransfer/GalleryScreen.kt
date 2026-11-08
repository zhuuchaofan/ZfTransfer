package com.xiaozhu.zftransfer

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*

/**
 * The photo grid behind the home screen: the camera's contact sheet (印样), or the photos already on the phone
 * (已冲印). All operations remain in the screen controller.
 */
class GalleryScreen(private val context: Context, adapter: BaseAdapter, private val actions: Actions) {
    interface Actions {
        fun back(); fun filters(); fun connect(); fun selectAll(); fun cancelSelection()
        fun preview(handle: Long); fun toggle(index: Int); fun rangeStart(index: Int); fun rangeMove(index: Int)
        fun wifiProfile(); fun wifiSettings(); fun importSelected()
    }
    private fun dp(n: Int) = AppUi.dp(context,n)
    private fun text(value: String,size: Float=14f,color: Int=AppUi.ink) = AppUi.text(context,value,size,color)
    private fun action(value: String, run: () -> Unit) = text(value,14f,AppUi.yellow).apply {
        gravity=Gravity.CENTER; minWidth=dp(48); minHeight=dp(44); setOnClickListener { run() }
    }
    val root = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
    val status = text("",13f,AppUi.muted).apply {
        maxLines=4; ellipsize=android.text.TextUtils.TruncateAt.END; visibility=View.GONE
        setLineSpacing(dp(2).toFloat(),1f); setPadding(dp(14),dp(10),dp(14),dp(10))
        background=AppUi.shape(context,AppUi.panel,AppUi.corner,AppUi.line)
    }
    /** Reading the catalog; an import shows its own progress in the transfer panel. */
    val progress = ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply {
        max=100; progressTintList=android.content.res.ColorStateList.valueOf(AppUi.yellow)
        progressBackgroundTintList=android.content.res.ColorStateList.valueOf(AppUi.line); visibility=View.GONE
    }
    val localAccess = AppUi.button(context,"读取历史导入照片") {}.apply { visibility=View.GONE }
    val grid = PhotoGrid(context)
    val selectAll = action("全选") { actions.selectAll() }
    val cancelSelection = action("取消") { actions.cancelSelection() }
    val headingTitle = text("印样",20f).apply { typeface=Typeface.DEFAULT_BOLD }
    /** Shown like a frame counter. */
    val count = text("",13f,AppUi.yellow).apply { typeface=AppUi.mono }
    val catalogSummary = text("",11f,AppUi.muted).apply { visibility=View.GONE; typeface=AppUi.mono }
    /** One control for every filter: import state, format and card; the label sums up what is active. */
    val filterControl = action("全部 ▾") { actions.filters() }.apply {
        setPadding(dp(12),0,dp(12),0); background=AppUi.chip(context,false); typeface=AppUi.mono; textSize=13f
    }
    val selectionLabel = text("长按滑动多选",13f,AppUi.muted).apply { typeface=AppUi.mono }
    val download = AppUi.button(context,"取回",true) { actions.importSelected() }
    val footer = LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL; visibility=View.GONE }
    val emptyTitle = text("相机没有连接",22f).apply { gravity=Gravity.CENTER }
    val emptyHint = text("",14f,AppUi.muted).apply { gravity=Gravity.CENTER; setLineSpacing(dp(3).toFloat(),1f) }
    val connect = AppUi.button(context,"连接相机",true) { actions.connect() }
    val connectionActions = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
    init {
        val top=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(6),0,0) }
        top.addView(ImageButton(context).apply {
            setImageResource(context.resources.getIdentifier("ic_back","drawable",context.packageName))
            imageTintList=android.content.res.ColorStateList.valueOf(AppUi.yellow)
            setBackgroundColor(Color.TRANSPARENT); contentDescription="返回取片台"; setOnClickListener { actions.back() }
        },LinearLayout.LayoutParams(dp(44),dp(48)))
        top.addView(headingTitle)
        top.addView(count,LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(10) })
        top.addView(selectAll,LinearLayout.LayoutParams(dp(48),dp(44)))
        top.addView(cancelSelection,LinearLayout.LayoutParams(dp(48),dp(44)))
        root.addView(top)
        root.addView(LinearLayout(context).apply {
            gravity=Gravity.CENTER_VERTICAL
            addView(filterControl,LinearLayout.LayoutParams(-2,dp(40)))
            addView(catalogSummary,LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(10) })
        },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(2);bottomMargin=dp(6) })
        root.addView(status,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(6) })
        root.addView(progress,LinearLayout.LayoutParams(-1,dp(4)).apply { bottomMargin=dp(6) })
        grid.apply {
            numColumns=3;horizontalSpacing=dp(4);verticalSpacing=dp(4);stretchMode=GridView.STRETCH_COLUMN_WIDTH
            this.adapter=adapter; setOnItemClickListener { _,_,_,handle -> actions.preview(handle) }
            onToggle={ actions.toggle(it) }
            onRangeStart={ actions.rangeStart(it) };onRangeMove={ actions.rangeMove(it) }
        }
        root.addView(localAccess,LinearLayout.LayoutParams(-1,dp(46)))
        val gallery=FrameLayout(context).apply { clipToPadding=true }
        val empty=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(20),dp(12),dp(20),dp(12))
            addView(emptyTitle);addView(emptyHint,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(18) })
            connectionActions.addView(connect,LinearLayout.LayoutParams(-1,dp(52)))
            connectionActions.addView(action("在应用内连接 Wi-Fi") { actions.wifiProfile() },LinearLayout.LayoutParams(-1,dp(48)))
            connectionActions.addView(action("打开 Wi-Fi 设置") { actions.wifiSettings() },LinearLayout.LayoutParams(-1,dp(48)))
            addView(connectionActions,LinearLayout.LayoutParams(-1,-2))
        }
        gallery.addView(grid,FrameLayout.LayoutParams(-1,-1));gallery.addView(empty,FrameLayout.LayoutParams(-1,-1));grid.emptyView=empty
        root.addView(gallery,LinearLayout.LayoutParams(-1,0,1f))
        footer.background=AppUi.shape(context,AppUi.panel,AppUi.corner,AppUi.line)
        footer.setPadding(dp(14),dp(8),dp(8),dp(8))
        footer.addView(selectionLabel,LinearLayout.LayoutParams(0,-2,1f))
        footer.addView(download,LinearLayout.LayoutParams(dp(120),dp(52)))
        root.addView(footer,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8);bottomMargin=dp(8) })
        cancelSelection.visibility=View.GONE
    }
    fun renderCount(visible: Int) { count.text="%02d".format(visible) }
}
