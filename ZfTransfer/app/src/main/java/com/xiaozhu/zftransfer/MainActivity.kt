package com.xiaozhu.zftransfer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.ViewGroup
import android.app.Dialog
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.net.wifi.WifiInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.*
import io.github.noisetta.nikonpull.ptp.*
import java.net.Inet4Address
import java.util.concurrent.Executors

/** Screen observer for the service-owned camera connection. */
class MainActivity : Activity() {
    private val runtime get() = (application as TransferApplication).runtime
    private val cameraWifi get() = runtime.cameraWifi
    private val autoGate get() = runtime.autoGate
    private val session get() = runtime.session
    private val worker get() = runtime.worker
    private val busy get() = runtime.busy
    private val wifiStore by lazy { WifiProfileStore(this) }
    private var pendingProfile: WifiProfile? = null
    private var resumed = false
    private var userStopped: Boolean
        get() = runtime.userStopped
        set(value) { runtime.userStopped = value }
    private var profileAttempted: Boolean
        get() = runtime.profileAttempted
        set(value) { runtime.profileAttempted = value }
    private val imageWorker = Executors.newSingleThreadExecutor()
    private val originalPreviews by lazy { OriginalPreviewCache(java.io.File(cacheDir, "original_previews")) }
    private val imageSession get() = runtime.imageSession
    @Volatile private var previewGeneration = 0L
    private var client: PtpIpClient?
        get() = session.client
        set(value) { session.client = value }
    @Volatile private var stopped = false
    private lateinit var status: TextView
    private lateinit var grid: PhotoGrid
    private lateinit var selectionLabel: TextView
    private lateinit var cameraLabel: TextView
    private lateinit var selectAll: TextView
    private lateinit var cancelSelection: TextView
    private lateinit var footer: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptyHint: TextView
    private val cameraName get() = runtime.cameraName
    private val photos get() = runtime.photos
    private val thumbs get() = runtime.thumbs
    private val imported get() = runtime.imported
    private var selectionMode = false
    private var range: RangeSelection? = null
    private var preview: Dialog? = null
    private var issueDialog: Dialog? = null
    private var previewIndex = 0
    private lateinit var previewImage: ZoomPhotoView
    private lateinit var previewName: TextView
    private lateinit var previewSize: TextView
    private lateinit var previewQuality: TextView
    private lateinit var previewHigh: Button
    private lateinit var previewImport: Button
    private lateinit var previewStrip: PhotoFilmstrip
    private lateinit var previewSelect: TextView
    private lateinit var previewPrevious: TextView
    private lateinit var previewNext: TextView
    private val yellow = 0xfff2c45b.toInt()
    private val surfaceColor = 0xff101112.toInt()
    private val muted = 0xffa1a4aa.toInt()
    private val adapter = object : BaseAdapter() {
        override fun getCount() = photos.size
        override fun getItem(position: Int) = photos[position]
        override fun getItemId(position: Int) = photos[position].handle
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View = photoCell(photos[position])
    }
    private lateinit var host: EditText
    private lateinit var connect: Button
    private lateinit var more: Button
    private lateinit var retryTransfer: Button
    private lateinit var download: Button
    private val transfers get() = runtime.transfers
    private lateinit var progress: ProgressBar
    private val selected = linkedMapOf<Long, CameraObject>()

    private val handles get() = runtime.handles
    private val cursor get() = runtime.cursor
    private val imports get() = runtime.imports
    private val cameraIdentity get() = runtime.cameraIdentity
    private var renderedRevision = -1L
    private var renderedSession = ""
    private val upgraded = mutableSetOf<Long>()
    private val runtimeListener: () -> Unit = { renderRuntime() }
    private fun renderRuntime() {
        if (stopped || !::status.isInitialized) return
        status.text = runtime.message
        status.visibility = if (runtime.message.isEmpty()) View.GONE else View.VISIBLE
        progress.progress = runtime.progress
        if (renderedSession != imageSession) {
            renderedSession = imageSession; selected.clear(); selectionMode = false; upgraded.clear()
            preview?.dismiss(); previewGeneration++
        }
        if (renderedRevision != runtime.revision) {
            renderedRevision = runtime.revision
            selected.keys.removeAll(runtime.completed)
            runtime.completed.clear()
            setBusy(busy.get()); updateSelection()
            photos.filter { it.handle in imported && upgraded.add(it.handle) }.forEach {
                upgradeGrid(it, duplicateKey(it), imageSession)
            }
        }
        if (resumed) {
            runtime.takeIssue()?.let { presentIssue(it.first, it.second) }
            if (runtime.serviceActive && android.os.Build.VERSION.SDK_INT >= 33 && hasWindowFocus() &&
                checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED &&
                !prefs.getBoolean("notification_permission_asked", false)) {
                prefs.edit().putBoolean("notification_permission_asked", true).apply()
                requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 42)
            }
        }
    }
    private val prefs by lazy { getSharedPreferences("transfers", MODE_PRIVATE) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val screen = GalleryScreen(this, prefs.getString("host", ""), adapter, object : GalleryScreen.Actions {
            override fun settings() = showSettings()
            override fun cameraStatus() { if (client == null && !busy.get()) connectCamera() else showSettings() }
            override fun connect() = connectCamera()
            override fun selectAll() { if (!busy.get()) {
                selectionMode = true; photos.forEach { selected[it.handle] = it }; updateSelection()
            } }
            override fun cancelSelection() { if (!busy.get()) { selected.clear(); selectionMode = false; updateSelection() } }
            override fun preview(index: Int) { if (!busy.get()) showPreview(index) }
            override fun rangeStart(index: Int) {
                selectionMode = true
                range = RangeSelection(photos.indices.filter { photos[it].handle in selected }.toSet(), index)
                applyRange(index)
            }
            override fun rangeMove(index: Int) = applyRange(index)
            override fun wifiProfile() = showWifiProfile()
            override fun wifiSettings() { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            override fun readMore() = readPage()
            override fun importSelected() = downloadSelected()
            override fun retry() {
                if (!transfers.queue.canRetry(cameraIdentity)) showNotice("请连接原来的相机", "未完成任务属于原相机，请连接原相机后再重试。")
                else startImport(transfers.queue.remaining, true, retry = true)
            }
        })
        status = screen.status
        grid = screen.grid
        selectionLabel = screen.selectionLabel
        cameraLabel = screen.cameraLabel
        selectAll = screen.selectAll
        cancelSelection = screen.cancelSelection
        footer = screen.footer
        emptyTitle = screen.emptyTitle
        emptyHint = screen.emptyHint
        host = screen.host
        connect = screen.connect
        more = screen.more
        download = screen.download
        progress = screen.progress
        retryTransfer = screen.retryTransfer
        setContentView(screen.root)
        updateRetryControl()
        runtime.attach(runtimeListener)
        runtime.watch()
        renderRuntime()
        log("ZfTransfer 0.5.2; Android ${android.os.Build.VERSION.RELEASE}")
    }

    private fun resource(name: String, type: String) = resources.getIdentifier(name, type, packageName)
    private fun textAction(text: String, action: () -> Unit) = label(text, 14f).apply {
        setTextColor(yellow); gravity = Gravity.CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        minHeight = dp(48); isClickable = true
        setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Color.WHITE)
        setPadding(dp(2), dp(6), dp(2), dp(6))
    }
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; setTextColor(yellow)
        background = GradientDrawable().apply { setColor(0xff242629.toInt()); cornerRadius = dp(12).toFloat() }
        backgroundTintList = ColorStateList.valueOf(0xff242629.toInt())
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { action() }
    }
    private fun showSettings() {
        val automatic = prefs.getBoolean("auto_connect", true)
        AlertDialog.Builder(this).setTitle("相机与应用").setItems(arrayOf("相机 Wi-Fi 配置", "${if (automatic) "关闭" else "开启"}自动连接", "相机地址", "Wi-Fi 设置", "停止 / 断开", "分享日志", "关于", "忘记相机 Wi-Fi", "后台连接设置", "预览速度测试")) { _, which ->
            when (which) {
                0 -> showWifiProfile()
                1 -> {
                    prefs.edit().putBoolean("auto_connect", !automatic).apply()
                    if (!automatic) { userStopped = false; autoGate.reset(); profileAttempted = false; cameraWifi.schedule() }
                    report(if (automatic) "已关闭自动连接。可手动连接相机。" else "已开启自动连接，正在检测相机 Wi-Fi…")
                }
                2 -> showCameraAddress()
                3 -> startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                4 -> {
                    runtime.stop()
                    stopService(Intent(this, CameraConnectionService::class.java))
                    setBusy(busy.get())
                }
                5 -> shareLogs()
                6 -> showLicense()
                7 -> AlertDialog.Builder(this).setTitle("忘记相机 Wi-Fi？")
                    .setMessage("删除本应用加密保存的名称和密码，不删除系统 Wi-Fi 或导入照片。")
                    .setPositiveButton("忘记") { _, _ -> wifiStore.clear(); profileAttempted = true; pendingProfile = null; report("已忘记本应用保存的相机 Wi-Fi。") }
                    .setNegativeButton("取消", null).show()
                8 -> showIssuePanel(UserIssue("保持后台连接", "请在系统应用信息中将省电策略设为「无限制」，并允许通知，以便熄屏后保持连接和随时断开。持续连接会增加手机和相机耗电。相机关机后的无线状态仍取决于相机设置与固件。", Recovery.APP_SETTINGS), false, "打开应用设置")
                9 -> probePreview()
            }
        }.show()
    }
    private fun probePreview() {
        val c = client
        val photo = photos.getOrNull(previewIndex) ?: photos.firstOrNull()
        if (c == null || photo == null || busy.get()) {
            showNotice("请先连接并读取照片", "连接相机并打开需要测试的照片，再从菜单运行预览速度测试。")
            return
        }
        var result = ""
        task(operation = UserOperation.PREVIEW, onComplete = {
            if (result.isNotEmpty()) showNotice("预览测试结果", result)
        }) {
            check(client === c) { "请先连接相机" }
            result = PreviewProbe.measure(c, photo, ::log)
            runtime.report("预览接口测试完成，未导入照片。")
        }
    }
    private fun showCameraAddress() {
        val draft = EditText(this).apply {
            setText(host.text); hint = "相机 IPv4 地址（留空自动识别）"
            inputType = android.text.InputType.TYPE_CLASS_PHONE; setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(this).setTitle("相机地址").setView(draft)
            .setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = draft.text.toString().trim()
                if (address.isNotEmpty() && !CameraAddress.isUsable(address)) {
                    draft.error = "请输入有效的相机 IPv4 地址，或留空自动识别"
                } else {
                    host.setText(address); prefs.edit().putString("host", address).apply()
                    autoGate.reset(); dialog.dismiss(); cameraWifi.schedule()
                }
            }
        }
        dialog.show()
    }
    private fun showWifiProfile() {
        val saved = wifiStore.load()
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        panel.addView(label("填写相机屏幕上显示的 Wi-Fi 名称和密码。首次连接需在系统弹窗中确认。", 14f))
        val ssid = EditText(this).apply {
            hint = "Wi-Fi 名称（SSID）"; setSingleLine(true); setText(saved?.ssid.orEmpty())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val password = EditText(this).apply {
            hint = if (saved == null) "Wi-Fi 密码" else "留空使用已保存的密码"; setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val remember = CheckBox(this).apply { text = "加密保存，下次打开自动连接"; isChecked = true }
        panel.addView(ssid); panel.addView(password); panel.addView(remember)
        panel.addView(label("密码仅保存在本机，不写入日志。仅支持相机的 WPA2 热点。", 12f).apply { setTextColor(muted) })
        val dialog = AlertDialog.Builder(this).setTitle("连接相机 Wi-Fi").setView(panel)
            .setPositiveButton("保存并连接", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = ssid.text.toString().trim()
                val secret = password.text.toString().ifEmpty { saved?.takeIf { it.ssid == name }?.password.orEmpty() }
                val profile = WifiProfile(name, secret)
                if (!profile.valid()) { password.error = "请输入有效名称及 8–63 位 Wi-Fi 密码"; return@setOnClickListener }
                if (remember.isChecked) {
                    if (runCatching { wifiStore.save(profile) }.isFailure) { password.error = "无法加密保存，请取消勾选后重试"; return@setOnClickListener }
                } else wifiStore.clear()
                prefs.edit().putBoolean("auto_connect", remember.isChecked).apply()
                userStopped = false; autoGate.reset(); profileAttempted = true
                dialog.dismiss(); requestWifi(profile, false)
            }
        }
        dialog.show()
    }
    private fun wifiPermission(): String = if (android.os.Build.VERSION.SDK_INT >= 33) "android.permission.NEARBY_WIFI_DEVICES" else "android.permission.ACCESS_FINE_LOCATION"
    private fun requestWifi(profile: WifiProfile, automatic: Boolean) {
        if (busy.get()) { report("请等待当前读取或传输完成后再连接 Wi-Fi。"); return }
        if (checkSelfPermission(wifiPermission()) != PackageManager.PERMISSION_GRANTED) {
            if (!automatic) {
                pendingProfile = profile
                val permissions = if (android.os.Build.VERSION.SDK_INT in 31..32) arrayOf(wifiPermission(), "android.permission.ACCESS_COARSE_LOCATION") else arrayOf(wifiPermission())
                requestPermissions(permissions, 41)
            }
            return
        }
        runtime.requestWifi(profile, automatic)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 41) return
        val profile = pendingProfile; pendingProfile = null
        if (permissions.indexOf(wifiPermission()).let { it >= 0 && grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED } && profile != null) requestWifi(profile, false)
        else presentIssue(UserIssue("未获得 Wi-Fi 连接权限", "可以在应用权限中开启相关权限，也可以手动连接系统 Wi-Fi 后返回应用，继续自动识别相机。", Recovery.APP_SETTINGS))
    }
    private fun applyRange(end: Int) {
        if (busy.get()) return
        val indices = range?.at(end) ?: return
        val nextHandles = indices.filter { it in photos.indices }.map { photos[it].handle }.toSet()
        if (nextHandles == selected.keys) return
        selected.clear()
        indices.filter { it in photos.indices }.forEach { selected[photos[it].handle] = photos[it] }
        updateSelection()
    }
    private fun toggle(obj: CameraObject) {
        if (busy.get()) return
        selectionMode = true
        if (selected.remove(obj.handle) == null) selected[obj.handle] = obj
        updateSelection()
    }
    private fun thumbnailFile(handle: Long, session: String = imageSession) = java.io.File(cacheDir, "thumb_${session}_$handle.jpg")
    private fun previewKey(obj: CameraObject) = duplicateKey(obj) ?: "$imageSession|${obj.storageId}|${obj.handle}|${obj.filename}|${obj.captureDate}|${obj.size}"
    private fun decodePreview(source: ImageDecoder.Source, maxEdge: Int): Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val scale = minOf(1.0, maxEdge.toDouble() / maxOf(info.size.width, info.size.height))
        decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    private fun upgradeGrid(obj: CameraObject, recordKey: String?, session: String) {
        imageWorker.execute {
            if (imageSession != session || stopped) return@execute
            val uri = verifiedSavedUri(obj, recordKey) ?: return@execute
            val bitmap = runCatching { decodePreview(ImageDecoder.createSource(contentResolver, uri), 512) }.getOrNull() ?: return@execute
            if (imageSession != session || stopped) { bitmap.recycle(); return@execute }
            runCatching { thumbnailFile(obj.handle, session).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) } }
            ui { if (imageSession == session) { thumbs.put(obj.handle, bitmap); adapter.notifyDataSetChanged() } }
        }
    }
    private fun loadDetailedPreview(obj: CameraObject, generation: Long) {
        val key = previewKey(obj)
        val recordKey = duplicateKey(obj)
        val session = imageSession
        imageWorker.execute {
            if (generation != previewGeneration || stopped) return@execute
            val uri = verifiedSavedUri(obj, recordKey)
            val cached = if (uri == null) originalPreviews.find(key, obj.size) else null
            val medium = if (uri == null && cached == null) runtime.mediumPreviews.findCompleted(key) else null
            val source = when {
                uri != null -> ImageDecoder.createSource(contentResolver, uri)
                cached != null -> ImageDecoder.createSource(cached)
                medium != null -> ImageDecoder.createSource(medium)
                else -> null
            }
            val bitmap = source?.let { runCatching { decodePreview(it, 2560) }.onFailure { log("Preview decode failed: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull() }
            if (bitmap == null && medium != null) medium.delete()
            val gridBitmap = bitmap?.takeIf { maxOf(it.width, it.height) > 512 }?.let {
                val scale = 512.0 / maxOf(it.width, it.height)
                Bitmap.createScaledBitmap(it, maxOf(1, (it.width * scale).toInt()), maxOf(1, (it.height * scale).toInt()), true)
            }
            if (gridBitmap != null && imageSession == session) runCatching {
                thumbnailFile(obj.handle, session).outputStream().use { gridBitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }
            }
            ui {
                if (gridBitmap != null) {
                    if (imageSession == session) { thumbs.put(obj.handle, gridBitmap); adapter.notifyDataSetChanged() }
                    else gridBitmap.recycle()
                }
                if (generation != previewGeneration || preview?.isShowing != true) { bitmap?.recycle(); return@ui }
                if (bitmap != null) {
                    previewImage.showPhoto(bitmap, detailed = true)
                    previewQuality.text = when {
                        uri != null -> "本地原图预览 · 双击或双指缩放"
                        cached != null -> "原图临时预览 · 双击或双指缩放"
                        else -> "清晰预览 ${bitmap.width} × ${bitmap.height} · 双击或双指缩放"
                    }
                    previewHigh.visibility = if (uri != null || cached != null) View.GONE else View.VISIBLE
                    previewHigh.isEnabled = !busy.get() && client != null
                } else {
                    previewQuality.text = "正在加载清晰预览…"
                    previewHigh.visibility = View.VISIBLE
                    previewHigh.isEnabled = !busy.get() && client != null
                    runtime.requestMediumPreview(obj, key,
                        { generation == previewGeneration && !stopped && session == imageSession },
                        { file ->
                            if (preview?.isShowing == true) {
                                if (file != null) loadDetailedPreview(obj, generation)
                                else previewQuality.text = "清晰预览暂不可用，可按需查看原图"
                            }
                        })
                }
            }
        }
    }
    private fun downloadDetailedPreview() {
        if (busy.get()) return
        val obj = photos.getOrNull(previewIndex) ?: return
        val c = client ?: return
        if (obj.size !in 1..originalPreviews.budget) {
            showNotice("照片较大", "为避免占用过多临时空间，请先导入原图，再查看清晰预览。"); return
        }
        val key = previewKey(obj)
        val generation = previewGeneration
        var complete = false
        task(operation = UserOperation.PREVIEW, onComplete = {
            if (preview?.isShowing == true && photos.getOrNull(previewIndex)?.let { previewKey(it) } == key) {
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
            report("高清预览已缓存，未写入相册，也未标记为已导入。")
        }
    }
    private fun thumbnail(handle: Long): Bitmap? {
        thumbs.get(handle)?.let { return it }
        val file = thumbnailFile(handle)
        val bitmap = if (file.exists()) runCatching { decodePreview(ImageDecoder.createSource(file), 1024) }.getOrNull() else null
        if (bitmap != null) thumbs.put(handle, bitmap)
        return bitmap
    }
    override fun onResume() {
        super.onResume()
        resumed = true
        runtime.foreground = true
        runtime.resumedAt = android.os.SystemClock.elapsedRealtime()
        runtime.watch()
        renderRuntime()
        cameraWifi.schedule()
        if (!::grid.isInitialized || busy.get()) return
        val snapshot = photos.toList()
        val identity = cameraIdentity
        worker.execute {
            val verified = snapshot.filter { savedExists(it) }.map { it.handle }.toSet()
            ui {
                if (!busy.get() && identity == cameraIdentity) {
                    imported.clear(); imported.addAll(verified); adapter.notifyDataSetChanged()
                }
            }
        }
    }
    override fun onPause() { resumed = false; runtime.foreground = false; super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) cameraWifi.schedule()
    }
    private val cells by lazy { GalleryPhotoCell(this) }
    private fun photoCell(obj: CameraObject): View = cells.create(obj, thumbnail(obj.handle),
        obj.handle in selected, obj.handle in imported, !busy.get(), grid.height) { toggle(obj) }
    private fun showPreview(index: Int) {
        if (index !in photos.indices) return
        previewIndex = index
        if (preview?.isShowing == true) {
            renderPreview()
            return
        }
        val dialog = PhotoPreviewDialog(this, ::downloadDetailedPreview,
            { showPreview(previewIndex - 1) }, { showPreview(previewIndex + 1) }, {
                val obj = photos[previewIndex]; preview?.dismiss(); requestImport(listOf(obj))
            }, { toggle(photos[previewIndex]); preview?.dismiss() })
        preview = dialog
        previewImage = dialog.image; previewStrip = dialog.strip; previewName = dialog.name
        previewQuality = dialog.quality; previewSize = dialog.size; previewHigh = dialog.high
        previewPrevious = dialog.previous; previewNext = dialog.next
        previewImport = dialog.import; previewSelect = dialog.select
        renderPreview()
        dialog.show()
    }
    private fun renderPreview() {
        val obj = photos[previewIndex]
        val generation = ++previewGeneration
        previewQuality.text = "快速缩略预览 · 正在检查本地原图"
        previewHigh.visibility = View.VISIBLE
        previewHigh.text = "查看原图（${"%.1f".format(obj.size / 1048576.0)} MB）"
        previewHigh.isEnabled = !busy.get() && client != null
        previewImage.showPhoto(thumbnail(obj.handle), detailed = false)
        previewImage.contentDescription = obj.filename
        previewName.text = obj.filename + if (obj.handle in imported) " · 已导入" else ""
        previewSize.text = "JPEG · ${"%.1f".format(obj.size / 1048576.0)} MB"
        loadDetailedPreview(obj, generation)
        previewSelect.text = if (obj.handle in selected) "移出选择" else "加入选择"
        previewPrevious.isEnabled = previewIndex > 0
        previewNext.isEnabled = previewIndex < photos.lastIndex
        previewPrevious.alpha = if (previewPrevious.isEnabled) 1f else 0.3f
        previewNext.alpha = if (previewNext.isEnabled) 1f else 0.3f
        previewStrip.submit(photos, previewIndex, ::thumbnail, ::showPreview)
    }
    private fun ui(action: () -> Unit) { runOnUiThread { if (!isDestroyed && !stopped) action() } }
    private fun log(message: String) = runtime.log(message)
    private fun presentIssue(issue: UserIssue, interactive: Boolean = true) { ui {
        report("${issue.title}\n${issue.message}")
        status.setTextColor(yellow)
        if (interactive) showIssuePanel(issue)
    } }
    private fun showNotice(title: String, message: String) = showIssuePanel(UserIssue(title, message, Recovery.NONE), false)
    private fun showIssuePanel(issue: UserIssue, diagnostics: Boolean = true, primaryLabel: String? = null) {
        if (isFinishing || stopped) return
        issueDialog?.dismiss()
        val dialog = Dialog(this)
        issueDialog = dialog
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(24), dp(22), dp(24))
            background = GradientDrawable().apply { setColor(0xff242629.toInt()); cornerRadius = dp(22).toFloat() }
        }
        sheet.addView(label(issue.title, 23f))
        sheet.addView(label(issue.message, 15f).apply {
            setTextColor(muted); setLineSpacing(dp(4).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        val primary = primaryLabel ?: when (issue.recovery) {
            Recovery.WIFI_SETTINGS -> "检查 Wi-Fi 连接"
            Recovery.APP_SETTINGS -> "查看应用权限"
            Recovery.STORAGE_SETTINGS -> "查看手机存储"
            Recovery.NONE -> "知道了"
        }
        sheet.addView(button(primary) {
            dialog.dismiss()
            val intent = when (issue.recovery) {
                Recovery.WIFI_SETTINGS -> Intent(Settings.ACTION_WIFI_SETTINGS)
                Recovery.APP_SETTINGS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                Recovery.STORAGE_SETTINGS -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
                Recovery.NONE -> null
            }
            if (intent != null) runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { showNotice("请手动打开系统设置", "当前系统无法从应用打开该页面，请手动进入系统设置后重试。") }
        }.apply { backgroundTintList = ColorStateList.valueOf(yellow); setTextColor(surfaceColor) }, LinearLayout.LayoutParams(-1, dp(52)))
        if (diagnostics) sheet.addView(textAction("分享诊断日志") { dialog.dismiss(); shareLogs() }, LinearLayout.LayoutParams(-1, dp(48)))
        if (issue.recovery != Recovery.NONE) sheet.addView(textAction("稍后处理") { dialog.dismiss() }.apply { setTextColor(muted) }, LinearLayout.LayoutParams(-1, dp(48)))
        dialog.setContentView(sheet); dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(-1, -2); setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(0.65f)
        }
    }
    private fun report(message: String) { ui { status.text = message; status.setTextColor(muted); status.visibility = View.VISIBLE } }
    private fun task(operation: UserOperation = UserOperation.READ_PHOTOS, silentErrors: Boolean = false, onComplete: () -> Unit = {}, action: () -> Unit) {
        runtime.task(operation, silentErrors, { if (!stopped && !isDestroyed) onComplete() }, action)
    }
    private fun setBusy(value: Boolean) {
        connect.isEnabled = !value
        host.isEnabled = !value
        more.isEnabled = !value && client != null && cursor < handles.size
        download.isEnabled = !value && client != null && selected.isNotEmpty()
        more.alpha = if (more.isEnabled) 1f else 0.4f
        download.alpha = if (download.isEnabled) 1f else 0.4f
        if (::previewHigh.isInitialized) previewHigh.isEnabled = !value && client != null
        if (::previewImport.isInitialized) previewImport.isEnabled = !value && client != null
        if (::previewSelect.isInitialized) previewSelect.isEnabled = !value
        grid.editable = !value
        progress.visibility = if (value) View.VISIBLE else View.GONE
        cameraLabel.text = if (client == null) "$cameraName · 点此连接" else "$cameraName · 已连接"
        cameraLabel.setTextColor(if (client == null) muted else yellow)
        connect.visibility = if (client == null) View.VISIBLE else View.GONE
        emptyTitle.text = if (client == null) "让照片，回到你的生活" else "正在寻找相机中的照片"
        emptyHint.text = if (client == null) "连接相机 Wi-Fi 后，将自动识别\n也可在应用内设置 Wi-Fi 名和密码" else "仅显示 JPEG 原图；可继续读取下一批"
        updateGalleryControls()
        updateRetryControl()
        adapter.notifyDataSetChanged()
    }
    private fun connectCamera(networkOverride: Network? = null, automatic: Boolean = false) = runtime.connectCamera(networkOverride, automatic)
    private fun readPage() = runtime.readPage()
    private fun updateSelection() {
        download.text = if (selected.isEmpty()) "导入原图" else "导入原图（${selected.size}）"
        download.isEnabled = selected.isNotEmpty() && client != null && !busy.get()
        download.alpha = if (download.isEnabled) 1f else 0.4f
        selectionLabel.text = if (selected.isEmpty()) "长按滑动多选" else "已选 ${selected.size} 张\n${"%.1f".format(selected.values.sumOf { it.size } / 1048576.0)} MB"
        updateGalleryControls()
        adapter.notifyDataSetChanged()
    }
    private fun updateGalleryControls() {
        if (!::footer.isInitialized) return
        footer.visibility = if (photos.isEmpty()) View.GONE else View.VISIBLE
        selectAll.visibility = if (photos.isEmpty()) View.GONE else View.VISIBLE
        cancelSelection.visibility = if (selectionMode) View.VISIBLE else View.GONE
        more.visibility = if (client != null && cursor < handles.size) View.VISIBLE else View.GONE
    }
    private fun savedExists(obj: CameraObject): Boolean = verifiedSavedUri(obj) != null
    private fun verifiedSavedUri(obj: CameraObject, recordKey: String? = duplicateKey(obj)): Uri? = imports.verifiedUri(obj, recordKey)
    private fun requestImport(items: List<CameraObject>) {
        if (busy.get() || client == null || items.isEmpty()) return
        if (transfers.queue.remaining.isNotEmpty()) {
            AlertDialog.Builder(this).setTitle("替换未完成任务？")
                .setMessage("当前仍有 ${transfers.queue.remaining.size} 张未完成。开始新任务会替换剩余任务记录，已导入照片保留。")
                .setPositiveButton("开始新任务") { _, _ -> checkDuplicates(items) }
                .setNegativeButton("取消", null).show()
            return
        }
        checkDuplicates(items)
    }
    private fun checkDuplicates(items: List<CameraObject>) {
        var checked = false
        var duplicates = emptySet<Long>()
        task(operation = UserOperation.IMPORT, onComplete = {
            if (checked && client != null) {
                items.forEach { if (it.handle in duplicates) imported.add(it.handle) else imported.remove(it.handle) }
                adapter.notifyDataSetChanged()
                if (duplicates.isEmpty()) startImport(items, false)
                else showDuplicateChoices(items, duplicates.size)
            }
        }) { duplicates = items.filter { savedExists(it) }.map { it.handle }.toSet(); checked = true }
    }
    private fun showDuplicateChoices(items: List<CameraObject>, duplicateCount: Int) {
        val dialog = Dialog(this)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
            background = GradientDrawable().apply { setColor(0xff242629.toInt()); cornerRadius = dp(20).toFloat() }
        }
        sheet.addView(label("所选照片中，$duplicateCount 张已导入", 21f))
        sheet.addView(label("已确认手机中仍保留这些文件。再次导入会新增副本。", 14f))
        sheet.addView(button("跳过已导入，导入其余 ${items.size - duplicateCount} 张") {
            dialog.dismiss(); startImport(items, true)
        }.apply { backgroundTintList = ColorStateList.valueOf(yellow); setTextColor(surfaceColor) }, LinearLayout.LayoutParams(-1, dp(56)))
        sheet.addView(button("再次导入全部 ${items.size} 张") {
            dialog.dismiss(); startImport(items, false)
        }, LinearLayout.LayoutParams(-1, dp(56)))
        sheet.addView(button("取消") { dialog.dismiss() }, LinearLayout.LayoutParams(-1, dp(48)))
        dialog.setContentView(sheet)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(-1, -2); setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.65f)
        }
    }
    private fun downloadSelected() {
        requestImport(selected.values.toList())
    }
    private fun updateRetryControl() {
        if (!::retryTransfer.isInitialized) return
        val count = transfers.queue.remaining.size
        retryTransfer.visibility = if (count > 0) View.VISIBLE else View.GONE
        retryTransfer.text = "重试未完成（$count 张）"
        retryTransfer.isEnabled = !busy.get() && client != null
    }
    private fun startImport(items: List<CameraObject>, skipExisting: Boolean, retry: Boolean = false) = runtime.startImport(items, skipExisting, retry)
    private fun duplicateKey(obj: CameraObject): String? = imports.key(obj)
    private fun shareLogs() {
        val text = runtime.logText()
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
        }, "分享连接日志"))
    }
    private fun showLicense() {
        val text = assets.open("LICENSE.txt").bufferedReader().use { it.readText() }
        AlertDialog.Builder(this).setTitle("Zf 传图 · 开源测试版").setMessage(
            "本应用无担保。遵循 GPL-3.0，可依许可证修改和分发。\n协议核心改编自 noisetta/Nikon-Pull，Zf 连接参考 jaminxx-aha/ZPill（MIT）。\n完整源码随测试包提供。\n\n$text"
        ).setPositiveButton("关闭", null).show()
    }
    override fun onDestroy() {
        stopped = true
        preview?.dismiss()
        issueDialog?.dismiss()
        runtime.detach(runtimeListener)
        previewGeneration++
        imageWorker.shutdownNow()
        super.onDestroy()
    }
    companion object {
        private fun validIpv4(value: String): Boolean = CameraAddress.isUsable(value)
    }
}
