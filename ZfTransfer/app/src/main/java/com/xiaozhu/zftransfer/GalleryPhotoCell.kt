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

/**
 * Photo-first cells. Filenames remain in accessibility labels rather than separate caption rows.
 * Photo cells are recycled: [bind] reuses the views the grid hands back, so scrolling allocates nothing and a newly
 * arrived thumbnail is swapped into the visible cell by [showThumbnail] without rebinding the whole grid.
 */
class GalleryPhotoCell(private val context: Context) {
    private fun dp(n: Int)=AppUi.dp(context,n)

    /** The views of one photo cell, kept in the cell's tag. */
    class Holder(val root: FrameLayout, val image: ImageView, val placeholder: TextView, val check: ImageView,
        val format: TextView, val imported: TextView, val selection: GradientDrawable) {
        var handle = Long.MIN_VALUE
            internal set
        var bitmap: Bitmap? = null
            internal set
    }

    private fun holder(): Holder {
        val cell=FrameLayout(context).apply {
            background=AppUi.shape(context,AppUi.panel,CELL_CORNER);clipToOutline=true
        }
        val image=ImageView(context).apply {
            scaleType=ImageView.ScaleType.CENTER_CROP
            // Gallery crops only: hide the camera thumbnail's embedded letterbox, never alter saved JPEGs.
            scaleX=1.15f;scaleY=1.15f
        }
        cell.addView(image,FrameLayout.LayoutParams(-1,-1))
        val placeholder=AppUi.text(context,"",11f,AppUi.muted)
        cell.addView(placeholder,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        val check=ImageView(context)
        cell.addView(check,FrameLayout.LayoutParams(dp(44),dp(44),Gravity.TOP or Gravity.RIGHT))
        val format=badge(mono=true)
        cell.addView(format,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.RIGHT).apply { rightMargin=dp(5);bottomMargin=dp(5) })
        val imported=badge(mono=false).apply { text="已导入" }
        cell.addView(imported,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.LEFT).apply { leftMargin=dp(5);bottomMargin=dp(5) })
        val selection=GradientDrawable().apply { setColor(Color.TRANSPARENT);setStroke(dp(3),AppUi.yellow);cornerRadius=dp(CELL_CORNER).toFloat() }
        return Holder(cell,image,placeholder,check,format,imported,selection).also { cell.tag=it }
    }

    fun bind(recycled: View?, obj: CameraObject, bitmap: Bitmap?, selected: Boolean, imported: Boolean,
        enabled: Boolean, gridHeight: Int, toggle: () -> Unit): View {
        val h=(recycled?.tag as? Holder) ?: holder()
        val formatLabel=MediaFormat.of(obj)?.label.orEmpty()
        h.handle=obj.handle
        h.image.contentDescription="预览 ${obj.filename}"
        h.placeholder.text=if(obj.canImport) "加载中" else formatLabel
        setBitmap(h,bitmap)
        val showFormat=!obj.canImport || MediaFormat.of(obj)==MediaFormat.RAW
        h.format.text=formatLabel; h.format.visibility=if(showFormat) View.VISIBLE else View.GONE
        if(obj.canImport) {
            AppUi.applySelectIcon(h.check,selected);h.check.contentDescription="选择 ${obj.filename}"
            h.check.isSelected=selected;h.check.isEnabled=enabled;h.check.setOnClickListener { toggle() }
            h.check.visibility=View.VISIBLE
            h.imported.visibility=if(imported) View.VISIBLE else View.GONE
            h.root.foreground=if(selected) h.selection else null
        } else {
            h.check.visibility=View.GONE;h.check.setOnClickListener(null)
            h.imported.visibility=View.GONE
            h.root.foreground=null
        }
        val height=cellHeight(gridHeight)
        if(h.root.layoutParams?.height!=height) h.root.layoutParams=AbsListView.LayoutParams(-1,height)
        return h.root
    }

    /** Puts a thumbnail that arrived after binding into a visible cell; false when [view] shows another photo. */
    fun showThumbnail(view: View, handle: Long, bitmap: Bitmap?): Boolean {
        val h=view.tag as? Holder ?: return false
        if(h.handle!=handle) return false
        if(h.bitmap!==bitmap) setBitmap(h,bitmap)
        return true
    }
    private fun setBitmap(h: Holder, bitmap: Bitmap?) {
        h.bitmap=bitmap
        h.image.setImageBitmap(bitmap)
        h.placeholder.visibility=if(bitmap==null) View.VISIBLE else View.GONE
    }
    private fun badge(mono: Boolean) = AppUi.text(context,"",10f).apply {
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
