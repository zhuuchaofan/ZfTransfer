package com.xiaozhu.zftransfer

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Switch
import io.github.noisetta.nikonpull.ptp.CameraObject

/**
 * Every bottom sheet the main screen opens: settings, camera Wi-Fi, filters, import confirmations, problems,
 * updates and the about page. Holds no screen state of its own; what a sheet changes goes back through callbacks.
 */
class ScreenDialogs(
    private val activity: Activity,
    private val runtime: ConnectionRuntime,
    private val alive: () -> Boolean,
    private val requestWifi: (WifiProfile) -> Unit,
) {
    private val prefs get() = activity.getSharedPreferences("transfers", Activity.MODE_PRIVATE)
    private val wifiStore by lazy { WifiProfileStore(activity) }
    private val sheets = mutableSetOf<AppSheet>()
    private var issueDialog: Dialog? = null
    private var updates: UpdateChecker? = null
    private fun dp(value: Int) = AppUi.dp(activity, value)

    fun newSheet(title: String) = AppSheet(activity, title).also { dialog ->
        sheets.add(dialog); dialog.setOnDismissListener { sheets.remove(dialog) }
    }
    private fun switch(label: String, checked: Boolean, textSize: Float = 15f, changed: (Boolean) -> Unit) = Switch(activity).apply {
        text = label; this.textSize = textSize; setTextColor(AppUi.ink); isChecked = checked
        buttonTintList = ColorStateList.valueOf(AppUi.yellow)
        setOnCheckedChangeListener { _, value -> changed(value) }
    }

    /** Import state, format and card in one sheet; [chosen] receives the new combination. */
    fun showFilters(filter: PhotoFilter, format: MediaFormat, card: Long?, photos: List<CameraObject>, imported: Set<Long>,
        chosen: (PhotoFilter, MediaFormat, Long?) -> Unit) {
        val sheet = newSheet("筛选")
        fun section(title: String) = sheet.body.addView(AppUi.text(activity, title, 12f, AppUi.muted).apply { typeface = AppUi.mono },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        fun mark(on: Boolean) = if (on) "✓  " else ""
        section("导入状态")
        PhotoFilter.entries.forEach { f -> sheet.row(mark(f == filter) + f.label) { chosen(f, format, card) } }
        section("格式")
        sheet.message("JPEG 和 RAW 可以预览和取回；视频、HEIF 目前只能浏览。")
        MediaFormat.entries.forEach { f -> sheet.row(mark(f == format) + f.label) { chosen(filter, f, card) } }
        val slots = StorageCards.slots(photos)
        if (slots.size >= 2) {
            section("存储卡")
            sheet.message("选“全部”时，两张卡上相同的照片只显示一次；选某张卡则只显示那张卡上的文件。")
            (listOf<Long?>(null) + slots).forEach { c ->
                val count = if (c == null) CopyGroups.unique(photos) { it.handle in imported }.size else photos.count { it.storageId == c }
                sheet.row(mark(c == card) + StorageCards.label(slots, c) + " · $count 个") { chosen(filter, format, c) }
            }
        }
        sheet.action("关闭"); sheet.show()
    }

    /** Format only, for the photos already on the phone. */
    fun showPrintFormat(format: MediaFormat, chosen: (MediaFormat) -> Unit) {
        val sheet = newSheet("格式")
        MediaFormat.entries.forEach { f -> sheet.row((if (f == format) "✓  " else "") + f.label) { chosen(f) } }
        sheet.action("关闭"); sheet.show()
    }

    fun showSettings() {
        val sheet = newSheet("相机与应用")
        val blePaired = runtime.bleStore.isPaired
        val bleSuffix = if (blePaired) " · 已配对" + (runtime.bleStore.cameraName?.let { " ($it)" } ?: "") else " · 未配对"
        sheet.row("蓝牙唤醒相机$bleSuffix") { showBleWake() }
        sheet.row("相机 Wi-Fi 配置") { showWifiProfile() }
        sheet.body.addView(switch("自动连接", prefs.getBoolean("auto_connect", true)) { checked ->
            prefs.edit().putBoolean("auto_connect", checked).apply()
            if (checked) { runtime.userStopped = false; runtime.autoGate.reset(); runtime.profileAttempted = false; runtime.cameraWifi.schedule() }
            runtime.report(if (checked) "已开启自动连接" else "已关闭自动连接")
        }, LinearLayout.LayoutParams(-1, dp(52)))
        sheet.body.addView(switch("新照片自动取回", runtime.autoImport) { checked ->
            runtime.autoImport = checked
            runtime.report(if (checked) "已开启新照片自动取回" else "已关闭新照片自动取回")
            if (checked) runtime.checkForNewPhotos()
        }, LinearLayout.LayoutParams(-1, dp(52)))
        sheet.body.addView(AppUi.text(activity, "连接后新拍的 JPEG 会自动保存到手机，断线期间拍的在重新连接后补下（一次最多 100 张）。只在应用打开时下载，锁屏时需把应用的省电策略设为「无限制」。自动取回只处理 JPEG；RAW 请在取片台或印样里手动取回，视频暂不支持。").apply { textSize = 12f })
        sheet.row("Wi-Fi 设置") { openSettings(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        sheet.row("后台连接设置") {
            showIssuePanel(UserIssue("保持后台连接", "允许通知，并将应用省电策略设为「无限制」，以便熄屏后保持连接。持续连接会增加手机和相机耗电，实际效果也受相机无线设置影响。", Recovery.APP_SETTINGS), "打开应用设置")
        }
        sheet.row("检查更新") { showUpdateCheck() }
        sheet.row("关于") { showAbout() }
        sheet.row("忘记相机 Wi-Fi") {
            newSheet("忘记相机 Wi-Fi？").apply {
                message("仅删除本应用保存的名称和密码，不删除系统 Wi-Fi 或导入照片。")
                action("忘记配置", true) { wifiStore.clear(); runtime.profileAttempted = true; runtime.report("已忘记保存的相机 Wi-Fi") }
                action("取消"); show()
            }
        }
        sheet.row("断开相机", true) { runtime.stop(); activity.stopService(Intent(activity, CameraConnectionService::class.java)) }
        sheet.show()
    }

    fun showWifiProfile(prefillSsid: String? = null) {
        val saved = wifiStore.load()
        val sheet = newSheet("连接相机 Wi-Fi")
        sheet.message(if (prefillSsid != null) "已通过蓝牙唤醒相机热点，请输入一次 Wi-Fi 密码（保存后下次自动填入）。" else "填写相机屏幕上的名称和密码，首次连接需在系统弹窗中确认。")
        sheet.body.addView(AppUi.text(activity, "Wi-Fi 名称"))
        val ssid = AppUi.input(activity, "相机 Wi-Fi 名称").apply { setText(prefillSsid ?: saved?.ssid.orEmpty()) }
        sheet.body.addView(ssid, LinearLayout.LayoutParams(-1, dp(50)))
        sheet.body.addView(AppUi.text(activity, "密码"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val password = AppUi.input(activity, if (saved == null) "Wi-Fi 密码" else "留空使用已保存的密码", true)
        sheet.body.addView(password, LinearLayout.LayoutParams(-1, dp(50)))
        val error = AppUi.text(activity, "", 12f, AppUi.danger).apply { visibility = View.GONE }
        sheet.body.addView(error)
        val remember = switch("加密保存，下次自动连接", true, 14f) {}
        sheet.body.addView(remember, LinearLayout.LayoutParams(-1, dp(56)))
        sheet.action("保存并连接", true, close = false) {
            val name = ssid.text.toString().trim()
            val secret = password.text.toString().ifEmpty { saved?.password.orEmpty() }
            val profile = WifiProfile(name, secret)
            if (!profile.valid()) { error.text = "请输入有效名称及 8–63 位 Wi-Fi 密码"; error.visibility = View.VISIBLE }
            else if (remember.isChecked && runCatching { wifiStore.save(profile) }.isFailure) {
                error.text = "无法加密保存，请取消保存后重试"; error.visibility = View.VISIBLE
            } else {
                if (!remember.isChecked) wifiStore.clear()
                prefs.edit().putBoolean("auto_connect", remember.isChecked).apply()
                runtime.userStopped = false; runtime.autoGate.reset(); runtime.profileAttempted = true
                sheet.dismiss(); requestWifi(profile)
            }
        }
        sheet.action("取消")
        sheet.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        sheet.show()
    }

    fun showBleWake() {
        val store = runtime.bleStore
        val sheet = newSheet("蓝牙唤醒相机")
        if (store.isPaired) {
            val name = store.cameraName ?: "尼康相机"
            sheet.message(
                "已配对：$name\n\n" +
                    "点击「连接相机」时，若附近没有开启的相机热点，应用会通过蓝牙自动唤醒相机 Wi-Fi（名称带 _SnapBridge），随后自动加入并读取照片。\n\n" +
                    "注意：相机与官方 SnapBridge 共用同一套蓝牙配对，若在 SnapBridge 里重新配对过，需要在本应用重新配对。"
            )
            sheet.action("重新配对") { startBlePairingFlow() }
            sheet.action("解除配对") {
                store.unpair()
                runtime.report("已解除蓝牙唤醒配对。如需彻底移除系统蓝牙绑定，可在手机蓝牙设置中忘记相机。")
            }
            sheet.action("关闭")
        } else {
            sheet.message(
                "配对后，无需每次在相机菜单里手动打开 Wi-Fi：点「连接相机」即可通过蓝牙唤醒相机热点并连接。\n\n" +
                    "配对步骤：\n" +
                    "1. 若手机之前用 SnapBridge 配对过这台相机，请先在手机「蓝牙设置」里忘记 / 取消配对该相机，并退出后台的 SnapBridge；\n" +
                    "2. 在相机上打开「网络菜单 → 连接至智能设备 → 配对（Bluetooth）」，停留在等待配对界面；\n" +
                    "3. 点击下方「开始配对」，随后在手机和相机弹出的确认框中都点确认。"
            )
            sheet.action("开始配对", true) { startBlePairingFlow() }
            sheet.action("打开手机蓝牙设置") { openSettings(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            sheet.action("取消")
        }
        sheet.show()
    }

    fun startBlePairingFlow() {
        if (!CameraBluetooth.hasPermissions(activity)) {
            activity.requestPermissions(CameraBluetooth.requiredPermissions(), 44)
            return
        }
        val existingBond = runtime.bluetooth.findExistingNikonBond()
        if (existingBond != null) {
            val label = @android.annotation.SuppressLint("MissingPermission") (existingBond.name ?: existingBond.address)
            showExistingBondSheet(label)
            return
        }
        runBlePairingDialog()
    }

    private fun showExistingBondSheet(bondedName: String) {
        newSheet("请先忘记旧蓝牙绑定").apply {
            message("手机系统蓝牙里已有相机绑定「$bondedName」（通常是之前配对留下的）。相机的蓝牙绑定需要先在手机「蓝牙设置」里忘记 / 取消配对，再回到应用开始配对。")
            action("打开蓝牙设置", true) { openSettings(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            action("已忘记，继续配对") { runBlePairingDialog() }
            action("取消")
            show()
        }
    }

    private fun runBlePairingDialog() {
        val sheet = newSheet("正在配对相机蓝牙")
        val status = sheet.message("请确认相机已进入「配对（Bluetooth）」界面…")
        val closeBtn = sheet.action("取消")
        sheet.setCancelable(false)
        sheet.show()
        Thread {
            val outcome = runtime.bluetooth.pair { msg ->
                activity.runOnUiThread { if (alive() && sheet.isShowing) status.text = msg }
            }
            activity.runOnUiThread {
                if (!alive()) return@runOnUiThread
                if (sheet.isShowing) sheet.dismiss()
                when (outcome) {
                    is BlePairOutcome.Paired -> {
                        runtime.report("蓝牙配对成功：${outcome.cameraName}")
                        showNotice("蓝牙配对已完成", "已与 ${outcome.cameraName} 完成蓝牙配对。下次点击「连接相机」时，应用会自动通过蓝牙唤醒相机 Wi-Fi 热点。")
                    }
                    is BlePairOutcome.ExistingBond -> showExistingBondSheet(outcome.bondedName)
                    is BlePairOutcome.Failed -> showIssuePanel(UserIssue(outcome.title, outcome.message, Recovery.NONE))
                }
            }
        }.start()
    }

    /** Confirms what needs confirming (a pending batch, duplicates), then starts the import. */
    fun confirmImport(items: List<CameraObject>, start: (List<CameraObject>, Boolean) -> Unit) {
        if (runtime.busy.get() || runtime.client == null || items.isEmpty()) return
        val remaining = runtime.transfers.queue.remaining.size
        if (remaining > 0) {
            newSheet("替换未完成任务？").apply {
                message("仍有 $remaining 张未完成。新任务会替换剩余任务，已取回的照片保留。")
                action("开始新任务", true) { checkDuplicates(items, start) }; action("取消"); show()
            }
            return
        }
        checkDuplicates(items, start)
    }
    private fun checkDuplicates(items: List<CameraObject>, start: (List<CameraObject>, Boolean) -> Unit) {
        runtime.checkImported(items) { duplicates ->
            if (!alive()) return@checkImported
            if (duplicates.isEmpty()) start(items, false)
            else newSheet("其中 ${duplicates.size} 张照片已在手机上").apply {
                message("已确认这些文件仍在手机中，再次取回会保存新的副本。")
                action("跳过已取回的", true) { start(items, true) }
                action("再次取回") { start(items, false) }
                action("取消"); show()
            }
        }
    }

    fun showNotice(title: String, message: String) = showIssuePanel(UserIssue(title, message, Recovery.NONE))
    fun showIssuePanel(issue: UserIssue, primaryLabel: String? = null) {
        if (!alive()) return
        issueDialog?.dismiss()
        val sheet = newSheet(issue.title); issueDialog = sheet
        sheet.message(issue.message)
        val primary = primaryLabel ?: when (issue.recovery) {
            Recovery.WIFI_SETTINGS -> "检查 Wi-Fi"
            Recovery.APP_SETTINGS -> "查看应用权限"
            Recovery.STORAGE_SETTINGS -> "查看手机存储"
            Recovery.NONE -> "知道了"
        }
        sheet.action(primary, true) {
            when (issue.recovery) {
                Recovery.WIFI_SETTINGS -> openSettings(Intent(Settings.ACTION_WIFI_SETTINGS))
                Recovery.APP_SETTINGS -> openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
                Recovery.STORAGE_SETTINGS -> openSettings(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                Recovery.NONE -> Unit
            }
        }
        if (issue.recovery != Recovery.NONE) sheet.action("稍后处理")
        sheet.show()
    }
    fun openSettings(intent: Intent) {
        runCatching { activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { showNotice("请手动打开系统设置", "当前系统无法从应用打开该页面，请手动进入系统设置。") }
    }

    private fun installedVersion() = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
    private fun showUpdateCheck() {
        val installed = installedVersion()
        val current = AppVersion.parse(installed)
        val sheet = newSheet("检查更新")
        if (current == null) { sheet.message("无法识别当前版本号（$installed）。"); sheet.action("关闭"); sheet.show(); return }
        val status = sheet.message("正在检查最新版本…")
        sheet.show()
        val checker = updates ?: UpdateChecker("ZfTransfer/$installed").also { updates = it }
        checker.check(current) { result -> activity.runOnUiThread {
            if (!alive() || !sheet.isShowing) return@runOnUiThread
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
    private fun openLink(url: String) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { showNotice("无法打开浏览器", "请在浏览器中手动访问：$url") }
    }
    private fun showAbout() {
        newSheet("关于取片").apply {
            message("版本 ${installedVersion().removeSuffix("-test")}\n遵循 GPL-3.0 开源许可证。\n基于 noisetta/Nikon-Pull、jaminxx-aha/ZPill 构建，蓝牙握手协议参考 rjmendez/nikongeotag、hurui200320/nsg 与 gkoh/furble。\n源代码：github.com/zhuuchaofan/ZfTransfer")
            action("开源许可证") {
                newSheet("开源许可证").apply { message(activity.assets.open("LICENSE.txt").bufferedReader().use { it.readText() }); action("关闭"); show() }
            }
            action("关闭"); show()
        }
    }
    fun close() { sheets.toList().forEach { it.dismiss() }; updates?.close() }
}
