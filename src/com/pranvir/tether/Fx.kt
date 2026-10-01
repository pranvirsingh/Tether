package com.pranvir.tether

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Cosmetics. Prices in motes. */
object Styles {
    val ORBS = arrayOf("CORE", "RING", "STAR", "EYE", "HEX", "NOVA")
    val TRAILS = arrayOf("COMET", "RIBBON", "SPARK", "PIXEL", "PRISM", "ECHO")
    val PRICE = intArrayOf(0, 150, 300, 500, 800, 1200)
}

/** All world and background drawing. World units; the caller sets up the camera transform. */
object Fx {
    private val p = Draw.fill
    private val st = Draw.stroke
    private val path = Path()
    private val m = Matrix()
    private const val DEG = 57.29578f

    // ------------------------------------------------------------------ background (screen space)

    private var bgSh: LinearGradient? = null
    private var bgTop = 0; private var bgBot = 0; private var bgH = 0
    private val sunSh = LinearGradient(0f, 0f, 0f, 1f, intArrayOf(0xFFFFE27A.toInt(), 0xFFFF7A7A.toInt(), 0xFFFF3FA4.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    private val groundSh = LinearGradient(0f, 0f, 0f, 1f, intArrayOf(0xFF1A0B2E.toInt(), 0xFF07060F.toInt()), null, Shader.TileMode.CLAMP)
    private val darkSh = RadialGradient(0f, 0f, 1f, intArrayOf(0x00000000, 0x00000000, 0x80020106.toInt(), 0xE6020106.toInt()), floatArrayOf(0f, 0.38f, 0.68f, 1f), Shader.TileMode.CLAMP)
    private val voidSh = LinearGradient(0f, 0f, 0f, 1f, intArrayOf(0x00000000, 0xB0050210.toInt(), 0xF0050210.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    private val vignSh = RadialGradient(0f, 0f, 1f, intArrayOf(0x00000000, 0x00000000, 0x66000000), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)

    fun zoneColors(mh: Float, out: IntArray) {
        val m0 = max(0f, mh)
        val z = Zones.index(m0)
        val b = (Zones.blend(m0) * 24f).toInt() / 24f
        val a = Zones.ALL[z]
        val n = Zones.ALL[min(z + 1, Zones.ALL.size - 1)]
        out[0] = lerpColor(a.top, n.top, b); out[1] = lerpColor(a.bottom, n.bottom, b)
        out[2] = lerpColor(a.blobA, n.blobA, b); out[3] = lerpColor(a.blobB, n.blobB, b); out[4] = lerpColor(a.accent, n.accent, b)
    }
    val zc = IntArray(5)

    /**
     * camY: world y at the top of the screen. s: pixels per world unit. vh: visible world height.
     * camY0: camera at the start of the run (for the horizon).
     */
    fun background(c: Canvas, w: Float, h: Float, s: Float, camY: Float, vh: Float, t: Float, camY0: Float) {
        val mh = -(camY + vh * 0.5f) / 10f
        zoneColors(mh, zc)
        var sh = bgSh
        if (sh == null || bgTop != zc[0] || bgBot != zc[1] || bgH != h.toInt()) {
            sh = LinearGradient(0f, 0f, 0f, h, intArrayOf(zc[0], zc[1]), null, Shader.TileMode.CLAMP)
            bgSh = sh; bgTop = zc[0]; bgBot = zc[1]; bgH = h.toInt()
        }
        Draw.grad.shader = sh
        c.drawRect(0f, 0f, w, h, Draw.grad)
        Draw.grad.shader = null

        // slow aurora blobs
        for (k in 0 until 3) {
            val col = zc[2 + k]
            val bx = w * (0.5f + 0.38f * sin(t * 0.05f + k * 2.1f))
            val by = h * (0.3f + 0.25f * k) + h * 0.12f * sin(t * 0.07f + k * 1.3f) + ((-camY * 0.03f * s) % (h * 0.4f))
            Draw.glow(c, bx, by, w * (0.62f + 0.1f * k), 0.42f, col)
        }

        // dust
        dust(c, w, h, s, camY, t)
        // parallax line geometry
        shapes(c, w, s, camY, vh, t, 0.22f, 0x1A, 760f, 1)
        shapes(c, w, s, camY, vh, t, 0.45f, 0x2A, 620f, 2)

        // synthwave horizon near the ground
        val drop = (camY0 - camY) * 0.35f * s
        val sunY = h * 0.80f + drop
        val r = w * 0.30f
        if (sunY - r < h) horizon(c, w, h, sunY, r, t)
    }

    private fun dust(c: Canvas, w: Float, h: Float, s: Float, camY: Float, t: Float) {
        val par = 0.12f
        val cell = 260f
        val v = camY * par
        val i0 = floor(v / cell).toInt() - 1
        val i1 = i0 + (h / s / cell).toInt() + 3
        p.color = 0x55FFFFFF
        for (i in i0..i1) {
            for (k in 0 until 4) {
                val x = hash01(i, k * 7 + 1) * 1000f
                val y = i * cell + hash01(i, k * 7 + 2) * cell
                val sy = (y - v) * s
                if (sy < -4f || sy > h + 4f) continue
                val tw = 0.5f + 0.5f * sin(t * (1f + hash01(i, k) * 2f) + k)
                p.alpha = (40 + 90 * tw).toInt()
                c.drawCircle(x * s, sy, (1.2f + hash01(i, k * 3) * 1.8f) * s, p)
            }
        }
    }

    private fun shapes(c: Canvas, w: Float, s: Float, camY: Float, vh: Float, t: Float, par: Float, alpha: Int, cell: Float, layer: Int) {
        val v = camY * par
        val i0 = floor(v / cell).toInt() - 1
        val i1 = i0 + (vh / cell).toInt() + 3
        st.strokeWidth = (1.6f + layer * 0.6f) * s
        st.color = withAlpha(0xFFFFFFFF.toInt(), alpha)
        for (i in i0..i1) {
            val hx = hash01(i, 11 * layer)
            val kind = (hash01(i, 13 * layer) * 4f).toInt()
            val size = (50f + hash01(i, 17 * layer) * 80f) * (0.7f + layer * 0.3f)
            val x = (hx * 1000f) * s
            val y = (i * cell + hash01(i, 19 * layer) * cell - v) * s
            val rot = t * (hash01(i, 23 * layer) - 0.5f) * 0.6f + hash01(i, 29) * 6f
            val rr = size * s
            c.save()
            c.translate(x, y)
            c.rotate(rot * DEG)
            when (kind) {
                0 -> c.drawCircle(0f, 0f, rr * 0.7f, st)
                1 -> poly(c, 3, rr)
                2 -> c.drawRect(-rr * 0.6f, -rr * 0.6f, rr * 0.6f, rr * 0.6f, st)
                else -> poly(c, 6, rr * 0.8f)
            }
            c.restore()
        }
    }

    private fun poly(c: Canvas, n: Int, r: Float, fill: Boolean = false) {
        path.reset()
        for (k in 0 until n) {
            val a = -1.5708f + k * 6.2832f / n
            val px = cos(a) * r; val py = sin(a) * r
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        c.drawPath(path, if (fill) p else st)
    }

    private fun horizon(c: Canvas, w: Float, h: Float, sunY: Float, r: Float, t: Float) {
        val cx = w * 0.5f
        Draw.glow(c, cx, sunY, r * 2.1f, 0.55f, 0xFFFF5FA8.toInt())
        // striped sun
        c.save()
        path.reset(); path.addCircle(cx, sunY, r, Path.Direction.CW)
        c.clipPath(path)
        val bottom = sunY + r
        val gapStart = sunY - r * 0.05f
        Draw.shadeRect(c, sunSh, cx - r, sunY - r, cx + r, gapStart, 0f, sunY - r, 1f, r * 2f)
        var y = gapStart
        var k = 0
        while (y < bottom) {
            y += r * (0.03f + k * 0.018f)
            val band = r * (0.12f - k * 0.008f).coerceAtLeast(0.04f)
            if (y < bottom) Draw.shadeRect(c, sunSh, cx - r, y, cx + r, min(bottom, y + band), 0f, sunY - r, 1f, r * 2f)
            y += band
            k++
        }
        c.restore()
        // ground with a scrolling perspective grid
        val hy = sunY + r * 0.42f
        if (hy < h) {
            Draw.shadeRect(c, groundSh, 0f, hy, w, h + 2f, 0f, hy, 1f, max(1f, h - hy))
            st.color = 0xCCFF4FD8.toInt(); st.strokeWidth = w * 0.004f
            c.drawLine(0f, hy, w, hy, st)
            st.color = 0x88FF4FD8.toInt(); st.strokeWidth = w * 0.0025f
            val depth = max(h - hy, 1f) * 1.4f
            val off = (t * 0.35f) % 1f
            for (i in 0 until 12) {
                val f = (i + off) / 12f
                val yy = hy + depth * f * f
                if (yy > h) break
                c.drawLine(0f, yy, w, yy, st)
            }
            for (i in -8..8) {
                val bx = cx + i * w * 0.09f
                c.drawLine(cx + i * w * 0.012f, hy, cx + (bx - cx) * 6f, hy + depth * 1.2f, st)
            }
        }
    }

    // ------------------------------------------------------------------ world

    fun world(c: Canvas, wd: World, t: Float, skin: Int, trail: Int, best: Float, vh: Float, showAim: Boolean) {
        val top = wd.camY - 60f
        val bot = wd.camY + vh + 60f
        val pcol = Col.phase(wd.phase)

        for (g in wd.gates) if (g > top && g < bot) gate(c, g, t, wd.phase)
        if (best > 5f) bestLine(c, -best * 10f, top, bot, t)
        windStreaks(c, wd, top, vh, t)

        for (mo in wd.motesList) if (mo.alive && mo.y > top && mo.y < bot) mote(c, mo.x, mo.y, t + mo.seed, 1f)
        for (h in wd.hazards) if (h.y > top - 400f && h.y < bot + 400f) hazard(c, h, t)
        for (a in wd.anchors) if (a.alive && a.y > top - 120f && a.y < bot + 120f) anchor(c, a, wd.valid(a), t)
        val aim = wd.aim
        if (showAim && aim != null && wd.anchor == null && !wd.dead) reticle(c, aim.x, aim.y, t, Col.phase(aim.color))

        // tether
        val an = wd.anchor
        if (an != null) tether(c, wd.x, wd.y, an.x, an.y, wd.ropeT, Col.phase(an.color), t)
        else if (wd.retractT > 0f) {
            val f = wd.retractT
            val ex = lerp(wd.lastAx, wd.x, f * 0.8f); val ey = lerp(wd.lastAy, wd.y, f * 0.8f)
            st.color = alphaF(Col.WHITE, f * 0.8f); st.strokeWidth = 3f
            c.drawLine(wd.lastAx, wd.lastAy, ex, ey, st)
        }

        if (!wd.dead) {
            trailOf(c, wd, trail, pcol, t)
            if (wd.chroma > 0.02f) {
                val o = 5f * wd.chroma
                orb(c, skin, wd.x - o, wd.y, World.ORB_R, alphaF(Col.CYAN, 0.55f * wd.chroma), t, wd.vx, wd.vy, 0f, true)
                orb(c, skin, wd.x + o, wd.y, World.ORB_R, alphaF(Col.MAGENTA, 0.55f * wd.chroma), t, wd.vx, wd.vy, 0f, true)
            }
            val sp = hypot(wd.vx, wd.vy)
            orb(c, skin, wd.x, wd.y, World.ORB_R, pcol, t, wd.vx, wd.vy, clamp01(sp / World.SPIN_MAX), false)
            if (an != null) chargeRing(c, wd, t)
            if (!wd.started) startPad(c, wd.x, wd.y, t)
        }
        particles(c, wd.parts)
        if (!wd.demo) popups(c, wd)
    }

    private fun gate(c: Canvas, y: Float, t: Float, phase: Int) {
        Draw.glow(c, 120f, y, 160f, 0.35f, Col.CYAN)
        Draw.glow(c, 880f, y, 160f, 0.35f, Col.MAGENTA)
        st.strokeWidth = 14f; st.color = 0x22FFFFFF
        c.drawLine(0f, y, 1000f, y, st)
        st.strokeWidth = 3.5f
        st.color = Col.CYAN; c.drawLine(0f, y - 4f, 1000f, y - 4f, st)
        st.color = Col.MAGENTA; c.drawLine(0f, y + 4f, 1000f, y + 4f, st)
        // drifting prism glints
        p.color = Col.WHITE
        for (k in 0 until 7) {
            val gx = ((k * 157f + t * (60f + k * 9f)) % 1100f) - 50f
            c.drawRect(gx, y - 1.5f, gx + 26f, y + 1.5f, p)
        }
        // chevrons hint which colour waits beyond
        val next = 3 - phase
        for (k in 0 until 5) {
            val cx = 100f + k * 200f
            p.color = alphaF(Col.phase(if (k % 2 == 0) next else phase), 0.85f)
            path.reset(); path.moveTo(cx - 12f, y - 14f); path.lineTo(cx, y - 26f); path.lineTo(cx + 12f, y - 14f); path.close()
            c.drawPath(path, p)
        }
    }

    private fun bestLine(c: Canvas, y: Float, top: Float, bot: Float, t: Float) {
        if (y < top || y > bot) return
        st.color = 0x66FFFFFF; st.strokeWidth = 2.5f
        var x = 0f
        while (x < 1000f) { c.drawLine(x, y, x + 22f, y, st); x += 40f }
        Draw.text(c, "BEST", 984f, y - 22f, 26f, 0xAAFFFFFF.toInt(), Fonts.bold, Paint.Align.RIGHT, 0.2f)
    }

    private fun windStreaks(c: Canvas, wd: World, top: Float, vh: Float, t: Float) {
        val wv = wd.windAt(wd.y, wd.time)
        val a = abs(wv) / 380f
        if (a < 0.05f) return
        st.strokeWidth = 2f
        for (k in 0 until 16) {
            val sy = top + hash01(k, 3) * vh
            val sp = 0.6f + hash01(k, 5)
            val x = ((hash01(k, 7) * 1200f + wd.time * wv * sp * 1.4f) % 1200f + 1200f) % 1200f - 100f
            val len = 60f + 80f * hash01(k, 9)
            st.color = alphaF(0xFFBFFFF0.toInt(), 0.32f * a)
            c.drawLine(x, sy, x - len * (if (wv > 0f) 1f else -1f), sy, st)
        }
    }

    fun mote(c: Canvas, x: Float, y: Float, t: Float, scale: Float) {
        Draw.glow(c, x, y, 46f * scale, 0.55f, Col.GOLD)
        val sx = 0.35f + 0.65f * abs(cos(t * 2.2f))
        path.reset()
        path.moveTo(x, y - 15f * scale); path.lineTo(x + 10f * sx * scale, y); path.lineTo(x, y + 15f * scale); path.lineTo(x - 10f * sx * scale, y); path.close()
        p.color = Col.GOLD; c.drawPath(path, p)
        p.color = 0xCCFFFFFF.toInt(); c.drawCircle(x - 2f * sx * scale, y - 4f * scale, 2.6f * scale, p)
    }

    private fun hazard(c: Canvas, h: Hazard, t: Float) {
        when (h.kind) {
            Hazard.SHARD -> {
                Draw.glow(c, h.x, h.y, h.len * 1.25f, 0.28f, Col.DANGER)
                c.save(); c.translate(h.x, h.y); c.rotate(h.a * DEG)
                val l = h.len
                path.reset()
                path.moveTo(-l, 0f); path.lineTo(-l + 22f, -10f); path.lineTo(l - 22f, -10f); path.lineTo(l, 0f); path.lineTo(l - 22f, 10f); path.lineTo(-l + 22f, 10f); path.close()
                p.color = 0xF0FF3D5A.toInt(); c.drawPath(path, p)
                p.color = 0x66FFFFFF; c.drawRect(-l + 22f, -10f, l - 22f, -4f, p)
                st.color = 0xFFFFB3BE.toInt(); st.strokeWidth = 2.2f; c.drawPath(path, st)
                p.color = Col.INK; c.drawCircle(0f, 0f, 7f, p)
                st.color = Col.WHITE; st.strokeWidth = 2f; c.drawCircle(0f, 0f, 7f, st)
                c.restore()
            }
            Hazard.MINE -> {
                val blink = 0.5f + 0.5f * sin(t * 6f + h.seed)
                Draw.glow(c, h.x, h.y, 70f, 0.3f + 0.25f * blink, Col.DANGER)
                c.save(); c.translate(h.x, h.y + sin(t * 1.7f + h.seed) * 4f); c.rotate(t * 40f + h.seed * 10f)
                p.color = 0xFFFF3D5A.toInt()
                path.reset()
                for (k in 0 until 8) {
                    val a = k * 0.7854f
                    val ca = cos(a); val sa = sin(a)
                    path.moveTo(ca * 14f - sa * 6f, sa * 14f + ca * 6f)
                    path.lineTo(ca * 30f, sa * 30f)
                    path.lineTo(ca * 14f + sa * 6f, sa * 14f - ca * 6f)
                    path.close()
                }
                c.drawPath(path, p)
                p.color = 0xFF2A0A14.toInt(); c.drawCircle(0f, 0f, 17f, p)
                st.color = 0xFFFF6B7F.toInt(); st.strokeWidth = 2.5f; c.drawCircle(0f, 0f, 17f, st)
                p.color = alphaF(Col.WHITE, 0.4f + 0.6f * blink); c.drawCircle(0f, 0f, 6f, p)
                c.restore()
            }
            Hazard.BEAM -> beam(c, h, t, 1f)
            Hazard.WELL -> well(c, h, t)
        }
    }

    private fun beam(c: Canvas, h: Hazard, t: Float, vis: Float) {
        val s = h.beamState()
        // emitters
        for (side in 0..1) {
            val ex = if (side == 0) h.x1 else h.x2
            p.color = alphaF(0xFF2A1A10.toInt(), vis); c.drawRoundRect(ex - 14f, h.y - 16f, ex + 14f, h.y + 16f, 8f, 8f, p)
            st.color = alphaF(Col.GOLD, vis); st.strokeWidth = 2.5f; c.drawRoundRect(ex - 14f, h.y - 16f, ex + 14f, h.y + 16f, 8f, 8f, st)
            p.color = alphaF(if (s == 0) 0xFF664422.toInt() else Col.DANGER, vis); c.drawCircle(ex, h.y, 5f, p)
        }
        when (s) {
            0 -> {
                st.color = alphaF(0x55FFB3A0, vis); st.strokeWidth = 2f
                var x = h.x1 + 18f
                while (x < h.x2 - 18f) { c.drawLine(x, h.y, min(x + 8f, h.x2 - 18f), h.y, st); x += 24f }
            }
            1 -> {
                val f = if (sin(t * 70f) > 0f) 1f else 0.35f
                st.color = alphaF(Col.DANGER, 0.8f * f * vis); st.strokeWidth = 3f
                c.drawLine(h.x1 + 14f, h.y, h.x2 - 14f, h.y, st)
            }
            else -> {
                st.color = alphaF(0x66FF5A5F, vis); st.strokeWidth = 30f
                c.drawLine(h.x1 + 14f, h.y, h.x2 - 14f, h.y, st)
                st.color = alphaF(Col.DANGER, vis); st.strokeWidth = 10f
                c.drawLine(h.x1 + 14f, h.y, h.x2 - 14f, h.y, st)
                st.color = alphaF(Col.WHITE, vis); st.strokeWidth = 3.5f
                c.drawLine(h.x1 + 14f, h.y, h.x2 - 14f, h.y, st)
            }
        }
    }

    private fun well(c: Canvas, h: Hazard, t: Float) {
        st.color = 0x22E7B8FF; st.strokeWidth = 2f
        val segs = 36
        for (k in 0 until segs step 2) {
            val a0 = k * 360f / segs + t * 8f
            Draw.r1.set(h.x - Hazard.WELL_R, h.y - Hazard.WELL_R, h.x + Hazard.WELL_R, h.y + Hazard.WELL_R)
            c.drawArc(Draw.r1, a0, 360f / segs, false, st)
        }
        Draw.glow(c, h.x, h.y, 210f, 0.55f, 0xFFB14AED.toInt())
        // accretion swirl
        for (ring in 0 until 3) {
            val rr = 58f + ring * 34f
            val sp = 120f / (ring + 1)
            Draw.r1.set(h.x - rr, h.y - rr, h.x + rr, h.y + rr)
            st.strokeWidth = 5f - ring
            st.color = if (ring == 1) 0xAA4FFFF0.toInt() else 0xAAFF4FD8.toInt()
            c.drawArc(Draw.r1, t * sp + ring * 70f, 110f, false, st)
            c.drawArc(Draw.r1, t * sp + ring * 70f + 180f, 80f, false, st)
        }
        // in-falling specks
        p.color = 0xCCFFFFFF.toInt()
        for (k in 0 until 10) {
            val ph = ((t * 0.35f + k / 10f) % 1f)
            val rr = 300f * (1f - ph) + 36f
            val a = k * 2.4f + ph * 7f
            c.drawCircle(h.x + cos(a) * rr, h.y + sin(a) * rr, 2.5f * (0.4f + ph), p)
        }
        p.color = Col.INK; c.drawCircle(h.x, h.y, Hazard.WELL_CORE, p)
        st.color = 0xFFFFFFFF.toInt(); st.strokeWidth = 3f; c.drawCircle(h.x, h.y, Hazard.WELL_CORE, st)
        st.color = 0xFFFF4FD8.toInt(); st.strokeWidth = 2f; c.drawCircle(h.x, h.y, Hazard.WELL_CORE + 6f, st)
    }

    fun anchor(c: Canvas, a: Anchor, valid: Boolean, t: Float) {
        val col = Col.phase(a.color)
        if (a.kind == Anchor.ORBIT) {
            st.color = withAlpha(col, if (valid) 0x40 else 0x20); st.strokeWidth = 2f
            for (k in 0 until 24 step 2) {
                Draw.r1.set(a.cx - a.orbitR, a.cy - a.orbitR, a.cx + a.orbitR, a.cy + a.orbitR)
                c.drawArc(Draw.r1, k * 15f, 15f, false, st)
            }
            p.color = withAlpha(col, if (valid) 0x80 else 0x30); c.drawCircle(a.cx, a.cy, 4f, p)
        }
        var x = a.x; var y = a.y
        if (a.breakT > 0f) { x += sin(t * 90f) * 3f; y += cos(t * 77f) * 3f }
        if (!valid) {
            st.color = withAlpha(col, 0x70); st.strokeWidth = 3f
            c.drawCircle(x, y, 15f, st)
            c.drawLine(x - 9f, y + 9f, x + 9f, y - 9f, st)
            return
        }
        val pulse = 0.5f + 0.5f * sin(t * 3f + a.seed)
        Draw.glow(c, x, y, 64f + 26f * a.flash + 8f * pulse, 0.65f + 0.35f * a.flash, col)
        st.color = col; st.strokeWidth = 3.5f
        Draw.r1.set(x - 21f, y - 21f, x + 21f, y + 21f)
        if (a.kind == Anchor.FRAGILE) {
            for (k in 0 until 6) c.drawArc(Draw.r1, k * 60f + 8f + t * 20f, 34f, false, st)
            if (a.breakT > 0f) {
                st.color = Col.DANGER; st.strokeWidth = 4f
                Draw.r2.set(x - 28f, y - 28f, x + 28f, y + 28f)
                c.drawArc(Draw.r2, -90f, 360f * clamp01(a.breakT), false, st)
            }
        } else {
            for (k in 0 until 3) c.drawArc(Draw.r1, k * 120f + t * 60f + a.seed * 10f, 92f, false, st)
        }
        p.color = Col.WHITE; c.drawCircle(x, y, 8f + 2f * a.flash, p)
        p.color = col; c.drawCircle(x, y, 4f, p)
    }

    private fun reticle(c: Canvas, x: Float, y: Float, t: Float, col: Int) {
        val r = 36f + 3f * sin(t * 6f)
        st.color = alphaF(Col.WHITE, 0.85f); st.strokeWidth = 3f
        c.save(); c.translate(x, y); c.rotate(t * 90f)
        for (k in 0 until 4) {
            c.rotate(90f)
            c.drawLine(r, -9f, r, 9f, st)
            c.drawLine(r, -9f, r - 7f, -9f, st)
            c.drawLine(r, 9f, r - 7f, 9f, st)
        }
        c.restore()
    }

    private fun tether(c: Canvas, ox: Float, oy: Float, ax: Float, ay: Float, f: Float, col: Int, t: Float) {
        val ex = lerp(ox, ax, f); val ey = lerp(oy, ay, f)
        st.color = withAlpha(col, 0x40); st.strokeWidth = 12f
        c.drawLine(ox, oy, ex, ey, st)
        st.color = col; st.strokeWidth = 4f
        c.drawLine(ox, oy, ex, ey, st)
        st.color = Col.WHITE; st.strokeWidth = 1.6f
        c.drawLine(ox, oy, ex, ey, st)
        // energy running up the line
        p.color = Col.WHITE
        for (k in 0 until 3) {
            val q = ((t * 2.2f + k / 3f) % 1f) * f
            c.drawCircle(lerp(ox, ax, q), lerp(oy, ay, q), 3.2f, p)
        }
    }

    private fun chargeRing(c: Canvas, wd: World, t: Float) {
        val sp = hypot(wd.vx, wd.vy)
        val k = clamp01(sp / World.SPIN_MAX)
        val ready = sp > 700f && -wd.vy > sp * 0.8f
        val col = if (ready) Col.GOLD else 0xCCFFFFFF.toInt()
        st.color = withAlpha(col, if (ready) 255 else 150); st.strokeWidth = 3.5f
        Draw.r1.set(wd.x - 32f, wd.y - 32f, wd.x + 32f, wd.y + 32f)
        c.drawArc(Draw.r1, -90f, 360f * k, false, st)
        // a little chevron where the orb would fly
        if (sp > 300f) {
            val ux = wd.vx / sp; val uy = wd.vy / sp
            val cx = wd.x + ux * 52f; val cy = wd.y + uy * 52f
            st.strokeWidth = 4f
            st.color = if (ready) Col.GOLD else withAlpha(Col.WHITE, (90 + 120 * k).toInt())
            c.drawLine(cx - ux * 10f - uy * 10f, cy - uy * 10f + ux * 10f, cx, cy, st)
            c.drawLine(cx - ux * 10f + uy * 10f, cy - uy * 10f - ux * 10f, cx, cy, st)
            if (ready) Draw.glow(c, cx, cy, 30f, 0.6f, Col.GOLD)
        }
    }

    private fun startPad(c: Canvas, x: Float, y: Float, t: Float) {
        val py = y + World.ORB_R + 6f
        Draw.glow(c, x, py, 120f, 0.4f, Col.CYAN)
        st.color = Col.CYAN; st.strokeWidth = 4f
        c.drawLine(x - 70f, py, x + 70f, py, st)
        st.color = 0x66FFFFFF; st.strokeWidth = 2f
        val r = 30f + (t * 40f) % 50f
        st.color = alphaF(Col.WHITE, 1f - (r - 30f) / 50f)
        c.drawCircle(x, y, r, st)
    }

    // ------------------------------------------------------------------ orb & trails (also used by the style gallery)

    fun orb(c: Canvas, skin: Int, x: Float, y: Float, r: Float, col: Int, t: Float, vx: Float, vy: Float, energy: Float, ghost: Boolean) {
        if (ghost) {
            p.color = col; c.drawCircle(x, y, r, p)
            return
        }
        Draw.glow(c, x, y, r * (3.6f + 2.4f * energy), 0.75f + 0.25f * energy, col)
        when (skin) {
            1 -> { // RING
                p.color = Col.WHITE; c.drawCircle(x, y, r * 0.72f, p)
                c.save(); c.translate(x, y); c.rotate(t * 160f)
                st.color = col; st.strokeWidth = r * 0.18f
                Draw.r1.set(-r * 1.45f, -r * 0.55f, r * 1.45f, r * 0.55f)
                c.drawOval(Draw.r1, st)
                c.restore()
            }
            2 -> { // STAR
                c.save(); c.translate(x, y); c.rotate(t * 120f)
                path.reset()
                for (k in 0 until 8) {
                    val a = k * 0.7854f
                    val rr = if (k % 2 == 0) r * 1.35f else r * 0.45f
                    if (k == 0) path.moveTo(cos(a) * rr, sin(a) * rr) else path.lineTo(cos(a) * rr, sin(a) * rr)
                }
                path.close()
                p.color = Col.WHITE; c.drawPath(path, p)
                st.color = col; st.strokeWidth = r * 0.12f; c.drawPath(path, st)
                c.restore()
            }
            3 -> { // EYE
                p.color = Col.WHITE; c.drawCircle(x, y, r, p)
                val sp = max(1f, hypot(vx, vy))
                val k = min(1f, sp / 400f)
                val ex = x + vx / sp * r * 0.35f * k; val ey = y + vy / sp * r * 0.35f * k
                p.color = col; c.drawCircle(ex, ey, r * 0.52f, p)
                p.color = Col.INK; c.drawCircle(ex, ey, r * 0.26f, p)
                p.color = Col.WHITE; c.drawCircle(ex - r * 0.12f, ey - r * 0.14f, r * 0.1f, p)
                val blink = (t % 3.7f) < 0.12f
                if (blink) { p.color = col; c.drawRect(x - r, y - r, x + r, y + r * 0.1f, p) }
            }
            4 -> { // HEX
                c.save(); c.translate(x, y); c.rotate(t * 70f)
                p.color = col; poly(c, 6, r * 1.15f, true)
                p.color = Col.WHITE; poly(c, 6, r * 0.62f, true)
                st.color = Col.WHITE; st.strokeWidth = r * 0.1f; poly(c, 6, r * 1.15f)
                c.restore()
            }
            5 -> { // NOVA
                val pulse = 0.5f + 0.5f * sin(t * 8f)
                c.save(); c.translate(x, y); c.rotate(t * 50f)
                st.color = col; st.strokeWidth = r * 0.14f
                for (k in 0 until 8) {
                    val a = k * 0.7854f
                    val l0 = r * 1.15f; val l1 = r * (1.6f + 0.5f * pulse * (if (k % 2 == 0) 1f else 0.5f))
                    c.drawLine(cos(a) * l0, sin(a) * l0, cos(a) * l1, sin(a) * l1, st)
                }
                c.restore()
                p.color = col; c.drawCircle(x, y, r * (0.95f + 0.1f * pulse), p)
                p.color = Col.WHITE; c.drawCircle(x, y, r * 0.6f, p)
            }
            else -> { // CORE
                p.color = col; c.drawCircle(x, y, r, p)
                p.color = Col.WHITE; c.drawCircle(x, y, r * 0.68f, p)
                p.color = 0x88FFFFFF.toInt(); c.drawCircle(x - r * 0.28f, y - r * 0.3f, r * 0.2f, p)
            }
        }
    }

    private fun trailOf(c: Canvas, wd: World, style: Int, col: Int, t: Float) {
        val n = wd.trailCount
        if (n < 2) return
        for (i in 0 until n) {
            val j = (wd.trailHead - n + i + World.TRAIL) % World.TRAIL
            tx[i] = wd.trailX[j]; ty[i] = wd.trailY[j]
        }
        tx[n] = wd.x; ty[n] = wd.y
        trail(c, style, tx, ty, n + 1, col, t, World.ORB_R)
    }
    private val tx = FloatArray(World.TRAIL + 1)
    private val ty = FloatArray(World.TRAIL + 1)

    /** xs/ys oldest first, n points. */
    fun trail(c: Canvas, style: Int, xs: FloatArray, ys: FloatArray, n: Int, col: Int, t: Float, r: Float) {
        if (n < 2) return
        when (style) {
            1 -> { // RIBBON
                for (side in 0..1) {
                    val cc = if (side == 0) Col.CYAN else Col.MAGENTA
                    for (i in 1 until n) {
                        val f = i / (n - 1f)
                        val dx = xs[i] - xs[i - 1]; val dy = ys[i] - ys[i - 1]
                        val l = max(0.01f, hypot(dx, dy))
                        val nx = -dy / l; val ny = dx / l
                        val o = sin(i * 0.5f - t * 8f + side * 3.1416f) * r * 0.6f * f
                        val o0 = sin((i - 1) * 0.5f - t * 8f + side * 3.1416f) * r * 0.6f * f
                        st.color = withAlpha(cc, (230 * f).toInt()); st.strokeWidth = r * 0.55f * f + 1f
                        c.drawLine(xs[i - 1] + nx * o0, ys[i - 1] + ny * o0, xs[i] + nx * o, ys[i] + ny * o, st)
                    }
                }
            }
            2 -> { // SPARK
                for (i in 1 until n) {
                    val f = i / (n - 1f)
                    st.color = withAlpha(col, (200 * f).toInt()); st.strokeWidth = 3f * f + 0.5f
                    c.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i], st)
                }
                st.color = Col.WHITE; st.strokeWidth = 2.2f
                for (i in 0 until n step 3) {
                    val f = i / (n - 1f)
                    val tw = sin(t * 14f + i * 1.7f)
                    if (tw < 0.2f) continue
                    val s = r * 0.55f * f * tw
                    st.color = withAlpha(if (i % 2 == 0) Col.GOLD else Col.WHITE, (255 * f).toInt())
                    val ox = sin(i * 2.3f) * r * 0.8f; val oy = cos(i * 1.9f) * r * 0.8f
                    c.drawLine(xs[i] + ox - s, ys[i] + oy, xs[i] + ox + s, ys[i] + oy, st)
                    c.drawLine(xs[i] + ox, ys[i] + oy - s, xs[i] + ox, ys[i] + oy + s, st)
                }
            }
            3 -> { // PIXEL
                for (i in 0 until n - 1) {
                    val f = i / (n - 1f)
                    val s = (r * 0.9f * f + 2f)
                    val gx = floor(xs[i] / 12f) * 12f; val gy = floor(ys[i] / 12f) * 12f
                    p.color = withAlpha(if (i % 3 == 0) Col.WHITE else col, (255 * f).toInt())
                    c.drawRect(gx - s / 2f, gy - s / 2f, gx + s / 2f, gy + s / 2f, p)
                }
            }
            4 -> { // PRISM
                for (i in 1 until n) {
                    val f = i / (n - 1f)
                    st.color = withAlpha(hsv(i * 9f - t * 160f, 0.75f, 1f), (240 * f).toInt()); st.strokeWidth = r * 1.4f * f + 1f
                    c.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i], st)
                }
            }
            5 -> { // ECHO
                for (i in 0 until n - 1 step 4) {
                    val f = i / (n - 1f)
                    st.color = withAlpha(col, (200 * f).toInt()); st.strokeWidth = 2.5f
                    c.drawCircle(xs[i], ys[i], r * (0.4f + 0.7f * f), st)
                }
                for (i in 1 until n) {
                    val f = i / (n - 1f)
                    st.color = withAlpha(Col.WHITE, (90 * f).toInt()); st.strokeWidth = 1.5f
                    c.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i], st)
                }
            }
            else -> { // COMET
                for (i in 1 until n) {
                    val f = i / (n - 1f)
                    st.color = withAlpha(col, (150 * f).toInt()); st.strokeWidth = r * 1.9f * f + 1f
                    c.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i], st)
                }
                for (i in 1 until n) {
                    val f = i / (n - 1f)
                    st.color = withAlpha(Col.WHITE, (200 * f * f).toInt()); st.strokeWidth = r * 0.7f * f + 0.5f
                    c.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i], st)
                }
            }
        }
    }

    // ------------------------------------------------------------------ particles & popups

    private fun particles(c: Canvas, ps: Particles) {
        for (i in 0 until ps.cap) {
            val l = ps.life[i]
            if (l <= 0f) continue
            val a = clamp01(l / ps.max[i])
            val col = ps.col[i]
            when (ps.kind[i]) {
                Particles.DOT -> { p.color = alphaF(col, a); c.drawCircle(ps.x[i], ps.y[i], ps.size[i] * (0.4f + 0.6f * a), p) }
                Particles.STREAK -> {
                    st.color = alphaF(col, a); st.strokeWidth = ps.size[i] * 0.7f
                    c.drawLine(ps.x[i], ps.y[i], ps.x[i] - ps.vx[i] * 0.05f, ps.y[i] - ps.vy[i] * 0.05f, st)
                }
                Particles.SHARD -> {
                    val s = ps.size[i]
                    val r = ps.rot[i]
                    val cr = cos(r) * s; val sr = sin(r) * s
                    path.reset()
                    path.moveTo(ps.x[i] + cr, ps.y[i] + sr)
                    path.lineTo(ps.x[i] - sr * 0.6f - cr * 0.5f, ps.y[i] + cr * 0.6f - sr * 0.5f)
                    path.lineTo(ps.x[i] + sr * 0.6f - cr * 0.5f, ps.y[i] - cr * 0.6f - sr * 0.5f)
                    path.close()
                    p.color = alphaF(col, a); c.drawPath(path, p)
                }
                else -> {
                    st.color = alphaF(col, a * 0.9f); st.strokeWidth = 5f * a + 1f
                    c.drawCircle(ps.x[i], ps.y[i], ps.size[i] * (1.15f - a * 0.9f), st)
                }
            }
        }
    }

    private fun popups(c: Canvas, wd: World) {
        for (pp in wd.popups) {
            if (!pp.alive) continue
            val f = pp.t
            val sc = if (f < 0.18f) easeOutBack(f / 0.18f) else 1f
            val a = if (f > 0.75f) 1f - (f - 0.75f) / 0.35f else 1f
            Draw.text(c, pp.text, pp.x + 2f, pp.y + 3f, 40f * pp.size * sc, alphaF(0xAA000000.toInt(), a), Fonts.bold, Paint.Align.CENTER, 0.06f)
            Draw.text(c, pp.text, pp.x, pp.y, 40f * pp.size * sc, alphaF(pp.col, a), Fonts.bold, Paint.Align.CENTER, 0.06f)
        }
    }

    // ------------------------------------------------------------------ overlays (screen space)

    fun darkness(c: Canvas, wd: World, w: Float, h: Float, s: Float, amount: Float, t: Float) {
        if (amount < 0.02f) return
        val ox = wd.x * s; val oy = (wd.y - wd.camY) * s
        Draw.shadeRect(c, darkSh, 0f, 0f, w, h, ox, oy, 560f * s, 560f * s, (255 * amount).toInt())
        // beacons stay visible through the dark
        c.save(); c.scale(s, s); c.translate(0f, -wd.camY)
        for (a in wd.anchors) {
            if (!a.alive || !wd.valid(a)) continue
            Draw.glow(c, a.x, a.y, 50f, 0.5f * amount, Col.phase(a.color))
            p.color = alphaF(Col.WHITE, amount); c.drawCircle(a.x, a.y, 5f, p)
        }
        for (hz in wd.hazards) {
            when (hz.kind) {
                Hazard.BEAM -> if (hz.beamState() != 0) beam(c, hz, t, amount)
                Hazard.MINE -> { val b = 0.5f + 0.5f * sin(t * 6f + hz.seed); p.color = alphaF(Col.DANGER, amount * b); c.drawCircle(hz.x, hz.y, 6f, p) }
                Hazard.SHARD -> {
                    val cc = cos(hz.a) * hz.len; val ss = sin(hz.a) * hz.len
                    st.color = alphaF(Col.DANGER, amount * 0.6f); st.strokeWidth = 2f
                    c.drawLine(hz.x - cc, hz.y - ss, hz.x + cc, hz.y + ss, st)
                }
            }
        }
        c.restore()
    }

    fun static(c: Canvas, w: Float, h: Float, danger: Float, t: Float) {
        val bh = h * 0.1f
        Draw.shadeRect(c, voidSh, 0f, h - bh, w, h, 0f, h - bh, 1f, bh)
        val frame = (t * 24f).toInt()
        for (k in 0 until 18) {
            val yy = h - bh * hash01(frame, k) * hash01(frame, k + 40)
            val x0 = hash01(frame, k + 80) * w
            val len = w * (0.05f + 0.25f * hash01(frame, k + 120))
            p.color = if (k % 4 == 0) alphaF(Col.DANGER, 0.25f + 0.6f * danger) else withAlpha(0xFFFFFFFF.toInt(), (18 + 60 * danger).toInt())
            c.drawRect(x0, yy, x0 + len, yy + h * 0.0016f + 1f, p)
        }
        if (danger > 0f) {
            p.color = alphaF(Col.DANGER, 0.5f * danger * (0.6f + 0.4f * sin(t * 12f)))
            c.drawRect(0f, h - h * 0.006f, w, h, p)
        }
    }

    fun vignette(c: Canvas, w: Float, h: Float, k: Float) {
        if (k < 0.02f) return
        val r = hypot(w, h) * 0.6f
        Draw.shadeRect(c, vignSh, 0f, 0f, w, h, w / 2f, h / 2f, r, r, (255 * clamp01(k)).toInt())
    }
}
