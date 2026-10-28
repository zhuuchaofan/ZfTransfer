package com.xiaozhu.zftransfer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.content.res.ColorStateList
import android.view.ViewGroup
import android.app.Dialog
import android.net.Network
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.*
import io.github.noisetta.nikonpull.ptp.*

/** Screen observer for the service-owned camera connection. */
class MainActivity : Activity() {
    private val runtime get() = (application as TransferApplication).runtime
    private val cameraWifi get() = runtime.cameraWifi
    private val autoGate get() = runtime.autoGate
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
    private val imageSession get() = runtime.gallery.imageSession
    private val client get() = runtime.client
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
    private val photos get() = runtime.gallery.photos
    private var visiblePhotos = emptyList<CameraObject>()
    private var activeFilter = PhotoFilter.ALL
    private var activeFormat = MediaFormat.ALL
    private lateinit var screen: GalleryScreen
    private var rangeOutside = emptyMap<Long, CameraObject>()
    private val sheets = mutableSetOf<AppSheet>()
    private fun newSheet(title: String) = AppSheet(this,title).also { dialog ->
        sheets.add(dialog); dialog.setOnDismissListener { sheets.remove(dialog) }
    }
    private fun refreshCatalog() {
        val next = PhotoCatalog.visible(photos, imported, activeFilter, activeFormat)
        if (next != visiblePhotos) { visiblePhotos = next; range = null }
        screen.renderFilter(activeFilter, if(activeFilter==PhotoFilter.IMPORTED) localGallery.size else visiblePhotos.size, true)
    }
    private val localGallery: LocalImportGallery by lazy { LocalImportGallery(this) {
        if (!stopped && activeFilter == PhotoFilter.IMPORTED && ::grid.isInitialized) {
            screen.renderFilter(activeFilter, localGallery.size, true); updateGalleryControls()
        }
    } }
    private val imported get() = runtime.gallery.imported
    private var selectionMode = false
    private var range: RangeSelection? = null
    private var issueDialog: Dialog? = null
    private val yellow = 0xfff2c45b.toInt()
    private val muted = 0xffa1a4aa.toInt()
    private val adapter = object : BaseAdapter() {
        override fun hasStableIds() = true
        override fun getCount() = visiblePhotos.size
        override fun getItem(position: Int) = visiblePhotos[position]
        override fun getItemId(position: Int) = visiblePhotos[position].handle
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View = photoCell(visiblePhotos[position])
    }
    private lateinit var connect: Button
    private lateinit var more: Button
    private lateinit var retryTransfer: Button
    private lateinit var download: Button
    private val transfers get() = runtime.transfers
    private lateinit var progress: ProgressBar
    private val selected = linkedMapOf<Long, CameraObject>()

    private val handles get() = runtime.gallery.handles
    private val cursor get() = runtime.gallery.cursor
    private val imports get() = runtime.imports
    private val cameraIdentity get() = runtime.cameraIdentity
    private var completionCursor = 0L
    private var renderedBusy: Boolean? = null
    private var renderedRevision = -1L
    private var renderedSession = ""
    private val upgraded = mutableSetOf<Long>()
    private val images: GalleryImageLoader by lazy { GalleryImageLoader(applicationContext, runtime) {
        if (!stopped) { adapter.notifyDataSetChanged(); previewController.refresh() }
    } }
    private val previewController: PhotoPreviewController by lazy { PhotoPreviewController(this, runtime, images,
        { it in selected }, ::requestImport, ::toggle, ::showNotice) }
    private fun thumbnail(handle: Long) = images.thumbnail(handle)
    private val runtimeListener: () -> Unit = { renderRuntime() }
    private fun renderRuntime() {
        if (stopped || !::status.isInitialized) return
        status.text = runtime.message
        status.visibility = if (runtime.message.isEmpty() || (!runtime.taskState.running && runtime.connectionReady)) View.GONE else View.VISIBLE
        progress.progress = runtime.progress
        val running = runtime.taskState.running
        val busyChanged = renderedBusy != running
        renderedBusy = running
        setBusy(running)
        if (busyChanged) adapter.notifyDataSetChanged()
        updateRetryControl()
        if (renderedSession != imageSession) {
            renderedSession = imageSession; selected.clear(); selectionMode = false; upgraded.clear()
            previewController.dismiss()
        }
        if (renderedRevision != runtime.revision) {
            renderedRevision = runtime.revision
            val completed = runtime.gallery.completions.since(completionCursor)
            selected.keys.removeAll(completed.map { it.handle }.toSet())
            if (completed.isNotEmpty()) {
                completionCursor = completed.last().sequence
                if (selected.isEmpty()) selectionMode = false
            }
            refreshCatalog()
            updateSelection()
            previewController.refresh()
            photos.filter { it.handle in imported && upgraded.add(it.handle) }.forEach {
                images.upgradeGrid(it, imports.key(it), imageSession)
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
        screen = GalleryScreen(this, adapter, object : GalleryScreen.Actions {
            override fun settings() = showSettings()
            override fun cameraStatus() { if (client == null && !busy.get()) connectCamera() else showSettings() }
            override fun connect() = connectCamera()
            override fun selectAll() { if (!busy.get()) {
                selectionMode = true; visiblePhotos.forEach { selected[it.handle] = it }; updateSelection()
            } }
            override fun cancelSelection() { if (!busy.get()) { selected.clear(); selectionMode = false; updateSelection() } }
            override fun preview(handle: Long) {
                if(activeFilter==PhotoFilter.IMPORTED) { localGallery.open(handle.toInt()); return }
                val index=visiblePhotos.indexOfFirst { it.handle==handle }
                if(!busy.get() && index>=0) previewController.open(index,visiblePhotos,activeFilter,activeFormat)
            }
            override fun toggle(index: Int) { visiblePhotos.getOrNull(index)?.let { toggle(it) } }
            override fun rangeStart(index: Int) {
                selectionMode = true
                val visibleHandles = visiblePhotos.map { it.handle }.toSet()
                rangeOutside = selected.filterKeys { it !in visibleHandles }
                range = RangeSelection(visiblePhotos.indices.filter { visiblePhotos[it].handle in selected }.toSet(), index)
                applyRange(index)
            }
            override fun rangeMove(index: Int) = applyRange(index)
            override fun format() = showFormatFilter()
            override fun filter(filter: PhotoFilter) {
                if(activeFilter==filter) return
                activeFilter=filter; range=null; grid.endBrowseGesture()
                grid.adapter=if(filter==PhotoFilter.IMPORTED) localGallery.adapter else adapter
                refreshCatalog(); updateSelection(); setBusy(busy.get()); grid.setSelection(0)
                if(filter==PhotoFilter.IMPORTED) localGallery.reload()
            }

            override fun wifiProfile() = showWifiProfile()
            override fun wifiSettings() { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            override fun readMore() = readPage()
            override fun importSelected() = downloadSelected()
            override fun retry() {
                if (!transfers.queue.canRetry(cameraIdentity)) showNotice("请连接原来的相机", "未完成任务属于原相机，请连接原相机后再重试。")
                else startImport(transfers.queue.remaining, true, retry = true)
            }
        })
        screen.localAccess.setOnClickListener {
            requestPermissions(arrayOf(if(android.os.Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES" else "android.permission.READ_EXTERNAL_STORAGE"),43)
        }
        status = screen.status
        grid = screen.grid
        grid.onReadMore = { if(activeFilter!=PhotoFilter.IMPORTED && activeFormat.available) runtime.readPage(automatic = true) }
        grid.onBrowseGesture = runtime.gallery::beginBrowseGesture
        selectionLabel = screen.selectionLabel
        cameraLabel = screen.cameraLabel
        selectAll = screen.selectAll
        cancelSelection = screen.cancelSelection
        footer = screen.footer
        emptyTitle = screen.emptyTitle
        emptyHint = screen.emptyHint
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
        log("ZfTransfer ${packageManager.getPackageInfo(packageName, 0).versionName}; Android ${android.os.Build.VERSION.RELEASE}")
    }

    private fun dp(value: Int) = AppUi.dp(this,value)
    private fun showFormatFilter() {
        val sheet=newSheet("格式筛选")
        sheet.message("与上方导入状态组合筛选。目前支持 JPEG；RAW、视频、HIF / HEIF 的浏览和导入后续开放。")
        MediaFormat.entries.forEach { format ->
            sheet.row((if(activeFormat==format) "✓  " else "") + format.label + if(format.available) "" else " · 后续支持") {
                sheet.dismiss()
                if(activeFormat!=format) {
                    activeFormat=format;range=null;grid.endBrowseGesture()
                    localGallery.setFormat(format)
                    refreshCatalog();updateSelection();setBusy(busy.get());grid.setSelection(0)
                }
            }
        }
        sheet.action("取消");sheet.show()
    }
    private fun showSettings() {
        val sheet = newSheet("相机与应用")
        sheet.row("相机 Wi-Fi 配置") { showWifiProfile() }
        val automatic = Switch(this).apply {
            text = "自动连接"; textSize = 15f; setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("auto_connect", true)
            buttonTintList = ColorStateList.valueOf(yellow)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("auto_connect", checked).apply()
                if (checked) { userStopped=false; autoGate.reset(); profileAttempted=false; cameraWifi.schedule() }
                runtime.report(if (checked) "已开启自动连接" else "已关闭自动连接")
            }
        }
        sheet.body.addView(automatic, LinearLayout.LayoutParams(-1,dp(52)))
        sheet.row("Wi-Fi 设置") { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        sheet.row("后台连接设置") {
            showIssuePanel(UserIssue("保持后台连接", "允许通知，并将应用省电策略设为「无限制」，以便熄屏后保持连接。持续连接会增加手机和相机耗电，实际效果也受相机无线设置影响。", Recovery.APP_SETTINGS), "打开应用设置")
        }
        sheet.row("关于") { showLicense() }
        sheet.row("忘记相机 Wi-Fi") {
            newSheet("忘记相机 Wi-Fi？").apply {
                message("仅删除本应用保存的名称和密码，不删除系统 Wi-Fi 或导入照片。")
                action("忘记配置",true) { wifiStore.clear();profileAttempted=true;pendingProfile=null;runtime.report("已忘记保存的相机 Wi-Fi") }
                action("取消");show()
            }
        }
        sheet.row("断开相机",true) { runtime.stop();stopService(Intent(this,CameraConnectionService::class.java)) }
        sheet.show()
    }
    private fun showWifiProfile() {
        val saved = wifiStore.load()
        val sheet = newSheet("连接相机 Wi-Fi")
        sheet.message("填写相机屏幕上的名称和密码，首次连接需在系统弹窗中确认。")
        sheet.body.addView(AppUi.text(this,"Wi-Fi 名称"))
        val ssid = AppUi.input(this,"相机 Wi-Fi 名称").apply { setText(saved?.ssid.orEmpty()) }
        sheet.body.addView(ssid,LinearLayout.LayoutParams(-1,dp(50)))
        sheet.body.addView(AppUi.text(this,"密码"),LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10) })
        val password = AppUi.input(this,if(saved==null) "Wi-Fi 密码" else "留空使用已保存的密码",true)
        sheet.body.addView(password,LinearLayout.LayoutParams(-1,dp(50)))
        val error = AppUi.text(this,"",12f,0xffff847b.toInt()).apply { visibility=View.GONE }
        sheet.body.addView(error)
        val remember = Switch(this).apply {
            text="加密保存，下次自动连接";textSize=14f;setTextColor(Color.WHITE);isChecked=true
            buttonTintList=ColorStateList.valueOf(yellow)
        }
        sheet.body.addView(remember,LinearLayout.LayoutParams(-1,dp(56)))
        sheet.action("保存并连接",true,close=false) {
            val name=ssid.text.toString().trim()
            val secret=password.text.toString().ifEmpty { saved?.takeIf { it.ssid==name }?.password.orEmpty() }
            val profile=WifiProfile(name,secret)
            if (!profile.valid()) { error.text="请输入有效名称及 8–63 位 Wi-Fi 密码";error.visibility=View.VISIBLE }
            else if (remember.isChecked && runCatching { wifiStore.save(profile) }.isFailure) {
                error.text="无法加密保存，请取消保存后重试";error.visibility=View.VISIBLE
            } else {
                if(!remember.isChecked) wifiStore.clear()
                prefs.edit().putBoolean("auto_connect",remember.isChecked).apply()
                userStopped=false;autoGate.reset();profileAttempted=true;sheet.dismiss();requestWifi(profile,false)
            }
        }
        sheet.action("取消")
        sheet.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        sheet.show()
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
        if(requestCode==43 && activeFilter==PhotoFilter.IMPORTED) localGallery.reload()
        if (requestCode != 41) return
        val profile = pendingProfile; pendingProfile = null
        if (permissions.indexOf(wifiPermission()).let { it >= 0 && grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED } && profile != null) requestWifi(profile, false)
        else presentIssue(UserIssue("未获得 Wi-Fi 连接权限", "可以在应用权限中开启相关权限，也可以手动连接系统 Wi-Fi 后返回应用，继续自动识别相机。", Recovery.APP_SETTINGS))
    }
    private fun applyRange(end: Int) {
        if (busy.get()) return
        val indices = range?.at(end) ?: return
        val nextHandles = indices.filter { it in visiblePhotos.indices }.map { visiblePhotos[it].handle }.toSet() + rangeOutside.keys
        if (nextHandles == selected.keys) return
        selected.clear(); selected.putAll(rangeOutside)
        indices.filter { it in visiblePhotos.indices }.forEach { selected[visiblePhotos[it].handle] = visiblePhotos[it] }
        updateSelection()
    }
    private fun toggle(obj: CameraObject) {
        if (busy.get()) return
        selectionMode = true
        if (selected.remove(obj.handle) == null) selected[obj.handle] = obj
        updateSelection()
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
        runtime.refreshImportEvidence()
        if(activeFilter==PhotoFilter.IMPORTED) localGallery.reload()
    }
    override fun onPause() { resumed = false; runtime.foreground = false; super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) cameraWifi.schedule()
    }
    private val cells by lazy { GalleryPhotoCell(this) }
    private fun photoCell(obj: CameraObject): View = cells.create(obj, thumbnail(obj.handle),
        obj.handle in selected, obj.handle in imported, !busy.get(), grid.height) { toggle(obj) }
    private fun ui(action: () -> Unit) { runOnUiThread { if (!isDestroyed && !stopped) action() } }
    private fun log(message: String) = runtime.log(message)
    private fun presentIssue(issue: UserIssue, interactive: Boolean = true) { ui {
        report("${issue.title}\n${issue.message}")
        status.setTextColor(yellow)
        if (interactive) showIssuePanel(issue)
    } }
    private fun showNotice(title: String, message: String) = showIssuePanel(UserIssue(title, message, Recovery.NONE))
    private fun showIssuePanel(issue: UserIssue, primaryLabel: String? = null) {
        if (isFinishing || stopped) return
        issueDialog?.dismiss()
        val sheet=newSheet(issue.title);issueDialog=sheet
        sheet.message(issue.message)
        val primary=primaryLabel ?: when(issue.recovery) {
            Recovery.WIFI_SETTINGS -> "检查 Wi-Fi"
            Recovery.APP_SETTINGS -> "查看应用权限"
            Recovery.STORAGE_SETTINGS -> "查看手机存储"
            Recovery.NONE -> "知道了"
        }
        sheet.action(primary,true) {
            val intent=when(issue.recovery) {
                Recovery.WIFI_SETTINGS -> Intent(Settings.ACTION_WIFI_SETTINGS)
                Recovery.APP_SETTINGS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName"))
                Recovery.STORAGE_SETTINGS -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
                Recovery.NONE -> null
            }
            if(intent!=null) runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { showNotice("请手动打开系统设置","当前系统无法从应用打开该页面，请手动进入系统设置。") }
        }
        if(issue.recovery!=Recovery.NONE) sheet.action("稍后处理")
        sheet.show()
    }
    private fun report(message: String) { ui { status.text = message; status.setTextColor(muted); status.visibility = View.VISIBLE } }
    private fun setBusy(value: Boolean) {
        connect.isEnabled = !value
        more.isEnabled = !value && client != null && cursor < handles.size
        download.isEnabled = !value && client != null && selected.isNotEmpty()
        more.alpha = if (more.isEnabled) 1f else 0.4f
        download.alpha = if (download.isEnabled) 1f else 0.4f
        previewController.refreshActions()
        grid.editable = !value && activeFilter!=PhotoFilter.IMPORTED && activeFormat.available
        grid.canReadMore = activeFormat.available && activeFilter!=PhotoFilter.IMPORTED && !value && client != null && cursor < handles.size
        progress.isIndeterminate = value && runtime.taskState.transfer == null
        progress.visibility = if (value) View.VISIBLE else View.GONE
        screen.renderFilter(activeFilter,if(activeFilter==PhotoFilter.IMPORTED) localGallery.size else visiblePhotos.size,true)
        cameraLabel.text = when {
            runtime.connectionReady -> "$cameraName · 已连接"
            busy.get() || runtime.connectionPending || cameraWifi.requesting -> "$cameraName · 正在连接"
            else -> "$cameraName · 点此连接"
        }
        cameraLabel.setTextColor(if (runtime.connectionReady) yellow else muted)
        updateGalleryControls()
        updateRetryControl()
    }
    private fun connectCamera(networkOverride: Network? = null, automatic: Boolean = false) = runtime.connectCamera(networkOverride, automatic)
    private fun readPage() = runtime.readPage()
    private fun updateSelection() {
        previewController.refreshActions()
        download.text = if (selected.isEmpty()) "导入原图" else "导入原图（${selected.size}）"
        download.isEnabled = selected.isNotEmpty() && client != null && !busy.get()
        download.alpha = if (download.isEnabled) 1f else 0.4f
        val hidden = selected.keys.count { handle -> visiblePhotos.none { it.handle==handle } }
        selectionLabel.text = if (selected.isEmpty()) "长按滑动多选" else "已选 ${selected.size} 张 · ${"%.1f".format(selected.values.sumOf { it.size } / 1048576.0)} MB" + if(hidden>0) "\n含其他筛选 $hidden 张" else ""
        updateGalleryControls()
        adapter.notifyDataSetChanged()
    }
    private fun updateGalleryControls() {
        if (!::footer.isInitialized) return
        val local=activeFilter==PhotoFilter.IMPORTED
        screen.formatControl.text="格式：${activeFormat.label} ▾"
        screen.catalogSummary.visibility=if(!local && runtime.connectionReady) View.VISIBLE else View.GONE
        screen.catalogSummary.text=CameraCatalogSummary.text(handles.size,cursor,photos.size)
        screen.headingTitle.text=if(local) "手机照片" else "相机照片"
        screen.localAccess.visibility=if(local && !localGallery.hasFullAccess) View.VISIBLE else View.GONE
        if(!activeFormat.available) {
            footer.visibility=View.GONE;selectAll.visibility=View.GONE;cancelSelection.visibility=View.GONE
            more.visibility=View.GONE;screen.connectionActions.visibility=View.GONE;screen.emptyReadMore.visibility=View.GONE
            screen.localAccess.visibility=View.GONE;status.visibility=View.GONE;progress.visibility=View.GONE
            emptyTitle.text="${activeFormat.label} · 后续支持"
            emptyHint.text="该格式的浏览和导入尚未开放，可切换为 JPEG 或全部格式"
            return
        }
        if(local) {
            footer.visibility=View.GONE;selectAll.visibility=View.GONE;cancelSelection.visibility=View.GONE
            more.visibility=View.GONE;screen.connectionActions.visibility=View.GONE;screen.emptyReadMore.visibility=View.GONE
            progress.visibility=View.GONE;status.visibility=View.GONE
            emptyTitle.text=if(localGallery.loading) "正在检查手机照片" else "暂无已导入照片"
            emptyHint.text="已保存到手机的照片会显示在这里，无需连接相机"
            return
        }
        val hasMore=client!=null && cursor<handles.size
        val connectionEmpty=photos.isEmpty() && client==null
        footer.visibility=if(photos.isEmpty()) View.GONE else View.VISIBLE
        selectAll.visibility=if(visiblePhotos.isEmpty()) View.GONE else View.VISIBLE
        cancelSelection.visibility=if(selectionMode) View.VISIBLE else View.GONE
        more.visibility=View.GONE
        screen.connectionActions.visibility=if(connectionEmpty) View.VISIBLE else View.GONE
        screen.emptyReadMore.visibility=if(!connectionEmpty && visiblePhotos.isEmpty() && hasMore) View.VISIBLE else View.GONE
        screen.emptyReadMore.isEnabled=!busy.get()
        emptyTitle.text=when {
            connectionEmpty -> "把照片，带回手机"
            busy.get() -> "正在读取照片"
            activeFilter==PhotoFilter.IMPORTED -> "暂时没有已导入照片"
            activeFilter==PhotoFilter.NOT_IMPORTED -> "暂时没有未导入照片"
            else -> "没有可浏览的照片"
        }
        emptyHint.text=when {
            connectionEmpty -> "连接相机 Wi-Fi 后，将自动识别"
            hasMore -> "已读取的照片中暂无匹配，可继续读取更多"
            activeFilter!=PhotoFilter.ALL -> "换个筛选看看"
            else -> "相机中暂未找到 JPEG 照片"
        }
    }
    private fun requestImport(items: List<CameraObject>) {
        if (busy.get() || client == null || items.isEmpty()) return
        if (transfers.queue.remaining.isNotEmpty()) {
            newSheet("替换未完成任务？").apply {
                message("仍有 ${transfers.queue.remaining.size} 张未完成。新任务会替换剩余任务，已导入照片保留。")
                action("开始新任务",true) { checkDuplicates(items) };action("取消");show()
            }
            return
        }
        checkDuplicates(items)
    }
    private fun checkDuplicates(items: List<CameraObject>) {
        runtime.checkImported(items) { duplicates ->
            if (!stopped && !isDestroyed) {
                if (duplicates.isEmpty()) startImport(items, false)
                else showDuplicateChoices(items, duplicates.size)
            }
        }
    }
    private fun showDuplicateChoices(items: List<CameraObject>, duplicateCount: Int) {
        newSheet("其中 $duplicateCount 张照片已导入").apply {
            message("已确认这些文件仍在手机中，再次导入会保存新的副本。")
            action("跳过已导入",true) { startImport(items,true) }
            action("再次导入") { startImport(items,false) }
            action("取消");show()
        }
    }
    private fun downloadSelected() {
        requestImport(selected.values.toList())
    }
    private fun updateRetryControl() {
        if (!::retryTransfer.isInitialized) return
        val count = transfers.queue.remaining.size
        retryTransfer.visibility = if (runtime.taskState.showRemaining(count)) View.VISIBLE else View.GONE
        retryTransfer.text = "继续导入未完成的 $count 张"
        retryTransfer.isEnabled = !busy.get() && client != null
    }
    private fun startImport(items: List<CameraObject>, skipExisting: Boolean, retry: Boolean = false) = runtime.startImport(items, skipExisting, retry)
    private fun showLicense() {
        newSheet("关于 Zf 传图").apply {
            message("版本 ${packageManager.getPackageInfo(packageName,0).versionName?.removeSuffix("-test")}\n遵循 GPL-3.0 开源许可证。\n基于 noisetta/Nikon-Pull 与 jaminxx-aha/ZPill 构建。\n源代码：github.com/zhuuchaofan/ZfTransfer")
            action("开源许可证") {
                newSheet("开源许可证").apply { message(assets.open("LICENSE.txt").bufferedReader().use { it.readText() });action("关闭");show() }
            }
            action("关闭");show()
        }
    }
    override fun onDestroy() {
        stopped = true
        previewController.close()
        sheets.toList().forEach { it.dismiss() }
        runtime.detach(runtimeListener)
        images.close(); localGallery.close()
        super.onDestroy()
    }
}
