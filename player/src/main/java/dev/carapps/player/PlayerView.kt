package dev.carapps.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A music app the empty screen can launch. */
class LaunchApp(val packageName: String, val label: String, val icon: Drawable)

/**
 * The whole player screen, drawn by hand on a Canvas.
 * Colours follow the album art; the background is the art blown up and blurred.
 */
class PlayerView(context: Context) : View(context) {

    interface Listener {
        fun onPlayPause()
        fun onNext()
        fun onPrevious()
        fun onSeek(ms: Long)
        fun onSelectSource(packageName: String)
        fun onOpenApp(packageName: String)
        fun onVolume(fraction: Float)
        fun onRequestAccess()
    }

    var listener: Listener? = null

    var launchApps: List<LaunchApp> = emptyList()
        set(v) {
            field = v
            invalidate()
        }

    /** 0..1. */
    private var volume = 0.5f

    private var track: Track? = null
    private var sources: List<Source> = emptyList()
    private var hasAccess = true
    private var message: String? = null
    private var messageUntil = 0L

    // Colours and background, crossfaded when the art changes.
    private var lastArt: Bitmap? = null
    private var bg: Bitmap? = null
    private var oldBg: Bitmap? = null
    private var bgFadeStart = 0L
    private var accent = DEFAULT_ACCENT
    private var accentFrom = DEFAULT_ACCENT
    private var accentTo = DEFAULT_ACCENT
    private var accentStart = 0L

    // Animated values.
    private var artScale = 0.9f
    private var dragX = 0f
    private var seekPreview = -1f
    private var seekHoldUntil = 0L
    private var pressed: Target = Target.NONE

    // Touch.
    private enum class Target { NONE, SEEK, VOLUME, ART, PREV, PLAY, NEXT, CHIP, APP, ACCESS }
    private var target = Target.NONE
    private var targetIndex = -1
    private var downX = 0f
    private var downY = 0f
    private var horizontalDrag = false

    // Layout, filled in by onDraw and reused for hit tests.
    private var u = 1f
    private val artRect = RectF()
    private val seekRect = RectF()
    private val volRect = RectF()
    private var ctrlY = 0f
    private var prevX = 0f
    private var playX = 0f
    private var nextX = 0f
    private val chipRects = ArrayList<RectF>()
    private val appRects = ArrayList<RectF>()
    private val accessRect = RectF()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val smallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val path = Path()
    private val srcRect = Rect()
    private val tmp = RectF()

    fun setTrack(t: Track?, s: List<Source>, access: Boolean) {
        val old = track
        track = t
        sources = s
        hasAccess = access
        val art = t?.art
        if (art !== lastArt) {
            lastArt = art
            onArtChanged(art)
        }
        // A seek we just sent shows its target until the app reports the new position.
        if (target != Target.SEEK && (old?.title != t?.title || SystemClock.uptimeMillis() > seekHoldUntil)) {
            seekPreview = -1f
        }
        invalidate()
    }

    /** Called by the activity when the volume changes elsewhere, e.g. steering-wheel buttons. */
    fun setVolumeLevel(f: Float) {
        if (target == Target.VOLUME) return
        volume = f.coerceIn(0f, 1f)
        invalidate()
    }

    fun flashMessage(text: String, ms: Long) {
        message = text
        messageUntil = SystemClock.uptimeMillis() + ms
        invalidate()
    }

    private fun onArtChanged(art: Bitmap?) {
        val now = SystemClock.uptimeMillis()
        oldBg = bg
        bg = art?.let { blurred(it) }
        bgFadeStart = now
        accentFrom = accent
        accentTo = art?.let { accentOf(it) } ?: DEFAULT_ACCENT
        accentStart = now
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        u = w / 100f
        val now = SystemClock.uptimeMillis()
        var animating = false

        // Ease the accent towards the new art's colour.
        val at = ((now - accentStart) / 700f).coerceIn(0f, 1f)
        accent = lerpColor(accentFrom, accentTo, ease(at))
        if (at < 1f) animating = true

        drawBackground(canvas, w, h, now).also { if (it) animating = true }

        val t = track
        // Tiny slow drift so nothing sits on the same pixels for hours.
        val driftX = sin(now / 47000.0).toFloat() * 0.8f * u
        val driftY = sin(now / 61000.0).toFloat() * 0.8f * u

        val chipsBottom = drawChips(canvas, w, driftX, driftY)

        if (!hasAccess) {
            drawNoAccess(canvas, w, h)
        } else if (t == null) {
            drawNothingPlaying(canvas, w, h, chipsBottom)
        } else {
            if (drawPlayer(canvas, w, h, t, chipsBottom, driftX, driftY, now)) animating = true
        }

        val msg = message
        if (msg != null && now < messageUntil) {
            drawToast(canvas, w, h, msg)
            animating = true
        } else {
            message = null
        }

        when {
            animating -> postInvalidateOnAnimation()
            t?.isPlaying == true -> postInvalidateDelayed(50)
            else -> postInvalidateDelayed(1000)
        }
    }

    /** Returns true while a crossfade is running. */
    private fun drawBackground(canvas: Canvas, w: Float, h: Float, now: Long): Boolean {
        canvas.drawColor(0xFF07080C.toInt())
        val fade = ((now - bgFadeStart) / 900f).coerceIn(0f, 1f)
        val zoom = 1.35f + 0.05f * sin(now / 9000.0).toFloat()
        val dx = sin(now / 13000.0).toFloat() * 4f * u
        val dy = sin(now / 17000.0).toFloat() * 4f * u

        fun paintBg(b: Bitmap?, alpha: Float) {
            if (alpha <= 0f) return
            val size = max(w, h) * zoom
            tmp.set((w - size) / 2 + dx, (h - size) / 2 + dy, (w + size) / 2 + dx, (h + size) / 2 + dy)
            if (b != null) {
                bmp.alpha = (alpha * 255).toInt()
                canvas.drawBitmap(b, null, tmp, bmp)
                bmp.alpha = 255
            } else {
                fill.shader = RadialGradient(
                    w * 0.3f + dx, h * 0.25f + dy, max(w, h) * 0.9f,
                    withAlpha(accentTo, (alpha * 150).toInt()), Color.TRANSPARENT, Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, w, h, fill)
                fill.shader = null
            }
        }
        if (fade < 1f) paintBg(oldBg, 1f - fade)
        paintBg(bg, fade)

        // Darken so text stays readable on bright covers, heavier at the bottom.
        fill.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0x66000000, 0x80000000.toInt(), 0xE6000000.toInt()),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        return fade < 1f
    }

    /** App chips across the top. Returns their bottom edge. */
    private fun drawChips(canvas: Canvas, w: Float, driftX: Float, driftY: Float): Float {
        chipRects.clear()
        val top = 5f * u + driftY
        val ch = 7f * u
        if (sources.isEmpty()) return top + ch
        smallPaint.textSize = 3f * u
        var x = 5f * u + driftX
        val current = track?.packageName
        for (s in sources) {
            val label = TextUtils.ellipsize(s.label, smallPaint, 30f * u, TextUtils.TruncateAt.END).toString()
            val dot = if (s.isPlaying) 2.6f * u else 0f
            val cw = smallPaint.measureText(label) + 6f * u + dot
            if (x + cw > w - 5f * u) break
            val r = RectF(x, top, x + cw, top + ch)
            chipRects.add(r)
            val selected = s.packageName == current
            if (selected) {
                fill.color = 0xF2FFFFFF.toInt()
                canvas.drawRoundRect(r, ch / 2, ch / 2, fill)
            } else {
                fill.color = 0x1FFFFFFF
                canvas.drawRoundRect(r, ch / 2, ch / 2, fill)
            }
            if (dot > 0f) {
                fill.color = if (selected) accentDark() else accent
                canvas.drawCircle(r.left + 3f * u + 0.6f * u, r.centerY(), 0.9f * u, fill)
            }
            smallPaint.color = if (selected) 0xFF101218.toInt() else 0xD9FFFFFF.toInt()
            canvas.drawText(label, r.left + 3f * u + dot, r.centerY() + smallPaint.textSize * 0.36f, smallPaint)
            x += cw + 2f * u
        }
        smallPaint.color = 0x99FFFFFF.toInt()
        return top + ch
    }

    /** Returns true while something is mid-animation. */
    private fun drawPlayer(
        canvas: Canvas, w: Float, h: Float, t: Track, chipsBottom: Float,
        driftX: Float, driftY: Float, now: Long,
    ): Boolean {
        var animating = false
        val s = min(74f * u, h * 0.44f)

        // Stack: art, title, artist, seek bar, times, controls, volume.
        val titleOff = s + 11f * u
        val artistOff = titleOff + 6.5f * u
        val seekOff = artistOff + 9f * u
        val timesOff = seekOff + 6f * u
        val ctrlOff = timesOff + 15f * u
        val volOff = ctrlOff + 19f * u
        val blockH = volOff + 3f * u
        val free = h - chipsBottom - 4f * u
        val top = chipsBottom + max(3f * u, (free - blockH) / 2f) + driftY
        val cx = w / 2f + driftX

        // Art: shrinks a little when paused, follows the finger when swiped.
        val targetScale = if (pressed == Target.ART) 0.95f else if (t.isPlaying) 1f else 0.88f
        artScale += (targetScale - artScale) * 0.15f
        if (abs(targetScale - artScale) > 0.002f) animating = true
        if (target != Target.ART && abs(dragX) > 0.5f) {
            dragX *= 0.8f
            animating = true
        } else if (target != Target.ART) {
            dragX = 0f
        }
        val half = s / 2f
        artRect.set(cx - half, top, cx + half, top + s)
        val sh = s * artScale / 2f
        val acx = cx + dragX
        val acy = top + half
        tmp.set(acx - sh, acy - sh, acx + sh, acy + sh)
        val radius = 4f * u
        fill.color = 0xFF000000.toInt()
        fill.setShadowLayer(5f * u, 0f, 2f * u, 0xB0000000.toInt())
        canvas.drawRoundRect(tmp, radius, radius, fill)
        fill.clearShadowLayer()
        val art = t.art
        canvas.save()
        path.reset()
        path.addRoundRect(tmp, radius, radius, Path.Direction.CW)
        canvas.clipPath(path)
        if (art != null) {
            centerCrop(art, srcRect)
            canvas.drawBitmap(art, srcRect, tmp, bmp)
        } else {
            fill.shader = LinearGradient(
                tmp.left, tmp.top, tmp.right, tmp.bottom,
                accent, lerpColor(accent, 0xFF101218.toInt(), 0.75f), Shader.TileMode.CLAMP,
            )
            canvas.drawRect(tmp, fill)
            fill.shader = null
            drawNote(canvas, tmp.centerX(), tmp.centerY(), tmp.width() * 0.22f, 0x66FFFFFF)
        }
        canvas.restore()

        // Title and artist.
        titlePaint.textSize = 6f * u
        subPaint.textSize = 4f * u
        val textW = w - 14f * u
        val title = TextUtils.ellipsize(t.title.ifBlank { "Unknown title" }, titlePaint, textW, TextUtils.TruncateAt.END)
        val artistLine = listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString("  ·  ")
        val artist = TextUtils.ellipsize(artistLine, subPaint, textW, TextUtils.TruncateAt.END)
        val textX = 7f * u + driftX
        canvas.drawText(title, 0, title.length, textX, top + titleOff, titlePaint)
        canvas.drawText(artist, 0, artist.length, textX, top + artistOff, subPaint)

        // Seek bar.
        val sy = top + seekOff
        seekRect.set(7f * u + driftX, sy - 4f * u, w - 7f * u + driftX, sy + 4f * u)
        val dur = t.durationMs
        if (target != Target.SEEK && seekPreview >= 0f && now > seekHoldUntil) seekPreview = -1f
        val frac = when {
            seekPreview >= 0f -> seekPreview
            dur > 0 -> t.positionNow().toFloat() / dur
            else -> 0f
        }.coerceIn(0f, 1f)
        val barL = seekRect.left
        val barR = seekRect.right
        val thick = if (target == Target.SEEK) 1.6f * u else 1.1f * u
        fill.color = 0x33FFFFFF
        tmp.set(barL, sy - thick / 2, barR, sy + thick / 2)
        canvas.drawRoundRect(tmp, thick, thick, fill)
        if (dur > 0) {
            val px = barL + (barR - barL) * frac
            fill.color = accent
            tmp.set(barL, sy - thick / 2, px, sy + thick / 2)
            canvas.drawRoundRect(tmp, thick, thick, fill)
            if (t.canSeek) {
                fill.color = Color.WHITE
                canvas.drawCircle(px, sy, if (target == Target.SEEK) 2.6f * u else 1.8f * u, fill)
            }
            smallPaint.textSize = 3f * u
            val pos = (frac * dur).toLong()
            canvas.drawText(formatTime(pos), barL, top + timesOff, smallPaint)
            val rem = "-" + formatTime(dur - pos)
            canvas.drawText(rem, barR - smallPaint.measureText(rem), top + timesOff, smallPaint)
        }

        // Transport controls.
        ctrlY = top + ctrlOff
        playX = cx
        prevX = cx - 28f * u
        nextX = cx + 28f * u
        val playR = 10.5f * u * (if (pressed == Target.PLAY) 0.92f else 1f)
        fill.color = accent
        fill.setShadowLayer(3f * u, 0f, 1f * u, withAlpha(accent, 0x70))
        canvas.drawCircle(playX, ctrlY, playR, fill)
        fill.clearShadowLayer()
        if (t.isPlaying) drawPause(canvas, playX, ctrlY, playR * 0.42f, accentDark())
        else drawPlay(canvas, playX, ctrlY, playR * 0.46f, accentDark())
        drawSkip(canvas, prevX, ctrlY, 4.2f * u * (if (pressed == Target.PREV) 0.85f else 1f), false)
        drawSkip(canvas, nextX, ctrlY, 4.2f * u * (if (pressed == Target.NEXT) 0.85f else 1f), true)

        // Volume.
        val vy = top + volOff
        drawSpeaker(canvas, 9f * u + driftX, vy, 2.2f * u)
        volRect.set(15f * u + driftX, vy - 4f * u, w - 7f * u + driftX, vy + 4f * u)
        val vThick = if (target == Target.VOLUME) 1.6f * u else 1.1f * u
        fill.color = 0x33FFFFFF
        tmp.set(volRect.left, vy - vThick / 2, volRect.right, vy + vThick / 2)
        canvas.drawRoundRect(tmp, vThick, vThick, fill)
        fill.color = 0xE6FFFFFF.toInt()
        tmp.set(volRect.left, vy - vThick / 2, volRect.left + volRect.width() * volume, vy + vThick / 2)
        canvas.drawRoundRect(tmp, vThick, vThick, fill)

        return animating || target != Target.NONE
    }

    private fun drawNothingPlaying(canvas: Canvas, w: Float, h: Float, chipsBottom: Float) {
        appRects.clear()
        val cx = w / 2f
        var y = max(chipsBottom + 20f * u, h * 0.28f)
        drawDisc(canvas, cx, y, 16f * u)
        y += 28f * u
        titlePaint.textSize = 6f * u
        drawCentered(canvas, "Nothing playing", cx, y, titlePaint)
        y += 7f * u
        subPaint.textSize = 3.6f * u
        drawCentered(
            canvas,
            if (launchApps.isEmpty()) "Start music in any app and it shows up here." else "Start music in one of these apps:",
            cx, y, subPaint,
        )
        if (launchApps.isEmpty()) return
        y += 10f * u
        val cols = 4
        val cell = 21f * u
        val icon = 12f * u
        smallPaint.textSize = 2.8f * u
        smallPaint.color = 0xCCFFFFFF.toInt()
        val apps = launchApps.take(12)
        val rows = (apps.size + cols - 1) / cols
        for (row in 0 until rows) {
            val inRow = min(cols, apps.size - row * cols)
            val rowLeft = cx - inRow * cell / 2f
            for (col in 0 until inRow) {
                val app = apps[row * cols + col]
                val ix = rowLeft + col * cell + cell / 2f
                val iy = y + row * (cell + 4f * u)
                val r = RectF(ix - cell / 2, iy - 2f * u, ix + cell / 2, iy + icon + 6f * u)
                appRects.add(r)
                app.icon.setBounds((ix - icon / 2).toInt(), iy.toInt(), (ix + icon / 2).toInt(), (iy + icon).toInt())
                app.icon.draw(canvas)
                val label = TextUtils.ellipsize(app.label, smallPaint, cell - 2f * u, TextUtils.TruncateAt.END).toString()
                drawCentered(canvas, label, ix, iy + icon + 4.5f * u, smallPaint)
            }
        }
        smallPaint.color = 0x99FFFFFF.toInt()
    }

    private fun drawNoAccess(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        var y = h * 0.36f
        drawDisc(canvas, cx, y, 16f * u)
        y += 28f * u
        titlePaint.textSize = 5.5f * u
        drawCentered(canvas, "Allow music access", cx, y, titlePaint)
        y += 7f * u
        subPaint.textSize = 3.6f * u
        drawCentered(canvas, "The player needs notification access", cx, y, subPaint)
        y += 5f * u
        drawCentered(canvas, "to see and control what's playing.", cx, y, subPaint)
        y += 9f * u
        accessRect.set(cx - 22f * u, y, cx + 22f * u, y + 10f * u)
        fill.color = accent
        canvas.drawRoundRect(accessRect, 5f * u, 5f * u, fill)
        titlePaint.textSize = 3.8f * u
        titlePaint.color = accentDark()
        drawCentered(canvas, "Open settings", cx, accessRect.centerY() + 1.3f * u, titlePaint)
        titlePaint.color = Color.WHITE
    }

    private fun drawToast(canvas: Canvas, w: Float, h: Float, text: String) {
        subPaint.textSize = 3.4f * u
        val tw = min(subPaint.measureText(text), w - 16f * u)
        val line = TextUtils.ellipsize(text, subPaint, tw, TextUtils.TruncateAt.END)
        tmp.set((w - tw) / 2 - 4f * u, h - 16f * u, (w + tw) / 2 + 4f * u, h - 7f * u)
        fill.color = 0xE6202430.toInt()
        canvas.drawRoundRect(tmp, 4.5f * u, 4.5f * u, fill)
        canvas.drawText(line, 0, line.length, (w - tw) / 2, tmp.centerY() + 1.2f * u, subPaint)
    }

    // ---------------------------------------------------------------- icons

    private fun drawPlay(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        path.reset()
        path.moveTo(cx - r * 0.7f, cy - r)
        path.lineTo(cx + r, cy)
        path.lineTo(cx - r * 0.7f, cy + r)
        path.close()
        fill.color = color
        fill.pathEffect = CornerPathEffect(r * 0.2f)
        canvas.drawPath(path, fill)
        fill.pathEffect = null
    }

    private fun drawPause(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        fill.color = color
        val bw = r * 0.62f
        val gap = r * 0.36f
        tmp.set(cx - gap - bw, cy - r, cx - gap, cy + r)
        canvas.drawRoundRect(tmp, bw * 0.3f, bw * 0.3f, fill)
        tmp.set(cx + gap, cy - r, cx + gap + bw, cy + r)
        canvas.drawRoundRect(tmp, bw * 0.3f, bw * 0.3f, fill)
    }

    /** Two triangles and a bar; mirrored for "previous". */
    private fun drawSkip(canvas: Canvas, cx: Float, cy: Float, r: Float, forward: Boolean) {
        val d = if (forward) 1f else -1f
        fill.color = Color.WHITE
        fill.pathEffect = CornerPathEffect(r * 0.15f)
        path.reset()
        path.moveTo(cx - d * r * 1.1f, cy - r)
        path.lineTo(cx, cy)
        path.lineTo(cx - d * r * 1.1f, cy + r)
        path.close()
        path.moveTo(cx - d * r * 0.05f, cy - r)
        path.lineTo(cx + d * r * 1.05f, cy)
        path.lineTo(cx - d * r * 0.05f, cy + r)
        path.close()
        canvas.drawPath(path, fill)
        fill.pathEffect = null
        val bx = cx + d * r * 1.25f
        tmp.set(bx - r * 0.13f, cy - r, bx + r * 0.13f, cy + r)
        canvas.drawRoundRect(tmp, r * 0.13f, r * 0.13f, fill)
    }

    private fun drawSpeaker(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        fill.color = 0xCCFFFFFF.toInt()
        path.reset()
        path.moveTo(cx - r, cy - r * 0.45f)
        path.lineTo(cx - r * 0.35f, cy - r * 0.45f)
        path.lineTo(cx + r * 0.4f, cy - r)
        path.lineTo(cx + r * 0.4f, cy + r)
        path.lineTo(cx - r * 0.35f, cy + r * 0.45f)
        path.lineTo(cx - r, cy + r * 0.45f)
        path.close()
        canvas.drawPath(path, fill)
    }

    private fun drawNote(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        fill.color = color
        canvas.drawOval(cx - r * 0.9f, cy + r * 0.35f, cx - r * 0.05f, cy + r * 0.95f, fill)
        tmp.set(cx - r * 0.2f, cy - r, cx - r * 0.05f, cy + r * 0.7f)
        canvas.drawRect(tmp, fill)
        path.reset()
        path.moveTo(cx - r * 0.2f, cy - r)
        path.quadTo(cx + r * 0.2f, cy - r * 0.5f, cx + r * 0.75f, cy - r * 0.3f)
        path.quadTo(cx + r * 0.35f, cy - r * 0.6f, cx - r * 0.05f, cy - r * 0.55f)
        path.close()
        canvas.drawPath(path, fill)
    }

    /** A vinyl record, spinning slowly. */
    private fun drawDisc(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        fill.color = 0x26FFFFFF
        canvas.drawCircle(cx, cy, r, fill)
        stroke.color = 0x14FFFFFF
        stroke.strokeWidth = 0.25f * u
        var rr = r * 0.9f
        while (rr > r * 0.42f) {
            canvas.drawCircle(cx, cy, rr, stroke)
            rr -= r * 0.09f
        }
        fill.color = accent
        canvas.drawCircle(cx, cy, r * 0.32f, fill)
        fill.color = 0xFF07080C.toInt()
        canvas.drawCircle(cx, cy, r * 0.06f, fill)
    }

    private fun drawCentered(canvas: Canvas, text: String, cx: Float, y: Float, p: Paint) {
        canvas.drawText(text, cx - p.measureText(text) / 2f, y, p)
    }

    // ---------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                horizontalDrag = false
                target = hitTest(x, y)
                pressed = target
                when (target) {
                    Target.SEEK -> seekPreview = seekFraction(x)
                    Target.VOLUME -> setVolumeFrom(x)
                    else -> {}
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                when (target) {
                    Target.SEEK -> seekPreview = seekFraction(x)
                    Target.VOLUME -> setVolumeFrom(x)
                    Target.ART -> {
                        val dx = x - downX
                        if (!horizontalDrag && abs(dx) > 3f * u && abs(dx) > abs(y - downY)) horizontalDrag = true
                        if (horizontalDrag) {
                            dragX = dx
                            pressed = Target.NONE
                        }
                    }
                    else -> if (hypot(x - downX, y - downY) > 6f * u) pressed = Target.NONE
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                finishTouch(x, y)
                target = Target.NONE
                pressed = Target.NONE
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                target = Target.NONE
                pressed = Target.NONE
                seekPreview = -1f
                invalidate()
            }
        }
        return true
    }

    private fun finishTouch(x: Float, y: Float) {
        val l = listener ?: return
        val t = track
        when (target) {
            Target.SEEK -> {
                if (t != null && t.canSeek && t.durationMs > 0) {
                    val ms = (seekFraction(x) * t.durationMs).toLong()
                    seekHoldUntil = SystemClock.uptimeMillis() + 1500
                    l.onSeek(ms)
                } else {
                    seekPreview = -1f
                }
            }
            Target.VOLUME -> setVolumeFrom(x)
            Target.ART -> {
                val dx = x - downX
                when {
                    horizontalDrag && dx < -18f * u -> l.onNext()
                    horizontalDrag && dx > 18f * u -> l.onPrevious()
                    !horizontalDrag && hypot(dx, y - downY) < 4f * u -> l.onPlayPause()
                }
            }
            Target.PREV -> if (pressed == Target.PREV) l.onPrevious()
            Target.PLAY -> if (pressed == Target.PLAY) l.onPlayPause()
            Target.NEXT -> if (pressed == Target.NEXT) l.onNext()
            Target.CHIP -> if (pressed == Target.CHIP) {
                val s = sources.getOrNull(targetIndex) ?: return
                if (s.packageName == t?.packageName) l.onOpenApp(s.packageName) else l.onSelectSource(s.packageName)
            }
            Target.APP -> if (pressed == Target.APP) {
                launchApps.getOrNull(targetIndex)?.let { l.onOpenApp(it.packageName) }
            }
            Target.ACCESS -> if (pressed == Target.ACCESS) l.onRequestAccess()
            Target.NONE -> {}
        }
    }

    private fun hitTest(x: Float, y: Float): Target {
        targetIndex = -1
        chipRects.forEachIndexed { i, r ->
            if (r.contains(x, y)) {
                targetIndex = i
                return Target.CHIP
            }
        }
        if (!hasAccess) return if (accessRect.contains(x, y)) Target.ACCESS else Target.NONE
        val t = track
        if (t == null) {
            appRects.forEachIndexed { i, r ->
                if (r.contains(x, y)) {
                    targetIndex = i
                    return Target.APP
                }
            }
            return Target.NONE
        }
        if (artRect.contains(x, y)) return Target.ART
        if (seekRect.contains(x, y) && t.canSeek && t.durationMs > 0) return Target.SEEK
        if (volRect.contains(x, y) || (abs(y - volRect.centerY()) < 4f * u && x < volRect.left)) return Target.VOLUME
        val hit = 12f * u
        if (hypot(x - playX, y - ctrlY) < hit) return Target.PLAY
        if (hypot(x - prevX, y - ctrlY) < hit) return Target.PREV
        if (hypot(x - nextX, y - ctrlY) < hit) return Target.NEXT
        return Target.NONE
    }

    private fun seekFraction(x: Float) = ((x - seekRect.left) / seekRect.width()).coerceIn(0f, 1f)

    private fun setVolumeFrom(x: Float) {
        val f = ((x - volRect.left) / volRect.width()).coerceIn(0f, 1f)
        volume = f
        listener?.onVolume(f)
    }

    // ---------------------------------------------------------------- colour helpers

    private fun accentDark() = 0xFF0E1016.toInt()

    private fun centerCrop(b: Bitmap, out: Rect) {
        val side = min(b.width, b.height)
        val l = (b.width - side) / 2
        val t = (b.height - side) / 2
        out.set(l, t, l + side, t + side)
    }

    companion object {
        private const val DEFAULT_ACCENT = 0xFF8C7CFF.toInt()

        /** Shrinking to a few pixels and stretching back with filtering is a cheap, smooth blur. */
        fun blurred(src: Bitmap): Bitmap? = try {
            val small = Bitmap.createScaledBitmap(src, 10, 10, true)
            Bitmap.createScaledBitmap(small, 40, 40, true)
        } catch (_: Exception) {
            null
        }

        /** The most colourful hue in the art, brightened so it reads on a dark screen. */
        fun accentOf(src: Bitmap): Int {
            val small = try {
                // Hardware bitmaps can't be read pixel by pixel, so copy into a plain one.
                Bitmap.createScaledBitmap(src, 24, 24, true).copy(Bitmap.Config.ARGB_8888, false)
            } catch (_: Exception) {
                return DEFAULT_ACCENT
            } ?: return DEFAULT_ACCENT
            val weights = FloatArray(12)
            val sums = Array(12) { FloatArray(3) }
            val hsv = FloatArray(3)
            for (y in 0 until small.height) for (x in 0 until small.width) {
                val c = small.getPixel(x, y)
                Color.colorToHSV(c, hsv)
                val wgt = hsv[1] * hsv[1] * hsv[2]
                if (hsv[1] < 0.2f || hsv[2] < 0.2f) continue
                val bucket = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
                weights[bucket] += wgt
                sums[bucket][0] += Color.red(c) * wgt
                sums[bucket][1] += Color.green(c) * wgt
                sums[bucket][2] += Color.blue(c) * wgt
            }
            val best = weights.indices.maxByOrNull { weights[it] } ?: return DEFAULT_ACCENT
            val wt = weights[best]
            if (wt < 0.5f) return DEFAULT_ACCENT
            val c = Color.rgb(
                (sums[best][0] / wt).toInt(),
                (sums[best][1] / wt).toInt(),
                (sums[best][2] / wt).toInt(),
            )
            Color.colorToHSV(c, hsv)
            hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
            hsv[2] = max(hsv[2], 0.9f)
            return Color.HSVToColor(hsv)
        }

        fun lerpColor(a: Int, b: Int, t: Float): Int = Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
        )

        fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

        fun ease(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)

        fun formatTime(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
        }
    }
}
