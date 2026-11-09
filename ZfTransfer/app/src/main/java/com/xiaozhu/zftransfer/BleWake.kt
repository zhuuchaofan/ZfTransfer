package com.xiaozhu.zftransfer

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Pure rules for Bluetooth wake-up and SnapBridge-mode hotspots, kept separate from Android framework calls so
 * they can be unit-tested directly.
 */
object BleWakeRules {
    const val SNAPBRIDGE_SUFFIX = "_SnapBridge"
    const val SNAPBRIDGE_PTP_NAME = "Android Device"
    const val DEFAULT_PTP_NAME = "ZfTransfer"
    const val NIKON_MANUFACTURER_ID = 0x0399

    fun isSnapBridgeSsid(ssid: String?): Boolean {
        val clean = ssid.orEmpty().trim('"').trim()
        return clean.contains("SnapBridge", ignoreCase = true)
    }

    fun ptpClientNameFor(ssid: String?): String =
        if (isSnapBridgeSsid(ssid)) SNAPBRIDGE_PTP_NAME else DEFAULT_PTP_NAME

    fun baseCameraName(name: String?): String? =
        name.orEmpty().trim('"').trim().removeSuffix(SNAPBRIDGE_SUFFIX).takeIf { it.isNotBlank() }

    /**
     * Picks a woken camera hotspot from nearby Wi-Fi scan results:
     * names containing "SnapBridge" or starting with a saved camera name (case-insensitive), strongest RSSI first.
     */
    fun matchHotspotSsid(scanCandidates: List<Pair<String, Int>>, savedCameraNames: Set<String>): String? {
        val prefixes = savedCameraNames.mapNotNull(::baseCameraName)
        return scanCandidates.asSequence()
            .map { (ssid, level) -> ssid.trim('"').trim() to level }
            .filter { (ssid, _) ->
                ssid.isNotBlank() && ssid != "<unknown ssid>" && (
                    ssid.contains("SnapBridge", ignoreCase = true) ||
                        prefixes.any { prefix -> ssid.startsWith(prefix, ignoreCase = true) }
                    )
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .firstOrNull()
    }

    /** Fallback target SSID when Wi-Fi scan results are empty or throttled by the OS. */
    fun fallbackWakeSsid(savedSsid: String?, pairedCameraName: String?): String? {
        val base = baseCameraName(savedSsid) ?: baseCameraName(pairedCameraName) ?: return null
        return base + SNAPBRIDGE_SUFFIX
    }

    /** Matches our paired camera's BLE advertisement: manufacturer 0x0399 carrying the first 4 bytes of our identity. */
    fun matchesIdentityPrefix(manufacturerData: ByteArray?, identity: ByteArray): Boolean =
        manufacturerData != null && manufacturerData.size >= 4 && identity.size >= 4 &&
            manufacturerData[0] == identity[0] &&
            manufacturerData[1] == identity[1] &&
            manufacturerData[2] == identity[2] &&
            manufacturerData[3] == identity[3]

    /** Recognizes a Nikon camera in Bluetooth pairing mode or classic discovery. */
    fun looksLikeNikonCamera(name: String?, hasNikonManufacturer: Boolean = false, hasNikonService: Boolean = false): Boolean {
        val n = name.orEmpty().trim()
        return hasNikonManufacturer || hasNikonService ||
            n.startsWith("Z_", ignoreCase = true) ||
            n.startsWith("Z ", ignoreCase = true) ||
            n.contains("NIKON", ignoreCase = true)
    }

    fun parseIdentityHex(hex: String?): ByteArray? {
        val clean = hex?.trim()?.lowercase() ?: return null
        if (clean.length != 16 || !clean.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return ByteArray(8) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun formatIdentityHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}

/**
 * Persists the controller identity in the same `ble_experiment` / `identity` preference used during the spike
 * so an already-paired camera stays paired without re-pairing.
 */
class BleWakeStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    companion object {
        const val PREFS_NAME = "ble_experiment"
        private const val KEY_IDENTITY = "identity"
        private const val KEY_PAIRED = "paired"
        private const val KEY_CAMERA_NAME = "camera_name"
        private const val KEY_CLASSIC = "classic"
    }

    val identityHex: String?
        get() = prefs.getString(KEY_IDENTITY, null)?.takeIf { BleWakeRules.parseIdentityHex(it) != null }

    val identityBytes: ByteArray?
        get() = BleWakeRules.parseIdentityHex(identityHex)

    /**
     * A camera is considered paired if `paired` was recorded, or if `identity` already exists from the experiment
     * and wasn't explicitly unpaired.
     */
    val isPaired: Boolean
        get() = identityBytes != null && prefs.getBoolean(KEY_PAIRED, true)

    val cameraName: String?
        get() = prefs.getString(KEY_CAMERA_NAME, null)?.takeIf { it.isNotBlank() }

    val classicAddress: String?
        get() = prefs.getString(KEY_CLASSIC, null)?.takeIf { it.isNotBlank() }

    fun getOrCreateIdentityHex(): String {
        identityHex?.let { return it }
        val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val hex = BleWakeRules.formatIdentityHex(bytes)
        prefs.edit().putString(KEY_IDENTITY, hex).commit()
        return hex
    }

    fun markPaired(cameraName: String?, classicAddress: String?) {
        prefs.edit().apply {
            putBoolean(KEY_PAIRED, true)
            if (!cameraName.isNullOrBlank()) putString(KEY_CAMERA_NAME, cameraName.trim())
            if (!classicAddress.isNullOrBlank()) putString(KEY_CLASSIC, classicAddress.trim())
        }.commit()
    }

    /** Clears the paired state in the app while keeping or clearing the stored camera metadata. */
    fun unpair() {
        prefs.edit()
            .putBoolean(KEY_PAIRED, false)
            .remove(KEY_CAMERA_NAME)
            .remove(KEY_CLASSIC)
            .commit()
    }
}

sealed class BleWakeOutcome {
    data object Woken : BleWakeOutcome()
    data object Cancelled : BleWakeOutcome()
    data class Failed(val issue: UserIssue) : BleWakeOutcome()
}

sealed class BlePairOutcome {
    data class Paired(val cameraName: String) : BlePairOutcome()
    data class ExistingBond(val bondedName: String) : BlePairOutcome()
    data object Cancelled : BlePairOutcome()
    data class Failed(val title: String, val message: String, val recovery: Recovery = Recovery.NONE) : BlePairOutcome()
}

/** Polls [queue] in short slices so a long Bluetooth wait stops soon after [cancelled] turns true. */
private fun <T> LinkedBlockingQueue<T>.pollUnless(seconds: Long, cancelled: () -> Boolean): T? {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
    while (!cancelled()) {
        val left = deadline - System.nanoTime()
        if (left <= 0) return null
        poll(minOf(left, TimeUnit.MILLISECONDS.toNanos(300)), TimeUnit.NANOSECONDS)?.let { return it }
    }
    return null
}

/**
 * Bluetooth Low Energy wake-up and pairing for Nikon smart-device mode.
 */
@SuppressLint("MissingPermission")
class CameraBluetooth(private val context: Context, private val store: BleWakeStore, private val log: (String) -> Unit) {
    companion object {
        private const val CONTROLLER_NAME = "ZfTransfer"
        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= 31) {
                arrayOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT")
            } else {
                arrayOf("android.permission.ACCESS_FINE_LOCATION")
            }

        fun hasPermissions(context: Context): Boolean =
            requiredPermissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun adapter(): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    /** Checks if the phone already holds a classic Bluetooth bond to a Nikon camera in system settings. */
    fun findExistingNikonBond(): BluetoothDevice? {
        if (!hasPermissions(context)) return null
        val adapter = adapter() ?: return null
        if (!adapter.isEnabled) return null
        return runCatching {
            adapter.bondedDevices?.firstOrNull { device ->
                BleWakeRules.looksLikeNikonCamera(device.name)
            }
        }.getOrNull()
    }

    /**
     * Pairs our controller identity with a camera that is in "配对（Bluetooth）" mode:
     * 1) Checks for an existing bond in system Bluetooth settings and asks the user to forget it first if present.
     * 2) Runs classic Bluetooth discovery (and LE scan in parallel) to find the camera's real BR/EDR address and LE device.
     * 3) Performs the 4-step GATT handshake + controller name write + device info reads.
     * 4) Initiates classic Bluetooth bonding (`createBond`) against the classic address and waits for confirmation.
     */
    fun pair(status: (String) -> Unit, cancelled: () -> Boolean = { false }): BlePairOutcome {
        if (!hasPermissions(context)) {
            return BlePairOutcome.Failed("需要附近设备权限", "请在应用权限里允许「附近设备」，再开始配对。", Recovery.APP_SETTINGS)
        }
        val adapter = adapter()
        if (adapter == null || !adapter.isEnabled) {
            return BlePairOutcome.Failed("手机蓝牙未打开", "请先打开手机蓝牙，再开始配对。", Recovery.BLUETOOTH_SETTINGS)
        }
        findExistingNikonBond()?.let { bonded ->
            val label = bonded.name ?: bonded.address
            log("BlePair: existing bond found ($label); asking user to forget first")
            return BlePairOutcome.ExistingBond(label)
        }

        val identityHex = store.getOrCreateIdentityHex()
        val identity = BleWakeRules.parseIdentityHex(identityHex)!!
        status("正在搜索相机…\n请让相机停在「配对（Bluetooth）」等待画面。")

        val found = discoverPairingCamera(adapter, status, cancelled)
        if (cancelled()) return BlePairOutcome.Cancelled
        if (found == null) return BlePairOutcome.Failed(
            "没有找到等待配对的相机",
            "请让相机停在「网络 → 连接至智能设备 → 配对（Bluetooth）」的等待画面，手机靠近相机后重试。手机后台开着 SnapBridge 时，先把它强行停止。"
        )

        val leDevice = found.first
        val classicAddress = found.second ?: store.classicAddress
        log("BlePair: connecting GATT to ${leDevice.address.takeLast(5)}, classic=${classicAddress?.takeLast(5) ?: "same"}")
        status("已找到相机，正在建立蓝牙连接…")

        val cameraName = try {
            GattSession(context, identity, CONTROLLER_NAME, wake = false, pairing = true, device = leDevice, log = log).run()
                .ifBlank { leDevice.name ?: "Nikon 相机" }
        } catch (e: Exception) {
            log("BlePair GATT failed: ${e.javaClass.simpleName}: ${e.message}")
            if (cancelled()) return BlePairOutcome.Cancelled
            return BlePairOutcome.Failed(
                "蓝牙连接中断",
                "相机没有完成蓝牙握手。请确认相机还停在「配对（Bluetooth）」画面，然后重试。"
            )
        }
        if (cancelled()) return BlePairOutcome.Cancelled

        val bondTarget = classicAddress?.let { runCatching { adapter.getRemoteDevice(it) }.getOrNull() } ?: leDevice
        status("请在手机和相机上都确认配对\n手机点「配对」，相机按 OK。")
        val bondedOk = performClassicBond(bondTarget, cancelled)
        if (cancelled()) return BlePairOutcome.Cancelled
        if (!bondedOk) {
            return BlePairOutcome.Failed(
                "配对没有完成",
                "手机或相机上的配对确认没有通过。如果手机蓝牙列表里还有这台相机的旧记录，先在蓝牙设置里忽略它，再重新配对。",
                Recovery.BLUETOOTH_SETTINGS
            )
        }

        store.markPaired(cameraName, bondTarget.address)
        log("BlePair: paired camera=$cameraName classic=${bondTarget.address.takeLast(5)}")
        return BlePairOutcome.Paired(cameraName)
    }

    /**
     * Searches for a camera in pairing mode using both LE scanning (for the GATT endpoint) and classic Bluetooth
     * discovery (to learn the camera's real BR/EDR MAC address, since in pairing mode LE uses a temporary address).
     */
    private fun discoverPairingCamera(adapter: BluetoothAdapter, status: (String) -> Unit, cancelled: () -> Boolean): Pair<BluetoothDevice, String?>? {
        val leFound = LinkedBlockingQueue<BluetoothDevice>()
        val classicFound = LinkedBlockingQueue<String>()

        val classicReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == BluetoothDevice.ACTION_FOUND) {
                    val d = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    } ?: return
                    val name = d.name
                    if (BleWakeRules.looksLikeNikonCamera(name) && d.type != BluetoothDevice.DEVICE_TYPE_LE) {
                        log("BlePair classic found: name=$name type=${d.type} addr=…${d.address.takeLast(5)}")
                        classicFound.offer(d.address)
                    }
                }
            }
        }
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val rec = r.scanRecord ?: return
                val m = rec.getManufacturerSpecificData(BleWakeRules.NIKON_MANUFACTURER_ID)
                val name = rec.deviceName ?: r.device.name
                val nikonService = rec.serviceUuids?.any { it.uuid.toString().startsWith("0000de00", ignoreCase = true) } == true
                if (BleWakeRules.looksLikeNikonCamera(name, m != null, nikonService) && r.rssi > -85) {
                    log("BlePair LE found: name=$name rssi=${r.rssi} nikonSvc=$nikonService addr=…${r.device.address.takeLast(5)}")
                    leFound.offer(r.device)
                }
            }
        }

        val scanner = adapter.bluetoothLeScanner ?: return null
        context.registerReceiver(classicReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND), Context.RECEIVER_EXPORTED)
        try {
            runCatching {
                if (adapter.isDiscovering) adapter.cancelDiscovery()
                adapter.startDiscovery()
            }
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            val leDevice = leFound.pollUnless(35, cancelled) ?: return null
            var classicAddr = classicFound.poll()
            if (classicAddr == null) {
                status("已发现相机，正在读取相机信息…")
                classicAddr = classicFound.pollUnless(6, cancelled)
            }
            return leDevice to classicAddr
        } finally {
            runCatching { scanner.stopScan(scanCallback) }
            runCatching { if (adapter.isDiscovering) adapter.cancelDiscovery() }
            runCatching { context.unregisterReceiver(classicReceiver) }
        }
    }

    private fun performClassicBond(target: BluetoothDevice, cancelled: () -> Boolean): Boolean {
        if (target.bondState == BluetoothDevice.BOND_BONDED) {
            log("BlePair: already bonded with …${target.address.takeLast(5)}")
            return true
        }
        val outcomes = LinkedBlockingQueue<Int>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val dev = if (Build.VERSION.SDK_INT >= 33) {
                    i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (dev != null && !dev.address.equals(target.address, ignoreCase = true)) return
                val state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                log("BlePair bond state -> $state")
                if (state == BluetoothDevice.BOND_BONDED || state == BluetoothDevice.BOND_NONE) {
                    outcomes.offer(state)
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED)
        return try {
            val started = target.createBond()
            log("BlePair createBond(…${target.address.takeLast(5)}) -> $started")
            if (!started) return target.bondState == BluetoothDevice.BOND_BONDED
            val finalState = outcomes.pollUnless(60, cancelled)
            finalState == BluetoothDevice.BOND_BONDED
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /**
     * Wakes the camera's Wi-Fi hotspot over BLE using our paired identity:
     * scans for the camera's advertisement carrying the first 4 bytes of our identity, runs the 4-step handshake,
     * reads 0x2004, and writes 0x01 to 0x2005.
     */
    fun wakeHotspot(status: (String) -> Unit, cancelled: () -> Boolean = { false }): BleWakeOutcome {
        val identity = store.identityBytes
            ?: return BleWakeOutcome.Failed(UserIssue("还没有配对蓝牙", "请先在「更多 → 蓝牙唤醒相机」里配对，或在相机上手动开启 Wi-Fi。", Recovery.NONE))
        if (!hasPermissions(context)) {
            return BleWakeOutcome.Failed(UserIssue("需要附近设备权限", "蓝牙唤醒相机需要「附近设备」权限。也可以在相机上手动开启 Wi-Fi。", Recovery.APP_SETTINGS))
        }
        val adapter = adapter()
        if (adapter == null || !adapter.isEnabled) {
            return BleWakeOutcome.Failed(UserIssue("手机蓝牙未打开", "打开蓝牙后再点「连接相机」，应用会自动唤醒相机 Wi-Fi。也可以在相机上手动开启 Wi-Fi。", Recovery.BLUETOOTH_SETTINGS))
        }

        status("正在通过蓝牙寻找相机…")
        val camera = findPairedCameraAdvertising(adapter, identity, cancelled)
        if (cancelled()) return BleWakeOutcome.Cancelled
        if (camera == null) return BleWakeOutcome.Failed(
            UserIssue(
                "蓝牙没找到相机",
                "请确认相机已开机、没有进入休眠（半按快门可唤醒），并靠近手机。\n" +
                    "手机后台开着 SnapBridge 时，它会占用相机蓝牙，先把它强行停止。\n" +
                    "也可以在相机上手动开启 Wi-Fi。",
                Recovery.WIFI_SETTINGS
            )
        )

        status("已找到相机，正在开启相机 Wi-Fi…")
        return try {
            val name = GattSession(context, identity, "Android_ZfTransfer", wake = true, pairing = false, device = camera, log = log, cancelled = cancelled).run()
            if (cancelled()) return BleWakeOutcome.Cancelled
            if (name.isNotBlank() && store.cameraName.isNullOrBlank()) {
                store.markPaired(name, null)
            }
            BleWakeOutcome.Woken
        } catch (e: GattDisconnectedException) {
            log("BleWake disconnected (status=${e.status}): ${e.message}")
            BleWakeOutcome.Failed(
                UserIssue(
                    "相机断开了蓝牙",
                    "相机停在 Wi-Fi 等待画面时会拒绝唤醒，请在相机上按 MENU 退出后重试。\n" +
                        "如果在 SnapBridge 里重新配对过，相机会忘记本应用，需要在「更多 → 蓝牙唤醒相机」里重新配对。",
                    Recovery.WIFI_SETTINGS
                )
            )
        } catch (e: Exception) {
            log("BleWake failed: ${e.javaClass.simpleName}: ${e.message}")
            BleWakeOutcome.Failed(
                UserIssue(
                    "没能唤醒相机 Wi-Fi",
                    "蓝牙连接中途断开。请确认 SnapBridge 没在后台运行、相机没停在等待画面，然后重试；也可以在相机上手动开启 Wi-Fi。",
                    Recovery.WIFI_SETTINGS
                )
            )
        }
    }

    private fun findPairedCameraAdvertising(adapter: BluetoothAdapter, identity: ByteArray, cancelled: () -> Boolean): BluetoothDevice? {
        val scanner = adapter.bluetoothLeScanner ?: return null
        val found = LinkedBlockingQueue<BluetoothDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val m = r.scanRecord?.getManufacturerSpecificData(BleWakeRules.NIKON_MANUFACTURER_ID) ?: return
                if (BleWakeRules.matchesIdentityPrefix(m, identity)) {
                    log("BleWake scan matched camera rssi=${r.rssi}")
                    found.offer(r.device)
                }
            }
            override fun onScanFailed(code: Int) {
                log("BleWake scan failed code=$code")
            }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        return try {
            found.pollUnless(12, cancelled)
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    /**
     * Scans nearby Wi-Fi networks for up to [timeoutSeconds] to find the hotspot opened by the wake command.
     */
    fun scanForWokenHotspot(savedCameraNames: Set<String>, timeoutSeconds: Int = 12, cancelled: () -> Boolean = { false }): String? {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
        while (System.nanoTime() < deadline && !cancelled()) {
            runCatching { @Suppress("DEPRECATION") wm.startScan() }
            val results = runCatching {
                wm.scanResults.orEmpty().mapNotNull { r ->
                    @Suppress("DEPRECATION")
                    val ssid = r.SSID?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    ssid to r.level
                }
            }.getOrDefault(emptyList())
            val matched = BleWakeRules.matchHotspotSsid(results, savedCameraNames)
            if (matched != null) {
                log("BleWake matched Wi-Fi scan SSID: $matched")
                return matched
            }
            Thread.sleep(1500)
        }
        return null
    }
}

private class GattDisconnectedException(val status: Int, message: String) : Exception(message)

@SuppressLint("MissingPermission")
private class GattSession(
    private val context: Context,
    private val identity: ByteArray,
    private val controllerName: String,
    private val wake: Boolean,
    private val pairing: Boolean,
    private val device: BluetoothDevice,
    private val log: (String) -> Unit,
    /** Checked before the wake command, so a connection the user stopped mid-handshake does not open the hotspot. */
    private val cancelled: () -> Boolean = { false },
) {
    private fun uuid(short: String) = UUID.fromString("0000$short-3dd4-4255-8d62-6dc7b9bd5561")
    private val service = uuid("de00")
    private val events = LinkedBlockingQueue<Pair<String, Any?>>()
    private var gatt: BluetoothGatt? = null
    private val early = ArrayDeque<Any?>()
    private fun hex(b: ByteArray?) = b?.joinToString("") { "%02x".format(it) } ?: "-"

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            events.add("conn" to (status to newState))
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            events.add("mtu" to mtu)
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            events.add("services" to status)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            events.add("descw" to status)
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            events.add("write" to status)
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            events.add("read" to (status to value))
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            events.add("changed" to (c.uuid to value))
        }
    }

    private fun await(kind: String, seconds: Long = 8): Any? {
        if (kind == "changed" && early.isNotEmpty()) return early.removeFirst()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (true) {
            val left = deadline - System.nanoTime()
            if (left <= 0) error("等待蓝牙响应超时 ($kind)")
            val e = events.poll(left, TimeUnit.NANOSECONDS) ?: error("等待蓝牙响应超时 ($kind)")
            if (e.first == kind) return e.second
            if (e.first == "changed") {
                early.addLast(e.second)
                continue
            }
            if (e.first == "conn") {
                val (st, state) = e.second as Pair<*, *>
                if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    throw GattDisconnectedException(st as Int, "蓝牙连接已断开 (status=$st)")
                }
            }
        }
    }

    private fun chr(short: String) =
        gatt!!.getService(service)?.getCharacteristic(uuid(short)) ?: error("相机未提供特征值 $short")

    private fun write(short: String, value: ByteArray) {
        check(gatt!!.writeCharacteristic(chr(short), value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS) {
            "蓝牙写入 $short 被拒绝"
        }
        val status = await("write") as Int
        check(status == 0) { "蓝牙写入 $short 失败 (status=$status)" }
    }

    private fun read(short: String): ByteArray {
        check(gatt!!.readCharacteristic(chr(short))) { "蓝牙读取 $short 被拒绝" }
        val (status, value) = await("read") as Pair<*, *>
        check(status == 0) { "蓝牙读取 $short 失败 (status=$status)" }
        return value as ByteArray
    }

    private fun subscribe(short: String, indicate: Boolean) {
        val c = chr(short)
        gatt!!.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
        val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        check(gatt!!.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS) { "订阅 $short 被拒绝" }
        check(await("descw") as Int == 0) { "订阅 $short 失败" }
    }

    private fun nextIndication(short: String): ByteArray {
        while (true) {
            val (u, v) = await("changed", 8) as Pair<*, *>
            if ((u as UUID) == uuid(short)) return v as ByteArray
        }
    }

    /** Returns the camera's reported name if read, or empty string. */
    fun run(): String {
        val started = System.nanoTime()
        fun elapsedMs() = "${(System.nanoTime() - started) / 1_000_000} ms"
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            val (st, state) = await("conn", 15) as Pair<*, *>
            if (state != BluetoothProfile.STATE_CONNECTED) {
                throw GattDisconnectedException(st as Int, "无法建立蓝牙连接 (status=$st)")
            }
            gatt!!.requestMtu(517)
            runCatching { await("mtu", 4) }
            check(gatt!!.discoverServices()) { "无法发现蓝牙服务" }
            check(await("services", 10) as Int == 0) { "蓝牙服务发现失败" }
            check(gatt!!.getService(service) != null) { "未找到尼康蓝牙服务" }

            subscribe("2008", false)
            subscribe("200a", false)
            subscribe("2000", true)
            subscribe("2020", true)
            subscribe("2021", true)

            val ts = ByteArray(8).also { SecureRandom().nextBytes(it) }
            val s1 = NikonHandshake.stage1(ts, identity)
            write("2000", s1)
            val s2 = nextIndication("2000")
            val s3 = NikonHandshake.stage3(s1, s2) ?: error("相机未接受蓝牙握手凭证")
            write("2000", s3)
            val s4 = nextIndication("2000")
            check(s4.isNotEmpty() && s4[0].toInt() == 4) { "蓝牙握手第 4 步异常" }

            write("2002", controllerName.toByteArray(Charsets.US_ASCII).copyOf(32))
            if (pairing) {
                val cameraName = runCatching { String(read("2003"), Charsets.UTF_8).trimEnd('\u0000').trim() }.getOrDefault("")
                read("2009")
                for (short in listOf("2a24", "2a26")) {
                    val u = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
                    val c = gatt!!.services.firstNotNullOfOrNull { it.getCharacteristic(u) } ?: continue
                    if (gatt!!.readCharacteristic(c)) runCatching { await("read") }
                }
                read("2009")
                log("BleGatt pairing handshake done in ${elapsedMs()}, cameraName=$cameraName")
                return cameraName
            }

            val cameraName = runCatching { String(read("2003"), Charsets.UTF_8).trimEnd('\u0000').trim() }.getOrDefault("")
            read("2009")
            read("2008")
            write("2008", byteArrayOf(0, 0))
            read("2001")
            val blob = read("2004")
            log("BleGatt read 0x2004 (${blob.size} bytes) at ${elapsedMs()}")
            if (wake) {
                if (cancelled()) { log("BleGatt wake skipped: stopped by user"); return cameraName }
                write("2005", byteArrayOf(1))
                log("BleGatt wake command (0x2005=0x01) sent at ${elapsedMs()}")
                Thread.sleep(1500)
            }
            return cameraName
        } finally {
            runCatching { gatt?.disconnect(); gatt?.close() }
        }
    }
}

