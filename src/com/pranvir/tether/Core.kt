package com.pranvir.tether

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v
fun smooth(t: Float): Float { val x = clamp01(t); return x * x * (3f - 2f * x) }
fun approach(v: Float, target: Float, rate: Float) = if (v < target) min(target, v + rate) else max(target, v - rate)
fun easeOutBack(x: Float): Float { val c1 = 1.70158f; val c3 = c1 + 1f; val t = x - 1f; return 1f + c3 * t * t * t + c1 * t * t }

fun lerpColor(a: Int, b: Int, t: Float): Int {
    val tt = clamp01(t)
    val aa = (a ushr 24) and 255; val ar = (a shr 16) and 255; val ag = (a shr 8) and 255; val ab = a and 255
    val ba = (b ushr 24) and 255; val br = (b shr 16) and 255; val bg = (b shr 8) and 255; val bb = b and 255
    return ((aa + (ba - aa) * tt).toInt() shl 24) or ((ar + (br - ar) * tt).toInt() shl 16) or
        ((ag + (bg - ag) * tt).toInt() shl 8) or (ab + (bb - ab) * tt).toInt()
}
fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)
fun alphaF(c: Int, f: Float): Int = withAlpha(c, (((c ushr 24) and 255) * clamp01(f)).toInt())

/** Hue 0..360, s and v 0..1. */
fun hsv(h: Float, s: Float, v: Float): Int {
    val hh = ((h % 360f) + 360f) % 360f / 60f
    val i = hh.toInt(); val f = hh - i
    val p = v * (1f - s); val q = v * (1f - s * f); val t = v * (1f - s * (1f - f))
    val r: Float; val g: Float; val b: Float
    when (i) {
        0 -> { r = v; g = t; b = p }
        1 -> { r = q; g = v; b = p }
        2 -> { r = p; g = v; b = t }
        3 -> { r = p; g = q; b = v }
        4 -> { r = t; g = p; b = v }
        else -> { r = v; g = p; b = q }
    }
    return (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
}

/** Cheap deterministic hash to [0,1). */
fun hash01(a: Int, b: Int): Float {
    var h = a * 374761393 + b * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xFFFFFF) / 16777216f
}

object Col {
    const val WHITE = 0xFFF4F1FF.toInt()
    const val CYAN = 0xFF3EF2FF.toInt()
    const val MAGENTA = 0xFFFF4FD8.toInt()
    const val GOLD = 0xFFFFD66B.toInt()
    const val DANGER = 0xFFFF5A5F.toInt()
    const val INK = 0xFF07060F.toInt()
    const val GLASS = 0x26FFFFFF
    const val GLASS_EDGE = 0x55FFFFFF
    fun phase(p: Int) = when (p) { 1 -> CYAN; 2 -> MAGENTA; else -> WHITE }
}

class ZoneTheme(val name: String, val startM: Float, val top: Int, val bottom: Int, val blobA: Int, val blobB: Int, val accent: Int)

object Zones {
    val ALL = arrayOf(
        ZoneTheme("DAWNLINE", 0f, 0xFF1E1240.toInt(), 0xFFC9607A.toInt(), 0xFFFF9A7B.toInt(), 0xFF8A5CF6.toInt(), 0xFFFFB38A.toInt()),
        ZoneTheme("NEBULA", 300f, 0xFF0A0A26.toInt(), 0xFF3A1C6C.toInt(), 0xFFB14AED.toInt(), 0xFF3A7BFF.toInt(), 0xFFC89BFF.toInt()),
        ZoneTheme("AURORA", 700f, 0xFF03121A.toInt(), 0xFF0D3A44.toInt(), 0xFF2DE2A6.toInt(), 0xFF4FB3FF.toInt(), 0xFF7CFFD4.toInt()),
        ZoneTheme("ECLIPSE", 1200f, 0xFF050406.toInt(), 0xFF1E160A.toInt(), 0xFFFFB547.toInt(), 0xFF5A3A14.toInt(), 0xFFFFCF7A.toInt()),
        ZoneTheme("SINGULARITY", 1800f, 0xFF07020F.toInt(), 0xFF1C0B33.toInt(), 0xFFFF4FD8.toInt(), 0xFF4FFFF0.toInt(), 0xFFE7B8FF.toInt())
    )
    fun index(m: Float): Int { var z = 0; for (i in ALL.indices) if (m >= ALL[i].startM) z = i; return z }
    /** Blend factor towards the next zone over the last 60 m of a zone. */
    fun blend(m: Float): Float {
        val z = index(m)
        if (z >= ALL.size - 1) return 0f
        val next = ALL[z + 1].startM
        return clamp01((m - (next - 60f)) / 60f)
    }
}

object Fonts {
    var med: Typeface = Typeface.DEFAULT
    var bold: Typeface = Typeface.DEFAULT_BOLD
}

object Draw {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    val glowP = Paint(Paint.ANTI_ALIAS_FLAG)
    val grad = Paint()
    val path = Path()
    val r1 = RectF()
    val r2 = RectF()
    private val fm = Paint.FontMetrics()
    private val glowCache = HashMap<Int, RadialGradient>()

    fun glow(c: Canvas, x: Float, y: Float, r: Float, strength: Float, col: Int) {
        if (strength <= 0.01f || r <= 1f) return
        var sh = glowCache[col]
        if (sh == null) {
            sh = RadialGradient(0f, 0f, 1f, intArrayOf(withAlpha(col, 200), withAlpha(col, 70), withAlpha(col, 18), withAlpha(col, 0)),
                floatArrayOf(0f, 0.25f, 0.6f, 1f), Shader.TileMode.CLAMP)
            glowCache[col] = sh
        }
        glowP.shader = sh
        glowP.alpha = (255 * clamp01(strength)).toInt()
        // shaders stay immutable; the canvas carries the transform (safe on every HWUI version)
        c.save(); c.translate(x, y); c.scale(r, r)
        c.drawCircle(0f, 0f, 1f, glowP)
        c.restore()
    }

    /** Fill screen rect l,t,r,b with a unit-space shader placed at (ox,oy) scaled by (sx,sy). */
    fun shadeRect(c: Canvas, sh: Shader, l: Float, t: Float, r: Float, b: Float, ox: Float, oy: Float, sx: Float, sy: Float, alpha: Int = 255) {
        val kx = max(sx, 0.001f); val ky = max(sy, 0.001f)
        grad.shader = sh; grad.alpha = alpha
        c.save(); c.translate(ox, oy); c.scale(kx, ky)
        c.drawRect((l - ox) / kx, (t - oy) / ky, (r - ox) / kx, (b - oy) / ky, grad)
        c.restore()
        grad.shader = null; grad.alpha = 255
    }

    fun shadeRound(c: Canvas, sh: Shader, rc: RectF, rad: Float, ox: Float, oy: Float, sx: Float, sy: Float) {
        val kx = max(sx, 0.001f); val ky = max(sy, 0.001f)
        grad.shader = sh; grad.alpha = 255
        c.save(); c.translate(ox, oy); c.scale(kx, ky)
        c.drawRoundRect((rc.left - ox) / kx, (rc.top - oy) / ky, (rc.right - ox) / kx, (rc.bottom - oy) / ky, rad / kx, rad / ky, grad)
        c.restore()
        grad.shader = null
    }

    fun text(c: Canvas, s: String, x: Float, cy: Float, size: Float, col: Int, face: Typeface = Fonts.bold, align: Paint.Align = Paint.Align.CENTER, spacing: Float = 0f) {
        text.typeface = face; text.textSize = size; text.color = col; text.textAlign = align
        text.letterSpacing = spacing
        text.getFontMetrics(fm)
        c.drawText(s, x, cy - (fm.ascent + fm.descent) / 2f, text)
        text.textAlign = Paint.Align.CENTER
        text.letterSpacing = 0f
    }

    fun width(s: String, size: Float, face: Typeface = Fonts.bold, spacing: Float = 0f): Float {
        text.typeface = face; text.textSize = size; text.letterSpacing = spacing
        val w = text.measureText(s)
        text.letterSpacing = 0f
        return w
    }

    fun fit(s: String, size: Float, maxW: Float, face: Typeface = Fonts.bold, spacing: Float = 0f): Float {
        val w = width(s, size, face, spacing)
        return if (w <= maxW || w <= 0f) size else size * maxW / w
    }

    private val glassSh = LinearGradient(0f, 0f, 0f, 1f, intArrayOf(0x2AFFFFFF, 0x06FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)

    /** Frosted glass card. */
    fun glass(c: Canvas, r: RectF, rad: Float, u: Float, tint: Int = Col.GLASS, edge: Int = Col.GLASS_EDGE) {
        fill.color = 0x40000000
        c.drawRoundRect(r.left, r.top + u * 0.8f, r.right, r.bottom + u * 1.2f, rad, rad, fill)
        fill.color = tint
        c.drawRoundRect(r, rad, rad, fill)
        shadeRound(c, glassSh, r, rad, 0f, r.top, 1f, max(1f, r.bottom - r.top))
        stroke.color = edge; stroke.strokeWidth = u * 0.25f
        c.drawRoundRect(r, rad, rad, stroke)
    }

    fun star(c: Canvas, x: Float, y: Float, r: Float, col: Int) {
        path.reset()
        for (k in 0 until 10) {
            val a = -PI / 2 + k * PI / 5
            val rr = if (k % 2 == 0) r else r * 0.45f
            val px = x + (cos(a) * rr).toFloat(); val py = y + (sin(a) * rr).toFloat()
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        fill.color = col
        c.drawPath(path, fill)
    }
}

object Icon {
    const val PAUSE = 1; const val PLAY = 2; const val HOME = 3; const val RETRY = 4; const val SOUND = 5; const val MUSIC = 6
    const val VIBE = 7; const val STYLE = 8; const val CAL = 9; const val CLOSE = 10; const val MOTE = 11; const val LOCK = 12; const val CHECK = 13
    private val p = Path()

    fun draw(c: Canvas, id: Int, x: Float, y: Float, s: Float, col: Int, off: Boolean = false) {
        val f = Draw.fill; val st = Draw.stroke
        f.color = col; st.color = col; st.strokeWidth = s * 0.09f
        when (id) {
            PAUSE -> { c.drawRoundRect(x - s * 0.24f, y - s * 0.28f, x - s * 0.08f, y + s * 0.28f, s * 0.05f, s * 0.05f, f); c.drawRoundRect(x + s * 0.08f, y - s * 0.28f, x + s * 0.24f, y + s * 0.28f, s * 0.05f, s * 0.05f, f) }
            PLAY -> { p.reset(); p.moveTo(x - s * 0.18f, y - s * 0.3f); p.lineTo(x + s * 0.3f, y); p.lineTo(x - s * 0.18f, y + s * 0.3f); p.close(); c.drawPath(p, f) }
            HOME -> {
                p.reset(); p.moveTo(x - s * 0.32f, y - s * 0.02f); p.lineTo(x, y - s * 0.3f); p.lineTo(x + s * 0.32f, y - s * 0.02f); c.drawPath(p, st)
                c.drawRoundRect(x - s * 0.22f, y - s * 0.06f, x + s * 0.22f, y + s * 0.3f, s * 0.04f, s * 0.04f, st)
            }
            RETRY -> {
                Draw.r2.set(x - s * 0.28f, y - s * 0.28f, x + s * 0.28f, y + s * 0.28f); c.drawArc(Draw.r2, -60f, 300f, false, st)
                val a = Math.toRadians(-60.0); val ax = x + (cos(a) * s * 0.28f).toFloat(); val ay = y + (sin(a) * s * 0.28f).toFloat()
                p.reset(); p.moveTo(ax + s * 0.02f, ay - s * 0.2f); p.lineTo(ax + s * 0.16f, ay + s * 0.06f); p.lineTo(ax - s * 0.12f, ay + s * 0.06f); p.close(); c.drawPath(p, f)
            }
            SOUND -> {
                p.reset(); p.moveTo(x - s * 0.32f, y - s * 0.1f); p.lineTo(x - s * 0.16f, y - s * 0.1f); p.lineTo(x + s * 0.04f, y - s * 0.28f)
                p.lineTo(x + s * 0.04f, y + s * 0.28f); p.lineTo(x - s * 0.16f, y + s * 0.1f); p.lineTo(x - s * 0.32f, y + s * 0.1f); p.close(); c.drawPath(p, f)
                if (!off) { Draw.r2.set(x - s * 0.08f, y - s * 0.2f, x + s * 0.28f, y + s * 0.2f); c.drawArc(Draw.r2, -50f, 100f, false, st) }
            }
            MUSIC -> {
                c.drawCircle(x - s * 0.14f, y + s * 0.2f, s * 0.11f, f); c.drawCircle(x + s * 0.2f, y + s * 0.13f, s * 0.11f, f)
                c.drawLine(x - s * 0.04f, y + s * 0.2f, x - s * 0.04f, y - s * 0.28f, st); c.drawLine(x + s * 0.3f, y + s * 0.13f, x + s * 0.3f, y - s * 0.34f, st)
                st.strokeWidth = s * 0.13f; c.drawLine(x - s * 0.04f, y - s * 0.26f, x + s * 0.3f, y - s * 0.33f, st)
            }
            VIBE -> {
                c.drawRoundRect(x - s * 0.13f, y - s * 0.26f, x + s * 0.13f, y + s * 0.26f, s * 0.05f, s * 0.05f, st)
                if (!off) { c.drawLine(x - s * 0.26f, y - s * 0.12f, x - s * 0.26f, y + s * 0.12f, st); c.drawLine(x + s * 0.26f, y - s * 0.12f, x + s * 0.26f, y + s * 0.12f, st) }
            }
            STYLE -> {
                c.drawCircle(x, y, s * 0.12f, f)
                st.strokeWidth = s * 0.07f
                Draw.r2.set(x - s * 0.3f, y - s * 0.3f, x + s * 0.3f, y + s * 0.3f); c.drawArc(Draw.r2, 200f, 250f, false, st)
                c.drawCircle(x + s * 0.2f, y - s * 0.22f, s * 0.05f, f)
            }
            CAL -> {
                c.drawRoundRect(x - s * 0.28f, y - s * 0.22f, x + s * 0.28f, y + s * 0.28f, s * 0.06f, s * 0.06f, st)
                c.drawLine(x - s * 0.28f, y - s * 0.06f, x + s * 0.28f, y - s * 0.06f, st)
                c.drawLine(x - s * 0.14f, y - s * 0.32f, x - s * 0.14f, y - s * 0.16f, st); c.drawLine(x + s * 0.14f, y - s * 0.32f, x + s * 0.14f, y - s * 0.16f, st)
                c.drawCircle(x, y + s * 0.1f, s * 0.05f, f)
            }
            CLOSE -> { c.drawLine(x - s * 0.22f, y - s * 0.22f, x + s * 0.22f, y + s * 0.22f, st); c.drawLine(x + s * 0.22f, y - s * 0.22f, x - s * 0.22f, y + s * 0.22f, st) }
            MOTE -> {
                Draw.glow(c, x, y, s * 0.6f, 0.7f, Col.GOLD)
                p.reset(); p.moveTo(x, y - s * 0.3f); p.lineTo(x + s * 0.2f, y); p.lineTo(x, y + s * 0.3f); p.lineTo(x - s * 0.2f, y); p.close()
                f.color = Col.GOLD; c.drawPath(p, f)
            }
            LOCK -> {
                c.drawRoundRect(x - s * 0.22f, y - s * 0.04f, x + s * 0.22f, y + s * 0.3f, s * 0.05f, s * 0.05f, f)
                Draw.r2.set(x - s * 0.14f, y - s * 0.3f, x + s * 0.14f, y + s * 0.02f); c.drawArc(Draw.r2, 180f, 180f, false, st)
                c.drawLine(x - s * 0.14f, y - s * 0.14f, x - s * 0.14f, y - s * 0.04f, st); c.drawLine(x + s * 0.14f, y - s * 0.14f, x + s * 0.14f, y - s * 0.04f, st)
            }
            CHECK -> { st.strokeWidth = s * 0.12f; c.drawLine(x - s * 0.24f, y, x - s * 0.06f, y + s * 0.18f, st); c.drawLine(x - s * 0.06f, y + s * 0.18f, x + s * 0.26f, y - s * 0.2f, st) }
        }
        if (off && id != SOUND && id != VIBE) { st.strokeWidth = s * 0.08f; c.drawLine(x - s * 0.34f, y - s * 0.34f, x + s * 0.34f, y + s * 0.34f, st) }
        if (off && id == SOUND) { st.strokeWidth = s * 0.08f; c.drawLine(x + s * 0.12f, y - s * 0.14f, x + s * 0.36f, y + s * 0.14f, st); c.drawLine(x + s * 0.36f, y - s * 0.14f, x + s * 0.12f, y + s * 0.14f, st) }
    }
}
