import com.pranvir.tether.*

object Sim {
    @JvmStatic fun main(a: Array<String>) {
        val god = a.isNotEmpty() && a[0] == "god"
        val seeds = if (a.size > 1) a[1].toInt() else 12
        var tot = 0f
        for (s in 0 until seeds) {
            val w = World(1000L + s)
            w.godMode = god
            w.setView(2100f)
            var t = 0f
            var held = false
            var presses = 0
            val dt = 1f / 60f
            while (!w.dead && t < 600f) {
                val h = w.autopilot()
                if (h && !held) presses++
                held = h
                w.update(dt, held)
                t += dt
                if (w.heightM > (if (a.size > 2) a[2].toFloat() else 2600f)) break
            }
            tot += w.heightM
            println("seed $s: ${w.heightM.toInt()} m zone ${w.zone} t=${t.toInt()}s presses=$presses perf=${w.perfects} maxCombo=${w.maxCombo} motes=${w.motes} near=${w.nearMisses} dead=${w.dead} cause=${w.deathCause} anchors=${w.anchors.size} haz=${w.hazardsMade}")
        }
        println("avg ${tot / seeds}")
    }
}
