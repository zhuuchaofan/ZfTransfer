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
    private val status get() = screen.status
    private val grid get() = screen.grid
    private val selectionLabel get() = screen.selectionLabel
    private val cameraLabel get() = screen.cameraLabel
    private val selectAll get() = screen.selectAll
    private val cancelSelection get() = screen.cancelSelection
    private val footer get() = screen.footer
    private val emptyTitle get() = screen.emptyTitle
    private val emptyHint get() = screen.emptyHint
    private val cameraName get() = runtime.cameraName
    private val photos get() = runtime.gallery.photos
    private var visiblePhotos = emptyList<CameraObject>()
    private var activeFilter = PhotoFilter.ALL
    private var activeFormat = MediaFormat.ALL
    /** Storage id of the card being shown, or null for every card. */
    private var activeCard: Long? = null
    private lateinit var screen: GalleryScreen
    private var rangeOutside = emptyMap<Long, CameraObject>()
    private val sheets = mutableSetOf<AppSheet>()
    private fun newSheet(title: String) = AppSheet(this,title).also { dialog ->
        sheets.add(dialog); dialog.setOnDismissListener { sheets.remove(dialog) }
    }
    private fun refreshCatalog() {
        val next = PhotoCatalog.visible(photos, imported, activeFilter, activeFormat, activeCard)
        if (next != visiblePhotos) { visiblePhotos = next; gridCells = DayGroups.plan(next); range = null }
        screen.renderFilter(activeFilter, if(activeFilter==PhotoFilter.IMPORTED) localGallery.size else visiblePhotos.size, true)
    }
    private val localGallery: LocalImportGallery by lazy { LocalImportGallery(this) {
        if (!stopped && activeFilter == PhotoFilter.IMPORTED && ::screen.isInitialized) {
            screen.renderFilter(activeFilter, localGallery.size, true); updateGalleryControls()
        }
    } }
    private val imported get() = runtime.gallery.imported
    private var selectionMode = false
    private var range: RangeSelection? = null
    private var issueDialog: Dialog? = null
    private val yellow = 0xfff2c45b.toInt()
    private val muted = 0xffa1a4aa.toInt()
    private var gridCells = emptyList<GridCell>()
    private val adapter = object : BaseAdapter() {
        override fun hasStableIds() = true
        override fun getCount() = gridCells.size
        override fun getItem(position: Int): Any = gridCells[position]
        override fun getItemId(position: Int): Long = when (val cell = gridCells[position]) {
            is GridCell.Photo -> visiblePhotos[cell.index].handle
            is GridCell.Header -> -1L - (cell.day.filter { it.isDigit() }.toLongOrNull() ?: 0L)
            GridCell.HeaderPad, GridCell.TailPad -> -1_000_000_000L - position
        }
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View = when (val cell = gridCells[position]) {
            is GridCell.Photo -> photoCell(visiblePhotos[cell.index])
            is GridCell.Header -> dayHeader(cell)
            GridCell.HeaderPad -> cells.pad(cells.headerHeight)
            GridCell.TailPad -> cells.pad(cells.cellHeight(grid.height))
        }
    }
    private val connect get() = screen.connect
    private val retryTransfer get() = screen.retryTransfer
    private val download get() = screen.download
    private val transfers get() = runtime.transfers
    private val progress get() = screen.progress
    private val selected = linkedMapOf<Long, CameraObject>()

    private val imports get() = runtime.imports
    private val cameraIdentity get() = runtime.cameraIdentity
    private var completionCursor = 0L
    private var renderedBusy: Boolean? = null
    private var renderedCatalog = -1
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
        if (stopped || !::screen.isInitialized) return
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
            val catalogChanged = renderedCatalog != runtime.gallery.version
            val anchor = if (catalogChanged) viewportAnchor() else null
            renderedCatalog = runtime.gallery.version
            // A card that is gone cannot stay selected.
            if (activeCard != null && activeCard !in StorageCards.slots(photos)) activeCard = null
            if (catalogChanged) {
                // Files deleted on the camera can no longer be selected.
                val present = photos.mapTo(HashSet()) { it.handle }
                selected.keys.retainAll(present)
                if (selected.isEmpty()) selectionMode = false
            }
            refreshCatalog()
            updateSelection()
            anchor?.let(::restoreViewport)
            previewController.refresh()
            photos.filter { it.handle in imported && upgraded.add(it.handle) }.forEach {
                images.upgradeGrid(it, imports.key(it), imageSession)
            }
        }
        if (resumed) {
            runtime.takeIssue()?.let { presentIssue(it.first, it.second) }
            // Wait for the camera Wi-Fi request: a second dialog now would cover the system's connect dialog.
            if (runtime.serviceActive && android.os.Build.VERSION.SDK_INT >= 33 && hasWindowFocus() && !cameraWifi.requesting &&
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
                val items = visiblePhotos.filter { it.canImport }
                if (items.isEmpty()) return
                selectionMode = true; items.forEach { selected[it.handle] = it }; updateSelection()
            } }
            override fun cancelSelection() { if (!busy.get()) { selected.clear(); selectionMode = false; updateSelection() } }
            override fun preview(handle: Long) {
                if(activeFilter==PhotoFilter.IMPORTED) { localGallery.open(handle.toInt()); return }
                val tapped=visiblePhotos.firstOrNull { it.handle==handle } ?: return
                if(!tapped.canImport) { showNotice("暂不支持预览","${MediaFormat.of(tapped)?.label.orEmpty()} 文件目前只能浏览，预览和导入将在后续版本开放。"); return }
                val items=visiblePhotos.filter { it.canImport }
                val index=items.indexOfFirst { it.handle==handle }
                if(!busy.get() && index>=0) previewController.open(index,items,activeFilter,activeFormat,activeCard)
            }
            override fun toggle(index: Int) {
                DayGroups.headerAt(gridCells, index)?.let { toggleDay(it); return }
                DayGroups.photoIndexAt(gridCells, index)?.let { visiblePhotos.getOrNull(it) }?.let { toggle(it) }
            }
            override fun rangeStart(index: Int) {
                val start = DayGroups.photoIndexAt(gridCells, index) ?: return
                selectionMode = true
                val visibleHandles = visiblePhotos.map { it.handle }.toSet()
                rangeOutside = selected.filterKeys { it !in visibleHandles }
                range = RangeSelection(visiblePhotos.indices.filter { visiblePhotos[it].handle in selected }.toSet(), start)
                applyRange(start)
            }
            override fun rangeMove(index: Int) { DayGroups.photoIndexAt(gridCells, index)?.let { applyRange(it) } }
            override fun format() = showFormatFilter()
            override fun card() = showCardFilter()
            override fun filter(filter: PhotoFilter) {
                if(activeFilter==filter) return
                activeFilter=filter; range=null
                grid.adapter=if(filter==PhotoFilter.IMPORTED) localGallery.adapter else adapter
                refreshCatalog(); updateSelection(); setBusy(busy.get()); grid.setSelection(0)
                if(filter==PhotoFilter.IMPORTED) localGallery.reload()
            }

            override fun wifiProfile() = showWifiProfile()
            override fun wifiSettings() { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            override fun importSelected() = requestImport(selected.values.toList())
            override fun retry() {
                if (!transfers.queue.canRetry(cameraIdentity)) showNotice("请连接原来的相机", "未完成任务属于原相机，请连接原相机后再重试。")
                else startImport(transfers.queue.remaining, true, retry = true)
            }
        })
        screen.localAccess.setOnClickListener {
            requestPermissions(arrayOf(if(android.os.Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES" else "android.permission.READ_EXTERNAL_STORAGE"),43)
        }
        grid.onVisibleRange = { first, visible -> focusThumbnails(first, visible) }
        setContentView(screen.root)
        updateRetryControl()
        runtime.attach(runtimeListener)
        runtime.watch()
        renderRuntime()
        log("ZfTransfer ${packageManager.getPackageInfo(packageName, 0).versionName}; Android ${android.os.Build.VERSION.RELEASE}")
    }

    private fun dp(value: Int) = AppUi.dp(this,value)
    private fun showCardFilter() {
        val slots = StorageCards.slots(photos)
        val sheet = newSheet("存储卡筛选")
        sheet.message("选“全部”时，两张卡上相同的照片只显示一次；选某张卡则只显示那张卡上的文件，可以从指定的卡导入。")
        (listOf<Long?>(null) + slots).forEach { card ->
            val count = if (card == null) CopyGroups.unique(photos) { it.handle in imported }.size else photos.count { it.storageId == card }
            sheet.row((if (activeCard == card) "✓  " else "") + StorageCards.label(slots, card) + " · $count 个") {
                sheet.dismiss()
                if (activeCard != card) {
                    activeCard = card; range = null
                    refreshCatalog(); updateSelection(); setBusy(busy.get()); grid.setSelection(0)
                }
            }
        }
        sheet.action("取消"); sheet.show()
    }
    private fun showFormatFilter() {
        val sheet=newSheet("格式筛选")
        sheet.message("与上方导入状态组合筛选。目前只有 JPEG 可以预览和导入；其他格式可以浏览和筛选，预览与导入后续开放。")
        MediaFormat.entries.forEach { format ->
            sheet.row((if(activeFormat==format) "✓  " else "") + format.label) {
                sheet.dismiss()
                if(activeFormat!=format) {
                    activeFormat=format;range=null
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
        sheet.row("检查更新") { showUpdateCheck() }
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
        val inRange = indices.filter { it in visiblePhotos.indices && visiblePhotos[it].canImport }
        val nextHandles = inRange.map { visiblePhotos[it].handle }.toSet() + rangeOutside.keys
        if (nextHandles == selected.keys) return
        selected.clear(); selected.putAll(rangeOutside)
        inRange.forEach { selected[visiblePhotos[it].handle] = visiblePhotos[it] }
        updateSelection()
    }
    /** The photo at the top of the screen and where it sits, so a refreshed list can keep the view in place. */
    private fun viewportAnchor(): Pair<Long, Int>? {
        if (activeFilter == PhotoFilter.IMPORTED || gridCells.isEmpty()) return null
        val first = grid.firstVisiblePosition
        val top = grid.getChildAt(0)?.top ?: return null
        if (first == 0 && top >= 0) return null // at the very top, new photos simply appear there
        for (i in 0 until grid.childCount) {
            val cell = gridCells.getOrNull(first + i) as? GridCell.Photo ?: continue
            return visiblePhotos[cell.index].handle to (grid.getChildAt(i)?.top ?: 0)
        }
        return null
    }
    private fun restoreViewport(anchor: Pair<Long, Int>) {
        val position = gridCells.indexOfFirst { it is GridCell.Photo && visiblePhotos[it.index].handle == anchor.first }
        if (position >= 0) grid.setSelectionFromTop(position, anchor.second)
    }
    /** Camera thumbnails are read for what is on screen first. */
    private fun focusThumbnails(first: Int, visible: Int) {
        if (activeFilter == PhotoFilter.IMPORTED) return
        val from = (first - visible).coerceAtLeast(0)
        val to = minOf(gridCells.size, first + visible * 2)
        runtime.focusThumbnails((from until to).mapNotNull { (gridCells[it] as? GridCell.Photo)?.let { cell -> visiblePhotos[cell.index].handle } })
    }
    private fun dayHeader(header: GridCell.Header): View {
        val items = DayGroups.photosOf(header, visiblePhotos).filter { it.canImport }
        val width = if (grid.width > 0) grid.width - grid.paddingLeft - grid.paddingRight else resources.displayMetrics.widthPixels - dp(16)
        return cells.header(header.day, header.count, items.isNotEmpty(), items.isNotEmpty() && items.all { it.handle in selected }, width) { toggleDay(header) }
    }
    /** Selects every importable photo of the day, or clears them when they are all selected already. */
    private fun toggleDay(header: GridCell.Header) {
        if (busy.get()) return
        val items = DayGroups.photosOf(header, visiblePhotos).filter { it.canImport }
        if (items.isEmpty()) return
        selectionMode = true
        if (items.all { it.handle in selected }) items.forEach { selected.remove(it.handle) } else items.forEach { selected[it.handle] = it }
        updateSelection()
    }
    private fun toggle(obj: CameraObject) {
        if (busy.get() || !obj.canImport) return
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
        if (!::screen.isInitialized || busy.get()) return
        runtime.refreshImportEvidence()
        runtime.checkForNewPhotos()
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
        setEnabledDimmed(download, !value && client != null && selected.isNotEmpty())
        previewController.refreshActions()
        grid.editable = !value && activeFilter!=PhotoFilter.IMPORTED
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
    private fun updateSelection() {
        previewController.refreshActions()
        download.text = if (selected.isEmpty()) "导入原图" else "导入原图（${selected.size}）"
        setEnabledDimmed(download, selected.isNotEmpty() && client != null && !busy.get())
        val hidden = selected.keys.count { handle -> visiblePhotos.none { it.handle==handle } }
        selectionLabel.text = if (selected.isEmpty()) "长按滑动多选" else "已选 ${selected.size} 张 · ${"%.1f".format(selected.values.sumOf { it.size } / 1048576.0)} MB" + if(hidden>0) "\n含其他筛选 $hidden 张" else ""
        updateGalleryControls()
        adapter.notifyDataSetChanged()
    }
    private fun hideCameraControls() {
        listOf(footer, selectAll, cancelSelection, screen.connectionActions, progress, status)
            .forEach { it.visibility = View.GONE }
    }
    private fun setEnabledDimmed(button: View, enabled: Boolean) { button.isEnabled = enabled; button.alpha = if (enabled) 1f else 0.4f }
    private fun updateGalleryControls() {
        if (!::screen.isInitialized) return
        val local=activeFilter==PhotoFilter.IMPORTED
        screen.formatControl.text="格式：${activeFormat.label} ▾"
        val slots=StorageCards.slots(photos)
        screen.cardControl.visibility=if(!local && slots.size>=2) View.VISIBLE else View.GONE
        screen.cardControl.text="存储卡：${StorageCards.label(slots,activeCard)} ▾"
        screen.catalogSummary.visibility=if(!local && runtime.connectionReady && runtime.gallery.ready) View.VISIBLE else View.GONE
        screen.catalogSummary.text=if(activeCard==null) CameraCatalogSummary.of(PhotoCatalog.inCard(photos,imported,null)) else CameraCatalogSummary.of(PhotoCatalog.inCard(photos,imported,activeCard),StorageCards.label(StorageCards.slots(photos),activeCard))
        screen.headingTitle.text=if(local) "手机照片" else "相机照片"
        screen.localAccess.visibility=if(local && !localGallery.hasFullAccess) View.VISIBLE else View.GONE
        if(local) {
            hideCameraControls()
            emptyTitle.text=if(localGallery.loading) "正在检查手机照片" else "暂无已导入照片"
            emptyHint.text="已保存到手机的照片会显示在这里，无需连接相机"
            return
        }
        val connectionEmpty=photos.isEmpty() && client==null
        footer.visibility=if(photos.isEmpty()) View.GONE else View.VISIBLE
        selectAll.visibility=if(visiblePhotos.none { it.canImport }) View.GONE else View.VISIBLE
        cancelSelection.visibility=if(selectionMode) View.VISIBLE else View.GONE
        screen.connectionActions.visibility=if(connectionEmpty) View.VISIBLE else View.GONE
        emptyTitle.text=when {
            connectionEmpty -> "把照片，带回手机"
            busy.get() -> "正在读取照片"
            activeFilter==PhotoFilter.IMPORTED -> "暂时没有已导入照片"
            activeFilter==PhotoFilter.NOT_IMPORTED -> "暂时没有未导入照片"
            else -> "没有可浏览的照片"
        }
        emptyHint.text=when {
            connectionEmpty -> "先在相机上开启 Wi-Fi，应用会自动识别并连接。断开后相机会自动关闭 Wi-Fi，下次需要重新开启。"
            activeFilter!=PhotoFilter.ALL -> "换个筛选看看"
            else -> "相机中暂未找到可浏览的文件"
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
    private fun updateRetryControl() {
        if (!::screen.isInitialized) return
        val count = transfers.queue.remaining.size
        retryTransfer.visibility = if (runtime.taskState.showRemaining(count)) View.VISIBLE else View.GONE
        retryTransfer.text = "继续导入未完成的 $count 张"
        retryTransfer.isEnabled = !busy.get() && client != null
    }
    private fun startImport(items: List<CameraObject>, skipExisting: Boolean, retry: Boolean = false) = runtime.startImport(items, skipExisting, retry)
    private var updates: UpdateChecker? = null
    private fun installedVersion() = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    private fun openLink(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { showNotice("无法打开浏览器", "请在浏览器中手动访问：$url") }
    }
    private fun showUpdateCheck() {
        val installed = installedVersion()
        val current = AppVersion.parse(installed)
        val sheet = newSheet("检查更新")
        if (current == null) { sheet.message("无法识别当前版本号（$installed）。"); sheet.action("关闭"); sheet.show(); return }
        val status = sheet.message("正在检查最新版本…")
        sheet.show()
        val checker = updates ?: UpdateChecker("ZfTransfer/$installed").also { updates = it }
        checker.check(current) { result -> ui {
            if (!sheet.isShowing) return@ui
            when (result) {
                is UpdateResult.Available -> {
                    status.text = "发现新版本 ${result.version}（当前 $current）。点击前往 GitHub 下载页，下载后在手机上打开即可升级。"
                    sheet.action("前往下载", true) { openLink(ReleaseFeed.pageFor(result.version)) }
                }
                UpdateResult.UpToDate -> status.text = "当前版本 $current，已是最新版本。"
                is UpdateResult.Failed -> status.text = "无法检查更新：${result.reason}。"
            }
            sheet.action("关闭")
        } }
    }
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
        images.close(); localGallery.close(); updates?.close()
        super.onDestroy()
    }
}
