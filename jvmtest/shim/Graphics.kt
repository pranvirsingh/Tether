@file:Suppress("unused", "UNUSED_PARAMETER")

package android.graphics

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color as AColor
import java.awt.Font
import java.awt.Graphics2D
import java.awt.MultipleGradientPaint
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.TexturePaint
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File

class RectF {
    @JvmField var left = 0f
    @JvmField var top = 0f
    @JvmField var right = 0f
    @JvmField var bottom = 0f
    constructor()
    constructor(l: Float, t: Float, r: Float, b: Float) { set(l, t, r, b) }
    fun set(l: Float, t: Float, r: Float, b: Float) { left = l; top = t; right = r; bottom = b }
    fun set(o: RectF) { set(o.left, o.top, o.right, o.bottom) }
    fun offset(dx: Float, dy: Float) { left += dx; right += dx; top += dy; bottom += dy }
    fun inset(dx: Float, dy: Float) { left += dx; right -= dx; top += dy; bottom -= dy }
    fun contains(x: Float, y: Float) = left < right && top < bottom && x >= left && x < right && y >= top && y < bottom
    fun centerX() = (left + right) * 0.5f
    fun centerY() = (top + bottom) * 0.5f
    fun width() = right - left
    fun height() = bottom - top
}

class Typeface private constructor(val font: Font) {
    companion object {
        @JvmField val DEFAULT_BOLD = Typeface(Font("Serif", Font.BOLD, 12))
        @JvmField val SERIF = Typeface(Font("Serif", Font.PLAIN, 12))
        @JvmField val DEFAULT = Typeface(Font("SansSerif", Font.PLAIN, 12))
        fun createFromFile(path: String): Typeface = Typeface(Font.createFont(Font.TRUETYPE_FONT, File(path)))
    }
}

open class Shader {
    enum class TileMode { CLAMP, REPEAT, MIRROR }
    internal var local: Matrix? = null
    fun setLocalMatrix(m: Matrix) { local = m.copy() }
    internal open fun toAwt(): java.awt.Paint? = null
}

class Matrix {
    var sx = 1f; var sy = 1f; var tx = 0f; var ty = 0f
    fun setScale(x: Float, y: Float) { sx = x; sy = y; tx = 0f; ty = 0f }
    fun postTranslate(x: Float, y: Float): Boolean { tx += x; ty += y; return true }
    fun setTranslate(x: Float, y: Float) { sx = 1f; sy = 1f; tx = x; ty = y }
    fun copy(): Matrix = Matrix().also { it.sx = sx; it.sy = sy; it.tx = tx; it.ty = ty }
}

class RadialGradient(val x: Float, val y: Float, val r: Float, val colors: IntArray, val stops: FloatArray?, val mode: TileMode) : Shader() {
    override fun toAwt(): java.awt.Paint {
        val st = stops ?: FloatArray(colors.size) { it / (colors.size - 1f) }
        val m = local ?: Matrix()
        val cx = x * m.sx + m.tx; val cy = y * m.sy + m.ty; val rr = r * m.sx
        return java.awt.RadialGradientPaint(Point2D.Float(cx, cy), maxOf(rr, 0.01f), st, Array(colors.size) { AColor(colors[it], true) },
            MultipleGradientPaint.CycleMethod.NO_CYCLE)
    }
}

class LinearGradient(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val colors: IntArray, val stops: FloatArray?, val mode: TileMode) : Shader() {
    override fun toAwt(): java.awt.Paint {
        val st = stops ?: FloatArray(colors.size) { it / (colors.size - 1f) }
        val m = local ?: Matrix()
        return java.awt.LinearGradientPaint(Point2D.Float(x0 * m.sx + m.tx, y0 * m.sy + m.ty), Point2D.Float(x1 * m.sx + m.tx, y1 * m.sy + m.ty + (if (x0 == x1 && y0 == y1) 0.01f else 0f)), st, Array(colors.size) { AColor(colors[it], true) },
            MultipleGradientPaint.CycleMethod.NO_CYCLE)
    }
}

class Bitmap private constructor(val img: BufferedImage) {
    enum class Config { ARGB_8888 }
    fun setPixels(px: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) = img.setRGB(x, y, w, h, px, offset, stride)
    val width get() = img.width
    val height get() = img.height
    companion object {
        fun createBitmap(w: Int, h: Int, c: Config) = Bitmap(BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB))
    }
}

class BitmapShader(val bmp: Bitmap, val tx: TileMode, val ty: TileMode) : Shader() {
    override fun toAwt(): java.awt.Paint {
        val m = local ?: Matrix()
        return TexturePaint(bmp.img, Rectangle2D.Float(m.tx, m.ty, bmp.width * m.sx, bmp.height * m.sy))
    }
}

class Path {
    enum class Direction { CW, CCW }
    enum class FillType { WINDING, EVEN_ODD }
    internal val p = Path2D.Float()
    var fillType: FillType = FillType.WINDING
        set(v) { field = v; p.windingRule = if (v == FillType.EVEN_ODD) Path2D.WIND_EVEN_ODD else Path2D.WIND_NON_ZERO }
    fun reset() { p.reset(); fillType = FillType.WINDING }
    fun moveTo(x: Float, y: Float) = p.moveTo(x, y)
    fun lineTo(x: Float, y: Float) { ensure(); p.lineTo(x, y) }
    fun quadTo(a: Float, b: Float, c: Float, d: Float) { ensure(); p.quadTo(a, b, c, d) }
    fun cubicTo(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) { ensure(); p.curveTo(a, b, c, d, e, f) }
    fun close() { if (p.currentPoint != null) p.closePath() }
    fun addCircle(x: Float, y: Float, r: Float, d: Direction) = p.append(Ellipse2D.Float(x - r, y - r, r * 2, r * 2), false)
    fun addRect(l: Float, t: Float, r: Float, b: Float, d: Direction) = p.append(Rectangle2D.Float(l, t, r - l, b - t), false)
    fun addOval(r: RectF, d: Direction) = p.append(Ellipse2D.Float(r.left, r.top, r.width(), r.height()), false)
    private fun ensure() { if (p.currentPoint == null) p.moveTo(0f, 0f) }
}

class Paint() {
    enum class Style { FILL, STROKE, FILL_AND_STROKE }
    enum class Cap { BUTT, ROUND, SQUARE }
    enum class Join { MITER, ROUND, BEVEL }
    enum class Align { LEFT, CENTER, RIGHT }
    class FontMetrics { @JvmField var ascent = 0f; @JvmField var descent = 0f; @JvmField var top = 0f; @JvmField var bottom = 0f; @JvmField var leading = 0f }

    constructor(flags: Int) : this()
    companion object {
        const val ANTI_ALIAS_FLAG = 1
        val frc = FontRenderContext(null, true, true)
    }

    var style = Style.FILL
    var strokeCap = Cap.BUTT
    var strokeJoin = Join.MITER
    var textAlign = Align.LEFT
    var color: Int = 0xFF000000.toInt()
    var alpha: Int
        get() = (color ushr 24) and 255
        set(v) { color = (color and 0x00FFFFFF) or (v.coerceIn(0, 255) shl 24) }
    var strokeWidth = 0f
    var textSize = 12f
    var typeface: Typeface? = Typeface.DEFAULT
    var shader: Shader? = null
    var isFilterBitmap = false
    var isDither = false
    var letterSpacing = 0f

    internal fun font(): Font {
        val f = (typeface ?: Typeface.DEFAULT).font.deriveFont(textSize)
        return if (letterSpacing == 0f) f else f.deriveFont(mapOf(java.awt.font.TextAttribute.TRACKING to letterSpacing))
    }
    fun measureText(s: String): Float = font().getStringBounds(s, frc).width.toFloat()
    fun getFontMetrics(out: FontMetrics): Float {
        val m = fontMetrics
        out.ascent = m.ascent; out.descent = m.descent; out.top = m.top; out.bottom = m.bottom
        return m.descent - m.ascent
    }
    val fontMetrics: FontMetrics
        get() {
            val lm = font().getLineMetrics("Hg", frc)
            return FontMetrics().also { it.ascent = -lm.ascent; it.descent = lm.descent; it.top = -lm.ascent * 1.1f; it.bottom = lm.descent * 1.1f }
        }
}

class Canvas(val img: BufferedImage) {
    private val g: Graphics2D = img.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }
    private val stack = ArrayList<Pair<AffineTransform, Shape?>>()
    var ops = 0L

    val width get() = img.width
    val height get() = img.height

    fun save(): Int { stack.add(Pair(g.transform, g.clip)); return stack.size }
    fun restore() {
        check(stack.isNotEmpty()) { "restore underflow" }
        val (t, c) = stack.removeAt(stack.size - 1)
        g.transform = t; g.clip = c
    }
    val saveCount get() = stack.size + 1
    fun translate(x: Float, y: Float) { chk(x, y); g.translate(x.toDouble(), y.toDouble()) }
    fun rotate(deg: Float) { chk(deg); g.rotate(Math.toRadians(deg.toDouble())) }
    fun rotate(deg: Float, px: Float, py: Float) { translate(px, py); rotate(deg); translate(-px, -py) }
    fun scale(sx: Float, sy: Float) { chk(sx, sy); g.scale(sx.toDouble(), sy.toDouble()) }
    fun scale(sx: Float, sy: Float, px: Float, py: Float) { translate(px, py); scale(sx, sy); translate(-px, -py) }
    fun clipPath(p: Path): Boolean { g.clip(p.p); return true }
    fun clipRect(r: RectF): Boolean { g.clip(Rectangle2D.Float(r.left, r.top, r.width(), r.height())); return true }

    private fun chk(vararg v: Float) { for (x in v) check(!x.isNaN() && !x.isInfinite()) { "NaN/Inf passed to canvas" } }

    private fun paintShape(s: Shape, p: Paint) {
        ops++
        val old = g.composite
        val sh = p.shader?.toAwt()
        if (sh != null) {
            g.paint = sh
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, p.alpha / 255f)
        } else g.color = AColor(p.color, true)
        if (p.style != Paint.Style.STROKE) g.fill(s)
        if (p.style != Paint.Style.FILL) {
            g.stroke = BasicStroke(p.strokeWidth,
                when (p.strokeCap) { Paint.Cap.ROUND -> BasicStroke.CAP_ROUND; Paint.Cap.SQUARE -> BasicStroke.CAP_SQUARE; else -> BasicStroke.CAP_BUTT },
                when (p.strokeJoin) { Paint.Join.ROUND -> BasicStroke.JOIN_ROUND; Paint.Join.BEVEL -> BasicStroke.JOIN_BEVEL; else -> BasicStroke.JOIN_MITER })
            g.draw(s)
        }
        g.composite = old
    }

    fun drawRect(l: Float, t: Float, r: Float, b: Float, p: Paint) { chk(l, t, r, b); paintShape(Rectangle2D.Float(l, t, r - l, b - t), p) }
    fun drawRect(r: RectF, p: Paint) = drawRect(r.left, r.top, r.right, r.bottom, p)
    fun drawRoundRect(r: RectF, rx: Float, ry: Float, p: Paint) {
        chk(r.left, r.top, r.right, r.bottom, rx, ry)
        paintShape(RoundRectangle2D.Float(r.left, r.top, r.width(), r.height(), rx * 2, ry * 2), p)
    }
    fun drawRoundRect(l: Float, t: Float, r: Float, b: Float, rx: Float, ry: Float, p: Paint) = drawRoundRect(RectF(l, t, r, b), rx, ry, p)
    fun drawArc(l: Float, t: Float, r: Float, b: Float, start: Float, sweep: Float, useCenter: Boolean, p: Paint) = drawArc(RectF(l, t, r, b), start, sweep, useCenter, p)
    fun drawCircle(x: Float, y: Float, r: Float, p: Paint) { chk(x, y, r); paintShape(Ellipse2D.Float(x - r, y - r, r * 2, r * 2), p) }
    fun drawOval(r: RectF, p: Paint) { chk(r.left, r.top, r.right, r.bottom); paintShape(Ellipse2D.Float(r.left, r.top, r.width(), r.height()), p) }
    fun drawPath(path: Path, p: Paint) = paintShape(path.p, p)
    fun drawLine(a: Float, b: Float, c: Float, d: Float, p: Paint) {
        chk(a, b, c, d)
        val s = p.style; p.style = Paint.Style.STROKE
        paintShape(Line2D.Float(a, b, c, d), p); p.style = s
    }
    fun drawArc(r: RectF, start: Float, sweep: Float, useCenter: Boolean, p: Paint) {
        chk(r.left, r.top, r.right, r.bottom, start, sweep)
        val type = if (useCenter) Arc2D.PIE else if (p.style == Paint.Style.STROKE) Arc2D.OPEN else Arc2D.CHORD
        paintShape(Arc2D.Float(r.left, r.top, r.width(), r.height(), -start, -sweep, type), p)
    }
    fun drawText(s: String, x: Float, y: Float, p: Paint) {
        chk(x, y)
        val f = p.font()
        val w = f.getStringBounds(s, Paint.frc).width.toFloat()
        val ox = when (p.textAlign) { Paint.Align.CENTER -> x - w / 2f; Paint.Align.RIGHT -> x - w; else -> x }
        val gv = f.createGlyphVector(g.fontRenderContext, s)
        val outline = gv.getOutline(ox, y)
        paintShape(outline, p)
    }
    fun drawColor(c: Int) {
        ops++
        val t = g.transform
        g.transform = AffineTransform()
        g.color = AColor(c, true)
        g.fillRect(-10, -10, img.width + 20, img.height + 20)
        g.transform = t
    }
}
