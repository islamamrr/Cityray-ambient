package dev.carapps.car

import kotlin.math.abs
import kotlin.math.atan

/**
 * Estimates how far the steering wheel is turned from the four wheel-rotation tooth
 * counters (raw signals WhlRotToothCntrFrntLe / FrntRi / ReLe / ReRi), because the car does
 * not send the steering angle itself to the head unit (see CAR_NOTES). In a turn the outer
 * rear wheel rolls further than the inner one; the difference gives the turn radius, and the
 * radius gives the front wheel angle. Only works while moving, and is coarse at walking pace.
 *
 * GUESS until calibrated in the car: [trackM], [wheelbaseM] and [steeringRatio] are typical
 * values for a car this size, not measured Cityray figures. The counters are 0..255 and wrap.
 * Left turns are positive.
 */
class WheelTurn(
    val trackM: Double = 1.56,
    val wheelbaseM: Double = 2.70,
    val steeringRatio: Double = 15.0,
    /** Teeth the two rear wheels must roll together before an estimate is made. */
    val minTeeth: Int = 12,
) {
    data class Estimate(
        /** Estimated steering wheel angle in degrees, left positive. */
        val steeringDeg: Double,
        /** Front wheel angle in degrees, left positive. */
        val wheelDeg: Double,
        /** Turn radius of the rear axle centre in metres, left positive, infinite when straight. */
        val radiusM: Double,
        val rearLeftTeeth: Int,
        val rearRightTeeth: Int,
    )

    private var last: IntArray? = null
    private var accL = 0
    private var accR = 0

    fun reset() { last = null; accL = 0; accR = 0 }

    /**
     * Feed the current counters (front left, front right, rear left, rear right). Returns a
     * new estimate once enough teeth have rolled by, otherwise null.
     */
    fun update(fl: Int, fr: Int, rl: Int, rr: Int): Estimate? {
        val now = intArrayOf(fl, fr, rl, rr)
        val prev = last
        last = now
        if (prev == null) return null
        accL += delta(prev[2], rl)
        accR += delta(prev[3], rr)
        if (accL + accR < minTeeth) return null
        val l = accL
        val r = accR
        accL = 0; accR = 0
        return estimate(l, r)
    }

    fun estimate(left: Int, right: Int): Estimate {
        val diff = (right - left).toDouble()
        val radius = if (diff == 0.0) Double.POSITIVE_INFINITY else trackM / 2 * (left + right) / diff
        val wheel = if (radius.isInfinite()) 0.0 else Math.toDegrees(atan(wheelbaseM / radius))
        return Estimate(wheel * steeringRatio, wheel, radius, left, right)
    }

    companion object {
        const val FRONT_LEFT = "WhlRotToothCntrFrntLe"
        const val FRONT_RIGHT = "WhlRotToothCntrFrntRi"
        const val REAR_LEFT = "WhlRotToothCntrReLe"
        const val REAR_RIGHT = "WhlRotToothCntrReRi"

        /** Forward movement of an 8-bit counter between two reads, allowing one wrap. Reversing is not handled. */
        fun delta(prev: Int, now: Int): Int {
            val d = (now - prev) and 0xFF
            return if (abs(d) > 200) 0 else d
        }
    }
}
