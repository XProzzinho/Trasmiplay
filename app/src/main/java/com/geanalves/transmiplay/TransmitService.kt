package com.geanalves.transmiplay

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

/** Mantém o Transmiplay funcionando mesmo com a tela desligada ou em outro app. */
class TransmitService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var controls: MediaControls? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Hub.start(applicationContext)
        val n = buildNotification(this, "Pronto pra mandar pras TVs")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else startForeground(1, n)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "transmiplay:hub").apply { acquire() }
        controls = try { MediaControls(this) } catch (_: Exception) { null }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QUIT) {
            CaptureService.stop(this)
            Hub.shutdown()
            stopForeground(true)
            stopSelf()
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        controls?.handle(intent?.action)
        return START_STICKY
    }

    override fun onDestroy() {
        controls?.release()
        try { wake?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "transmiplay"
        const val ACTION_QUIT = "quit"

        fun start(ctx: Context) {
            val i = Intent(ctx, TransmitService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun buildNotification(ctx: Context, text: String): Notification {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Transmiplay", NotificationManager.IMPORTANCE_LOW))
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), flags)
            val quit = PendingIntent.getService(
                ctx, 1, Intent(ctx, TransmitService::class.java).setAction(ACTION_QUIT), flags,
            )
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else Notification.Builder(ctx)
            @Suppress("DEPRECATION")
            return b.setContentTitle("Transmiplay")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Fechar", quit).build())
                .build()
        }
    }
}
