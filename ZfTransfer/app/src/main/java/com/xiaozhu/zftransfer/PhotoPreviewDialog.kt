package com.xiaozhu.zftransfer

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.widget.*

/** Immersive photo viewer. View state and transfers are owned by its controller. */
class PhotoPreviewDialog(activity: Activity, onHigh: () -> Unit, private val onPrevious: () -> Unit,
    private val onNext: () -> Unit, onImport: () -> Unit, onSelect: () -> Unit) : Dialog(activity,android.R.style.Theme_Material_NoActionBar) {
    private fun dp(n:Int)=AppUi.dp(context,n)
    private fun label(value:String,size:Float=14f,color:Int=Color.WHITE)=AppUi.text(context,value,size,color)
    private fun arrow(description:String,next:Boolean=false,run:()->Unit)=ImageButton(context).apply {
        setImageResource(context.resources.getIdentifier("ic_back","drawable",context.packageName))
        imageTintList=android.content.res.ColorStateList.valueOf(AppUi.yellow)
        setBackgroundColor(Color.TRANSPARENT);contentDescription=description
        if(next) rotation=180f
        setOnClickListener { run() }
    }
    val image=ZoomPhotoView(context)
    val strip=PhotoFilmstrip(context)
    val counter=label("照片预览",15f).apply { gravity=Gravity.CENTER;contentDescription="照片预览" }
    val name=label("",16f)
    val quality=label("清晰预览",11f,AppUi.muted).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END }
    val size=label("",12f,AppUi.muted)
    val high=label("原图",14f,AppUi.yellow).apply {
        gravity=Gravity.CENTER;minHeight=dp(48);contentDescription="查看原图";setOnClickListener { onHigh() }
    }
    val previous=arrow("上一张") { navigate(-1) }
    val next=arrow("下一张",true) { navigate(1) }
    val import=ProgressActionButton(context).apply { text="导入原图";setOnClickListener { onImport() } }
    val select=AppUi.button(context,"加入选择",false,onSelect)
    private var switching=false
    private fun navigate(direction: Int) {
        if(switching || (direction>0 && !next.isEnabled) || (direction<0 && !previous.isEnabled)) return
        val change = { if(direction>0) onNext() else onPrevious() }
        if(!android.animation.ValueAnimator.areAnimatorsEnabled()) { change();return }
        switching=true
        val distance=dp(72).toFloat()
        image.animate().translationX(-direction*distance).alpha(0f).setDuration(110)
            .setInterpolator(android.view.animation.AccelerateInterpolator()).withEndAction {
                if(!isShowing) { switching=false;return@withEndAction }
                change()
                image.translationX=direction*distance
                image.animate().translationX(0f).alpha(1f).setDuration(210)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                    .withEndAction { switching=false }.start()
            }.start()
    }
    override fun dismiss() {
        image.animate().cancel(); image.translationX=0f; image.alpha=1f; switching=false
        super.dismiss()
    }
    init {
        image.onPage=::navigate
        val root=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(AppUi.background) }
        root.addView(LinearLayout(context).apply {
            gravity=Gravity.CENTER_VERTICAL
            addView(arrow("返回照片") { dismiss() },LinearLayout.LayoutParams(dp(44),dp(48)))
            addView(counter,LinearLayout.LayoutParams(0,dp(48),1f))
            addView(high,LinearLayout.LayoutParams(dp(52),dp(48)))
        })
        root.addView(image,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(LinearLayout(context).apply {
            gravity=Gravity.CENTER_VERTICAL
            addView(previous,LinearLayout.LayoutParams(dp(44),dp(56)))
            addView(strip,LinearLayout.LayoutParams(0,dp(56),1f))
            addView(next,LinearLayout.LayoutParams(dp(44),dp(56)))
        },LinearLayout.LayoutParams(-1,dp(64)))
        val details=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(6),dp(10),dp(8)) }
        details.addView(name)
        details.addView(size)
        details.addView(quality)
        root.addView(details)
        root.addView(LinearLayout(context).apply {
            gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(12))
            addView(select,LinearLayout.LayoutParams(0,dp(50),.38f).apply { rightMargin=dp(8) })
            addView(import,LinearLayout.LayoutParams(0,dp(50),.62f))
        })
        root.setOnApplyWindowInsetsListener { v,insets ->
            v.setPadding(dp(6),insets.systemWindowInsetTop,dp(6),insets.systemWindowInsetBottom);insets
        }
        setContentView(root)
    }
    override fun show() { super.show();window?.setLayout(-1,-1) }
}
