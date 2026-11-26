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
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Modo caixinha: pega o som que está tocando no celular (qualquer app ou site)
 * e manda ao vivo pras TVs. Precisa de Android 10 ou mais novo.
 */
class CaptureService : Service() {
    private var projection: MediaProjection? = null
    @Volatile private var running = false
    private var worker: Thread? = null
    private var mirror = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        val wantMirror = intent?.getBooleanExtra(EXTRA_MIRROR, false) == true
        val n = TransmitService.buildNotification(this,
            if (wantMirror) "Espelhando a tela nas TVs" else "Caixinha ligada: o som do celular está indo pras TVs")
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(2, n)

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
        mirror = wantMirror
        running = true
        if (mirror) startMirror(p)
        val canAudio = Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (canAudio) startCapture(p)
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun startCapture(p: MediaProjection) {
        worker = thread(name = "capture", isDaemon = true) {
            var rec: AudioRecord? = null
            try {
                // se o som do celular sai numa caixa Bluetooth, as TVs esperam o mesmo atraso dela
                val bt = PhoneSpeaker.outputName() != null
                Hub.audioDelay = if (bt) maxOf(PhoneSpeaker.measureOutputLatencyMs() + 20, 180) else Proto.AUDIO_DELAY_MS
                Hub.setCaixinha(true)
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
        if (!running) { Hub.setCaixinha(false); Hub.setMirror(false); return }
        running = false
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        Hub.setCaixinha(false)
        Hub.setMirror(false)
    }

    // ------------------------------------------------------------------ espelhar a tela

    /** tamanho da imagem: a tela do celular reduzida até 1280 no lado maior (múltiplo de 16) */
    private fun mirrorSize(): Pair<Int, Int> {
        val pt = android.graphics.Point()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(pt)
        val long = maxOf(pt.x, pt.y).toFloat(); val short = minOf(pt.x, pt.y).toFloat()
        val k = minOf(1f, 1280f / long, 720f / short)
        fun r(v: Float) = ((v * k).toInt() / 16 * 16).coerceAtLeast(16)
        return r(pt.x.toFloat()) to r(pt.y.toFloat())
    }

    private fun startMirror(p: MediaProjection) {
        Hub.setMirror(true)
        thread(name = "mirror", isDaemon = true, priority = Thread.MAX_PRIORITY) {
            var vd: VirtualDisplay? = null
            val dpi = resources.displayMetrics.densityDpi
            try {
                while (running) {
                    val (w, h) = mirrorSize()
                    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE, if (w * h > 600_000) 6_000_000 else 4_000_000)
                        setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                        // tela parada: repete o último quadro pra TV não ficar esperando
                        setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
                    }
                    codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    val surface = codec.createInputSurface()
                    codec.start()
                    val v = vd
                    if (v == null) {
                        vd = p.createVirtualDisplay("transmiplay", w, h, dpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
                    } else { v.resize(w, h, dpi); v.surface = surface }
                    Hub.mirrorSize(w, h)
                    Hub.onNeedKey = {
                        try { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } catch (_: Exception) {}
                    }
                    val info = MediaCodec.BufferInfo()
                    var lastCheck = SystemClock.elapsedRealtime()
                    while (running) {
                        val idx = codec.dequeueOutputBuffer(info, 50_000)
                        if (idx >= 0) {
                            if (info.size > 0) {
                                val buf = codec.getOutputBuffer(idx)!!
                                val data = ByteArray(info.size)
                                buf.position(info.offset); buf.get(data)
                                val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                                @Suppress("DEPRECATION")
                                val key = info.flags and MediaCodec.BUFFER_FLAG_SYNC_FRAME != 0
                                // quando o quadro foi desenhado, no relógio do celular
                                val ageMs = (System.nanoTime() / 1000 - info.presentationTimeUs) / 1000
                                val ts = if (config) Hub.now() else Hub.now() - ageMs.coerceIn(0, 500)
                                val flags = (if (config) 1 else 0) or (if (key) 2 else 0)
                                val packet = ByteBuffer.allocate(16 + data.size)
                                    .putLong(ts).putInt(flags).putInt(data.size).put(data).array()
                                Hub.pushVideo(packet, flags)
                            }
                            codec.releaseOutputBuffer(idx, false)
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastCheck > 1000) {
                            lastCheck = now
                            if (mirrorSize() != (w to h)) break // girou o celular: recomeça no tamanho novo
                        }
                    }
                    Hub.onNeedKey = null
                    try { codec.stop() } catch (_: Exception) {}
                    codec.release()
                    surface.release()
                }
            } catch (e: Exception) {
                Hub.lastError = "Espelhar: ${e.message}"
                Hub.notifyUi()
            } finally {
                Hub.onNeedKey = null
                try { vd?.release() } catch (_: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "stop"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_MIRROR = "mirror"

        fun start(ctx: Context, code: Int, data: Intent, mirror: Boolean = false) {
            val i = Intent(ctx, CaptureService::class.java).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_MIRROR, mirror)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            try { ctx.startService(Intent(ctx, CaptureService::class.java).setAction(ACTION_STOP)) } catch (_: Exception) {
                Hub.setCaixinha(false); Hub.setMirror(false)
            }
        }
    }
}
