package com.xiaozhu.zftransfer

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Network
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import io.github.noisetta.nikonpull.ptp.*

/**
 * Screen observer for the service-owned camera connection, and the coordinator of its three pages:
 * the home screen (取片台), the camera's contact sheet (印样) and the photos already on the phone (已冲印).
 * Sheets and dialogs live in [ScreenDialogs]; this class owns navigation, selection and filters.
 */
class MainActivity : Activity() {
    enum class Page { HOME, SHEET, PRINTS }
    private val runtime get() = (application as TransferApplication).runtime
    private val cameraWifi get() = runtime.cameraWifi
    private val busy get() = runtime.busy
    private var pendingProfile: WifiProfile? = null
    private var resumed = false
    private val imageSession get() = runtime.gallery.imageSession
    private val client get() = runtime.client
    @Volatile private var stopped = false
    private val cameraName get() = runtime.cameraName
    private val photos get() = runtime.gallery.photos
    private val imported get() = runtime.gallery.imported
    private val transfers get() = runtime.transfers
    private val imports get() = runtime.imports
    private val cameraIdentity get() = runtime.cameraIdentity
    private val prefs by lazy { getSharedPreferences("transfers", MODE_PRIVATE) }

    private var page = Page.HOME
    private lateinit var home: HomeScreen
    private lateinit var screen: GalleryScreen
    private lateinit var transferPanel: TransferPanel
    private lateinit var homeScroll: ScrollView
    private val dialogs by lazy { ScreenDialogs(this, runtime, { !stopped && !isFinishing && !isDestroyed }, { requestWifi(it, false) }) }

    // Contact sheet state.
    private var visiblePhotos = emptyList<CameraObject>()
    private var activeFilter = PhotoFilter.NOT_IMPORTED
    private var activeFormat = MediaFormat.ALL
    /** Storage id of the card being shown, or null for every card. */
    private var activeCard: Long? = null
    private var printFormat = MediaFormat.ALL
    private var gridCells = emptyList<GridCell>()
    private val selected = linkedMapOf<Long, CameraObject>()
    private var selectionMode = false
    private var range: RangeSelection? = null
    private var rangeOutside = emptyMap<Long, CameraObject>()

    // What was rendered last, so a runtime change only redraws what changed.
    private var completionCursor = 0L
    private var renderedBusy: Boolean? = null
    private var renderedCatalog = -1
    private var renderedRevision = -1L
    private var renderedSession = ""
    private val upgraded = mutableSetOf<Long>()
    private var freshKey: Pair<Int, Long>? = null
    private var freshSummary = FreshPhotos.Summary(emptyList(), 0, false)

    private val localGallery: LocalImportGallery by lazy { LocalImportGallery(this) {
        if (!stopped && page == Page.PRINTS && ::screen.isInitialized) { screen.renderCount(localGallery.size); updateGalleryControls() }
        if (!stopped && page == Page.HOME) renderHome()
    } }
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
            GridCell.TailPad -> cells.pad(cells.cellHeight(screen.grid.height))
        }
    }
    private val images: GalleryImageLoader by lazy { GalleryImageLoader(applicationContext, runtime) {
        if (!stopped) { adapter.notifyDataSetChanged(); previewController.refresh(); renderHome() }
    } }
    private val previewController: PhotoPreviewController by lazy { PhotoPreviewController(this, runtime, images,
        { it in selected }, { items -> dialogs.confirmImport(items, ::startImport) }, ::toggle, dialogs::showNotice) }
    private val cells by lazy { GalleryPhotoCell(this) }
    private val runtimeListener: () -> Unit = { renderRuntime() }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        home = HomeScreen(this, object : HomeScreen.Actions {
            override fun settings() = dialogs.showSettings()
            override fun prints() = showPage(Page.PRINTS)
            override fun connect() = connectCamera()
            override fun wifiProfile() = dialogs.showWifiProfile()
            override fun wifiSettings() = dialogs.openSettings(Intent(Settings.ACTION_WIFI_SETTINGS))
            override fun takeFresh() = dialogs.confirmImport(freshSummary.fresh, ::startImport)
            override fun browse() = showPage(Page.SHEET)
            override fun retry() = retryRemaining()
            override fun openRecent(index: Int) {
                if (!showsCameraRoll()) { localGallery.open(index); return }
                val photo = recentRoll().getOrNull(index) ?: return
                if (!photo.canImport) { dialogs.showNotice("暂不支持预览", "${MediaFormat.of(photo)?.label.orEmpty()} 文件目前只能浏览。"); return }
                val items = PhotoCatalog.visible(photos, imported, PhotoFilter.ALL).filter { it.canImport }
                val at = items.indexOfFirst { it.handle == photo.handle }
                if (!busy.get() && at >= 0) previewController.open(at, items, PhotoFilter.ALL, MediaFormat.ALL, null)
            }
        })
        screen = GalleryScreen(this, adapter, object : GalleryScreen.Actions {
            override fun back() = showPage(Page.HOME)
            override fun filters() {
                if (page == Page.PRINTS) dialogs.showPrintFormat(printFormat) { f ->
                    printFormat = f; localGallery.setFormat(f); screen.renderCount(localGallery.size); updateGalleryControls()
                } else dialogs.showFilters(activeFilter, activeFormat, activeCard, photos, imported) { f, format, card ->
                    if (f != activeFilter || format != activeFormat || card != activeCard) {
                        activeFilter = f; activeFormat = format; activeCard = card; range = null
                        refreshCatalog(); updateSelection(); setBusy(busy.get()); screen.grid.setSelection(0)
                    }
                }
            }
            override fun connect() = connectCamera()
            override fun selectAll() { if (!busy.get()) {
                val items = visiblePhotos.filter { it.canImport }
                if (items.isEmpty()) return
                selectionMode = true; items.forEach { selected[it.handle] = it }; updateSelection()
            } }
            override fun cancelSelection() { if (!busy.get()) { selected.clear(); selectionMode = false; updateSelection() } }
            override fun preview(handle: Long) {
                if (page == Page.PRINTS) { localGallery.open(handle.toInt()); return }
                val tapped = visiblePhotos.firstOrNull { it.handle == handle } ?: return
                if (!tapped.canImport) { dialogs.showNotice("暂不支持预览", "${MediaFormat.of(tapped)?.label.orEmpty()} 文件目前只能浏览，预览和取回将在后续版本开放。"); return }
                val items = visiblePhotos.filter { it.canImport }
                val index = items.indexOfFirst { it.handle == handle }
                if (!busy.get() && index >= 0) previewController.open(index, items, activeFilter, activeFormat, activeCard)
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
            override fun wifiProfile() = dialogs.showWifiProfile()
            override fun wifiSettings() = dialogs.openSettings(Intent(Settings.ACTION_WIFI_SETTINGS))
            override fun importSelected() = dialogs.confirmImport(selected.values.toList(), ::startImport)
        })
        screen.localAccess.setOnClickListener {
            requestPermissions(arrayOf(if (android.os.Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES" else "android.permission.READ_EXTERNAL_STORAGE"), 43)
        }
        screen.grid.onVisibleRange = { first, visible -> focusThumbnails(first, visible) }
        transferPanel = TransferPanel(this)
        homeScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(home.root) }
        val pages = FrameLayout(this).apply {
            addView(homeScroll, FrameLayout.LayoutParams(-1, -1))
            addView(screen.root, FrameLayout.LayoutParams(-1, -1))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(AppUi.background)
            addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(transferPanel.root, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(8) })
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(dp(12), insets.systemWindowInsetTop, dp(12), insets.systemWindowInsetBottom); insets
            }
        }
        setContentView(root)
        showPage(Page.HOME)
        runtime.attach(runtimeListener)
        runtime.watch()
        renderRuntime()
        log("ZfTransfer ${packageManager.getPackageInfo(packageName, 0).versionName}; Android ${android.os.Build.VERSION.RELEASE}")
    }

    /** Below Android 13 back arrives here; from 13 on, apps targeting 36 only get [backCallback]. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (page != Page.HOME) showPage(Page.HOME) else @Suppress("DEPRECATION") super.onBackPressed()
    }
    private var backCallback: Any? = null
    /** Back returns to the home screen from the other pages; on the home screen it leaves the app as usual. */
    private fun updateBackHandling() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val dispatcher = onBackInvokedDispatcher
        (backCallback as? android.window.OnBackInvokedCallback)?.let { dispatcher.unregisterOnBackInvokedCallback(it) }
        backCallback = null
        if (page != Page.HOME) {
            val callback = android.window.OnBackInvokedCallback { showPage(Page.HOME) }
            dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        }
    }

    private fun showPage(next: Page) {
        page = next
        updateBackHandling()
        homeScroll.visibility = if (next == Page.HOME) View.VISIBLE else View.GONE
        screen.root.visibility = if (next == Page.HOME) View.GONE else View.VISIBLE
        if (next == Page.HOME) {
            selected.clear(); selectionMode = false; range = null
            renderHome()
            return
        }
        range = null
        screen.grid.adapter = if (next == Page.PRINTS) localGallery.adapter else adapter
        screen.headingTitle.text = if (next == Page.PRINTS) "已冲印" else "印样"
        refreshCatalog(); updateSelection(); setBusy(busy.get()); screen.grid.setSelection(0)
        if (next == Page.PRINTS) localGallery.reload()
    }

    private fun renderRuntime() {
        if (stopped || !::screen.isInitialized) return
        screen.status.text = runtime.message
        screen.status.visibility = if (runtime.message.isEmpty() || (!runtime.taskState.running && runtime.connectionReady)) View.GONE else View.VISIBLE
        screen.progress.progress = runtime.progress
        transferPanel.render(runtime.taskState)
        val running = runtime.taskState.running
        val busyChanged = renderedBusy != running
        renderedBusy = running
        setBusy(running)
        if (busyChanged) adapter.notifyDataSetChanged()
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
        renderHome()
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

    /** The home screen's numbers are recomputed only when the catalog or the import evidence changed. */
    private fun renderHome() {
        if (stopped || !::home.isInitialized) return
        val key = runtime.gallery.version to runtime.revision
        if (key != freshKey) { freshKey = key; freshSummary = FreshPhotos.of(photos, imported) }
        val connected = runtime.connectionReady
        val connecting = busy.get() || runtime.connectionPending || cameraWifi.requesting
        val cameraRoll = showsCameraRoll()
        val recent = recentRoll()
        home.render(HomeScreen.State(
            connected = connected, connecting = connecting, busy = busy.get(),
            cameraLabel = "$cameraName · " + when { connected -> "已连接"; connecting -> "正在连接"; else -> "未连接" },
            catalogReady = runtime.gallery.ready,
            summary = if (runtime.gallery.ready) CameraCatalogSummary.of(PhotoCatalog.inCard(photos, imported, null)) else "",
            fresh = freshSummary, remaining = transfers.queue.remaining.size,
            message = runtime.message,
            recentTitle = if (cameraRoll) "最近一卷" else "最近冲印",
            recentCount = if (cameraRoll) recent.size else minOf(RECENT, localGallery.size),
        )) { i -> if (cameraRoll) recent.getOrNull(i)?.let { images.thumbnail(it.handle) } else localGallery.thumbnailAt(i) }
        if (page == Page.HOME && cameraRoll) runtime.focusThumbnails(recent.map { it.handle })
    }
    /** With a camera listed, the home screen shows its newest photos; otherwise the newest ones already on the phone. */
    private fun showsCameraRoll() = photos.isNotEmpty()
    private fun recentRoll() = PhotoCatalog.inCard(photos, imported, null).take(RECENT)
    private companion object { const val RECENT = 9 }

    private fun dp(value: Int) = AppUi.dp(this, value)
    private fun refreshCatalog() {
        if (page == Page.PRINTS) { screen.renderCount(localGallery.size); return }
        val next = PhotoCatalog.visible(photos, imported, activeFilter, activeFormat, activeCard)
        if (next != visiblePhotos) { visiblePhotos = next; gridCells = DayGroups.plan(next); range = null }
        screen.renderCount(visiblePhotos.size)
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
        if (requestCode == 43 && page == Page.PRINTS) localGallery.reload()
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
        val grid = screen.grid
        if (page != Page.SHEET || gridCells.isEmpty()) return null
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
        if (position >= 0) screen.grid.setSelectionFromTop(position, anchor.second)
    }
    /** Camera thumbnails are read for what is on screen first. */
    private fun focusThumbnails(first: Int, visible: Int) {
        if (page != Page.SHEET) return
        val from = (first - visible).coerceAtLeast(0)
        val to = minOf(gridCells.size, first + visible * 2)
        runtime.focusThumbnails((from until to).mapNotNull { (gridCells[it] as? GridCell.Photo)?.let { cell -> visiblePhotos[cell.index].handle } })
    }
    private fun dayHeader(header: GridCell.Header): View {
        val grid = screen.grid
        val items = DayGroups.photosOf(header, visiblePhotos).filter { it.canImport }
        val width = if (grid.width > 0) grid.width - grid.paddingLeft - grid.paddingRight else resources.displayMetrics.widthPixels - dp(24)
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
        // The phone's photos fill the home screen when no camera is around, so they are read on every return.
        if (::screen.isInitialized && page != Page.SHEET) localGallery.reload()
        if (!::screen.isInitialized || busy.get()) return
        runtime.refreshImportEvidence()
        runtime.checkForNewPhotos()
    }
    override fun onPause() { resumed = false; runtime.foreground = false; super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) cameraWifi.schedule()
    }
    private fun photoCell(obj: CameraObject): View = cells.create(obj, images.thumbnail(obj.handle),
        obj.handle in selected, obj.handle in imported, !busy.get(), screen.grid.height) { toggle(obj) }
    private fun ui(action: () -> Unit) { runOnUiThread { if (!isDestroyed && !stopped) action() } }
    private fun log(message: String) = runtime.log(message)
    private fun presentIssue(issue: UserIssue, interactive: Boolean = true) { ui {
        report("${issue.title}\n${issue.message}")
        if (interactive) dialogs.showIssuePanel(issue)
    } }
    private fun report(message: String) = runtime.report(message)
    private fun setBusy(value: Boolean) {
        previewController.refreshActions()
        screen.grid.editable = !value && page == Page.SHEET
        val importing = runtime.taskState.operation == UserOperation.IMPORT
        screen.progress.isIndeterminate = value && runtime.taskState.transfer == null
        screen.progress.visibility = if (value && !importing) View.VISIBLE else View.GONE
        updateGalleryControls()
    }
    private fun connectCamera(networkOverride: Network? = null, automatic: Boolean = false) = runtime.connectCamera(networkOverride, automatic)
    private fun updateSelection() {
        previewController.refreshActions()
        screen.download.text = if (selected.isEmpty()) "取回" else "取回 ${selected.size}"
        setEnabledDimmed(screen.download, selected.isNotEmpty() && client != null && !busy.get())
        val hidden = selected.keys.count { handle -> visiblePhotos.none { it.handle == handle } }
        screen.selectionLabel.text = if (selected.isEmpty()) "长按滑动多选" else "已选 %02d · %.1f MB".format(selected.size, selected.values.sumOf { it.size } / 1048576.0) + if (hidden > 0) "\n含其他筛选 $hidden 张" else ""
        updateGalleryControls()
        adapter.notifyDataSetChanged()
    }
    private fun setEnabledDimmed(button: View, enabled: Boolean) { button.isEnabled = enabled; button.alpha = if (enabled) 1f else 0.4f }
    private fun updateGalleryControls() {
        if (!::screen.isInitialized) return
        val prints = page == Page.PRINTS
        val slots = StorageCards.slots(photos)
        screen.filterControl.text = if (prints) "${printFormat.label} ▾"
            else listOfNotNull(activeFilter.label, activeFormat.label, activeCard?.let { StorageCards.label(slots, it) }).joinToString(" · ") + " ▾"
        screen.catalogSummary.visibility = if (!prints && runtime.connectionReady && runtime.gallery.ready) View.VISIBLE else View.GONE
        screen.catalogSummary.text = if (activeCard == null) CameraCatalogSummary.of(PhotoCatalog.inCard(photos, imported, null))
            else CameraCatalogSummary.of(PhotoCatalog.inCard(photos, imported, activeCard), StorageCards.label(slots, activeCard))
        screen.localAccess.visibility = if (prints && !localGallery.hasFullAccess) View.VISIBLE else View.GONE
        if (prints) {
            listOf(screen.footer, screen.selectAll, screen.cancelSelection, screen.connectionActions, screen.progress, screen.status)
                .forEach { it.visibility = View.GONE }
            screen.emptyTitle.text = if (localGallery.loading) "正在检查手机照片" else "手机上还没有取回的照片"
            screen.emptyHint.text = "取回到手机的照片会显示在这里，无需连接相机"
            return
        }
        val disconnected = photos.isEmpty() && client == null
        screen.footer.visibility = if (selected.isEmpty() || busy.get()) View.GONE else View.VISIBLE
        screen.selectAll.visibility = if (visiblePhotos.none { it.canImport } || selectionMode) View.GONE else View.VISIBLE
        screen.cancelSelection.visibility = if (selectionMode) View.VISIBLE else View.GONE
        screen.connectionActions.visibility = if (disconnected) View.VISIBLE else View.GONE
        screen.emptyTitle.text = when {
            disconnected -> "相机没有连接"
            busy.get() -> "正在读取照片"
            activeFilter == PhotoFilter.IMPORTED -> "还没有取回过照片"
            activeFilter == PhotoFilter.NOT_IMPORTED -> "照片都已取回"
            else -> "没有可浏览的照片"
        }
        screen.emptyHint.text = when {
            disconnected -> "先在相机上开启 Wi-Fi，应用会自动识别并连接。"
            activeFilter != PhotoFilter.ALL || activeFormat != MediaFormat.ALL -> "换个筛选看看"
            else -> "相机中暂未找到可浏览的文件"
        }
    }
    private fun retryRemaining() {
        if (!transfers.queue.canRetry(cameraIdentity)) dialogs.showNotice("请连接原来的相机", "未完成任务属于原相机，请连接原相机后再重试。")
        else startImport(transfers.queue.remaining, true, retry = true)
    }
    private fun startImport(items: List<CameraObject>, skipExisting: Boolean) = startImport(items, skipExisting, false)
    private fun startImport(items: List<CameraObject>, skipExisting: Boolean, retry: Boolean) = runtime.startImport(items, skipExisting, retry)
    override fun onDestroy() {
        stopped = true
        previewController.close()
        dialogs.close()
        runtime.detach(runtimeListener)
        images.close(); localGallery.close()
        super.onDestroy()
    }
}
