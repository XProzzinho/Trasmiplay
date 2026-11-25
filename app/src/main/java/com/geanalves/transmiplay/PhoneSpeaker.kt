package com.geanalves.transmiplay

import android.content.Context
import android.media.AudioAttributes as AndroidAttrs
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * "Este celular": o celular também toca, na caixa Bluetooth ligada nele,
 * seguindo o mesmo relógio das TVs.
 */
object PhoneSpeaker : Hub.Syncable {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var player: ExoPlayer? = null
    private var loadedSession = -1
    private var lastSpeed = 1f
    private var seekCooldown = 0L
    private var startPosted = false
    var lastNote = ""; private set

    override val name = "Este celular"
    override val adj: Int get() = prefs().getInt("phone_adj", 0)

    val enabled: Boolean get() = ::app.isInitialized && prefs().getBoolean("phone_play", false)

    private fun prefs() = app.getSharedPreferences("transmiplay", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        main.post(tick)
        // avisa a tela quando liga/desliga uma caixa Bluetooth no celular
        if (Build.VERSION.SDK_INT >= 23) try {
            (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).registerAudioDeviceCallback(object : android.media.AudioDeviceCallback() {
                override fun onAudioDevicesAdded(d: Array<out AudioDeviceInfo>?) { Hub.notifyUi() }
                override fun onAudioDevicesRemoved(d: Array<out AudioDeviceInfo>?) { Hub.notifyUi() }
            }, main)
        } catch (_: Exception) {}
    }

    fun setEnabled(on: Boolean) {
        prefs().edit().putBoolean("phone_play", on).apply()
        if (!on) release()
        Hub.notifyUi()
    }

    override fun changeAdj(delta: Int) {
        prefs().edit().putInt("phone_adj", (adj + delta).coerceIn(-1000, 1000)).apply()
        Hub.notifyUi()
    }

    override fun beep(at: Long, freq: Double) = beepAt(at + adj, freq)

    /** nome da caixa Bluetooth ligada no celular (ou null se está no alto-falante) */
    fun outputName(): String? {
        if (!::app.isInitialized || Build.VERSION.SDK_INT < 23) return null
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val bt = outs.filter {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
        }
        if (bt.isEmpty()) return null
        return bt.joinToString(" + ") { it.productName?.toString()?.takeIf { n -> n.isNotBlank() } ?: "caixa Bluetooth" }
    }

    // ------------------------------------------------------------------ tocar junto

    private val tick = object : Runnable {
        override fun run() {
            try { sync() } catch (_: Exception) {}
            main.postDelayed(this, 50)
        }
    }

    private fun release() {
        player?.release(); player = null; loadedSession = -1; lastSpeed = 1f
    }

    private fun sync() {
        val cur = Hub.current
        if (!enabled || cur == null || Hub.caixinha) { if (player != null) release(); return }
        if (cur.kind != "media") {
            if (player != null) release()
            lastNote = "YouTube toca só nas TVs"
            return
        }
        lastNote = ""
        val p = player ?: ExoPlayer.Builder(app).build().also {
            it.setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                false,
            )
            player = it
        }
        if (loadedSession != Hub.sessionId) {
            loadedSession = Hub.sessionId
            startPosted = false
            load(p, cur)
        }
        val playing = Hub.playing
        val now = SystemClock.elapsedRealtime()
        val clock = Hub.now() - adj
        if (!playing) {
            p.playWhenReady = false; speed(p, 1f)
            if (abs(p.currentPosition - Hub.basePos) > 250 && now > seekCooldown) { p.seekTo(Hub.basePos); seekCooldown = now + 800 }
            return
        }
        if (clock < Hub.baseAt) {
            if (p.playWhenReady && !startPosted) p.playWhenReady = false
            if (abs(p.currentPosition - Hub.basePos) > 80 && now > seekCooldown) { p.seekTo(Hub.basePos); seekCooldown = now + 300 }
            val wait = Hub.baseAt - clock
            if (wait < 300 && !startPosted && p.playbackState == Player.STATE_READY) {
                startPosted = true
                main.postDelayed({ player?.playWhenReady = true; startPosted = false }, wait)
            }
            return
        }
        if (startPosted) return
        if (!p.playWhenReady) p.playWhenReady = true
        if (p.playbackState != Player.STATE_READY) return
        val target = Hub.basePos + (clock - Hub.baseAt)
        if (!Hub.autoSync) { speed(p, 1f); return }
        val diff = p.currentPosition - target
        when {
            abs(diff) > 600 -> if (now > seekCooldown) { speed(p, 1f); p.seekTo(target + 150); seekCooldown = now + 1500 }
            abs(diff) > 15 -> speed(p, (1f - diff / 2500f).coerceIn(0.95f, 1.05f))
            else -> speed(p, 1f)
        }
    }

    private fun speed(p: ExoPlayer, s: Float) {
        if (abs(s - lastSpeed) < 0.004f) return
        lastSpeed = s
        p.playbackParameters = PlaybackParameters(s)
    }

    private fun load(p: ExoPlayer, it: Hub.Item) {
        val headers = it.headers.toMutableMap()
        val ua = headers.remove("User-Agent")
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(headers)
        if (ua != null) http.setUserAgent(ua)
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(app, http))
        val b = MediaItem.Builder().setUri(it.uri ?: android.net.Uri.parse(it.url))
        when (it.mime) {
            "m3u8" -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
            "mpd" -> b.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        p.setMediaSource(factory.createMediaSource(b.build()))
        p.playWhenReady = false
        p.prepare()
    }

    // ------------------------------------------------------------------ atraso da saída (caixa Bluetooth)

    /** mede quanto tempo o som leva pra sair do celular (caixa Bluetooth costuma levar 150–300 ms) */
    fun measureOutputLatencyMs(): Int {
        val rate = 48000
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack(
            AndroidAttrs.Builder().setUsage(AndroidAttrs.USAGE_MEDIA).setContentType(AndroidAttrs.CONTENT_TYPE_MUSIC).build(),
            AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
            minBuf, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        return try {
            val chunk = ShortArray(rate / 50 * 2)
            var written = 0L
            t.play()
            val st = AudioTimestamp()
            val samples = mutableListOf<Long>()
            val end = SystemClock.elapsedRealtime() + 1500
            while (SystemClock.elapsedRealtime() < end) {
                t.write(chunk, 0, chunk.size); written += chunk.size / 2
                if (t.getTimestamp(st) && st.framePosition > 0) {
                    val presentAt = st.nanoTime / 1e6 + (written - st.framePosition) * 1000.0 / rate
                    samples += (presentAt - System.nanoTime() / 1e6).toLong()
                }
            }
            samples.sorted().getOrNull(samples.size / 2)?.toInt()?.coerceIn(0, 1000) ?: 0
        } catch (_: Exception) { 0 } finally {
            try { t.stop() } catch (_: Exception) {}
            t.release()
        }
    }

    // ------------------------------------------------------------------ apito pra sincronizar pelo microfone

    private fun beepAt(targetLocal: Long, freq: Double, durMs: Int = 120) {
        thread(name = "phone-beep", isDaemon = true, priority = Thread.MAX_PRIORITY) {
            val rate = 48000
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack(
                AndroidAttrs.Builder().setUsage(AndroidAttrs.USAGE_MEDIA).setContentType(AndroidAttrs.CONTENT_TYPE_MUSIC).build(),
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
                maxOf(minBuf, rate * 4 / 10), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
            try {
                val stamp = AudioTimestamp()
                var written = 0L
                val chunk = ShortArray(rate / 100 * 2)
                track.play()
                fun endOfQueued(): Long {
                    val now = SystemClock.elapsedRealtime()
                    return if (track.getTimestamp(stamp) && stamp.framePosition > 0) {
                        val el = now + (stamp.nanoTime - System.nanoTime()) / 1_000_000
                        el + (written - stamp.framePosition).coerceAtLeast(0) * 1000 / rate
                    } else {
                        val head = track.playbackHeadPosition.toLong() and 0xffffffffL
                        now + (written - head).coerceAtLeast(0) * 1000 / rate
                    }
                }
                var guard = 0
                while (endOfQueued() < targetLocal - 15 && guard++ < 600) { track.write(chunk, 0, chunk.size); written += chunk.size / 2 }
                val gap = ((targetLocal - endOfQueued()) * rate / 1000).toInt().coerceIn(0, rate)
                if (gap > 0) { track.write(ShortArray(gap * 2), 0, gap * 2); written += gap }
                val n = rate * durMs / 1000
                val ramp = rate * 4 / 1000
                val tone = ShortArray(n * 2)
                for (i in 0 until n) {
                    val env = when { i < ramp -> 0.5 - 0.5 * cos(PI * i / ramp); i > n - ramp -> 0.5 - 0.5 * cos(PI * (n - i) / ramp); else -> 1.0 }
                    val v = (sin(2 * PI * freq * i / rate) * env * 0.7 * Short.MAX_VALUE).toInt().toShort()
                    tone[2 * i] = v; tone[2 * i + 1] = v
                }
                track.write(tone, 0, tone.size)
                track.write(ShortArray(rate / 5 * 2), 0, rate / 5 * 2)
                Thread.sleep((targetLocal - SystemClock.elapsedRealtime()).coerceAtLeast(0) + durMs + 400L)
            } catch (_: Exception) {
            } finally {
                try { track.stop() } catch (_: Exception) {}
                track.release()
            }
        }
    }
}
