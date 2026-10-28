package com.xiaozhu.zftransfer

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.concurrent.Executors

/** Local album controller. Historical folder files never establish camera import evidence. */
class LocalImportGallery(private val activity: Activity, private val changed: () -> Unit) : AutoCloseable {
    private val contentResolver get() = activity.contentResolver
    private data class Photo(val uri: Uri, val name: String, val size: Long, val date: Long, val confirmed: Boolean)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var allPhotos = emptyList<Photo>()
    private var photos = emptyList<Photo>()
    private var format = MediaFormat.ALL
    fun setFormat(value: MediaFormat) {
        format=value
        photos=allPhotos.filter { value==MediaFormat.ALL || MediaFormat.classify(it.name)==value }
        stripItems=photos.mapIndexed { i,p -> io.github.noisetta.nikonpull.ptp.CameraObject(i.toLong(),0,0x3801,p.size,0,p.name,"") }
        adapter.notifyDataSetChanged();changed()
    }
    private val pending = mutableSetOf<Uri>()
    private val failed = mutableSetOf<Uri>()
    private var stripItems = emptyList<io.github.noisetta.nikonpull.ptp.CameraObject>()
    private val thumbs = object : LruCache<Uri, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Uri, value: Bitmap) = value.allocationByteCount
    }
    val size get() = photos.size
    val hasFullAccess get() = activity.checkSelfPermission(if(android.os.Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES" else "android.permission.READ_EXTERNAL_STORAGE") == android.content.pm.PackageManager.PERMISSION_GRANTED
    var loading = false
        private set
    private var viewer: PhotoPreviewDialog? = null
    private var index = 0
    private var generation = 0
    val adapter = object : BaseAdapter() {
        override fun getCount() = photos.size
        override fun getItem(position: Int) = photos[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val photo = photos[position]
            val image = (recycled as? ImageView) ?: ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = AbsListView.LayoutParams(-1, (resources.displayMetrics.widthPixels - AppUi.dp(context, 22)) / 3)
                setBackgroundColor(AppUi.panel)
            }
            image.contentDescription = "预览 ${photo.name}"
            image.setImageBitmap(thumbnail(photo))
            return image
        }
    }
    fun reload() {
        if (closed || loading) return
        loading = true; changed()
        val recorded = activity.getSharedPreferences("transfers", android.content.Context.MODE_PRIVATE).all
            .filterKeys { it.startsWith("saved_") || it.startsWith("import_uri_") }
            .values.filterIsInstance<String>().toMutableSet()
        val recordedIds = recorded.map { Uri.parse(it).lastPathSegment }.toSet()
        val ownedIds = mutableSetOf<String>()
        val fullAccess = hasFullAccess
        worker.execute {
            // Earlier records store only the latest URI for each camera object. Recover older
            // published copies from this app's own album, never from filename guesses.
            runCatching {
                contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf("_id", "owner_package_name"), "relative_path = ? AND is_pending = 0 AND mime_type = ?",
                    arrayOf("Pictures/ZfTransfer/", "image/jpeg"), null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val owned = cursor.getString(1) == activity.packageName
                        if(owned) ownedIds.add(cursor.getLong(0).toString())
                        if(ImportEvidence.includeLocal(owned, fullAccess)) recorded.add(android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)).toString())
                    }
                }
            }.logFailure("Album scan failed")
            val local = recorded.mapNotNull { value ->
                runCatching {
                    val uri = Uri.parse(value)
                    require(uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY)
                    contentResolver.query(uri, arrayOf("_display_name", "_size", "is_pending", "mime_type", "date_added"), null, null, null)?.use { c ->
                        if (!c.moveToFirst() || c.getInt(2) != 0 || c.getString(3) != "image/jpeg" || c.getLong(1) <= 0) return@use null
                        val size = c.getLong(1)
                        if (contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize == size } != true) return@use null
                        Photo(uri, c.getString(0), size, c.getLong(4), ImportEvidence.confirmsLocal(uri.lastPathSegment in recordedIds, uri.lastPathSegment in ownedIds))
                    }
                }.getOrNull()
            }.sortedByDescending { it.date }
            main.post {
                if (closed) return@post
                allPhotos = local.distinctBy { it.uri.lastPathSegment }; failed.clear()
                setFormat(format)
                stripItems = photos.mapIndexed { i, p -> io.github.noisetta.nikonpull.ptp.CameraObject(i.toLong(), 0, 0x3801, p.size, 0, p.name, "") }
                adapter.notifyDataSetChanged()
                loading = false; changed()
            }
        }
    }
    private fun decode(photo: Photo, edge: Int): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, photo.uri)) { decoder, info, _ ->
        val ratio = minOf(1.0, edge.toDouble() / maxOf(info.size.width, info.size.height))
        decoder.setTargetSize(maxOf(1, (info.size.width * ratio).toInt()), maxOf(1, (info.size.height * ratio).toInt()))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    private fun thumbnail(photo: Photo): Bitmap? {
        thumbs.get(photo.uri)?.let { return it }
        if (!closed && photo.uri !in failed && pending.add(photo.uri)) worker.execute {
            val bitmap = runCatching { decode(photo, 384) }.logFailure("Local thumbnail decode failed").getOrNull()
            main.post {
                pending.remove(photo.uri)
                if (closed) { bitmap?.recycle(); return@post }
                if (bitmap != null) { thumbs.put(photo.uri, bitmap); adapter.notifyDataSetChanged(); refreshStrip() } else failed.add(photo.uri)
            }
        }
        return null
    }
    private fun refreshStrip() {
        val dialog = viewer ?: return
        if (dialog.isShowing) dialog.strip.submit(stripItems, index, { handle -> photos.getOrNull(handle.toInt())?.let(::thumbnail) }, ::open)
    }
    fun open(position: Int) {
        if (position !in photos.indices) return
        index = position
        if (viewer?.isShowing != true) {
            viewer = PhotoPreviewDialog(activity, {}, { open(index - 1) }, { open(index + 1) }, {}, {}).apply {
                high.visibility = View.GONE; select.visibility = View.GONE; import.visibility = View.GONE
                strip.contentDescription = "手机已导入照片缩略图，可左右滑动"
                setOnDismissListener { generation++ }
                show()
            }
        }
        val dialog = viewer ?: return
        val photo = photos[position]
        val request = ++generation
        dialog.counter.text = "${position + 1} / ${photos.size}"
        dialog.name.text = photo.name + if(photo.confirmed) " · 已导入" else " · 历史相册文件"
        dialog.size.text = "JPEG · ${"%.1f".format(photo.size / 1048576.0)} MB"
        dialog.quality.text = "正在加载手机原图…"
        dialog.image.showPhoto(thumbnail(photo), detailed = false)
        dialog.previous.isEnabled = position > 0; dialog.next.isEnabled = position < photos.lastIndex
        refreshStrip()
        worker.execute {
            val bitmap = runCatching { decode(photo, 2560) }.logFailure("Local preview decode failed").getOrNull()
            main.post {
                if (closed || request != generation || !dialog.isShowing) { bitmap?.recycle(); return@post }
                if (bitmap != null) {
                    dialog.image.showPhoto(bitmap, detailed = true)
                    dialog.quality.text = "本地原图预览 · 双击或双指缩放"
                } else dialog.quality.text = "手机文件不可读取，请返回刷新"
            }
        }
    }
    override fun close() {
        closed = true; generation++; viewer?.dismiss(); worker.shutdownNow(); thumbs.evictAll()
    }
}
