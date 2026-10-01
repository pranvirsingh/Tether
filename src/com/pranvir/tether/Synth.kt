package com.pranvir.tether

import java.io.ByteArrayOutputStream
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

object Sfx {
    const val ZIP = 0; const val WHOOSH = 1; const val PERFECT = 2; const val MOTE = 3; const val PHASE = 4
    const val NEAR = 5; const val DEATH = 6; const val ZONE = 7; const val TAP = 8; const val UNLOCK = 9
    const val SNAP = 10; const val NOPE = 11; const val BEST = 12
    const val COUNT = 13
}

/** Synthwave stems, all the same length, mixed live. */
object Stem {
    const val PAD = 0; const val BASS = 1; const val DRUMS = 2; const val ARP = 3; const val LEAD = 4
    const val COUNT = 5
}

object Synth {
    const val RATE = 22050
    const val BPM = 104f
    private const val TAU = (2 * PI).toFloat()
    val BEAT = 60f / BPM
    val BAR = BEAT * 4f
    val LOOP_BARS = 8
    val LOOP_SEC = BAR * LOOP_BARS
    val LOOP_N = (LOOP_SEC * RATE).toInt()

    fun hz(m: Float) = (440.0 * 2.0.pow((m - 69.0) / 12.0)).toFloat()
    private fun buf(sec: Float) = FloatArray((sec * RATE).toInt().coerceAtLeast(1))

    fun pcm(f: FloatArray, gain: Float): ShortArray {
        var peak = 0.0001f
        for (v in f) { val a = abs(v); if (a > peak) peak = a }
        val g = gain / peak
        return ShortArray(f.size) { (tanh((f[it] * g).toDouble()) * 30000).toInt().toShort() }
    }

    // ------------------------------------------------------------------ voices

    /** Detuned saw stack through a one-pole low-pass with its own envelope. */
    private fun saw(b: FloatArray, start: Float, dur: Float, f: Float, amp: Float, att: Float, rel: Float, cutoff: Float,
                    voices: Int = 3, detune: Float = 0.006f, wrap: Boolean = true, vib: Float = 0f) {
        val n = b.size
        val o = (start * RATE).toInt()
        val len = ((dur + rel) * RATE).toInt()
        val ph = FloatArray(voices) { it * 0.37f }
        var lp = 0f; var lp2 = 0f
        val a = 1f - exp(-TAU * cutoff / RATE)
        for (j in 0 until len) {
            val t = j / RATE.toFloat()
            val e = (if (t < att) t / att else 1f) * (if (t > dur) exp(-(t - dur) / rel * 3f) else 1f)
            var s = 0f
            val fm = if (vib > 0f) 1f + vib * sin(TAU * 5.2f * t) * clamp01(t / 0.4f) else 1f
            for (v in 0 until voices) {
                val fv = f * fm * (1f + (v - (voices - 1) / 2f) * detune)
                ph[v] += fv / RATE; if (ph[v] >= 1f) ph[v] -= 1f
                s += ph[v] * 2f - 1f
            }
            s /= voices
            lp += a * (s - lp); lp2 += a * (lp - lp2)
            val i = o + j
            if (!wrap && i >= n) break
            b[((i % n) + n) % n] += lp2 * e * amp
        }
    }

    private fun sine(b: FloatArray, start: Float, f: Float, amp: Float, decay: Float, att: Float = 0.002f, f2: Float = f, wrap: Boolean = false) {
        val n = b.size
        val o = (start * RATE).toInt()
        var ph = 0f
        val maxLen = (RATE * 4f).toInt()
        for (j in 0 until maxLen) {
            val t = j / RATE.toFloat()
            val e = (if (t < att) t / att else 1f) * exp(-(t - att).coerceAtLeast(0f) * decay)
            if (t > att && e < 0.0005f) break
            val fr = f2 + (f - f2) * exp(-t * 30f)
            ph += fr / RATE
            val i = o + j
            if (!wrap && i >= n) break
            b[((i % n) + n) % n] += sin(TAU * ph) * e * amp
        }
    }

    private fun noise(b: FloatArray, start: Float, len: Float, amp: Float, lpHz: Float, hp: Boolean, rnd: Random, decay: Float, att: Float = 0.001f, wrap: Boolean = false) {
        val n = b.size
        val o = (start * RATE).toInt(); val cnt = (len * RATE).toInt()
        var y = 0f
        val a = 1f - exp(-TAU * lpHz / RATE)
        for (j in 0 until cnt) {
            val t = j / RATE.toFloat()
            val e = (if (t < att) t / att else 1f) * exp(-t * decay)
            val w = rnd.nextFloat() * 2f - 1f
            y += a * (w - y)
            val s = if (hp) w - y else y
            val i = o + j
            if (!wrap && i >= n) break
            b[((i % n) + n) % n] += s * e * amp
        }
    }

    private fun square(b: FloatArray, start: Float, dur: Float, f: Float, amp: Float, decay: Float, cutoff: Float) {
        val n = b.size
        val o = (start * RATE).toInt()
        val len = (dur * RATE).toInt()
        var ph = 0f; var lp = 0f
        val a = 1f - exp(-TAU * cutoff / RATE)
        for (j in 0 until len) {
            val t = j / RATE.toFloat()
            val e = min1(t / 0.003f) * exp(-t * decay)
            ph += f / RATE; if (ph >= 1f) ph -= 1f
            val s = if (ph < 0.5f) 1f else -1f
            lp += a * (s - lp)
            val i = o + j
            b[i % n] += lp * e * amp
        }
    }

    private fun min1(v: Float) = if (v > 1f) 1f else v

    /** Feedback echo, tempo-synced. */
    private fun echo(b: FloatArray, delaySec: Float, fb: Float, mix: Float) {
        val d = (delaySec * RATE).toInt()
        val n = b.size
        val out = b.copyOf()
        val tap = FloatArray(n)
        for (pass in 0 until 2) {
            for (i in 0 until n) {
                val src = (i - d + n) % n
                tap[i] = out[src] * mix + tap[src] * fb
            }
        }
        for (i in 0 until n) b[i] += tap[i]
    }

    // ------------------------------------------------------------------ sfx

    fun render(id: Int): ShortArray {
        val rnd = Random(77L + id)
        return when (id) {
            Sfx.ZIP -> {
                val b = buf(0.22f)
                sine(b, 0f, 1500f, 0.5f, 18f, 0.001f, 420f)
                noise(b, 0f, 0.12f, 0.35f, 6000f, true, rnd, 28f)
                sine(b, 0.02f, 2400f, 0.15f, 30f)
                pcm(b, 0.8f)
            }
            Sfx.WHOOSH -> {
                val b = buf(0.4f)
                val n = b.size
                var y = 0f; var y2 = 0f
                for (i in 0 until n) {
                    val t = i / RATE.toFloat()
                    val e = sin(PI.toFloat() * clamp01(t / 0.38f)).let { it * it }
                    val c = 600f + 2600f * clamp01(t / 0.25f)
                    val a = 1f - exp(-TAU * c / RATE)
                    y += a * (rnd.nextFloat() * 2f - 1f - y); y2 += a * (y - y2)
                    b[i] = (y - y2 * 0.6f) * e
                }
                pcm(b, 0.7f)
            }
            Sfx.PERFECT -> {
                val b = buf(1.1f)
                val base = 88f // E6
                sine(b, 0f, hz(base), 0.5f, 4.5f, 0.002f)
                sine(b, 0f, hz(base + 7f), 0.32f, 5f, 0.002f)
                sine(b, 0f, hz(base + 12f) * 1.003f, 0.22f, 6f, 0.002f)
                sine(b, 0.05f, hz(base + 19f), 0.12f, 7f, 0.002f)
                sine(b, 0f, hz(base) * 2.76f, 0.08f, 12f)
                noise(b, 0f, 0.05f, 0.12f, 9000f, true, rnd, 60f)
                pcm(b, 0.75f)
            }
            Sfx.MOTE -> {
                val b = buf(0.25f)
                sine(b, 0f, 1975f, 0.5f, 22f)
                sine(b, 0.035f, 2637f, 0.4f, 20f)
                sine(b, 0f, 3951f, 0.12f, 35f)
                pcm(b, 0.6f)
            }
            Sfx.PHASE -> {
                val b = buf(0.9f)
                val notes = floatArrayOf(69f, 76f, 81f, 88f, 93f)
                for ((k, m) in notes.withIndex()) sine(b, k * 0.035f, hz(m), 0.3f, 5f, 0.004f)
                noise(b, 0f, 0.6f, 0.12f, 7000f, true, rnd, 5f, 0.08f)
                pcm(b, 0.7f)
            }
            Sfx.NEAR -> {
                val b = buf(0.8f)
                val n = b.size
                var y = 0f
                for (i in 0 until n) {
                    val t = i / RATE.toFloat()
                    val e = clamp01(t / 0.25f).let { it * it } * exp(-(t - 0.25f).coerceAtLeast(0f) * 9f)
                    val a = 1f - exp(-TAU * (300f + 3000f * clamp01(t / 0.25f)) / RATE)
                    y += a * (rnd.nextFloat() * 2f - 1f - y)
                    b[i] = y * e * 0.8f
                }
                sine(b, 0.24f, 70f, 0.8f, 9f, 0.002f, 140f)
                pcm(b, 0.8f)
            }
            Sfx.DEATH -> {
                val b = buf(1.6f)
                sine(b, 0f, 48f, 1f, 3.5f, 0.002f, 160f)
                noise(b, 0f, 0.5f, 0.6f, 9000f, true, rnd, 10f)
                for (k in 0 until 14) {
                    val t0 = k * 0.03f + rnd.nextFloat() * 0.04f
                    sine(b, t0, 2500f + rnd.nextFloat() * 4500f, 0.16f, 18f + rnd.nextFloat() * 20f)
                }
                noise(b, 0f, 1.4f, 0.25f, 400f, false, rnd, 3f)
                pcm(b, 0.9f)
            }
            Sfx.ZONE -> {
                val b = buf(2.6f)
                val n = b.size
                var y = 0f
                for (i in 0 until (1.4f * RATE).toInt()) {
                    val t = i / RATE.toFloat()
                    val e = (t / 1.4f).let { it * it * it }
                    val a = 1f - exp(-TAU * (300f + 6000f * t / 1.4f) / RATE)
                    y += a * (rnd.nextFloat() * 2f - 1f - y)
                    b[i] += y * e * 0.5f
                }
                sine(b, 1.4f, 55f, 1f, 2.6f, 0.002f, 110f)
                for (m in floatArrayOf(57f, 64f, 69f, 72f, 76f)) saw(b, 1.4f, 0.5f, hz(m), 0.14f, 0.005f, 0.6f, 2400f, 2, 0.008f, false)
                noise(b, 1.4f, 1.1f, 0.25f, 8000f, true, rnd, 4f)
                pcm(b, 0.85f)
            }
            Sfx.TAP -> {
                val b = buf(0.08f)
                sine(b, 0f, 1300f, 0.6f, 60f, 0.001f, 1800f)
                noise(b, 0f, 0.02f, 0.2f, 8000f, true, rnd, 200f)
                pcm(b, 0.5f)
            }
            Sfx.UNLOCK -> {
                val b = buf(1.4f)
                val ns = floatArrayOf(72f, 76f, 79f, 84f)
                for ((k, m) in ns.withIndex()) {
                    saw(b, k * 0.09f, 0.12f, hz(m), 0.22f, 0.004f, 0.25f, 3500f, 3, 0.006f, false)
                    sine(b, k * 0.09f, hz(m + 12f), 0.18f, 6f)
                }
                saw(b, 0.36f, 0.5f, hz(84f), 0.18f, 0.01f, 0.5f, 3000f, 3, 0.008f, false)
                sine(b, 0.36f, hz(91f), 0.2f, 3f)
                noise(b, 0.36f, 0.8f, 0.1f, 9000f, true, rnd, 4f, 0.02f)
                pcm(b, 0.75f)
            }
            Sfx.SNAP -> {
                val b = buf(0.5f)
                noise(b, 0f, 0.08f, 0.8f, 9000f, true, rnd, 40f)
                sine(b, 0f, 3100f, 0.3f, 14f)
                sine(b, 0.01f, 4650f, 0.2f, 18f)
                sine(b, 0f, 160f, 0.5f, 20f, 0.001f, 320f)
                pcm(b, 0.75f)
            }
            Sfx.NOPE -> {
                val b = buf(0.22f)
                square(b, 0f, 0.09f, 220f, 0.4f, 18f, 1500f)
                square(b, 0.09f, 0.12f, 165f, 0.4f, 14f, 1500f)
                pcm(b, 0.5f)
            }
            Sfx.BEST -> {
                val b = buf(1.8f)
                val ns = floatArrayOf(69f, 72f, 76f, 81f, 84f, 88f)
                for ((k, m) in ns.withIndex()) sine(b, k * 0.07f, hz(m), 0.3f, 3f, 0.003f)
                for (m in floatArrayOf(69f, 76f, 81f)) saw(b, 0.42f, 0.7f, hz(m), 0.12f, 0.02f, 0.6f, 2800f, 3, 0.008f, false)
                noise(b, 0.42f, 1f, 0.08f, 9000f, true, rnd, 3f, 0.05f)
                pcm(b, 0.8f)
            }
            else -> ShortArray(10)
        }
    }

    // ------------------------------------------------------------------ music

    /** Am – F – C – G, twice; second half lifts to the upper voicings. */
    private val ROOTS = intArrayOf(45, 41, 48, 43, 45, 41, 48, 43)
    private val CHORDS = arrayOf(
        intArrayOf(57, 60, 64, 69), intArrayOf(57, 60, 65, 69), intArrayOf(55, 60, 64, 67), intArrayOf(55, 59, 62, 67),
        intArrayOf(57, 64, 69, 72), intArrayOf(57, 65, 69, 72), intArrayOf(60, 64, 67, 72), intArrayOf(59, 62, 67, 71))

    fun renderStem(id: Int): ShortArray = when (id) {
        Stem.PAD -> pad()
        Stem.BASS -> bass()
        Stem.DRUMS -> drums()
        Stem.ARP -> arp()
        else -> lead()
    }

    private fun stemBuf() = FloatArray(LOOP_N)

    private fun pad(): ShortArray {
        val b = stemBuf()
        for (bar in 0 until LOOP_BARS) {
            for (m in CHORDS[bar]) saw(b, bar * BAR, BAR * 0.98f, hz(m.toFloat()), 0.16f, 0.35f, 0.6f, 1100f, 3, 0.007f)
            sine(b, bar * BAR, hz(ROOTS[bar] + 12f), 0.12f, 0.6f, 0.2f, wrap = true)
        }
        echo(b, BEAT * 0.75f, 0.35f, 0.25f)
        return pcm(b, 0.55f)
    }

    private fun bass(): ShortArray {
        val b = stemBuf()
        val e8 = BEAT / 2f
        for (bar in 0 until LOOP_BARS) {
            val r = ROOTS[bar].toFloat() - 12f
            for (k in 0 until 8) {
                val m = if (k % 2 == 0) r else r + 12f
                saw(b, bar * BAR + k * e8, e8 * 0.55f, hz(m), 0.5f, 0.004f, 0.05f, 700f + (if (k % 2 == 1) 500f else 0f), 2, 0.004f)
            }
        }
        // sidechain pump against the kick
        val q = (BEAT * RATE).toInt()
        for (i in b.indices) { val p = (i % q) / q.toFloat(); b[i] *= 0.35f + 0.65f * smooth(p / 0.45f) }
        return pcm(b, 0.6f)
    }

    private fun drums(): ShortArray {
        val b = stemBuf()
        val rnd = Random(5)
        val s16 = BEAT / 4f
        for (bar in 0 until LOOP_BARS) {
            for (beat in 0 until 4) {
                val t = bar * BAR + beat * BEAT
                sine(b, t, 52f, 0.95f, 9f, 0.001f, 150f, wrap = true)
                noise(b, t, 0.01f, 0.25f, 4000f, false, rnd, 300f, wrap = true)
                if (beat == 1 || beat == 3) {
                    noise(b, t, 0.45f, 0.5f, 5200f, false, rnd, 9f, wrap = true)       // gated-ish snare body
                    sine(b, t, 190f, 0.35f, 22f, 0.001f, 240f, wrap = true)
                    noise(b, t + 0.012f, 0.3f, 0.18f, 7000f, true, rnd, 12f, wrap = true)
                }
            }
            for (k in 0 until 16) {
                val acc = if (k % 4 == 2) 0.2f else if (k % 2 == 0) 0.07f else 0.11f
                noise(b, bar * BAR + k * s16, 0.05f, acc, 9000f, true, rnd, 70f, wrap = true)
            }
            if (bar % 4 == 3) for (k in 12 until 16) noise(b, bar * BAR + k * s16, 0.12f, 0.28f, 5000f, false, rnd, 20f, wrap = true)
        }
        return pcm(b, 0.75f)
    }

    private fun arp(): ShortArray {
        val b = stemBuf()
        val s16 = BEAT / 4f
        val pattern = intArrayOf(0, 1, 2, 3, 2, 1, 0, 2, 0, 1, 2, 3, 3, 2, 1, 3)
        for (bar in 0 until LOOP_BARS) {
            val ch = CHORDS[bar]
            for (k in 0 until 16) {
                val m = ch[pattern[k]] + 12
                square(b, bar * BAR + k * s16, s16 * 0.9f, hz(m.toFloat()), 0.16f, 14f, 2600f)
            }
        }
        echo(b, BEAT * 0.75f, 0.4f, 0.4f)
        return pcm(b, 0.5f)
    }

    private fun lead(): ShortArray {
        val b = stemBuf()
        // (bar, beat offset, length in beats, midi)
        val mel = arrayOf(
            floatArrayOf(0f, 0f, 1.5f, 76f), floatArrayOf(0f, 1.5f, 0.5f, 74f), floatArrayOf(0f, 2f, 2f, 72f),
            floatArrayOf(1f, 0f, 1.5f, 72f), floatArrayOf(1f, 1.5f, 0.5f, 74f), floatArrayOf(1f, 2f, 2f, 77f),
            floatArrayOf(2f, 0f, 1f, 79f), floatArrayOf(2f, 1f, 1f, 76f), floatArrayOf(2f, 2f, 2f, 72f),
            floatArrayOf(3f, 0f, 2.5f, 74f), floatArrayOf(3f, 2.5f, 1.5f, 71f),
            floatArrayOf(4f, 0f, 1.5f, 81f), floatArrayOf(4f, 1.5f, 0.5f, 79f), floatArrayOf(4f, 2f, 2f, 76f),
            floatArrayOf(5f, 0f, 1.5f, 77f), floatArrayOf(5f, 1.5f, 0.5f, 76f), floatArrayOf(5f, 2f, 2f, 72f),
            floatArrayOf(6f, 0f, 1f, 79f), floatArrayOf(6f, 1f, 1f, 84f), floatArrayOf(6f, 2f, 2f, 83f),
            floatArrayOf(7f, 0f, 3f, 79f), floatArrayOf(7f, 3f, 1f, 76f))
        for (n in mel) {
            val t = n[0] * BAR + n[1] * BEAT
            saw(b, t, n[2] * BEAT * 0.92f, hz(n[3]), 0.3f, 0.02f, 0.18f, 2300f, 3, 0.005f, vib = 0.006f)
        }
        echo(b, BEAT * 0.75f, 0.35f, 0.32f)
        return pcm(b, 0.55f)
    }

    /**
     * Stem gains. scene: 0 menu, 1 playing, 2 paused, 3 results.
     * combo and speed (0..1) build the arrangement up while climbing.
     */
    fun targets(scene: Int, combo: Int, intensity: Float, music: Boolean, out: FloatArray) {
        for (i in out.indices) out[i] = 0f
        if (!music) return
        when (scene) {
            0 -> { out[Stem.PAD] = 0.55f; out[Stem.ARP] = 0.3f; out[Stem.BASS] = 0.25f }
            1 -> {
                out[Stem.PAD] = 0.45f
                out[Stem.BASS] = 0.55f
                out[Stem.DRUMS] = 0.35f + 0.3f * clamp01(intensity)
                out[Stem.ARP] = if (combo >= 1 || intensity > 0.55f) 0.38f else 0.12f
                out[Stem.LEAD] = if (combo >= 4) 0.42f else if (combo >= 2) 0.15f else 0f
            }
            2 -> { out[Stem.PAD] = 0.45f; out[Stem.BASS] = 0.15f }
            else -> { out[Stem.PAD] = 0.5f; out[Stem.ARP] = 0.18f }
        }
    }

    fun wav(pcm: ShortArray): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size * 2)
        fun i32(v: Int) { out.write(v and 255); out.write((v shr 8) and 255); out.write((v shr 16) and 255); out.write((v shr 24) and 255) }
        fun i16(v: Int) { out.write(v and 255); out.write((v shr 8) and 255) }
        out.write("RIFF".toByteArray()); i32(36 + pcm.size * 2); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(RATE); i32(RATE * 2); i16(2); i16(16)
        out.write("data".toByteArray()); i32(pcm.size * 2)
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) { bytes[i * 2] = (pcm[i].toInt() and 255).toByte(); bytes[i * 2 + 1] = (pcm[i].toInt() shr 8).toByte() }
        out.write(bytes)
        return out.toByteArray()
    }
}

/** Pure mixing logic (unit-testable): gains, tape-stop resampling and a muffle low-pass. */
class Mixer(private val st: Array<ShortArray>) {
    private val g = FloatArray(st.size)
    private val target = FloatArray(st.size)
    private var pos = 0f
    private var rate = 1f
    private var lp = 0f
    private var lpA = 1f

    fun render(out: ShortArray, scene: Int, combo: Int, intensity: Float, slow: Boolean, dead: Boolean, music: Boolean) {
        Synth.targets(scene, combo, intensity, music, target)
        for (k in st.indices) g[k] = approach(g[k], target[k], 0.02f)
        val lpT = if (slow || scene == 2) 0.1f else if (dead) 0.25f else 1f
        val n = st[0].size
        for (i in out.indices) {
            rate = if (dead) maxOf(0f, rate - 1f / (22050f * 0.9f)) else minOf(1f, rate + 1f / (22050f * 0.35f))
            lpA += (lpT - lpA) * 0.0004f
            val i0 = pos.toInt()
            val fr = pos - i0
            val i1 = if (i0 + 1 >= n) 0 else i0 + 1
            var s = 0f
            for (k in st.indices) {
                if (g[k] <= 0.0005f) continue
                val a = st[k]
                s += (a[i0] + (a[i1] - a[i0]) * fr) * g[k]
            }
            s *= minOf(1f, rate * 3f)
            lp += lpA * (s - lp)
            out[i] = lp.coerceIn(-32000f, 32000f).toInt().toShort()
            pos += rate
            if (pos >= n) pos -= n
        }
    }
}
