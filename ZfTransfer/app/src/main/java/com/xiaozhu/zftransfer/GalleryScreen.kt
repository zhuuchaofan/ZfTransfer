package com.xiaozhu.zftransfer

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*

/** Compact photography surface; all operations remain in the screen controller. */
class GalleryScreen(private val context: Context, adapter: BaseAdapter, private val actions: Actions) {
    interface Actions {
        fun settings(); fun cameraStatus(); fun connect(); fun selectAll(); fun cancelSelection()
        fun preview(handle: Long); fun toggle(index: Int); fun rangeStart(index: Int); fun rangeMove(index: Int)
        fun wifiProfile(); fun wifiSettings(); fun importSelected(); fun retry()
        fun format(); fun card(); fun filter(filter: PhotoFilter)
    }
    private fun dp(n: Int) = AppUi.dp(context,n)
    private fun text(value: String,size: Float=14f,color: Int=Color.WHITE) = AppUi.text(context,value,size,color)
    private fun action(value: String, run: () -> Unit) = text(value,14f,AppUi.yellow).apply {
        gravity=Gravity.CENTER; minWidth=dp(48); minHeight=dp(44); setOnClickListener { run() }
    }
    private var activeFilter=PhotoFilter.ALL
    private var switching=false
    private lateinit var gallery: FrameLayout
    private fun switchFilter(filter: PhotoFilter) {
        if(switching || filter==activeFilter) return
        val direction=if(filter.ordinal>activeFilter.ordinal) 1 else -1
        if(!android.animation.ValueAnimator.areAnimatorsEnabled()) { actions.filter(filter);return }
        switching=true
        val distance=dp(64).toFloat()
        gallery.animate().translationX(-direction*distance).alpha(0f).setDuration(110)
            .setInterpolator(android.view.animation.AccelerateInterpolator()).withEndAction {
                actions.filter(filter)
                gallery.translationX=direction*distance
                gallery.animate().translationX(0f).alpha(1f).setDuration(210)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                    .withEndAction { switching=false }.start()
            }.start()
    }
    val root = SwipeTabsLayout(context).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(AppUi.background) }
    val status = text("",13f,AppUi.muted).apply {
        maxLines=4; ellipsize=android.text.TextUtils.TruncateAt.END; visibility=View.GONE
        setLineSpacing(dp(2).toFloat(),1f); setPadding(dp(14),dp(10),dp(14),dp(10))
        background=AppUi.shape(context,AppUi.panel,16)
    }
    val progress = ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply {
        max=100; progressTintList=android.content.res.ColorStateList.valueOf(AppUi.yellow)
        progressBackgroundTintList=android.content.res.ColorStateList.valueOf(AppUi.line); visibility=View.GONE
    }
    val localAccess = AppUi.button(context,"读取历史导入照片") {}.apply { visibility=View.GONE }
    val grid = PhotoGrid(context)
    val cameraLabel = text("Nikon Zf · 尚未连接",12f,AppUi.muted)
    val selectAll = action("全选") { actions.selectAll() }
    val cancelSelection = action("取消") { actions.cancelSelection() }
    val headingTitle = text("相机照片",22f).apply { typeface=Typeface.DEFAULT_BOLD }
    val catalogSummary = text("",11f,AppUi.muted).apply { visibility=View.GONE }
    private fun chipAction(value: String, run: () -> Unit) = action(value,run).apply {
        setPadding(dp(14),0,dp(14),0); background=AppUi.chip(context,false,18)
    }
    val formatControl=chipAction("格式：全部 ▾") { actions.format() }
    val cardControl=chipAction("存储卡：全部 ▾") { actions.card() }.apply { visibility=View.GONE }
    val count = text("",13f,AppUi.muted)
    val selectionLabel = text("长按滑动多选",13f,AppUi.muted)
    val download = AppUi.button(context,"导入原图",true) { actions.importSelected() }
    val retryTransfer = AppUi.button(context,"继续导入未完成照片") { actions.retry() }.apply { visibility=View.GONE }
    val footer = LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL; visibility=View.GONE; elevation=dp(6).toFloat() }
    val emptyTitle = text("把照片，带回手机",24f).apply { gravity=Gravity.CENTER }
    val emptyHint = text("先在相机上开启 Wi-Fi，应用会自动识别并连接。断开后相机会自动关闭 Wi-Fi，下次需要重新开启。",14f,AppUi.muted).apply { gravity=Gravity.CENTER; setLineSpacing(dp(3).toFloat(),1f) }
    val connect = AppUi.button(context,"连接相机",true) { actions.connect() }
    val connectionActions = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
    private val filters = linkedMapOf<PhotoFilter, Pair<TextView,View>>()
    init {
        root.onPage={ direction -> switchFilter(PhotoFilter.entries[SwipeNavigation.adjacent(activeFilter.ordinal,PhotoFilter.entries.size,direction)]) }
        root.setOnApplyWindowInsetsListener { v,insets ->
            v.setPadding(dp(12),insets.systemWindowInsetTop,dp(12),insets.systemWindowInsetBottom);insets
        }
        val header=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(4),dp(8),0,0) }
        val brand=LinearLayout(context).apply {
            gravity=Gravity.CENTER_VERTICAL
            addView(text("Zf",24f).apply { typeface=Typeface.create("serif",Typeface.ITALIC) })
            addView(text(" 传图",22f).apply { typeface=Typeface.DEFAULT_BOLD })
        }
        header.addView(brand,LinearLayout.LayoutParams(0,dp(44),1f))
        header.addView(ImageButton(context).apply {
            setImageResource(context.resources.getIdentifier("ic_more","drawable",context.packageName))
            background=AppUi.shape(context,Color.TRANSPARENT,22); contentDescription="相机与应用设置";setOnClickListener { actions.settings() }
        },LinearLayout.LayoutParams(dp(44),dp(44)))
        root.addView(header)
        root.addView(LinearLayout(context).apply {
            gravity=Gravity.CENTER_VERTICAL; setPadding(dp(14),0,dp(14),0); contentDescription="相机连接状态"
            background=AppUi.shape(context,AppUi.panel,20)
            setOnClickListener { actions.cameraStatus() }
            addView(ImageView(context).apply {
                setImageResource(context.resources.getIdentifier("ic_launcher","mipmap",context.packageName))
            },LinearLayout.LayoutParams(dp(22),dp(22)).apply { rightMargin=dp(10) })
            addView(cameraLabel)
        },LinearLayout.LayoutParams(-1,dp(44)).apply { topMargin=dp(6) })
        val tabs=LinearLayout(context)
        for (filter in PhotoFilter.entries) {
            val tab=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
            val label=action(filter.label) { switchFilter(filter) }
            val underline=View(context).apply { visibility=View.GONE }
            tab.addView(label,LinearLayout.LayoutParams(-1,dp(38)))
            tab.addView(underline,LinearLayout.LayoutParams(0,0))
            tabs.addView(tab,LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=dp(6) });filters[filter]=label to underline
        }
        root.addView(tabs,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10);bottomMargin=dp(6) })
        val heading=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(4),dp(4),0,0) }
        heading.addView(headingTitle)
        heading.addView(count,LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(10) })
        heading.addView(selectAll,LinearLayout.LayoutParams(dp(48),dp(44)))
        heading.addView(cancelSelection,LinearLayout.LayoutParams(dp(48),dp(44)))
        root.addView(LinearLayout(context).apply {
            gravity=Gravity.START
            addView(formatControl,LinearLayout.LayoutParams(-2,dp(36)))
            addView(cardControl,LinearLayout.LayoutParams(-2,dp(36)).apply { leftMargin=dp(8) })
        },LinearLayout.LayoutParams(-2,-2).apply { topMargin=dp(4);bottomMargin=dp(4) })
        root.addView(heading);root.addView(catalogSummary);root.addView(status,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4);bottomMargin=dp(4) });root.addView(progress,LinearLayout.LayoutParams(-1,dp(4)).apply { bottomMargin=dp(6) })
        grid.apply {
            numColumns=3;horizontalSpacing=dp(4);verticalSpacing=dp(4);stretchMode=GridView.STRETCH_COLUMN_WIDTH
            this.adapter=adapter; setOnItemClickListener { _,_,_,handle -> actions.preview(handle) }
            onToggle={ actions.toggle(it) }
            onRangeStart={ actions.rangeStart(it) };onRangeMove={ actions.rangeMove(it) }
        }
        root.addView(localAccess,LinearLayout.LayoutParams(-1,dp(46)))
        gallery=FrameLayout(context).apply { clipToPadding=true }
        val empty=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(20),dp(12),dp(20),dp(12))
            addView(emptyTitle);addView(emptyHint,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(18) })
            connectionActions.addView(connect,LinearLayout.LayoutParams(-1,dp(50)))
            connectionActions.addView(action("在应用内连接 Wi-Fi") { actions.wifiProfile() },LinearLayout.LayoutParams(-1,dp(48)))
            connectionActions.addView(action("打开 Wi-Fi 设置") { actions.wifiSettings() },LinearLayout.LayoutParams(-1,dp(48)))
            addView(connectionActions,LinearLayout.LayoutParams(-1,-2))
        }
        gallery.addView(grid,FrameLayout.LayoutParams(-1,-1));gallery.addView(empty,FrameLayout.LayoutParams(-1,-1));grid.emptyView=empty
        root.addView(gallery,LinearLayout.LayoutParams(-1,0,1f))
        // The manual fallback is only exposed for empty filtered results; normal browsing prefetches.
        footer.background=AppUi.shape(context,AppUi.panel,32)
        footer.setPadding(dp(20),dp(10),dp(10),dp(10))
        footer.addView(selectionLabel,LinearLayout.LayoutParams(0,-2,1f))
        footer.addView(download,LinearLayout.LayoutParams(dp(150),dp(52)))
        root.addView(footer,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8);bottomMargin=dp(8) })
        root.addView(retryTransfer,LinearLayout.LayoutParams(-1,dp(48)))
        cancelSelection.visibility=View.GONE
        renderFilter(PhotoFilter.ALL,0,false)
    }
    fun renderFilter(active: PhotoFilter, visible: Int, enabled: Boolean) {
        activeFilter=active
        count.text=visible.toString()
        filters.forEach { (filter,pair) ->
            pair.first.setTextColor(if(filter==active) AppUi.onAccentContainer else AppUi.muted)
            pair.first.background=AppUi.chip(context,filter==active,19)
            pair.first.isSelected=filter==active;pair.first.isEnabled=enabled
        }
    }
}
