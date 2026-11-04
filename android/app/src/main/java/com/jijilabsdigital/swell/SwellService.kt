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
    
    /** The messages channel id depends on the chosen sound (Android fixes a channel's sound once it exists). */
    private fun messagesChannel(): String = "messages-" + (Swell.notifSound ?: "default").hashCode().toUInt().toString(16)

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_CONNECTED, context.getString(R.string.channel_connected), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            },
        )
        val id = messagesChannel()
        nm.notificationChannels.filter { it.id.startsWith("messages") && it.id != id }.forEach { nm.deleteNotificationChannel(it.id) }
        nm.createNotificationChannel(
            NotificationChannel(id, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                val snd = Swell.notifSound
                val attrs = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT).build()
                when {
                    snd == null -> {}                       // system default
                    snd.isEmpty() -> setSound(null, null)   // silent
                    else -> setSound(android.net.Uri.parse(snd), attrs)
                }
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

    enum class Kind { PLAIN, VIEW_ONCE, PRIVATE }

    /** Removes the message notification (a view-once photo was opened, a private chat was wiped). */
    fun cancelMessages(context: Context) { NotificationManagerCompat.from(context).cancel(ID_MESSAGE) }

    /**
     * What the notification shows follows Settings: nothing ("New message"), the name only, or name and text.
     * View-once and private messages never show a name or content, whatever the setting says.
     */
    fun newMessage(context: Context, kind: Kind = Kind.PLAIN, name: String? = null, text: String? = null) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val generic = when (kind) { Kind.PLAIN -> R.string.notif_new_message; Kind.VIEW_ONCE -> R.string.notif_view_once; Kind.PRIVATE -> R.string.notif_private }
        val level = if (kind == Kind.PLAIN) Swell.notifContent else 0
        val title = if (level >= 1 && !name.isNullOrBlank()) name else context.getString(generic)
        val body = if (level >= 1 && !name.isNullOrBlank()) (if (level >= 2 && !text.isNullOrBlank()) text.take(120) else context.getString(generic)) else null
        val n = NotificationCompat.Builder(context, messagesChannel())
            .setSmallIcon(R.drawable.ic_stat_swell)
            .setContentTitle(title)
            .apply { if (body != null) setContentText(body) }
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        NotificationManagerCompat.from(context).notify(ID_MESSAGE, n)
    }

    /** A short sound for a message that arrives while the app is open. */
    fun playInApp(context: Context) {
        val snd = Swell.notifSound
        if (snd != null && snd.isEmpty()) return
        runCatching {
            val uri = snd?.let { android.net.Uri.parse(it) } ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            android.media.RingtoneManager.getRingtone(context, uri)?.play()
        }
    }
}
