package com.xiaozhu.zftransfer

import android.app.Activity
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
    private var previewPhotos = emptyList<CameraObject>()
    private val photos get() = previewPhotos
    private var filterAtOpen = PhotoFilter.ALL
    private var formatAtOpen = MediaFormat.ALL
    private val imported get() = runtime.gallery.imported
    private val busy get() = runtime.busy
    private val client get() = runtime.client
    private val imageSession get() = runtime.gallery.imageSession
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
    private lateinit var previewImport: ProgressActionButton
    private lateinit var previewCounter: TextView
    private lateinit var previewStrip: PhotoFilmstrip
    private lateinit var previewSelect: TextView
    private lateinit var previewPrevious: ImageButton
    private lateinit var previewNext: ImageButton
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
    private fun updateOriginalAction() {
        previewHigh.text = "原图"
        previewHigh.visibility = View.VISIBLE
        previewHigh.isEnabled = !previewChecking && !previewOriginalAvailable && !busy.get() && client != null
        previewHigh.alpha = if (previewHigh.isEnabled) 1f else 0.4f
        previewHigh.contentDescription = if (previewOriginalAvailable) "当前为原图预览" else "查看原图"
    }
    private fun confirmOriginalPreview() {
        val obj=photos.getOrNull(index) ?: return
        if(!previewHigh.isEnabled) return
        if(obj.handle in imported) { loadDetailedPreview(obj,previewGeneration);return }
        val generation=previewGeneration
        val size="%.1f".format(obj.size/1048576.0)
        originalDialog?.dismiss()
        val sheet=AppSheet(activity,"查看原图");originalDialog=sheet
        sheet.message("需要从相机加载约 $size MB，用于查看细节。查看原图不会保存到手机相册。")
        sheet.action("加载原图（$size MB）",true) {
            if(generation==previewGeneration && !previewOriginalAvailable && !busy.get()) downloadDetailedPreview()
        }
        sheet.action("取消");sheet.show()
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
    fun open(position: Int, items: List<CameraObject>, filter: PhotoFilter, format: MediaFormat = MediaFormat.ALL) {
        previewPhotos=items.toList();filterAtOpen=filter;formatAtOpen=format;show(position)
    }
    private fun show(position: Int) {
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
        previewImport = dialog.import; previewSelect = dialog.select; previewCounter = dialog.counter
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
        previewImport.setTransferProgress(if(runtime.taskState.running) runtime.taskState.transfer?.percentage else null)
        previewImport.isEnabled = !busy.get() && client != null
        previewSelect.isEnabled = !busy.get()
        previewSelect.alpha = if(previewSelect.isEnabled) 1f else .4f
        previewCounter.text = "${index+1} / ${photos.size}"
    }
    private fun renderPreview() {
        val obj = photos[index]
        val generation = ++previewGeneration
        previewQuality.text = "正在加载清晰预览…"
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
        previewPhotos=PhotoCatalog.appendPreview(previewPhotos,runtime.gallery.photos,imported,filterAtOpen,formatAtOpen)
        refreshActions()
        updateOriginalAction()
        previewStrip.submit(photos, index, ::thumbnail, ::show)
        previewNext.isEnabled = index < photos.lastIndex
        previewNext.alpha = if (previewNext.isEnabled) 1f else 0.3f
    }
    fun dismiss() { originalDialog?.dismiss(); preview?.dismiss(); previewGeneration++ }
    override fun close() { stopped = true; dismiss() }
}
