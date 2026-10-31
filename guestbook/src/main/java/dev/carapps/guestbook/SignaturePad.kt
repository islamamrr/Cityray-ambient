package dev.carapps.guestbook

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A sheet of paper you sign with a finger. The line gets thinner when you move fast,
 * like a real pen. Finished strokes are cached in a bitmap so long signatures stay smooth.
 */
class SignaturePad(context: Context) : View(context) {

    /** Points are stored as x, y, width triples. */
    private class Stroke(val color: Int) {
        var pts = FloatArray(96)
        var size = 0
        fun add(x: Float, y: Float, w: Float) {
            if (size + 3 > pts.size) pts = pts.copyOf(pts.size * 2)
            pts[size] = x; pts[size + 1] = y; pts[size + 2] = w
            size += 3
        }
        val count get() = size / 3
    }

    var inkColor = Ink.colors[0]
    var onChanged: (() -> Unit)? = null

    private val strokes = ArrayList<Stroke>()
    private var current: Stroke? = null
    private var lastTime = 0L
    private var lastWidth = 0f

    private val maxWidth = dp(7f)
    private val minWidth = dp(2.2f)

    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.paperLine; strokeWidth = dp(2f) }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.inkDim
        typeface = Fonts.serifItalic
        textSize = dp(26f)
        textAlign = Paint.Align.CENTER
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.inkDim
        typeface = Fonts.serif
        textSize = dp(34f)
    }

    private var cache: Bitmap? = null
    private var cacheCanvas: Canvas? = null

    val isEmpty get() = strokes.isEmpty()

    init {
        background = roundRect(Palette.paper, dp(18f), Palette.paperEdge, dpi(2))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // The keyboard can shrink the pad; keep strokes in place and just rebuild the cache.
        rebuildCache()
    }

    private fun rebuildCache() {
        if (width <= 0 || height <= 0) return
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        cache = bmp
        cacheCanvas = Canvas(bmp).also { c -> strokes.forEach { drawStroke(c, it, 0) } }
    }

    fun undo() {
        if (strokes.isEmpty()) return
        strokes.removeAt(strokes.size - 1)
        rebuildCache()
        invalidate()
        onChanged?.invoke()
    }

    fun clear() {
        strokes.clear()
        rebuildCache()
        invalidate()
        onChanged?.invoke()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val s = Stroke(inkColor)
                current = s
                lastWidth = (maxWidth + minWidth) / 2
                lastTime = e.eventTime
                s.add(e.x, e.y, lastWidth)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val s = current ?: return true
                for (i in 0 until e.historySize) addPoint(s, e.getHistoricalX(i), e.getHistoricalY(i), e.getHistoricalEventTime(i))
                addPoint(s, e.x, e.y, e.eventTime)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val s = current ?: return true
                addPoint(s, e.x, e.y, e.eventTime)
                if (s.count == 1) s.add(e.x + 0.5f, e.y + 0.5f, s.pts[2]) // a tap makes a dot
                strokes.add(s)
                cacheCanvas?.let { drawStroke(it, s, 0) }
                current = null
                invalidate()
                onChanged?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> {
                current = null
                invalidate()
            }
        }
        return true
    }

    private fun addPoint(s: Stroke, x: Float, y: Float, time: Long) {
        val i = s.size - 3
        val dist = hypot(x - s.pts[i], y - s.pts[i + 1])
        if (dist < dp(1.5f)) return
        val dt = max(1L, time - lastTime)
        lastTime = time
        val speed = dist / dt / dp(1f) // dp per ms
        val target = (maxWidth - speed * dp(1.6f)).coerceIn(minWidth, maxWidth)
        lastWidth += (target - lastWidth) * 0.35f // smooth so the line doesn't flicker
        s.add(x, y, lastWidth)
    }

    private fun drawStroke(c: Canvas, s: Stroke, offset: Int, scale: Float = 1f, dx: Float = 0f, dy: Float = 0f) {
        inkPaint.color = s.color
        val p = s.pts
        var i = offset
        while (i + 3 < s.size) {
            inkPaint.strokeWidth = (p[i + 2] + p[i + 5]) / 2 * scale
            c.drawLine(p[i] * scale + dx, p[i + 1] * scale + dy, p[i + 3] * scale + dx, p[i + 4] * scale + dy, inkPaint)
            i += 3
        }
    }

    override fun onDraw(canvas: Canvas) {
        val baseY = height * 0.72f
        val left = dp(36f)
        canvas.drawLine(left, baseY, width - left, baseY, linePaint)
        canvas.drawText("×", left, baseY - dp(10f), crossPaint)
        if (strokes.isEmpty() && current == null) {
            canvas.drawText("Sign here", width / 2f, height * 0.42f, hintPaint)
        }
        cache?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        current?.let { drawStroke(canvas, it, 0) }
    }

    /** The signature cropped to its ink with a little margin, on a transparent background. */
    fun export(maxSide: Int = 1400): Bitmap? {
        if (strokes.isEmpty()) return null
        val bounds = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (s in strokes) {
            var i = 0
            while (i < s.size) {
                val r = s.pts[i + 2]
                bounds.left = min(bounds.left, s.pts[i] - r)
                bounds.top = min(bounds.top, s.pts[i + 1] - r)
                bounds.right = max(bounds.right, s.pts[i] + r)
                bounds.bottom = max(bounds.bottom, s.pts[i + 1] + r)
                i += 3
            }
        }
        bounds.inset(-dp(12f), -dp(12f))
        val scale = min(1f, maxSide / max(bounds.width(), bounds.height()))
        val w = max(1, (bounds.width() * scale).toInt())
        val h = max(1, (bounds.height() * scale).toInt())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        for (s in strokes) drawStroke(c, s, 0, scale, -bounds.left * scale, -bounds.top * scale)
        return out
    }
}
