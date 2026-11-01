package dev.carapps.tumbler

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.carapps.car.CarDecode
import dev.carapps.car.CarSensor
import dev.carapps.car.EcarxCar
import kotlin.math.abs

/**
 * Reads the two car signals Tumbler uses: the gear (so it only plays in Park) and the
 * steering wheel angle (the safe dial). Read-only: nothing here changes anything in the car.
 *
 * The steering angle's units and sign are not confirmed yet (CAR_NOTES: OBSERVED, read 0
 * while parked), so a two-step calibration measures them: wheel straight, then half a turn left.
 */
class Wheel(context: Context, private val onGear: (old: CarDecode.Gear, new: CarDecode.Gear) -> Unit) {

    private val car = EcarxCar(context)
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("wheel", Context.MODE_PRIVATE)

    var gear = CarDecode.Gear.UNKNOWN
        private set

    /** Latest steering angle in the car's own units, or null if we have no reading. */
    var raw: Float? = null
        private set

    private var seenMin = Float.MAX_VALUE
    private var seenMax = -Float.MAX_VALUE

    /** True once the steering reading has visibly changed, so we know it is live. */
    val live: Boolean
        get() = seenMax - seenMin > 2f

    val connected: Boolean
        get() = car.isStarted

    private var center = prefs.getFloat("center", 0f)
    private var halfTurn = prefs.getFloat("half", 0f)
    private var sign = prefs.getFloat("sign", 1f)

    val calibrated: Boolean
        get() = halfTurn > 0f

    /** Wheel position in whole turns from straight, right positive. 0 when we can't tell. */
    fun turns(): Float {
        val r = raw ?: return 0f
        if (!calibrated) return 0f
        return (r - center) / halfTurn * sign * 0.5f
    }

    /** Saves a calibration from a straight reading and a "half a turn left" reading. False if the wheel barely moved. */
    fun calibrate(straight: Float, left: Float): Boolean {
        val d = left - straight
        if (abs(d) < MIN_TURN) return false
        center = straight
        halfTurn = abs(d)
        sign = if (d < 0) 1f else -1f
        prefs.edit().putFloat("center", center).putFloat("half", halfTurn).putFloat("sign", sign).apply()
        return true
    }

    private val poll = object : Runnable {
        override fun run() {
            // Sensors may only push on change, so also ask for the latest value now and then.
            car.latestValue(CarSensor.STEERING_ANGLE.id)?.let { setSteer(decode(it)) }
            main.postDelayed(this, 50)
        }
    }

    fun start() {
        val ok = car.start(object : EcarxCar.Listener {
            override fun onEventChanged(sensorId: Int, event: Int) {
                if (sensorId == CarSensor.GEAR.id) setGear(CarDecode.gear(event))
            }

            override fun onValueChanged(sensorId: Int, value: Float) {
                if (sensorId == CarSensor.STEERING_ANGLE.id) setSteer(decode(value))
            }
        }, listOf(CarSensor.GEAR.id, CarSensor.STEERING_ANGLE.id))
        if (ok) {
            car.latestEvent(CarSensor.GEAR.id)?.let { setGear(CarDecode.gear(it)) }
            main.post(poll)
        }
    }

    fun stop() {
        main.removeCallbacks(poll)
        car.stop()
        gear = CarDecode.Gear.UNKNOWN
    }

    private fun setGear(g: CarDecode.Gear) {
        if (g == gear) return
        val old = gear
        gear = g
        onGear(old, g)
    }

    private fun setSteer(v: Float) {
        if (v.isNaN() || v.isInfinite()) return
        raw = v
        if (v < seenMin) seenMin = v
        if (v > seenMax) seenMax = v
    }

    /** Plain floats pass through. Integers packed into the float's bits are unpacked, keeping the sign. */
    private fun decode(value: Float): Float {
        if (value == 0f || value.isNaN()) return value
        val bits = java.lang.Float.floatToRawIntBits(value)
        val exponent = (bits ushr 23) and 0xFF
        if (exponent != 0) return value
        val magnitude = (bits and 0x7FFFFF).toFloat()
        return if (bits < 0) -magnitude else magnitude
    }

    companion object {
        /** Smallest change we accept as "the wheel turned" (5 degrees, or 5 raw steps). */
        const val MIN_TURN = 5f
    }
}
