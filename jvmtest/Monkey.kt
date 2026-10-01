import android.graphics.Canvas
import com.pranvir.tether.*
import java.awt.image.BufferedImage
import java.util.Random

object Monkey {
    @JvmStatic fun main(a: Array<String>) {
        Shots.fonts()
        val rnd = Random(if (a.size > 1) a[1].toLong() else 42L)
        val host = FakeHost()
        var g = Game(host)
        val W = 360; val H = 780
        g.resize(W, H, 1f); g.setInsets(0, 24, 0, 16)
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB)
        val c = Canvas(img)
        var down = false
        val modes = IntArray(4)
        var restores = 0; var frames = 0
        val steps = if (a.isNotEmpty()) a[0].toInt() else 60000
        for (i in 0 until steps) {
            val r = rnd.nextFloat()
            val x = rnd.nextFloat() * W; val y = rnd.nextFloat() * H
            if (rnd.nextFloat() < 0.012f && !down) {
                val vis = g.visibleButtons()
                if (vis.isNotEmpty()) { val o = FloatArray(2); g.debugBtn(vis[rnd.nextInt(vis.size)], o); g.touchDown(o[0], o[1]); g.update(0.016f); g.touchUp(o[0], o[1]) }
            }
            if (rnd.nextFloat() < 0.0005f) g.debugMotes(rnd.nextInt(3000))
            when {
                r < 0.04f && !down -> { g.touchDown(x, y); down = true }
                r < 0.08f && down -> { g.touchUp(x, y); down = false }
                r < 0.09f -> g.touchMove(x, y)
                r < 0.092f -> { g.touchCancel(); down = false }
                r < 0.0935f -> g.onBack()
                r < 0.0945f -> { g.onPause() }
                r < 0.0950f -> { // process death + restore
                    g.onPause(); g = Game(host); g.resize(W, H, 1f); g.setInsets(0, 24, 0, 16); down = false; restores++
                }
                r < 0.0952f -> g.resize(W, H, 1f)
            }
            // sometimes play properly so we get deep into runs
            if (g.mode == Game.M_PLAY && (i / 3000) % 2 == 0) {
                val want = g.world.autopilot()
                if (want && !down) { g.touchDown(W / 2f, H * 0.6f); down = true } else if (!want && down) { g.touchUp(W / 2f, H * 0.6f); down = false }
            }
            val dt = if (rnd.nextFloat() < 0.01f) rnd.nextFloat() * 0.5f else 1f / 60f
            g.update(dt)
            if (i % 7 == 0) { g.draw(c); check(c.saveCount == 1) { "save leak mode ${g.mode}" }; frames++ }
            modes[g.mode]++
            val w = g.world
            check(!w.x.isNaN() && !w.y.isNaN() && !w.vx.isNaN() && !w.vy.isNaN()) { "NaN at step $i" }
            check(w.x >= 0f && w.x <= 1000f) { "x out ${w.x}" }
            check(g.motes >= 0) { "motes negative" }
            check(g.orbSkin in 0..5 && g.trailStyle in 0..5)
            check(g.unlocked and (1 shl g.orbSkin) != 0 && g.unlocked and (1 shl (8 + g.trailStyle)) != 0) { "equipped locked item" }
            check(w.anchors.size < 200 && w.hazards.size < 200 && w.motesList.size < 400) { "leak" }
        }
        println("MONKEY OK steps=$steps frames=$frames restores=$restores modes=${modes.toList()} best=${g.best} motes=${g.motes} unlocked=${Integer.toBinaryString(g.unlocked)}")
    }
}
