package com.geanalves.transmiplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class MicDetectTest {
    private val rate = 48000

    /** sala simulada: tons que começam em horários conhecidos, com volume diferente, eco e barulho */
    private fun record(onsets: List<Pair<Double, Double>>, vols: List<Double>, noise: Double, seed: Long = 1): ShortArray {
        val n = rate * 22 / 10
        val x = DoubleArray(n)
        val rnd = Random(seed)
        for (i in 0 until n) x[i] = rnd.nextGaussian() * noise + noise * 1.5 * sin(2 * PI * 180 * i / rate) // ronco grave + chiado
        onsets.forEachIndexed { k, (freq, ms) ->
            val start = (ms * rate / 1000).toInt()
            val len = rate * 120 / 1000; val ramp = rate * 4 / 1000
            for (i in 0 until len) {
                val env = when { i < ramp -> 0.5 - 0.5 * cos(PI * i / ramp); i > len - ramp -> 0.5 - 0.5 * cos(PI * (len - i) / ramp); else -> 1.0 }
                val v = sin(2 * PI * freq * i / rate) * env * vols[k]
                if (start + i < n) x[start + i] += v
                val echo = start + i + rate * 35 / 1000 // eco da parede 35 ms depois
                if (echo < n) x[echo] += v * 0.45
            }
        }
        return ShortArray(n) { (x[it] * 32767).coerceIn(-32768.0, 32767.0).toInt().toShort() }
    }

    @Test fun threeTvsTogether() {
        val truth = listOf(1500.0 to 700.0, 2200.0 to 742.0, 2900.0 to 688.0)
        val rec = record(truth, vols = listOf(0.30, 0.08, 0.15), noise = 0.01)
        for ((f, ms) in truth) {
            val got = MicDetect.onsetMs(rec, rate, f)!!
            println("tom ${f.toInt()} Hz: real ${ms} ms, achou ${"%.1f".format(got)} ms")
            assertEquals(ms, got, 4.0)
        }
        // diferença entre TVs (o que importa pra sincronia)
        val a = MicDetect.onsetMs(rec, rate, 1500.0)!!; val b = MicDetect.onsetMs(rec, rate, 2200.0)!!
        assertEquals(42.0, b - a, 3.0)
    }

    @Test fun noisyRoom() {
        val truth = listOf(3600.0 to 810.0, 4300.0 to 760.0)
        val rec = record(truth, vols = listOf(0.05, 0.05), noise = 0.02, seed = 7)
        for ((f, ms) in truth) assertEquals(ms, MicDetect.onsetMs(rec, rate, f)!!, 5.0)
    }

    @Test fun silentTvIsNotInvented() {
        val rec = record(listOf(1500.0 to 700.0), vols = listOf(0.2), noise = 0.01)
        assertNull(MicDetect.onsetMs(rec, rate, 2900.0)) // essa TV não apitou
    }
}
