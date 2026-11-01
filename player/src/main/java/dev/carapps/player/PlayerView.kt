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
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import java.util.Date
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A music app the empty screen can launch. */
class LaunchApp(val packageName: String, val label: String, val icon: Drawable)

/**
 * The whole player, drawn by hand on a Canvas: a top bar, three tabs (Now playing,
 * Up next, Sources) and a bottom dock. Colours follow the album art.
 */
class PlayerView(context: Context) : View(context) {

    interface Listener {
        fun onPlayPause()
        fun onNext()
        fun onPrevious()
        fun onSeek(ms: Long)
        fun onAction(action: String)
        fun onQueueItem(id: Long)
        fun onSelectSource(packageName: String)
        fun onOpenApp(packageName: String)
        fun onRequestAccess()
        fun onInstallUpdate()
    }

    private enum class Tab { NOW, LYRICS, QUEUE, SOURCES }

    var listener: Listener? = null

    var launchApps: List<LaunchApp> = emptyList()
        set(v) {
            field = v
            invalidate()
        }

    /** Lyrics for the current song, set by the activity. */
    var lyrics: Lyrics? = null
        set(v) {
            field = v
            lyricLayouts = null
            lyricScroll = 0f
            lyricManualUntil = 0L
            invalidate()
        }

    /** Build number of a newer release, or 0 if there's none. */
    var updateVersion = 0
        set(v) {
            field = v
            invalidate()
        }

    private val fonts = Fonts(context.assets)
    private var track: Track? = null
    private var sources: List<Source> = emptyList()
    private var hasAccess = true
    private var tab = Tab.NOW
    private var message: String? = null
    private var messageUntil = 0L

    // Colours and background, crossfaded when the art changes.
    private var lastArt: Bitmap? = null
    private var bg: Bitmap? = null
    private var oldBg: Bitmap? = null
    private var bgFadeStart = 0L
    private var accent = DEFAULT_ACCENT
    private var accent2 = DEFAULT_ACCENT2
    private var fromA = DEFAULT_ACCENT
    private var fromB = DEFAULT_ACCENT2
    private var toA = DEFAULT_ACCENT
    private var toB = DEFAULT_ACCENT2
    private var accentStart = 0L

    // Animated values.
    private var artScale = 0.9f
    private var dragX = 0f
    private var seekPreview = -1f
    private var seekHoldUntil = 0L
    private var queueScroll = 0f
    private var tabFadeStart = 0L
    private var lyricScroll = 0f
    private var lyricManualUntil = 0L
    private var lyricLayouts: List<StaticLayout>? = null
    private var lyricLayoutWidth = 0
    private val lyricTops = ArrayList<Float>()

    // Touch.
    private enum class Target { NONE, SEEK, ART, PREV, PLAY, NEXT, ACTION, TAB, PILL, ROW, LYRIC, SOURCE, APP, ACCESS, RESUME, UPDATE }
    private var target = Target.NONE
    private var pressed = Target.NONE
    private var targetIndex = -1
    private var downX = 0f
    private var downY = 0f
    private var horizontalDrag = false
    private var verticalDrag = false
    private var scrollAtDown = 0f

    // Layout, filled in by onDraw and reused for hit tests.
    private var u = 1f
    private val artRect = RectF()
    private val seekRect = RectF()
    private val pillRect = RectF()
    private val accessRect = RectF()
    private val resumeRect = RectF()
    private val updateRect = RectF()
    private var ctrlY = 0f
    private var prevX = 0f
    private var playX = 0f
    private var nextX = 0f
    private var playR = 0f
    private val actionRects = ArrayList<RectF>()
    private val tabRects = ArrayList<Pair<Tab, RectF>>()
    private val rowRects = ArrayList<Pair<Long, RectF>>()
    private val sourceRects = ArrayList<RectF>()
    private val appRects = ArrayList<RectF>()
    private var listTop = 0f
    private var listBottom = 0f
    private var listMaxScroll = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
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
        if (tab == Tab.QUEUE && t?.queue.isNullOrEmpty()) tab = Tab.NOW
        if (tab == Tab.LYRICS && t == null) tab = Tab.NOW
        if (old?.title != t?.title) queueScroll = 0f
        // A seek we just sent shows its target until the app reports the new position.
        if (target != Target.SEEK && (old?.title != t?.title || SystemClock.uptimeMillis() > seekHoldUntil)) {
            seekPreview = -1f
        }
        invalidate()
    }

    fun flashMessage(msg: String, ms: Long) {
        message = msg
        messageUntil = SystemClock.uptimeMillis() + ms
        invalidate()
    }

    private fun onArtChanged(art: Bitmap?) {
        val now = SystemClock.uptimeMillis()
        oldBg = bg
        bg = art?.let { blurred(it) }
        bgFadeStart = now
        fromA = accent
        fromB = accent2
        val pair = art?.let { accentsOf(it) }
        toA = pair?.get(0) ?: DEFAULT_ACCENT
        toB = pair?.get(1) ?: DEFAULT_ACCENT2
        accentStart = now
    }

    private fun showTab(t: Tab) {
        if (t == tab) return
        tab = t
        tabFadeStart = SystemClock.uptimeMillis()
        invalidate()
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // One unit is 0.72% of the width: sized for the 13" screen, where 1% read too large.
        u = w / 100f * 0.72f
        val now = SystemClock.uptimeMillis()
        var animating = false

        val at = ((now - accentStart) / 700f).coerceIn(0f, 1f)
        accent = lerpColor(fromA, toA, ease(at))
        accent2 = lerpColor(fromB, toB, ease(at))
        if (at < 1f) animating = true

        if (drawBackground(canvas, w, h, now)) animating = true

        val top = drawTopBar(canvas, w)
        val showDock = hasAccess
        val bottom = if (showDock) drawDock(canvas, w, h) else h - 4f * u

        // Tabs fade in when switched.
        val fade = ((now - tabFadeStart) / 250f).coerceIn(0f, 1f)
        if (fade < 1f) animating = true
        val layer = if (fade < 1f) canvas.saveLayerAlpha(0f, top, w, bottom, (255 * fade).toInt()) else -1

        actionRects.clear()
        rowRects.clear()
        sourceRects.clear()
        appRects.clear()
        val t = track
        when {
            !hasAccess -> drawNoAccess(canvas, w, top, bottom)
            tab == Tab.SOURCES -> drawSources(canvas, w, top, bottom)
            tab == Tab.QUEUE && t != null -> drawQueue(canvas, w, top, bottom, t)
            tab == Tab.LYRICS && t != null -> if (drawLyrics(canvas, w, top, bottom, t)) animating = true
            t == null -> drawNothingPlaying(canvas, w, top, bottom)
            else -> if (drawNowPlaying(canvas, w, top, bottom, t)) animating = true
        }
        if (layer >= 0) canvas.restoreToCount(layer)

        val msg = message
        if (msg != null && now < messageUntil) {
            drawToast(canvas, w, bottom, msg)
            animating = true
        } else {
            message = null
        }

        when {
            animating || target != Target.NONE -> postInvalidateOnAnimation()
            t?.isPlaying == true -> postInvalidateDelayed(50)
            else -> postInvalidateDelayed(1000)
        }
    }

    /** Returns true while a crossfade is running. */
    private fun drawBackground(canvas: Canvas, w: Float, h: Float, now: Long): Boolean {
        canvas.drawColor(0xFF07080B.toInt())
        val fade = ((now - bgFadeStart) / 900f).coerceIn(0f, 1f)
        val zoom = 1.35f + 0.05f * sin(now / 9000.0).toFloat()
        val dx = sin(now / 13000.0).toFloat() * 4f * u
        val dy = sin(now / 17000.0).toFloat() * 4f * u

        fun paintBg(b: Bitmap?, alpha: Float) {
            if (alpha <= 0f) return
            val size = max(w, h) * zoom
            tmp.set((w - size) / 2 + dx, (h - size) / 2 + dy, (w + size) / 2 + dx, (h + size) / 2 + dy)
            if (b != null) {
                bmp.alpha = (alpha * 190).toInt()
                canvas.drawBitmap(b, null, tmp, bmp)
                bmp.alpha = 255
            } else {
                fill.shader = RadialGradient(
                    w * 0.3f + dx, h * 0.22f + dy, max(w, h) * 0.8f,
                    withAlpha(toA, (alpha * 140).toInt()), Color.TRANSPARENT, Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, w, h, fill)
                fill.shader = RadialGradient(
                    w * 0.75f - dx, h * 0.45f, max(w, h) * 0.7f,
                    withAlpha(toB, (alpha * 120).toInt()), Color.TRANSPARENT, Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, w, h, fill)
                fill.shader = null
            }
        }
        if (fade < 1f) paintBg(oldBg, 1f - fade)
        paintBg(bg, fade)

        fill.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0x4007080B, 0x8C07080B.toInt(), 0xEB07080B.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        return fade < 1f
    }

    /** "Player." on the left, the source pill and the clock on the right. Returns its bottom edge. */
    private fun drawTopBar(canvas: Canvas, w: Float): Float {
        val cy = 8.5f * u
        text.typeface = fonts.displayBold
        text.textSize = 4.2f * u
        text.color = INK
        canvas.drawText("Player", 6f * u, cy + 1.5f * u, text)
        val pw = text.measureText("Player")
        text.color = accent
        canvas.drawText(".", 6f * u + pw, cy + 1.5f * u, text)

        text.typeface = fonts.displaySemi
        text.textSize = 4f * u
        text.color = INK
        val clock = DateFormat.getTimeFormat(context).format(Date())
        val cw = text.measureText(clock)
        val clockX = w - 6f * u - cw
        canvas.drawText(clock, clockX, cy + 1.4f * u, text)

        pillRect.setEmpty()
        val t = track
        if (hasAccess && t != null) {
            text.typeface = fonts.bodyMedium
            text.textSize = 3.2f * u
            val label = TextUtils.ellipsize(t.appLabel, text, 30f * u, TextUtils.TruncateAt.END).toString()
            val lw = text.measureText(label)
            val ph = 8f * u
            val right = clockX - 3f * u
            pillRect.set(right - (lw + 10.6f * u), cy - ph / 2, right, cy + ph / 2)
            fill.color = if (pressed == Target.PILL) 0x2EFFFFFF else 0x1AFFFFFF
            canvas.drawRoundRect(pillRect, ph / 2, ph / 2, fill)
            val icon = sources.firstOrNull { it.packageName == t.packageName }?.icon
            val ir = 2.5f * u
            val icx = pillRect.left + 1.6f * u + ir
            if (icon != null) {
                canvas.save()
                path.reset()
                path.addCircle(icx, cy, ir, Path.Direction.CW)
                canvas.clipPath(path)
                icon.setBounds((icx - ir).toInt(), (cy - ir).toInt(), (icx + ir).toInt(), (cy + ir).toInt())
                icon.draw(canvas)
                canvas.restore()
            } else {
                fill.color = accent
                canvas.drawCircle(icx, cy, ir, fill)
            }
            text.color = INK
            canvas.drawText(label, icx + ir + 1.6f * u, cy + 1.1f * u, text)
        }
        return 14f * u
    }

    /** Tab bar along the bottom. Up next only appears when the app shares its queue. Returns its top edge. */
    private fun drawDock(canvas: Canvas, w: Float, h: Float): Float {
        val dockH = 17f * u
        val top = h - dockH
        fill.shader = LinearGradient(0f, top, 0f, h, 0x9907080B.toInt(), 0xF207080B.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, top, w, h, fill)
        fill.shader = null
        fill.color = 0x0FFFFFFF
        canvas.drawRect(0f, top, w, top + max(1f, 0.15f * u), fill)

        val tabs = ArrayList<Tab>()
        tabs.add(Tab.NOW)
        if (track != null) tabs.add(Tab.LYRICS)
        if (!track?.queue.isNullOrEmpty()) tabs.add(Tab.QUEUE)
        tabs.add(Tab.SOURCES)
        tabRects.clear()
        val slot = w / tabs.size
        text.typeface = fonts.bodyMedium
        text.textSize = 2.9f * u
        tabs.forEachIndexed { i, tb ->
            val cx = slot * i + slot / 2
            tabRects.add(tb to RectF(slot * i, top, slot * (i + 1), h))
            val on = tb == tab
            val iconColor = if (on) accent else MUTED
            val iy = top + 6.5f * u
            val r = 3.2f * u
            when (tb) {
                Tab.NOW -> {
                    stroke.color = iconColor
                    stroke.strokeWidth = 0.55f * u
                    canvas.drawCircle(cx, iy, r * 0.9f, stroke)
                    canvas.drawCircle(cx, iy, r * 0.3f, stroke)
                }
                Tab.LYRICS -> {
                    // A speech bubble with two lines of text.
                    stroke.color = iconColor
                    stroke.strokeWidth = 0.55f * u
                    tmp.set(cx - r, iy - r * 0.85f, cx + r, iy + r * 0.6f)
                    canvas.drawRoundRect(tmp, r * 0.4f, r * 0.4f, stroke)
                    canvas.drawLine(cx - r * 0.45f, iy + r * 0.6f, cx - r * 0.65f, iy + r * 1.05f, stroke)
                    canvas.drawLine(cx - r * 0.5f, iy - r * 0.3f, cx + r * 0.5f, iy - r * 0.3f, stroke)
                    canvas.drawLine(cx - r * 0.5f, iy + r * 0.1f, cx + r * 0.2f, iy + r * 0.1f, stroke)
                }
                Tab.QUEUE -> {
                    stroke.color = iconColor
                    stroke.strokeWidth = 0.55f * u
                    canvas.drawLine(cx - r, iy - r * 0.6f, cx + r, iy - r * 0.6f, stroke)
                    canvas.drawLine(cx - r, iy, cx + r * 0.25f, iy, stroke)
                    canvas.drawLine(cx - r, iy + r * 0.6f, cx - r * 0.15f, iy + r * 0.6f, stroke)
                    drawPlay(canvas, cx + r * 0.65f, iy + r * 0.45f, r * 0.42f, iconColor)
                }
                Tab.SOURCES -> {
                    fill.color = iconColor
                    val s = r * 0.8f
                    val g = r * 0.2f
                    for (dx in listOf(-1, 1)) for (dy in listOf(-1, 1)) {
                        val l = cx + (if (dx < 0) -s - g / 2 else g / 2)
                        val tp = iy + (if (dy < 0) -s - g / 2 else g / 2)
                        tmp.set(l, tp, l + s, tp + s)
                        stroke.color = iconColor
                        stroke.strokeWidth = 0.5f * u
                        canvas.drawRoundRect(tmp, s * 0.28f, s * 0.28f, stroke)
                    }
                    if (updateVersion > 0) canvas.drawCircle(cx + r * 1.2f, iy - r * 1.1f, 0.9f * u, fill.apply { color = accent })
                }
            }
            text.color = if (on) INK else MUTED
            val label = when (tb) {
                Tab.NOW -> "Now playing"
                Tab.LYRICS -> "Lyrics"
                Tab.QUEUE -> "Up next"
                Tab.SOURCES -> "Sources"
            }
            canvas.drawText(label, cx - text.measureText(label) / 2, top + 13f * u, text)
        }
        return top
    }

    /** Returns true while something is mid-animation. */
    private fun drawNowPlaying(canvas: Canvas, w: Float, top: Float, bottom: Float, t: Track): Boolean {
        var animating = false
        val cx = w / 2f
        val avail = bottom - top
        val actions = t.actions.take(5)

        // Stack, measured from the art's bottom edge.
        val titleOff = 12f * u
        val artistOff = titleOff + 6.5f * u
        val seekOff = artistOff + 9f * u
        val timesOff = seekOff + 6f * u
        val ctrlOff = timesOff + 15f * u
        val actionsOff = ctrlOff + 18f * u
        val below = (if (actions.isNotEmpty()) actionsOff + 5f * u else ctrlOff + 13f * u)
        val s = min(70f * u, avail - below - 4f * u).coerceAtLeast(30f * u)
        val blockTop = top + max(2f * u, (avail - s - below) / 2f)
        val artBottom = blockTop + s

        // Art: shrinks a little when paused, follows the finger when swiped.
        val targetScale = if (pressed == Target.ART) 0.95f else if (t.isPlaying) 1f else 0.9f
        artScale += (targetScale - artScale) * 0.15f
        if (abs(targetScale - artScale) > 0.002f) animating = true
        if (target != Target.ART && abs(dragX) > 0.5f) {
            dragX *= 0.8f
            animating = true
        } else if (target != Target.ART) {
            dragX = 0f
        }
        artRect.set(cx - s / 2, blockTop, cx + s / 2, artBottom)
        val sh = s * artScale / 2f
        val acx = cx + dragX
        val acy = blockTop + s / 2
        tmp.set(acx - sh, acy - sh, acx + sh, acy + sh)
        val radius = 6f * u * artScale
        fill.color = 0xFF000000.toInt()
        fill.setShadowLayer(8f * u, 0f, 3f * u, withAlpha(accent2, 0x99))
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
            fill.shader = LinearGradient(tmp.left, tmp.top, tmp.right, tmp.bottom, accent, accent2, Shader.TileMode.CLAMP)
            canvas.drawRect(tmp, fill)
            fill.shader = null
            drawNote(canvas, tmp.centerX(), tmp.centerY(), tmp.width() * 0.2f, 0x66FFFFFF)
        }
        canvas.restore()

        // Title and artist.
        val textX = 6f * u
        val textW = w - 12f * u
        text.typeface = fonts.displayBold
        text.textSize = 7f * u
        text.color = INK
        val title = TextUtils.ellipsize(t.title.ifBlank { "Unknown title" }, text, textW, TextUtils.TruncateAt.END)
        canvas.drawText(title, 0, title.length, textX, artBottom + titleOff, text)
        text.typeface = fonts.body
        text.textSize = 4f * u
        text.color = 0xB3FFFFFF.toInt()
        val sub = TextUtils.ellipsize(
            listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "),
            text, textW, TextUtils.TruncateAt.END,
        )
        canvas.drawText(sub, 0, sub.length, textX, artBottom + artistOff, text)

        // Seek bar.
        seekRect.setEmpty()
        run {
            val sy = artBottom + seekOff
            val dur = t.durationMs
            if (target != Target.SEEK && seekPreview >= 0f && SystemClock.uptimeMillis() > seekHoldUntil) seekPreview = -1f
            val frac = when {
                seekPreview >= 0f -> seekPreview
                dur > 0 -> t.positionNow().toFloat() / dur
                else -> 0f
            }.coerceIn(0f, 1f)
            val l = textX
            val r = w - 6f * u
            seekRect.set(l, sy - 4f * u, r, sy + 4f * u)
            val th = if (target == Target.SEEK) 2f * u else 1.4f * u
            fill.color = 0x33FFFFFF
            tmp.set(l, sy - th / 2, r, sy + th / 2)
            canvas.drawRoundRect(tmp, th, th, fill)
            if (dur > 0) {
                val px = l + (r - l) * frac
                fill.color = accent
                tmp.set(l, sy - th / 2, px, sy + th / 2)
                canvas.drawRoundRect(tmp, th, th, fill)
                if (t.canSeek) {
                    fill.color = Color.WHITE
                    fill.setShadowLayer(1.5f * u, 0f, 0.5f * u, 0x66000000)
                    canvas.drawCircle(px, sy, if (target == Target.SEEK) 2.6f * u else 2f * u, fill)
                    fill.clearShadowLayer()
                }
                text.typeface = fonts.body
                text.textSize = 3f * u
                text.color = MUTED
                val pos = (frac * dur).toLong()
                canvas.drawText(formatTime(pos), l, artBottom + timesOff, text)
                val rem = "-" + formatTime(dur - pos)
                canvas.drawText(rem, r - text.measureText(rem), artBottom + timesOff, text)
            }
        }

        // Transport controls.
        ctrlY = artBottom + ctrlOff
        val spread = 26f * u
        playX = cx
        prevX = cx - spread
        nextX = cx + spread
        playR = 10f * u
        drawPlayButton(canvas, playX, ctrlY, playR * (if (pressed == Target.PLAY) 0.93f else 1f), t.isPlaying)
        val skipR = 4f * u
        drawSkip(canvas, prevX, ctrlY, skipR * (if (pressed == Target.PREV) 0.85f else 1f), false)
        drawSkip(canvas, nextX, ctrlY, skipR * (if (pressed == Target.NEXT) 0.85f else 1f), true)

        // The app's own extra buttons (Spotify: like, shuffle, repeat...), drawn with the app's icons.
        if (actions.isNotEmpty()) {
            val ay = artBottom + actionsOff
            val gap = 16f * u
            val startX = cx - gap * (actions.size - 1) / 2f
            val size = 6.5f * u
            actions.forEachIndexed { i, a ->
                val ax = startX + gap * i
                val r = RectF(ax - 6f * u, ay - 6f * u, ax + 6f * u, ay + 6f * u)
                actionRects.add(r)
                if (pressed == Target.ACTION && targetIndex == i) {
                    fill.color = 0x1FFFFFFF
                    canvas.drawCircle(ax, ay, 6f * u, fill)
                }
                val icon = a.icon
                if (icon != null) {
                    icon.setBounds((ax - size / 2).toInt(), (ay - size / 2).toInt(), (ax + size / 2).toInt(), (ay + size / 2).toInt())
                    icon.setTint(0xD9FFFFFF.toInt())
                    icon.draw(canvas)
                } else {
                    text.typeface = fonts.bodyMedium
                    text.textSize = 2.8f * u
                    text.color = MUTED
                    val n = TextUtils.ellipsize(a.name, text, 14f * u, TextUtils.TruncateAt.END).toString()
                    canvas.drawText(n, ax - text.measureText(n) / 2, ay + 1f * u, text)
                }
            }
        }
        return animating
    }

    private fun drawQueue(canvas: Canvas, w: Float, top: Float, bottom: Float, t: Track) {
        var y = top + 8f * u
        text.typeface = fonts.displayBold
        text.textSize = 7f * u
        text.color = INK
        canvas.drawText("Up next", 6f * u, y, text)
        y += 6f * u
        text.typeface = fonts.body
        text.textSize = 3.4f * u
        text.color = MUTED
        canvas.drawText("From ${t.appLabel}. Tap a song to jump to it.", 6f * u, y, text)
        y += 5f * u

        listTop = y
        listBottom = bottom
        val rowH = 18f * u
        listMaxScroll = max(0f, t.queue.size * rowH - (listBottom - listTop) + 3f * u)
        queueScroll = queueScroll.coerceIn(0f, listMaxScroll)

        canvas.save()
        canvas.clipRect(0f, listTop, w, listBottom)
        t.queue.forEachIndexed { i, q ->
            val rt = listTop + i * rowH - queueScroll
            if (rt + rowH < listTop || rt > listBottom) return@forEachIndexed
            val r = RectF(4f * u, rt, w - 4f * u, rt + rowH - 1.5f * u)
            rowRects.add(q.id to r)
            val current = q.id == t.queueCurrentId
            if (current || (pressed == Target.ROW && targetIndex == i)) {
                fill.color = if (current) 0x14FFFFFF else 0x0FFFFFFF
                canvas.drawRoundRect(r, 4f * u, 4f * u, fill)
            }
            val mini = 13f * u
            tmp.set(r.left + 2.4f * u, r.centerY() - mini / 2, r.left + 2.4f * u + mini, r.centerY() + mini / 2)
            val qa = q.art
            canvas.save()
            path.reset()
            path.addRoundRect(tmp, 2.6f * u, 2.6f * u, Path.Direction.CW)
            canvas.clipPath(path)
            if (qa != null) {
                centerCrop(qa, srcRect)
                canvas.drawBitmap(qa, srcRect, tmp, bmp)
            } else {
                fill.color = 0x1FFFFFFF
                canvas.drawRect(tmp, fill)
            }
            canvas.restore()
            val tx = tmp.right + 3.5f * u
            val tw = r.right - tx - (if (current) 9f * u else 3f * u)
            text.typeface = fonts.bodyMedium
            text.textSize = 4f * u
            text.color = if (current) accent else INK
            val tt = TextUtils.ellipsize(q.title, text, tw, TextUtils.TruncateAt.END)
            canvas.drawText(tt, 0, tt.length, tx, r.centerY() - 0.4f * u, text)
            text.typeface = fonts.body
            text.textSize = 3.3f * u
            text.color = MUTED
            val st = TextUtils.ellipsize(q.subtitle, text, tw, TextUtils.TruncateAt.END)
            canvas.drawText(st, 0, st.length, tx, r.centerY() + 4f * u, text)
            if (current) drawEq(canvas, r.right - 5f * u, r.centerY(), t.isPlaying)
        }
        canvas.restore()
    }

    /** Returns true while the lyrics are scrolling to the current line. */
    private fun drawLyrics(canvas: Canvas, w: Float, top: Float, bottom: Float, t: Track): Boolean {
        val l = lyrics
        listTop = top + 2f * u
        listBottom = bottom - 6f * u
        val status = when (l) {
            null, Lyrics.Loading -> "Looking up lyrics…"
            Lyrics.NotFound -> "No lyrics found for this song."
            Lyrics.Offline -> "Lyrics need internet the first time a song plays."
            Lyrics.Instrumental -> "Instrumental"
            else -> null
        }
        if (status != null) {
            listMaxScroll = 0f
            text.typeface = fonts.displaySemi
            text.textSize = 4.6f * u
            text.color = MUTED
            drawCentered(canvas, status, w / 2f, top + (bottom - top) * 0.4f)
            return false
        }

        val synced = l as? Lyrics.Synced
        val lines = synced?.lines?.map { it.second } ?: (l as Lyrics.Plain).lines
        val width = (w - 12f * u).toInt()
        var layouts = lyricLayouts
        if (layouts == null || lyricLayoutWidth != width) {
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = fonts.displaySemi
                textSize = (if (synced != null) 6f else 4.6f) * u
                color = Color.WHITE
            }
            layouts = lines.map { line ->
                StaticLayout.Builder.obtain(line.ifBlank { "♪" }, 0, line.ifBlank { "♪" }.length, p, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.1f)
                    .build()
            }
            lyricLayouts = layouts
            lyricLayoutWidth = width
        }

        val gap = (if (synced != null) 4.5f else 2f) * u
        lyricTops.clear()
        var y = 0f
        for (lay in layouts) {
            lyricTops.add(y)
            y += lay.height + gap
        }
        val area = listBottom - listTop
        listMaxScroll = max(0f, y - area * 0.5f)

        // Synced lyrics follow the song unless you scrolled in the last few seconds.
        var current = -1
        var animating = false
        if (synced != null) {
            val pos = t.positionNow() + 300
            current = synced.lines.indexOfLast { it.first <= pos }
            if (target != Target.LYRIC && SystemClock.uptimeMillis() > lyricManualUntil && current >= 0) {
                val goal = (lyricTops[current] + layouts[current].height / 2f - area * 0.35f).coerceIn(0f, listMaxScroll)
                lyricScroll += (goal - lyricScroll) * 0.12f
                if (abs(goal - lyricScroll) > 0.5f) animating = true
            }
        }
        lyricScroll = lyricScroll.coerceIn(0f, listMaxScroll)

        canvas.save()
        canvas.clipRect(0f, listTop, w, listBottom)
        layouts.forEachIndexed { i, lay ->
            val ly = listTop + lyricTops[i] - lyricScroll
            if (ly + lay.height < listTop || ly > listBottom) return@forEachIndexed
            lay.paint.color = when {
                synced == null -> 0xD9FFFFFF.toInt()
                i == current -> INK
                i < current -> 0x59FFFFFF
                else -> 0x8CFFFFFF.toInt()
            }
            canvas.save()
            canvas.translate(6f * u, ly)
            lay.draw(canvas)
            canvas.restore()
        }
        canvas.restore()

        text.typeface = fonts.body
        text.textSize = 2.6f * u
        text.color = 0x66FFFFFF
        drawCentered(canvas, if (t.canSeek && synced != null) "Lyrics from LRCLIB · tap a line to jump to it" else "Lyrics from LRCLIB", w / 2f, bottom - 2f * u)
        return animating
    }

    /** Index of the lyric line at screen y, or -1. */
    private fun lyricAt(y: Float): Int {
        val layouts = lyricLayouts ?: return -1
        if (y < listTop || y > listBottom) return -1
        val content = y - listTop + lyricScroll
        for (i in layouts.indices) {
            if (content >= lyricTops.getOrElse(i) { 0f } && content <= lyricTops.getOrElse(i) { 0f } + layouts[i].height) return i
        }
        return -1
    }

    private fun drawSources(canvas: Canvas, w: Float, top: Float, bottom: Float) {
        var y = top + 8f * u
        text.typeface = fonts.displayBold
        text.textSize = 7f * u
        text.color = INK
        canvas.drawText("Sources", 6f * u, y, text)
        y += 6f * u
        text.typeface = fonts.body
        text.textSize = 3.4f * u
        text.color = MUTED
        canvas.drawText(
            if (sources.isEmpty()) "No app has music loaded right now." else "Apps that have music loaded right now. Tap one to control it.",
            6f * u, y, text,
        )
        y += 5f * u

        val gap = 3f * u
        val cw = (w - 12f * u - gap) / 2
        val ch = 30f * u
        sources.take(6).forEachIndexed { i, s ->
            val col = i % 2
            val row = i / 2
            val l = 6f * u + col * (cw + gap)
            val t = y + row * (ch + gap)
            val r = RectF(l, t, l + cw, t + ch)
            sourceRects.add(r)
            val sel = s.packageName == track?.packageName
            fill.color = if (sel) 0x24FFFFFF else if (pressed == Target.SOURCE && targetIndex == i) 0x1AFFFFFF else 0x0FFFFFFF
            canvas.drawRoundRect(r, 5f * u, 5f * u, fill)
            if (sel) {
                stroke.color = 0x33FFFFFF
                stroke.strokeWidth = max(1f, 0.2f * u)
                canvas.drawRoundRect(r, 5f * u, 5f * u, stroke)
            }
            val icon = s.icon
            val isz = 12f * u
            if (icon != null) {
                icon.setBounds((l + 4f * u).toInt(), (t + 4f * u).toInt(), (l + 4f * u + isz).toInt(), (t + 4f * u + isz).toInt())
                icon.draw(canvas)
            }
            text.typeface = fonts.bodyMedium
            text.textSize = 4f * u
            text.color = INK
            val n = TextUtils.ellipsize(s.label, text, cw - 8f * u, TextUtils.TruncateAt.END)
            canvas.drawText(n, 0, n.length, l + 4f * u, t + 22f * u, text)
            text.typeface = fonts.body
            text.textSize = 3f * u
            text.color = MUTED
            var sx = l + 4f * u
            if (s.isPlaying) {
                fill.color = accent
                canvas.drawCircle(sx + 0.8f * u, t + 25.9f * u, 0.8f * u, fill)
                sx += 3f * u
            }
            canvas.drawText(if (s.isPlaying) "Playing" else "Paused", sx, t + 27f * u, text)
        }

        // Update card, only when a newer build is published.
        updateRect.setEmpty()
        if (updateVersion > 0) {
            val rows = (min(sources.size, 6) + 1) / 2
            val ut = y + rows * (ch + gap) + 2f * u
            updateRect.set(6f * u, ut, w - 6f * u, ut + 16f * u)
            fill.color = if (pressed == Target.UPDATE) 0x24FFFFFF else 0x0FFFFFFF
            canvas.drawRoundRect(updateRect, 5f * u, 5f * u, fill)
            text.typeface = fonts.bodyMedium
            text.textSize = 4f * u
            text.color = INK
            canvas.drawText("Update available", updateRect.left + 4f * u, updateRect.top + 7f * u, text)
            text.typeface = fonts.body
            text.textSize = 3.2f * u
            text.color = MUTED
            canvas.drawText("Build $updateVersion · tap to install", updateRect.left + 4f * u, updateRect.top + 12f * u, text)
            drawPlayButtonSmall(canvas, updateRect.right - 8f * u, updateRect.centerY())
        }
    }

    private fun drawNothingPlaying(canvas: Canvas, w: Float, top: Float, bottom: Float) {
        val cx = w / 2f
        var y = top + max(14f * u, (bottom - top) * 0.16f)
        drawDisc(canvas, cx, y, 15f * u)
        y += 26f * u
        text.typeface = fonts.displayBold
        text.textSize = 6.5f * u
        text.color = INK
        drawCentered(canvas, "Nothing playing", cx, y)
        y += 6.5f * u
        text.typeface = fonts.body
        text.textSize = 3.6f * u
        text.color = MUTED
        drawCentered(canvas, "Resume picks up where your last music app stopped.", cx, y)
        y += 8f * u

        // Play from cold: a "play" media key resumes the last music app.
        resumeRect.set(cx - 24f * u, y, cx + 24f * u, y + 12f * u)
        fill.shader = LinearGradient(resumeRect.left, 0f, resumeRect.right, 0f, accent, accent2, Shader.TileMode.CLAMP)
        val sc = if (pressed == Target.RESUME) 0.96f else 1f
        canvas.save()
        canvas.scale(sc, sc, resumeRect.centerX(), resumeRect.centerY())
        canvas.drawRoundRect(resumeRect, 6f * u, 6f * u, fill)
        fill.shader = null
        drawPlay(canvas, resumeRect.left + 9f * u, resumeRect.centerY(), 2.2f * u, DARK)
        text.typeface = fonts.displaySemi
        text.textSize = 4f * u
        text.color = DARK
        canvas.drawText("Resume music", resumeRect.left + 14f * u, resumeRect.centerY() + 1.4f * u, text)
        canvas.restore()
        y += 22f * u

        if (launchApps.isEmpty()) return
        text.typeface = fonts.bodyMedium
        text.textSize = 3.2f * u
        text.color = MUTED
        drawCentered(canvas, "Or open a music app", cx, y)
        y += 6f * u
        val cols = 4
        val cell = 21f * u
        val icon = 12f * u
        val apps = launchApps.take(8)
        val rows = (apps.size + cols - 1) / cols
        text.typeface = fonts.body
        text.textSize = 2.8f * u
        for (row in 0 until rows) {
            val inRow = min(cols, apps.size - row * cols)
            val rowLeft = cx - inRow * cell / 2f
            for (col in 0 until inRow) {
                val app = apps[row * cols + col]
                val ix = rowLeft + col * cell + cell / 2f
                val iy = y + row * (cell + 4f * u)
                if (iy + icon > bottom) return
                appRects.add(RectF(ix - cell / 2, iy - 2f * u, ix + cell / 2, iy + icon + 6f * u))
                app.icon.setBounds((ix - icon / 2).toInt(), iy.toInt(), (ix + icon / 2).toInt(), (iy + icon).toInt())
                app.icon.draw(canvas)
                text.color = 0xCCFFFFFF.toInt()
                val label = TextUtils.ellipsize(app.label, text, cell - 2f * u, TextUtils.TruncateAt.END).toString()
                drawCentered(canvas, label, ix, iy + icon + 4.5f * u)
            }
        }
    }

    private fun drawNoAccess(canvas: Canvas, w: Float, top: Float, bottom: Float) {
        val cx = w / 2f
        var y = top + (bottom - top) * 0.25f
        drawDisc(canvas, cx, y, 15f * u)
        y += 26f * u
        text.typeface = fonts.displayBold
        text.textSize = 6f * u
        text.color = INK
        drawCentered(canvas, "Allow music access", cx, y)
        y += 7f * u
        text.typeface = fonts.body
        text.textSize = 3.6f * u
        text.color = MUTED
        drawCentered(canvas, "Player needs notification access", cx, y)
        y += 5f * u
        drawCentered(canvas, "to see and control what's playing.", cx, y)
        y += 9f * u
        accessRect.set(cx - 22f * u, y, cx + 22f * u, y + 11f * u)
        fill.shader = LinearGradient(accessRect.left, 0f, accessRect.right, 0f, accent, accent2, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(accessRect, 5.5f * u, 5.5f * u, fill)
        fill.shader = null
        text.typeface = fonts.displaySemi
        text.textSize = 3.8f * u
        text.color = DARK
        drawCentered(canvas, "Open settings", cx, accessRect.centerY() + 1.3f * u)
    }

    private fun drawToast(canvas: Canvas, w: Float, bottom: Float, msg: String) {
        text.typeface = fonts.bodyMedium
        text.textSize = 3.4f * u
        text.color = INK
        val tw = min(text.measureText(msg), w - 20f * u)
        val line = TextUtils.ellipsize(msg, text, tw, TextUtils.TruncateAt.END)
        tmp.set((w - tw) / 2 - 4f * u, bottom - 13f * u, (w + tw) / 2 + 4f * u, bottom - 4f * u)
        fill.color = 0xF0202430.toInt()
        canvas.drawRoundRect(tmp, 4.5f * u, 4.5f * u, fill)
        canvas.drawText(line, 0, line.length, (w - tw) / 2, tmp.centerY() + 1.2f * u, text)
    }

    // ---------------------------------------------------------------- icons

    private fun drawPlayButton(canvas: Canvas, cx: Float, cy: Float, r: Float, playing: Boolean) {
        fill.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, accent, accent2, Shader.TileMode.CLAMP)
        fill.setShadowLayer(4f * u, 0f, 1.5f * u, withAlpha(accent, 0x80))
        canvas.drawCircle(cx, cy, r, fill)
        fill.clearShadowLayer()
        fill.shader = null
        if (playing) drawPause(canvas, cx, cy, r * 0.4f, DARK) else drawPlay(canvas, cx + r * 0.06f, cy, r * 0.42f, DARK)
    }

    private fun drawPlayButtonSmall(canvas: Canvas, cx: Float, cy: Float) {
        fill.shader = LinearGradient(cx - 5f * u, cy, cx + 5f * u, cy, accent, accent2, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, 5f * u, fill)
        fill.shader = null
        // Down arrow: "download and install".
        stroke.color = DARK
        stroke.strokeWidth = 0.7f * u
        canvas.drawLine(cx, cy - 2.2f * u, cx, cy + 2f * u, stroke)
        canvas.drawLine(cx - 1.8f * u, cy + 0.3f * u, cx, cy + 2.1f * u, stroke)
        canvas.drawLine(cx + 1.8f * u, cy + 0.3f * u, cx, cy + 2.1f * u, stroke)
    }

    private fun drawPlay(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        path.reset()
        path.moveTo(cx - r * 0.75f, cy - r)
        path.lineTo(cx + r, cy)
        path.lineTo(cx - r * 0.75f, cy + r)
        path.close()
        fill.color = color
        fill.pathEffect = CornerPathEffect(r * 0.25f)
        canvas.drawPath(path, fill)
        fill.pathEffect = null
    }

    private fun drawPause(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        fill.color = color
        val bw = r * 0.6f
        val gap = r * 0.35f
        tmp.set(cx - gap - bw, cy - r, cx - gap, cy + r)
        canvas.drawRoundRect(tmp, bw * 0.3f, bw * 0.3f, fill)
        tmp.set(cx + gap, cy - r, cx + gap + bw, cy + r)
        canvas.drawRoundRect(tmp, bw * 0.3f, bw * 0.3f, fill)
    }

    /** A bar and a triangle; mirrored for "previous". */
    private fun drawSkip(canvas: Canvas, cx: Float, cy: Float, r: Float, forward: Boolean) {
        val d = if (forward) 1f else -1f
        fill.color = INK
        fill.pathEffect = CornerPathEffect(r * 0.2f)
        path.reset()
        path.moveTo(cx - d * r * 0.9f, cy - r)
        path.lineTo(cx + d * r * 0.7f, cy)
        path.lineTo(cx - d * r * 0.9f, cy + r)
        path.close()
        canvas.drawPath(path, fill)
        fill.pathEffect = null
        val bx = cx + d * r * 0.95f
        tmp.set(bx - r * 0.16f, cy - r, bx + r * 0.16f, cy + r)
        canvas.drawRoundRect(tmp, r * 0.16f, r * 0.16f, fill)
    }

    private fun drawEq(canvas: Canvas, cx: Float, cy: Float, playing: Boolean) {
        fill.color = accent
        val now = SystemClock.uptimeMillis()
        for (i in 0..2) {
            val f = if (playing) 0.35f + 0.65f * abs(sin(now / 280.0 + i * 1.3).toFloat()) else 0.4f
            val bh = 5f * u * f
            val x = cx - 2f * u + i * 1.9f * u
            tmp.set(x, cy + 2.5f * u - bh, x + 1.1f * u, cy + 2.5f * u)
            canvas.drawRoundRect(tmp, 0.5f * u, 0.5f * u, fill)
        }
        if (playing) postInvalidateOnAnimation()
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

    private fun drawDisc(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        fill.color = 0x1FFFFFFF
        canvas.drawCircle(cx, cy, r, fill)
        stroke.color = 0x14FFFFFF
        stroke.strokeWidth = 0.25f * u
        var rr = r * 0.9f
        while (rr > r * 0.42f) {
            canvas.drawCircle(cx, cy, rr, stroke)
            rr -= r * 0.09f
        }
        fill.shader = LinearGradient(cx - r * 0.32f, cy - r * 0.32f, cx + r * 0.32f, cy + r * 0.32f, accent, accent2, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 0.32f, fill)
        fill.shader = null
        fill.color = 0xFF07080B.toInt()
        canvas.drawCircle(cx, cy, r * 0.06f, fill)
    }

    private fun drawCentered(canvas: Canvas, s: String, cx: Float, y: Float) {
        canvas.drawText(s, cx - text.measureText(s) / 2f, y, text)
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
                verticalDrag = false
                scrollAtDown = if (tab == Tab.LYRICS) lyricScroll else queueScroll
                target = hitTest(x, y)
                pressed = target
                if (target == Target.SEEK) seekPreview = seekFraction(x)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - downX
                val dy = y - downY
                when (target) {
                    Target.SEEK -> seekPreview = seekFraction(x)
                    Target.ART -> {
                        if (!horizontalDrag && abs(dx) > 3f * u && abs(dx) > abs(dy)) horizontalDrag = true
                        if (horizontalDrag) {
                            dragX = dx
                            pressed = Target.NONE
                        }
                    }
                    else -> {
                        val scrolls = tab == Tab.QUEUE || tab == Tab.LYRICS
                        if (scrolls && y > listTop && (verticalDrag || abs(dy) > 3f * u)) {
                            verticalDrag = true
                            pressed = Target.NONE
                            if (tab == Tab.QUEUE) {
                                queueScroll = (scrollAtDown - dy).coerceIn(0f, listMaxScroll)
                            } else {
                                lyricScroll = (scrollAtDown - dy).coerceIn(0f, listMaxScroll)
                                lyricManualUntil = SystemClock.uptimeMillis() + 4000
                            }
                        } else if (hypot(dx, dy) > 6f * u) {
                            pressed = Target.NONE
                        }
                    }
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
                    seekHoldUntil = SystemClock.uptimeMillis() + 1500
                    l.onSeek((seekFraction(x) * t.durationMs).toLong())
                } else {
                    seekPreview = -1f
                }
            }
            Target.ART -> {
                val dx = x - downX
                when {
                    horizontalDrag && dx < -18f * u -> l.onNext()
                    horizontalDrag && dx > 18f * u -> l.onPrevious()
                    !horizontalDrag && hypot(dx, y - downY) < 4f * u -> l.onPlayPause()
                }
            }
            Target.NONE -> {}
            else -> if (pressed == target) tap(l, t)
        }
    }

    private fun tap(l: Listener, t: Track?) {
        when (target) {
            Target.PREV -> l.onPrevious()
            Target.PLAY, Target.RESUME -> l.onPlayPause()
            Target.NEXT -> l.onNext()
            Target.ACTION -> t?.actions?.getOrNull(targetIndex)?.let { l.onAction(it.action) }
            Target.TAB -> tabRects.getOrNull(targetIndex)?.let { showTab(it.first) }
            Target.PILL -> showTab(Tab.SOURCES)
            Target.ROW -> rowRects.getOrNull(targetIndex)?.let {
                l.onQueueItem(it.first)
                showTab(Tab.NOW)
            }
            Target.SOURCE -> sources.getOrNull(targetIndex)?.let {
                if (it.packageName == t?.packageName) l.onOpenApp(it.packageName) else l.onSelectSource(it.packageName)
                showTab(Tab.NOW)
            }
            Target.APP -> launchApps.getOrNull(targetIndex)?.let { l.onOpenApp(it.packageName) }
            Target.LYRIC -> {
                val synced = lyrics as? Lyrics.Synced
                val line = synced?.lines?.getOrNull(targetIndex)
                if (line != null && t != null && t.canSeek) {
                    l.onSeek(line.first)
                    lyricManualUntil = 0L
                }
            }
            Target.ACCESS -> l.onRequestAccess()
            Target.UPDATE -> l.onInstallUpdate()
            else -> {}
        }
    }

    private fun hitTest(x: Float, y: Float): Target {
        targetIndex = -1
        fun idx(list: List<RectF>, tg: Target): Target? {
            list.forEachIndexed { i, r ->
                if (r.contains(x, y)) {
                    targetIndex = i
                    return tg
                }
            }
            return null
        }
        tabRects.forEachIndexed { i, (_, r) ->
            if (hasAccess && r.contains(x, y)) {
                targetIndex = i
                return Target.TAB
            }
        }
        if (!pillRect.isEmpty && pillRect.contains(x, y)) return Target.PILL
        if (!hasAccess) return if (accessRect.contains(x, y)) Target.ACCESS else Target.NONE
        val t = track
        when {
            tab == Tab.SOURCES -> {
                if (!updateRect.isEmpty && updateRect.contains(x, y)) return Target.UPDATE
                return idx(sourceRects, Target.SOURCE) ?: Target.NONE
            }
            tab == Tab.LYRICS && t != null -> {
                val i = lyricAt(y)
                if (i < 0) return Target.NONE
                targetIndex = i
                return Target.LYRIC
            }
            tab == Tab.QUEUE && t != null -> {
                rowRects.forEachIndexed { i, (_, r) ->
                    if (y > listTop && r.contains(x, y)) {
                        targetIndex = i
                        return Target.ROW
                    }
                }
                return Target.NONE
            }
            t == null -> {
                if (resumeRect.contains(x, y)) return Target.RESUME
                return idx(appRects, Target.APP) ?: Target.NONE
            }
        }
        if (artRect.contains(x, y)) return Target.ART
        if (!seekRect.isEmpty && seekRect.contains(x, y) && t!!.canSeek && t.durationMs > 0) return Target.SEEK
        idx(actionRects, Target.ACTION)?.let { return it }
        val hit = playR + 2f * u
        if (hypot(x - playX, y - ctrlY) < hit) return Target.PLAY
        if (hypot(x - prevX, y - ctrlY) < hit) return Target.PREV
        if (hypot(x - nextX, y - ctrlY) < hit) return Target.NEXT
        return Target.NONE
    }

    private fun seekFraction(x: Float) = ((x - seekRect.left) / seekRect.width()).coerceIn(0f, 1f)

    private fun centerCrop(b: Bitmap, out: Rect) {
        val side = min(b.width, b.height)
        val l = (b.width - side) / 2
        val t = (b.height - side) / 2
        out.set(l, t, l + side, t + side)
    }

    companion object {
        private const val INK = 0xFFF3F4F7.toInt()
        private const val MUTED = 0xFF9AA0AE.toInt()
        private const val DARK = 0xFF101114.toInt()
        private const val DEFAULT_ACCENT = 0xFFFF7A59.toInt()
        private const val DEFAULT_ACCENT2 = 0xFF6B3CFF.toInt()

        /** Shrinking to a few pixels and stretching back with filtering is a cheap, smooth blur. */
        fun blurred(src: Bitmap): Bitmap? = try {
            val small = Bitmap.createScaledBitmap(src, 10, 10, true)
            Bitmap.createScaledBitmap(small, 40, 40, true)
        } catch (_: Exception) {
            null
        }

        /**
         * The two most colourful hues in the art, brightened so they read on a dark screen.
         * Used for the play button's gradient, the seek bar and the glow.
         */
        fun accentsOf(src: Bitmap): IntArray? {
            val small = try {
                // Hardware bitmaps can't be read pixel by pixel, so copy into a plain one.
                Bitmap.createScaledBitmap(src, 24, 24, true).copy(Bitmap.Config.ARGB_8888, false)
            } catch (_: Exception) {
                null
            } ?: return null
            val weights = FloatArray(12)
            val sums = Array(12) { FloatArray(3) }
            val hsv = FloatArray(3)
            for (y in 0 until small.height) for (x in 0 until small.width) {
                val c = small.getPixel(x, y)
                Color.colorToHSV(c, hsv)
                if (hsv[1] < 0.2f || hsv[2] < 0.2f) continue
                val wgt = hsv[1] * hsv[1] * hsv[2]
                val bucket = (hsv[0] / 30f).toInt().coerceIn(0, 11)
                weights[bucket] += wgt
                sums[bucket][0] += Color.red(c) * wgt
                sums[bucket][1] += Color.green(c) * wgt
                sums[bucket][2] += Color.blue(c) * wgt
            }
            val order = weights.indices.sortedByDescending { weights[it] }
            val first = order[0]
            if (weights[first] < 0.5f) return null
            // The second colour must be a clearly different hue; otherwise shift the first.
            val second = order.drop(1).firstOrNull { abs(it - first) in 2..10 && weights[it] > weights[first] * 0.15f }

            fun colourOf(b: Int, minV: Float): Int {
                val wt = weights[b]
                val c = Color.rgb((sums[b][0] / wt).toInt(), (sums[b][1] / wt).toInt(), (sums[b][2] / wt).toInt())
                Color.colorToHSV(c, hsv)
                hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
                hsv[2] = max(hsv[2], minV)
                return Color.HSVToColor(hsv)
            }
            val a = colourOf(first, 0.95f)
            val b = if (second != null) colourOf(second, 0.75f) else {
                Color.colorToHSV(a, hsv)
                hsv[0] = (hsv[0] + 40f) % 360f
                hsv[2] = 0.8f
                Color.HSVToColor(hsv)
            }
            return intArrayOf(a, b)
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
