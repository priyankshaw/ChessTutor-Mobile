package com.chesstutor.nativeapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class AnalysisService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("analysis", "Game analysis", NotificationManager.IMPORTANCE_LOW))
        @Suppress("WakelockTimeout")
        val lock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChessTutor:analysis")
        lock.acquire(30 * 60 * 1000L)
        wakeLock = lock
        startForeground(41, notification("Finding your best moves…"))
    }
    private fun notification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 1, Intent(this, AnalysisService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "analysis").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ChessTutor analysis").setContentText(text).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true).addAction(0, "Cancel", cancel).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") {
            (application as TutorApplication).model.cancelAnalysis()
            stopSelf()
        } else {
            wakeLock?.let { if (it.isHeld) it.release(); it.acquire(30 * 60 * 1000L) }
            val text = intent?.getStringExtra("text") ?: "Finding your best moves…"
            getSystemService(NotificationManager::class.java).notify(41, notification(text))
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
