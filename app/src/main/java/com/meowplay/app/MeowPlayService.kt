package com.meowplay.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.weblink.sdk.WebLinkTouchPoint

class MeowPlayService : Service(), MeowPlayBridge.BridgeListener {

    companion object {
        private const val CHANNEL_ID = "meowplay_channel"
        private const val NOTIFICATION_ID = 2026
        const val ACTION_START = "com.meowplay.app.ACTION_START"
        const val ACTION_STOP = "com.meowplay.app.ACTION_STOP"

        fun start(context: Context) {
            val intent = Intent(context, MeowPlayService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MeowPlayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var bridge: MeowPlayBridge

    private var stereoInfo = "Stereo: Disconnected"
    private var carPlayInfo = "CarPlay: Waiting"

    override fun onCreate() {
        super.onCreate()
        bridge = MeowPlayBridge.getInstance(this)
        bridge.addListener(this)

        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            bridge.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        bridge.start()
        return START_STICKY
    }

    override fun onDestroy() {
        bridge.removeListener(this)
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MeowPlay Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains connection between Apple CarPlay and Pioneer WebLink stereo"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MeowPlayActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MeowPlay 🐱")
            .setContentText("$stereoInfo | $carPlayInfo")
            .setSmallIcon(R.drawable.ic_carplay)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeowPlay::WakeLock")?.apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (ignored: Exception) {}
        wakeLock = null
    }

    // ==========================================
    // MeowPlayBridge.BridgeListener
    // ==========================================

    override fun onStereoStatusChanged(model: String, width: Int, height: Int, connected: Boolean) {
        stereoInfo = if (connected) "Stereo: $model (${width}x${height})" else "Stereo: Disconnected"
        updateNotification()
    }

    override fun onCarPlayStatusChanged(status: String) {
        carPlayInfo = "CarPlay: $status"
        updateNotification()
    }

    override fun onActiveSessionChanged(active: Boolean, deviceName: String?) {
        carPlayInfo = if (active) "CarPlay: Connected (${deviceName ?: "iPhone"})" else "CarPlay: Waiting"
        updateNotification()
    }

    override fun onTouchReported(point: WebLinkTouchPoint, normX: Double, normY: Double) {}
    override fun onLog(message: String) {}
}
