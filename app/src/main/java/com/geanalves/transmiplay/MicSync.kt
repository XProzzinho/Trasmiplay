package com.geanalves.transmiplay

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos

/** Acha em que momento um tom começa numa gravação (filtro Goertzel em janelas curtas). */
object MicDetect {
    /** @return início do tom em ms desde o começo da gravação, ou null se não ouviu */
    fun onsetMs(x: ShortArray, rate: Int, freq: Double, quietMs: Int = 250): Double? {
        val win = rate / 100          // janela de 10 ms
        val hop = rate / 1000         // passo de 1 ms
        if (x.size < win * 4) return null
        val k = 2 * cos(2 * PI * freq / rate)
        val n = (x.size - win) / hop
        val pw = DoubleArray(n)
        for (w in 0 until n) {
            var s1 = 0.0; var s2 = 0.0
            val off = w * hop
            for (i in 0 until win) {
                val s0 = x[off + i] + k * s1 - s2
                s2 = s1; s1 = s0
            }
            pw[w] = s1 * s1 + s2 * s2 - k * s1 * s2
        }
        val quiet = (quietMs * rate / 1000 / hop).coerceIn(1, n)
        val noise = pw.copyOfRange(0, quiet).sorted()[quiet / 2].coerceAtLeast(1.0)
        val peak = pw.maxOrNull() ?: return null
        if (peak < noise * 40) return null // não ouviu o apito
        val thr = maxOf(noise * 12, peak * 0.2)
        val first = pw.indexOfFirst { it > thr }
        if (first < 0) return null
        // a janela "pega" o tom quando a metade dela já tem som: corrige meio passo da janela
        return (first * hop + win / 2.0) * 1000.0 / rate
    }
}

/**
 * Sincronizar pelo microfone: cada TV apita num tom diferente no mesmo horário,
 * o celular escuta, vê quem chegou atrasado e corrige o ajuste de cada uma.
 */
object MicSync {
    private const val RATE = 48000
    val FREQS = doubleArrayOf(1500.0, 2200.0, 2900.0, 3600.0, 4300.0, 5000.0)
    private val main = Handler(Looper.getMainLooper())

    /** erro medido de cada TV (ms, já com o atraso do microfone que é igual pra todas) */
    val measured = LinkedHashMap<String, Double>()
    @Volatile var running = false; private set

    @SuppressLint("MissingPermission")
    fun run(tvs: List<Hub.Syncable>, rounds: Int = 3, progress: (String) -> Unit, done: (Map<Hub.Syncable, Double?>) -> Unit) {
        if (running || tvs.isEmpty()) return
        running = true
        val list = tvs.take(FREQS.size)
        thread(name = "mic-sync", isDaemon = true) {
            val results = HashMap<Hub.Syncable, MutableList<Double>>()
            try {
                for (r in 1..rounds) {
                    main.post { progress("Escutando… rodada $r de $rounds") }
                    val total = RATE * 22 / 10 // 2,2 s
                    val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, total * 2))
                    val buf = ShortArray(total)
                    rec.startRecording()
                    // o apito sai daqui a 0,7 s (tempo de todo mundo se preparar)
                    val target = Hub.now() + 700
                    list.forEachIndexed { i, tv -> tv.beep(target, FREQS[i]) }
                    var got = 0
                    while (got < total) {
                        val n = rec.read(buf, got, total - got)
                        if (n <= 0) break
                        got += n
                    }
                    // quando foi gravado o 1º som: usa o carimbo de tempo do microfone
                    val st = AudioTimestamp()
                    val startEl: Double = if (rec.getTimestamp(st, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        val stampEl = SystemClock.elapsedRealtime() + (st.nanoTime - System.nanoTime()) / 1e6
                        stampEl - st.framePosition * 1000.0 / RATE
                    } else Hub.now() - got * 1000.0 / RATE
                    rec.stop(); rec.release()
                    list.forEachIndexed { i, tv ->
                        val on = MicDetect.onsetMs(buf.copyOf(got), RATE, FREQS[i]) ?: return@forEachIndexed
                        results.getOrPut(tv) { mutableListOf() } += (startEl + on) - target
                    }
                    Thread.sleep(400)
                }
            } catch (e: Exception) {
                main.post { progress("Erro no microfone: ${e.message}") }
            }
            val med = list.associateWith { tv -> results[tv]?.sorted()?.let { it[it.size / 2] } }
            main.post {
                running = false
                apply(med)
                done(med)
            }
        }
    }

    /** alinha todo mundo pela TV que soou mais cedo */
    private fun apply(med: Map<Hub.Syncable, Double?>) {
        for ((tv, e) in med) if (e != null) measured[tv.name] = e
        val all: List<Hub.Syncable> = Hub.tvs.toList<Hub.Syncable>() + listOfNotNull(PhoneSpeaker.takeIf { it.enabled })
        val active = all.filter { measured.containsKey(it.name) }
        if (active.size < 2) return // com uma só ainda não dá pra comparar: espera medir outra
        // com o celular junto, ele é a referência (o som dele não dá pra atrasar no modo caixinha)
        val ref = if (active.contains(PhoneSpeaker)) measured[PhoneSpeaker.name]!! else active.minOf { measured[it.name]!! }
        for (tv in active) {
            val late = measured[tv.name]!! - ref
            val step = (-late).toInt()
            if (kotlin.math.abs(step) >= 4 && tv !== PhoneSpeaker) tv.changeAdj(step)
            measured[tv.name] = ref
        }
    }

    fun forget() = measured.clear()
}
