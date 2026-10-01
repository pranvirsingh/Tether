package com.pranvir.tether

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import java.util.Calendar

class MainActivity : Activity(), Host {
    private lateinit var view: GameView
    private lateinit var game: Game
    private var audio: Audio? = null
    private val prefs by lazy { getSharedPreferences("tether", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            Fonts.med = Typeface.createFromAsset(assets, "fonts/sg_med.ttf")
            Fonts.bold = Typeface.createFromAsset(assets, "fonts/sg_bold.ttf")
        } catch (e: Exception) {
            Fonts.med = Typeface.DEFAULT; Fonts.bold = Typeface.DEFAULT_BOLD
        }
        audio = Audio(this)
        game = Game(this)
        view = GameView(this, game)
        setContentView(view)
        goImmersive()
    }

    @Suppress("DEPRECATION")
    private fun goImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive() else game.touchCancel()
    }

    override fun onResume() {
        super.onResume()
        audio?.resume()
        view.start()
    }

    override fun onPause() {
        view.stop()
        game.onPause()
        flushPrefs()
        audio?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        audio?.release()
        audio = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!game.onBack()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ---------------------------------------------------------------- Host

    private var pending: android.content.SharedPreferences.Editor? = null
    private val flushTask = Runnable { flushPrefs() }

    private fun editor(): android.content.SharedPreferences.Editor {
        return pending ?: prefs.edit().also {
            pending = it
            window.decorView.post(flushTask)
        }
    }

    private fun flushPrefs() {
        val e = pending ?: return
        pending = null
        e.apply()
    }

    override fun loadInt(key: String, def: Int): Int {
        flushPrefs()
        return try { prefs.getInt(key, def) } catch (e: Exception) { def }
    }
    override fun saveInt(key: String, v: Int) { editor().putInt(key, v) }
    override fun sound(id: Int, vol: Float, rate: Float) { audio?.play(id, vol, rate) }
    override fun setAudio(soundOn: Boolean, musicOn: Boolean) { audio?.setEnabled(soundOn, musicOn) }
    override fun setMusicState(scene: Int, combo: Int, intensity: Float, slow: Boolean, dead: Boolean) { audio?.setState(scene, combo, intensity, slow, dead) }
    override fun haptic(strong: Boolean) {
        if (::view.isInitialized) {
            view.performHapticFeedback(if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }
    override fun today(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }
}

class GameView(context: Context, private val game: Game) : View(context) {
    private var running = false
    private var last = 0L
    private var activePointer = -1

    init {
        isFocusable = true
        isHapticFeedbackEnabled = true
    }

    fun start() {
        running = true
        last = 0L
        postInvalidateOnAnimation()
    }

    fun stop() {
        running = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        game.resize(w, h, resources.displayMetrics.density)
        requestApplyInsets()
    }

    @Suppress("DEPRECATION")
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        var t = 0; var b = 0; var l = 0; var r = 0
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            t = i.top; b = i.bottom; l = i.left; r = i.right
        } else {
            t = insets.systemWindowInsetTop; b = insets.systemWindowInsetBottom
            l = insets.systemWindowInsetLeft; r = insets.systemWindowInsetRight
            if (Build.VERSION.SDK_INT >= 28) {
                insets.displayCutout?.let {
                    t = maxOf(t, it.safeInsetTop); b = maxOf(b, it.safeInsetBottom)
                    l = maxOf(l, it.safeInsetLeft); r = maxOf(r, it.safeInsetRight)
                }
            }
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) t = maxOf(t, resources.getDimensionPixelSize(id))
        }
        game.setInsets(l, t, r, b)
        return super.onApplyWindowInsets(insets)
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 1f / 60f else (now - last) / 1_000_000_000f
        last = now
        try {
            game.update(dt)
            game.draw(canvas)
        } catch (e: Exception) {
            android.util.Log.e("Tether", "frame error", e)
        }
        if (running) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointer = e.getPointerId(0)
                game.touchDown(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = e.findPointerIndex(activePointer)
                if (idx >= 0) game.touchMove(e.getX(idx), e.getY(idx))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // a second finger lifting never lets go of the tether; hand the hold over instead
                if (e.getPointerId(e.actionIndex) == activePointer) {
                    val other = if (e.actionIndex == 0) 1 else 0
                    activePointer = if (other < e.pointerCount) e.getPointerId(other) else -1
                }
            }
            MotionEvent.ACTION_UP -> {
                game.touchUp(e.x, e.y)
                activePointer = -1
            }
            MotionEvent.ACTION_CANCEL -> {
                game.touchCancel()
                activePointer = -1
            }
        }
        return true
    }
}
