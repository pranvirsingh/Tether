package com.pranvir.tether

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

interface Host {
    fun loadInt(key: String, def: Int): Int
    fun saveInt(key: String, v: Int)
    fun sound(id: Int, vol: Float = 1f, rate: Float = 1f)
    fun setAudio(soundOn: Boolean, musicOn: Boolean)
    /** scene: 0 menu, 1 playing, 2 paused, 3 results. */
    fun setMusicState(scene: Int, combo: Int, intensity: Float, slow: Boolean, dead: Boolean)
    fun haptic(strong: Boolean)
    /** Today as yyyymmdd. */
    fun today(): Int
}

class Btn(val id: Int) {
    val r = RectF()
    var label = ""
    var icon = 0
    var style = 0          // 0 glass, 1 primary, 2 round
    var visible = false
    var off = false
    var press = 0f
    var sub = ""
    fun set(l: Float, t: Float, rr: Float, b: Float): Btn { r.set(l, t, rr, b); visible = true; return this }
    fun hit(x: Float, y: Float, slop: Float) = visible && x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop
}

class Game(val host: Host) : WorldEvents {
    companion object {
        const val M_HOME = 0; const val M_PLAY = 1; const val M_RESULT = 2; const val M_STYLE = 3
        const val B_PLAY = 1; const val B_DAILY = 2; const val B_STYLE = 3; const val B_SOUND = 4; const val B_MUSIC = 5; const val B_VIBE = 6
        const val B_PAUSE = 7; const val B_RESUME = 8; const val B_RESTART = 9; const val B_HOME = 10; const val B_RETRY = 11; const val B_BACK = 12
        const val B_TAB0 = 13; const val B_TAB1 = 14; const val B_ITEM0 = 20
        val ZONE_SUB = arrayOf("hold  ·  spin  ·  release", "low gravity  ·  prism gates", "solar wind  ·  fragile anchors", "darkness  ·  lasers", "gravity wells  ·  endless")
    }

    // screen
    var w = 1080f; var h = 2340f
    private var u = 10.8f
    private var s = 1.08f
    private var vh = 2166f
    private var inL = 0f; private var inT = 0f; private var inR = 0f; private var inB = 0f

    // state
    var mode = M_HOME
        private set
    var paused = false
        private set
    var world: World = World(1L)
        private set
    private var demo = World(System.nanoTime(), demo = true)
    private var demoWait = 1.2f
    private var held = false
    private var daily = false
    private var time = 0f
    private var fadeIn = 1f
    private var bannerT = 0f
    private var bannerZone = 0
    private var resultT = 0f
    private var newBest = false
    private var runMotesCommitted = 0
    private var tab = 0
    private val cardFlash = FloatArray(6)
    private val cardShake = FloatArray(6)
    private var pressed: Btn? = null
    private var playing = false
    private var introShown = false

    // save
    var best = 0
    var dailyBest = 0
    var dailyDate = 0
    var motes = 0
    var unlocked = 1 or (1 shl 8)
    var orbSkin = 0
    var trailStyle = 0
    var soundOn = true
    var musicOn = true
    var vibeOn = true
    var runs = 0
    var perfectTotal = 0
    var tutGate = 0

    private val buttons = ArrayList<Btn>()
    private fun btn(id: Int): Btn = buttons.firstOrNull { it.id == id } ?: Btn(id).also { buttons.add(it) }

    private val primSh = LinearGradient(0f, 0f, 1f, 0f, intArrayOf(Col.CYAN, 0xFF8A5CF6.toInt(), Col.MAGENTA), null, Shader.TileMode.CLAMP)
    private val mtx = Matrix()
    private val rc = RectF()

    init {
        load()
        host.setAudio(soundOn, musicOn)
        world = World(1L)
    }

    private fun load() {
        best = host.loadInt("best", 0)
        dailyBest = host.loadInt("dailyBest", 0)
        dailyDate = host.loadInt("dailyDate", 0)
        motes = host.loadInt("motes", 0).coerceAtLeast(0)
        unlocked = host.loadInt("unlocked", 1 or (1 shl 8)) or 1 or (1 shl 8)
        orbSkin = host.loadInt("orb", 0).coerceIn(0, 5)
        trailStyle = host.loadInt("trail", 0).coerceIn(0, 5)
        if (unlocked and (1 shl orbSkin) == 0) orbSkin = 0
        if (unlocked and (1 shl (8 + trailStyle)) == 0) trailStyle = 0
        soundOn = host.loadInt("sound", 1) == 1
        musicOn = host.loadInt("music", 1) == 1
        vibeOn = host.loadInt("vibe", 1) == 1
        runs = host.loadInt("runs", 0)
        perfectTotal = host.loadInt("perfects", 0)
        tutGate = host.loadInt("tutGate", 0)
    }

    private fun save() {
        host.saveInt("best", best); host.saveInt("dailyBest", dailyBest); host.saveInt("dailyDate", dailyDate)
        host.saveInt("motes", motes); host.saveInt("unlocked", unlocked); host.saveInt("orb", orbSkin); host.saveInt("trail", trailStyle)
        host.saveInt("sound", if (soundOn) 1 else 0); host.saveInt("music", if (musicOn) 1 else 0); host.saveInt("vibe", if (vibeOn) 1 else 0)
        host.saveInt("runs", runs); host.saveInt("perfects", perfectTotal); host.saveInt("tutGate", tutGate)
    }

    // ------------------------------------------------------------------ WorldEvents

    override fun sfx(id: Int, vol: Float, rate: Float) { if (mode == M_PLAY || mode == M_RESULT) host.sound(id, vol, rate) }
    override fun haptic(strong: Boolean) { if (vibeOn) host.haptic(strong) }
    override fun onZone(z: Int) {
        bannerZone = z; bannerT = 0.001f
        host.sound(Sfx.ZONE, 0.8f, 1f)
    }
    override fun onDeath() { held = false }

    // ------------------------------------------------------------------ layout

    fun resize(width: Int, height: Int, density: Float) {
        w = max(1, width).toFloat(); h = max(1, height).toFloat()
        layout()
    }

    fun setInsets(l: Int, t: Int, r: Int, b: Int) { inL = l.toFloat(); inT = t.toFloat(); inR = r.toFloat(); inB = b.toFloat(); layout() }

    private fun layout() {
        u = min(w / 100f, h / 165f)
        s = w / World.W
        vh = h / s
        world.setView(vh)
        demo.setView(vh)
        for (b in buttons) b.visible = false
        val cx = w / 2f
        val bottom = h - inB
        when (mode) {
            M_HOME -> {
                val bw = 64f * u
                val y0 = bottom - 58f * u
                btn(B_PLAY).set(cx - bw / 2f, y0, cx + bw / 2f, y0 + 15f * u).apply { label = "PLAY"; style = 1; icon = Icon.PLAY }
                val y1 = y0 + 19f * u
                btn(B_DAILY).set(cx - bw / 2f, y1, cx - 1.5f * u, y1 + 12.5f * u).apply { label = "DAILY"; style = 0; icon = Icon.CAL }
                btn(B_STYLE).set(cx + 1.5f * u, y1, cx + bw / 2f, y1 + 12.5f * u).apply { label = "STYLE"; style = 0; icon = Icon.STYLE }
                toggles(y1 + 20f * u)
            }
            M_PLAY -> {
                val r = 6.2f * u
                val bx = inL + 4f * u + r; val by = inT + 4f * u + r
                btn(B_PAUSE).set(bx - r, by - r, bx + r, by + r).apply { style = 2; icon = Icon.PAUSE; label = "" }
                if (paused) {
                    val bw = 56f * u
                    val y0 = h * 0.42f
                    btn(B_RESUME).set(cx - bw / 2f, y0, cx + bw / 2f, y0 + 13.5f * u).apply { label = "RESUME"; style = 1; icon = Icon.PLAY }
                    btn(B_RESTART).set(cx - bw / 2f, y0 + 17f * u, cx - 1.5f * u, y0 + 29f * u).apply { label = "RETRY"; style = 0; icon = Icon.RETRY }
                    btn(B_HOME).set(cx + 1.5f * u, y0 + 17f * u, cx + bw / 2f, y0 + 29f * u).apply { label = "HOME"; style = 0; icon = Icon.HOME }
                    toggles(y0 + 38f * u)
                }
            }
            M_RESULT -> {
                val bw = 64f * u
                val y0 = bottom - 34f * u
                btn(B_RETRY).set(cx - bw / 2f, y0, cx + bw / 2f, y0 + 14.5f * u).apply { label = "CLIMB AGAIN"; style = 1; icon = Icon.RETRY }
                btn(B_HOME).set(cx - bw / 2f, y0 + 18f * u, cx + bw / 2f, y0 + 29f * u).apply { label = "HOME"; style = 0; icon = Icon.HOME }
            }
            M_STYLE -> {
                val r = 6.2f * u
                val bx = inL + 4f * u + r; val by = inT + 4f * u + r
                btn(B_BACK).set(bx - r, by - r, bx + r, by + r).apply { style = 2; icon = Icon.CLOSE; label = "" }
                val tw = 30f * u
                val ty = inT + 20f * u
                btn(B_TAB0).set(cx - tw, ty, cx, ty + 9f * u).apply { label = "ORB"; style = 0; icon = 0 }
                btn(B_TAB1).set(cx, ty, cx + tw, ty + 9f * u).apply { label = "TRAIL"; style = 0; icon = 0 }
                val top = ty + 14f * u
                val gap = 3f * u
                val gw = min(w - inL - inR - 8f * u, 92f * u)
                val cw = (gw - gap) / 2f
                val avail = bottom - 5f * u - top
                val ch = min((avail - gap * 2f) / 3f, cw * 1.25f)
                val left = cx - gw / 2f
                for (i in 0 until 6) {
                    val col = i % 2; val row = i / 2
                    val l = left + col * (cw + gap); val t = top + row * (ch + gap)
                    btn(B_ITEM0 + i).set(l, t, l + cw, t + ch).apply { style = 3; label = ""; icon = 0 }
                }
            }
        }
    }

    private fun toggles(y: Float) {
        val r = 6f * u
        val cx = w / 2f
        btn(B_SOUND).set(cx - 18f * u - r, y, cx - 18f * u + r, y + 2 * r).apply { style = 2; icon = Icon.SOUND; off = !soundOn; label = "" }
        btn(B_MUSIC).set(cx - r, y, cx + r, y + 2 * r).apply { style = 2; icon = Icon.MUSIC; off = !musicOn; label = "" }
        btn(B_VIBE).set(cx + 18f * u - r, y, cx + 18f * u + r, y + 2 * r).apply { style = 2; icon = Icon.VIBE; off = !vibeOn; label = "" }
    }

    private fun go(m: Int) {
        mode = m
        pressed = null
        held = false
        fadeIn = 1f
        layout()
    }

    // ------------------------------------------------------------------ flow

    private fun startRun(isDaily: Boolean) {
        daily = isDaily
        val seed = if (isDaily) host.today() * 7919L + 13L else System.nanoTime() xor (runs * 1_000_003L)
        world = World(seed)
        world.events = this
        world.setView(vh)
        runMotesCommitted = 0
        paused = false
        playing = true
        newBest = false
        bannerZone = 0; bannerT = 0f
        introShown = false
        if (isDaily && dailyDate != host.today()) { dailyDate = host.today(); dailyBest = 0 }
        go(M_PLAY)
    }

    private fun commitMotes() {
        val add = world.motes - runMotesCommitted
        if (add > 0) { motes += add; runMotesCommitted = world.motes; save() }
    }

    /** Quitting from the pause menu still banks motes and any record height. */
    private fun abandonRun() {
        commitMotes()
        playing = false
        val hm = world.heightM.toInt()
        if (hm > best) best = hm
        if (daily && dailyDate == host.today() && hm > dailyBest) dailyBest = hm
        perfectTotal += world.perfects
        save()
    }

    private fun endRun() {
        playing = false
        commitMotes()
        val hm = world.heightM.toInt()
        runs++
        perfectTotal += world.perfects
        newBest = hm > best && hm > 0
        if (newBest) best = hm
        if (daily) {
            if (dailyDate != host.today()) { dailyDate = host.today(); dailyBest = 0 }
            if (hm > dailyBest) dailyBest = hm
        }
        if (world.heightM > 300f) tutGate = 1
        save()
        resultT = 0f
        go(M_RESULT)
        if (newBest) host.sound(Sfx.BEST, 0.8f, 1f)
    }

    fun onBack(): Boolean {
        when (mode) {
            M_HOME -> return false
            M_PLAY -> { if (paused) resume() else pause() }
            M_RESULT -> go(M_HOME)
            M_STYLE -> go(M_HOME)
        }
        host.sound(Sfx.TAP, 0.5f, 1f)
        return true
    }

    fun onPause() {
        held = false
        pressed = null
        if (mode == M_PLAY && !world.dead) { if (world.started) pause() }
        if (mode == M_PLAY) commitMotes()
        save()
    }

    private fun pause() {
        if (mode != M_PLAY || world.dead) return
        paused = true
        held = false
        commitMotes()
        layout()
    }

    private fun resume() {
        paused = false
        held = false
        layout()
    }

    // ------------------------------------------------------------------ input

    fun touchDown(x: Float, y: Float) {
        val b = buttons.firstOrNull { it.hit(x, y, u * 1.2f) && it.visible }
        if (mode == M_PLAY && !paused) {
            if (b != null && b.id == B_PAUSE) { pressed = b; b.press = 1f; return }
            if (!world.dead) held = true
            return
        }
        pressed = b
        b?.press = 1f
    }

    fun touchMove(x: Float, y: Float) {
        val p = pressed ?: return
        if (!p.hit(x, y, u * 3f)) pressed = null
    }

    fun touchUp(x: Float, y: Float) {
        held = false
        val p = pressed
        pressed = null
        if (p != null && p.hit(x, y, u * 3f) && p.visible) onButton(p)
    }

    fun touchCancel() { held = false; pressed = null }

    private fun onButton(b: Btn) {
        host.sound(Sfx.TAP, 0.5f, 1f)
        when (b.id) {
            B_PLAY -> startRun(false)
            B_DAILY -> startRun(true)
            B_STYLE -> go(M_STYLE)
            B_SOUND -> { soundOn = !soundOn; host.setAudio(soundOn, musicOn); save(); layout() }
            B_MUSIC -> { musicOn = !musicOn; host.setAudio(soundOn, musicOn); save(); layout() }
            B_VIBE -> { vibeOn = !vibeOn; if (vibeOn) host.haptic(false); save(); layout() }
            B_PAUSE -> pause()
            B_RESUME -> resume()
            B_RESTART -> { abandonRun(); startRun(daily) }
            B_HOME -> { if (mode == M_PLAY) abandonRun(); paused = false; go(M_HOME) }
            B_RETRY -> startRun(daily)
            B_BACK -> go(M_HOME)
            B_TAB0 -> tab = 0
            B_TAB1 -> tab = 1
            else -> if (b.id >= B_ITEM0 && b.id < B_ITEM0 + 6) tapItem(b.id - B_ITEM0)
        }
    }

    private fun bit(i: Int) = if (tab == 0) 1 shl i else 1 shl (8 + i)
    private fun owned(i: Int) = unlocked and bit(i) != 0

    private fun tapItem(i: Int) {
        if (owned(i)) {
            if (tab == 0) orbSkin = i else trailStyle = i
            cardFlash[i] = 0.6f
            save()
            return
        }
        val price = Styles.PRICE[i]
        if (motes >= price) {
            motes -= price
            unlocked = unlocked or bit(i)
            if (tab == 0) orbSkin = i else trailStyle = i
            cardFlash[i] = 1f
            host.sound(Sfx.UNLOCK, 0.9f, 1f)
            haptic(true)
            save()
        } else {
            cardShake[i] = 1f
            host.sound(Sfx.NOPE, 0.6f, 1f)
        }
    }

    // ------------------------------------------------------------------ update

    fun update(dtIn: Float) {
        val dt = dtIn.coerceIn(0f, 0.05f)
        time += dt
        if (fadeIn > 0f) fadeIn = max(0f, fadeIn - dt * 3f)
        for (b in buttons) b.press = max(0f, b.press - dt * 5f)
        for (i in 0 until 6) { cardFlash[i] = max(0f, cardFlash[i] - dt * 2f); cardShake[i] = max(0f, cardShake[i] - dt * 3f) }

        when (mode) {
            M_PLAY -> {
                if (!paused) {
                    world.update(dt, held)
                    if (world.started && !introShown) { introShown = true; bannerZone = 0; bannerT = 0.001f }
                    if (bannerT > 0f) bannerT += dt
                    if (bannerT > 3f) bannerT = 0f
                    if (world.dead && world.deadT > 1.15f) endRun()
                }
            }
            M_RESULT -> { world.update(dt, false); resultT += dt }
            else -> stepDemo(dt)
        }

        val scene = when (mode) { M_PLAY -> if (paused) 2 else 1; M_RESULT -> 3; else -> 0 }
        val inten = if (mode == M_PLAY) clamp01(kotlin.math.hypot(world.vx, world.vy) / World.SPIN_MAX) else 0f
        host.setMusicState(scene, if (mode == M_PLAY) world.combo else 0, inten, mode == M_PLAY && world.slowT > 0f, mode == M_PLAY && world.dead)
    }

    private fun stepDemo(dt: Float) {
        if (demoWait > 0f) { demoWait -= dt; demo.update(dt, false); return }
        demo.update(dt, demo.autopilot())
        if ((demo.dead && demo.deadT > 1.2f) || demo.heightM > 700f) {
            demo = World(System.nanoTime(), demo = true)
            demo.apSloppy = 0.35f
            demo.setView(vh)
            demoWait = 0.6f
        }
    }

    // ------------------------------------------------------------------ draw

    fun draw(c: Canvas) {
        when (mode) {
            M_PLAY, M_RESULT -> drawPlay(c)
            M_STYLE -> { drawScene(c, demo, false, 0.7f); drawStyle(c) }
            else -> { drawScene(c, demo, false, 0.35f); drawHome(c) }
        }
        if (fadeIn > 0f) { Draw.fill.color = alphaF(Col.INK, fadeIn * 0.85f); c.drawRect(0f, 0f, w, h, Draw.fill) }
    }

    private fun drawScene(c: Canvas, wd: World, live: Boolean, dim: Float) {
        val sh = wd.shake * wd.shake
        val ox = sh * 14f * sin(time * 53f); val oy = sh * 14f * cos(time * 41f)
        Fx.background(c, w, h, s, wd.camY, vh, time, -vh * 0.62f)
        c.save()
        c.scale(s, s)
        c.translate(ox, -wd.camY + oy)
        Fx.world(c, wd, time, if (live) orbSkin else orbSkin, if (live) trailStyle else trailStyle, if (live) (if (daily) dailyBest else best).toFloat() else 0f, vh, live)
        c.restore()
        Fx.darkness(c, wd, w, h, s, wd.darkness(), time)
        if (live && wd.started && wd.voidSpeed > 0f) {
            val gap = wd.camY + vh - wd.y
            Fx.static(c, w, h, clamp01(1f - (gap - 60f) / 380f), time)
        }
        Fx.vignette(c, w, h, 0.55f + wd.chroma * 0.6f)
        if (dim > 0f) { Draw.fill.color = alphaF(0xFF07060F.toInt(), dim); c.drawRect(0f, 0f, w, h, Draw.fill) }
    }

    private fun drawHome(c: Canvas) {
        val cx = w / 2f
        val ty = inT + h * 0.17f
        val ts = Draw.fit("TETHER", 19f * u, w * 0.86f, Fonts.bold, 0.18f)
        val wob = sin(time * 1.3f) * 0.6f * u
        Draw.glow(c, cx, ty, 40f * u, 0.35f, 0xFF8A5CF6.toInt())
        Draw.text(c, "TETHER", cx - 0.7f * u + wob, ty, ts, withAlpha(Col.CYAN, 200), Fonts.bold, Paint.Align.CENTER, 0.18f)
        Draw.text(c, "TETHER", cx + 0.7f * u - wob, ty, ts, withAlpha(Col.MAGENTA, 200), Fonts.bold, Paint.Align.CENTER, 0.18f)
        Draw.text(c, "TETHER", cx, ty, ts, Col.WHITE, Fonts.bold, Paint.Align.CENTER, 0.18f)
        Draw.text(c, "HOLD  ·  SPIN  ·  RELEASE", cx, ty + 12f * u, 3.4f * u, 0xCCFFFFFF.toInt(), Fonts.med, Paint.Align.CENTER, 0.3f)

        // best pill
        if (best > 0) {
            val label = "BEST  $best m"
            val bw = Draw.width(label, 4f * u, Fonts.bold, 0.12f) + 10f * u
            rc.set(cx - bw / 2f, ty + 19f * u, cx + bw / 2f, ty + 28f * u)
            Draw.glass(c, rc, 4.5f * u, u)
            Draw.text(c, label, cx, rc.centerY(), 4f * u, Col.WHITE, Fonts.bold, Paint.Align.CENTER, 0.12f)
        }
        motePill(c)
        for (b in buttons) if (b.visible) drawButton(c, b)
        // daily subline
        val d = btn(B_DAILY)
        if (d.visible) {
            val sub = if (dailyDate == host.today() && dailyBest > 0) "today $dailyBest m" else "new climb"
            Draw.text(c, sub, d.r.centerX(), d.r.bottom + 3.2f * u, 2.8f * u, 0x99FFFFFF.toInt(), Fonts.med)
        }
    }

    private fun motePill(c: Canvas) {
        val label = "$motes"
        val tw = Draw.width(label, 4.2f * u, Fonts.bold)
        val pw = tw + 13f * u
        val r = w - inR - 4f * u
        rc.set(r - pw, inT + 5f * u, r, inT + 15f * u)
        Draw.glass(c, rc, 5f * u, u)
        Icon.draw(c, Icon.MOTE, rc.left + 5.5f * u, rc.centerY(), 7f * u, Col.GOLD)
        Draw.text(c, label, rc.right - 4f * u, rc.centerY(), 4.2f * u, Col.WHITE, Fonts.bold, Paint.Align.RIGHT)
    }

    private fun drawButton(c: Canvas, b: Btn) {
        val pr = b.press
        val sc = 1f - 0.04f * pr
        c.save()
        c.scale(sc, sc, b.r.centerX(), b.r.centerY())
        val r = b.r
        when (b.style) {
            1 -> {
                val rad = (r.bottom - r.top) / 2f
                Draw.glow(c, r.centerX(), r.centerY(), (r.right - r.left) * 0.62f, 0.35f + 0.1f * sin(time * 2.4f), 0xFF8A5CF6.toInt())
                Draw.shadeRound(c, primSh, r, rad, r.left, 0f, r.right - r.left, 1f)
                Draw.fill.color = 0x30FFFFFF
                c.drawRoundRect(r.left + u, r.top + u * 0.6f, r.right - u, r.centerY(), rad, rad, Draw.fill)
                label(c, b, Col.WHITE, 5.4f * u)
            }
            2 -> {
                val rad = (r.right - r.left) / 2f
                Draw.glass(c, r, rad, u * 0.6f)
                Icon.draw(c, b.icon, r.centerX(), r.centerY(), rad * 1.2f, if (b.off) 0x88FFFFFF.toInt() else Col.WHITE, b.off)
            }
            else -> {
                val rad = min((r.bottom - r.top) / 2f, 5f * u)
                Draw.glass(c, r, rad, u * 0.8f)
                label(c, b, Col.WHITE, 4.3f * u)
            }
        }
        c.restore()
    }

    private fun label(c: Canvas, b: Btn, col: Int, size: Float) {
        val r = b.r
        val sz = Draw.fit(b.label, size, (r.right - r.left) * 0.62f, Fonts.bold, 0.14f)
        if (b.icon != 0) {
            val tw = Draw.width(b.label, sz, Fonts.bold, 0.14f)
            val iw = sz * 1.3f
            val x0 = r.centerX() - (tw + iw) / 2f
            Icon.draw(c, b.icon, x0 + iw * 0.4f, r.centerY(), sz * 1.2f, col)
            Draw.text(c, b.label, x0 + iw, r.centerY(), sz, col, Fonts.bold, Paint.Align.LEFT, 0.14f)
        } else Draw.text(c, b.label, r.centerX(), r.centerY(), sz, col, Fonts.bold, Paint.Align.CENTER, 0.14f)
    }

    // ------------------------------------------------------------------ play

    private fun drawPlay(c: Canvas) {
        val wd = world
        drawScene(c, wd, true, 0f)
        if (mode == M_RESULT) { drawResult(c); return }
        val cx = w / 2f

        // height
        val hm = wd.heightM.toInt()
        val hs = 12f * u
        val top = inT + 11f * u
        val tw = Draw.width("$hm", hs, Fonts.bold)
        Draw.text(c, "$hm", cx + 1.5f * u, top + 0.3f * u, hs, 0x55000000, Fonts.bold)
        Draw.text(c, "$hm", cx, top, hs, Col.WHITE, Fonts.bold)
        Draw.text(c, "m", cx + tw / 2f + 1.2f * u, top + 2.4f * u, 4.2f * u, 0xBBFFFFFF.toInt(), Fonts.med, Paint.Align.LEFT)
        val zn = Zones.ALL[Zones.index(wd.heightM)].name
        Draw.text(c, if (daily) "DAILY  ·  $zn" else zn, cx, top + 9.5f * u, 2.7f * u, 0x99FFFFFF.toInt(), Fonts.med, Paint.Align.CENTER, 0.35f)

        // combo
        if (wd.combo >= 1) {
            val by = top + 19f * u
            val label = "×${wd.combo}"
            val bs = 5.6f * u
            val bw = Draw.width(label, bs, Fonts.bold) + 9f * u
            rc.set(cx - bw / 2f, by - 4.6f * u, cx + bw / 2f, by + 4.6f * u)
            Draw.glow(c, cx, by, bw, 0.25f + min(wd.combo, 10) * 0.04f, Col.GOLD)
            Draw.glass(c, rc, 4.6f * u, u * 0.6f, 0x33FFD66B, 0x99FFD66B.toInt())
            Draw.text(c, label, cx, by, bs, Col.GOLD, Fonts.bold)
            Draw.stroke.color = Col.GOLD; Draw.stroke.strokeWidth = 0.7f * u
            c.drawLine(rc.left + 3f * u, rc.bottom + 1.2f * u, rc.left + 3f * u + (rc.right - rc.left - 6f * u) * clamp01(wd.comboT / 4f), rc.bottom + 1.2f * u, Draw.stroke)
        }

        motePillPlay(c, wd.motes)
        for (b in buttons) if (b.visible && b.id == B_PAUSE) drawButton(c, b)

        // start + tutorial prompts
        if (!wd.started) {
            val py = h * 0.70f
            val pulse = 0.6f + 0.4f * sin(time * 4f)
            Draw.text(c, "HOLD TO TETHER", cx + 0.4f * u, py + 0.5f * u, 5.2f * u, alphaF(0x99000000.toInt(), pulse), Fonts.bold, Paint.Align.CENTER, 0.25f)
            Draw.text(c, "HOLD TO TETHER", cx, py, 5.2f * u, alphaF(Col.WHITE, pulse), Fonts.bold, Paint.Align.CENTER, 0.25f)
            Draw.text(c, "keep holding to spin  ·  let go to fly", cx, py + 7f * u, 3.2f * u, 0xAAFFFFFF.toInt(), Fonts.med, Paint.Align.CENTER, 0.1f)
        } else if (!wd.dead && runs < 3 && perfectTotal + wd.perfects < 4) {
            val hint = when {
                wd.anchor != null && kotlin.math.hypot(wd.vx, wd.vy) < World.SPIN_MAX * 0.7f -> "keep holding  ·  spin up"
                wd.anchor != null -> "let go when the arrow glows gold"
                else -> "hold again to catch the next anchor"
            }
            Draw.text(c, hint, cx, h * 0.80f, 3.6f * u, 0xCCFFFFFF.toInt(), Fonts.med, Paint.Align.CENTER, 0.12f)
        }
        if (tutGate == 0 && wd.heightM > 270f && wd.heightM < 420f && !wd.dead) {
            Draw.text(c, "only anchors in your colour will catch", cx, h * 0.80f, 3.6f * u, alphaF(Col.phase(wd.phase), 0.9f), Fonts.med, Paint.Align.CENTER, 0.12f)
            Draw.text(c, "prism gates flip your colour", cx, h * 0.80f + 5.5f * u, 3.2f * u, 0xAAFFFFFF.toInt(), Fonts.med, Paint.Align.CENTER, 0.12f)
        }
        if (bannerT > 0f) banner(c)
        if (paused) drawPause(c)
    }

    private fun motePillPlay(c: Canvas, n: Int) {
        val label = "$n"
        val tw = Draw.width(label, 4.2f * u, Fonts.bold)
        val pw = tw + 13f * u
        val r = w - inR - 4f * u
        rc.set(r - pw, inT + 6f * u, r, inT + 16.4f * u)
        Draw.glass(c, rc, 5.2f * u, u * 0.6f)
        Icon.draw(c, Icon.MOTE, rc.left + 5.5f * u, rc.centerY(), 7f * u, Col.GOLD)
        Draw.text(c, label, rc.right - 4f * u, rc.centerY(), 4.2f * u, Col.WHITE, Fonts.bold, Paint.Align.RIGHT)
    }

    private fun banner(c: Canvas) {
        val t = bannerT
        val a = if (t < 0.3f) t / 0.3f else if (t > 2.4f) 1f - (t - 2.4f) / 0.6f else 1f
        val z = Zones.ALL[bannerZone.coerceIn(0, Zones.ALL.size - 1)]
        val cy = h * 0.34f
        val sp = 0.5f - 0.2f * smooth(t / 0.8f)
        val size = Draw.fit(z.name, 9f * u, w * 0.84f, Fonts.bold, sp)
        Draw.glow(c, w / 2f, cy, 45f * u, 0.4f * a, z.accent)
        Draw.stroke.color = alphaF(z.accent, a); Draw.stroke.strokeWidth = 0.5f * u
        val lw = w * 0.32f * smooth(t / 0.6f)
        c.drawLine(w / 2f - lw, cy - 8f * u, w / 2f + lw, cy - 8f * u, Draw.stroke)
        c.drawLine(w / 2f - lw, cy + 9f * u, w / 2f + lw, cy + 9f * u, Draw.stroke)
        Draw.text(c, z.name, w / 2f, cy, size, alphaF(Col.WHITE, a), Fonts.bold, Paint.Align.CENTER, sp)
        Draw.text(c, ZONE_SUB[bannerZone.coerceIn(0, 4)], w / 2f, cy + 14f * u, 3.4f * u, alphaF(z.accent, a), Fonts.med, Paint.Align.CENTER, 0.2f)
    }

    private fun drawPause(c: Canvas) {
        Draw.fill.color = 0xB007060F.toInt(); c.drawRect(0f, 0f, w, h, Draw.fill)
        val cx = w / 2f
        rc.set(cx - 34f * u, h * 0.42f - 26f * u, cx + 34f * u, h * 0.42f + 54f * u)
        Draw.glass(c, rc, 6f * u, u)
        Draw.text(c, "PAUSED", cx, h * 0.42f - 15f * u, 7f * u, Col.WHITE, Fonts.bold, Paint.Align.CENTER, 0.3f)
        Draw.text(c, "${world.heightM.toInt()} m  ·  ${world.motes} motes", cx, h * 0.42f - 7f * u, 3.3f * u, 0xAAFFFFFF.toInt(), Fonts.med, Paint.Align.CENTER, 0.1f)
        for (b in buttons) if (b.visible && b.id != B_PAUSE) drawButton(c, b)
    }

    private fun drawResult(c: Canvas) {
        val t = resultT
        val a = smooth(t / 0.35f)
        Draw.fill.color = alphaF(0xC007060F.toInt(), a); c.drawRect(0f, 0f, w, h, Draw.fill)
        val cx = w / 2f
        val slide = (1f - smooth(t / 0.45f)) * 30f * u
        c.save(); c.translate(0f, slide)
        val cardTop = inT + 22f * u
        val cardBot = h - inB - 40f * u
        rc.set(cx - 42f * u, cardTop, cx + 42f * u, cardBot)
        Draw.glass(c, rc, 7f * u, u)
        Draw.text(c, if (daily) "DAILY CLIMB" else "SIGNAL LOST", cx, cardTop + 9f * u, 3.4f * u, 0xBBFFFFFF.toInt(), Fonts.med, Paint.Align.CENTER, 0.45f)
        val hm = world.heightM.toInt()
        val shown = (hm * smooth(t / 1.1f)).toInt()
        val big = Draw.fit("$shown", 24f * u, 60f * u, Fonts.bold)
        val ny = cardTop + 30f * u
        Draw.glow(c, cx, ny, 34f * u, 0.4f, if (newBest) Col.GOLD else Col.CYAN)
        Draw.text(c, "$shown", cx, ny, big, Col.WHITE, Fonts.bold)
        Draw.text(c, "METRES", cx, ny + 15f * u, 3.2f * u, 0xBBFFFFFF.toInt(), Fonts.med, Paint.Align.CENTER, 0.5f)
        var y = ny + 22f * u
        if (newBest && t > 1.1f) {
            val k = easeOutBack(clamp01((t - 1.1f) / 0.3f))
            c.save(); c.scale(k, k, cx, y + 3.5f * u)
            Draw.r2.set(cx - 18f * u, y, cx + 18f * u, y + 7.5f * u)
            Draw.fill.color = Col.GOLD; c.drawRoundRect(Draw.r2, 3.75f * u, 3.75f * u, Draw.fill)
            Draw.text(c, "NEW BEST", cx, y + 3.75f * u, 3.6f * u, Col.INK, Fonts.bold, Paint.Align.CENTER, 0.25f)
            c.restore()
        } else {
            val ref = if (daily) dailyBest else best
            Draw.text(c, "best  $ref m", cx, y + 3.75f * u, 3.6f * u, 0x99FFFFFF.toInt(), Fonts.med, Paint.Align.CENTER, 0.15f)
        }
        y += 14f * u
        val rows = arrayOf(
            "ZONE" to Zones.ALL[Zones.index(world.heightM)].name,
            "PERFECT RELEASES" to "${world.perfects}",
            "BEST COMBO" to "×${world.maxCombo}",
            "NEAR MISSES" to "${world.nearMisses}",
            "MOTES" to "+${world.motes}")
        val rowH = min(8.6f * u, (cardBot - 6f * u - y) / rows.size)
        for ((i, r) in rows.withIndex()) {
            val ra = clamp01((t - 0.3f - i * 0.08f) / 0.25f)
            val yy = y + i * rowH + rowH / 2f
            Draw.text(c, r.first, rc.left + 7f * u, yy, 3.2f * u, alphaF(0xAAFFFFFF.toInt(), ra), Fonts.med, Paint.Align.LEFT, 0.2f)
            val col = if (i == 4) Col.GOLD else Col.WHITE
            Draw.text(c, r.second, rc.right - 7f * u, yy, 4.2f * u, alphaF(col, ra), Fonts.bold, Paint.Align.RIGHT, 0.05f)
            if (i < rows.size - 1) {
                Draw.fill.color = alphaF(0x22FFFFFF, ra)
                c.drawRect(rc.left + 7f * u, yy + rowH / 2f, rc.right - 7f * u, yy + rowH / 2f + 0.25f * u, Draw.fill)
            }
        }
        c.restore()
        if (t > 0.4f) for (b in buttons) if (b.visible) drawButton(c, b)
    }

    // ------------------------------------------------------------------ style gallery

    private val px = FloatArray(32)
    private val py = FloatArray(32)

    private fun drawStyle(c: Canvas) {
        val cx = w / 2f
        Draw.text(c, "STYLE", cx, inT + 10.2f * u, 6.4f * u, Col.WHITE, Fonts.bold, Paint.Align.CENTER, 0.35f)
        motePill(c)
        // segmented tabs
        val t0 = btn(B_TAB0); val t1 = btn(B_TAB1)
        rc.set(t0.r.left, t0.r.top, t1.r.right, t1.r.bottom)
        Draw.glass(c, rc, 4.5f * u, u * 0.6f)
        val sel = if (tab == 0) t0 else t1
        Draw.r2.set(sel.r.left + 0.8f * u, sel.r.top + 0.8f * u, sel.r.right - 0.8f * u, sel.r.bottom - 0.8f * u)
        Draw.fill.color = 0x40FFFFFF; c.drawRoundRect(Draw.r2, 3.7f * u, 3.7f * u, Draw.fill)
        Draw.text(c, "ORB", t0.r.centerX(), t0.r.centerY(), 3.8f * u, if (tab == 0) Col.WHITE else 0x88FFFFFF.toInt(), Fonts.bold, Paint.Align.CENTER, 0.2f)
        Draw.text(c, "TRAIL", t1.r.centerX(), t1.r.centerY(), 3.8f * u, if (tab == 1) Col.WHITE else 0x88FFFFFF.toInt(), Fonts.bold, Paint.Align.CENTER, 0.2f)
        drawButton(c, btn(B_BACK))
        for (i in 0 until 6) card(c, i, btn(B_ITEM0 + i))
    }

    private fun card(c: Canvas, i: Int, b: Btn) {
        val r = b.r
        val own = owned(i)
        val equipped = if (tab == 0) orbSkin == i else trailStyle == i
        val shake = sin(cardShake[i] * 30f) * cardShake[i] * 2f * u
        c.save()
        c.translate(shake, 0f)
        val sc = 1f - 0.03f * b.press
        c.scale(sc, sc, r.centerX(), r.centerY())
        val edge = if (equipped) 0xCC3EF2FF.toInt() else Col.GLASS_EDGE
        Draw.glass(c, r, 5f * u, u * 0.7f, if (equipped) 0x3329D6FF else Col.GLASS, edge)
        if (cardFlash[i] > 0f) { Draw.fill.color = alphaF(Col.WHITE, cardFlash[i] * 0.35f); c.drawRoundRect(r, 5f * u, 5f * u, Draw.fill) }
        // live preview
        val pv = Draw.r2
        pv.set(r.left + 2f * u, r.top + 2f * u, r.right - 2f * u, r.top + (r.bottom - r.top) * 0.66f)
        c.save()
        c.clipRect(pv)
        val k = (pv.right - pv.left) / 300f
        c.translate(pv.centerX(), pv.centerY())
        c.scale(k, k)
        val n = 26
        for (j in 0 until n) {
            val ph = time * 2.2f - (n - 1 - j) * 0.075f + i
            px[j] = cos(ph) * 85f; py[j] = sin(ph * 2f) * 34f
        }
        val col = if (i % 2 == 0) Col.CYAN else Col.MAGENTA
        val trail = if (tab == 1) i else trailStyle
        val skin = if (tab == 0) i else orbSkin
        Fx.trail(c, trail, px, py, n, col, time, 18f)
        val vx = -sin(time * 2.2f + i) * 85f * 2.2f; val vy = cos((time * 2.2f + i) * 2f) * 34f * 4.4f
        Fx.orb(c, skin, px[n - 1], py[n - 1], 18f * (if (tab == 0) 1.5f else 1f), col, time, vx, vy, 0.3f, false)
        c.restore()
        val names = if (tab == 0) Styles.ORBS else Styles.TRAILS
        val ny = r.top + (r.bottom - r.top) * 0.75f
        Draw.text(c, names[i], r.centerX(), ny, 4f * u, if (own) Col.WHITE else 0xAAFFFFFF.toInt(), Fonts.bold, Paint.Align.CENTER, 0.2f)
        val sy = r.top + (r.bottom - r.top) * 0.88f
        when {
            equipped -> {
                Icon.draw(c, Icon.CHECK, r.centerX() - 9f * u, sy, 4f * u, Col.CYAN)
                Draw.text(c, "EQUIPPED", r.centerX() + 1.5f * u, sy, 2.8f * u, Col.CYAN, Fonts.bold, Paint.Align.CENTER, 0.2f)
            }
            own -> Draw.text(c, "TAP TO EQUIP", r.centerX(), sy, 2.8f * u, 0x99FFFFFF.toInt(), Fonts.med, Paint.Align.CENTER, 0.2f)
            else -> {
                val price = Styles.PRICE[i]
                val can = motes >= price
                val label = "$price"
                val tw = Draw.width(label, 3.6f * u, Fonts.bold)
                val x0 = r.centerX() - (tw + 5f * u) / 2f
                Icon.draw(c, Icon.MOTE, x0 + 1.5f * u, sy, 4.5f * u, Col.GOLD)
                Draw.text(c, label, x0 + 4.5f * u, sy, 3.6f * u, if (can) Col.GOLD else 0x88FFD66B.toInt(), Fonts.bold, Paint.Align.LEFT)
                Icon.draw(c, Icon.LOCK, r.right - 5f * u, r.top + 5f * u, 4f * u, 0x88FFFFFF.toInt())
            }
        }
        c.restore()
    }

    // ------------------------------------------------------------------ test hooks

    fun debugBtn(id: Int, out: FloatArray): Boolean {
        val b = buttons.firstOrNull { it.id == id && it.visible } ?: return false
        out[0] = b.r.centerX(); out[1] = b.r.centerY(); return true
    }
    fun visibleButtons(): List<Int> = buttons.filter { it.visible }.map { it.id }
    fun debugTab(t: Int) { tab = t }
    fun debugMotes(m: Int) { motes = m }
}
