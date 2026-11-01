package dev.carapps.hunch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import dev.carapps.gamekit.Buttons
import dev.carapps.gamekit.Palette
import dev.carapps.gamekit.Pausable
import dev.carapps.gamekit.Ui
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Hunch: a pass-the-screen guessing game for 2 to 6 players.
 * Each round everyone makes a guess in turn; the closest guess scores.
 */
class HunchView(context: Context) : View(context), Pausable {

    private enum class Phase { MENU, INTRO, FLASH, HANDOFF, INPUT, TIMING, REVEAL, FINAL }

    private enum class Kind(val title: String, val blurb: String) {
        CLOCK("Blind Clock", "Start a clock, watch it vanish, and stop it when you think the time is up."),
        DOTS("Dot Flash", "A cloud of dots shows for a moment and a half. Then everyone guesses how many there were."),
        ANGLE("Angle Eye", "Two lines meet at an angle. Guess how many degrees it is."),
        FACT("Close Call", "A question with a number for an answer. Nobody has to know it, just get closest."),
    }

    private class Round(val kind: Kind, val answer: Double, val prompt: String, val unit: String, val tolerance: Double) {
        /** Dots as x, y, radius, all as fractions of the dot box. */
        var dots: List<FloatArray> = emptyList()
        var angle = 0f
        var turn = 0f
    }

    private val ui = Ui()
    private val buttons = Buttons()
    private val rng = Random()
    private val r = RectF()

    private var phase = Phase.MENU
    private var phaseAt = 0L
    private var paused = false

    private var players = 3
    private var totalRounds = 8
    private val scores = IntArray(6)

    private var roundNo = 0
    private val kinds = ArrayList<Kind>()
    private val factBag = ArrayList<Fact>()
    private var round = Round(Kind.CLOCK, 5.0, "", "s", 0.05)
    private val guesses = DoubleArray(6)
    private val roundPts = IntArray(6)
    private val order = IntArray(6)
    private var turnIdx = 0
    private var entry = ""
    private var timerStart = 0L
    private var quitArmedAt = 0L

    private val current get() = order[turnIdx]

    // ---- flow ----

    private fun go(p: Phase) {
        phase = p
        phaseAt = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun startGame() {
        scores.fill(0)
        roundNo = 0
        kinds.clear()
        nextRound()
    }

    private fun nextRound() {
        if (kinds.isEmpty()) {
            val block = Kind.entries.shuffled(rng).toMutableList()
            if (block[0] == round.kind && roundNo > 0) block.add(block.removeAt(0))
            kinds.addAll(block)
        }
        round = makeRound(kinds.removeAt(0))
        roundNo++
        for (i in 0 until players) order[i] = (i + roundNo - 1) % players
        guesses.fill(Double.NaN)
        roundPts.fill(0)
        go(Phase.INTRO)
    }

    private fun makeRound(kind: Kind): Round = when (kind) {
        Kind.CLOCK -> {
            val secs = 3 + rng.nextInt(7)
            Round(kind, secs.toDouble(), "Stop the hidden clock at exactly $secs seconds", "s", 0.05)
        }
        Kind.DOTS -> {
            val dots = makeDots(15 + rng.nextInt(66))
            Round(kind, dots.size.toDouble(), "How many dots were there?", "", 0.0).also { it.dots = dots }
        }
        Kind.ANGLE -> {
            val deg = 8 + rng.nextInt(165)
            Round(kind, deg.toDouble(), "How many degrees is this angle?", "°", 1.0).also {
                it.angle = deg.toFloat()
                it.turn = rng.nextInt(360).toFloat()
            }
        }
        Kind.FACT -> {
            if (factBag.isEmpty()) factBag.addAll(Facts.all.shuffled(rng))
            val f = factBag.removeAt(0)
            Round(kind, f.answer.toDouble(), f.question, f.unit, f.answer * 0.01)
        }
    }

    private fun makeDots(n: Int): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        var tries = 0
        while (out.size < n && tries < 6000) {
            tries++
            val rad = 0.012f + rng.nextFloat() * 0.014f
            val x = rad + rng.nextFloat() * (1 - 2 * rad)
            val y = rad + rng.nextFloat() * (1 - 2 * rad)
            val clear = out.none { d ->
                val dx = d[0] - x
                val dy = d[1] - y
                dx * dx + dy * dy < (d[2] + rad + 0.01f) * (d[2] + rad + 0.01f)
            }
            if (clear) out.add(floatArrayOf(x, y, rad))
        }
        return out
    }

    private fun startTurns() {
        turnIdx = 0
        go(Phase.HANDOFF)
    }

    private fun submit(value: Double) {
        guesses[current] = value
        turnIdx++
        if (turnIdx < players) go(Phase.HANDOFF) else reveal()
    }

    private fun reveal() {
        val errs = (0 until players).map { abs(guesses[it] - round.answer) }
        val best = errs.minOrNull() ?: 0.0
        for (p in 0 until players) {
            var pts = 0
            if (errs[p] <= best + 1e-9) pts += 2
            if (errs[p] <= round.tolerance + 1e-9) pts += 1
            roundPts[p] = pts
            scores[p] += pts
        }
        go(Phase.REVEAL)
    }

    override fun setPaused(paused: Boolean) {
        this.paused = paused
        if (paused) {
            // Timed moments can't survive a pause; replay them.
            if (phase == Phase.TIMING) go(Phase.INPUT)
            if (phase == Phase.FLASH) go(Phase.INTRO)
        } else {
            invalidate()
        }
    }

    // ---- drawing ----

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (phase == Phase.FLASH && now - phaseAt > FLASH_MS) startTurns()
        buttons.clear()
        c.drawColor(Palette.BG)
        val u = min(width, height) / 100f
        when (phase) {
            Phase.MENU -> drawMenu(c, u)
            Phase.INTRO -> drawIntro(c, u)
            Phase.FLASH -> drawFlash(c, u, now)
            Phase.HANDOFF -> drawHandoff(c, u)
            Phase.INPUT -> drawInput(c, u)
            Phase.TIMING -> drawTiming(c, u, now)
            Phase.REVEAL -> drawReveal(c, u)
            Phase.FINAL -> drawFinal(c, u, now)
        }
        if (phase != Phase.MENU && phase != Phase.FINAL && phase != Phase.TIMING && phase != Phase.FLASH) drawTopBar(c, u, now)
        if (!paused && (phase == Phase.FLASH || phase == Phase.TIMING || phase == Phase.HANDOFF || phase == Phase.FINAL || now - quitArmedAt < 3000)) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawTopBar(c: Canvas, u: Float, now: Long) {
        val armed = now - quitArmedAt < 2500
        r.set(u * 3, u * 3, u * (if (armed) 40 else 18), u * 10)
        ui.button(c, r, if (armed) "Tap again to quit" else "Menu", Palette.PANEL_HI, Palette.TEXT, u * 3.2f)
        buttons.add("quit", r) {
            val t = SystemClock.uptimeMillis()
            if (t - quitArmedAt < 2500) go(Phase.MENU) else quitArmedAt = t
            invalidate()
        }
        ui.text(c, "Round $roundNo of $totalRounds", width - u * 4, u * 6.5f, u * 3.4f, Palette.MUTED, align = Paint.Align.RIGHT)
    }

    private fun drawMenu(c: Canvas, u: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        var y = h * 0.12f
        ui.text(c, "HUNCH", cx, y, u * 13, Palette.GOLD, bold = true)
        y += u * 10
        ui.text(c, "A guessing game for 2 to 6 players", cx, y, u * 4.2f, Palette.MUTED)

        y += u * 16
        ui.text(c, "Players", cx, y, u * 4.5f, Palette.TEXT, bold = true)
        y += u * 9
        chips(c, u, y, listOf(2, 3, 4, 5, 6), players, "p") { players = it }

        y += u * 15
        ui.text(c, "Rounds", cx, y, u * 4.5f, Palette.TEXT, bold = true)
        y += u * 9
        chips(c, u, y, listOf(4, 8, 12), totalRounds, "r") { totalRounds = it }

        y += u * 16
        r.set(cx - u * 30, y - u * 7, cx + u * 30, y + u * 7)
        ui.button(c, r, "START", Palette.ACCENT, Palette.TEXT, u * 6, buttons.isPressed("start"))
        buttons.add("start", r) { startGame() }

        y += u * 18
        ui.wrap(
            c,
            "Take turns guessing on the same screen while the others look away. " +
                "Closest guess each round scores 2, and a near-perfect guess earns 1 more.",
            cx, y, w * 0.84f, u * 3.8f, Palette.MUTED,
        )
        y += u * 18
        for (k in Kind.entries) {
            ui.text(c, k.title, cx, y, u * 3.8f, Palette.TEXT, bold = true)
            y += u * 6
        }
    }

    private fun chips(c: Canvas, u: Float, y: Float, values: List<Int>, selected: Int, key: String, pick: (Int) -> Unit) {
        val cw = u * 13
        val gap = u * 3
        val total = values.size * cw + (values.size - 1) * gap
        var x = width / 2f - total / 2
        for (v in values) {
            r.set(x, y - u * 6, x + cw, y + u * 6)
            val on = v == selected
            ui.button(c, r, v.toString(), if (on) Palette.TEXT else Palette.PANEL_HI, if (on) Palette.BG else Palette.TEXT, u * 5.5f, buttons.isPressed("$key$v"))
            buttons.add("$key$v", r) { pick(v); invalidate() }
            x += cw + gap
        }
    }

    private fun drawIntro(c: Canvas, u: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        var y = h * 0.2f
        ui.text(c, round.kind.title.uppercase(), cx, y, u * 10, Palette.GOLD, bold = true)
        y += u * 12
        y += ui.wrap(c, round.kind.blurb, cx, y, w * 0.84f, u * 4.4f, Palette.TEXT)
        y += u * 6
        if (round.kind != Kind.DOTS) ui.wrap(c, round.prompt, cx, y, w * 0.84f, u * 5f, Palette.GOLD, bold = true)

        val label = if (round.kind == Kind.DOTS) "Everyone watch: show the dots" else "Start guessing"
        if (round.kind == Kind.DOTS) ui.text(c, "Everyone look now. They only show once.", cx, h * 0.62f, u * 4f, Palette.MUTED)
        r.set(w * 0.1f, h * 0.68f, w * 0.9f, h * 0.68f + u * 15)
        ui.button(c, r, label, Palette.ACCENT, Palette.TEXT, u * 5, buttons.isPressed("go"))
        buttons.add("go", r) { if (round.kind == Kind.DOTS) go(Phase.FLASH) else startTurns() }
    }

    private fun dotBox(u: Float): RectF {
        val side = min(width * 0.92f, height * 0.6f)
        val cx = width / 2f
        val cy = height * 0.48f
        return RectF(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
    }

    private fun drawFlash(c: Canvas, u: Float, now: Long) {
        val box = dotBox(u)
        ui.round(c, box, u * 3, Palette.PANEL)
        for (d in round.dots) ui.circle(c, box.left + d[0] * box.width(), box.top + d[1] * box.height(), d[2] * box.width(), Palette.TEXT)
        val left = 1f - (now - phaseAt).toFloat() / FLASH_MS
        r.set(box.left, box.bottom + u * 4, box.left + box.width() * left.coerceIn(0f, 1f), box.bottom + u * 6)
        ui.round(c, r, u, Palette.GOLD)
    }

    private fun drawHandoff(c: Canvas, u: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val p = current
        val col = Palette.PLAYERS[p]
        r.set(0f, 0f, w, h)
        ui.round(c, r, 0f, Palette.darken(col, 0.7f))
        ui.text(c, "Turn ${turnIdx + 1} of $players", w / 2, h * 0.16f, u * 4, Palette.TEXT)
        ui.text(c, Palette.NAMES[p].uppercase(), w / 2, h * 0.3f, u * 18, col, bold = true)
        ui.text(c, "it's your guess", w / 2, h * 0.3f + u * 14, u * 5.5f, Palette.TEXT)
        ui.wrap(c, "Everyone else, look away from the screen.", w / 2, h * 0.5f, w * 0.8f, u * 4.5f, Palette.TEXT)
        r.set(w * 0.15f, h * 0.66f, w * 0.85f, h * 0.66f + u * 15)
        ui.button(c, r, "I'm ready", col, Palette.BG, u * 6, buttons.isPressed("ready"))
        buttons.add("ready", r) { entry = ""; go(Phase.INPUT) }
    }

    private fun drawHeader(c: Canvas, u: Float): Float {
        val w = width.toFloat()
        val p = current
        ui.text(c, Palette.NAMES[p].uppercase() + "'S GUESS", w / 2, height * 0.1f, u * 6.5f, Palette.PLAYERS[p], bold = true)
        val used = ui.wrap(c, round.prompt, w / 2, height * 0.1f + u * 10, w * 0.88f, u * 4.6f, Palette.TEXT, bold = true)
        return height * 0.1f + u * 10 + used
    }

    private fun drawInput(c: Canvas, u: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val below = drawHeader(c, u)

        if (round.kind == Kind.CLOCK) {
            ui.wrap(c, "Tap START, count in your head, then tap anywhere at ${round.answer.toInt()} seconds. The clock hides after one second.", w / 2, h * 0.38f, w * 0.84f, u * 4.2f, Palette.MUTED)
            val rad = min(w, h) * 0.22f
            ui.circle(c, w / 2, h * 0.64f, rad, Palette.ACCENT)
            ui.text(c, "START", w / 2, h * 0.64f, u * 8, Palette.TEXT, bold = true)
            r.set(w / 2 - rad, h * 0.64f - rad, w / 2 + rad, h * 0.64f + rad)
            buttons.add("start", r) { timerStart = SystemClock.uptimeMillis(); go(Phase.TIMING) }
            return
        }

        val padTop = h * 0.56f
        if (round.kind == Kind.ANGLE) drawAngle(c, u, w / 2, (below + padTop) / 2 - u * 4, min(w * 0.4f, (padTop - below) * 0.36f))
        if (round.kind == Kind.FACT && round.unit.isNotEmpty()) ui.text(c, "Answer in ${round.unit}", w / 2, below + u * 4, u * 3.8f, Palette.MUTED)

        // Entry readout.
        val shown = if (entry.isEmpty()) "–" else group(entry.toLong()) + (if (round.unit.isNotEmpty()) " " + round.unit else "")
        r.set(w * 0.08f, padTop - u * 15, w * 0.92f, padTop - u * 3)
        ui.round(c, r, u * 3, Palette.PANEL)
        ui.text(c, shown, w / 2, r.centerY(), u * 8, Palette.TEXT, bold = true)

        // Keypad.
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
        val gap = u * 2.5f
        val padW = min(w * 0.88f, (h - padTop) * 0.95f)
        val kw = (padW - gap * 2) / 3
        val kh = (h - padTop - u * 4 - gap * 3) / 4
        val left = w / 2 - padW / 2
        keys.forEachIndexed { i, k ->
            val col = i % 3
            val row = i / 3
            r.set(left + col * (kw + gap), padTop + row * (kh + gap), left + col * (kw + gap) + kw, padTop + row * (kh + gap) + kh)
            val isOk = k == "OK"
            val enabled = !isOk || entry.isNotEmpty()
            val fill = when {
                isOk && enabled -> Palette.PLAYERS[current]
                isOk -> Palette.PANEL
                else -> Palette.PANEL_HI
            }
            ui.button(c, r, k, fill, if (enabled) Palette.TEXT else Palette.MUTED, u * 7, buttons.isPressed("k$k"))
            buttons.add("k$k", r) {
                when (k) {
                    "⌫" -> entry = entry.dropLast(1)
                    "OK" -> if (entry.isNotEmpty()) submit(entry.toLong().toDouble())
                    else -> if (entry.length < 9 && !(entry == "0")) entry += k else if (entry == "0") entry = k
                }
                invalidate()
            }
        }
    }

    private fun drawAngle(c: Canvas, u: Float, cx: Float, cy: Float, len: Float) {
        val a1 = Math.toRadians(round.turn.toDouble())
        val a2 = Math.toRadians((round.turn + round.angle).toDouble())
        // Center the drawing on the angle's middle so it doesn't drift off to one side.
        val mid = Math.toRadians((round.turn + round.angle / 2).toDouble())
        val vx = cx - (cos(mid) * len * 0.45).toFloat()
        val vy = cy - (sin(mid) * len * 0.45).toFloat()
        ui.paint.style = Paint.Style.STROKE
        ui.paint.strokeCap = Paint.Cap.ROUND
        ui.paint.strokeWidth = u * 1.4f
        ui.paint.color = Palette.TEXT
        c.drawLine(vx, vy, vx + (cos(a1) * len).toFloat(), vy + (sin(a1) * len).toFloat(), ui.paint)
        c.drawLine(vx, vy, vx + (cos(a2) * len).toFloat(), vy + (sin(a2) * len).toFloat(), ui.paint)
        ui.paint.color = Palette.GOLD
        ui.paint.strokeWidth = u * 0.8f
        val ar = len * 0.2f
        c.drawArc(RectF(vx - ar, vy - ar, vx + ar, vy + ar), round.turn, round.angle, false, ui.paint)
        ui.paint.style = Paint.Style.FILL
        ui.circle(c, vx, vy, u * 1.2f, Palette.TEXT)
    }

    private fun drawTiming(c: Canvas, u: Float, now: Long) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = now - timerStart
        ui.text(c, Palette.NAMES[current].uppercase(), w / 2, h * 0.12f, u * 6.5f, Palette.PLAYERS[current], bold = true)
        ui.text(c, "Target: ${round.answer.toInt()} seconds", w / 2, h * 0.12f + u * 9, u * 4.5f, Palette.TEXT)
        val rad = min(w, h) * 0.3f
        val pulse = 0.5f + 0.5f * sin(t / 1000.0 * Math.PI * 2).toFloat()
        ui.circle(c, w / 2, h * 0.5f, rad, Palette.darken(Palette.PLAYERS[current], 0.55f + 0.15f * pulse))
        val label = if (t < 1000) String.format(Locale.US, "%.1f", t / 1000f) else "?"
        ui.text(c, label, w / 2, h * 0.5f, u * 18, Palette.TEXT, bold = true)
        ui.text(c, "Tap anywhere to stop", w / 2, h * 0.85f, u * 5, Palette.MUTED)
    }

    private fun drawReveal(c: Canvas, u: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        ui.text(c, round.kind.title.uppercase(), cx, h * 0.1f, u * 5, Palette.MUTED, bold = true)
        val used = ui.wrap(c, round.prompt, cx, h * 0.1f + u * 8, w * 0.88f, u * 4.2f, Palette.TEXT)
        var y = h * 0.1f + u * 10 + used
        ui.text(c, "Answer: " + fmt(round.answer), cx, y, u * 7, Palette.GOLD, bold = true)
        y += u * 18

        // Number line with every guess and the answer.
        val vals = (0 until players).map { guesses[it] } + round.answer
        var lo = vals.min()
        var hi = vals.max()
        if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
        val pad = (hi - lo) * 0.08
        lo -= pad
        hi += pad
        val x0 = w * 0.06f
        val x1 = w * 0.94f
        fun xOf(v: Double) = x0 + ((v - lo) / (hi - lo)).toFloat() * (x1 - x0)
        ui.paint.strokeWidth = u * 0.6f
        ui.paint.color = Palette.MUTED
        c.drawLine(x0, y, x1, y, ui.paint)
        val ax = xOf(round.answer)
        ui.paint.color = Palette.GOLD
        ui.paint.strokeWidth = u * 0.9f
        c.drawLine(ax, y - u * 7, ax, y + u * 7, ui.paint)
        val dotR = u * 3.2f
        val rows = ArrayList<Float>() // rightmost x used in each row
        for (p in (0 until players).sortedBy { guesses[it] }) {
            val x = xOf(guesses[p])
            var row = rows.indexOfFirst { x - it > dotR * 2.2f }
            if (row < 0) { rows.add(x); row = rows.size - 1 } else rows[row] = x
            val dy = y + (if (row % 2 == 0) -1 else 1) * (dotR * 1.6f + (row / 2) * dotR * 2.3f)
            ui.circle(c, x, dy, dotR, Palette.PLAYERS[p])
            ui.text(c, Palette.NAMES[p].substring(0, 1), x, dy, u * 3.2f, Palette.BG, bold = true)
        }
        y += u * 16 + (rows.size / 2) * dotR * 2.3f

        // Rows, closest first.
        val rowH = min(u * 9, (h * 0.86f - y) / players)
        for (p in (0 until players).sortedBy { abs(guesses[it] - round.answer) }) {
            val col = Palette.PLAYERS[p]
            ui.text(c, Palette.NAMES[p], w * 0.06f, y, u * 4.4f, col, bold = true, align = Paint.Align.LEFT)
            ui.text(c, fmt(guesses[p]), w * 0.5f, y, u * 4.4f, Palette.TEXT, align = Paint.Align.RIGHT)
            val off = abs(guesses[p] - round.answer)
            val offText = if (off <= round.tolerance + 1e-9) "spot on" else "off by " + fmt(off)
            ui.text(c, offText, w * 0.54f, y, u * 3.4f, Palette.MUTED, align = Paint.Align.LEFT)
            val pts = roundPts[p]
            ui.text(c, if (pts > 0) "+$pts" else "", w * 0.94f, y, u * 4.6f, Palette.GOLD, bold = true, align = Paint.Align.RIGHT)
            y += rowH
        }

        val last = roundNo >= totalRounds
        r.set(w * 0.12f, h * 0.88f, w * 0.88f, h * 0.88f + u * 13)
        ui.button(c, r, if (last) "Final scores" else "Next round", Palette.ACCENT, Palette.TEXT, u * 5.5f, buttons.isPressed("next"))
        buttons.add("next", r) { if (last) go(Phase.FINAL) else nextRound() }
    }

    private fun drawFinal(c: Canvas, u: Float, now: Long) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val ranked = (0 until players).sortedByDescending { scores[it] }
        val top = scores[ranked[0]]
        val leaders = ranked.filter { scores[it] == top }
        if (leaders.size == 1) {
            val p = leaders[0]
            ui.text(c, Palette.NAMES[p].uppercase(), cx, h * 0.16f, u * 16, Palette.PLAYERS[p], bold = true)
            ui.text(c, "has the best hunches", cx, h * 0.16f + u * 13, u * 5.5f, Palette.TEXT)
        } else {
            ui.text(c, "IT'S A TIE", cx, h * 0.16f, u * 13, Palette.GOLD, bold = true)
            ui.text(c, leaders.joinToString(" & ") { Palette.NAMES[it] }, cx, h * 0.16f + u * 13, u * 5.5f, Palette.TEXT)
        }
        var y = h * 0.16f + u * 30
        for (p in ranked) {
            ui.text(c, Palette.NAMES[p], cx - u * 4, y, u * 5.5f, Palette.PLAYERS[p], bold = true, align = Paint.Align.RIGHT)
            ui.text(c, scores[p].toString(), cx + u * 4, y, u * 5.5f, Palette.TEXT, bold = true, align = Paint.Align.LEFT)
            y += u * 9
        }
        if (now - phaseAt < 1000) return
        y += u * 8
        r.set(cx - u * 30, y - u * 7, cx + u * 30, y + u * 7)
        ui.button(c, r, "PLAY AGAIN", Palette.ACCENT, Palette.TEXT, u * 6, buttons.isPressed("again"))
        buttons.add("again", r) { startGame() }
        y += u * 18
        r.set(cx - u * 30, y - u * 6, cx + u * 30, y + u * 6)
        ui.button(c, r, "Menu", Palette.PANEL_HI, Palette.TEXT, u * 5, buttons.isPressed("menu"))
        buttons.add("menu", r) { go(Phase.MENU) }
    }

    private fun fmt(v: Double): String =
        if (round.kind == Kind.CLOCK) String.format(Locale.US, "%.2f s", v)
        else group(Math.round(v)) + (if (round.unit.isNotEmpty()) " " + round.unit else "")

    private fun group(v: Long) = String.format(Locale.US, "%,d", v)

    // ---- touch ----

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (phase == Phase.TIMING) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN && e.eventTime - timerStart > 150) {
                submit((e.eventTime - timerStart) / 1000.0)
            }
            return true
        }
        buttons.onTouch(e)
        invalidate()
        return true
    }

    companion object {
        const val FLASH_MS = 1500L
    }
}
