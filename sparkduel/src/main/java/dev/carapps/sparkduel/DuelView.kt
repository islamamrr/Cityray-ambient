package dev.carapps.sparkduel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import dev.carapps.gamekit.Buttons
import dev.carapps.gamekit.Palette
import dev.carapps.gamekit.Pausable
import dev.carapps.gamekit.Ui
import java.util.Random

/**
 * Spark Duel: every player owns a pad along the bottom of the screen.
 * Each round brings a new rule for when to hit your pad. First to the target score wins.
 */
class DuelView(context: Context) : View(context), Pausable {

    private enum class Phase { MENU, INTRO, PLAY, RESULT, PAUSED, OVER }

    private val ui = Ui()
    private val buttons = Buttons()
    private val rng = Random()

    private var phase = Phase.MENU
    private var phaseAt = 0L
    private var pausedFrom = Phase.MENU
    private var paused = false

    private var players = 2
    private var target = 5
    private val scores = IntArray(4)
    private val padFlash = LongArray(4)

    private val allRules: List<Rule> = listOf(GoldRule(), MyColorRule(), GoWordRule(), CountRule(), BlindRingRule())
    private val bag = ArrayList<Rule>()
    private var rule: Rule = allRules[0]
    private var round = 0
    private var resultText = ""
    private var foulText = ""
    private var resultColor = Palette.TEXT
    private var quitArmedAt = 0L

    private val pads = Array(4) { RectF() }
    private val arena = Arena()
    private val r = RectF()

    // ---- flow ----

    private fun go(p: Phase) {
        phase = p
        phaseAt = SystemClock.uptimeMillis()
    }

    private fun startMatch() {
        scores.fill(0)
        bag.clear()
        round = 0
        nextRound()
    }

    private fun nextRound() {
        if (bag.isEmpty()) {
            bag.addAll(allRules.shuffled(rng))
            // Never repeat the same rule across a reshuffle.
            if (bag[0] === rule && bag.size > 1) bag.add(bag.removeAt(0))
        }
        rule = bag.removeAt(0)
        (rule as? CountRule)?.pick(rng)
        round++
        go(Phase.INTRO)
    }

    private fun endRound() {
        val w = rule.winner
        if (w >= 0) scores[w]++
        val fouls = (0 until players).filter { rule.fouled[it] }
        for (p in fouls) scores[p] = maxOf(0, scores[p] - 1)
        resultText = rule.message
        resultColor = if (w >= 0) Palette.PLAYERS[w] else Palette.MUTED
        foulText = if (fouls.isEmpty()) "" else fouls.joinToString(", ") { Palette.NAMES[it] } + " fouled, −1"
        go(Phase.RESULT)
    }

    override fun setPaused(paused: Boolean) {
        this.paused = paused
        if (paused) {
            if (phase == Phase.INTRO || phase == Phase.PLAY || phase == Phase.RESULT) {
                pausedFrom = phase
                go(Phase.PAUSED)
            }
        } else {
            invalidate()
        }
    }

    private fun resumeFromPause() {
        if (pausedFrom == Phase.RESULT) {
            afterResult()
        } else {
            // Replay the interrupted round from its intro.
            (rule as? CountRule)?.pick(rng)
            go(Phase.INTRO)
        }
    }

    private fun afterResult() {
        if ((0 until players).any { scores[it] >= target }) go(Phase.OVER) else nextRound()
    }

    private fun update(now: Long) {
        when (phase) {
            Phase.INTRO -> if (now - phaseAt > INTRO_MS) {
                rule.begin(now, players, rng)
                go(Phase.PLAY)
            }
            Phase.PLAY -> {
                rule.update(now)
                if (rule.done) endRound()
            }
            Phase.RESULT -> if (now - phaseAt > RESULT_MS) afterResult()
            else -> Unit
        }
    }

    // ---- layout ----

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutPads()
    }

    private fun layoutPads() {
        val w = width.toFloat()
        val h = height.toFloat()
        val u = minOf(w, h) / 100f
        val padH = h * (if (h > w) 0.28f else 0.34f)
        val gap = u * 2.5f
        val pw = (w - gap * (players + 1)) / players
        for (i in 0 until players) {
            val left = gap + i * (pw + gap)
            pads[i].set(left, h - padH - gap, left + pw, h - gap)
        }
        val top = u * 12
        val bottom = h - padH - gap * 2
        arena.cx = w / 2
        arena.cy = (top + bottom) / 2
        arena.outerR = minOf(w * 0.92f, bottom - top) / 2
        arena.coreR = arena.outerR * 0.42f
        arena.unit = u
    }

    // ---- drawing ----

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        update(now)
        buttons.clear()
        c.drawColor(Palette.BG)
        when (phase) {
            Phase.MENU -> drawMenu(c)
            Phase.OVER -> drawOver(c, now)
            else -> drawGame(c, now)
        }
        if (!paused) postInvalidateOnAnimation()
    }

    private fun drawMenu(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val u = minOf(w, h) / 100f
        val cx = w / 2
        var y = h * 0.12f

        ui.text(c, "SPARK DUEL", cx, y, u * 11, Palette.GOLD, bold = true)
        y += u * 9
        ui.text(c, "A reflex game for 2 to 4 players", cx, y, u * 4.2f, Palette.MUTED)

        y += u * 16
        ui.text(c, "Players", cx, y, u * 4.5f, Palette.TEXT, bold = true)
        y += u * 9
        chips(c, y, u, listOf(2, 3, 4), players, "p") { players = it; layoutPads() }

        y += u * 15
        ui.text(c, "First to", cx, y, u * 4.5f, Palette.TEXT, bold = true)
        y += u * 9
        chips(c, y, u, listOf(3, 5, 7), target, "t") { target = it }

        y += u * 16
        r.set(cx - u * 30, y - u * 7, cx + u * 30, y + u * 7)
        ui.button(c, r, "START", Palette.ACCENT, Palette.TEXT, u * 6, buttons.isPressed("start"))
        buttons.add("start", r) { layoutPads(); startMatch() }

        y += u * 18
        ui.wrap(
            c,
            "Each player gets a pad along the bottom. Every round has a new rule for when to hit it. " +
                "Get it right first to score. Tap at the wrong moment and you lose a point.",
            cx, y, w * 0.84f, u * 3.8f, Palette.MUTED,
        )
    }

    private fun chips(c: Canvas, y: Float, u: Float, values: List<Int>, selected: Int, key: String, pick: (Int) -> Unit) {
        val cw = u * 16
        val gap = u * 4
        val total = values.size * cw + (values.size - 1) * gap
        var x = width / 2f - total / 2
        for (v in values) {
            r.set(x, y - u * 6, x + cw, y + u * 6)
            val on = v == selected
            ui.button(c, r, v.toString(), if (on) Palette.TEXT else Palette.PANEL_HI, if (on) Palette.BG else Palette.TEXT, u * 5.5f, buttons.isPressed("$key$v"))
            buttons.add("$key$v", r) { pick(v) }
            x += cw + gap
        }
    }

    private fun drawGame(c: Canvas, now: Long) {
        val w = width.toFloat()
        val u = minOf(w, height.toFloat()) / 100f

        // Quit: two taps, so a stray touch can't end the match.
        val armed = now - quitArmedAt < 2500
        r.set(u * 3, u * 3, u * (if (armed) 40 else 18), u * 10)
        ui.button(c, r, if (armed) "Tap again to quit" else "Menu", Palette.PANEL_HI, Palette.TEXT, u * 3.2f)
        buttons.add("quit", r) {
            if (SystemClock.uptimeMillis() - quitArmedAt < 2500) go(Phase.MENU) else quitArmedAt = SystemClock.uptimeMillis()
        }
        ui.text(c, "Round $round · first to $target", w - u * 4, u * 6.5f, u * 3.2f, Palette.MUTED, align = android.graphics.Paint.Align.RIGHT)

        when (phase) {
            Phase.INTRO -> drawIntro(c, now, u)
            Phase.PLAY -> {
                rule.draw(c, ui, arena, now)
                ui.text(c, rule.title, arena.cx, arena.cy - arena.outerR - u * 1, u * 4, Palette.MUTED, bold = true)
            }
            Phase.RESULT -> {
                rule.draw(c, ui, arena, now)
                drawBanner(c, u, resultText, resultColor, foulText)
            }
            Phase.PAUSED -> {
                drawBanner(c, u, "Paused", Palette.TEXT, "Tap anywhere to carry on")
            }
            else -> Unit
        }
        drawPads(c, now, u)
    }

    private fun drawIntro(c: Canvas, now: Long, u: Float) {
        val t = now - phaseAt
        val cx = arena.cx
        ui.text(c, rule.title, cx, arena.cy - u * 18, u * 10, Palette.GOLD, bold = true)
        ui.wrap(c, rule.hint, cx, arena.cy - u * 4, width * 0.84f, u * 4.6f, Palette.TEXT)
        val left = ((INTRO_MS - t) / 1000 + 1).coerceIn(1L, 3L)
        ui.text(c, if (t > INTRO_MS - 3000) "$left" else "", cx, arena.cy + u * 24, u * 9, Palette.MUTED, bold = true)
    }

    private fun drawBanner(c: Canvas, u: Float, title: String, color: Int, sub: String) {
        val w = width.toFloat()
        r.set(u * 4, arena.cy - u * 13, w - u * 4, arena.cy + u * 13)
        ui.round(c, r, u * 4, Palette.alpha(Palette.PANEL, 235))
        ui.wrap(c, title, arena.cx, arena.cy - u * (if (sub.isEmpty()) 0 else 4), w * 0.86f, u * 6, color, bold = true)
        if (sub.isNotEmpty()) ui.text(c, sub, arena.cx, arena.cy + u * 8, u * 3.8f, Palette.MUTED)
    }

    private fun drawPads(c: Canvas, now: Long, u: Float) {
        for (p in 0 until players) {
            val pad = pads[p]
            val col = Palette.PLAYERS[p]
            val fouled = (phase == Phase.PLAY || phase == Phase.RESULT) && rule.fouled[p]
            val flash = now - padFlash[p] < 150
            val fill = when {
                fouled -> Palette.darken(col, 0.8f)
                flash -> col
                else -> Palette.darken(col, 0.45f)
            }
            ui.round(c, pad, u * 4, fill)
            val won = phase == Phase.RESULT && rule.winner == p
            if (won) ui.outline(c, pad, u * 4, Palette.GOLD, u * 1.2f)

            val cx = pad.centerX()
            ui.text(c, Palette.NAMES[p].uppercase(), cx, pad.top + pad.height() * 0.2f, u * 4.2f, Palette.TEXT, bold = true)
            ui.text(c, scores[p].toString(), cx, pad.centerY(), u * 12, Palette.TEXT, bold = true)

            // Score dots out of the target.
            val dotR = minOf(u * 1.3f, pad.width() / (target * 3f))
            val step = dotR * 3
            var x = cx - step * (target - 1) / 2
            val dy = pad.top + pad.height() * 0.72f
            for (i in 0 until target) {
                ui.circle(c, x, dy, dotR, if (i < scores[p]) Palette.TEXT else Palette.alpha(Palette.TEXT, 60))
                x += step
            }

            val note = when {
                fouled -> "FOUL"
                phase == Phase.PLAY || phase == Phase.RESULT -> rule.padNote(p)
                else -> null
            }
            if (note != null) ui.text(c, note, cx, pad.top + pad.height() * 0.88f, u * 3.4f, Palette.TEXT, bold = true)
        }
    }

    private fun drawOver(c: Canvas, now: Long) {
        val w = width.toFloat()
        val h = height.toFloat()
        val u = minOf(w, h) / 100f
        val cx = w / 2
        val winner = (0 until players).maxByOrNull { scores[it] } ?: 0
        ui.text(c, Palette.NAMES[winner].uppercase(), cx, h * 0.2f, u * 16, Palette.PLAYERS[winner], bold = true)
        ui.text(c, "wins the duel", cx, h * 0.2f + u * 13, u * 6, Palette.TEXT)

        var y = h * 0.2f + u * 32
        for (p in (0 until players).sortedByDescending { scores[it] }) {
            ui.text(c, "${Palette.NAMES[p]}   ${scores[p]}", cx, y, u * 5, Palette.PLAYERS[p], bold = true)
            y += u * 8
        }

        if (now - phaseAt < 1200) return // ignore the winning tap's follow-through
        y += u * 10
        r.set(cx - u * 30, y - u * 7, cx + u * 30, y + u * 7)
        ui.button(c, r, "REMATCH", Palette.ACCENT, Palette.TEXT, u * 6, buttons.isPressed("again"))
        buttons.add("again", r) { startMatch() }
        y += u * 18
        r.set(cx - u * 30, y - u * 6, cx + u * 30, y + u * 6)
        ui.button(c, r, "Menu", Palette.PANEL_HI, Palette.TEXT, u * 5, buttons.isPressed("menu"))
        buttons.add("menu", r) { go(Phase.MENU) }
    }

    // ---- touch ----

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val action = e.actionMasked
        if (phase == Phase.PLAY && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)) {
            val i = e.actionIndex
            val p = padAt(e.getX(i), e.getY(i))
            if (p >= 0) {
                padFlash[p] = SystemClock.uptimeMillis()
                rule.tap(p, e.eventTime)
                return true
            }
        }
        if (phase == Phase.PAUSED && action == MotionEvent.ACTION_UP && !paused) {
            if (!buttons.onTouch(e)) resumeFromPause()
            return true
        }
        buttons.onTouch(e)
        return true
    }

    private fun padAt(x: Float, y: Float): Int {
        for (p in 0 until players) if (pads[p].contains(x, y)) return p
        return -1
    }

    companion object {
        const val INTRO_MS = 4200L
        const val RESULT_MS = 2600L
    }
}
