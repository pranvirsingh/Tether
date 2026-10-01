import com.pranvir.tether.*
object Trace {
    @JvmStatic fun main(a: Array<String>) {
        val w = World(1000L); w.godMode = true; w.setView(2100f)
        var held = false; var t = 0f; val dt = 1f/60f; var k = 0
        val secs = if (a.isNotEmpty()) a[0].toFloat() else 6f
        while (t < secs && !w.dead) {
            held = w.autopilot(); w.update(dt, held); t += dt; k++
            if (k % 6 == 0) { val an = w.anchor
                println("sd=${w.spinDir} ts=${w.timeScale} t=%.2f held=%b pos=(%.0f,%.0f) v=(%.0f,%.0f) L=%.0f anc=%s h=%.1f".format(t, held, w.x, w.y, w.vx, w.vy, w.ropeL, if (an==null) "-" else "(%.0f,%.0f)".format(an.x, an.y), w.heightM)) }
        }
    }
}
