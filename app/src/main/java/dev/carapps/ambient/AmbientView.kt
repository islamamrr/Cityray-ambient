package dev.carapps.ambient

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The whole ambient screen, drawn by hand on a Canvas.
 * Themes: 0 = Minimal, 1 = Gauge, 2 = Aurora.
 */
class AmbientView(context: Context) : View(context) {

    interface Listener {
        fun onThemeChanged(theme: Int)
        fun onDimChanged(dimmed: Boolean)
        fun onPlayPause()
        fun onNext()
        fun onPrevious()
        fun onRequestAccess()
    }

    private class Palette(
        val bg: Int,
        val text: Int,
        val sub: Int,
        val accent: Int,
        val card: Int,
        val track: Int,
    )

    var listener: Listener? = null

    var themeIndex = 0
        set(v) {
            field = ((v % THEME_COUNT) + THEME_COUNT) % THEME_COUNT
            invalidate()
        }

    var dimmed = false
        set(v) {
            field = v
            invalidate()
        }

    private var nowPlaying: NowPlaying? = null
    private var hasAccess = true
    private var message: String? = null
    private var messageUntil = 0L
    private var dotsUntil = 0L
    private val startTime = System.currentTimeMillis()

    private val palettes = arrayOf(
        // Minimal
        Palette(0xFF050608.toInt(), 0xFFF2F4F8.toInt(), 0xFF7D8596.toInt(), 0xFF9FB4FF.toInt(), 0x14FFFFFF, 0xFF1A1E27.toInt()),
        // Gauge
        Palette(0xFF07090E.toInt(), 0xFFFFFFFF.toInt(), 0xFF5F6779.toInt(), 0xFFFF5A36.toInt(), 0x12FFFFFF, 0xFF161B27.toInt()),
        // Aurora
        Palette(0xFF040714.toInt(), 0xFFFFFFFF.toInt(), 0xFFB9C3E6.toInt(), 0xFF64FFDA.toInt(), 0x1FFFFFFF, 0x33FFFFFF),
    )

    private val thin = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val condensedBold = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val smallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val artRect = RectF()

    // Touch targets, in screen coordinates.
    private val prevHit = RectF()
    private val playHit = RectF()
    private val nextHit = RectF()
    private val accessHit = RectF()

    private var blobShaders: Array<RadialGradient> = emptyArray()
    private var blobRadius = 0f

    private val datePattern: String by lazy {
        DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM")
    }

    private val tick = Runnable { invalidate() }

    fun setNowPlaying(np: NowPlaying?, access: Boolean) {
        nowPlaying = np
        hasAccess = access
        invalidate()
    }

    fun flashMessage(text: String, durationMs: Long = 4000L) {
        message = text
        messageUntil = System.currentTimeMillis() + durationMs
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        blobRadius = max(w, h) * 0.55f
        val colors = intArrayOf(0xFF1DE9B6.toInt(), 0xFF7C4DFF.toInt(), 0xFF2979FF.toInt())
        blobShaders = Array(colors.size) { i ->
            val c = colors[i]
            RadialGradient(
                0f, 0f, blobRadius,
                intArrayOf(Color.argb(115, Color.red(c), Color.green(c), Color.blue(c)), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP,
            )
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // Layout unit: the width on a portrait screen, but never so big that things overflow.
        val u = min(w, h * 0.62f)
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val p = palettes[themeIndex]

        canvas.drawColor(p.bg)
        if (themeIndex == 2) drawAuroraBackground(canvas, w, h, now)

        // Burn-in protection: nudge everything a few pixels each minute.
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val shift = u * 0.012f
        val dx = (sin(minuteOfDay * 1.7) * shift).toFloat()
        val dy = (cos(minuteOfDay * 1.3) * shift).toFloat()

        canvas.save()
        canvas.translate(dx, dy)
        when (themeIndex) {
            0 -> drawMinimal(canvas, w, h, u, cal, p)
            1 -> drawGauge(canvas, w, h, u, cal, p)
            else -> drawAuroraClock(canvas, w, h, u, cal, p)
        }
        drawMedia(canvas, w, h, u, p, dx, dy)
        canvas.restore()

        if (dimmed) canvas.drawColor(0xA6000000.toInt())
        drawOverlays(canvas, w, h, u, now, p)

        // Minimal only needs a redraw once a second; the animated themes run at ~30 fps.
        val animating = themeIndex != 0 || now < dotsUntil || now < messageUntil
        removeCallbacks(tick)
        postDelayed(tick, if (animating) 33L else 1000L - (now % 1000L))
    }

    // ---------- Themes ----------

    private fun drawMinimal(c: Canvas, w: Float, h: Float, u: Float, cal: Calendar, p: Palette) {
        val cy = h * 0.34f
        timePaint.typeface = thin
        timePaint.color = p.text
        timePaint.textSize = u * 0.32f
        c.drawText(timeText(cal), w / 2, cy, timePaint)

        datePaint.typeface = light
        datePaint.color = p.sub
        datePaint.textSize = u * 0.05f
        c.drawText(dateText(cal), w / 2, cy + u * 0.11f, datePaint)

        // A hairline that fills up over the minute.
        val lineW = u * 0.5f
        val lx = (w - lineW) / 2
        val ly = cy + u * 0.17f
        val lh = max(2f, u * 0.003f)
        fill.shader = null
        fill.color = p.track
        c.drawRect(lx, ly, lx + lineW, ly + lh, fill)
        fill.color = p.accent
        c.drawRect(lx, ly, lx + lineW * (cal.get(Calendar.SECOND) / 59f), ly + lh, fill)
    }

    private fun drawGauge(c: Canvas, w: Float, h: Float, u: Float, cal: Calendar, p: Palette) {
        val cx = w / 2
        val cy = h * 0.36f
        val r = u * 0.40f
        val start = 135f
        val sweep = 270f
        val sec = cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000f

        rect.set(cx - r, cy - r, cx + r, cy + r)
        stroke.shader = null
        stroke.strokeWidth = u * 0.03f
        stroke.color = p.track
        c.drawArc(rect, start, sweep, false, stroke)
        stroke.color = p.accent
        c.drawArc(rect, start, sweep * sec / 60f, false, stroke)

        // Tachometer-style ticks and labels, counting seconds.
        val outer = r - u * 0.045f
        smallPaint.textAlign = Paint.Align.CENTER
        smallPaint.typeface = condensedBold
        smallPaint.textSize = u * 0.034f
        smallPaint.color = p.sub
        for (i in 0..60) {
            val a = Math.toRadians((start + sweep * i / 60f).toDouble())
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            val major = i % 10 == 0
            val len = when {
                major -> u * 0.05f
                i % 5 == 0 -> u * 0.03f
                else -> u * 0.015f
            }
            stroke.strokeWidth = if (major) u * 0.007f else u * 0.0035f
            stroke.color = if (i <= sec) p.text else p.sub
            c.drawLine(cx + ca * outer, cy + sa * outer, cx + ca * (outer - len), cy + sa * (outer - len), stroke)
            if (major) {
                val lr = outer - u * 0.09f
                c.drawText(i.toString(), cx + ca * lr, cy + sa * lr + smallPaint.textSize * 0.35f, smallPaint)
            }
        }

        // Needle tip.
        val a = Math.toRadians((start + sweep * sec / 60f).toDouble())
        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        stroke.color = p.accent
        stroke.strokeWidth = u * 0.012f
        c.drawLine(cx + ca * r * 0.8f, cy + sa * r * 0.8f, cx + ca * (r + u * 0.02f), cy + sa * (r + u * 0.02f), stroke)

        timePaint.typeface = condensedBold
        timePaint.color = p.text
        timePaint.textSize = u * 0.2f
        c.drawText(timeText(cal), cx, cy + u * 0.07f, timePaint)

        datePaint.typeface = light
        datePaint.color = p.sub
        datePaint.textSize = u * 0.036f
        c.drawText(dateText(cal), cx, cy + r * 0.84f, datePaint)
    }

    private fun drawAuroraBackground(c: Canvas, w: Float, h: Float, now: Long) {
        if (blobShaders.isEmpty()) return
        val t = (now - startTime) / 1000.0
        val xs = floatArrayOf(
            (w * (0.25 + 0.25 * sin(t * 0.071))).toFloat(),
            (w * (0.75 + 0.22 * sin(t * 0.053 + 2.0))).toFloat(),
            (w * (0.50 + 0.30 * cos(t * 0.041 + 1.0))).toFloat(),
        )
        val ys = floatArrayOf(
            (h * (0.22 + 0.14 * cos(t * 0.047))).toFloat(),
            (h * (0.48 + 0.16 * sin(t * 0.061 + 1.0))).toFloat(),
            (h * (0.80 + 0.12 * sin(t * 0.037 + 3.0))).toFloat(),
        )
        fill.color = Color.WHITE
        for (i in blobShaders.indices) {
            fill.shader = blobShaders[i]
            c.save()
            c.translate(xs[i], ys[i])
            c.drawCircle(0f, 0f, blobRadius, fill)
            c.restore()
        }
        fill.shader = null
    }

    private fun drawAuroraClock(c: Canvas, w: Float, h: Float, u: Float, cal: Calendar, p: Palette) {
        val cy = h * 0.36f
        timePaint.typeface = light
        timePaint.color = p.text
        timePaint.textSize = u * 0.30f
        c.drawText(timeText(cal), w / 2, cy, timePaint)

        datePaint.typeface = light
        datePaint.color = p.sub
        datePaint.textSize = u * 0.05f
        c.drawText(dateText(cal), w / 2, cy + u * 0.11f, datePaint)
    }

    // ---------- Now playing ----------

    private fun drawMedia(c: Canvas, w: Float, h: Float, u: Float, p: Palette, dx: Float, dy: Float) {
        prevHit.setEmpty()
        playHit.setEmpty()
        nextHit.setEmpty()
        accessHit.setEmpty()

        if (!hasAccess) {
            smallPaint.textAlign = Paint.Align.CENTER
            smallPaint.typeface = light
            smallPaint.textSize = u * 0.034f
            smallPaint.color = p.sub
            val y = h * 0.80f
            c.drawText("Tap here to show what's playing", w / 2, y, smallPaint)
            accessHit.set(w * 0.1f, y - u * 0.08f, w * 0.9f, y + u * 0.05f)
            accessHit.offset(dx, dy)
            return
        }
        val np = nowPlaying ?: return

        val cardW = u * 0.86f
        val cardH = u * 0.46f
        val left = (w - cardW) / 2
        val top = h * 0.64f
        rect.set(left, top, left + cardW, top + cardH)
        fill.shader = null
        fill.color = p.card
        c.drawRoundRect(rect, u * 0.04f, u * 0.04f, fill)

        val pad = u * 0.045f
        val artSize = u * 0.2f
        artRect.set(rect.left + pad, rect.top + pad, rect.left + pad + artSize, rect.top + pad + artSize)
        val corner = u * 0.025f
        val art = np.art
        if (art != null && !art.isRecycled) {
            c.save()
            path.reset()
            path.addRoundRect(artRect, corner, corner, Path.Direction.CW)
            c.clipPath(path)
            c.drawBitmap(art, null, artRect, bitmapPaint)
            c.restore()
        } else {
            fill.color = p.track
            c.drawRoundRect(artRect, corner, corner, fill)
            smallPaint.textAlign = Paint.Align.CENTER
            smallPaint.typeface = light
            smallPaint.textSize = artSize * 0.45f
            smallPaint.color = p.accent
            c.drawText("♪", artRect.centerX(), artRect.centerY() + artSize * 0.16f, smallPaint)
        }

        val tx = artRect.right + pad
        val tw = rect.right - pad - tx
        smallPaint.textAlign = Paint.Align.LEFT
        smallPaint.typeface = medium
        smallPaint.textSize = u * 0.026f
        smallPaint.color = p.accent
        c.drawText(fit(np.appLabel.uppercase(Locale.getDefault()), smallPaint, tw), tx, artRect.top + u * 0.035f, smallPaint)

        titlePaint.typeface = medium
        titlePaint.textSize = u * 0.05f
        titlePaint.color = p.text
        c.drawText(fit(np.title, titlePaint, tw), tx, artRect.top + u * 0.105f, titlePaint)

        artistPaint.typeface = light
        artistPaint.textSize = u * 0.038f
        artistPaint.color = p.sub
        c.drawText(fit(np.artist, artistPaint, tw), tx, artRect.top + u * 0.16f, artistPaint)

        // Controls: big touch targets.
        val cy = artRect.bottom + (rect.bottom - artRect.bottom) / 2
        val cx = rect.centerX()
        val gap = u * 0.2f
        val s = u * 0.05f
        drawPrev(c, cx - gap, cy, s, p)
        drawPlayPause(c, cx, cy, s * 1.35f, np.isPlaying, p)
        drawNext(c, cx + gap, cy, s, p)

        val hr = u * 0.09f
        prevHit.set(cx - gap - hr, cy - hr, cx - gap + hr, cy + hr)
        playHit.set(cx - hr, cy - hr, cx + hr, cy + hr)
        nextHit.set(cx + gap - hr, cy - hr, cx + gap + hr, cy + hr)
        prevHit.offset(dx, dy)
        playHit.offset(dx, dy)
        nextHit.offset(dx, dy)
    }

    private fun drawPrev(c: Canvas, x: Float, y: Float, s: Float, p: Palette) {
        fill.color = p.text
        path.reset()
        path.moveTo(x + s * 0.55f, y - s * 0.6f)
        path.lineTo(x - s * 0.25f, y)
        path.lineTo(x + s * 0.55f, y + s * 0.6f)
        path.close()
        c.drawPath(path, fill)
        c.drawRect(x - s * 0.55f, y - s * 0.6f, x - s * 0.35f, y + s * 0.6f, fill)
    }

    private fun drawNext(c: Canvas, x: Float, y: Float, s: Float, p: Palette) {
        fill.color = p.text
        path.reset()
        path.moveTo(x - s * 0.55f, y - s * 0.6f)
        path.lineTo(x + s * 0.25f, y)
        path.lineTo(x - s * 0.55f, y + s * 0.6f)
        path.close()
        c.drawPath(path, fill)
        c.drawRect(x + s * 0.35f, y - s * 0.6f, x + s * 0.55f, y + s * 0.6f, fill)
    }

    private fun drawPlayPause(c: Canvas, x: Float, y: Float, r: Float, playing: Boolean, p: Palette) {
        fill.color = p.text
        c.drawCircle(x, y, r, fill)
        fill.color = p.bg
        val s = r * 0.42f
        if (playing) {
            c.drawRect(x - s * 0.75f, y - s, x - s * 0.2f, y + s, fill)
            c.drawRect(x + s * 0.2f, y - s, x + s * 0.75f, y + s, fill)
        } else {
            path.reset()
            path.moveTo(x - s * 0.6f, y - s)
            path.lineTo(x + s, y)
            path.lineTo(x - s * 0.6f, y + s)
            path.close()
            c.drawPath(path, fill)
        }
    }

    // ---------- Overlays ----------

    private fun drawOverlays(c: Canvas, w: Float, h: Float, u: Float, now: Long, p: Palette) {
        if (now < dotsUntil) {
            val y = h - u * 0.06f
            for (i in 0 until THEME_COUNT) {
                fill.color = p.text
                fill.alpha = if (i == themeIndex) 230 else 70
                c.drawCircle(w / 2 + (i - 1) * u * 0.04f, y, u * 0.009f, fill)
            }
            fill.alpha = 255
            smallPaint.textAlign = Paint.Align.CENTER
            smallPaint.typeface = medium
            smallPaint.textSize = u * 0.03f
            smallPaint.color = p.sub
            c.drawText(THEME_NAMES[themeIndex], w / 2, y - u * 0.035f, smallPaint)
        }
        val msg = message
        if (msg != null && now < messageUntil) {
            smallPaint.textAlign = Paint.Align.CENTER
            smallPaint.typeface = medium
            smallPaint.textSize = u * 0.032f
            smallPaint.color = p.text
            c.drawText(fit(msg, smallPaint, w * 0.92f), w / 2, u * 0.1f, smallPaint)
        }
    }

    // ---------- Touch ----------

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val l = listener ?: return false
            when {
                playHit.contains(e.x, e.y) -> l.onPlayPause()
                prevHit.contains(e.x, e.y) -> l.onPrevious()
                nextHit.contains(e.x, e.y) -> l.onNext()
                accessHit.contains(e.x, e.y) -> l.onRequestAccess()
                else -> return false
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (onControl(e)) return false
            dimmed = !dimmed
            listener?.onDimChanged(dimmed)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val start = e1 ?: return false
            val dx = e2.x - start.x
            val dy = e2.y - start.y
            if (abs(dx) > abs(dy) && abs(dx) > width * 0.12f) {
                themeIndex += if (dx < 0) 1 else -1
                listener?.onThemeChanged(themeIndex)
                dotsUntil = System.currentTimeMillis() + 2000L
                invalidate()
                return true
            }
            return false
        }
    })

    private fun onControl(e: MotionEvent): Boolean =
        playHit.contains(e.x, e.y) || prevHit.contains(e.x, e.y) ||
            nextHit.contains(e.x, e.y) || accessHit.contains(e.x, e.y)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean =
        gestures.onTouchEvent(event) || super.onTouchEvent(event)

    // ---------- Helpers ----------

    private fun timeText(cal: Calendar): String {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
        return DateFormat.format(pattern, cal).toString()
    }

    private fun dateText(cal: Calendar): String = DateFormat.format(datePattern, cal).toString()

    private fun fit(text: String, paint: TextPaint, maxWidth: Float): String =
        TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.END).toString()

    companion object {
        const val THEME_COUNT = 3
        private val THEME_NAMES = arrayOf("Minimal", "Gauge", "Aurora")
    }
}
