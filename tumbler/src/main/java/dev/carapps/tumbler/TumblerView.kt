package dev.carapps.tumbler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tumbler: the car's steering wheel becomes the dial of a safe. Turn it, listen to the
 * clicks get heavier as you near each number, and hold still on the heaviest click to
 * set the pin. Plays only in Park. Without a wheel signal, drag the dial with a finger.
 */
class TumblerView(
    context: Context,
    private val wheel: Wheel,
    private val sounds: Sounds,
) : View(context), Choreographer.FrameCallback, Heist.Feedback {

    private enum class Screen { TITLE, CAL_STRAIGHT, CAL_LEFT, PARTY_SETUP, BRIEF, PLAY, CRACKED, FAILED, PARTY_RESULTS }

    private class Btn(val r: RectF, val action: () -> Unit)
    private class Blip(val t: Float, val amp: Float, val heavy: Boolean)

    private val prefs = context.getSharedPreferences("tumbler", Context.MODE_PRIVATE)
    private val rnd = Random(SystemClock.uptimeMillis())

    private var screen = Screen.TITLE
    private var screenT = 0f
    private var clock = 0f
    private var running = false
    private var lastFrame = 0L

    /** Set by the activity: true while the car is known to be out of Park. */
    var blocked = false
        set(v) {
            field = v
            invalidate()
        }

    // Career
    private var level = prefs.getInt("level", 1)
    private var haul = prefs.getLong("haul", 0L)
    private val careerSeed: Long = prefs.getLong("seed", 0L).let {
        if (it != 0L) it else rnd.nextLong().also { s -> prefs.edit().putLong("seed", s).apply() }
    }

    // Current vault
    private var spec: VaultSpec = Vaults.make(level, careerSeed)
    private var heist: Heist? = null
    private var lastHaul = 0
    private var lastStars = 0

    // Input
    private var wheelSmooth = 0f
    private var wheelBase = 0f
    private var touchOffset = 0f
    private var dragging = false
    private var dragAngle = 0f
    private var downBtn: Btn? = null

    // Feedback
    private val scope = ArrayDeque<Blip>()
    private var shake = 0f
    private var glow = 0f
    private var toast: String? = null
    private var toastT = 0f

    // Calibration
    private var calStraight = 0f
    private var calMsg: String? = null
    private var afterCal: (() -> Unit)? = null

    // Party
    private var party = false
    private var partyPlayers = 0
    private var partyTurn = 0
    private var partySeed = 0L
    private var partyTimes = arrayOfNulls<Float>(0)

    private val buttons = ArrayList<Btn>()

    // Drawing
    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = INK }
    private val path = Path()
    private val rect = RectF()
    private var u = 10f
    private var bgShader: Shader? = null
    private var shaderKey = ""
    private var bezelShader: Shader? = null
    private var faceShader: Shader? = null
    private var knobShader: Shader? = null

    // ---------------------------------------------------------------- lifecycle

    fun resume() {
        running = true
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun pause() {
        running = false
        dragging = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastFrame == 0L) 0f else ((frameTimeNanos - lastFrame) / 1e9f).coerceIn(0f, 0.05f)
        lastFrame = frameTimeNanos
        step(dt)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** Steering-wheel or arrow key nudge. True if it was used. */
    fun nudge(step: Int): Boolean {
        if (blocked || (screen != Screen.PLAY && screen != Screen.TITLE)) return false
        touchOffset += step
        return true
    }

    private fun step(dt: Float) {
        clock += dt
        screenT += dt
        if (toast != null) {
            toastT -= dt
            if (toastT <= 0f) toast = null
        }
        val target = wheel.turns() * 100f
        wheelSmooth += (target - wheelSmooth) * min(1f, dt * 16f)
        shake = max(0f, shake - dt * 4f)
        glow = max(0f, glow - dt * 1.5f)
        while (scope.isNotEmpty() && clock - scope.first().t > SCOPE_SECONDS) scope.removeFirst()

        if (screen == Screen.PLAY && !blocked) heist?.update(dt, dialInput())
    }

    private fun dialInput(): Float = wheelSmooth - wheelBase + touchOffset

    private fun go(s: Screen) {
        screen = s
        screenT = 0f
        dragging = false
    }

    private fun say(msg: String) {
        toast = msg
        toastT = 2.6f
    }

    // ---------------------------------------------------------------- game flow

    private fun startCareer() {
        if (wheel.live && !wheel.calibrated) {
            afterCal = { startCareer() }
            go(Screen.CAL_STRAIGHT)
            return
        }
        party = false
        prepare(Vaults.make(level, careerSeed))
    }

    private fun startParty(players: Int) {
        party = true
        partyPlayers = players
        partyTurn = 0
        partySeed = rnd.nextLong()
        partyTimes = arrayOfNulls(players)
        prepare(Vaults.make(PARTY_LEVEL, partySeed))
    }

    private fun prepare(v: VaultSpec) {
        spec = v
        heist = null
        go(Screen.BRIEF)
    }

    private fun begin() {
        heist = Heist(spec, this)
        wheelBase = wheelSmooth
        touchOffset = 0f
        scope.clear()
        toast = null
        go(Screen.PLAY)
    }

    private fun afterRound() {
        if (!party) {
            go(Screen.TITLE)
            return
        }
        partyTurn++
        if (partyTurn >= partyPlayers) go(Screen.PARTY_RESULTS)
        else prepare(Vaults.make(PARTY_LEVEL, partySeed))
    }

    // Heist.Feedback

    override fun tick(strength: Float, heavy: Boolean) {
        sounds.tick(strength, heavy)
        scope.addLast(Blip(clock, 0.1f + 0.9f * strength, heavy))
        if (scope.size > 200) scope.removeFirst()
        if (heavy) shake = max(shake, 0.8f)
        glow = max(glow, strength)
    }

    override fun pinSet(index: Int) {
        sounds.clunk()
        shake = 1.6f
        val next = spec.pins.getOrNull(index + 1)
        say(if (next == null) "Last pin set!" else "Pin ${index + 1} set. Now turn ${dirWord(next.dir)}.")
    }

    override fun wrongWay() {
        sounds.rattle()
        val p = heist?.pin ?: return
        say("It rattles. This pin only sets when you arrive turning ${dirWord(p.dir)}.")
    }

    override fun jammed() {
        sounds.jam()
        shake = 2f
        say("Decoy! The lock jammed and the alarm jumped.")
    }

    override fun alarm() {
        sounds.alarm()
        if (party) partyTimes[partyTurn] = null
        go(Screen.FAILED)
    }

    override fun cracked() {
        val h = heist ?: return
        sounds.fanfare()
        lastStars = h.stars()
        lastHaul = h.haul()
        if (party) {
            partyTimes[partyTurn] = h.elapsed
        } else if (spec.number == level) {
            level++
            haul += lastHaul
            val key = "stars_${spec.number}"
            prefs.edit()
                .putInt("level", level)
                .putLong("haul", haul)
                .putInt(key, max(prefs.getInt(key, 0), lastStars))
                .apply()
        }
        go(Screen.CRACKED)
    }

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (blocked) return true
        val (cx, cy, r) = dialGeometry()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val canDrag = screen == Screen.PLAY || screen == Screen.TITLE
                downBtn = hit(e.x, e.y)
                if (downBtn == null && canDrag && hypot(e.x - cx, e.y - cy) < r * 1.25f) {
                    dragging = true
                    dragAngle = angle(e.x, e.y, cx, cy)
                }
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val a = angle(e.x, e.y, cx, cy)
                var d = a - dragAngle
                if (d > 180f) d -= 360f
                if (d < -180f) d += 360f
                touchOffset += d / 3.6f
                dragAngle = a
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    val b = hit(e.x, e.y)
                    if (b != null && b === downBtn) {
                        sounds.beep()
                        b.action()
                    }
                }
                dragging = false
                downBtn = null
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                downBtn = null
            }
        }
        return true
    }

    private fun hit(x: Float, y: Float): Btn? = buttons.lastOrNull { it.r.contains(x, y) }

    private fun angle(x: Float, y: Float, cx: Float, cy: Float): Float =
        Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()

    private data class Geo(val cx: Float, val cy: Float, val r: Float)

    private fun dialGeometry(): Geo {
        val w = width.toFloat()
        val h = height.toFloat()
        return when (screen) {
            Screen.TITLE -> Geo(w / 2f, h * 0.36f, min(w * 0.30f, h * 0.17f))
            else -> Geo(w / 2f, h * 0.43f, min(w * 0.38f, h * 0.23f))
        }
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        u = min(w, h * 0.62f) / 100f
        buttons.clear()

        if (bgShader == null || shaderKey != "$w:$h") {
            shaderKey = "$w:$h"
            bgShader = LinearGradient(0f, 0f, 0f, h, Color.rgb(20, 26, 32), Color.rgb(8, 10, 13), Shader.TileMode.CLAMP)
            bezelShader = null
        }
        fill.shader = bgShader
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        when (screen) {
            Screen.TITLE -> drawTitle(canvas, w, h)
            Screen.CAL_STRAIGHT, Screen.CAL_LEFT -> drawCalibration(canvas, w, h)
            Screen.PARTY_SETUP -> drawPartySetup(canvas, w, h)
            Screen.BRIEF -> drawBrief(canvas, w, h)
            Screen.PLAY -> drawPlay(canvas, w, h)
            Screen.CRACKED -> drawCracked(canvas, w, h)
            Screen.FAILED -> drawFailed(canvas, w, h)
            Screen.PARTY_RESULTS -> drawPartyResults(canvas, w, h)
        }

        if (blocked) drawBlocked(canvas, w, h)
    }

    private fun drawTitle(c: Canvas, w: Float, h: Float) {
        label(c, "TUMBLER", w / 2, h * 0.11f, 11f * u, GOLD, bold, spacing = 0.18f)
        paragraph(c, "Your steering wheel is the dial. Crack the safe by ear.", w / 2, h * 0.11f + 7f * u, w - 16f * u, 3.6f * u, DIM)

        val (cx, cy, r) = dialGeometry()
        drawDial(c, cx, cy, r, dialInput(), 0f, false)

        var y = cy + r * 1.25f + 5f * u
        val (msg, col) = when {
            wheel.live && wheel.calibrated -> "Steering wheel connected. Turn it and the dial follows." to GREEN
            wheel.live -> "Steering wheel found. It needs a quick calibration before the first vault." to GOLD
            wheel.connected -> "Waiting for the steering wheel. Switch the car on and turn the wheel a little. You can also drag the dial with a finger." to DIM
            else -> "No car data on this device. Drag the dial with a finger." to DIM
        }
        y = paragraph(c, msg, w / 2, y, w - 14f * u, 3.3f * u, col)
        y += 2f * u
        label(c, "Next: vault $level   ·   Total haul ${money(haul)}", w / 2, y + 3f * u, 3.6f * u, INK, bold)
        y += 9f * u

        val bw = min(w - 16f * u, 70f * u)
        y = button(c, "Crack vault $level", w / 2, y, bw, true) { startCareer() }
        y = button(c, "Pass the wheel  (2 to 4 players)", w / 2, y, bw, false) { go(Screen.PARTY_SETUP) }
        y = button(c, if (wheel.calibrated) "Recalibrate steering wheel" else "Calibrate steering wheel", w / 2, y, bw, false) {
            afterCal = null
            calMsg = null
            go(Screen.CAL_STRAIGHT)
        }
        button(c, if (sounds.muted) "Sound: off" else "Sound: on", w / 2, y, bw * 0.5f, false) { sounds.muted = !sounds.muted }
        label(c, "Park only. Best with the car switched on and the sound up.", w / 2, h - 4f * u, 2.8f * u, DIM)
    }

    private fun drawCalibration(c: Canvas, w: Float, h: Float) {
        val first = screen == Screen.CAL_STRAIGHT
        label(c, "Calibrate the wheel", w / 2, h * 0.12f, 7f * u, GOLD, bold)
        label(c, if (first) "Step 1 of 2" else "Step 2 of 2", w / 2, h * 0.12f + 6f * u, 3.4f * u, DIM)

        val cx = w / 2
        val cy = h * 0.40f
        val r = min(w * 0.26f, h * 0.15f)
        val raw = wheel.raw
        val shownTurn = if (first) 0f else -180f
        drawSteeringWheel(c, cx, cy, r, shownTurn)

        var y = cy + r + 8f * u
        val instruction = if (first) "Point the wheel straight ahead, like driving straight. Then tap Next."
        else "Now turn the wheel half a turn LEFT, so the top of the wheel points down, like the picture. Then tap Done."
        y = paragraph(c, instruction, w / 2, y, w - 16f * u, 4f * u, INK)
        y += 2f * u
        val reading = when {
            raw == null -> "No steering reading yet. Is the car switched on?"
            first -> "Sensor reading: ${fmt1(raw)}"
            else -> "Sensor reading: ${fmt1(raw)}   (moved ${fmt1(raw - calStraight)})"
        }
        y = paragraph(c, reading, w / 2, y, w - 16f * u, 3.3f * u, if (raw == null) RED else DIM)
        calMsg?.let { y = paragraph(c, it, w / 2, y + u, w - 16f * u, 3.3f * u, RED) }
        y += 4f * u

        val bw = min(w - 16f * u, 60f * u)
        y = button(c, if (first) "Next" else "Done", w / 2, y, bw, true) {
            val now = wheel.raw
            if (now == null) {
                calMsg = "There is no steering reading to measure. Switch the car on, or play with touch instead."
            } else if (first) {
                calStraight = now
                calMsg = null
                go(Screen.CAL_LEFT)
            } else if (wheel.calibrate(calStraight, now)) {
                calMsg = null
                say("Wheel calibrated.")
                val next = afterCal
                afterCal = null
                if (next != null) next() else go(Screen.TITLE)
            } else {
                calMsg = "The reading barely changed. Turn further, or check that the car is switched on."
            }
        }
        button(c, "Cancel", w / 2, y, bw, false) {
            calMsg = null
            afterCal = null
            go(Screen.TITLE)
        }
        drawToast(c, w, h * 0.22f)
    }

    private fun drawPartySetup(c: Canvas, w: Float, h: Float) {
        label(c, "Pass the wheel", w / 2, h * 0.14f, 8f * u, GOLD, bold)
        var y = paragraph(
            c,
            "Everyone cracks the same vault, one at a time. Fastest crack wins. Swap into the driver's seat for your turn, or drag the dial on the screen.",
            w / 2, h * 0.14f + 8f * u, w - 16f * u, 3.8f * u, INK,
        )
        y += 8f * u
        val bw = min(w - 16f * u, 60f * u)
        for (n in 2..4) y = button(c, "$n players", w / 2, y, bw, n == 2) { startParty(n) }
        y += 2f * u
        button(c, "Back", w / 2, y, bw, false) { go(Screen.TITLE) }
    }

    private fun drawBrief(c: Canvas, w: Float, h: Float) {
        val title = if (party) "PLAYER ${partyTurn + 1}" else "VAULT ${spec.number}"
        label(c, title, w / 2, h * 0.12f, 10f * u, GOLD, bold, spacing = 0.12f)
        val sub = if (party) "Take the wheel. Same vault for everyone." else "Inside: ???"
        label(c, sub, w / 2, h * 0.12f + 7f * u, 3.8f * u, DIM)

        var y = h * 0.12f + 15f * u
        val facts = "${spec.pins.size} pins   ·   alarm in ${clockText(spec.timeLimit)}"
        label(c, facts, w / 2, y, 4.2f * u, INK, bold)
        y += 6f * u

        // The combination's directions, as arrows.
        val n = spec.pins.size
        val gap = 13f * u
        val x0 = w / 2 - gap * (n - 1) / 2f
        for (i in 0 until n) {
            drawTurnArrow(c, x0 + gap * i, y + 4f * u, 4f * u, spec.pins[i].dir, INK)
        }
        y += 14f * u

        val lines = ArrayList<String>()
        lines += "How to crack it"
        lines += "1. Turn the way the arrow shows."
        lines += "2. Listen. The clicks get heavier as you near the secret number."
        lines += "3. On the heaviest click, stop and hold still until the bolt slides."
        lines += "The next pin turns the other way. Too slow and the alarm goes off."
        if (spec.pins.first().decoys.isNotEmpty()) {
            lines += ""
            lines += "Decoys: some heavy clicks come without the build-up. Holding one jams the lock and raises the alarm."
        }
        if (spec.backlash > 0f) {
            lines += ""
            lines += "Worn dial: it slips a little each time you change direction."
        }
        for ((i, l) in lines.withIndex()) {
            if (l.isEmpty()) {
                y += 2f * u
                continue
            }
            y = paragraph(c, l, w / 2, y, w - 16f * u, if (i == 0) 4.2f * u else 3.6f * u, if (i == 0) GOLD else INK)
            y += 1.2f * u
        }

        y = max(y + 4f * u, h * 0.72f)
        val bw = min(w - 16f * u, 60f * u)
        y = button(c, "Start", w / 2, y, bw, true) { begin() }
        button(c, if (party) "Quit game" else "Back", w / 2, y, bw, false) { go(Screen.TITLE) }
    }

    private fun drawPlay(c: Canvas, w: Float, h: Float) {
        val hs = heist ?: return
        val m = 6f * u

        // Header
        text.textAlign = Paint.Align.LEFT
        label(c, if (party) "PLAYER ${partyTurn + 1}" else "VAULT ${spec.number}", m, 7f * u, 4.6f * u, GOLD, bold, align = Paint.Align.LEFT)
        label(c, clockText(hs.elapsed), w - m, 7f * u, 4.6f * u, INK, mono, align = Paint.Align.RIGHT)

        // Alarm bar
        val by = 10f * u
        rect.set(m, by, w - m, by + 2.8f * u)
        fill.color = Color.rgb(40, 22, 24)
        c.drawRoundRect(rect, 1.4f * u, 1.4f * u, fill)
        val danger = hs.alarm > 0.75f && (clock * 4f).toInt() % 2 == 0
        fill.color = if (danger) Color.rgb(255, 120, 110) else RED
        rect.right = m + (w - 2 * m) * hs.alarm
        c.drawRoundRect(rect, 1.4f * u, 1.4f * u, fill)
        label(c, "ALARM", w / 2, by + 2.1f * u, 2.2f * u, Color.WHITE, bold, spacing = 0.3f)

        // Pins
        val n = spec.pins.size
        val slot = min(14f * u, (w - 2 * m) / n)
        val px0 = w / 2 - slot * (n - 1) / 2f
        val py = 21f * u
        for (i in 0 until n) {
            val x = px0 + slot * i
            val done = i < hs.pinIndex
            val current = i == hs.pinIndex
            rect.set(x - slot * 0.36f, py - 3.6f * u, x + slot * 0.36f, py + 3.6f * u)
            fill.color = when {
                done -> GOLD
                current -> Color.rgb(36, 44, 52)
                else -> Color.rgb(24, 30, 36)
            }
            c.drawRoundRect(rect, 1.2f * u, 1.2f * u, fill)
            if (current) {
                val pulse = 0.5f + 0.5f * sin(clock * 5f)
                stroke.color = withAlpha(GOLD, (120 + 135 * pulse).toInt())
                stroke.strokeWidth = 0.5f * u
                c.drawRoundRect(rect, 1.2f * u, 1.2f * u, stroke)
            }
            if (done) {
                stroke.color = Color.rgb(40, 30, 10)
                stroke.strokeWidth = 0.7f * u
                path.reset()
                path.moveTo(x - 1.8f * u, py)
                path.lineTo(x - 0.4f * u, py + 1.5f * u)
                path.lineTo(x + 2f * u, py - 1.6f * u)
                c.drawPath(path, stroke)
            } else {
                drawTurnArrow(c, x, py + 0.6f * u, 2.1f * u, spec.pins[i].dir, if (current) INK else DIM)
            }
        }

        // Dial
        val (cx, cy, r) = dialGeometry()
        c.save()
        if (shake > 0f) c.translate((rnd.nextFloat() - 0.5f) * shake * u, (rnd.nextFloat() - 0.5f) * shake * u)
        drawDial(c, cx, cy, r, hs.dial, hs.hold, hs.wrongWayNow)
        c.restore()

        // Readout and instruction
        var y = cy + r * 1.18f + 7f * u
        val num = mod100(hs.dial).roundToInt() % 100
        label(c, num.toString().padStart(2, '0'), w / 2, y, 8f * u, INK, mono)
        y += 6f * u
        val p = hs.pin
        if (p != null) {
            if (hs.wrongWayNow) {
                y = paragraph(c, "Wrong way. Back off and come in turning ${dirWord(p.dir)}.", w / 2, y, w - 2 * m, 3.8f * u, RED)
            } else {
                val arrowX = w / 2 - 15f * u
                drawTurnArrow(c, arrowX, y - 1.2f * u, 2.4f * u, p.dir, GOLD)
                label(c, "Turn ${dirWord(p.dir)}", w / 2 + 3f * u, y, 4.4f * u, GOLD, bold)
                y += 5f * u
                y = paragraph(c, "Listen for the heaviest click, then hold still.", w / 2, y, w - 2 * m, 3.3f * u, DIM)
            }
        }

        // Stethoscope
        val sh = 13f * u
        val sy = max(y + 3f * u, h - sh - 20f * u)
        drawScope(c, m, sy, w - 2 * m, sh)

        // Footer
        val src = when {
            wheel.live && wheel.calibrated -> "Steering wheel"
            wheel.live -> "Wheel not calibrated. Drag the dial"
            else -> "Touch: drag the dial"
        }
        label(c, src, w / 2, sy + sh + 4.5f * u, 2.8f * u, DIM)
        val bw = (w - 3 * m) / 2
        val fy = h - 12f * u
        button(c, "Give up", m + bw / 2, fy, bw, false) {
            if (party) {
                partyTimes[partyTurn] = null
                afterRound()
            } else {
                go(Screen.TITLE)
            }
        }
        button(c, if (sounds.muted) "Sound: off" else "Sound: on", w - m - bw / 2, fy, bw, false) { sounds.muted = !sounds.muted }

        drawToast(c, w, cy - r * 1.18f - 2f * u)
    }

    private fun drawCracked(c: Canvas, w: Float, h: Float) {
        val hs = heist
        val (cx, cy, r) = dialGeometry()
        val t = screenT

        // The door swings open, revealing the glow inside.
        val open = ((t - 0.3f) / 1.1f).coerceIn(0f, 1f)
        fill.shader = RadialGradient(cx, cy, r * 1.4f, Color.rgb(255, 214, 120), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        fill.alpha = (255 * open).toInt()
        c.drawCircle(cx, cy, r * 1.4f, fill)
        fill.shader = null
        fill.alpha = 255
        if (open < 1f) {
            c.save()
            c.translate(cx - r * 1.2f, 0f)
            c.scale(1f - open, 1f)
            c.translate(-(cx - r * 1.2f), 0f)
            rect.set(cx - r * 1.2f, cy - r * 1.2f, cx + r * 1.2f, cy + r * 1.2f)
            fill.color = Color.rgb(48, 56, 64)
            c.drawRoundRect(rect, 2f * u, 2f * u, fill)
            drawDial(c, cx, cy, r, hs?.dial ?: 0f, 0f, false)
            c.restore()
        } else {
            drawGem(c, cx, cy, r * 0.5f, t)
        }

        label(c, "CRACKED!", w / 2, h * 0.10f, 10f * u, GOLD, bold, spacing = 0.12f)
        if (t < 1.2f) return

        var y = cy + r * 1.3f + 6f * u
        val who = if (party) "Player ${partyTurn + 1}" else "Vault ${spec.number}"
        label(c, "$who in ${clockText(hs?.elapsed ?: 0f)}", w / 2, y, 5f * u, INK, bold)
        y += 7f * u
        val sx = w / 2 - 8f * u
        for (i in 0 until 3) drawStar(c, sx + i * 8f * u, y, 3f * u, i < lastStars)
        y += 8f * u
        y = paragraph(c, "Inside: ${spec.loot.name}", w / 2, y, w - 16f * u, 4f * u, INK)
        if (!party) {
            y += u
            label(c, "+${money(lastHaul.toLong())}   ·   Total haul ${money(haul)}", w / 2, y + 3f * u, 3.6f * u, GOLD, bold)
            y += 6f * u
        }
        y = max(y + 4f * u, h * 0.78f)
        val bw = min(w - 16f * u, 60f * u)
        if (party) {
            val last = partyTurn + 1 >= partyPlayers
            y = button(c, if (last) "See results" else "Next player", w / 2, y, bw, true) { afterRound() }
        } else {
            y = button(c, "Crack vault $level", w / 2, y, bw, true) { startCareer() }
            button(c, "Menu", w / 2, y, bw, false) { go(Screen.TITLE) }
        }
    }

    private fun drawFailed(c: Canvas, w: Float, h: Float) {
        val flash = screenT < 2f && (screenT * 6f).toInt() % 2 == 0
        if (flash) {
            fill.color = Color.argb(70, 255, 40, 40)
            c.drawRect(0f, 0f, w, h, fill)
        }
        val (cx, cy, r) = dialGeometry()
        drawDial(c, cx, cy, r, heist?.dial ?: 0f, 0f, false)
        label(c, "ALARM!", w / 2, h * 0.10f, 11f * u, RED, bold, spacing = 0.15f)
        var y = cy + r * 1.3f + 6f * u
        val msg = if (party) "Player ${partyTurn + 1} set off the alarm. No time counted."
        else "The guards heard you. Vault ${spec.number} stays shut, for now."
        y = paragraph(c, msg, w / 2, y, w - 16f * u, 4f * u, INK)
        y = max(y + 6f * u, h * 0.78f)
        val bw = min(w - 16f * u, 60f * u)
        if (party) {
            val last = partyTurn + 1 >= partyPlayers
            button(c, if (last) "See results" else "Next player", w / 2, y, bw, true) { afterRound() }
        } else {
            y = button(c, "Try again", w / 2, y, bw, true) { prepare(spec) }
            button(c, "Menu", w / 2, y, bw, false) { go(Screen.TITLE) }
        }
    }

    private fun drawPartyResults(c: Canvas, w: Float, h: Float) {
        label(c, "Results", w / 2, h * 0.12f, 9f * u, GOLD, bold)
        val order = partyTimes.indices.sortedWith(compareBy<Int>({ partyTimes[it] == null }, { partyTimes[it] ?: 0f }))
        var y = h * 0.12f + 14f * u
        for ((rank, i) in order.withIndex()) {
            val time = partyTimes[i]
            val winner = rank == 0 && time != null
            rect.set(10f * u, y - 5.5f * u, w - 10f * u, y + 3f * u)
            fill.color = if (winner) Color.rgb(60, 48, 20) else Color.rgb(26, 32, 38)
            c.drawRoundRect(rect, 2f * u, 2f * u, fill)
            label(c, "${rank + 1}.  Player ${i + 1}", 14f * u, y, 4.4f * u, if (winner) GOLD else INK, bold, align = Paint.Align.LEFT)
            label(c, time?.let { clockText(it) } ?: "alarm", w - 14f * u, y, 4.4f * u, if (time == null) RED else INK, mono, align = Paint.Align.RIGHT)
            y += 11f * u
        }
        if (partyTimes.all { it == null }) {
            y = paragraph(c, "Nobody cracked it. The vault wins this round.", w / 2, y, w - 16f * u, 3.8f * u, DIM)
        }
        y = max(y + 6f * u, h * 0.72f)
        val bw = min(w - 16f * u, 60f * u)
        y = button(c, "Play again (new vault)", w / 2, y, bw, true) { startParty(partyPlayers) }
        button(c, "Menu", w / 2, y, bw, false) { go(Screen.TITLE) }
    }

    private fun drawBlocked(c: Canvas, w: Float, h: Float) {
        buttons.clear()
        fill.color = Color.argb(235, 6, 8, 10)
        c.drawRect(0f, 0f, w, h, fill)
        label(c, "P", w / 2, h * 0.42f, 22f * u, GOLD, bold)
        label(c, "Shift to Park to keep cracking", w / 2, h * 0.42f + 12f * u, 4.6f * u, INK, bold)
        label(c, "Tumbler only plays while the car is parked.", w / 2, h * 0.42f + 19f * u, 3.4f * u, DIM)
    }

    // ---------------------------------------------------------------- pieces

    private fun drawDial(c: Canvas, cx: Float, cy: Float, r: Float, value: Float, hold: Float, wrong: Boolean) {
        val key = "$cx:$cy:$r"
        if (bezelShader == null || dialKey != key) {
            dialKey = key
            bezelShader = SweepGradient(
                cx, cy,
                intArrayOf(
                    Color.rgb(150, 160, 170), Color.rgb(70, 78, 88), Color.rgb(190, 198, 206),
                    Color.rgb(60, 66, 74), Color.rgb(150, 160, 170),
                ),
                null,
            )
            faceShader = RadialGradient(cx, cy - r * 0.3f, r * 1.1f, Color.rgb(44, 52, 60), Color.rgb(16, 20, 24), Shader.TileMode.CLAMP)
            knobShader = RadialGradient(cx - r * 0.1f, cy - r * 0.15f, r * 0.45f, Color.rgb(255, 222, 140), Color.rgb(150, 100, 30), Shader.TileMode.CLAMP)
        }

        // Shadow and bezel
        fill.color = Color.argb(120, 0, 0, 0)
        c.drawCircle(cx, cy + r * 0.04f, r * 1.12f, fill)
        fill.shader = bezelShader
        c.drawCircle(cx, cy, r * 1.08f, fill)
        fill.shader = faceShader
        c.drawCircle(cx, cy, r * 0.9f, fill)
        fill.shader = null

        if (glow > 0.05f) {
            stroke.color = withAlpha(GOLD, (glow * 160).toInt())
            stroke.strokeWidth = 0.9f * u
            c.drawCircle(cx, cy, r * 0.92f, stroke)
        }

        // Rotating face
        val rf = r * 0.86f
        c.save()
        c.rotate(value * 3.6f, cx, cy)
        for (k in 0 until 100) {
            val a = Math.toRadians((-90.0 - k * 3.6))
            val inner = when {
                k % 10 == 0 -> 0.78f
                k % 5 == 0 -> 0.84f
                else -> 0.88f
            }
            stroke.color = if (k % 5 == 0) INK else DIM
            stroke.strokeWidth = if (k % 10 == 0) 0.55f * u else 0.3f * u
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            c.drawLine(cx + ca * rf * inner, cy + sa * rf * inner, cx + ca * rf * 0.97f, cy + sa * rf * 0.97f, stroke)
        }
        text.typeface = bold
        text.textSize = r * 0.1f
        text.color = INK
        text.textAlign = Paint.Align.CENTER
        text.letterSpacing = 0f
        for (k in 0 until 100 step 10) {
            c.save()
            c.rotate(-k * 3.6f, cx, cy)
            c.drawText(k.toString(), cx, cy - rf * 0.62f, text)
            c.restore()
        }
        // Knob with grip ridges
        fill.shader = knobShader
        c.drawCircle(cx, cy, r * 0.4f, fill)
        fill.shader = null
        stroke.color = Color.rgb(120, 82, 24)
        stroke.strokeWidth = 0.35f * u
        for (k in 0 until 36) {
            val a = Math.toRadians(k * 10.0)
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            c.drawLine(cx + ca * r * 0.34f, cy + sa * r * 0.34f, cx + ca * r * 0.4f, cy + sa * r * 0.4f, stroke)
        }
        fill.color = Color.rgb(110, 74, 20)
        rect.set(cx - r * 0.035f, cy - r * 0.3f, cx + r * 0.035f, cy + r * 0.3f)
        c.drawRoundRect(rect, r * 0.03f, r * 0.03f, fill)
        c.restore()

        // Fixed index mark
        path.reset()
        path.moveTo(cx, cy - r * 0.93f)
        path.lineTo(cx - r * 0.07f, cy - r * 1.12f)
        path.lineTo(cx + r * 0.07f, cy - r * 1.12f)
        path.close()
        fill.color = RED
        c.drawPath(path, fill)

        // Hold progress
        if (hold > 0f || wrong) {
            rect.set(cx - r * 1.17f, cy - r * 1.17f, cx + r * 1.17f, cy + r * 1.17f)
            stroke.strokeWidth = 1.3f * u
            if (wrong) {
                stroke.color = withAlpha(RED, 200)
                c.drawArc(rect, -90f, 360f, false, stroke)
            } else {
                stroke.color = GOLD
                c.drawArc(rect, -90f, 360f * hold.coerceIn(0f, 1f), false, stroke)
            }
        }
    }

    private var dialKey = ""

    private fun drawScope(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        rect.set(x, y, x + w, y + h)
        fill.color = Color.rgb(8, 18, 14)
        c.drawRoundRect(rect, 1.5f * u, 1.5f * u, fill)
        stroke.color = Color.rgb(24, 60, 44)
        stroke.strokeWidth = 0.2f * u
        val mid = y + h / 2
        c.drawLine(x + u, mid, x + w - u, mid, stroke)
        label(c, "STETHOSCOPE", x + 2f * u, y + 2.8f * u, 2f * u, Color.rgb(60, 140, 100), bold, align = Paint.Align.LEFT)

        c.save()
        c.clipRect(rect)
        for (b in scope) {
            val age = clock - b.t
            val px = x + w - 2f * u - age / SCOPE_SECONDS * (w - 4f * u)
            val amp = b.amp * (h * 0.45f) * (1f - age / SCOPE_SECONDS * 0.5f)
            stroke.color = if (b.heavy) GOLD else GREEN
            stroke.strokeWidth = if (b.heavy) 0.7f * u else 0.4f * u
            c.drawLine(px, mid - amp, px, mid + amp, stroke)
        }
        c.restore()
    }

    private fun drawSteeringWheel(c: Canvas, cx: Float, cy: Float, r: Float, degrees: Float) {
        c.save()
        c.rotate(degrees, cx, cy)
        stroke.color = Color.rgb(170, 178, 186)
        stroke.strokeWidth = r * 0.14f
        c.drawCircle(cx, cy, r, stroke)
        stroke.strokeWidth = r * 0.12f
        c.drawLine(cx - r, cy, cx + r, cy, stroke)
        c.drawLine(cx, cy, cx, cy + r, stroke)
        fill.color = Color.rgb(60, 66, 74)
        c.drawCircle(cx, cy, r * 0.26f, fill)
        fill.color = GOLD
        c.drawCircle(cx, cy - r, r * 0.09f, fill) // marks the top of the wheel
        c.restore()
    }

    private fun drawTurnArrow(c: Canvas, cx: Float, cy: Float, r: Float, dir: Int, color: Int) {
        stroke.color = color
        stroke.strokeWidth = r * 0.22f
        rect.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(rect, 200f, 140f, false, stroke)
        val endDeg = if (dir > 0) 340.0 else 200.0
        val a = Math.toRadians(endDeg)
        val px = cx + r * cos(a).toFloat()
        val py = cy + r * sin(a).toFloat()
        // Tangent pointing the way of travel (clockwise for right).
        val tx = (if (dir > 0) -sin(a) else sin(a)).toFloat()
        val ty = (if (dir > 0) cos(a) else -cos(a)).toFloat()
        val nx = cos(a).toFloat()
        val ny = sin(a).toFloat()
        val hs = r * 0.5f
        path.reset()
        path.moveTo(px + tx * hs, py + ty * hs)
        path.lineTo(px + nx * hs * 0.7f - tx * hs * 0.2f, py + ny * hs * 0.7f - ty * hs * 0.2f)
        path.lineTo(px - nx * hs * 0.7f - tx * hs * 0.2f, py - ny * hs * 0.7f - ty * hs * 0.2f)
        path.close()
        fill.color = color
        c.drawPath(path, fill)
    }

    private fun drawStar(c: Canvas, cx: Float, cy: Float, r: Float, on: Boolean) {
        path.reset()
        for (i in 0 until 10) {
            val rr = if (i % 2 == 0) r else r * 0.45f
            val a = Math.toRadians(-90.0 + i * 36.0)
            val x = cx + rr * cos(a).toFloat()
            val y = cy + rr * sin(a).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        if (on) {
            fill.color = GOLD
            c.drawPath(path, fill)
        } else {
            stroke.color = DIM
            stroke.strokeWidth = 0.4f * u
            c.drawPath(path, stroke)
        }
    }

    private fun drawGem(c: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        val bob = sin(t * 2f) * r * 0.06f
        path.reset()
        path.moveTo(cx, cy - r + bob)
        path.lineTo(cx + r * 0.8f, cy - r * 0.2f + bob)
        path.lineTo(cx, cy + r + bob)
        path.lineTo(cx - r * 0.8f, cy - r * 0.2f + bob)
        path.close()
        fill.color = Color.rgb(255, 210, 110)
        c.drawPath(path, fill)
        stroke.color = Color.rgb(150, 100, 30)
        stroke.strokeWidth = 0.4f * u
        c.drawPath(path, stroke)
        c.drawLine(cx - r * 0.8f, cy - r * 0.2f + bob, cx + r * 0.8f, cy - r * 0.2f + bob, stroke)
        c.drawLine(cx, cy - r + bob, cx, cy + r + bob, stroke)
        // Sparkles
        for (i in 0 until 6) {
            val a = t * 0.8f + i * 1.047f
            val d = r * (1.3f + 0.2f * sin(t * 3f + i))
            val s = (0.5f + 0.5f * sin(t * 4f + i * 2f)) * 0.9f * u
            fill.color = Color.WHITE
            c.drawCircle(cx + cos(a) * d, cy + sin(a) * d, s, fill)
        }
    }

    private fun drawToast(c: Canvas, w: Float, y: Float) {
        val msg = toast ?: return
        text.typeface = bold
        text.textSize = 3.4f * u
        text.letterSpacing = 0f
        val lines = wrap(msg, w - 20f * u)
        val lh = 4.4f * u
        val boxH = lines.size * lh + 2.6f * u
        val maxW = lines.maxOf { text.measureText(it) }
        rect.set(w / 2 - maxW / 2 - 3f * u, y - boxH, w / 2 + maxW / 2 + 3f * u, y)
        fill.color = Color.argb(230, 34, 40, 48)
        c.drawRoundRect(rect, 2f * u, 2f * u, fill)
        var ty = y - boxH + 1.3f * u + lh * 0.8f
        text.color = INK
        text.textAlign = Paint.Align.CENTER
        for (l in lines) {
            c.drawText(l, w / 2, ty, text)
            ty += lh
        }
    }

    /** Draws a button whose top edge is at [top]; returns the y for the next one. */
    private fun button(c: Canvas, label: String, cx: Float, top: Float, width: Float, primary: Boolean, action: () -> Unit): Float {
        val h = 9f * u
        val r = RectF(cx - width / 2, top, cx + width / 2, top + h)
        if (primary) {
            fill.color = GOLD
            c.drawRoundRect(r, h / 2, h / 2, fill)
        } else {
            fill.color = Color.rgb(28, 35, 42)
            c.drawRoundRect(r, h / 2, h / 2, fill)
            stroke.color = Color.rgb(70, 82, 94)
            stroke.strokeWidth = 0.25f * u
            c.drawRoundRect(r, h / 2, h / 2, stroke)
        }
        label(c, label, cx, top + h * 0.63f, 3.6f * u, if (primary) Color.rgb(30, 22, 8) else INK, bold)
        buttons.add(Btn(r, action))
        return top + h + 2.5f * u
    }

    private fun label(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, face: Typeface = Typeface.DEFAULT,
        align: Paint.Align = Paint.Align.CENTER, spacing: Float = 0f,
    ) {
        text.typeface = face
        text.textSize = size
        text.color = color
        text.textAlign = align
        text.letterSpacing = spacing
        c.drawText(s, x, y, text)
        text.letterSpacing = 0f
        text.textAlign = Paint.Align.CENTER
    }

    /** Centered, word-wrapped text starting at baseline-ish [y]. Returns the y below it. */
    private fun paragraph(c: Canvas, s: String, cx: Float, y: Float, maxW: Float, size: Float, color: Int): Float {
        text.typeface = Typeface.DEFAULT
        text.textSize = size
        text.color = color
        text.textAlign = Paint.Align.CENTER
        text.letterSpacing = 0f
        var ty = y + size
        for (l in wrap(s, maxW)) {
            c.drawText(l, cx, ty, text)
            ty += size * 1.35f
        }
        return ty - size * 0.35f
    }

    private fun wrap(s: String, maxW: Float): List<String> {
        val out = ArrayList<String>()
        for (para in s.split("\n")) {
            var line = ""
            for (word in para.split(" ")) {
                val tryLine = if (line.isEmpty()) word else "$line $word"
                if (text.measureText(tryLine) <= maxW || line.isEmpty()) line = tryLine
                else {
                    out.add(line)
                    line = word
                }
            }
            out.add(line)
        }
        return out
    }

    private fun withAlpha(color: Int, a: Int): Int = Color.argb(a.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun dirWord(dir: Int) = if (dir > 0) "RIGHT" else "LEFT"

    private fun clockText(s: Float): String {
        val t = max(0, s.toInt())
        return "%d:%02d".format(t / 60, t % 60)
    }

    private fun fmt1(f: Float) = "%.1f".format(f)

    private fun money(v: Long): String = "$" + "%,d".format(v)

    companion object {
        private const val PARTY_LEVEL = 4
        private const val SCOPE_SECONDS = 2.5f

        private val GOLD = Color.rgb(242, 184, 75)
        private val RED = Color.rgb(255, 77, 77)
        private val GREEN = Color.rgb(75, 224, 138)
        private val INK = Color.rgb(232, 236, 239)
        private val DIM = Color.rgb(138, 150, 163)
    }
}
