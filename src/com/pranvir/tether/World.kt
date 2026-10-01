package com.pranvir.tether

import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure simulation: no Android types here, so the whole climb can be tested on a plain JVM. */
interface WorldEvents {
    fun sfx(id: Int, vol: Float = 1f, rate: Float = 1f)
    fun haptic(strong: Boolean)
    fun onZone(z: Int)
    fun onDeath()
}

class Anchor {
    var x = 0f; var y = 0f
    var cx = 0f; var cy = 0f
    var kind = 0          // 0 normal, 1 orbit, 2 fragile
    var color = 0         // 0 white (any phase), 1 cyan, 2 magenta
    var orbitR = 0f; var orbitW = 0f; var orbitA = 0f
    var alive = true
    var breakT = -1f      // fragile countdown once grabbed
    var flash = 0f
    var seed = 0f
    companion object { const val NORMAL = 0; const val ORBIT = 1; const val FRAGILE = 2 }
}

class Hazard {
    var kind = 0
    var x = 0f; var y = 0f
    var a = 0f; var w = 0f; var len = 0f
    var x1 = 0f; var x2 = 0f
    var t = 0f; var period = 2.4f
    var nearCd = 0f
    var seed = 0f
    companion object {
        const val SHARD = 0; const val MINE = 1; const val BEAM = 2; const val WELL = 3
        const val WELL_R = 320f; const val WELL_CORE = 34f; const val WELL_G = 2600f
        const val MINE_R = 22f
    }
    /** Beam cycle: 0 off, 1 warning, 2 live. */
    fun beamState(): Int { val p = ((t % period) + period) % period; return if (p < 1.2f) 0 else if (p < 1.6f) 1 else 2 }
}

class Mote { var x = 0f; var y = 0f; var alive = true; var seed = 0f }

class Popup { var text = ""; var x = 0f; var y = 0f; var t = 0f; var col = 0; var size = 1f; var alive = false }

/** Fixed-capacity particle pool in world space. */
class Particles(val cap: Int = 420) {
    val x = FloatArray(cap); val y = FloatArray(cap); val vx = FloatArray(cap); val vy = FloatArray(cap)
    val life = FloatArray(cap); val max = FloatArray(cap); val size = FloatArray(cap); val col = IntArray(cap)
    val kind = IntArray(cap); val rot = FloatArray(cap); val drag = FloatArray(cap); val grav = FloatArray(cap)
    var count = 0
    private var next = 0
    companion object { const val DOT = 0; const val STREAK = 1; const val SHARD = 2; const val RING = 3 }

    fun spawn(px: Float, py: Float, pvx: Float, pvy: Float, l: Float, s: Float, c: Int, k: Int, dr: Float = 1.5f, g: Float = 0f) {
        var i = -1
        for (n in 0 until cap) { val j = (next + n) % cap; if (life[j] <= 0f) { i = j; break } }
        if (i < 0) i = next
        next = (i + 1) % cap
        x[i] = px; y[i] = py; vx[i] = pvx; vy[i] = pvy; life[i] = l; max[i] = l; size[i] = s; col[i] = c; kind[i] = k
        rot[i] = (px * 13f + py * 7f) % 6.28f; drag[i] = dr; grav[i] = g
    }

    fun update(dt: Float) {
        var n = 0
        for (i in 0 until cap) {
            if (life[i] <= 0f) continue
            life[i] -= dt
            val d = exp(-drag[i] * dt)
            vx[i] *= d; vy[i] = vy[i] * d + grav[i] * dt
            x[i] += vx[i] * dt; y[i] += vy[i] * dt
            rot[i] += dt * 6f
            n++
        }
        count = n
    }

    fun clear() { for (i in 0 until cap) life[i] = 0f; count = 0 }
}

class World(val seed: Long, val demo: Boolean = false) {
    companion object {
        const val W = 1000f
        const val ORB_R = 18f
        const val G = 1700f
        const val RANGE = 470f
        const val REEL = 560f
        const val MIN_L = 90f
        const val ROPE = 170f
        const val SPIN_ACC = 1500f
        const val SPIN_MAX = 1250f
        const val SPEED_MAX = 2300f
        /** Hazards keep this far from any anchor so a full spin is always safe. */
        const val CLEAR = 215f
        /** Anchors stay this far from the walls so a spin never scrapes them. */
        const val X0 = 195f
        const val X1 = 805f
        const val TRAIL = 48
        fun gravMul(z: Int) = when (z) { 1 -> 0.78f; 3 -> 1.05f; 4 -> 0.95f; else -> 1f }
    }

    var events: WorldEvents? = null
    val rnd = Random(seed)
    var viewH = 2000f

    // orb
    var x = 500f; var y = 0f; var vx = 0f; var vy = 0f
    var started = false
    var dead = false
    var deadT = 0f
    var time = 0f
    var phase = 1
    var phaseFlash = 0f

    // tether
    var anchor: Anchor? = null
    var ropeL = 0f
    var ropeT = 0f            // 0..1 shoot-out animation
    var attachT = 0f
    var swingMaxUp = 0f
    var spinDir = 0
    var lastAx = 0f; var lastAy = 0f; var retractT = 0f
    var aim: Anchor? = null

    // scoring
    var heightM = 0f
    var combo = 0
    var maxCombo = 0
    var comboT = 0f
    var motes = 0
    var perfects = 0
    var zone = 0
    var nearMisses = 0

    // feel
    var slowT = 0f
    var shake = 0f
    var camY = 0f
    var timeScale = 1f
    var chroma = 0f

    val anchors = ArrayList<Anchor>()
    val hazards = ArrayList<Hazard>()
    val motesList = ArrayList<Mote>()
    val gates = ArrayList<Float>()
    var prunedGates = 0
    val parts = Particles()
    val popups = Array(10) { Popup() }

    val trailX = FloatArray(TRAIL); val trailY = FloatArray(TRAIL); val trailS = FloatArray(TRAIL)
    var trailHead = 0; var trailCount = 0

    // generation state
    private var genY = 0f
    private var lastValidX = 500f
    private var nextGateY = 0f
    private var rowIndex = 0

    init {
        camY = -viewH * 0.62f
        genY = 0f
        nextGateY = -3000f - rnd.nextFloat() * 600f
        // a guaranteed, readable opening: two rows placed by hand
        addAnchor(700f, -270f, 0, Anchor.NORMAL)
        addAnchor(320f, -540f, 0, Anchor.NORMAL)
        lastValidX = 320f
        genY = -540f
        rowIndex = 2
        generate()
    }

    fun setView(h: Float) {
        viewH = h
        if (!started) camY = -viewH * 0.62f
    }

    // ------------------------------------------------------------------ queries

    fun heightOf(wy: Float) = -wy / 10f
    fun zoneAt(wy: Float) = Zones.index(heightOf(wy))
    fun grav(): Float {
        val m = heightOf(y)
        val z = Zones.index(m)
        val b = Zones.blend(m)
        val g0 = gravMul(z)
        val g1 = if (z < Zones.ALL.size - 1) gravMul(z + 1) else g0
        return G * lerp(g0, g1, b)
    }
    fun windAt(wy: Float, t: Float): Float {
        val m = heightOf(wy)
        val a = smooth((m - 700f) / 60f) * (1f - smooth((m - 1200f) / 60f)) + 0.45f * smooth((m - 1800f) / 80f)
        return 380f * a * sin(t * 0.6f)
    }
    fun darkness(): Float { val m = heightOf(camY + viewH * 0.6f); return smooth((m - 1200f) / 70f) * (1f - smooth((m - 1800f) / 70f)) }

    fun phaseAt(wy: Float): Int {
        var n = prunedGates
        for (g in gates) if (g > wy) n++
        return 1 + (n % 2)
    }

    fun valid(a: Anchor) = a.alive && (a.color == 0 || a.color == phase)

    fun bestAnchor(): Anchor? {
        var best: Anchor? = null
        var bs = Float.MAX_VALUE
        for (a in anchors) {
            if (!valid(a)) continue
            val d = hypot(a.x - x, a.y - y)
            if (d > RANGE || d < ORB_R) continue
            val s = d + (if (a.y > y) 500f else 0f)
            if (s < bs) { bs = s; best = a }
        }
        return best
    }

    // ------------------------------------------------------------------ generation

    private fun addAnchor(ax: Float, ay: Float, color: Int, kind: Int): Anchor {
        val a = Anchor()
        a.cx = ax; a.cy = ay; a.x = ax; a.y = ay; a.color = color; a.kind = kind
        a.seed = rnd.nextFloat() * 100f
        if (kind == Anchor.ORBIT) {
            a.orbitR = 60f + rnd.nextFloat() * 40f
            a.orbitW = (0.9f + rnd.nextFloat() * 0.8f) * (if (rnd.nextBoolean()) 1f else -1f)
            a.orbitA = rnd.nextFloat() * 6.28f
            a.cx = ax.coerceIn(X0 + a.orbitR, X1 - a.orbitR)
            a.x = a.cx + cos(a.orbitA) * a.orbitR; a.y = a.cy + sin(a.orbitA) * a.orbitR
        }
        anchors.add(a)
        return a
    }

    private fun generate() {
        while (genY > camY - 1600f) nextRow()
    }

    private fun nextRow() {
        val prevY = genY
        val m = heightOf(prevY)
        val d = clamp01(m / 2500f)
        val z = Zones.index(m)
        // a prism gate needs a wide gap so no anchor's spin circle ever crosses it
        val gateDue = prevY - 230f < nextGateY
        val gap = if (gateDue) 450f + rnd.nextFloat() * 30f else 210f + rnd.nextFloat() * 70f + d * 40f
        val ry = prevY - gap
        genY = ry
        rowIndex++

        // prism gate sits half-way between rows
        val mid = (ry + prevY) / 2f
        if (gateDue) {
            gates.add(mid)
            nextGateY = mid - (3000f + rnd.nextFloat() * 1500f)
        }
        val ph = phaseAt(ry)

        // guaranteed anchor: shifted sideways from the previous one so there is always a swing
        var vx0: Float
        var tries = 0
        do {
            val dx = (140f + rnd.nextFloat() * 300f) * (if (rnd.nextBoolean()) 1f else -1f)
            vx0 = lastValidX + dx
            tries++
        } while ((vx0 < X0 || vx0 > X1) && tries < 20)
        vx0 = vx0.coerceIn(X0, X1)
        val coloured = z >= 1 && rnd.nextFloat() < 0.6f
        val kind = if (z >= 1 && rnd.nextFloat() < 0.2f) Anchor.ORBIT else Anchor.NORMAL
        val main = addAnchor(vx0, ry + (rnd.nextFloat() - 0.5f) * 40f, if (coloured) ph else 0, kind)
        lastValidX = main.cx

        // optional second anchor: decoy, fragile shortcut, or plain alternative
        if (rnd.nextFloat() < 0.35f + 0.3f * d) {
            var sx = 0f
            var ok = false
            for (i in 0 until 12) {
                sx = X0 + rnd.nextFloat() * (X1 - X0)
                if (abs(sx - main.cx) > 240f) { ok = true; break }
            }
            if (ok) {
                val r = rnd.nextFloat()
                val col = when {
                    z >= 1 && r < 0.5f -> 3 - ph
                    z >= 1 && r < 0.75f -> ph
                    else -> 0
                }
                val k = if (z >= 2 && rnd.nextFloat() < 0.35f) Anchor.FRAGILE else if (z >= 1 && rnd.nextFloat() < 0.2f) Anchor.ORBIT else Anchor.NORMAL
                addAnchor(sx, ry + (rnd.nextFloat() - 0.5f) * 60f, col, k)
            }
        }

        // motes on a little arc
        if (rnd.nextFloat() < 0.55f) {
            val mx = 180f + rnd.nextFloat() * 640f
            val my = (prevY + ry) / 2f
            val n = 3 + rnd.nextInt(3)
            for (i in 0 until n) {
                val f = (i - (n - 1) / 2f)
                val mo = Mote()
                mo.x = (mx + f * 46f).coerceIn(60f, 940f); mo.y = my + f * f * 9f
                mo.seed = rnd.nextFloat() * 10f
                motesList.add(mo)
            }
        }

        // hazards between this row and the previous one
        if (m > 70f && !demo || (demo && m > 150f)) {
            var count = 0
            if (rnd.nextFloat() < 0.45f + 0.5f * d) count++
            if (m > 300f && rnd.nextFloat() < 0.2f + 0.5f * d) count++
            if (m > 1200f && rnd.nextFloat() < 0.35f * d) count++
            for (c in 0 until count) placeHazard(prevY, ry, z, m)
        }
    }

    private fun clearOfAnchors(hx: Float, hy: Float, pad: Float): Boolean {
        for (a in anchors) {
            val reach = if (a.kind == Anchor.ORBIT) a.orbitR else 0f
            if (hypot(a.cx - hx, a.cy - hy) < pad + reach) return false
        }
        return true
    }

    private fun placeHazard(y0: Float, y1: Float, z: Int, m: Float) {
        val roll = rnd.nextFloat()
        val kind = when {
            z >= 4 && roll < 0.3f -> Hazard.WELL
            z >= 3 && roll < 0.55f -> Hazard.BEAM
            m > 150f && roll < 0.75f -> Hazard.MINE
            else -> Hazard.SHARD
        }
        for (attempt in 0 until 18) {
            val hx = 90f + rnd.nextFloat() * 820f
            val hy = lerp(y0, y1, 0.25f + rnd.nextFloat() * 0.5f)
            val h = Hazard()
            h.kind = kind; h.x = hx; h.y = hy; h.seed = rnd.nextFloat() * 100f
            when (kind) {
                Hazard.SHARD -> {
                    h.len = 60f + rnd.nextFloat() * (40f + 40f * clamp01(m / 1500f))
                    h.w = (0.7f + rnd.nextFloat() * (0.6f + clamp01(m / 2000f))) * (if (rnd.nextBoolean()) 1f else -1f)
                    h.a = rnd.nextFloat() * 6.28f
                    if (!clearOfAnchors(hx, hy, CLEAR + h.len * 0.6f)) continue
                }
                Hazard.MINE -> { if (!clearOfAnchors(hx, hy, CLEAR + Hazard.MINE_R)) continue }
                Hazard.WELL -> {
                    h.x = 200f + rnd.nextFloat() * 600f
                    if (!clearOfAnchors(h.x, hy, CLEAR + 80f)) continue
                    for (o in hazards) if (o.kind == Hazard.WELL && abs(o.y - hy) < 900f) return
                }
                Hazard.BEAM -> {
                    val len = 300f + rnd.nextFloat() * 220f
                    h.x1 = (hx - len / 2f).coerceIn(0f, W - len); h.x2 = h.x1 + len
                    h.x = (h.x1 + h.x2) / 2f
                    h.t = rnd.nextFloat() * 2.4f
                    var ok = true
                    for (a in anchors) {
                        val reach = if (a.kind == Anchor.ORBIT) a.orbitR else 0f
                        val px = a.cx.coerceIn(h.x1, h.x2)
                        if (hypot(a.cx - px, a.cy - hy) < CLEAR + reach) { ok = false; break }
                    }
                    if (!ok) continue
                }
            }
            hazards.add(h)
            hazardsMade++
            return
        }
    }

    // ------------------------------------------------------------------ simulation

    /** Real-time step; the world itself may run slowed (near-miss, death). */
    fun update(realDt: Float, held: Boolean) {
        val rdt = realDt.coerceIn(0f, 1f / 20f)
        if (slowT > 0f) slowT -= rdt
        val target = when {
            dead -> 0.25f
            slowT > 0f -> 0.35f
            else -> 1f
        }
        timeScale = approach(timeScale, target, rdt * (if (target < timeScale) 12f else 3f))
        chroma = approach(chroma, if (slowT > 0f || dead) 1f else 0f, rdt * 5f)
        val dt = rdt * timeScale
        time += dt
        if (dead) deadT += rdt
        shake = max(0f, shake - rdt * 2.5f)
        phaseFlash = max(0f, phaseFlash - rdt * 1.8f)
        if (retractT > 0f) retractT = max(0f, retractT - rdt * 6f)
        if (comboT > 0f) { comboT -= dt; if (comboT <= 0f && combo > 0) { combo = 0 } }

        animate(dt)
        if (!dead) {
            if (!started) {
                aim = bestAnchor()
                if (held) { started = true; tryAttach() }
            } else {
                input(held)
                val sub = 3
                val h = dt / sub
                for (i in 0 until sub) { physics(h, held); if (dead) break }
                if (!dead) postStep(dt)
            }
        }
        parts.update(dt)
        for (p in popups) if (p.alive) { p.t += rdt; p.y -= rdt * 60f; if (p.t > 1.1f) p.alive = false }
        camera(rdt)
        if (!dead) generate()
        prune()
    }

    private fun animate(dt: Float) {
        for (a in anchors) {
            if (!a.alive) continue
            if (a.kind == Anchor.ORBIT) {
                a.orbitA += a.orbitW * dt
                a.x = a.cx + cos(a.orbitA) * a.orbitR; a.y = a.cy + sin(a.orbitA) * a.orbitR
            }
            if (a.flash > 0f) a.flash = max(0f, a.flash - dt * 2.5f)
            if (a.breakT > 0f) {
                a.breakT -= dt
                if (a.breakT <= 0f) shatterAnchor(a)
            }
        }
        for (h in hazards) {
            h.t += dt
            if (h.kind == Hazard.SHARD) h.a += h.w * dt
            if (h.nearCd > 0f) h.nearCd -= dt
        }
    }

    private fun shatterAnchor(a: Anchor) {
        a.alive = false
        for (i in 0 until 14) {
            val ang = rnd.nextFloat() * 6.28f; val sp = 120f + rnd.nextFloat() * 260f
            parts.spawn(a.x, a.y, cos(ang) * sp, sin(ang) * sp, 0.7f, 6f + rnd.nextFloat() * 6f, Col.phase(a.color), Particles.SHARD, 2f, 500f)
        }
        events?.sfx(Sfx.SNAP, 0.8f, 1f)
        if (anchor === a) detach(false)
    }

    private fun input(held: Boolean) {
        if (held) {
            if (anchor == null) { aim = bestAnchor(); tryAttach() } else aim = null
        } else {
            aim = bestAnchor()
            if (anchor != null) detach(true)
        }
    }

    private fun tryAttach() {
        val a = bestAnchor() ?: return
        anchor = a
        ropeL = max(MIN_L, hypot(a.x - x, a.y - y))
        ropeT = 0f
        attachT = 0f
        spinDir = 0
        swingMaxUp = max(0f, -vy)
        a.flash = 1f
        if (a.kind == Anchor.FRAGILE && a.breakT < 0f) a.breakT = 1.0f
        events?.sfx(Sfx.ZIP, 0.7f, 0.9f + rnd.nextFloat() * 0.2f)
        events?.haptic(false)
        for (i in 0 until 6) {
            val ang = rnd.nextFloat() * 6.28f
            parts.spawn(a.x, a.y, cos(ang) * 160f, sin(ang) * 160f, 0.35f, 4f, Col.phase(a.color), Particles.DOT, 4f)
        }
    }

    private fun detach(byPlayer: Boolean) {
        val a = anchor ?: return
        lastAx = a.x; lastAy = a.y; retractT = 1f
        anchor = null
        if (!byPlayer) return
        val up = -vy
        if (up > 700f && up >= swingMaxUp * 0.8f) {
            combo++
            maxCombo = max(maxCombo, combo)
            perfects++
            comboT = 4f
            val label = if (combo >= 2) "PERFECT ×$combo" else "PERFECT"
            popup(label, x, y - 50f, Col.GOLD, 1f + min(combo, 10) * 0.04f)
            events?.sfx(Sfx.PERFECT, 0.8f, min(2f, 1f + (combo - 1) * 0.06f))
            events?.haptic(false)
            for (i in 0 until 16) {
                val ang = rnd.nextFloat() * 6.28f; val sp = 200f + rnd.nextFloat() * 220f
                parts.spawn(x, y, cos(ang) * sp + vx * 0.2f, sin(ang) * sp + vy * 0.2f, 0.5f, 3f + rnd.nextFloat() * 3f, Col.GOLD, Particles.STREAK, 3f)
            }
            parts.spawn(x, y, 0f, 0f, 0.45f, 70f, Col.GOLD, Particles.RING, 0f)
        } else if (up > 300f) {
            events?.sfx(Sfx.WHOOSH, 0.6f, 1f)
        } else {
            events?.sfx(Sfx.WHOOSH, 0.35f, 0.8f)
        }
    }

    private fun physics(dt: Float, held: Boolean) {
        val g = grav()
        var ax = windAt(y, time)
        var ay = g
        // gravity wells
        for (h in hazards) {
            if (h.kind != Hazard.WELL) continue
            val dx = h.x - x; val dy = h.y - y
            val d = hypot(dx, dy)
            if (d < Hazard.WELL_R && d > 1f) {
                val f = Hazard.WELL_G * (1f - d / Hazard.WELL_R)
                ax += dx / d * f; ay += dy / d * f
            }
        }
        vx += ax * dt; vy += ay * dt

        val a = anchor
        if (a != null) {
            val rx = x - a.x; val ry = y - a.y
            val dist = hypot(rx, ry)
            if (held) {
                // zip in to working length
                if (ropeL > ROPE) ropeL = max(ROPE, ropeL - REEL * dt)
                // spin motor: holding pumps speed around the anchor
                if (dist > 1f) {
                    val nx = rx / dist; val ny = ry / dist
                    val tx = -ny; val ty = nx
                    val vt = vx * tx + vy * ty
                    if (spinDir == 0) spinDir = if (abs(vt) > 60f) (if (vt > 0f) 1 else -1) else (if (ty > 0f) 1 else -1)
                    val cur = vt * spinDir
                    if (cur < SPIN_MAX) {
                        val add = min(SPIN_ACC * dt, SPIN_MAX - cur)
                        vx += tx * spinDir * add; vy += ty * spinDir * add
                    }
                }
            }
        }
        x += vx * dt; y += vy * dt

        if (a != null) {
            val rx = x - a.x; val ry = y - a.y
            val dist = hypot(rx, ry)
            if (dist > ropeL && dist > 0.01f) {
                val nx = rx / dist; val ny = ry / dist
                x = a.x + nx * ropeL; y = a.y + ny * ropeL
                val vr = vx * nx + vy * ny
                if (vr > 0f) { vx -= vr * nx; vy -= vr * ny }
            }
            // orbiting anchors drag the line along with them
        }
        // walls
        if (x < ORB_R) { x = ORB_R; if (vx < 0f) { vx = -vx * 0.6f; wallHit() } }
        if (x > W - ORB_R) { x = W - ORB_R; if (vx > 0f) { vx = -vx * 0.6f; wallHit() } }
        val sp = hypot(vx, vy)
        if (sp > SPEED_MAX) { vx *= SPEED_MAX / sp; vy *= SPEED_MAX / sp }
        collide()
    }

    private fun wallHit() {
        if (abs(vx) > 250f) {
            events?.sfx(Sfx.TAP, 0.35f, 0.7f)
            for (i in 0 until 5) parts.spawn(x, y, vx * 0.3f + (rnd.nextFloat() - 0.5f) * 200f, (rnd.nextFloat() - 0.5f) * 300f, 0.3f, 3f, Col.WHITE, Particles.DOT, 4f)
        }
    }

    private fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 <= 0f) 0f else clamp01(((px - ax) * dx + (py - ay) * dy) / l2)
        return hypot(px - (ax + dx * t), py - (ay + dy * t))
    }

    /** Surface distance from the orb to a hazard; negative means contact. */
    fun hazardGap(h: Hazard): Float = when (h.kind) {
        Hazard.SHARD -> {
            val c = cos(h.a) * h.len; val s = sin(h.a) * h.len
            segDist(x, y, h.x - c, h.y - s, h.x + c, h.y + s) - ORB_R - 6f
        }
        Hazard.MINE -> hypot(x - h.x, y - h.y) - ORB_R - Hazard.MINE_R * 0.9f
        Hazard.WELL -> hypot(x - h.x, y - h.y) - ORB_R - Hazard.WELL_CORE
        else -> {
            if (h.beamState() != 2) 999f
            else if (x < h.x1 - ORB_R || x > h.x2 + ORB_R) 999f
            else abs(y - h.y) - ORB_R - 5f
        }
    }

    var godMode = false

    private fun collide() {
        for (h in hazards) {
            val gap = hazardGap(h)
            if (gap < 0f) {
                if (godMode) { h.nearCd = 1f; continue }
                die(h)
                return
            }
            if (gap < 38f && h.nearCd <= 0f && hypot(vx, vy) > 450f) {
                h.nearCd = 1.6f
                nearMisses++
                slowT = 0.45f
                motes += 2
                popup("CLOSE  +2", x, y - 46f, Col.WHITE, 0.9f)
                events?.sfx(Sfx.NEAR, 0.7f, 1f)
                events?.haptic(false)
            }
        }
    }

    private fun die(h: Hazard?) {
        if (dead) return
        dead = true
        deadT = 0f
        deathCause = h?.kind ?: -1
        if (anchor != null) { lastAx = anchor!!.x; lastAy = anchor!!.y; retractT = 1f; anchor = null }
        shake = 1f
        val c = Col.phase(phase)
        for (i in 0 until 34) {
            val ang = rnd.nextFloat() * 6.28f; val sp = 150f + rnd.nextFloat() * 520f
            parts.spawn(x, y, cos(ang) * sp + vx * 0.15f, sin(ang) * sp + vy * 0.15f, 0.9f + rnd.nextFloat() * 0.6f, 5f + rnd.nextFloat() * 9f,
                if (i % 3 == 0) Col.WHITE else c, Particles.SHARD, 1.2f, 300f)
        }
        parts.spawn(x, y, 0f, 0f, 0.6f, 160f, if (h != null) Col.DANGER else Col.WHITE, Particles.RING, 0f)
        events?.sfx(Sfx.DEATH, 1f, 1f)
        events?.haptic(true)
        events?.onDeath()
    }

    private fun postStep(dt: Float) {
        val a = anchor
        if (a != null) {
            attachT += dt
            ropeT = min(1f, ropeT + dt * 14f)
            swingMaxUp = max(swingMaxUp, -vy)
            if (!a.alive) detach(false)
        }
        // trail
        val li = (trailHead - 1 + TRAIL) % TRAIL
        if (trailCount == 0 || hypot(trailX[li] - x, trailY[li] - y) > 7f) {
            trailX[trailHead] = x; trailY[trailHead] = y; trailS[trailHead] = hypot(vx, vy)
            trailHead = (trailHead + 1) % TRAIL
            if (trailCount < TRAIL) trailCount++
        }
        // motes
        for (mo in motesList) {
            if (!mo.alive) continue
            if (hypot(mo.x - x, mo.y - y) < ORB_R + 26f) {
                mo.alive = false
                val v = 1 + min(combo, 12) / 4
                motes += v
                events?.sfx(Sfx.MOTE, 0.5f, 1f + min(combo, 12) * 0.04f + rnd.nextFloat() * 0.06f)
                for (i in 0 until 6) {
                    val ang = rnd.nextFloat() * 6.28f
                    parts.spawn(mo.x, mo.y, cos(ang) * 140f, sin(ang) * 140f, 0.4f, 3f, Col.GOLD, Particles.DOT, 4f)
                }
                if (v > 1) popup("+$v", mo.x, mo.y - 30f, Col.GOLD, 0.7f)
            }
        }
        // phase gates
        val p = phaseAt(y)
        if (p != phase) {
            phase = p
            phaseFlash = 1f
            events?.sfx(Sfx.PHASE, 0.7f, if (p == 1) 1f else 1.12f)
            for (i in 0 until 26) {
                val px = rnd.nextFloat() * W
                parts.spawn(px, y + (rnd.nextFloat() - 0.5f) * 20f, (rnd.nextFloat() - 0.5f) * 80f, (rnd.nextFloat() - 0.5f) * 160f, 0.6f, 3f, Col.phase(p), Particles.DOT, 2f)
            }
        }
        // height & zones
        val hm = heightOf(y)
        if (hm > heightM) heightM = hm
        val z = Zones.index(heightM)
        if (z > zone) {
            zone = z
            events?.onZone(z)
        }
        // falling out of view
        if (y > camY + viewH + 70f) die(null)
    }

    var voidSpeed = 0f
    var hazardsMade = 0
    var deathCause = -2

    private fun camera(rdt: Float) {
        if (!started) return
        // the static rises: dawdling on one anchor is not free
        voidSpeed = if (dead || heightM < 30f) 0f else 30f + 60f * clamp01(heightM / 2500f)
        camY -= voidSpeed * rdt * timeScale
        val a = anchor
        val focus = if (a != null) min(y, a.y + 120f) else y
        val target = focus - viewH * 0.6f
        if (target < camY) camY = lerp(camY, target, 1f - exp(-4.5f * rdt))
    }

    private fun prune() {
        val limit = camY + viewH + 450f
        anchors.removeAll { it.cy > limit + 120f || !it.alive }
        hazards.removeAll { it.y > limit + (if (it.kind == Hazard.WELL) Hazard.WELL_R else 0f) }
        motesList.removeAll { !it.alive || it.y > limit }
        while (gates.isNotEmpty() && gates[0] > limit) { gates.removeAt(0); prunedGates++ }
    }

    fun popup(text: String, px: Float, py: Float, col: Int, size: Float) {
        var p = popups[0]
        for (q in popups) if (!q.alive) { p = q; break } else if (q.t > p.t) p = q
        p.text = text; p.x = px.coerceIn(140f, W - 140f); p.y = py; p.t = 0f; p.col = col; p.size = size; p.alive = true
    }

    // ------------------------------------------------------------------ autopilot (attract mode + tests)

    private var apAnchor: Anchor? = null
    private var apTarget: Anchor? = null
    private var apPrev = Float.MAX_VALUE
    /** 0..1: how sloppy the autopilot is (attract mode plays a little loose). */
    var apSloppy = 0f

    /** Next valid anchor above the current one, preferring the closest one up. */
    private fun pickTarget(cur: Anchor): Anchor? {
        var best: Anchor? = null
        var bs = Float.MAX_VALUE
        for (o in anchors) {
            if (o === cur || !o.alive || o.kind == Anchor.FRAGILE) continue
            if (o.y > cur.y - 120f) continue
            if (o.color != 0 && o.color != phaseAt(o.y)) continue
            val d = hypot(o.x - cur.x, o.y - cur.y)
            if (d > 900f) continue
            val sc = d - (cur.y - o.y) * 0.4f
            if (sc < bs) { bs = sc; best = o }
        }
        return best
    }

    /** Closest approach between a ballistic flight from here and point (tx, ty). */
    private fun flightMiss(tx: Float, ty: Float): Float {
        val g = grav()
        var px = x; var py = y; var pvx = vx; var pvy = vy
        var best = Float.MAX_VALUE
        val h = 1f / 60f
        for (i in 0 until 150) {
            pvy += g * h
            px += pvx * h; py += pvy * h
            if (px < ORB_R) { px = ORB_R; pvx = -pvx * 0.6f }
            if (px > W - ORB_R) { px = W - ORB_R; pvx = -pvx * 0.6f }
            val d = hypot(px - tx, py - ty)
            if (d < best) best = d
            if (pvy > 0f && py > ty + 150f) break
        }
        return best
    }

    /** A competent player: used behind the menus and by the test bot. */
    fun autopilot(): Boolean {
        if (dead) return false
        if (!started) return true
        val a = anchor
        if (a != null) {
            if (apAnchor !== a) { apAnchor = a; apTarget = pickTarget(a); apPrev = Float.MAX_VALUE }
            if (attachT > 6f) return false
            val sp = hypot(vx, vy)
            if (vy > -300f || sp < SPIN_MAX * 0.75f) { apPrev = Float.MAX_VALUE; return true }
            var miss = Float.MAX_VALUE
            for (o in anchors) {
                if (o === a || !o.alive || o.kind == Anchor.FRAGILE || o.y > a.y - 120f) continue
                if (o.color != 0 && o.color != phaseAt(o.y)) continue
                if (hypot(o.x - a.x, o.y - a.y) > 900f) continue
                miss = min(miss, flightMiss(o.x, o.y))
            }
            val good = 140f + apSloppy * 120f + max(0f, attachT - 2f) * 120f
            if (miss < good && miss > apPrev) return false
            if (miss < 30f) return false
            apPrev = miss
            return true
        }
        apAnchor = null
        val b = bestAnchor() ?: return false
        if (b.y < y - 20f && vy < -150f) {
            // still climbing towards it: grab when it is about to slip past, or when it is the one we aimed for
            val d = hypot(b.x - x, b.y - y)
            return d < 200f || vy > -400f
        }
        if (vy > -150f && b.y < y + 60f) return true
        if (vy > 300f) return true
        return false
    }
}
