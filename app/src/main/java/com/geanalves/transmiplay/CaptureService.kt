package com.geanalves.transmiplay

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.annotation.TargetApi
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Modo caixinha: pega o som que está tocando no celular (qualquer app ou site)
 * e manda ao vivo pras TVs. Precisa de Android 10 ou mais novo.
 */
@TargetApi(29)
class CaptureService : Service() {
    private var projection: MediaProjection? = null
    @Volatile private var running = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        val n = TransmitService.buildNotification(this, "Caixinha ligada: o som do celular está indo pras TVs")
        startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (code != Activity.RESULT_OK || data == null || running) {
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = try { mpm.getMediaProjection(code, data) } catch (e: Exception) { null }
        if (p == null) { Hub.lastError = "Não consegui ligar a captura de som"; Hub.notifyUi(); stopSelf(); return START_NOT_STICKY }
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopCapture(); stopSelf() }
        }, Handler(Looper.getMainLooper()))
        startCapture(p)
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startCapture(p: MediaProjection) {
        running = true
        Hub.setCaixinha(true)
        worker = thread(name = "capture", isDaemon = true) {
            var rec: AudioRecord? = null
            try {
                val config = AudioPlaybackCaptureConfiguration.Builder(p)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(Proto.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
                val minBuf = AudioRecord.getMinBufferSize(
                    Proto.SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                )
                rec = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(minBuf, Proto.CHUNK_BYTES * 4))
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
                rec.startRecording()
                val buf = ByteArray(Proto.CHUNK_BYTES)
                val chunkMs = Proto.CHUNK_BYTES * 1000L / (Proto.SAMPLE_RATE * 4)
                while (running) {
                    var got = 0
                    while (got < buf.size && running) {
                        val n = rec.read(buf, got, buf.size - got)
                        if (n < 0) throw IllegalStateException("erro $n")
                        got += n
                    }
                    // horário (no relógio do celular) em que esse pedaço começou a tocar
                    val ts = Hub.now() - chunkMs
                    val packet = ByteBuffer.allocate(12 + buf.size)
                        .putLong(ts).putInt(buf.size).put(buf).array()
                    Hub.pushAudio(packet)
                }
            } catch (e: Exception) {
                Hub.lastError = "Caixinha: ${e.message}"
                Hub.notifyUi()
            } finally {
                try { rec?.stop() } catch (_: Exception) {}
                try { rec?.release() } catch (_: Exception) {}
            }
        }
    }

    private fun stopCapture() {
        if (!running) { Hub.setCaixinha(false); return }
        running = false
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        Hub.setCaixinha(false)
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "stop"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"

        fun start(ctx: Context, code: Int, data: Intent) {
            val i = Intent(ctx, CaptureService::class.java).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            if (Build.VERSION.SDK_INT < 29) return
            try { ctx.startService(Intent(ctx, CaptureService::class.java).setAction(ACTION_STOP)) } catch (_: Exception) {
                Hub.setCaixinha(false)
            }
        }
    }
}
