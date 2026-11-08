package com.xiaozhu.zftransfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import io.github.noisetta.nikonpull.ptp.CameraObject

/** Photo-first cells. Filenames remain in accessibility labels rather than separate caption rows. */
class GalleryPhotoCell(private val context: Context) {
    private fun dp(n: Int)=AppUi.dp(context,n)
    fun create(obj: CameraObject, bitmap: Bitmap?, selected: Boolean, imported: Boolean,
        enabled: Boolean, gridHeight: Int, toggle: () -> Unit): View {
        val formatLabel=MediaFormat.of(obj)?.label.orEmpty()
        val cell=FrameLayout(context).apply {
            background=AppUi.shape(context,AppUi.panel,CELL_CORNER);clipToOutline=true
        }
        val image=ImageView(context).apply {
            scaleType=ImageView.ScaleType.CENTER_CROP;setImageBitmap(bitmap)
            // Gallery crops only: hide the camera thumbnail's embedded letterbox, never alter saved JPEGs.
            scaleX=1.15f;scaleY=1.15f
            contentDescription="预览 ${obj.filename}"
        }
        cell.addView(image,FrameLayout.LayoutParams(-1,-1))
        if(bitmap==null) cell.addView(AppUi.text(context,if(obj.canImport) "加载中" else formatLabel,11f,AppUi.muted),FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        if(!obj.canImport) {
            cell.addView(badge(formatLabel,mono=true),FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.RIGHT).apply { rightMargin=dp(5);bottomMargin=dp(5) })
            cell.layoutParams=AbsListView.LayoutParams(-1,cellHeight(gridHeight))
            return cell
        }
        val check=ImageView(context).apply {
            AppUi.applySelectIcon(this,selected);contentDescription="选择 ${obj.filename}"
            isSelected=selected;isEnabled=enabled;setOnClickListener { toggle() }
        }
        cell.addView(check,FrameLayout.LayoutParams(dp(44),dp(44),Gravity.TOP or Gravity.RIGHT))
        if(MediaFormat.of(obj)==MediaFormat.RAW) cell.addView(badge(formatLabel,mono=true),
            FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.RIGHT).apply { rightMargin=dp(5);bottomMargin=dp(5) })
        if(imported) cell.addView(badge("已导入",mono=false),
            FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.LEFT).apply { leftMargin=dp(5);bottomMargin=dp(5) })
        if(selected) cell.foreground=GradientDrawable().apply { setColor(Color.TRANSPARENT);setStroke(dp(3),AppUi.yellow);cornerRadius=dp(CELL_CORNER).toFloat() }
        cell.layoutParams=AbsListView.LayoutParams(-1,cellHeight(gridHeight))
        return cell
    }
    private fun badge(value: String, mono: Boolean) = AppUi.text(context,value,10f).apply {
        setPadding(dp(5),dp(1),dp(5),dp(1));background=AppUi.shape(context,AppUi.scrim,2)
        if(mono) typeface=AppUi.mono
    }
    /**
     * A day heading, drawn as wide as three columns; the two blank cells behind it keep the row free.
     * A strip of film edge runs along its top, so each day reads as its own roll.
     */
    fun header(day: String, count: Int, selectable: Boolean, allSelected: Boolean, width: Int, toggle: () -> Unit): View {
        val row=LinearLayout(context).apply {
            gravity=Gravity.CENTER_VERTICAL;setPadding(dp(4),dp(14),0,0)
            background=FilmEdgeDrawable(context)
            contentDescription="$day，共 $count 张";isClickable=selectable
            if(selectable) setOnClickListener { toggle() }
        }
        row.addView(AppUi.text(context,day,16f).apply { typeface=Typeface.DEFAULT_BOLD })
        row.addView(AppUi.text(context,"  %02d 张".format(count),12f,AppUi.yellow).apply { typeface=AppUi.mono })
        row.addView(View(context),LinearLayout.LayoutParams(0,1,1f))
        if(selectable) row.addView(ImageView(context).apply {
            AppUi.applySelectIcon(this,allSelected);contentDescription="选择 $day 的照片"
        },LinearLayout.LayoutParams(dp(44),dp(44)))
        row.layoutParams=AbsListView.LayoutParams(width,headerHeight)
        return row
    }
    val headerHeight get()=dp(56)
    private companion object { const val CELL_CORNER = 2 }
    fun pad(height: Int): View = View(context).apply { layoutParams=AbsListView.LayoutParams(-1,height) }
    fun cellHeight(gridHeight: Int): Int {
        val width=((context.resources.displayMetrics.widthPixels-dp(32))/3).coerceAtLeast(dp(68))
        return if(gridHeight>0) minOf(width,((gridHeight-dp(12))/4).coerceAtLeast(dp(68))) else width
    }
}
