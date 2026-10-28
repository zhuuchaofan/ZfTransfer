package com.xiaozhu.zftransfer

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.*

/** Keeps the network request and PTP session alive independently of the gallery screen. */
class CameraConnectionService : Service() {
    private val runtime get() = (application as TransferApplication).runtime
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var cpu: PowerManager.WakeLock
    private lateinit var wifi: WifiManager.WifiLock
    private var lastUpdate = 0L
    private val listener: () -> Unit = { refresh() }
    private val renew = object : Runnable {
        override fun run() { refresh(force = true); handler.postDelayed(this, 60000) }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "相机连接", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持相机连接与显示照片传输进度"; setShowBadge(false)
            })
        cpu = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZfTransfer:CameraConnection").apply { setReferenceCounted(false) }
        wifi = applicationContext.getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ZfTransfer:CameraWifi").apply { setReferenceCounted(false) }
        startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        runtime.serviceStarted()
        runtime.attach(listener)
        handler.post(renew)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { runtime.stop(); stopSelf(); return START_NOT_STICKY }
        refresh(force = true)
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, CameraConnectionService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = when {
            runtime.userStopped -> "相机连接已断开"
            runtime.taskState.running -> "正在与相机通信"
            runtime.connectionReady -> "${runtime.cameraName} · 保持连接中"
            else -> "等待相机连接"
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(resources.getIdentifier("ic_connection", "drawable", packageName))
            .setContentTitle(title).setContentText(runtime.message.ifEmpty { "返回、切换应用或熄屏后继续保持；点击断开可停止。" })
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "断开", stop).build())
            .apply { if (runtime.taskState.running) setProgress(100, runtime.progress, runtime.progress == 0) }
            .build()
    }
    private fun refresh(force: Boolean = false) {
        val needsRadio = runtime.lifetime.desired && (runtime.client != null || runtime.busy.get() || runtime.connectionPending)
        if (needsRadio) {
            // Renew a bounded lease; a stuck service cannot retain a wake lock indefinitely.
            if (force || !cpu.isHeld) cpu.acquire(10 * 60 * 1000L)
            if (!wifi.isHeld) wifi.acquire()
        } else {
            if (cpu.isHeld) cpu.release()
            if (wifi.isHeld) wifi.release()
        }
        val now = SystemClock.elapsedRealtime()
        if (force || now - lastUpdate >= 1000) {
            lastUpdate = now
            getSystemService(NotificationManager::class.java).notify(ID, notification())
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runtime.detach(listener)
        runtime.serviceActive = false
        runtime.stop()
        if (cpu.isHeld) cpu.release()
        if (wifi.isHeld) wifi.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        const val STOP = "com.xiaozhu.zftransfer.DISCONNECT"
        private const val CHANNEL = "camera_connection"
        private const val ID = 40
    }
}
