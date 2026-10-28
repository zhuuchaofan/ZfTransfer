package com.xiaozhu.zftransfer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.view.Gravity
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
    private val imageSession get() = runtime.imageSession
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
    private val photos get() = runtime.photos
    private val imported get() = runtime.imported
    private var selectionMode = false
    private var range: RangeSelection? = null
    private var issueDialog: Dialog? = null
    private val yellow = 0xfff2c45b.toInt()
    private val surfaceColor = 0xff101112.toInt()
    private val muted = 0xffa1a4aa.toInt()
    private val adapter = object : BaseAdapter() {
        override fun getCount() = photos.size
        override fun getItem(position: Int) = photos[position]
        override fun getItemId(position: Int) = photos[position].handle
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View = photoCell(photos[position])
    }
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
        status.visibility = if (runtime.message.isEmpty()) View.GONE else View.VISIBLE
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
            val completed = runtime.completedSince(completionCursor)
            selected.keys.removeAll(completed.map { it.handle }.toSet())
            if (completed.isNotEmpty()) {
                completionCursor = completed.last().sequence
                if (selected.isEmpty()) selectionMode = false
            }
            updateSelection()
            previewController.refresh()
            photos.filter { it.handle in imported && upgraded.add(it.handle) }.forEach {
                images.upgradeGrid(it, duplicateKey(it), imageSession)
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
        val screen = GalleryScreen(this, adapter, object : GalleryScreen.Actions {
            override fun settings() = showSettings()
            override fun cameraStatus() { if (client == null && !busy.get()) connectCamera() else showSettings() }
            override fun connect() = connectCamera()
            override fun selectAll() { if (!busy.get()) {
                selectionMode = true; photos.forEach { selected[it.handle] = it }; updateSelection()
            } }
            override fun cancelSelection() { if (!busy.get()) { selected.clear(); selectionMode = false; updateSelection() } }
            override fun preview(index: Int) { if (!busy.get()) previewController.show(index) }
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
        grid.onReadMore = { runtime.readPage(automatic = true) }
        grid.onBrowseGesture = runtime::beginBrowseGesture
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
        val choices = listOf<Pair<String, () -> Unit>>(
            "相机 Wi-Fi 配置" to { showWifiProfile() },
            "${if (automatic) "关闭" else "开启"}自动连接" to {
                prefs.edit().putBoolean("auto_connect", !automatic).apply()
                if (!automatic) { userStopped = false; autoGate.reset(); profileAttempted = false; cameraWifi.schedule() }
                report(if (automatic) "已关闭自动连接。可手动连接相机。" else "已开启自动连接，正在检测相机 Wi-Fi…")
            },
            "Wi-Fi 设置" to { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); Unit },
            "断开相机" to {
                runtime.stop(); stopService(Intent(this, CameraConnectionService::class.java)); Unit
            },
            "后台连接设置" to {
                showIssuePanel(UserIssue("保持后台连接", "请在系统应用信息中将省电策略设为「无限制」，并允许通知，以便熄屏后保持连接和随时断开。持续连接会增加手机和相机耗电。相机关机后的无线状态仍取决于相机设置。", Recovery.APP_SETTINGS), "打开应用设置")
            },
            "忘记相机 Wi-Fi" to {
                AlertDialog.Builder(this).setTitle("忘记相机 Wi-Fi？")
                    .setMessage("删除本应用保存的名称和密码，不删除系统 Wi-Fi 或导入照片。")
                    .setPositiveButton("忘记") { _, _ -> wifiStore.clear(); profileAttempted = true; pendingProfile = null; report("已忘记保存的相机 Wi-Fi。") }
                    .setNegativeButton("取消", null).show(); Unit
            },
            "关于" to { showLicense() }
        )
        AlertDialog.Builder(this).setTitle("相机与应用").setItems(choices.map { it.first }.toTypedArray()) { _, which -> choices[which].second() }.show()
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
        panel.addView(label("名称和密码加密保存在本机，用于下次连接相机。", 12f).apply { setTextColor(muted) })
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
        if (issue.recovery != Recovery.NONE) sheet.addView(textAction("稍后处理") { dialog.dismiss() }.apply { setTextColor(muted) }, LinearLayout.LayoutParams(-1, dp(48)))
        dialog.setContentView(sheet); dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(-1, -2); setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(0.65f)
        }
    }
    private fun report(message: String) { ui { status.text = message; status.setTextColor(muted); status.visibility = View.VISIBLE } }
    private fun setBusy(value: Boolean) {
        connect.isEnabled = !value
        more.isEnabled = !value && client != null && cursor < handles.size
        download.isEnabled = !value && client != null && selected.isNotEmpty()
        more.alpha = if (more.isEnabled) 1f else 0.4f
        download.alpha = if (download.isEnabled) 1f else 0.4f
        previewController.refreshActions()
        grid.editable = !value
        grid.canReadMore = !value && client != null && cursor < handles.size
        progress.visibility = if (value) View.VISIBLE else View.GONE
        cameraLabel.text = when {
            runtime.connectionReady -> "$cameraName · 已连接"
            busy.get() || runtime.connectionPending || cameraWifi.requesting -> "$cameraName · 正在连接"
            else -> "$cameraName · 点此连接"
        }
        cameraLabel.setTextColor(if (runtime.connectionReady) yellow else muted)
        connect.visibility = if (client == null) View.VISIBLE else View.GONE
        emptyTitle.text = if (client == null) "让照片，回到你的生活" else "正在寻找相机中的照片"
        emptyHint.text = if (client == null) "连接相机 Wi-Fi 后，将自动识别\n也可在应用内设置 Wi-Fi 名和密码" else "仅显示 JPEG 原图；可继续读取下一批"
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
        runtime.checkImported(items) { duplicates ->
            if (!stopped && !isDestroyed) {
                if (duplicates.isEmpty()) startImport(items, false)
                else showDuplicateChoices(items, duplicates.size)
            }
        }
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
        retryTransfer.visibility = if (runtime.taskState.showRemaining(count)) View.VISIBLE else View.GONE
        retryTransfer.text = "继续导入未完成的 $count 张"
        retryTransfer.isEnabled = !busy.get() && client != null
    }
    private fun startImport(items: List<CameraObject>, skipExisting: Boolean, retry: Boolean = false) = runtime.startImport(items, skipExisting, retry)
    private fun duplicateKey(obj: CameraObject): String? = imports.key(obj)
    private fun showLicense() {
        val text = assets.open("LICENSE.txt").bufferedReader().use { it.readText() }
        AlertDialog.Builder(this).setTitle("关于 Zf 传图").setMessage(
            "遵循 GPL-3.0 开源许可证。\n基于 noisetta/Nikon-Pull 与 jaminxx-aha/ZPill 构建。\n源代码：github.com/zhuuchaofan/ZfTransfer\n\n$text"
        ).setPositiveButton("关闭", null).show()
    }
    override fun onDestroy() {
        stopped = true
        previewController.close()
        issueDialog?.dismiss()
        runtime.detach(runtimeListener)
        images.close()
        super.onDestroy()
    }
}
