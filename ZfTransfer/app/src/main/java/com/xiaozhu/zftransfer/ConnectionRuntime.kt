package com.xiaozhu.zftransfer

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Network
import android.net.NetworkCapabilities
import android.net.ConnectivityManager
import android.net.wifi.WifiInfo
import io.github.noisetta.nikonpull.ptp.*
import java.net.Inet4Address

/** Application-scoped state; the service owns connection lifetime, never a screen. */
class ConnectionRuntime(private val context: android.content.Context) {
    val session = CameraSession()
    val lifetime = ConnectionLifetime()
    val autoGate = AutoConnectGate()
    private val preparation = ConnectionPreparation()
    @Volatile private var readyClient: PtpIpClient? = null
    val connectionReady get() = readyClient != null && readyClient === client
    private var pendingConnect: Runnable? = null
    private var pendingNetwork: Network? = null
    val connectionPending get() = pendingConnect != null
    private fun cancelPendingConnect() {
        pendingConnect?.let { connectionHandler.removeCallbacks(it) }
        pendingConnect = null; pendingNetwork = null
    }
    val connectionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    var foreground = false
    var resumedAt = 0L
    @Volatile var userStopped = false
    var stoppedNetwork: Long? = null
    var profileAttempted = false
    private var lastWifiAttempt = 0L
    private var wifiWasAvailable = false
    var serviceActive = false
    private val prefs = context.getSharedPreferences("transfers", 0)
    private val wifiStore = WifiProfileStore(context)
    val imports = ImportRepository(context.contentResolver, prefs, ::log)
    val transfers = TransferCoordinator(imports, TransferQueueStore(context.getSharedPreferences("transfer_queue", 0)))
    val cacheDir get() = context.cacheDir
    val worker get() = session.worker
    val busy get() = session.busy
    var client: PtpIpClient?
        get() = session.client
        set(value) { session.client = value }
    var sessionNetwork: Network?
        get() = session.network
        set(value) { session.network = value }
    var cameraIdentity: String?
        get() = imports.identity
        set(value) { imports.identity = value }
    @Volatile var cameraName = "Nikon Zf"
    private val galleryPhotos = mutableListOf<CameraObject>()
    val photos: List<CameraObject> get() = galleryPhotos
    private val importedHandles = mutableSetOf<Long>()
    val imported: Set<Long> get() = importedHandles
    private val completions = ImportCompletions()
    fun completedSince(cursor: Long) = completions.since(cursor)
    private val pageReads = PageReadGate()
    val thumbs = object : android.util.LruCache<Long, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    @Volatile var imageSession = java.util.UUID.randomUUID().toString()
    var handles = emptyList<Long>()
    @Volatile var cursor = 0
    var message = ""
    var taskState = TaskState()
        private set
    val progress get() = taskState.percentage
    @Volatile var revision = 0L
    private var issue: Pair<UserIssue, Boolean>? = null
    private val listeners = linkedSetOf<() -> Unit>()
    private val logs = ArrayDeque<String>()
    private data class WifiEndpoint(val network: Network, val address: String, val caps: NetworkCapabilities)
    val cameraWifi = CameraWifi(context, ::maybeAutoConnect, ::wifiLost, ::report) { issue, interactive ->
        if (!connectionReady) presentIssue(issue, interactive)
        else log("Ignoring obsolete Wi-Fi request failure; verified camera session is connected")
    }
    private fun now() = android.os.SystemClock.elapsedRealtime()
    fun attach(listener: () -> Unit) { listeners.add(listener) }
    fun detach(listener: () -> Unit) { listeners.remove(listener) }
    fun takeIssue(): Pair<UserIssue, Boolean>? = issue.also { issue = null }
    private fun changed() { ui { listeners.toList().forEach { it() } } }
    private fun ui(action: () -> Unit) { connectionHandler.post(action) }
    fun report(text: String) { ui { message = text; changed() } }
    fun presentIssue(value: UserIssue, interactive: Boolean = true) {
        ui { issue = value to interactive; message = value.title + "\n" + value.message; changed() }
    }
    @Synchronized fun log(message: String) {
        if (logs.size >= 400) logs.removeFirst()
        logs.addLast("${java.time.LocalTime.now().withNano(0)} $message")
        android.util.Log.i("ZfTransfer", message)
    }
    @Synchronized fun logText() = logs.joinToString("\n")
    fun thumbnailFile(handle: Long, session: String = imageSession) = java.io.File(cacheDir, "thumb_${session}_$handle.jpg")
    private fun savedExists(obj: CameraObject) = imports.verifiedUri(obj) != null
    private fun duplicateKey(obj: CameraObject) = imports.key(obj)
    private fun wifiPermission() = if (android.os.Build.VERSION.SDK_INT >= 33) "android.permission.NEARBY_WIFI_DEVICES" else "android.permission.ACCESS_FINE_LOCATION"
    fun serviceStarted() { serviceActive = true; watch(); revision++; changed() }
    fun watch() { runCatching { cameraWifi.watch() }.onFailure { report("无法监听 Wi-Fi 状态，可手动重试连接。") } }
    fun ensureService(): Boolean {
        if (serviceActive) { lifetime.request(); return true }
        return try {
            context.startForegroundService(Intent(context, CameraConnectionService::class.java))
            lifetime.request(); true
        } catch (e: Exception) { presentIssue(UserIssue("无法启动连接服务", "请返回应用前台后重新连接相机。", Recovery.APP_SETTINGS)); false }
    }
    fun requestWifi(profile: WifiProfile, automatic: Boolean) {
        if (!ensureService()) return
        userStopped = false
        cancelPendingConnect(); preparation.reset()
        session.disconnect()
        lastWifiAttempt = now()
        cameraWifi.request(profile, !automatic)
    }
    fun stop() {
        userStopped = true; cancelPendingConnect(); preparation.reset(); lifetime.stop(); autoGate.stop(sessionNetwork?.networkHandle)
        session.disconnect(); cameraWifi.close(); revision++
        report("已断开连接。成功导入的照片仍保留，未完成任务可稍后重试。")
        changed()
    }
    init {
        worker.scheduleWithFixedDelay({
            if (lifetime.desired && !busy.get()) {
                val c = client
                if (c != null) try { c.ping(); log("Connection heartbeat OK") } catch (e: Exception) {
                    c.abort(); if (client === c) client = null
                    lifetime.failed(now()); report("相机暂时无响应，正在等待连接恢复…")
                    ui { revision++; changed() }
                }
                else ui {
                    if (lifetime.canReconnect(now())) { autoGate.reset(); maybeAutoConnect() }
                }
            }
        }, 15, 15, java.util.concurrent.TimeUnit.SECONDS)
    }
    private fun wifiEndpoints(manual: String, only: Network? = null): List<WifiEndpoint> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
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
    private fun cameraEndpoint(manual: String, only: Network? = null): WifiEndpoint? {
        val endpoints = wifiEndpoints(manual, only)
        val names = setOfNotNull(wifiStore.load()?.ssid, prefs.getString("last_camera_ssid", null))
        val chosen = CameraNetworkSelector.select(endpoints.map {
            CameraNetworkHint(it.network.networkHandle, it.address,
                runCatching { (it.caps.transportInfo as? WifiInfo)?.ssid }.getOrNull(),
                it.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), it.network == cameraWifi.requestedNetwork)
        }, manual, prefs.getString("last_camera_address", "192.168.1.1").orEmpty(), names) ?: return null
        return endpoints.firstOrNull { it.network.networkHandle == chosen.id }
    }
    private fun maybeAutoConnect() {
        if (cameraWifi.requestedNetwork != null) wifiWasAvailable = true
        if ((!foreground && !lifetime.desired) || busy.get() || client != null || userStopped) return
        val settling = if (foreground) 1800L - (android.os.SystemClock.elapsedRealtime() - resumedAt) else 0L
        if (settling > 0) { connectionHandler.postDelayed({ if (!userStopped) cameraWifi.schedule() }, settling); return }
        if (!lifetime.desired && !prefs.getBoolean("auto_connect", true) && cameraWifi.requestedNetwork == null) return
        if (lifetime.desired && !lifetime.canReconnect(now())) return
        val manual = prefs.getString("host", "").orEmpty().trim()
        if (manual.isNotEmpty() && !CameraAddress.isUsable(manual)) return
        val endpoint = cameraEndpoint(manual)
        if (endpoint != null) {
            if (autoGate.take(endpoint.network.networkHandle, endpoint.address)) {
                connectCamera(endpoint.network, automatic = true)

            }
        } else if ((!profileAttempted || (wifiWasAvailable && now() - lastWifiAttempt >= 60000)) && !cameraWifi.hasActiveRequest) {
            val saved = wifiStore.load()
            if (saved != null && context.checkSelfPermission(wifiPermission()) == PackageManager.PERMISSION_GRANTED) {
                profileAttempted = true; requestWifi(saved, true)
            }
        }
    }
    private fun wifiLost(network: Network) {
        if (userStopped) return
        autoGate.lost(network.networkHandle)
        if (pendingNetwork == network) { cancelPendingConnect(); preparation.resetNetwork() }
        if (sessionNetwork == network) {
            session.disconnect()
            report("相机 Wi-Fi 已断开。重新连接相机热点后将自动识别，已导入照片仍保留。")
            revision++; changed()
        }
    }
    fun task(operation: UserOperation = UserOperation.READ_PHOTOS, silentErrors: Boolean = false, onComplete: () -> Unit = {}, action: () -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        taskState = TaskState(operation)
        changed()
        worker.execute {
            try { action() }
            catch (e: Exception) {
                log("FAIL ${e.stackTraceToString()}")
                if (operation == UserOperation.CONNECT) session.disconnect() else session.recoverAfterFailure()
                if (!userStopped && client == null) lifetime.failed(now())
                if (userStopped) report("已停止。已成功导入的照片仍保留，未完成文件不会标记为已导入。")
                else if (operation == UserOperation.CONNECT && lifetime.desired && preparation.retryQuietly(e)) {
                    ui { issue = null }
                    report("正在连接相机，请保持手机连接相机 Wi-Fi。")
                } else presentIssue(UserIssues.from(e, operation), !silentErrors)
            } finally {
                ui { busy.set(false); taskState = TaskState(); revision++; changed(); onComplete() }
            }
        }
    }
    fun connectCamera(networkOverride: Network? = null, automatic: Boolean = false) {
        if (busy.get()) return
        userStopped = false
        watch()
        val manual = prefs.getString("host", "").orEmpty().trim()
        if (manual.isNotEmpty() && !CameraAddress.isUsable(manual)) {
            report("请填写正确的 IPv4 地址，或清空后自动识别。")
            return
        }
        val selectedEndpoint = cameraEndpoint(manual, networkOverride)
        if (selectedEndpoint == null) {
            log("No camera Wi-Fi candidate; ordinary Wi-Fi was not probed")
            if (cameraWifi.requesting) {
                report("正在请求连接相机 Wi-Fi，请在系统弹窗中确认，或手动连接后返回应用。")
                return
            }
            val profile = wifiStore.load()
            if (!automatic && profile != null && context.checkSelfPermission(wifiPermission()) == PackageManager.PERMISSION_GRANTED) {
                profileAttempted = true; requestWifi(profile, false)
            } else if (!automatic) {
                presentIssue(UserIssue("手机尚未连接相机 Wi-Fi", "请在手机 Wi-Fi 设置中连接相机屏幕显示的热点，再返回应用。请保持手机连接该热点。", Recovery.WIFI_SETTINGS))
            }
            return
        }
        if (!ensureService()) return
        val wait = preparation.waitMillis(selectedEndpoint.network.networkHandle, selectedEndpoint.address, now())
        cancelPendingConnect()
        if (wait > 0) {
            report("已识别相机 Wi-Fi，正在连接…")
            pendingNetwork = selectedEndpoint.network
            pendingConnect = Runnable {
                pendingConnect = null; pendingNetwork = null
                if (lifetime.desired && !userStopped) connectCamera(selectedEndpoint.network, automatic)
            }.also { connectionHandler.postDelayed(it, wait) }
            revision++; changed()
            return
        }
        cacheDir.listFiles()?.filter { it.name.startsWith("thumb_") && it.name.endsWith(".jpg") }?.forEach { it.delete() }
        imageSession = java.util.UUID.randomUUID().toString()
        galleryPhotos.clear(); thumbs.evictAll(); importedHandles.clear(); completions.beginBatch(); revision++; changed()
        task(operation = UserOperation.CONNECT, silentErrors = automatic) {
            check(!userStopped && lifetime.desired) { "已停止连接" }
            session.disconnect()
            val candidate = cameraEndpoint(manual, selectedEndpoint.network)
                ?: error("Wi-Fi 尚未就绪：请保持连接相机热点，等待获取 IPv4 地址后重试")
            val network = candidate.network
            val address = candidate.address
            sessionNetwork = network
            log("Using physical Wi-Fi network=${network.networkHandle}; endpoint=$address; mode=${if (cameraWifi.requestedNetwork == network) "app-request" else "system-wifi"}")
            report("正在连接相机…")
            val c = PtpIpClient(host = address, clientName = "ZfTransfer", socketFactory = network.socketFactory,
                zfMode = true, log = ::log)
            client = c
            if (userStopped || !lifetime.desired) { session.disconnect(); error("已停止连接") }
            c.connect()
            if (client !== c || userStopped || !lifetime.desired) { c.abort(); error("已停止连接") }
            val info = c.getDeviceInfo()
            check(client === c && !userStopped && lifetime.desired) { "已停止连接" }
            log("Camera model=${info.model}; advertised ops=${info.operationsSupported.size}")
            val learnedSsid = runCatching { (candidate.caps.transportInfo as? WifiInfo)?.ssid?.trim('"') }.getOrNull()
            prefs.edit().putString("last_camera_address", address).apply {
                if (!learnedSsid.isNullOrBlank() && learnedSsid != "<unknown ssid>") putString("last_camera_ssid", learnedSsid)
            }.apply()
            readyClient = c
            ui {
                // A user-selected system network already works: cancel a different, unfulfilled request.
                if (client === c && cameraWifi.requesting && cameraWifi.requestedNetwork == null) cameraWifi.releaseRequest()
            }
            cameraName = info.displayName
            cameraIdentity = info.serialNumber.takeIf { it.isNotBlank() }?.let { "${info.manufacturer}|${info.model}|$it" }
            if (cameraIdentity == null) {
                log("No stable serial: duplicate skipping disabled")
                presentIssue(UserIssue("无法确认历史导入", "相机未提供稳定标识，本次不会显示已导入或自动跳过照片。", Recovery.NONE))
            }
            // Zf may advertise a partial operation table: exercise read operations directly.
            handles = c.listObjectHandles().asReversed()
            cursor = 0
            report("已连接 ${info.displayName}，正在读取照片…")
            lifetime.connected(); preparation.reset(); ui { issue = null }; revision++
            loadPage(c)
        }
    }
    fun beginBrowseGesture() = pageReads.resetGesture()
    fun readPage(automatic: Boolean = false) {
        if (!pageReads.request(imageSession, cursor, handles.size, busy.get(), client != null, automatic)) return
        task { loadPage(client ?: error("请先连接相机")) }
    }
    private fun loadPage(c: PtpIpClient) {
        val pageSession = imageSession
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
                        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
                        inSampleSize = sample
                    }
                    BitmapFactory.decodeByteArray(it, 0, it.size, opts)
                }
                if (bitmap != null) runCatching {
                    thumbnailFile(obj.handle).writeBytes(thumb!!)
                }
                val exists = savedExists(obj)
                ui {
                    if (pageSession != imageSession) return@ui
                    galleryPhotos.add(obj)
                    if (bitmap != null) thumbs.put(obj.handle, bitmap)
                    if (exists) importedHandles.add(obj.handle)
                    revision++; changed()
                }

            }
            report("正在读取照片…")
        }
        ui { message = "已加载 ${photos.size} 张照片${if (cursor < handles.size) " · 向后浏览自动加载更多" else " · 已全部加载"}"; changed() }
    }
    val mediumPreviews by lazy { OriginalPreviewCache(java.io.File(cacheDir, "medium_previews"), 48L * 1024 * 1024) }
    fun requestMediumPreview(photo: CameraObject, key: String, current: () -> Boolean, ready: (java.io.File?) -> Unit) {
        worker.execute {
            if (!current()) return@execute
            var result: java.io.File? = null
            val c = client
            try {
                result = mediumPreviews.findCompleted(key)
                if (result == null && c != null) {
                    for (fullHd in listOf(true, false)) {
                        if (!current() || client !== c) break
                        val bytes = try { c.getNikonPreview(photo.handle, fullHd) } catch (e: PtpException) {
                            if (!e.message.orEmpty().startsWith("Camera returned PTP error")) throw e
                            log("Medium preview rejected fullHd=$fullHd: ${e.message}")
                            continue
                        }
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        if (MediumPreviewPolicy.usable(bounds.outWidth, bounds.outHeight, bytes.size)) {
                            result = mediumPreviews.store(key, bytes.size.toLong()) { it.write(bytes) }
                            log("Medium preview bytes=${bytes.size} width=${bounds.outWidth} height=${bounds.outHeight}")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                log("Medium preview failed: ${e.stackTraceToString()}")
                session.recoverAfterFailure()
                if (client == null && !userStopped) lifetime.failed(now())
            }
            val completed = result
            ui { if (current()) ready(completed) }
        }
    }
    /** Evidence ownership stays here; screens never rewrite the shared imported set. */
    fun refreshImportEvidence() {
        if (busy.get()) return
        val snapshot = photos.toList()
        val identity = cameraIdentity
        val session = imageSession
        worker.execute {
            val verified = snapshot.filter { savedExists(it) }.map { it.handle }.toSet()
            ui {
                if (!busy.get() && identity == cameraIdentity && session == imageSession) {
                    importedHandles.removeAll(snapshot.map { it.handle }.toSet()); importedHandles.addAll(verified); revision++; changed()
                }
            }
        }
    }
    fun checkImported(items: List<CameraObject>, ready: (Set<Long>) -> Unit) {
        val session = imageSession
        var duplicates: Set<Long>? = null
        task(operation = UserOperation.IMPORT, onComplete = {
            val checked = duplicates
            if (checked != null && client != null && session == imageSession) {
                items.forEach { if (it.handle in checked) importedHandles.add(it.handle) else importedHandles.remove(it.handle) }
                revision++; changed()
                ready(checked)
            }
        }) { duplicates = items.filter { savedExists(it) }.map { it.handle }.toSet() }
    }
    fun startImport(items: List<CameraObject>, skipExisting: Boolean, retry: Boolean = false) {
        if (items.isEmpty() || busy.get()) return
        completions.beginBatch()
        task(operation = UserOperation.IMPORT) {
            val c = client ?: error("请先连接相机")
            val (done, skipped) = transfers.run(c, items, skipExisting, retry,
                checkActive = { check(client === c) { "已停止传输" } },
                progress = { index, obj, percentage ->
                    report("正在下载 ${index + 1}/${items.size}：${obj.filename} · $percentage%")
                    ui { taskState = TaskState(UserOperation.IMPORT, ImportProgress(obj.handle, percentage.coerceIn(0, 100))); changed() }
                }, completed = { obj, verified -> ui {
                    completions.record(obj.handle)
                    if (verified) importedHandles.add(obj.handle) else importedHandles.remove(obj.handle)
                    revision++; changed()
                } })
            report("已导入 $done 张，跳过 $skipped 张。已保存到手机相册。")
        }
    }
}
