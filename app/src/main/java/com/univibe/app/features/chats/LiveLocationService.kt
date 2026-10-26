package com.univibe.app.features.chats

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.univibe.app.R
import com.univibe.app.app.AppContainer
import com.univibe.app.ui.components.LocationFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Keeps one live location share fresh. It runs only while a share is active, shows a notification with a Stop button
 * the whole time, and ends by itself at the chosen time. It needs no background-location permission: Android lets a
 * foreground service of type "location" keep using the permission the person gave while the app was open.
 */
class LiveLocationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var manager: LocationManager? = null
    private var messageId: String? = null
    private var lastSentAt = 0L
    private var finishing = false

    private val listener = LocationListener { location -> push(location) }
    private val expire = Runnable { finish() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { finish(); return START_NOT_STICKY }
        val id = intent?.getStringExtra(EXTRA_MESSAGE_ID)
        val endsAt = intent?.getLongExtra(EXTRA_ENDS_AT, 0L) ?: 0L
        if (id == null || endsAt <= System.currentTimeMillis() || !LocationFix.hasPermission(this)) { stopSelf(); return START_NOT_STICKY }
        messageId = id
        startForegroundNow(endsAt)
        handler.removeCallbacks(expire)
        handler.postDelayed(expire, endsAt - System.currentTimeMillis())
        startUpdates()
        return START_NOT_STICKY
    }

    private fun startForegroundNow(endsAt: Long) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Live location", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while you share your live location in a chat."
                setShowBadge(false)
            })
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LiveLocationService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val until = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(endsAt))
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_univibe)
            .setContentTitle("Sharing your live location")
            .setContentText("Until $until")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, "Stop sharing", stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return finish()
        manager = lm
        val fine = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        try {
            var any = false
            if (fine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVAL_MS, MIN_DISTANCE_M, listener, Looper.getMainLooper()); any = true }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, INTERVAL_MS, MIN_DISTANCE_M, listener, Looper.getMainLooper()); any = true }
            if (!any) finish()
        } catch (_: SecurityException) { finish() }
    }

    /** At most one upload every few seconds, whatever the phone reports. */
    private fun push(location: Location) {
        val id = messageId ?: return
        val now = System.currentTimeMillis()
        if (now - lastSentAt < MIN_UPLOAD_GAP_MS) return
        lastSentAt = now
        scope.launch {
            try {
                if (!AppContainer.messageRepository.updateLiveLocation(id, location.latitude, location.longitude)) handler.post { finish(tellServer = false) }
            } catch (_: Exception) {
                // No signal for a moment: the next fix tries again.
            }
        }
    }

    private fun finish(tellServer: Boolean = true) {
        if (finishing) return
        finishing = true
        handler.removeCallbacks(expire)
        runCatching { manager?.removeUpdates(listener) }
        val id = messageId
        if (tellServer && id != null) {
            scope.launch {
                runCatching { AppContainer.messageRepository.stopLiveLocation(id) }
                handler.post { stopForegroundAndSelf() }
            }
        } else stopForegroundAndSelf()
    }

    private fun stopForegroundAndSelf() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(expire)
        runCatching { manager?.removeUpdates(listener) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "live_location"
        private const val NOTIFICATION_ID = 4107
        private const val ACTION_STOP = "com.univibe.app.STOP_LIVE_LOCATION"
        private const val EXTRA_MESSAGE_ID = "messageId"
        private const val EXTRA_ENDS_AT = "endsAt"
        private const val INTERVAL_MS = 10_000L
        private const val MIN_DISTANCE_M = 10f
        private const val MIN_UPLOAD_GAP_MS = 8_000L

        fun start(context: Context, messageId: String, endsAtMillis: Long) {
            ContextCompat.startForegroundService(
                context, Intent(context, LiveLocationService::class.java).putExtra(EXTRA_MESSAGE_ID, messageId).putExtra(EXTRA_ENDS_AT, endsAtMillis))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveLocationService::class.java))
        }
    }
}
