package com.jijilabsdigital.swell

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps Tor and the onion service running so messages arrive while the app is closed.
 * Type `remoteMessaging` (not `dataSync`, which Android 15 limits to 6 h a day).
 */
class SwellService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Swell.init(this)
        ServiceCompat.startForeground(
            this,
            Notifications.ID_CONNECTED,
            Notifications.connected(this),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0,
        )
        Swell.connect()
        return START_STICKY
    }

    companion object {
        fun start(context: Context) {
            if (!Swell.stayConnected) { Swell.connect(); return }
            try {
                ContextCompat.startForegroundService(context, Intent(context, SwellService::class.java))
            } catch (e: Exception) {
                // Android refuses a foreground service while the app is not visible (e.g. phone locked).
                // Connect inside the app process now; the service starts the next time the app is opened.
                android.util.Log.w("Swell", "foreground service not allowed now: ${e.javaClass.simpleName}")
                Swell.connect()
            }
        }

        fun stop(context: Context) = context.stopService(Intent(context, SwellService::class.java))
    }
}

object Notifications {
    const val ID_CONNECTED = 1
    private const val ID_MESSAGE = 2
    private const val CH_CONNECTED = "connected"
    private const val CH_MESSAGES = "messages"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_CONNECTED, context.getString(R.string.channel_connected), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    fun connected(context: Context): Notification =
        NotificationCompat.Builder(context, CH_CONNECTED)
            .setSmallIcon(R.drawable.ic_stat_swell)
            .setContentTitle(context.getString(R.string.notif_connected))
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET) // not on the lock screen (audit L-09)
            .build()

    /** Never shows the sender or the text: only "New message". */
    fun newMessage(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_swell)
            .setContentTitle(context.getString(R.string.notif_new_message))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        NotificationManagerCompat.from(context).notify(ID_MESSAGE, n)
    }
}
