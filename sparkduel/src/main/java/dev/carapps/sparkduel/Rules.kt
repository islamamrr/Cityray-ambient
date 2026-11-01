package dev.carapps.sparkduel

import android.graphics.Canvas
import dev.carapps.gamekit.Palette
import dev.carapps.gamekit.Ui
import java.util.Random
import kotlin.math.abs

/** Where the round's core is drawn. */
class Arena {
    var cx = 0f
    var cy = 0f
    var coreR = 0f
    var outerR = 0f
    var unit = 1f
}

/**
 * One round of Spark Duel. A round ends with at most one winner. A player who taps at the
 * wrong moment is "fouled": they lose a point and sit out the rest of the round.
 */
abstract class Rule(val title: String) {
    var winner: Int = -1
        protected set
    val fouled = BooleanArray(4)
    var done = false
        protected set
    var message = ""
        protected set
    var players = 2
        private set

    abstract val hint: String

    fun begin(now: Long, players: Int, rng: Random) {
        this.players = players
        winner = -1
        fouled.fill(false)
        done = false
        message = ""
        build(now, rng)
    }

    protected abstract fun build(now: Long, rng: Random)
    abstract fun tap(p: Int, t: Long)
    abstract fun update(now: Long)
    abstract fun draw(c: Canvas, ui: Ui, a: Arena, now: Long)

    /** Small text under a player's pad, such as their timing. */
    open fun padNote(p: Int): String? = null

    protected fun name(p: Int) = Palette.NAMES[p]

    protected fun foul(p: Int) {
        fouled[p] = true
        if ((0 until players).all { fouled[it] }) finish(-1, "Everyone jumped the gun")
    }

    protected fun finish(w: Int, msg: String) {
        winner = w
        message = msg
        done = true
    }
}

/** A stretch of time where the core looks one way, and which players may tap. */
class Segment(
    val start: Long,
    val end: Long,
    val color: Int,
    val text: String? = null,
    val textColor: Int = Palette.TEXT,
    val validMask: Int = 0,
)

/** A rule made of a fixed timeline of [Segment]s, decided when the round starts. */
abstract class SequenceRule(title: String) : Rule(title) {
    protected val segs = ArrayList<Segment>()

    override fun build(now: Long, rng: Random) {
        segs.clear()
        timeline(now, rng)
    }

    protected abstract fun timeline(now: Long, rng: Random)
    protected abstract fun winText(p: Int, t: Long, s: Segment): String
    protected abstract fun timeoutText(): String

    protected fun add(start: Long, dur: Long, color: Int, text: String? = null, textColor: Int = Palette.TEXT, mask: Int = 0): Long {
        segs.add(Segment(start, start + dur, color, text, textColor, mask))
        return start + dur
    }

    protected fun allMask() = (1 shl players) - 1

    private fun segAt(t: Long): Segment? {
        for (i in segs.indices.reversed()) {
            val s = segs[i]
            if (t >= s.start) return if (t < s.end) s else null
        }
        return null
    }

    override fun tap(p: Int, t: Long) {
        if (done || fouled[p]) return
        val s = segAt(t)
        if (s != null && (s.validMask shr p) and 1 == 1) finish(p, winText(p, t, s))
        else foul(p)
    }

    override fun update(now: Long) {
        if (!done && segs.isNotEmpty() && now >= segs.last().end) finish(-1, timeoutText())
    }

    override fun draw(c: Canvas, ui: Ui, a: Arena, now: Long) {
        val s = segAt(now)
        ui.circle(c, a.cx, a.cy, a.coreR, s?.color ?: CORE_IDLE)
        s?.text?.let { ui.text(c, it, a.cx, a.cy, a.coreR * 0.62f, s.textColor, bold = true) }
    }

    companion object {
        const val CORE_IDLE = 0xFF1A2232.toInt()
    }
}

/** Colors flicker; the first tap after the core turns gold wins. */
class GoldRule : SequenceRule("GOLD RUSH") {
    override val hint = "Tap your pad the moment the core turns gold. Any other color is a trap."

    private val decoys = intArrayOf(
        0xFFFF7A1A.toInt(), 0xFFE8E8E8.toInt(), 0xFF5AD1FF.toInt(),
        0xFFC6FF3D.toInt(), 0xFFFFE9A8.toInt(), 0xFFFF5EC4.toInt(),
    )

    override fun timeline(now: Long, rng: Random) {
        val goldAt = now + 2000 + rng.nextInt(4500)
        var t = now + 400
        var last = -1
        while (t < goldAt) {
            var i = rng.nextInt(decoys.size)
            if (i == last) i = (i + 1) % decoys.size
            last = i
            val end = minOf(t + 300 + rng.nextInt(600), goldAt)
            t = add(t, end - t, decoys[i])
        }
        add(goldAt, 3000, Palette.GOLD, mask = allMask())
    }

    override fun winText(p: Int, t: Long, s: Segment) = "${name(p)} scores · ${t - s.start} ms"
    override fun timeoutText() = "Too slow. Nobody scores"
}

/** The core cycles through the players' colors; tap when it shows yours. */
class MyColorRule : SequenceRule("YOUR COLOR") {
    override val hint = "Tap when the core shows YOUR color. Someone else's color is a foul."

    private val neutrals = intArrayOf(0xFFE8E8E8.toInt(), 0xFF59617A.toInt())

    override fun timeline(now: Long, rng: Random) {
        var t = add(now, 600L + rng.nextInt(600), neutrals[0])
        val end = now + 14000
        var last = -1
        while (t < end) {
            var pick = if (rng.nextFloat() < 0.45f) -1 else rng.nextInt(players)
            if (pick == last) pick = if (pick == -1) rng.nextInt(players) else -1
            last = pick
            t = if (pick < 0) {
                add(t, 350L + rng.nextInt(600), neutrals[rng.nextInt(neutrals.size)])
            } else {
                add(t, 550L + rng.nextInt(450), Palette.PLAYERS[pick], mask = 1 shl pick)
            }
        }
    }

    override fun winText(p: Int, t: Long, s: Segment) = "${name(p)} spotted it · ${t - s.start} ms"
    override fun timeoutText() = "Nobody grabbed their color"
}

/** Words flash in the core. Only the word GO counts. */
class GoWordRule : SequenceRule("ONLY GO") {
    override val hint = "Words will flash. Tap only on the word GO, whatever color it is."

    private val decoys = arrayOf("NO", "SO", "OG", "DO", "EGO", "TO", "GOAL", "OH", "G0", "YO")
    private val inks = intArrayOf(
        0xFF35D07F.toInt(), 0xFFFF4D6A.toInt(), 0xFFFFC400.toInt(),
        0xFF5AD1FF.toInt(), 0xFFF2F4F8.toInt(),
    )

    override fun timeline(now: Long, rng: Random) {
        val before = 3 + rng.nextInt(6)
        var t = now + 300
        var last = ""
        repeat(before) {
            var w = decoys[rng.nextInt(decoys.size)]
            if (w == last) w = decoys[(decoys.indexOf(w) + 1) % decoys.size]
            last = w
            t = add(t, 450L + rng.nextInt(400), WORD_BG, w, inks[rng.nextInt(inks.size)])
        }
        t = add(t, 950, WORD_BG, "GO", inks[rng.nextInt(inks.size)], allMask())
        repeat(3) {
            t = add(t, 450L + rng.nextInt(400), WORD_BG, decoys[rng.nextInt(decoys.size)], inks[rng.nextInt(inks.size)])
        }
    }

    override fun winText(p: Int, t: Long, s: Segment) = "${name(p)} says GO · ${t - s.start} ms"
    override fun timeoutText() = "The GO slipped past everyone"

    companion object {
        const val WORD_BG = 0xFF101626.toInt()
    }
}

/** The core flashes at an uneven beat. Tap on flash number N, not before, not after. */
class CountRule : SequenceRule("COUNT") {
    private var target = 5
    override val hint: String get() = "Count the flashes silently. Tap on flash number $target, before the next one."

    override fun timeline(now: Long, rng: Random) {
        var t = now + 700
        for (i in 1..target + 2) {
            val mask = if (i == target) allMask() else 0
            t = add(t, 160, 0xFFF2F4F8.toInt(), mask = mask)
            t = add(t, 250L + rng.nextInt(800), CORE_IDLE, mask = mask)
        }
    }

    /** Picks the number to count to. Called before the intro, so the hint can show it. */
    fun pick(rng: Random) {
        target = 4 + rng.nextInt(5)
    }

    override fun winText(p: Int, t: Long, s: Segment) = "${name(p)} counted right"
    override fun timeoutText() = "Nobody caught flash $target"
}

/**
 * A ring closes in on the core, then vanishes partway. Everyone gets one tap;
 * whoever is closest to the moment it would touch the core wins.
 */
class BlindRingRule : Rule("BLIND RING") {
    override val hint = "The ring closes in, then disappears. Tap once when it would touch the core. Closest wins."

    private var start = 0L
    private var dur = 2000L
    private var touchAt = 0L
    private var vanishAt = 0L
    private var endAt = 0L
    private val err = LongArray(4)
    private val tapped = BooleanArray(4)

    override fun build(now: Long, rng: Random) {
        start = now + 500
        dur = 1800L + rng.nextInt(1600)
        touchAt = start + dur
        vanishAt = start + (dur * (0.45f + rng.nextFloat() * 0.2f)).toLong()
        endAt = touchAt + 900
        tapped.fill(false)
    }

    override fun tap(p: Int, t: Long) {
        if (done || tapped[p]) return
        tapped[p] = true
        err[p] = t - touchAt
        if ((0 until players).all { tapped[it] }) decide()
    }

    override fun update(now: Long) {
        if (!done && now >= endAt) decide()
    }

    private fun decide() {
        val best = (0 until players).filter { tapped[it] }.minByOrNull { abs(err[it]) }
        if (best == null) finish(-1, "Nobody tapped")
        else finish(best, "${name(best)} wins · ${describe(err[best])}")
    }

    private fun describe(e: Long) = when {
        e == 0L -> "perfect"
        e < 0 -> "${-e} ms early"
        else -> "$e ms late"
    }

    override fun padNote(p: Int): String? = if (done && tapped[p]) describe(err[p]) else if (tapped[p]) "locked in" else null

    override fun draw(c: Canvas, ui: Ui, a: Arena, now: Long) {
        ui.circle(c, a.cx, a.cy, a.coreR, 0xFF1F2A44.toInt())
        ui.ring(c, a.cx, a.cy, a.coreR, Palette.GOLD, a.unit * 0.8f)
        val ringR = a.outerR - (a.outerR - a.coreR) * (now - start).toFloat() / dur
        if (now < vanishAt && ringR > 0) {
            ui.ring(c, a.cx, a.cy, ringR, Palette.TEXT, a.unit * 1.4f)
        } else if (done) {
            // Show where the ring really was, so everyone sees the true moment.
            ui.ring(c, a.cx, a.cy, a.coreR, Palette.alpha(Palette.TEXT, 200), a.unit * 1.4f)
        }
    }
}
