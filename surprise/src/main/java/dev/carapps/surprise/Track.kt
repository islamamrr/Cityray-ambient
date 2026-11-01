package dev.carapps.surprise

import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random

class Segment(val index: Int) {
    var curve = 0f
    var y1 = 0f
    var y2 = 0f
    val z1: Float get() = index * Track.SEG

    // Screen projection, refreshed every frame.
    var sx1 = 0f; var sy1 = 0f; var sw1 = 0f; var scale1 = 0f
    var sx2 = 0f; var sy2 = 0f; var sw2 = 0f; var scale2 = 0f
    var fog = 0f

    var orbOffset = Float.NaN
    var orbTaken = false
    val hasOrb: Boolean get() = !orbOffset.isNaN() && !orbTaken

    val light: Boolean get() = (index / Track.RUMBLE) % 2 == 0
}

/** Builds a random looping road of curves and hills. */
object Track {
    const val SEG = 200f
    const val RUMBLE = 3

    fun build(seed: Long): Array<Segment> {
        val r = Random(seed)
        val list = ArrayList<Segment>()

        fun lastY() = if (list.isEmpty()) 0f else list.last().y2
        fun add(curve: Float, y: Float) {
            val s = Segment(list.size)
            s.curve = curve
            s.y1 = lastY()
            s.y2 = y
            list += s
        }
        fun easeIn(a: Float, b: Float, p: Float) = a + (b - a) * p * p
        fun easeInOut(a: Float, b: Float, p: Float) = a + (b - a) * ((-cos(p * PI) / 2) + 0.5).toFloat()
        fun road(enter: Int, hold: Int, leave: Int, curve: Float, hill: Float) {
            val startY = lastY()
            val endY = startY + hill * SEG
            val total = (enter + hold + leave).toFloat()
            for (n in 0 until enter) add(easeIn(0f, curve, n / enter.toFloat()), easeInOut(startY, endY, n / total))
            for (n in 0 until hold) add(curve, easeInOut(startY, endY, (enter + n) / total))
            for (n in 0 until leave) add(easeInOut(curve, 0f, n / leave.toFloat()), easeInOut(startY, endY, (enter + hold + n) / total))
        }
        fun side() = if (r.nextBoolean()) 1f else -1f
        fun hill() = (r.nextFloat() * 2f - 1f) * 30f - lastY() / SEG * 0.5f

        road(0, 70, 0, 0f, 0f)
        repeat(44) {
            when (r.nextInt(7)) {
                0 -> road(20, 30 + r.nextInt(40), 20, 0f, hill())
                1, 2 -> road(25, 30 + r.nextInt(50), 25, side() * (1.2f + r.nextFloat() * 2.2f), hill())
                3 -> {
                    val c = side() * (1.5f + r.nextFloat() * 1.5f)
                    road(20, 25, 20, c, 0f)
                    road(20, 25, 20, -c, 0f)
                }
                4 -> repeat(3) { road(12, 12, 12, 0f, if (it % 2 == 0) 9f else -9f) }
                5 -> road(30, 50, 30, side() * 3.2f, hill())
                else -> road(10, 40 + r.nextInt(40), 10, side() * 0.8f, 0f)
            }
        }
        road(30, 60, 30, 0f, -lastY() / SEG)
        road(0, 40, 0, 0f, 0f)

        // Lines of glowing orbs to collect.
        var i = 90
        while (i < list.size - 40) {
            if (r.nextFloat() < 0.6f) {
                val start = r.nextFloat() * 1.4f - 0.7f
                val slope = (r.nextFloat() * 2f - 1f) * 0.12f
                for (k in 0 until 6) list[i + k * 3].orbOffset = (start + k * slope).coerceIn(-0.8f, 0.8f)
            }
            i += 35 + r.nextInt(45)
        }
        return list.toTypedArray()
    }
}
