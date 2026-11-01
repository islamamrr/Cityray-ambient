package dev.carapps.surprise

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Night Shift: a neon road game you steer with the car's real steering wheel, while parked.
 * Falls back to touch steering when there is no wheel signal (or on a phone).
 */
class GameView(
    context: Context,
    private val car: CarLink,
    private val steering: Steering,
    private val sfx: Sfx,
) : View(context), Choreographer.FrameCallback {

    private enum class Mode { INTRO, CAL_CENTER, CAL_LEFT, COUNTDOWN, PLAYING, OVER }

    private class Traffic(var z: Float, var offset: Float, var speed: Float, val color: Int)

    private val prefs = context.getSharedPreferences("game", Context.MODE_PRIVATE)
    private val rnd = Random(SystemClock.uptimeMillis())
    private val segs = Track.build(rnd.nextLong())
    private val trackLen = segs.size * Track.SEG
    private val cars = ArrayList<Traffic>()

    private var mode = Mode.INTRO
    private var paused = false
    private var running = false
    private var lastFrame = 0L

    /** Set by the activity: true while the car is known to be out of Park. */
    var blocked = false
        set(v) {
            field = v
            if (v && (mode == Mode.PLAYING || mode == Mode.COUNTDOWN)) paused = true
            invalidate()
        }

    // Game state
    private var position = 0f
    private var speed = 0f
    private var playerX = 0f
    private var steerNow = 0f
    private var lives = 3
    private var score = 0f
    private var best = prefs.getInt("best", 0)
    private var playTime = 0f
    private var invuln = 0f
    private var shake = 0f
    private var flash = 0f
    private var countdown = 0f
    private var overAt = 0L
    private var skyOffset = 0f
    private var combo = 0
    private var orbsTotal = 0
    private var popText: String? = null
    private var popTime = 0f
    private var message: String? = null
    private var messageUntil = 0L
    private var calCenter = 0f
    private var useTouch = false
    private var wheelUsed = false

    // Touch
    private var touching = false
    private var touchSteer = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    // Drawing
    private val fill = Paint().apply { style = Paint.Style.FILL }
    private val aa = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val rect = RectF()
    private var skyShader: Shader? = null
    private var sunShader: Shader? = null
    private var shaderH = 0
    private val visible = IntArray(DRAW_DIST)
    private val stars = Array(90) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat() * 0.36f, rnd.nextFloat()) }
    private val farHills = FloatArray(48) { 0.03f + rnd.nextFloat() * 0.06f }
    private val nearHills = FloatArray(32) { 0.02f + rnd.nextFloat() * 0.05f }

    init {
        resetTraffic()
    }

    // ---------------------------------------------------------------- lifecycle

    fun resume() {
        if (running) return
        running = true
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun pause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        if (mode == Mode.PLAYING || mode == Mode.COUNTDOWN) paused = true
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastFrame == 0L) 1f / 60f else ((frameTimeNanos - lastFrame) / 1e9f).coerceIn(0f, 0.05f)
        lastFrame = frameTimeNanos
        update(dt)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    // ---------------------------------------------------------------- game logic

    private fun wheelMode(): Boolean = !useTouch && steering.calibrated && car.hasSteer

    private fun readSteer(): Float {
        if (touching) return touchSteer
        val raw = car.steerRaw
        if (wheelMode() && raw != null) return steering.input(raw)
        return 0f
    }

    private fun segAt(z: Float): Segment {
        val i = ((z % trackLen + trackLen) % trackLen / Track.SEG).toInt()
        return segs[i.coerceIn(0, segs.size - 1)]
    }

    private fun wrap(z: Float): Float = ((z % trackLen) + trackLen) % trackLen

    private fun resetTraffic() {
        cars.clear()
        val count = 38
        for (i in 0 until count) {
            val z = PLAYER_Z + Track.SEG * 70 + i * (trackLen - Track.SEG * 90) / count + rnd.nextFloat() * Track.SEG * 20
            val lane = rnd.nextInt(3) - 1
            cars += Traffic(
                z = wrap(z),
                offset = lane * 0.62f + (rnd.nextFloat() - 0.5f) * 0.1f,
                speed = MAX_SPEED * (0.22f + rnd.nextFloat() * 0.25f),
                color = TRAFFIC_COLORS[rnd.nextInt(TRAFFIC_COLORS.size)],
            )
        }
    }

    private fun startGame() {
        position = 0f
        speed = 0f
        playerX = 0f
        lives = 3
        score = 0f
        playTime = 0f
        invuln = 0f
        combo = 0
        orbsTotal = 0
        paused = false
        segs.forEach { it.orbTaken = false }
        resetTraffic()
        countdown = 3.999f
        mode = Mode.COUNTDOWN
        sfx.beep()
    }

    private fun update(dt: Float) {
        steerNow = readSteer()
        if (abs(steerNow) > 0.15f && !touching && wheelMode()) wheelUsed = true

        shake = max(0f, shake - dt)
        flash = max(0f, flash - dt)
        popTime = max(0f, popTime - dt)

        if (blocked || paused) return

        when (mode) {
            Mode.INTRO, Mode.CAL_CENTER, Mode.CAL_LEFT, Mode.OVER -> {
                // Attract mode: cruise slowly, and let the wheel move the car so it can be tried out.
                speed += (MAX_SPEED * 0.35f - speed) * min(1f, dt * 1.5f)
                playerX = (playerX + steerNow * dt * STEER_RATE * 0.5f).coerceIn(-0.9f, 0.9f)
                if (abs(steerNow) < 0.05f) playerX *= (1f - dt * 0.6f)
                advance(dt)
                moveTraffic(dt)
            }
            Mode.COUNTDOWN -> {
                speed = 0f
                val before = countdown.toInt()
                countdown -= dt
                if (countdown <= 1f) {
                    mode = Mode.PLAYING
                    sfx.go()
                } else if (countdown.toInt() != before) {
                    sfx.beep()
                }
            }
            Mode.PLAYING -> play(dt)
        }
    }

    private fun play(dt: Float) {
        playTime += dt
        invuln = max(0f, invuln - dt)

        val pSeg = segAt(position + PLAYER_Z)
        val pct = speed / MAX_SPEED

        playerX += steerNow * dt * STEER_RATE * max(pct, 0.3f)
        playerX -= dt * pct * pct * pSeg.curve * CENTRIFUGAL
        playerX = playerX.coerceIn(-2.2f, 2.2f)

        val target = MAX_SPEED * (0.6f + min(playTime / 150f, 0.5f))
        val offroad = abs(playerX) > 1.08f
        speed = when {
            offroad && speed > MAX_SPEED * 0.3f -> speed - MAX_SPEED * 0.9f * dt
            speed < target -> min(target, speed + MAX_SPEED * 0.35f * dt)
            else -> max(target, speed - MAX_SPEED * 0.5f * dt)
        }
        if (offroad) shake = max(shake, 0.06f)

        val prevPlayerZ = position + PLAYER_Z
        val prevGaps = FloatArray(cars.size) { gap(cars[it].z, prevPlayerZ) }
        val prevSegIndex = pSeg.index

        advance(dt)
        moveTraffic(dt)
        score += speed * dt / 400f

        // Orbs in every segment we crossed this frame.
        val nowSeg = segAt(position + PLAYER_Z).index
        var i = prevSegIndex
        var guard = 0
        while (guard++ < 8) {
            val s = segs[i]
            if (s.hasOrb && abs(s.orbOffset - playerX) < 0.3f) {
                s.orbTaken = true
                combo++
                orbsTotal++
                val bonus = 25 * min(combo, 8)
                score += bonus
                popText = "+$bonus"
                popTime = 0.8f
                sfx.pickup()
            } else if (s.hasOrb && i != nowSeg) {
                combo = 0 // missed one
            }
            if (i == nowSeg) break
            i = (i + 1) % segs.size
        }

        // Traffic: a crash happens when we close the gap on a car in our lane.
        if (invuln <= 0f) {
            for ((k, c) in cars.withIndex()) {
                val before = prevGaps[k]
                val now = gap(c.z, position + PLAYER_Z)
                if (before >= -Track.SEG * 0.2f && now < Track.SEG * 0.4f && now > -Track.SEG * 1.5f &&
                    abs(c.offset - playerX) < 0.36f
                ) {
                    crash(c)
                    break
                }
            }
        }
    }

    /** Distance from the player to a car ahead, wrapped to the nearest lap. */
    private fun gap(carZ: Float, playerZ: Float): Float {
        var d = carZ - wrap(playerZ)
        if (d > trackLen / 2) d -= trackLen
        if (d < -trackLen / 2) d += trackLen
        return d
    }

    private fun advance(dt: Float) {
        val before = position
        position = wrap(position + speed * dt)
        if (position < before) segs.forEach { it.orbTaken = false } // new lap, new orbs
        val curve = segAt(position + PLAYER_Z).curve
        skyOffset += curve * (speed / MAX_SPEED) * dt * 40f
    }

    private fun moveTraffic(dt: Float) {
        for (c in cars) c.z = wrap(c.z + c.speed * dt)
    }

    private fun crash(c: Traffic) {
        lives--
        combo = 0
        shake = 0.5f
        flash = 0.35f
        invuln = 2f
        speed = c.speed * 0.5f
        position = wrap(c.z - PLAYER_Z - Track.SEG * 1.5f)
        if (lives <= 0) {
            mode = Mode.OVER
            overAt = SystemClock.uptimeMillis()
            val s = score.toInt()
            if (s > best) {
                best = s
                prefs.edit().putInt("best", best).apply()
            }
            sfx.over()
        } else {
            sfx.crash()
            say("CRASH!  ${lives} left", 1200)
        }
    }

    private fun say(msg: String, ms: Long) {
        message = msg
        messageUntil = SystemClock.uptimeMillis() + ms
    }

    // ---------------------------------------------------------------- input

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val w = width.toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downTime = SystemClock.uptimeMillis()
                if (mode == Mode.PLAYING && !paused) {
                    touching = true
                    touchSteer = ((e.x - w / 2) / (w * 0.32f)).coerceIn(-1.3f, 1.3f)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (touching) touchSteer = ((e.x - w / 2) / (w * 0.32f)).coerceIn(-1.3f, 1.3f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasTouching = touching
                touching = false
                touchSteer = 0f
                val tap = e.actionMasked == MotionEvent.ACTION_UP &&
                    hypot(e.x - downX, e.y - downY) < 40f &&
                    SystemClock.uptimeMillis() - downTime < 600
                if (tap && !(wasTouching && mode == Mode.PLAYING)) onTap(e.x, e.y)
            }
        }
        return true
    }

    private fun onTap(x: Float, y: Float) {
        if (blocked) return
        val h = height.toFloat()
        val bottom = y > h * 0.86f
        if (paused) {
            paused = false
            lastFrame = 0L
            return
        }
        when (mode) {
            Mode.INTRO -> when {
                bottom && car.hasSteer -> {
                    if (useTouch) {
                        useTouch = false
                        if (!steering.calibrated) mode = Mode.CAL_CENTER
                    } else {
                        mode = Mode.CAL_CENTER
                    }
                }
                car.hasSteer && !useTouch && !steering.calibrated -> mode = Mode.CAL_CENTER
                else -> startGame()
            }
            Mode.CAL_CENTER -> when {
                bottom -> { useTouch = true; mode = Mode.INTRO }
                else -> {
                    val raw = car.steerRaw
                    if (raw == null) say("No wheel reading yet. Try again in a second.", 2500)
                    else { calCenter = raw; mode = Mode.CAL_LEFT }
                }
            }
            Mode.CAL_LEFT -> when {
                bottom -> { useTouch = true; mode = Mode.INTRO }
                else -> {
                    val raw = car.steerRaw
                    if (raw != null && steering.calibrate(calCenter, raw)) {
                        say("Wheel connected!", 2000)
                        startGame()
                    } else {
                        say("I didn't feel the wheel move. Turn it further left, then tap.", 3000)
                    }
                }
            }
            Mode.OVER -> if (SystemClock.uptimeMillis() - overAt > 1200) startGame()
            else -> {}
        }
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val horizon = h * 0.40f

        canvas.save()
        if (shake > 0f) canvas.translate((rnd.nextFloat() - 0.5f) * shake * 40f, (rnd.nextFloat() - 0.5f) * shake * 30f)

        drawSky(canvas, w, h, horizon)
        drawRoad(canvas, w, h, horizon)
        drawPlayer(canvas, w, h)
        canvas.restore()

        drawHud(canvas, w, h)
        drawOverlay(canvas, w, h)

        if (flash > 0f) {
            fill.color = Color.argb((flash / 0.35f * 150).toInt().coerceIn(0, 255), 255, 40, 90)
            canvas.drawRect(0f, 0f, w, h, fill)
        }
    }

    private fun drawSky(c: Canvas, w: Float, h: Float, horizon: Float) {
        if (shaderH != h.toInt()) {
            shaderH = h.toInt()
            skyShader = LinearGradient(
                0f, 0f, 0f, horizon,
                intArrayOf(0xFF05010F.toInt(), 0xFF1A0533.toInt(), 0xFF4E0E5C.toInt(), 0xFFC2316B.toInt()),
                floatArrayOf(0f, 0.45f, 0.8f, 1f), Shader.TileMode.CLAMP,
            )
            val r = w * 0.3f
            val cy = horizon - w * 0.1f
            sunShader = LinearGradient(
                0f, cy - r, 0f, cy + r,
                intArrayOf(0xFFFFE66D.toInt(), 0xFFFF8A3D.toInt(), 0xFFFF2BD6.toInt()),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
        }
        fill.color = FOG
        c.drawRect(-60f, -60f, w + 60f, h + 60f, fill)
        fill.shader = skyShader
        c.drawRect(-60f, -60f, w + 60f, horizon, fill)
        fill.shader = null

        // Stars
        val t = SystemClock.uptimeMillis() / 1000f
        for (s in stars) {
            val a = (150 + 100 * sin(t * 2f + s[2] * 20f)).toInt().coerceIn(0, 255)
            aa.color = Color.argb(a, 255, 255, 255)
            c.drawCircle(s[0] * w, s[1] * h, 1.5f + s[2] * 2f, aa)
        }

        // Striped sun
        val r = w * 0.3f
        val sunX = w / 2 - (skyOffset * 0.3f) % (w * 0.6f)
        val sunY = horizon - w * 0.1f
        c.save()
        var stripeY = sunY + r * 0.05f
        var gapH = r * 0.03f
        while (stripeY < sunY + r) {
            c.clipOutRect(sunX - r - 2, stripeY, sunX + r + 2, stripeY + gapH)
            stripeY += gapH * 3.2f
            gapH *= 1.25f
        }
        aa.shader = sunShader
        c.drawCircle(sunX, sunY, r, aa)
        aa.shader = null
        c.restore()

        drawHills(c, w, horizon, farHills, 0.15f, 0xFF2A0A45.toInt(), 0xFF7A2FFF.toInt(), h * 0.06f)
        drawHills(c, w, horizon, nearHills, 0.35f, 0xFF1A0630.toInt(), 0xFF2DE2FF.toInt(), h * 0.045f)
    }

    private fun drawHills(c: Canvas, w: Float, horizon: Float, heights: FloatArray, parallax: Float, body: Int, edge: Int, scale: Float) {
        val period = w * 2f
        val step = period / heights.size
        var off = (skyOffset * parallax * 10f) % period
        if (off < 0) off += period
        path.reset()
        path.moveTo(-step, horizon + 2)
        var i = 0
        var x = -off - step
        while (x < w + step) {
            val hh = heights[((i % heights.size) + heights.size) % heights.size] * scale / 0.06f
            path.lineTo(x, horizon - hh)
            x += step
            i++
        }
        path.lineTo(w + step, horizon + 2)
        path.close()
        aa.color = body
        c.drawPath(path, aa)
        stroke.color = edge
        stroke.strokeWidth = 2.5f
        c.drawPath(path, stroke)
    }

    private fun drawRoad(c: Canvas, w: Float, h: Float, horizon: Float) {
        val kx = w / 2
        val ky = h * 0.90f - horizon
        val n = segs.size
        val baseIdx = (position / Track.SEG).toInt().coerceIn(0, n - 1)
        val base = segs[baseIdx]
        val basePct = (position % Track.SEG) / Track.SEG
        val playerZ = position + PLAYER_Z
        val pSeg = segAt(playerZ)
        val pPct = (wrap(playerZ) % Track.SEG) / Track.SEG
        val playerY = pSeg.y1 + (pSeg.y2 - pSeg.y1) * pPct
        val camY = playerY + CAM_H
        val camX = playerX * ROAD_W

        var x = 0f
        var dx = -(base.curve * basePct)
        var maxY = h
        var count = 0
        for (k in 0 until DRAW_DIST) {
            val s = segs[(baseIdx + k) % n]
            val loop = if (s.index < baseIdx) trackLen else 0f
            val cz1 = s.z1 + loop - position
            val cz2 = cz1 + Track.SEG
            val x1 = x
            val x2 = x + dx
            x += dx
            dx += s.curve
            if (cz1 <= CAM_DEPTH) continue
            s.scale1 = CAM_DEPTH / cz1
            s.sx1 = kx + s.scale1 * (x1 - camX) * kx
            s.sy1 = horizon - s.scale1 * (s.y1 - camY) * ky
            s.sw1 = s.scale1 * ROAD_W * kx
            s.scale2 = CAM_DEPTH / cz2
            s.sx2 = kx + s.scale2 * (x2 - camX) * kx
            s.sy2 = horizon - s.scale2 * (s.y2 - camY) * ky
            s.sw2 = s.scale2 * ROAD_W * kx
            s.fog = k / DRAW_DIST.toFloat()
            if (s.sy2 >= s.sy1 || s.sy2 >= maxY) continue
            maxY = s.sy2
            visible[count++] = s.index
        }

        // Far to near, so nearer things paint over farther ones.
        for (v in count - 1 downTo 0) {
            val s = segs[visible[v]]
            drawSegment(c, w, s)
            drawSprites(c, s)
        }
    }

    private fun drawSegment(c: Canvas, w: Float, s: Segment) {
        val f = s.fog * s.fog
        val light = s.light
        fill.color = mix(if (light) GRASS_LIGHT else GRASS_DARK, FOG, f)
        c.drawRect(-60f, s.sy2, w + 60f, s.sy1 + 1f, fill)

        val r1 = s.sw1 / 7f
        val r2 = s.sw2 / 7f
        val rumble = mix(if (light) RUMBLE_LIGHT else RUMBLE_DARK, FOG, f)
        quad(c, s.sx1 - s.sw1 - r1, s.sy1, s.sx1 - s.sw1, s.sy1, s.sx2 - s.sw2, s.sy2, s.sx2 - s.sw2 - r2, s.sy2, rumble)
        quad(c, s.sx1 + s.sw1 + r1, s.sy1, s.sx1 + s.sw1, s.sy1, s.sx2 + s.sw2, s.sy2, s.sx2 + s.sw2 + r2, s.sy2, rumble)
        quad(c, s.sx1 - s.sw1, s.sy1, s.sx1 + s.sw1, s.sy1, s.sx2 + s.sw2, s.sy2, s.sx2 - s.sw2, s.sy2,
            mix(if (light) ROAD_LIGHT else ROAD_DARK, FOG, f))

        if (light) {
            val l1 = s.sw1 / 40f
            val l2 = s.sw2 / 40f
            val lane = mix(LANE, FOG, f)
            for (i in 1 until LANES) {
                val p = -1f + 2f * i / LANES
                val lx1 = s.sx1 + s.sw1 * p
                val lx2 = s.sx2 + s.sw2 * p
                quad(c, lx1 - l1, s.sy1, lx1 + l1, s.sy1, lx2 + l2, s.sy2, lx2 - l2, s.sy2, lane)
            }
        }
    }

    private fun drawSprites(c: Canvas, s: Segment) {
        if (s.hasOrb) {
            val sc = s.scale1
            val ox = s.sx1 + sc * s.orbOffset * ROAD_W * (width / 2f)
            val r = sc * 90f * (width / 2f)
            if (r > 0.8f) {
                val bob = sin(SystemClock.uptimeMillis() / 180.0 + s.index).toFloat() * r * 0.25f
                val cy = s.sy1 - r * 2.2f + bob
                aa.color = mix(0x552DE2FF, FOG and 0x00FFFFFF, s.fog)
                c.drawCircle(ox, cy, r * 1.8f, aa)
                aa.color = mix(0xFF9BF6FF.toInt(), FOG, s.fog * s.fog)
                c.drawCircle(ox, cy, r, aa)
            }
        }
        for (car in cars) {
            if (segAt(car.z).index != s.index) continue
            val pct = (car.z % Track.SEG) / Track.SEG
            val sc = s.scale1 + (s.scale2 - s.scale1) * pct
            val sx = s.sx1 + (s.sx2 - s.sx1) * pct + sc * car.offset * ROAD_W * (width / 2f)
            val sy = s.sy1 + (s.sy2 - s.sy1) * pct
            val wid = sc * CAR_W * (width / 2f)
            if (wid < 2f) continue
            drawCar(c, sx, sy, wid, mix(car.color, FOG, s.fog * s.fog), TAIL_RED, 0f, false)
        }
    }

    private fun drawPlayer(c: Canvas, w: Float, h: Float) {
        if (mode == Mode.CAL_CENTER || mode == Mode.CAL_LEFT) return
        val wid = w * 0.22f
        val bounce = if (speed > 0f) (sin(SystemClock.uptimeMillis() / 60.0).toFloat() * (speed / MAX_SPEED) * h * 0.002f) else 0f
        val visibleNow = invuln <= 0f || (SystemClock.uptimeMillis() / 100) % 2 == 0L
        if (!visibleNow) return
        drawCar(c, w / 2, h * 0.93f + bounce, wid, PLAYER_BODY, PLAYER_LIGHT, steerNow.coerceIn(-1f, 1f) * 5f, true)
    }

    private fun drawCar(c: Canvas, cx: Float, by: Float, wid: Float, body: Int, light: Int, tilt: Float, player: Boolean) {
        val h = wid * 0.55f
        if (wid < 10f) {
            fill.color = body
            c.drawRect(cx - wid / 2, by - h * 0.8f, cx + wid / 2, by, fill)
            fill.color = light
            c.drawRect(cx - wid * 0.45f, by - h * 0.5f, cx + wid * 0.45f, by - h * 0.35f, fill)
            return
        }
        c.save()
        c.rotate(tilt, cx, by)
        aa.color = 0x77000000
        rect.set(cx - wid * 0.58f, by - h * 0.12f, cx + wid * 0.58f, by + h * 0.1f)
        c.drawOval(rect, aa)
        aa.color = 0xFF0A0A12.toInt()
        c.drawRect(cx - wid * 0.47f, by - h * 0.3f, cx - wid * 0.29f, by, aa)
        c.drawRect(cx + wid * 0.29f, by - h * 0.3f, cx + wid * 0.47f, by, aa)
        // Cabin
        path.reset()
        path.moveTo(cx - wid * 0.40f, by - h * 0.62f)
        path.lineTo(cx - wid * 0.27f, by - h * 1.0f)
        path.lineTo(cx + wid * 0.27f, by - h * 1.0f)
        path.lineTo(cx + wid * 0.40f, by - h * 0.62f)
        path.close()
        aa.color = darken(body, 0.55f)
        c.drawPath(path, aa)
        path.reset()
        path.moveTo(cx - wid * 0.34f, by - h * 0.64f)
        path.lineTo(cx - wid * 0.24f, by - h * 0.94f)
        path.lineTo(cx + wid * 0.24f, by - h * 0.94f)
        path.lineTo(cx + wid * 0.34f, by - h * 0.64f)
        path.close()
        aa.color = 0xFF141B33.toInt()
        c.drawPath(path, aa)
        // Body
        aa.color = body
        rect.set(cx - wid / 2, by - h * 0.66f, cx + wid / 2, by - h * 0.14f)
        c.drawRoundRect(rect, wid * 0.08f, wid * 0.08f, aa)
        if (player) {
            stroke.color = 0xFF2DE2FF.toInt()
            stroke.strokeWidth = max(2f, wid * 0.012f)
            c.drawRoundRect(rect, wid * 0.08f, wid * 0.08f, stroke)
        }
        // Light bar with glow
        aa.color = (light and 0x00FFFFFF) or 0x55000000
        rect.set(cx - wid * 0.5f, by - h * 0.56f, cx + wid * 0.5f, by - h * 0.32f)
        c.drawRoundRect(rect, wid * 0.05f, wid * 0.05f, aa)
        aa.color = light
        rect.set(cx - wid * 0.44f, by - h * 0.49f, cx + wid * 0.44f, by - h * 0.39f)
        c.drawRoundRect(rect, wid * 0.03f, wid * 0.03f, aa)
        // Plate
        aa.color = 0xFFE8E8F0.toInt()
        rect.set(cx - wid * 0.1f, by - h * 0.30f, cx + wid * 0.1f, by - h * 0.19f)
        c.drawRect(rect, aa)
        c.restore()
    }

    private fun drawHud(c: Canvas, w: Float, h: Float) {
        if (mode != Mode.PLAYING && mode != Mode.COUNTDOWN) return
        val u = w / 100f
        text.setShadowLayer(u * 1.2f, 0f, 0f, 0xFFFF2BD6.toInt())
        text.color = Color.WHITE
        text.textSize = u * 9f
        c.drawText(score.toInt().toString(), w / 2, h * 0.07f, text)
        text.textSize = u * 3.2f
        text.color = 0xFF9BF6FF.toInt()
        c.drawText("BEST ${max(best, score.toInt())}", w / 2, h * 0.07f + u * 5f, text)

        text.textAlign = Paint.Align.LEFT
        text.textSize = u * 4.5f
        text.color = Color.WHITE
        c.drawText("${(speed / MAX_SPEED * 240).toInt()} km/h", u * 4, h * 0.05f, text)
        text.textAlign = Paint.Align.RIGHT
        c.drawText("♥".repeat(max(0, lives)), w - u * 4, h * 0.05f, text)
        text.textAlign = Paint.Align.CENTER
        text.clearShadowLayer()

        if (popTime > 0f && popText != null) {
            text.color = Color.argb((popTime / 0.8f * 255).toInt().coerceIn(0, 255), 155, 246, 255)
            text.textSize = u * 7f
            c.drawText(popText!!, w / 2, h * 0.6f - (0.8f - popTime) * u * 20, text)
        }

        drawWheelBadge(c, u * 11, h * 0.86f, u * 7)
    }

    private fun drawWheelBadge(c: Canvas, cx: Float, cy: Float, r: Float) {
        val wheel = wheelMode() && !touching
        stroke.color = if (wheel) 0xFF2DE2FF.toInt() else 0x88FFFFFF.toInt()
        stroke.strokeWidth = r * 0.16f
        c.save()
        c.rotate(steerNow * 180f, cx, cy)
        c.drawCircle(cx, cy, r, stroke)
        c.drawLine(cx - r, cy, cx + r, cy, stroke)
        c.drawLine(cx, cy, cx, cy + r, stroke)
        aa.color = 0xFFFF2BD6.toInt()
        c.drawCircle(cx, cy - r, r * 0.18f, aa)
        c.restore()
        text.textSize = r * 0.45f
        text.color = 0xAAFFFFFF.toInt()
        c.drawText(if (wheel) "WHEEL" else "TOUCH", cx, cy + r * 1.75f, text)
    }

    private fun drawOverlay(c: Canvas, w: Float, h: Float) {
        val u = w / 100f
        val now = SystemClock.uptimeMillis()

        if (blocked) {
            panel(c, w, h, 0xE0000000.toInt())
            title(c, "PARK TO PLAY", w / 2, h * 0.45f, u * 11, 0xFFFF2BD6.toInt())
            line(c, "This only runs while the car is in P.", w / 2, h * 0.52f, u * 4.2f)
            return
        }

        when (mode) {
            Mode.INTRO -> {
                panel(c, w, h, 0x66000000)
                title(c, "NIGHT", w / 2, h * 0.18f, u * 20, 0xFFFF2BD6.toInt())
                title(c, "SHIFT", w / 2, h * 0.18f + u * 19, u * 20, 0xFF2DE2FF.toInt())
                if (car.hasSteer && !useTouch) {
                    line(c, "Your steering wheel is the controller.", w / 2, h * 0.60f, u * 4.6f)
                    line(c, if (wheelUsed) "Nice. Now tap to drive." else "Go on, turn it.", w / 2, h * 0.60f + u * 6.5f, u * 4.6f)
                    blink(c, if (steering.calibrated) "TAP TO START" else "TAP TO SET UP THE WHEEL", w / 2, h * 0.75f, u * 6f)
                    small(c, if (steering.calibrated) "Tap here to recalibrate the wheel" else "", w / 2, h * 0.93f, u * 3.4f)
                } else {
                    line(c, "Drag left and right to steer.", w / 2, h * 0.60f, u * 4.6f)
                    if (!car.hasSteer) line(c, "(No steering wheel signal found here.)", w / 2, h * 0.60f + u * 6.5f, u * 3.6f)
                    blink(c, "TAP TO START", w / 2, h * 0.75f, u * 6f)
                    if (car.hasSteer) small(c, "Tap here to use the steering wheel", w / 2, h * 0.93f, u * 3.4f)
                }
                small(c, "Dodge traffic. Grab the orbs. Stay on the road.", w / 2, h * 0.66f, u * 3.6f)
                if (best > 0) small(c, "BEST  $best", w / 2, h * 0.84f, u * 4f)
            }
            Mode.CAL_CENTER -> {
                panel(c, w, h, 0xCC0A0318.toInt())
                title(c, "WHEEL SETUP 1/2", w / 2, h * 0.25f, u * 8, 0xFF2DE2FF.toInt())
                line(c, "Point the steering wheel", w / 2, h * 0.40f, u * 5.5f)
                line(c, "straight ahead, then tap.", w / 2, h * 0.40f + u * 8, u * 5.5f)
                small(c, "reading: ${car.steerRaw?.let { fmt(it) } ?: "waiting…"}", w / 2, h * 0.58f, u * 3.6f)
                small(c, "Tap here to steer by touch instead", w / 2, h * 0.93f, u * 3.4f)
            }
            Mode.CAL_LEFT -> {
                panel(c, w, h, 0xCC0A0318.toInt())
                title(c, "WHEEL SETUP 2/2", w / 2, h * 0.25f, u * 8, 0xFF2DE2FF.toInt())
                line(c, "Turn the wheel LEFT about", w / 2, h * 0.40f, u * 5.5f)
                line(c, "half a turn, then tap.", w / 2, h * 0.40f + u * 8, u * 5.5f)
                val d = (car.steerRaw ?: calCenter) - calCenter
                small(c, "moved: ${fmt(d)}", w / 2, h * 0.58f, u * 3.6f)
                small(c, "Tap here to steer by touch instead", w / 2, h * 0.93f, u * 3.4f)
            }
            Mode.COUNTDOWN -> {
                val n = countdown.toInt()
                title(c, if (n >= 1) n.toString() else "GO", w / 2, h * 0.45f, u * 30, 0xFFFFE66D.toInt())
                if (wheelMode()) line(c, "Steer with the wheel", w / 2, h * 0.55f, u * 5f)
            }
            Mode.OVER -> {
                panel(c, w, h, 0xB0000000.toInt())
                title(c, "GAME OVER", w / 2, h * 0.30f, u * 13, 0xFFFF2BD6.toInt())
                title(c, score.toInt().toString(), w / 2, h * 0.42f, u * 16, Color.WHITE)
                line(c, if (score.toInt() >= best) "New best!" else "Best: $best", w / 2, h * 0.48f, u * 5f)
                line(c, "$orbsTotal orbs collected", w / 2, h * 0.53f, u * 4f)
                if (now - overAt > 1200) blink(c, "TAP TO DRIVE AGAIN", w / 2, h * 0.68f, u * 6f)
            }
            Mode.PLAYING -> {
                if (paused) {
                    panel(c, w, h, 0xB0000000.toInt())
                    title(c, "PAUSED", w / 2, h * 0.45f, u * 13, 0xFF2DE2FF.toInt())
                    blink(c, "TAP TO CONTINUE", w / 2, h * 0.55f, u * 6f)
                }
            }
        }

        val msg = message
        if (msg != null && now < messageUntil) {
            text.textSize = u * 4.6f
            val tw = text.measureText(msg)
            aa.color = 0xCC000000.toInt()
            rect.set(w / 2 - tw / 2 - u * 4, h * 0.79f - u * 7, w / 2 + tw / 2 + u * 4, h * 0.79f + u * 3)
            c.drawRoundRect(rect, u * 2, u * 2, aa)
            line(c, msg, w / 2, h * 0.79f, u * 4.6f)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun fmt(v: Float): String = if (abs(v) >= 100 || v == v.toInt().toFloat()) v.toInt().toString() else String.format("%.1f", v)

    private fun panel(c: Canvas, w: Float, h: Float, color: Int) {
        fill.color = color
        c.drawRect(0f, 0f, w, h, fill)
    }

    private fun title(c: Canvas, s: String, x: Float, y: Float, size: Float, glow: Int) {
        text.textSize = size
        text.color = Color.WHITE
        text.setShadowLayer(size * 0.18f, 0f, 0f, glow)
        c.drawText(s, x, y, text)
        text.color = glow and 0x00FFFFFF or 0x40000000
        c.drawText(s, x, y, text)
        text.clearShadowLayer()
    }

    private fun line(c: Canvas, s: String, x: Float, y: Float, size: Float) {
        text.textSize = size
        text.color = Color.WHITE
        c.drawText(s, x, y, text)
    }

    private fun small(c: Canvas, s: String, x: Float, y: Float, size: Float) {
        if (s.isEmpty()) return
        text.textSize = size
        text.color = 0xAA9BF6FF.toInt()
        c.drawText(s, x, y, text)
    }

    private fun blink(c: Canvas, s: String, x: Float, y: Float, size: Float) {
        val a = (170 + 85 * sin(SystemClock.uptimeMillis() / 250.0)).toInt().coerceIn(0, 255)
        text.textSize = size
        text.color = Color.argb(a, 255, 230, 109)
        c.drawText(s, x, y, text)
    }

    private fun quad(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, x4: Float, y4: Float, color: Int) {
        path.reset()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        path.lineTo(x3, y3)
        path.lineTo(x4, y4)
        path.close()
        fill.color = color
        c.drawPath(path, fill)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val p = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val x = (a ushr shift) and 0xFF
            val y = (b ushr shift) and 0xFF
            return (x + (y - x) * p).toInt() and 0xFF
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun darken(color: Int, f: Float): Int = mix(color, 0xFF000000.toInt(), 1f - f)

    companion object {
        const val DRAW_DIST = 160
        const val ROAD_W = 1100f
        const val CAM_H = 1000f
        const val CAM_DEPTH = 0.84f // 1 / tan(50 degrees)
        const val PLAYER_Z = CAM_H * CAM_DEPTH
        const val CAR_W = ROAD_W * 0.36f
        const val LANES = 3
        const val MAX_SPEED = Track.SEG * 60f
        const val STEER_RATE = 2.2f
        const val CENTRIFUGAL = 0.6f

        val FOG = 0xFF3A0D4A.toInt()
        val GRASS_LIGHT = 0xFF1E0A36.toInt()
        val GRASS_DARK = 0xFF140626.toInt()
        val RUMBLE_LIGHT = 0xFFFF2BD6.toInt()
        val RUMBLE_DARK = 0xFF2A0F3F.toInt()
        val ROAD_LIGHT = 0xFF26243A.toInt()
        val ROAD_DARK = 0xFF201E33.toInt()
        val LANE = 0xFF2DE2FF.toInt()
        val PLAYER_BODY = 0xFFFF2BD6.toInt()
        val PLAYER_LIGHT = 0xFF2DE2FF.toInt()
        val TAIL_RED = 0xFFFF2240.toInt()
        val TRAFFIC_COLORS = intArrayOf(
            0xFF6C5CE7.toInt(), 0xFF00B894.toInt(), 0xFFFDCB6E.toInt(), 0xFFE17055.toInt(),
            0xFF74B9FF.toInt(), 0xFFDFE6E9.toInt(), 0xFFA29BFE.toInt(),
        )
    }
}
