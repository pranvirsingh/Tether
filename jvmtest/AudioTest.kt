import com.pranvir.tether.*
object AudioTest {
    @JvmStatic fun main(a: Array<String>) {
        val t0 = System.currentTimeMillis()
        for (id in 0 until Sfx.COUNT) {
            val p = Synth.render(id)
            var peak = 0; var sum = 0.0
            for (v in p) { peak = maxOf(peak, Math.abs(v.toInt())); sum += v * v.toDouble() }
            println("sfx $id len=${"%.2f".format(p.size / 22050f)}s peak=$peak rms=${"%.0f".format(Math.sqrt(sum / p.size))}")
            check(peak > 2000) { "silent sfx $id" }
        }
        val t1 = System.currentTimeMillis()
        val stems = Array(Stem.COUNT) { Synth.renderStem(it) }
        val t2 = System.currentTimeMillis()
        for ((k, p) in stems.withIndex()) {
            check(p.size == Synth.LOOP_N)
            var peak = 0; var sum = 0.0
            for (v in p) { peak = maxOf(peak, Math.abs(v.toInt())); sum += v * v.toDouble() }
            // seam check: loop wrap should not click
            val seam = Math.abs(p[0] - p[p.size - 1])
            println("stem $k peak=$peak rms=${"%.0f".format(Math.sqrt(sum / p.size))} seam=$seam")
        }
        val m = Mixer(stems)
        val out = ShortArray(1024)
        var peak = 0
        for (i in 0 until 2000) {
            val dead = i in 800..900; val slow = i in 400..450
            m.render(out, if (i < 200) 0 else 1, i / 100, (i % 300) / 300f, slow, dead, true)
            for (v in out) peak = maxOf(peak, Math.abs(v.toInt()))
        }
        println("mixer peak=$peak sfx ${t1 - t0}ms stems ${t2 - t1}ms loop=${Synth.LOOP_SEC}s")
        // write a preview wav of the full arrangement
        val prev = ShortArray(22050 * 20)
        val m2 = Mixer(stems)
        var o = 0
        while (o + 1024 <= prev.size) { m2.render(out, 1, if (o > 22050 * 8) 5 else 1, 0.8f, false, o > 22050 * 17, true); System.arraycopy(out, 0, prev, o, 1024); o += 1024 }
        java.io.File("/home/claude/tether/shots/music_preview.wav").writeBytes(Synth.wav(prev))
        println("AUDIO OK")
    }
}
