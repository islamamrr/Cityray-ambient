package dev.carapps.tumbler

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.carapps.car.AndroidCar
import dev.carapps.car.CarDecode
import dev.carapps.car.CarSensor
import dev.carapps.car.EcarxCar
import java.util.Locale
import kotlin.math.abs

/**
 * Reads what Tumbler needs from the car: whether it is parked, and the steering wheel angle.
 * Read-only: nothing here changes anything in the car.
 *
 * Each signal is read from every route we know (the ECARX API and Android's standard car
 * API), because neither is confirmed for steering yet. The first steering source that
 * visibly moves becomes the dial. A two-step calibration measures its units and sign.
 */
class Wheel(context: Context, private val onPark: (old: Park, new: Park) -> Unit) {

    enum class Park { PARKED, NOT_PARKED, UNKNOWN }

    /** One raw reading from one route, with the range we've seen so we can tell it's live. */
    class Signal(val name: String) {
        var value: Float? = null
            private set
        var error: String? = null
        var updates = 0
            private set
        private var lo = Float.MAX_VALUE
        private var hi = -Float.MAX_VALUE

        val moved: Boolean get() = hi - lo > 2f
        val range: String get() = if (updates == 0) "-" else "${fmt(lo)} .. ${fmt(hi)}"

        fun set(v: Float) {
            if (v.isNaN() || v.isInfinite()) return
            value = v
            updates++
            if (v < lo) lo = v
            if (v > hi) hi = v
        }

        fun reset() {
            value = null
            updates = 0
            lo = Float.MAX_VALUE
            hi = -Float.MAX_VALUE
        }
    }

    private val ecarx = EcarxCar(context)
    private val android = AndroidCar(context)
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("wheel", Context.MODE_PRIVATE)

    // Steering routes, in order of preference.
    val ecarxSteer = Signal("Car sensor 0x101000")
    val androidSteer = Signal("Android car steering")
    private val steerSources = listOf(ecarxSteer, androidSteer)

    // Shown on the Car signals screen only.
    val ecarxSteerSpeed = Signal("Car sensor 0x101100")
    val ecarxSpeed = Signal("Car sensor speed")
    val androidSpeed = Signal("Android car speed")

    var ecarxGearRaw: Int? = null
        private set
    var androidGearRaw: Int? = null
        private set
    var ecarxGear = CarDecode.Gear.UNKNOWN
        private set
    /** The car sensor's gear as asked for (polled), kept apart from the pushed one so neither hides the other. */
    private var ecarxGearPolled = CarDecode.Gear.UNKNOWN
    private var ecarxGearPolledRaw: Int? = null
    var androidGear = CarDecode.Gear.UNKNOWN
        private set

    var park = Park.UNKNOWN
        private set

    var started = false
        private set
    private var pollCount = 0
    private var androidFailures = 0

    /** The steering source that has moved, or null. */
    val active: Signal? get() = steerSources.firstOrNull { it.moved }

    /** The source calibration measures: the live one, else the first that has any reading. */
    private val calSource: Signal? get() = active ?: steerSources.firstOrNull { it.value != null }

    val live: Boolean get() = active != null
    val connected: Boolean get() = ecarx.isStarted || android.isConnected

    /** Raw reading of the source calibration would use. */
    val raw: Float? get() = calSource?.value

    val calibrated: Boolean
        get() {
            val s = active ?: return false
            return prefs.getFloat(key(s, "half"), 0f) > 0f
        }

    /** Wheel position in whole turns from straight, right positive. 0 when we can't tell. */
    fun turns(): Float {
        val s = active ?: return 0f
        val r = s.value ?: return 0f
        val half = prefs.getFloat(key(s, "half"), 0f)
        if (half <= 0f) return 0f
        val center = prefs.getFloat(key(s, "center"), 0f)
        val sign = prefs.getFloat(key(s, "sign"), 1f)
        return (r - center) / half * sign * 0.5f
    }

    /** Saves a calibration from a straight reading and a "half a turn left" reading. False if the wheel barely moved. */
    fun calibrate(straight: Float, left: Float): Boolean {
        val s = calSource ?: return false
        val d = left - straight
        if (abs(d) < MIN_TURN) return false
        prefs.edit()
            .putFloat(key(s, "center"), straight)
            .putFloat(key(s, "half"), abs(d))
            .putFloat(key(s, "sign"), if (d < 0) 1f else -1f)
            .apply()
        return true
    }

    private fun key(s: Signal, what: String) = "${s.name}:$what"

    fun start() {
        started = true
        val ids = listOf(CarSensor.GEAR.id, CarSensor.STEERING_ANGLE.id, CarSensor.STEERING_ANGLE_SPEED.id, CarSensor.SPEED.id)
        ecarx.start(object : EcarxCar.Listener {
            override fun onEventChanged(sensorId: Int, event: Int) = onEcarx(sensorId, event.toFloat(), event)
            override fun onValueChanged(sensorId: Int, value: Float) = onEcarx(sensorId, decode(value), null)
        }, ids)
        androidFailures = 0
        android.connect()
        main.post(poll)
    }

    fun stop() {
        started = false
        main.removeCallbacks(poll)
        ecarx.stop()
        android.disconnect()
        ecarxGear = CarDecode.Gear.UNKNOWN
        ecarxGearPolled = CarDecode.Gear.UNKNOWN
        androidGear = CarDecode.Gear.UNKNOWN
        park = Park.UNKNOWN
    }

    private fun onEcarx(id: Int, value: Float, event: Int?) {
        when (id) {
            CarSensor.STEERING_ANGLE.id -> ecarxSteer.set(value)
            CarSensor.STEERING_ANGLE_SPEED.id -> ecarxSteerSpeed.set(value)
            CarSensor.SPEED.id -> ecarxSpeed.set(value)
            CarSensor.GEAR.id -> {
                val e = event ?: value.toInt()
                ecarxGearRaw = e
                ecarxGear = CarDecode.gear(e)
                updatePark()
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!started) return
            pollCount++
            // Sensors may only push on change, so also ask for the latest values.
            ecarx.latestValue(CarSensor.STEERING_ANGLE.id)?.let { ecarxSteer.set(decode(it)) }
            if (pollCount % 5 == 0) {
                ecarx.latestValue(CarSensor.STEERING_ANGLE_SPEED.id)?.let { ecarxSteerSpeed.set(decode(it)) }
                ecarx.latestValue(CarSensor.SPEED.id)?.let { ecarxSpeed.set(decode(it)) }
                ecarx.latestEvent(CarSensor.GEAR.id)?.let {
                    ecarxGearPolledRaw = it
                    ecarxGearPolled = CarDecode.gear(it)
                }
            }
            if (android.isConnected && androidFailures < 50) {
                val steer = android.float(AndroidCar.PERF_STEERING_ANGLE)
                if (steer != null) androidSteer.set(steer) else {
                    androidFailures++
                    androidSteer.error = android.lastError
                }
                if (pollCount % 5 == 0) {
                    android.int(AndroidCar.GEAR_SELECTION)?.let {
                        androidGearRaw = it
                        androidGear = AndroidCar.gear(it)
                        updatePark()
                    }
                    android.float(AndroidCar.PERF_VEHICLE_SPEED)?.let { androidSpeed.set(it) }
                }
            } else if (!android.isConnected) {
                androidSteer.error = android.lastError
            }
            updatePark()
            main.postDelayed(this, 50)
        }
    }

    /**
     * Parked only when a gear reading says P and nothing says otherwise. Any other gear
     * reading, from either route, or the car rolling, counts as not parked.
     */
    private fun updatePark() {
        val gears = listOf(ecarxGear, ecarxGearPolled, androidGear).filter { it != CarDecode.Gear.UNKNOWN }
        val rolling = (ecarxSpeed.value ?: 0f) > 3f
        val next = when {
            rolling || gears.any { it != CarDecode.Gear.P } -> Park.NOT_PARKED
            gears.isNotEmpty() -> Park.PARKED
            else -> Park.UNKNOWN
        }
        if (next == park) return
        val old = park
        park = next
        onPark(old, next)
    }

    /** Lines for the Car signals screen. */
    fun diagnostics(): List<String> {
        val out = ArrayList<String>()
        out += "Park state: $park"
        out += "Gear, car sensor (pushed): " + (ecarxGearRaw?.let { "+0x${Integer.toHexString(it - CarSensor.GEAR.id)} = $ecarxGear" } ?: "no reading")
        out += "Gear, car sensor (asked): " + (ecarxGearPolledRaw?.let { "+0x${Integer.toHexString(it - CarSensor.GEAR.id)} = $ecarxGearPolled" } ?: "no reading")
        out += "Gear, Android car: " + (androidGearRaw?.let { "$it = $androidGear" } ?: (android.lastError ?: "no reading"))
        out += "Steering in use: " + (active?.name ?: "none has moved yet")
        for (s in listOf(ecarxSteer, androidSteer, ecarxSteerSpeed, ecarxSpeed, androidSpeed)) {
            val now = s.value?.let { fmt(it) } ?: "-"
            val err = if (s.updates == 0 && s.error != null) "  (${s.error})" else ""
            out += "${s.name}: $now   seen ${s.range}   x${s.updates}$err"
        }
        out += "Car sensor status, steering: " + (ecarx.supportStatus(CarSensor.STEERING_ANGLE.id) ?: ecarx.lastError ?: "-")
        return out
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

        fun fmt(f: Float): String = String.format(Locale.US, "%.1f", f)
    }
}
