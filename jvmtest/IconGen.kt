import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.pranvir.tether.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

object IconGen {
    fun art(c: Canvas, s: Float, safe: Float, bg: Boolean, mono: Boolean = false) {
        if (bg) {
            val p = Paint()
            p.shader = LinearGradient(0f, 0f, 0f, s, intArrayOf(0xFF140A30.toInt(), 0xFF3A1C6C.toInt(), 0xFFC9607A.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, s, s, p)
            // striped sun low on the horizon
            val r = s * 0.3f; val cy = s * 0.86f
            Draw.glow(c, s / 2f, cy, r * 2f, 0.5f, 0xFFFF5FA8.toInt())
            val sun = Paint(); sun.shader = LinearGradient(0f, cy - r, 0f, cy + r, intArrayOf(0xFFFFE27A.toInt(), 0xFFFF3FA4.toInt()), null, Shader.TileMode.CLAMP)
            c.save(); val cl = Path(); cl.addCircle(s / 2f, cy, r, Path.Direction.CW); c.clipPath(cl)
            c.drawRect(0f, cy - r, s, cy, sun)
            var y = cy; var k = 0
            while (y < cy + r) { y += r * (0.05f + k * 0.03f); c.drawRect(0f, y, s, y + r * 0.1f, sun); y += r * 0.1f; k++ }
            c.restore()
        }
        val k = safe / 100f
        val cx = s / 2f; val cy = s / 2f
        val ax = cx + 18f * k; val ay = cy - 30f * k
        val ox = cx - 22f * k; val oy = cy + 22f * k
        val col = if (mono) 0xFFFFFFFF.toInt() else Col.CYAN
        // comet trail arc around the anchor
        val st = Draw.stroke
        val rr = Math.hypot((ox - ax).toDouble(), (oy - ay).toDouble()).toFloat()
        Draw.r1.set(ax - rr, ay - rr, ax + rr, ay + rr)
        val a0 = Math.toDegrees(Math.atan2((oy - ay).toDouble(), (ox - ax).toDouble())).toFloat()
        for (i in 0 until 24) {
            val f = i / 23f
            st.color = withAlpha(col, if (mono) 255 else (60 + 195 * f).toInt())
            st.strokeWidth = 3f * k + 14f * k * f
            c.drawArc(Draw.r1, a0 + 75f - i * 3.2f, -4f, false, st)
        }
        st.color = if (mono) 0xFFFFFFFF.toInt() else Col.WHITE; st.strokeWidth = 4f * k
        c.drawLine(ox, oy, ax, ay, st)
        if (!mono) Draw.glow(c, ax, ay, 22f * k, 0.9f, Col.MAGENTA)
        st.color = if (mono) 0xFFFFFFFF.toInt() else Col.MAGENTA; st.strokeWidth = 4f * k
        c.drawCircle(ax, ay, 9f * k, st)
        Draw.fill.color = 0xFFFFFFFF.toInt(); c.drawCircle(ax, ay, 4f * k, Draw.fill)
        if (!mono) Draw.glow(c, ox, oy, 40f * k, 1f, Col.CYAN)
        Draw.fill.color = col; c.drawCircle(ox, oy, 13f * k, Draw.fill)
        Draw.fill.color = 0xFFFFFFFF.toInt(); c.drawCircle(ox, oy, 9f * k, Draw.fill)
    }
    fun save(img: BufferedImage, path: String) { val f = File(path); f.parentFile.mkdirs(); ImageIO.write(img, "png", f) }

    @JvmStatic fun main(a: Array<String>) {
        val res = "/home/claude/tether/res"
        for ((dn, k) in linkedMapOf("mdpi" to 1f, "hdpi" to 1.5f, "xhdpi" to 2f, "xxhdpi" to 3f, "xxxhdpi" to 4f)) {
            val fs = (108 * k).toInt()
            val fg = BufferedImage(fs, fs, BufferedImage.TYPE_INT_ARGB)
            art(Canvas(fg), fs.toFloat(), fs * 0.62f, true)
            save(fg, "$res/mipmap-$dn/ic_launcher_fg.png")
            val mono = BufferedImage(fs, fs, BufferedImage.TYPE_INT_ARGB)
            art(Canvas(mono), fs.toFloat(), fs * 0.62f, false, true)
            save(mono, "$res/mipmap-$dn/ic_launcher_mono.png")
            val ls = (48 * k).toInt()
            for (round in listOf(false, true)) {
                val big = ls * 4
                val img = BufferedImage(big, big, BufferedImage.TYPE_INT_ARGB)
                val c = Canvas(img)
                val clip = Path(); val inset = big * 0.04f
                if (round) clip.addCircle(big / 2f, big / 2f, big / 2f - inset, Path.Direction.CW)
                else { clip.addCircle(big / 2f, big / 2f, big / 2f - inset, Path.Direction.CW) }
                c.save(); c.clipPath(clip); art(c, big.toFloat(), big * 0.82f, true); c.restore()
                val out = BufferedImage(ls, ls, BufferedImage.TYPE_INT_ARGB)
                out.createGraphics().drawImage(img.getScaledInstance(ls, ls, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null)
                save(out, "$res/mipmap-$dn/" + (if (round) "ic_launcher_round.png" else "ic_launcher.png"))
            }
        }
        val prev = BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB)
        art(Canvas(prev), 512f, 512f * 0.8f, true)
        save(prev, "/home/claude/tether/shots/icon512.png")
        println("ICONS OK")
    }
}
