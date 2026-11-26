package com.geanalves.transmiplay

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build

/**
 * Controles de fora do app: notificação com tocar/pausar/próxima, tela de bloqueio,
 * fone Bluetooth e os botões de volume do celular mexendo no volume das TVs.
 */
class MediaControls(private val svc: Service) {
    private val session = MediaSession(svc, "transmiplay")
    private var lastKey = ""
    private val volume = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, 20, Hub.masterVol / 5) {
        override fun onAdjustVolume(direction: Int) {
            if (direction == 0) return
            Hub.volAll(direction * 5)
            currentVolume = (Hub.masterVol / 5).coerceIn(0, 20)
        }
        override fun onSetVolumeTo(volume: Int) {
            Hub.setVolAll(volume * 5)
            currentVolume = volume.coerceIn(0, 20)
        }
    }
    private val watcher: () -> Unit = { update() }

    init {
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { if (!Hub.playing) Hub.togglePlay() }
            override fun onPause() { if (Hub.playing) Hub.togglePlay() }
            override fun onSkipToNext() { Hub.next() }
            override fun onSkipToPrevious() { Hub.prev() }
            override fun onFastForward() { Hub.skip(10_000) }
            override fun onRewind() { Hub.skip(-10_000) }
            override fun onSeekTo(pos: Long) { Hub.seek(pos) }
            override fun onStop() { Hub.stopAll(); CaptureService.stop(svc) }
        })
        @Suppress("DEPRECATION")
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        session.setSessionActivity(PendingIntent.getActivity(svc, 0,
            Intent(svc, MainActivity::class.java).putExtra(MainActivity.EXTRA_CONTROL, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        Hub.watchers += watcher
        update()
    }

    fun handle(action: String?) {
        when (action) {
            A_TOGGLE -> Hub.togglePlay()
            A_NEXT -> Hub.next()
            A_PREV -> Hub.prev()
            A_STOP -> { Hub.stopAll(); CaptureService.stop(svc) }
        }
    }

    fun release() {
        Hub.watchers -= watcher
        session.release()
    }

    private fun update() {
        val cur = Hub.current
        val active = cur != null || Hub.caixinha
        // volume: com o celular tocando junto, o botão mexe no som do celular; senão nas TVs
        if (active && Hub.activeTvs > 0 && !PhoneSpeaker.enabled) {
            session.setPlaybackToRemote(volume)
            volume.currentVolume = (Hub.masterVol / 5).coerceIn(0, 20)
        } else {
            session.setPlaybackToLocal(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
        }
        if (!active) {
            if (session.isActive) session.isActive = false
            if (lastKey != "") { lastKey = ""; notify(TransmitService.buildNotification(svc, "Pronto pra mandar pras TVs")) }
            return
        }
        val title = if (cur == null) "Som do celular" else cur.title
        val sub = when {
            cur == null -> "Modo caixinha · ao vivo"
            Hub.activeTvs == 1 -> "Tocando em 1 TV"
            else -> "Tocando em ${Hub.activeTvs} TVs"
        }
        val playing = Hub.playing || (Hub.caixinha && cur == null)
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP
        if (cur != null) actions = actions or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_REWIND or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (Hub.hasNext()) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(actions)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                if (cur != null) Hub.position() else PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                if (playing && cur != null) 1f else 0f)
            .build())
        val key = "$title|$sub|$playing|${Hub.hasNext()}|${cur?.durMs}"
        if (key == lastKey) return
        lastKey = key
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, sub)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, cur?.durMs ?: 0)
            .build())
        if (!session.isActive) session.isActive = true
        notify(build(title, sub, playing, cur != null))
    }

    private fun notify(n: Notification) {
        (svc.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1, n)
    }

    private fun pi(action: String, code: Int) = PendingIntent.getService(svc, code,
        Intent(svc, TransmitService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    @Suppress("DEPRECATION")
    private fun build(title: String, sub: String, playing: Boolean, media: Boolean): Notification {
        TransmitService.buildNotification(svc, sub) // garante o canal
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(svc, TransmitService.CHANNEL) else Notification.Builder(svc)
        val open = PendingIntent.getActivity(svc, 0, Intent(svc, MainActivity::class.java).putExtra(MainActivity.EXTRA_CONTROL, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        b.setContentTitle(title).setContentText(sub)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setLargeIcon(android.graphics.BitmapFactory.decodeResource(svc.resources, R.drawable.logo))
        val compact = mutableListOf<Int>()
        var i = 0
        if (media) { b.addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Anterior", pi(A_PREV, 11)).build()); i++ }
        b.addAction(Notification.Action.Builder(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (playing) "Pausar" else "Tocar", pi(A_TOGGLE, 12)).build())
        compact += i; i++
        if (media && Hub.hasNext()) { b.addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Próxima", pi(A_NEXT, 13)).build()); compact += i; i++ }
        b.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Parar", pi(A_STOP, 14)).build())
        compact += i
        b.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(*compact.take(3).toIntArray()))
        return b.build()
    }

    companion object {
        const val A_TOGGLE = "toggle"
        const val A_NEXT = "next"
        const val A_PREV = "prev"
        const val A_STOP = "stopall"
    }
}
