import com.pranvir.tether.*
object Fall {
    @JvmStatic fun main(a: Array<String>) {
        val seed = a[0].toLong()
        val w = World(seed); w.godMode = true; w.setView(2100f)
        val log = ArrayList<String>()
        var t = 0f; val dt = 1f/60f; var k = 0
        while (!w.dead && t < 300f) {
            val held = w.autopilot(); w.update(dt, held); t += dt; k++
            if (k % 6 == 0) { val an = w.anchor
                log.add("t=%.1f h=%b pos=(%.0f,%.0f) v=(%.0f,%.0f) ph=%d anc=%s cam=%.0f bottom=%.0f".format(t, held, w.x, w.y, w.vx, w.vy, w.phase, if (an==null) "-" else "(%.0f,%.0f c%d k%d)".format(an.x, an.y, an.color, an.kind), w.camY, w.camY + w.viewH))
            }
        }
        for (s in log.takeLast(30)) println(s)
        println("anchors near:")
        for (an in w.anchors.sortedBy { -it.y }) if (an.y > w.y - 900 && an.y < w.y + 900) println("  (%.0f,%.0f) col=%d kind=%d alive=%b".format(an.x, an.y, an.color, an.kind, an.alive))
        println("gates: ${w.gates} pruned=${w.prunedGates} hazards near: " + w.hazards.filter { Math.abs(it.y - w.y) < 900 }.joinToString { "k${it.kind}(%.0f,%.0f)".format(it.x, it.y) })
    }
}
