package dev.carapps.tumbler

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** One number of the combination. [dir] is +1 when it must be approached turning right, -1 for left. */
class Pin(val target: Int, val dir: Int, val decoys: IntArray)

class Loot(val name: String, val value: Int)

/** Everything that makes one vault different from the next. */
class VaultSpec(
    val number: Int,
    val pins: List<Pin>,
    /** How many numbers either side of the target the clicks start getting heavier. */
    val zone: Float,
    /** How close to the target the dial must sit (in numbers) to set the pin. */
    val tol: Float,
    /** Seconds until the alarm goes off if nothing else goes wrong. */
    val timeLimit: Float,
    /** Slack (in numbers) when the dial changes direction. 0 for a tight dial. */
    val backlash: Float,
    val loot: Loot,
)

object Vaults {

    /** Level 1 is a gentle tutorial; it gets harder from there with no upper limit. */
    fun make(level: Int, seed: Long): VaultSpec {
        val rnd = Random(seed * 31 + level)
        val l = max(1, level)
        val pinCount = min(3 + (l - 1) / 3, 6)
        val zone = max(11f - (l - 1) * 0.9f, 3f)
        val tol = max(0.7f - l * 0.025f, 0.42f)
        val decoysPerPin = if (l < 3) 0 else min((l - 1) / 2, 3)
        val backlash = if (l < 5) 0f else min(0.8f + (l - 5) * 0.25f, 2.5f)

        val pins = ArrayList<Pin>()
        var last = 0
        for (i in 0 until pinCount) {
            var t: Int
            do {
                t = rnd.nextInt(100)
            } while (cyc(t.toFloat(), last.toFloat()) < 18f)
            last = t
            val decoys = IntArray(decoysPerPin)
            for (d in 0 until decoysPerPin) {
                var c: Int
                do {
                    c = rnd.nextInt(100)
                } while (cyc(c.toFloat(), t.toFloat()) < zone + 4f || decoys.take(d).any { cyc(it.toFloat(), c.toFloat()) < 6f })
                decoys[d] = c
            }
            pins.add(Pin(t, if (i % 2 == 0) 1 else -1, decoys))
        }
        val time = max(55f + pinCount * 28f - l * 2.5f, 30f + pinCount * 16f)
        val loot = Loot(LOOT[rnd.nextInt(LOOT.size)], (4000 + l * 2500 + rnd.nextInt(40) * 100))
        return VaultSpec(level, pins, zone, tol, time, backlash, loot)
    }

    private val LOOT = listOf(
        "a golden falafel",
        "the last cassette of a band nobody remembers",
        "a map to the best parking spot in Cairo",
        "1,000 shares in a rubber duck company",
        "a diamond the size of a date",
        "the original blueprint of a cup holder",
        "a jar of very old coins",
        "a signed photo of a famous camel",
        "an emerald shaped like a steering wheel",
        "grandma's secret koshari recipe",
        "a pocket watch that runs backwards",
        "a crown that is slightly too small",
        "a meteorite still warm to the touch",
        "a tiny pyramid made of solid gold",
        "a lifetime supply of air fresheners",
        "a violin with only one string",
        "a key to another, bigger vault",
        "a sealed letter marked DO NOT OPEN",
    )
}

/** Distance between two dial numbers going the short way round, 0..50. */
fun cyc(a: Float, b: Float): Float {
    val d = abs(mod100(a) - mod100(b))
    return min(d, 100f - d)
}

fun mod100(x: Float): Float {
    val m = x - floor(x / 100f) * 100f
    return if (m >= 100f) m - 100f else m
}

/**
 * The rules of one attempt at a vault, without any drawing or sound.
 * Feed it the dial position every frame; it calls [Feedback] when something happens.
 */
class Heist(val spec: VaultSpec, private val out: Feedback) {

    interface Feedback {
        /** The dial passed a number. [strength] 0..1 is how close to the gate; [heavy] is a gate (real or decoy). */
        fun tick(strength: Float, heavy: Boolean)
        fun pinSet(index: Int)
        fun wrongWay()
        fun jammed()
        fun alarm()
        fun cracked()
    }

    enum class State { RUNNING, CRACKED, FAILED }

    var state = State.RUNNING
        private set
    var pinIndex = 0
        private set
    /** The dial position actually shown, after any backlash. Unbounded; use [mod100] for the number. */
    var dial = 0f
        private set
    var elapsed = 0f
        private set
    /** 0..1. The vault fails at 1. */
    var alarm = 0f
        private set
    /** 0..1, how long the dial has been held still on a gate in the right direction. */
    var hold = 0f
        private set
    /** True while the dial sits on a gate but was last turned the wrong way. */
    var wrongWayNow = false
        private set
    /** +1 if the dial was last turned right, -1 left, 0 not yet. */
    var lastDir = 0
        private set

    private var started = false
    private var swing = 0f
    private var speed = 0f
    private var mustLeave = false
    private var warned = false

    val pin: Pin? get() = spec.pins.getOrNull(pinIndex)

    fun update(dt: Float, input: Float) {
        if (state != State.RUNNING) return
        val prev = dial
        if (!started) {
            started = true
            dial = input
            return
        }
        val bl = spec.backlash
        dial = when {
            bl <= 0f -> input
            input - dial > bl -> input - bl
            dial - input > bl -> input + bl
            else -> dial
        }
        val delta = dial - prev
        if (delta != 0f) {
            swing = if ((delta > 0) == (swing > 0) && swing != 0f) swing + delta else delta
            if (abs(swing) > 0.3f) lastDir = if (swing > 0) 1 else -1
        }
        val inst = if (dt > 0f) abs(delta) / dt else 0f
        speed += (inst - speed) * min(1f, dt * 10f)

        clicks(prev, dial)

        elapsed += dt
        alarm += dt / spec.timeLimit
        if (alarm >= 1f) {
            alarm = 1f
            state = State.FAILED
            out.alarm()
            return
        }
        gate(dt)
    }

    private fun clicks(from: Float, to: Float) {
        val a = floor(from).toInt()
        val b = floor(to).toInt()
        if (a == b) return
        val p = pin ?: return
        // Moving up we cross a+1..b; moving down we cross a..b+1. Only the last few matter for sound.
        val crossed = if (b > a) ((a + 1)..b).toList() else (a downTo (b + 1)).toList()
        for (n in crossed.takeLast(5)) {
            val num = mod100(n.toFloat())
            val d = cyc(num, p.target.toFloat())
            val strength = (1f - d / spec.zone).coerceIn(0f, 1f)
            val heavy = d < 0.5f || p.decoys.any { cyc(num, it.toFloat()) < 0.5f }
            out.tick(strength, heavy)
        }
    }

    private fun gate(dt: Float) {
        val p = pin ?: return
        val num = mod100(dial)
        val onTarget = cyc(num, p.target.toFloat()) <= spec.tol
        val onDecoy = !onTarget && p.decoys.any { cyc(num, it.toFloat()) <= spec.tol }
        val onGate = onTarget || onDecoy

        if (!onGate) {
            mustLeave = false
            warned = false
            wrongWayNow = false
            hold = max(0f, hold - dt * 3f)
            return
        }
        if (mustLeave) return
        if (lastDir != p.dir) {
            wrongWayNow = lastDir != 0
            hold = max(0f, hold - dt * 3f)
            if (wrongWayNow && !warned) {
                warned = true
                out.wrongWay()
            }
            return
        }
        wrongWayNow = false
        if (speed > 3f) return
        hold += dt / HOLD_SECONDS
        if (hold < 1f) return
        hold = 0f
        mustLeave = true
        if (onTarget) {
            out.pinSet(pinIndex)
            pinIndex++
            lastDir = 0
            swing = 0f
            if (pinIndex >= spec.pins.size) {
                state = State.CRACKED
                out.cracked()
            }
        } else {
            alarm = min(1f, alarm + JAM_PENALTY)
            out.jammed()
        }
    }

    /** 3 stars for a quick, clean job. */
    fun stars(): Int = when {
        alarm < 0.45f -> 3
        alarm < 0.75f -> 2
        else -> 1
    }

    fun haul(): Int = ((spec.loot.value * (0.5f + 0.5f * (1f - alarm))) / 10f).toInt() * 10

    companion object {
        const val HOLD_SECONDS = 0.8f
        const val JAM_PENALTY = 0.15f
    }
}
