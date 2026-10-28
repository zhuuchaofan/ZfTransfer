package com.xiaozhu.zftransfer

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.app.Dialog
import android.view.View
import android.widget.*
import io.github.noisetta.nikonpull.ptp.*

/** Owns the preview window and its page lifecycle; never owns the camera session. */
class PhotoPreviewController(
    private val activity: Activity,
    private val runtime: ConnectionRuntime,
    private val images: GalleryImageLoader,
    private val isSelected: (Long) -> Boolean,
    private val requestImport: (List<CameraObject>) -> Unit,
    private val toggle: (CameraObject) -> Unit,
    private val showNotice: (String, String) -> Unit,
) : AutoCloseable {
    private val photos get() = runtime.photos
    private val imported get() = runtime.imported
    private val busy get() = runtime.busy
    private val client get() = runtime.client
    private val imageSession get() = runtime.imageSession
    private val handles get() = runtime.handles
    private val cursor get() = runtime.cursor
    private val originalPreviews get() = images.originals
    var index = 0
        private set
    @Volatile private var previewGeneration = 0L
    @Volatile private var stopped = false
    private var preview: Dialog? = null
    private lateinit var previewImage: ZoomPhotoView
    private lateinit var previewName: TextView
    private lateinit var previewSize: TextView
    private lateinit var previewQuality: TextView
    private lateinit var previewHigh: TextView
    private var previewOriginalAvailable = false
    private var previewChecking = false
    private var originalDialog: Dialog? = null
    private lateinit var previewImport: Button
    private lateinit var previewStrip: PhotoFilmstrip
    private lateinit var previewSelect: TextView
    private lateinit var previewPrevious: TextView
    private lateinit var previewNext: TextView
    private val yellow = 0xfff2c45b.toInt()
    private val surfaceColor = 0xff101112.toInt()
    private val muted = 0xffa1a4aa.toInt()
    private val resources get() = activity.resources
    private fun ui(action: () -> Unit) { activity.runOnUiThread { if (!stopped && !activity.isDestroyed) action() } }
    private fun previewKey(obj: CameraObject) = images.key(obj)
    private fun thumbnail(handle: Long) = images.thumbnail(handle)
    private fun report(message: String) = runtime.report(message)
    private fun task(operation: UserOperation, onComplete: () -> Unit, action: () -> Unit) {
        runtime.task(operation, onComplete = { if (!stopped) onComplete() }, action = action)
    }
    private fun loadDetailedPreview(obj: CameraObject, generation: Long) {
        val session = imageSession
        images.loadPreview(obj, { generation == previewGeneration && !stopped && session == imageSession }) { result ->
            if (preview?.isShowing != true) { result?.bitmap?.recycle(); return@loadPreview }
            previewChecking = false
            previewOriginalAvailable = result?.original == true
            if (result != null) {
                previewImage.showPhoto(result.bitmap, detailed = true)
                previewQuality.text = result.quality
            } else previewQuality.text = "清晰预览暂不可用，可按需查看原图"
            updateOriginalAction()
        }
    }
    private fun textAction(text: String, action: () -> Unit) = label(text, 14f).apply {
        setTextColor(yellow); gravity = Gravity.CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        minHeight = dp(48); isClickable = true
        setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float) = TextView(activity).apply {
        this.text = text; textSize = size; setTextColor(Color.WHITE)
        setPadding(dp(2), dp(6), dp(2), dp(6))
    }
    private fun button(text: String, action: () -> Unit) = Button(activity).apply {
        this.text = text; isAllCaps = false; setTextColor(yellow)
        background = GradientDrawable().apply { setColor(0xff242629.toInt()); cornerRadius = dp(12).toFloat() }
        backgroundTintList = ColorStateList.valueOf(0xff242629.toInt())
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { action() }
    }
    private fun updateOriginalAction() {
        previewHigh.text = "原图"
        previewHigh.visibility = View.VISIBLE
        previewHigh.isEnabled = !previewChecking && !previewOriginalAvailable && !busy.get() && client != null
        previewHigh.alpha = if (previewHigh.isEnabled) 1f else 0.4f
        previewHigh.contentDescription = if (previewOriginalAvailable) "当前为原图预览" else "查看原图"
    }
    private fun confirmOriginalPreview() {
        val obj = photos.getOrNull(index) ?: return
        if (!previewHigh.isEnabled) return
        val generation = previewGeneration
        val size = "%.1f".format(obj.size / 1048576.0)
        originalDialog?.dismiss()
        val dialog = Dialog(activity)
        originalDialog = dialog
        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(24))
            background = GradientDrawable().apply { setColor(0xff242629.toInt()); cornerRadius = dp(20).toFloat() }
        }
        sheet.addView(label("查看原图", 21f))
        sheet.addView(label("需要从相机加载约 $size MB，用于查看细节。查看原图不会保存到手机相册。", 14f).apply { setTextColor(muted) })
        sheet.addView(button("加载原图（$size MB）") {
            dialog.dismiss()
            if (generation == previewGeneration && !previewOriginalAvailable && !busy.get()) downloadDetailedPreview()
        }.apply { backgroundTintList = ColorStateList.valueOf(yellow); setTextColor(surfaceColor) }, LinearLayout.LayoutParams(-1, dp(52)))
        sheet.addView(textAction("取消") { dialog.dismiss() }, LinearLayout.LayoutParams(-1, dp(48)))
        dialog.setContentView(sheet); dialog.show()
        dialog.window?.apply { setBackgroundDrawableResource(android.R.color.transparent); setLayout(-1, -2); setGravity(Gravity.BOTTOM); setDimAmount(0.65f) }
    }
    private fun downloadDetailedPreview() {
        if (busy.get()) return
        val obj = photos.getOrNull(index) ?: return
        val c = client ?: return
        if (obj.size !in 1..originalPreviews.budget) {
            showNotice("照片较大", "为避免占用过多临时空间，请先导入原图，再查看清晰预览。"); return
        }
        val key = previewKey(obj)
        val generation = previewGeneration
        var complete = false
        task(operation = UserOperation.PREVIEW, onComplete = {
            if (preview?.isShowing == true && photos.getOrNull(index)?.let { previewKey(it) } == key) {
                if (complete) renderPreview() else previewQuality.text = "高清加载未完成，可重连后重试"
            }
        }) {
            originalPreviews.store(key, obj.size) { output ->
                check(client === c) { "已停止高清预览" }
                var lastPercent = -1
                c.downloadObject(obj.handle, output) { transfer ->
                    val percent = (transfer.bytesTransferred * 100 / obj.size).coerceIn(0, 100).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        ui { if (generation == previewGeneration && preview?.isShowing == true) previewQuality.text = "正在加载高清预览 $percent% · 不写入相册" }
                    }
                }
            }
            complete = true
            report("原图已加载。需要保存时，请点击导入。")
        }
    }
    fun show(position: Int) {
        if (position !in photos.indices) return
        index = position
        if (preview?.isShowing == true) {
            renderPreview()
            return
        }
        val dialog = PhotoPreviewDialog(activity, ::confirmOriginalPreview,
            { show(index - 1) }, { show(index + 1) }, {
                val obj = photos[index]; requestImport(listOf(obj))
            }, { toggle(photos[index]) })
        preview = dialog
        dialog.setOnDismissListener { originalDialog?.dismiss(); previewGeneration++ }
        previewImage = dialog.image; previewStrip = dialog.strip; previewName = dialog.name
        previewQuality = dialog.quality; previewSize = dialog.size; previewHigh = dialog.high
        previewPrevious = dialog.previous; previewNext = dialog.next
        previewImport = dialog.import; previewSelect = dialog.select
        previewStrip.canReadMore = { !busy.get() && client != null && cursor < handles.size }
        previewStrip.onReadMore = { runtime.readPage(automatic = true) }
        previewStrip.onBrowseGesture = runtime::beginBrowseGesture
        renderPreview()
        dialog.show()
        refreshActions()
    }
    fun refreshActions() {
        if (preview?.isShowing != true) return
        updateOriginalAction()
        val obj = photos.getOrNull(index) ?: return
        previewName.text = obj.filename + if (obj.handle in imported) " · 已导入" else ""
        previewSelect.text = if (isSelected(obj.handle)) "移出选择" else "加入选择"
        previewImport.text = runtime.taskState.previewImportLabel(obj.handle, obj.handle in imported)
        previewImport.isEnabled = !busy.get() && client != null
        previewSelect.isEnabled = !busy.get()
    }
    private fun renderPreview() {
        val obj = photos[index]
        val generation = ++previewGeneration
        previewQuality.text = "快速缩略预览 · 正在检查本地原图"
        previewChecking = true; previewOriginalAvailable = false
        updateOriginalAction()
        previewImage.showPhoto(thumbnail(obj.handle), detailed = false)
        previewImage.contentDescription = obj.filename
        previewName.text = obj.filename + if (obj.handle in imported) " · 已导入" else ""
        previewSize.text = "JPEG · ${"%.1f".format(obj.size / 1048576.0)} MB"
        loadDetailedPreview(obj, generation)
        previewSelect.text = if (isSelected(obj.handle)) "移出选择" else "加入选择"
        previewPrevious.isEnabled = index > 0
        previewNext.isEnabled = index < photos.lastIndex
        previewPrevious.alpha = if (previewPrevious.isEnabled) 1f else 0.3f
        previewNext.alpha = if (previewNext.isEnabled) 1f else 0.3f
        previewStrip.submit(photos, index, ::thumbnail, ::show)
        refreshActions()
    }
    fun refresh() {
        if (preview?.isShowing != true) return
        refreshActions()
        updateOriginalAction()
        previewStrip.submit(photos, index, ::thumbnail, ::show)
        previewNext.isEnabled = index < photos.lastIndex
        previewNext.alpha = if (previewNext.isEnabled) 1f else 0.3f
    }
    fun dismiss() { originalDialog?.dismiss(); preview?.dismiss(); previewGeneration++ }
    override fun close() { stopped = true; dismiss() }
}
