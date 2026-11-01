package dev.carapps.surprise

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.carapps.car.CarDecode
import dev.carapps.car.CarSensor
import dev.carapps.car.EcarxCar

/**
 * Reads the two car signals this app uses: the gear (to stay Park-only) and the steering
 * wheel angle. Read-only: nothing here changes anything in the car.
 */
class CarLink(context: Context, private val onGear: (old: CarDecode.Gear, new: CarDecode.Gear) -> Unit) {

    private val car = EcarxCar(context)
    private val main = Handler(Looper.getMainLooper())

    var gear = CarDecode.Gear.UNKNOWN
        private set

    /** Latest steering angle in the car's own units, or null if we have no reading. */
    var steerRaw: Float? = null
        private set

    private var steerMin = Float.MAX_VALUE
    private var steerMax = -Float.MAX_VALUE

    /** True once the steering reading has visibly changed, so we know it is live. */
    val steerMoved: Boolean
        get() = steerMax - steerMin > 2f

    val hasSteer: Boolean
        get() = steerRaw != null

    private val poll = object : Runnable {
        override fun run() {
            // Some sensors only push on change, so also ask for the latest value now and then.
            car.latestValue(CarSensor.STEERING_ANGLE.id)?.let { setSteer(decode(it)) }
            main.postDelayed(this, 100)
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
        steerRaw = v
        if (v < steerMin) steerMin = v
        if (v > steerMax) steerMax = v
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
}
