package com.xiaozhu.zftransfer

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.BitmapFactory
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
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.*
import io.github.noisetta.nikonpull.ptp.*
import java.io.FilterOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground-only, read-only camera client. GPL-3.0, see bundled notices. */
class MainActivity : Activity() {
    private lateinit var cameraWifi: CameraWifi
    private val autoGate = AutoConnectGate()
    private val connectionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var resumedAt = 0L
    private var resumed = false
    private var userStopped = false
    private var stoppedNetwork: Long? = null
    private var profileAttempted = false
    private var pendingProfile: WifiProfile? = null
    @Volatile private var sessionNetwork: Network? = null
    private val wifiStore by lazy { WifiProfileStore(this) }
    private data class WifiEndpoint(val network: Network, val address: String, val caps: NetworkCapabilities)
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var client: PtpIpClient? = null
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
    private var cameraName = "Nikon Zf"
    private val photos = mutableListOf<CameraObject>()
    private val thumbs = object : android.util.LruCache<Long, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private val imported = mutableSetOf<Long>()
    private var selectionMode = false
    private var range: RangeSelection? = null
    private var preview: Dialog? = null
    private var previewIndex = 0
    private lateinit var previewImage: ImageView
    private lateinit var previewName: TextView
    private lateinit var previewSize: TextView
    private lateinit var previewStrip: LinearLayout
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
    private lateinit var download: Button
    private lateinit var progress: ProgressBar
    private val selected = linkedMapOf<Long, CameraObject>()

    private val logs = ArrayDeque<String>()
    private var handles = emptyList<Long>()
    private var cursor = 0
    private var cameraIdentity: String? = null
    private val prefs by lazy { getSharedPreferences("transfers", MODE_PRIVATE) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surfaceColor)
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(dp(16), insets.systemWindowInsetTop, dp(16), insets.systemWindowInsetBottom)
                insets
            }
        }
        val title = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(14)) }
        val brand = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        brand.addView(label("Zf", 29f).apply { typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.ITALIC) })
        brand.addView(label(" 传图", 24f))
        title.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        title.addView(ImageButton(this).apply {
            setImageResource(resource("ic_more", "drawable"))
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "相机设置与日志"
            setOnClickListener { showSettings() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(title)
        val cameraRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
            contentDescription = "相机连接状态"
            setOnClickListener { if (client == null && !busy.get()) connectCamera() else showSettings() }
        }
        cameraRow.addView(ImageView(this).apply {
            setImageResource(resource("ic_launcher", "mipmap"))
            contentDescription = "Zf 传图"
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        cameraLabel = label("Nikon Zf · 尚未连接", 14f).apply { setTextColor(muted); setPadding(dp(12), 0, 0, 0) }
        cameraRow.addView(cameraLabel)
        root.addView(cameraRow)
        host = EditText(this).apply {
            setText(prefs.getString("host", "")); setSingleLine(true)
            hint = "相机 IPv4 地址（留空自动识别）"
            setTextColor(Color.WHITE); setHintTextColor(muted)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        connect = button("连接相机") { connectCamera() }.apply {
            backgroundTintList = ColorStateList.valueOf(yellow)
            setTextColor(surfaceColor); textSize = 16f
        }
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        heading.addView(label("相机照片", 28f).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
        selectAll = textAction("全选") {
            if (!busy.get()) {
                selectionMode = true
                photos.forEach { selected[it.handle] = it }; updateSelection()
            }
        }
        cancelSelection = textAction("取消") {
            if (!busy.get()) { selected.clear(); selectionMode = false; updateSelection() }
        }
        selectAll.visibility = View.GONE; cancelSelection.visibility = View.GONE
        heading.addView(selectAll); heading.addView(cancelSelection)
        root.addView(heading)
        root.addView(label("JPEG 原图", 13f).apply { setTextColor(muted); setPadding(dp(2), 0, 0, dp(16)) })
        status = label("", 12f).apply { setTextColor(muted); visibility = View.GONE; maxLines = 3 }
        root.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = ColorStateList.valueOf(yellow)
            visibility = View.GONE
        }
        root.addView(progress)
        grid = PhotoGrid(this).apply {
            numColumns = 3; horizontalSpacing = dp(8); verticalSpacing = dp(12)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, position, _ ->
                if (!busy.get()) {
                    val obj = photos[position]
                    showPreview(position)
                }
            }
            onRangeStart = { anchor ->
                selectionMode = true
                range = RangeSelection(photos.indices.filter { photos[it].handle in selected }.toSet(), anchor)
                applyRange(anchor)
            }
            onRangeMove = { applyRange(it) }
        }
        val gallery = FrameLayout(this)
        val empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), dp(48))
        }
        empty.addView(ImageView(this).apply {
            setImageResource(resource("ic_launcher", "mipmap"))
            contentDescription = "相机连接"
        }, LinearLayout.LayoutParams(dp(76), dp(76)).apply { bottomMargin = dp(24) })
        emptyTitle = label("让照片，回到你的生活", 22f).apply { gravity = Gravity.CENTER }
        emptyHint = label("连接相机 Wi-Fi 后，将自动识别\n也可在应用内设置 Wi-Fi 名和密码", 14f).apply {
            gravity = Gravity.CENTER; setTextColor(muted); setLineSpacing(dp(5).toFloat(), 1f)
        }
        empty.addView(emptyTitle)
        empty.addView(emptyHint, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(24) })
        empty.addView(connect, LinearLayout.LayoutParams(-1, dp(52)))
        empty.addView(textAction("在应用内连接 Wi-Fi") { showWifiProfile() }.apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(48)))
        empty.addView(textAction("打开 Wi-Fi 设置") { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.apply {
            gravity = Gravity.CENTER; setTextColor(muted)
        }, LinearLayout.LayoutParams(-1, dp(52)))
        gallery.addView(grid, FrameLayout.LayoutParams(-1, -1))
        gallery.addView(empty, FrameLayout.LayoutParams(-1, -1))
        grid.emptyView = empty
        root.addView(gallery, LinearLayout.LayoutParams(-1, 0, 1f))
        more = button("继续读取照片") { readPage() }.apply {
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            textSize = 13f; visibility = View.GONE
        }
        root.addView(more, LinearLayout.LayoutParams(-1, dp(44)))
        footer = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12)); visibility = View.GONE
        }
        selectionLabel = label("长按滑动多选", 13f).apply { setTextColor(muted) }
        footer.addView(selectionLabel, LinearLayout.LayoutParams(0, -2, 1f))
        download = button("导入原图", ::downloadSelected).apply {
            backgroundTintList = ColorStateList.valueOf(yellow)
            setTextColor(surfaceColor); textSize = 16f
        }
        footer.addView(download, LinearLayout.LayoutParams(dp(154), dp(52)))
        root.addView(footer)
        download.isEnabled = false; download.alpha = 0.4f
        setContentView(root)
        cameraWifi = CameraWifi(this, ::maybeAutoConnect, ::wifiLost, ::report)
        runCatching { cameraWifi.watch() }.onFailure { report("无法监听 Wi-Fi 状态，可点击连接相机手动重试。") }
        worker.scheduleWithFixedDelay({
            if (!busy.get() && !stopped) {
                val c = client
                if (c != null) try { c.ping() } catch (e: Exception) {
                    log("Heartbeat failed: ${e.message}")
                    c.abort()
                    if (client === c) client = null
                    report("连接已断开，请确认相机 Wi-Fi 后重新连接。已下载的照片仍保留。")
                    ui { setBusy(busy.get()) }
                }
            }
        }, 30, 30, java.util.concurrent.TimeUnit.SECONDS)
        log("ZfTransfer 0.3.0; Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
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
        AlertDialog.Builder(this).setTitle("相机与应用").setItems(arrayOf("相机 Wi-Fi 配置", "${if (automatic) "关闭" else "开启"}自动连接", "相机地址", "Wi-Fi 设置", "停止 / 断开", "分享日志", "关于", "忘记相机 Wi-Fi")) { _, which ->
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
                    userStopped = true; stoppedNetwork = sessionNetwork?.networkHandle
                    autoGate.stop(stoppedNetwork)
                    if (cameraWifi.requesting) cameraWifi.releaseRequest()
                    client?.abort(); client = null; sessionNetwork = null
                    report("已停止，成功导入的照片仍保留。点击顶部相机状态可重新连接。")
                    setBusy(busy.get())
                }
                5 -> shareLogs()
                6 -> showLicense()
                7 -> AlertDialog.Builder(this).setTitle("忘记相机 Wi-Fi？")
                    .setMessage("删除本应用加密保存的名称和密码，不删除系统 Wi-Fi 或导入照片。")
                    .setPositiveButton("忘记") { _, _ -> wifiStore.clear(); profileAttempted = true; pendingProfile = null; report("已忘记本应用保存的相机 Wi-Fi。") }
                    .setNegativeButton("取消", null).show()
            }
        }.show()
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
        if (client != null) {
            client?.abort(); client = null; sessionNetwork = null; setBusy(false)
        }
        cameraWifi.request(profile)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 41) return
        val profile = pendingProfile; pendingProfile = null
        if (permissions.indexOf(wifiPermission()).let { it >= 0 && grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED } && profile != null) requestWifi(profile, false)
        else report("未获得 Wi-Fi 连接权限。仍可手动连接系统 Wi-Fi，返回后自动识别相机。")
    }
    private fun wifiEndpoints(manual: String, only: Network? = null): List<WifiEndpoint> {
        val cm = getSystemService(ConnectivityManager::class.java)
        val candidates = if (only != null) listOf(only) else listOfNotNull(cameraWifi.requestedNetwork) + cm.allNetworks.toList()
        return candidates.distinct().mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val links = cm.getLinkProperties(network) ?: return@mapNotNull null
            val gateway = links.routes.asSequence().filter { it.isDefaultRoute }
                .mapNotNull { (it.gateway as? Inet4Address)?.hostAddress }.firstOrNull { CameraAddress.isUsable(it) }
                ?: if (android.os.Build.VERSION.SDK_INT >= 30) links.dhcpServerAddress?.hostAddress?.takeIf { CameraAddress.isUsable(it) } else null
            val address = manual.ifEmpty { gateway ?: return@mapNotNull null }
            WifiEndpoint(network, address, caps)
        }
    }
    private fun maybeAutoConnect() {
        if (!resumed || stopped || busy.get() || client != null || userStopped || !hasWindowFocus()) return
        val settling = 1800L - (android.os.SystemClock.elapsedRealtime() - resumedAt)
        if (settling > 0) { connectionHandler.postDelayed({ if (!stopped) cameraWifi.schedule() }, settling); return }
        if (!prefs.getBoolean("auto_connect", true) && cameraWifi.requestedNetwork == null) return
        val manual = host.text.toString().trim()
        if (manual.isNotEmpty() && !CameraAddress.isUsable(manual)) return
        val known = prefs.getString("last_camera_address", "192.168.1.1").orEmpty()
        val endpoint = wifiEndpoints(manual).firstOrNull {
            val ssid = runCatching { (it.caps.transportInfo as? WifiInfo)?.ssid }.getOrNull()
            AutoConnectGate.isCameraCandidate(it.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), ssid,
                it.address, if (manual.isEmpty()) known else manual, it.network == cameraWifi.requestedNetwork)
        }
        if (endpoint != null) {
            if (autoGate.take(endpoint.network.networkHandle, endpoint.address)) {
                connectCamera(endpoint.network, automatic = true)
                cameraLabel.text = "Wi-Fi 已连接 · 正在识别相机…"
            }
        } else if (!profileAttempted && !cameraWifi.requesting) {
            val saved = wifiStore.load()
            if (saved != null && checkSelfPermission(wifiPermission()) == PackageManager.PERMISSION_GRANTED) {
                profileAttempted = true; requestWifi(saved, true)
            }
        }
    }
    private fun wifiLost(network: Network) {
        if (stopped) return
        autoGate.lost(network.networkHandle)
        if (network.networkHandle == stoppedNetwork) { userStopped = false; stoppedNetwork = null }
        if (sessionNetwork == network) {
            client?.abort(); client = null; sessionNetwork = null
            report("相机 Wi-Fi 已断开。重新连接相机热点后将自动识别，已导入照片仍保留。")
            setBusy(busy.get())
        }
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
    private fun thumbnail(handle: Long): Bitmap? {
        thumbs.get(handle)?.let { return it }
        val file = java.io.File(cacheDir, "thumb_$handle.jpg")
        val bitmap = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
        if (bitmap != null) thumbs.put(handle, bitmap)
        return bitmap
    }
    override fun onResume() {
        super.onResume()
        resumed = true
        resumedAt = android.os.SystemClock.elapsedRealtime()
        if (::cameraWifi.isInitialized) cameraWifi.schedule()
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
    override fun onPause() { resumed = false; super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::cameraWifi.isInitialized) cameraWifi.schedule()
    }
    private fun photoCell(obj: CameraObject): View {
        val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val frame = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(0xff242629.toInt()); cornerRadius = dp(5).toFloat() }
            clipToOutline = true
        }
        val width = ((resources.displayMetrics.widthPixels - dp(48)) / 3).coerceAtLeast(dp(60))
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(thumbnail(obj.handle))
            setBackgroundColor(0xff242629.toInt())
            contentDescription = "预览 ${obj.filename}"
        }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        if (thumbnail(obj.handle) == null) frame.addView(label("无预览", 12f), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val check = ImageView(this).apply {
            setImageResource(resource(if (obj.handle in selected) "ic_selected" else "ic_unselected", "drawable"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "选择 ${obj.filename}"
            isEnabled = !busy.get()
            setOnClickListener { toggle(obj) }
        }
        frame.addView(check, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.RIGHT))
        if (obj.handle in imported) {
            val badge = label("已导入", 10f).apply {
                setPadding(dp(7), dp(3), dp(7), dp(3))
                background = GradientDrawable().apply { setColor(0xdd202225.toInt()); cornerRadius = dp(12).toFloat() }
            }
            frame.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.RIGHT))
        }
        if (obj.handle in selected) frame.foreground = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(2), yellow) }
        val frameHeight = if (grid.height > 0) minOf(width, ((grid.height - dp(36)) / 4 - dp(26)).coerceAtLeast(dp(68))) else width
        cell.addView(frame, LinearLayout.LayoutParams(-1, frameHeight))
        cell.addView(label(obj.filename, 10f).apply { setTextColor(muted); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        return cell
    }
    private fun showPreview(index: Int) {
        if (index !in photos.indices) return
        previewIndex = index
        if (preview?.isShowing == true) {
            renderPreview()
            return
        }
        val dialog = Dialog(this, android.R.style.Theme_Material_NoActionBar)
        preview = dialog
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(surfaceColor)
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageButton(this).apply {
            setImageResource(resource("ic_back", "drawable")); setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "返回照片"; setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        header.addView(label("照片预览", 18f))
        root.addView(header)
        previewImage = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(previewImage, LinearLayout.LayoutParams(-1, 0, 1f))
        previewStrip = LinearLayout(this)
        root.addView(HorizontalScrollView(this).apply { addView(previewStrip); isHorizontalScrollBarEnabled = false })
        previewName = label("", 20f)
        root.addView(previewName, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        root.addView(label("相机缩略预览 · 导入时保存 JPEG 原图", 12f).apply { setTextColor(muted) })
        previewSize = label("", 13f).apply { setTextColor(muted) }
        root.addView(previewSize)
        val navigation = LinearLayout(this)
        previewPrevious = textAction("上一张") { showPreview(previewIndex - 1) }
        previewNext = textAction("下一张") { showPreview(previewIndex + 1) }
        navigation.addView(previewPrevious, LinearLayout.LayoutParams(0, dp(44), 1f))
        navigation.addView(previewNext, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(navigation)
        root.addView(button("导入这张原图") {
            val obj = photos[previewIndex]
            dialog.dismiss(); requestImport(listOf(obj))
        }.apply { backgroundTintList = ColorStateList.valueOf(yellow); setTextColor(surfaceColor) }, LinearLayout.LayoutParams(-1, dp(52)))
        previewSelect = textAction("") { toggle(photos[previewIndex]); dialog.dismiss() }
        root.addView(previewSelect, LinearLayout.LayoutParams(-1, dp(48)))
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(16), insets.systemWindowInsetTop + dp(8), dp(16), insets.systemWindowInsetBottom + dp(8))
            insets
        }
        renderPreview()
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setLayout(-1, -1)
    }
    private fun renderPreview() {
        val obj = photos[previewIndex]
        previewImage.setImageBitmap(thumbnail(obj.handle))
        previewImage.contentDescription = obj.filename
        previewName.text = obj.filename + if (obj.handle in imported) " · 已导入" else ""
        previewSize.text = "JPEG · ${"%.1f".format(obj.size / 1048576.0)} MB"
        previewSelect.text = if (obj.handle in selected) "移出选择" else "加入选择"
        previewPrevious.isEnabled = previewIndex > 0
        previewNext.isEnabled = previewIndex < photos.lastIndex
        previewPrevious.alpha = if (previewPrevious.isEnabled) 1f else 0.3f
        previewNext.alpha = if (previewNext.isEnabled) 1f else 0.3f
        previewStrip.removeAllViews()
        for (position in maxOf(0, previewIndex - 3)..minOf(photos.lastIndex, previewIndex + 3)) {
            previewStrip.addView(ImageView(this).apply {
                setImageBitmap(thumbnail(photos[position].handle)); scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "预览 ${photos[position].filename}"
                setPadding(dp(2), dp(2), dp(2), dp(2))
                if (position == previewIndex) setBackgroundColor(yellow)
                setOnClickListener { showPreview(position) }
            }, LinearLayout.LayoutParams(dp(72), dp(56)).apply { rightMargin = dp(6) })
        }
    }
    private fun ui(action: () -> Unit) { runOnUiThread { if (!isDestroyed && !stopped) action() } }
    @Synchronized private fun log(message: String) {
        if (logs.size >= 400) logs.removeFirst()
        logs.addLast("${java.time.LocalTime.now().withNano(0)} $message")
        android.util.Log.i("ZfTransfer", message)
    }
    private fun report(message: String) { ui { status.text = message; status.visibility = View.VISIBLE } }
    private fun task(onComplete: () -> Unit = {}, action: () -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        setBusy(true)
        worker.execute {
            try { action() }
            catch (e: Exception) {
                log("FAIL ${e.javaClass.simpleName}: ${e.message}")
                client?.abort(); client = null; sessionNetwork = null
                report(if (userStopped) "已停止。已成功导入的照片仍保留，未完成文件已清理。" else "操作失败：${e.message}\n可分享日志。确认相机热点后，点连接重试。")
            } finally {
                busy.set(false)
                ui { setBusy(false); onComplete() }
            }
        }
    }
    private fun setBusy(value: Boolean) {
        connect.isEnabled = !value
        host.isEnabled = !value
        more.isEnabled = !value && client != null && cursor < handles.size
        download.isEnabled = !value && client != null && selected.isNotEmpty()
        more.alpha = if (more.isEnabled) 1f else 0.4f
        download.alpha = if (download.isEnabled) 1f else 0.4f
        grid.editable = !value
        progress.visibility = if (value) View.VISIBLE else View.GONE
        cameraLabel.text = if (client == null) "$cameraName · 点此连接" else "$cameraName · 已连接"
        cameraLabel.setTextColor(if (client == null) muted else yellow)
        connect.visibility = if (client == null) View.VISIBLE else View.GONE
        emptyTitle.text = if (client == null) "让照片，回到你的生活" else "正在寻找相机中的照片"
        emptyHint.text = if (client == null) "连接相机 Wi-Fi 后，将自动识别\n也可在应用内设置 Wi-Fi 名和密码" else "仅显示 JPEG 原图；可继续读取下一批"
        updateGalleryControls()
        adapter.notifyDataSetChanged()
    }
    private fun connectCamera(networkOverride: Network? = null, automatic: Boolean = false) {
        userStopped = false
        val manual = host.text.toString().trim()
        if (manual.isNotEmpty() && !validIpv4(manual)) {
            report("请填写正确的 IPv4 地址，或清空后自动识别。")
            return
        }
        prefs.edit().putString("host", manual).apply()
        if (busy.get()) return
        cacheDir.listFiles()?.filter { it.name.startsWith("thumb_") && it.name.endsWith(".jpg") }?.forEach { it.delete() }
        photos.clear(); thumbs.evictAll(); imported.clear(); selected.clear(); selectionMode = false; updateSelection()
        task(onComplete = {
            if (automatic && client == null && networkOverride != null) {
                connectionHandler.postDelayed({
                    if (!stopped && resumed && !userStopped && client == null && !busy.get()) {
                        val endpoint = wifiEndpoints(manual, networkOverride).firstOrNull()
                        if (endpoint != null && autoGate.retryAfterFailure(endpoint.network.networkHandle, endpoint.address)) {
                            report("正在等待相机就绪，自动重试连接…")
                            maybeAutoConnect()
                        }
                    }
                }, 3000)
            }
        }) {
            client?.abort(); client = null
            val candidate = wifiEndpoints(manual, networkOverride).firstOrNull()
                ?: error("Wi-Fi 尚未就绪：请保持连接相机热点，等待获取 IPv4 地址后重试")
            val network = candidate.network
            val address = candidate.address
            sessionNetwork = network
            log("Using physical Wi-Fi network=${network.networkHandle}; endpoint=$address; mode=${if (cameraWifi.requestedNetwork == network) "app-request" else "system-wifi"}")
            report("正在连接 $address …")
            val c = PtpIpClient(host = address, clientName = "ZfTransfer", socketFactory = network.socketFactory,
                zfMode = true, log = ::log)
            client = c
            c.connect()
            if (client !== c) { c.abort(); error("已停止连接") }
            val info = c.getDeviceInfo()
            log("Camera model=${info.model}; advertised ops=${info.operationsSupported.size}")
            prefs.edit().putString("last_camera_address", address).apply()
            cameraName = info.displayName
            cameraIdentity = info.serialNumber.takeIf { it.isNotBlank() }?.let { "${info.manufacturer}|${info.model}|$it" }
            if (cameraIdentity == null) {
                log("No stable serial: duplicate skipping disabled")
                ui { Toast.makeText(this, "相机未提供稳定标识，无法确认历史导入；不会标记或跳过照片。", Toast.LENGTH_LONG).show() }
            }
            // Zf may advertise a partial operation table: exercise read operations directly.
            handles = c.listObjectHandles().asReversed()
            cursor = 0
            report("已连接 ${info.displayName}，共 ${handles.size} 个对象，正在读取照片…")
            loadPage(c)
        }
    }
    private fun readPage() { task { loadPage(client ?: error("请先连接相机")) } }
    private fun loadPage(c: PtpIpClient) {
        var foundPhotos = 0
        val end = minOf(cursor + 40, handles.size)
        while (cursor < end) {
            check(client === c) { "已停止读取" }
            val obj = c.getObjectInfo(handles[cursor])
            cursor++
            if (obj.isJpeg) {
                val thumb = try { c.getThumbnail(obj.handle) } catch (e: PtpException) {
                    // A PTP error has a fully consumed response; transport errors abort the task.
                    if (!e.message.orEmpty().startsWith("Camera returned PTP error")) throw e
                    log("Thumbnail unavailable; keeping original download")
                    null
                }
                val bitmap = thumb?.let {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(it, 0, it.size, bounds)
                    val opts = BitmapFactory.Options().apply {
                        var sample = 1
                        while (bounds.outWidth / sample > 240 || bounds.outHeight / sample > 240) sample *= 2
                        inSampleSize = sample
                    }
                    BitmapFactory.decodeByteArray(it, 0, it.size, opts)
                }
                if (bitmap != null) runCatching {
                    java.io.File(cacheDir, "thumb_${obj.handle}.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                }
                val exists = savedExists(obj)
                ui {
                    photos.add(obj)
                    if (bitmap != null) thumbs.put(obj.handle, bitmap)
                    if (exists) imported.add(obj.handle)
                    adapter.notifyDataSetChanged()
                    updateGalleryControls()
                }
                foundPhotos++
            }
            report("已读取 $cursor / ${handles.size} 个对象，本批找到 $foundPhotos 张 JPEG")
        }
        report("已读取 $cursor / ${handles.size} 个对象。勾选 JPEG 下载原图${if (cursor < handles.size) "，或继续读取下一批。" else "。"}")
    }
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
    private fun savedExists(obj: CameraObject): Boolean {
        val key = duplicateKey(obj) ?: return false
        val saved = prefs.getString(key, null)?.let(Uri::parse) ?: return false
        return runCatching {
            val valid = contentResolver.query(saved, arrayOf(MediaStore.Images.Media.SIZE, MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.MIME_TYPE), null, null, null)?.use {
                it.moveToFirst() && ImportEvidence.matches(obj.size, it.getLong(0), it.getInt(1), it.getString(2))
            } ?: false
            valid && (contentResolver.openFileDescriptor(saved, "r")?.use { it.statSize == obj.size } ?: false)
        }.getOrDefault(false)
    }
    private fun requestImport(items: List<CameraObject>) {
        if (busy.get() || client == null || items.isEmpty()) return
        var duplicates = emptySet<Long>()
        task(onComplete = {
            if (client != null) {
                items.forEach { if (it.handle in duplicates) imported.add(it.handle) else imported.remove(it.handle) }
                adapter.notifyDataSetChanged()
                if (duplicates.isEmpty()) startImport(items, false)
                else showDuplicateChoices(items, duplicates.size)
            }
        }) { duplicates = items.filter { savedExists(it) }.map { it.handle }.toSet() }
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
    private fun startImport(items: List<CameraObject>, skipExisting: Boolean) {
        task {
            val c = client ?: error("请先连接相机")
            var done = 0
            var skipped = 0
            for ((index, obj) in items.withIndex()) {
                check(client === c) { "已停止传输" }
                report("正在下载 ${index + 1}/${items.size}：${obj.filename}")
                val key = duplicateKey(obj)
                val exists = skipExisting && savedExists(obj)
                if (!exists) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, obj.filename.replace(Regex("[\\\\/\\p{Cntrl}]"), "_"))
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ZfTransfer")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: error("无法创建相册文件")
                    var published = false
                    try {
                        var count = 0L
                        val raw = contentResolver.openOutputStream(uri, "w") ?: error("无法写入相册")
                        val output = object : FilterOutputStream(raw) {
                            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
                            override fun write(b: Int) { out.write(b); count++ }
                        }
                        output.use {
                            var lastPercent = -1
                            c.downloadObject(obj.handle, it) { p ->
                                val percentage = if (obj.size > 0) (p.bytesTransferred * 100 / obj.size).coerceIn(0, 100).toInt() else 0
                                if (percentage != lastPercent) { lastPercent = percentage; ui { progress.progress = percentage } }
                            }
                        }
                        check(count == obj.size) { "文件大小不一致：$count / ${obj.size}，未保留不完整文件" }
                        check(contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1) {
                            "相册文件发布失败"
                        }
                        published = true
                        if (key != null) prefs.edit().putString(key, uri.toString()).apply()
                        log("Saved original JPEG; bytes=$count")
                    } finally {
                        if (!published) contentResolver.delete(uri, null, null)
                    }
                    done++
                } else { skipped++; log("Skipped existing original") }
                val verified = savedExists(obj)
                ui { selected.remove(obj.handle); if (verified) imported.add(obj.handle) else imported.remove(obj.handle); updateSelection() }
            }
            report("已导入 $done 张，跳过 $skipped 张。保存至系统相册 Pictures/ZfTransfer。")
        }
    }
    private fun duplicateKey(obj: CameraObject): String? = cameraIdentity?.takeIf { obj.captureDate.isNotBlank() && obj.size > 0 }?.let {
        val content = "$it|${obj.storageId}|${obj.handle}|${obj.filename}|${obj.captureDate}|${obj.size}"
        "saved_" + MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { b -> "%02x".format(b) }
    }
    private fun shareLogs() {
        val text = synchronized(this) { logs.joinToString("\n") }
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
        connectionHandler.removeCallbacksAndMessages(null)
        if (::cameraWifi.isInitialized) cameraWifi.close()
        client?.abort()
        worker.shutdownNow()
        super.onDestroy()
    }
    companion object {
        private fun validIpv4(value: String): Boolean = CameraAddress.isUsable(value)
    }
}
