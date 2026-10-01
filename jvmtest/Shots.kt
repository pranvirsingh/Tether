import android.graphics.Canvas
import android.graphics.Typeface
import com.pranvir.tether.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class FakeHost : Host {
    val ints = HashMap<String, Int>()
    var sounds = 0
    var lastScene = -1
    override fun loadInt(key: String, def: Int) = ints[key] ?: def
    override fun saveInt(key: String, v: Int) { ints[key] = v }
    override fun sound(id: Int, vol: Float, rate: Float) { check(id in 0 until Sfx.COUNT); sounds++ }
    override fun setAudio(soundOn: Boolean, musicOn: Boolean) {}
    override fun setMusicState(scene: Int, combo: Int, intensity: Float, slow: Boolean, dead: Boolean) { lastScene = scene; check(!intensity.isNaN()) }
    override fun haptic(strong: Boolean) {}
    override fun today() = 20261001
}

object Shots {
    fun fonts() {
        Fonts.med = Typeface.createFromFile("/home/claude/tether/assets/fonts/sg_med.ttf")
        Fonts.bold = Typeface.createFromFile("/home/claude/tether/assets/fonts/sg_bold.ttf")
    }
    val W = 540; val H = 1170
    fun shot(g: Game, name: String) {
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB)
        val c = Canvas(img)
        g.draw(c)
        check(c.saveCount == 1) { "unbalanced save in $name" }
        ImageIO.write(img, "png", File("/home/claude/tether/shots/$name.png"))
    }
    fun tap(g: Game, id: Int) { val o = FloatArray(2); check(g.debugBtn(id, o)) { "no button $id in ${g.visibleButtons()}" }; g.touchDown(o[0], o[1]); g.touchUp(o[0], o[1]) }
    fun run(g: Game, sec: Float, auto: Boolean = true) {
        var t = 0f
        while (t < sec) {
            if (auto && g.mode == Game.M_PLAY) { val want = g.world.autopilot(); if (want) g.touchDown(500f, 900f) else g.touchUp(500f, 900f) }
            g.update(1f / 60f); t += 1f / 60f
        }
    }

    @JvmStatic fun main(a: Array<String>) {
        fonts()
        val host = FakeHost()
        val g = Game(host)
        g.resize(W, H, 1.5f); g.setInsets(0, 30, 0, 20)
        run(g, 0.5f); shot(g, "01_home")
        run(g, 4f); shot(g, "02_home_attract")
        host.ints["motes"] = 0
        tap(g, Game.B_STYLE); run(g, 0.6f); g.debugMotes(420); shot(g, "03_style_orb")
        tap(g, Game.B_ITEM0 + 2); run(g, 0.3f)
        tap(g, Game.B_TAB1); run(g, 0.4f); shot(g, "04_style_trail")
        tap(g, Game.B_BACK); run(g, 0.3f)
        tap(g, Game.B_PLAY); run(g, 0.5f, false); shot(g, "05_play_start")
        run(g, 1.6f); shot(g, "06_play_spin")
        // climb into each zone
        val marks = floatArrayOf(330f, 760f, 1260f, 1860f)
        val names = arrayOf("07_nebula", "08_aurora", "09_eclipse", "10_singularity")
        g.world.godMode = true
        var k = 0
        var guard = 0
        while (k < marks.size && guard < 60 * 400) {
            run(g, 1f / 60f); guard++
            if (g.world.heightM > marks[k]) { run(g, 0.9f); shot(g, names[k]); k++ }
        }
        println("reached ${g.world.heightM} m in ${guard / 60}s, k=$k")
        g.world.godMode = false
        tap(g, Game.B_PAUSE); run(g, 0.3f, false); shot(g, "11_pause")
        tap(g, Game.B_RESUME)
        // die: stop holding and fall
        var t = 0
        while (g.mode == Game.M_PLAY && t < 60 * 30) { g.touchUp(1f, 1f); g.update(1f / 60f); t++ }
        run(g, 2.0f, false); shot(g, "12_result")
        println("mode=${g.mode} best=${g.best} motes=${g.motes} sounds=${host.sounds}")
    }
}
