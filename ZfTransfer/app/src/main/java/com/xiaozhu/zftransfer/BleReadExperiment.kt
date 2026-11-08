package com.xiaozhu.zftransfer

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Experiment only, local branch, never released: connect to the camera over Bluetooth, run the 4-step handshake
 * and read 0x2004, logging every value so the session key can be worked out offline. Writing 0x01 to 0x2005
 * (switch the camera's Wi-Fi on) only happens with the extra wake=true. Started from adb with a broadcast; the
 * controller identity is passed in and never stored.
 */
class BleReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val idPrefs = context.applicationContext.getSharedPreferences("ble_experiment", Context.MODE_PRIVATE)
        if (intent.getBooleanExtra("remember", false)) {
            // The camera's classic address, taken from the phone's existing bond before the user forgets it.
            @SuppressLint("MissingPermission")
            val cam = context.getSystemService(BluetoothManager::class.java).adapter.bondedDevices.firstOrNull { it.name?.startsWith("Z_") == true }
            if (cam != null) idPrefs.edit().putString("classic", cam.address).commit()
            bleLog("remembered classic address: ${cam?.address?.takeLast(5) ?: "none"} (${cam?.name})")
            return
        }
        intent.getStringExtra("classic")?.let { idPrefs.edit().putString("classic", it).commit() }
        if (intent.getBooleanExtra("pair", false)) {
            // Our own controller identity: 8 random bytes made once and kept, like the official app's DeviceID.
            val own = idPrefs.getString("identity", null) ?: ByteArray(8).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }.also { idPrefs.edit().putString("identity", it).commit() }
            val pending = goAsync()
            Thread {
                try { BlePairing(context.applicationContext, own, intent.getStringExtra("name") ?: "ZfTransfer").run() }
                catch (e: Throwable) { bleLog("PAIR FAILED ${e.javaClass.simpleName}: ${e.message}") }
                pending.finish()
            }.start()
            return
        }
        val identity = if (intent.getBooleanExtra("own", false)) idPrefs.getString("identity", null) ?: return
            else intent.getStringExtra("id") ?: return
        val name = intent.getStringExtra("name") ?: "Android_ZfTransfer"
        val wake = intent.getBooleanExtra("wake", false)
        val join = intent.getBooleanExtra("join", false)
        val ptpName = intent.getStringExtra("ptpname") ?: "Android Device"
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            // The app's own auto-connect would dial the hotspot with its usual name and make the camera drop it.
            val prefs = app.getSharedPreferences("transfers", Context.MODE_PRIVATE)
            val autoBefore = prefs.getBoolean("auto_connect", true)
            val runtime = (app as TransferApplication).runtime
            if (join) { prefs.edit().putBoolean("auto_connect", false).commit(); runtime.userStopped = true }
            try {
                BleReadExperiment(app, identity, name, wake).run()
                if (join) SnapBridgeJoin(app, ptpName).run()
            } catch (e: Throwable) { bleLog("FAILED ${e.javaClass.simpleName}: ${e.message}") }
            finally { if (join) prefs.edit().putBoolean("auto_connect", autoBefore).commit() }
            pending.finish()
        }.start()
    }
}

private fun bleLog(text: String) { android.util.Log.i("ZfBle", text) }

/**
 * Pairs our own identity with a camera in pairing mode, following the official app's first pairing seen in the
 * capture: GATT handshake with the new identity, our name on 0x2002, the camera's name/model/serial/firmware
 * reads, then a classic Bluetooth bond.
 */
@SuppressLint("MissingPermission")
class BlePairing(private val context: Context, identityHex: String, private val controllerName: String) {
    private val identity = identityHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    fun run() {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        bleLog("pairing start; own identity prefix=${identity.copyOfRange(0, 2).joinToString("") { "%02x".format(it) }}…")
        val found = LinkedBlockingQueue<BluetoothDevice>()
        val scan = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val rec = r.scanRecord ?: return
                val m = rec.getManufacturerSpecificData(0x0399)
                val name = rec.deviceName ?: r.device.name
                val nikonService = rec.serviceUuids?.any { it.uuid.toString().startsWith("0000de00") } == true
                val makers = (0 until (rec.manufacturerSpecificData?.size() ?: 0)).map { "%04x".format(rec.manufacturerSpecificData.keyAt(it)) }
                if (name == null && m == null && !nikonService) return
                bleLog("pair scan rssi=${r.rssi} name=$name makers=$makers nikonSvc=$nikonService services=${rec.serviceUuids} nikon=${m?.joinToString("") { "%02x".format(it) }?.take(8)}")
                val looksLikeCamera = m != null || nikonService || name?.startsWith("Z_") == true || name?.contains("NIKON", true) == true
                if (looksLikeCamera && r.rssi > -80) found.add(r.device)
            }
        }
        val scanner = adapter.bluetoothLeScanner!!
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan)
        val camera = try { found.poll(40, TimeUnit.SECONDS) ?: error("no camera advertising nearby in 40 s") } finally { scanner.stopScan(scan) }
        bleLog("pairing with camera; bond state before=${camera.bondState}")
        BleReadExperiment(context, identity.joinToString("") { "%02x".format(it) }, controllerName, wake = false, pairing = true, device = camera).run()
        // Classic bond, as the official app does right after the GATT part.
        val bonded = LinkedBlockingQueue<Int>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                bleLog("bond state -> $state"); if (state == BluetoothDevice.BOND_BONDED || state == BluetoothDevice.BOND_NONE) bonded.add(state)
            }
        }
        context.registerReceiver(receiver, android.content.IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED)
        try {
            // Bond over BR/EDR with the camera's real (classic) address; in pairing mode it advertises from a temporary one.
            val saved = context.getSharedPreferences("ble_experiment", Context.MODE_PRIVATE).getString("classic", null)
            val classic = adapter.getRemoteDevice(saved ?: camera.address)
            bleLog("bonding with ${if (saved != null) "classic" else "advertised"} address …${classic.address.takeLast(5)}")
            if (classic.bondState == BluetoothDevice.BOND_BONDED) bleLog("already bonded at the phone level; skipping createBond")
            else {
                bleLog("createBond -> ${classic.createBond()}")
                bleLog("bond result=${bonded.poll(60, TimeUnit.SECONDS) ?: "timeout"}")
            }
        } finally { runCatching { context.unregisterReceiver(receiver) } }
        bleLog("PAIRING DONE")
    }
}

/**
 * Joins the SnapBridge hotspot the wake command opened (saved camera name + "_SnapBridge", same password, read
 * from the app's own encrypted store) and tries PTP/IP with the official app's identity: GUID bytes 00 11 … FF
 * (what zfMode already sends) and the friendly name [ptpName].
 */
class SnapBridgeJoin(private val context: Context, private val ptpName: String) {
    fun run() {
        val saved = WifiProfileStore(context).load() ?: error("no saved camera Wi-Fi profile")
        val ssid = saved.ssid.removeSuffix("_SnapBridge") + "_SnapBridge"
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val result = LinkedBlockingQueue<Any>()
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { result.add(network) }
            override fun onUnavailable() { result.add("unavailable") }
            override fun onLost(network: android.net.Network) { bleLog("wifi lost") }
        }
        val specifier = android.net.wifi.WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(saved.password).build()
        val started = System.nanoTime()
        fun t() = "${(System.nanoTime() - started) / 1_000_000} ms"
        bleLog("requesting wifi $ssid")
        cm.requestNetwork(android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build(), callback, 60000)
        try {
            val network = result.poll(65, TimeUnit.SECONDS) as? android.net.Network ?: error("could not join $ssid")
            bleLog("JOINED $ssid at ${t()}")
            for (attempt in 1..8) {
                val c = io.github.noisetta.nikonpull.ptp.PtpIpClient(host = "192.168.1.1", clientName = ptpName,
                    socketFactory = network.socketFactory, zfMode = true, log = { bleLog("ptp: $it") })
                try {
                    c.connect()
                    val info = c.getDeviceInfo()
                    bleLog("PTP OK name=[$ptpName] model=${info.model} at ${t()}")
                    bleLog("HANDLES ${c.listObjectHandles().size} at ${t()}")
                    exercise(c, ::t)
                    return
                } catch (e: Exception) {
                    bleLog("PTP attempt $attempt failed: ${e.javaClass.simpleName} ${e.message?.take(100)}")
                    if (e is java.net.ConnectException || e.message?.contains("refused") == true) { Thread.sleep(2000); continue }
                    return
                } finally { runCatching { c.abort() } }
            }
        } finally { runCatching { cm.unregisterNetworkCallback(callback) }; bleLog("join done at ${t()}") }
    }

    /** What the gallery does on a normal hotspot: catalog with details, thumbnails, a clear preview, one full download. */
    private fun exercise(c: io.github.noisetta.nikonpull.ptp.PtpIpClient, t: () -> String) {
        val all = c.listObjectsByProperties()
        val photos = CameraCatalog.arrange(all)
        val jpegs = photos.filter { MediaFormat.of(it) == MediaFormat.JPEG }
        bleLog("CATALOG ${all.size} objects, ${photos.size} photos/videos, ${jpegs.size} JPEG, newest=${photos.firstOrNull()?.filename} at ${t()}")
        for (p in jpegs.take(3)) {
            val thumb = runCatching { c.getThumbnail(p.handle) }
            val bmp = thumb.getOrNull()?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
            bleLog("THUMB ${p.filename}: ${thumb.getOrNull()?.size ?: thumb.exceptionOrNull()?.message} bytes ${bmp?.width}x${bmp?.height} at ${t()}")
        }
        jpegs.firstOrNull()?.let { p ->
            val prev = runCatching { c.getNikonPreview(p.handle, true) }
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            prev.getOrNull()?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size, o) }
            bleLog("PREVIEW ${p.filename}: ${prev.getOrNull()?.size ?: prev.exceptionOrNull()?.message} bytes ${o.outWidth}x${o.outHeight} at ${t()}")
            // Full download to the app's cache only, so the test leaves no extra copy in the album.
            val file = java.io.File(context.cacheDir, "snapbridge_test.jpg")
            val started = System.nanoTime()
            runCatching { file.outputStream().buffered().use { out -> c.downloadObject(p.handle, out) } }
                .onFailure { bleLog("DOWNLOAD failed: ${it.javaClass.simpleName} ${it.message?.take(80)}") }
            val ms = (System.nanoTime() - started) / 1_000_000
            val head = file.takeIf { it.exists() }?.inputStream()?.use { s -> ByteArray(2).also { s.read(it) } }
            bleLog("DOWNLOAD ${p.filename}: ${file.length()} of ${p.size} bytes, match=${file.length() == p.size}, jpeg=${head?.get(0) == 0xFF.toByte() && head?.get(1) == 0xD8.toByte()}, ${ms} ms (${if (ms > 0) "%.1f".format(file.length() / 1048.576 / ms) else "-"} MB/s)")
            file.delete()
        }
        bleLog("EXERCISE DONE at ${t()}")
    }
}

@SuppressLint("MissingPermission")
class BleReadExperiment(private val context: Context, identityHex: String, private val controllerName: String, private val wake: Boolean,
    private val pairing: Boolean = false, private val device: BluetoothDevice? = null) {
    private val identity = identityHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun uuid(short: String) = UUID.fromString("0000$short-3dd4-4255-8d62-6dc7b9bd5561")
    private val service = uuid("de00")
    private val events = LinkedBlockingQueue<Pair<String, Any?>>()
    private var gatt: BluetoothGatt? = null
    private fun hex(b: ByteArray?) = b?.joinToString("") { "%02x".format(it) } ?: "-"

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) { events.add("conn" to (status to newState)) }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { events.add("mtu" to mtu) }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { events.add("services" to status) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { events.add("descw" to status) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { events.add("write" to status) }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) { events.add("read" to (status to value)) }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            bleLog("notify ${c.uuid.toString().substring(4, 8)} ${hex(value)}"); events.add("changed" to (c.uuid to value))
        }
    }
    /** Indications that arrive while waiting for something else (stage 4 can beat the stage 3 write callback). */
    private val early = ArrayDeque<Any?>()
    private fun await(kind: String, seconds: Long = 8): Any? {
        if (kind == "changed" && early.isNotEmpty()) return early.removeFirst()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (true) {
            val left = deadline - System.nanoTime()
            if (left <= 0) error("timeout waiting for $kind")
            val e = events.poll(left, TimeUnit.NANOSECONDS) ?: error("timeout waiting for $kind")
            if (e.first == kind) return e.second
            if (e.first == "changed") { early.addLast(e.second); continue }
            if (e.first == "conn" && (e.second as Pair<*, *>).second == BluetoothProfile.STATE_DISCONNECTED) error("disconnected while waiting for $kind")
        }
    }
    private fun chr(short: String) = gatt!!.getService(service).getCharacteristic(uuid(short)) ?: error("no characteristic $short")
    private fun write(short: String, value: ByteArray) {
        check(gatt!!.writeCharacteristic(chr(short), value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS) { "write $short refused" }
        val status = await("write") as Int; bleLog("write $short ${hex(value)} -> status=$status"); check(status == 0) { "write $short status=$status" }
    }
    private fun read(short: String): ByteArray {
        check(gatt!!.readCharacteristic(chr(short))) { "read $short refused" }
        val (status, value) = await("read") as Pair<*, *>; bleLog("read $short -> status=$status ${hex(value as ByteArray?)}")
        check(status == 0) { "read $short status=$status" }; return value!!
    }
    private fun subscribe(short: String, indicate: Boolean) {
        val c = chr(short); gatt!!.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
        val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        check(gatt!!.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS) { "descriptor $short refused" }
        check(await("descw") as Int == 0) { "descriptor $short failed" }
    }
    private fun nextIndication(short: String): ByteArray {
        while (true) { val (u, v) = await("changed", 8) as Pair<*, *>; if ((u as UUID) == uuid(short)) return v as ByteArray }
    }
    private fun findCamera(): BluetoothDevice {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val found = LinkedBlockingQueue<BluetoothDevice>()
        val scan = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val m = r.scanRecord?.getManufacturerSpecificData(0x0399) ?: return
                bleLog("scan rssi=${r.rssi} nikon=${hex(m.copyOfRange(0, minOf(4, m.size)))}…")
                if (m.size >= 4 && m.copyOfRange(0, 4).contentEquals(identity.copyOfRange(0, 4))) found.add(r.device)
            }
            override fun onScanFailed(code: Int) { bleLog("scan failed $code") }
        }
        val scanner = adapter.bluetoothLeScanner!!
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan)
        try { return found.poll(30, TimeUnit.SECONDS) ?: error("camera not seen advertising in 30 s") } finally { scanner.stopScan(scan) }
    }

    fun run() {
        val started = System.nanoTime()
        fun t() = "${(System.nanoTime() - started) / 1_000_000} ms"
        bleLog("start; wake=$wake")
        val camera = device ?: findCamera(); bleLog("camera found at ${t()}")
        try {
            gatt = camera.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            val (st, state) = await("conn", 15) as Pair<*, *>; check(state == BluetoothProfile.STATE_CONNECTED) { "connect status=$st state=$state" }
            // The MTU exchange is optional: long reads still work in pieces when the camera does not answer.
            gatt!!.requestMtu(517); bleLog("mtu=${runCatching { await("mtu", 5) }.getOrElse { "no answer" }}")
            check(gatt!!.discoverServices()); check(await("services", 10) as Int == 0)
            check(gatt!!.getService(service) != null) { "camera has no Nikon service" }
            subscribe("2008", false); subscribe("200a", false); subscribe("2000", true); subscribe("2020", true); subscribe("2021", true)
            val ts = ByteArray(8).also { SecureRandom().nextBytes(it) }
            val s1 = NikonHandshake.stage1(ts, identity)
            write("2000", s1); bleLog("STAGE1 ${hex(s1)}")
            val s2 = nextIndication("2000"); bleLog("STAGE2 ${hex(s2)}")
            val s3 = NikonHandshake.stage3(s1, s2) ?: error("camera's stage 2 did not verify")
            write("2000", s3); bleLog("STAGE3 ${hex(s3)}")
            val s4 = nextIndication("2000"); bleLog("STAGE4 ${hex(s4)} at ${t()}")
            check(s4[0].toInt() == 4) { "unexpected stage 4" }
            write("2002", controllerName.toByteArray(Charsets.US_ASCII).copyOf(32))
            if (pairing) {
                // The official app's first pairing reads the camera's name, then device information.
                bleLog("camera name=${String(read("2003")).trimEnd('\u0000')}")
                read("2009")
                for (short in listOf("2a24", "2a26")) {
                    val u = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
                    val c = gatt!!.services.firstNotNullOfOrNull { it.getCharacteristic(u) } ?: continue
                    check(gatt!!.readCharacteristic(c)); val (_, v) = await("read") as Pair<*, *>
                    bleLog("info $short=${String(v as ByteArray).trimEnd('\u0000')}")
                }
                read("2009")
                bleLog("PAIRING GATT PART OK at ${t()}")
                return
            }
            read("2009"); read("2008")
            write("2008", byteArrayOf(0, 0))
            read("2001")
            val blob = read("2004"); bleLog("C2004 ${hex(blob)} length=${blob.size}")
            val blob2 = read("2004"); bleLog("C2004B ${hex(blob2)} same=${blob.contentEquals(blob2)}")
            if (wake) { write("2005", byteArrayOf(1)); bleLog("WAKE COMMAND SENT at ${t()}"); Thread.sleep(3000) }
        } finally {
            runCatching { gatt?.disconnect(); gatt?.close() }; bleLog("done at ${t()}")
        }
    }
}
