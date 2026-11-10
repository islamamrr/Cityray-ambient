package dev.carapps.car

/**
 * Turns raw ECARX sensor values into meaning, as measured with Car Probe on a Cityray.
 * Each decoder says how sure we are.
 */
object CarDecode {

    /** OTHER is a gear reading we don't recognise. Treat it as "not in Park". UNKNOWN means no reading. */
    enum class Gear { P, R, N, D, OTHER, UNKNOWN }

    /**
     * CONFIRMED in the car on 2026-11-08, gear by gear: +0x30 P, +0x40 R, +0x10 N, +0x20 D.
     * The button app agrees: it treats 0x200240 as "reverse on". (The 2026-10-31 map was
     * shifted by one, which is why reverse looked like Park on 2026-11-01.)
     */
    fun gear(event: Int): Gear = when (event - CarSensor.GEAR.id) {
        0x30 -> Gear.P
        0x40 -> Gear.R
        0x10 -> Gear.N
        0x20 -> Gear.D
        else -> Gear.OTHER
    }

    /** ECARX ignition states, IGNITION_STATE_* = 0x200100 + step. */
    enum class Ignition(val step: Int) {
        UNDEFINED(1), LOCK(2), OFF(3), ACC(4), ON(5), START(6), DRIVING(7), OTHER(-1);

        /** True when the car is powered (ON, START or engine running). */
        val isOn: Boolean get() = this == ON || this == START || this == DRIVING
    }

    /**
     * OBSERVED in the car: +3, +5 and +7. The names are ECARX's own (IGNITION_STATE_*):
     * +3 OFF, +5 ON, +7 DRIVING (engine running). Both the button app and gbinder treat
     * +7 as "ignition on". +1/+2/+4/+6 (undefined, lock, accessory, cranking) not seen yet.
     */
    fun ignition(event: Int): Ignition =
        Ignition.entries.firstOrNull { it.step == event - CarSensor.IGNITION.id } ?: Ignition.OTHER

    /** The raw step after 0x200100, for logs. */
    fun ignitionStep(event: Int): Int = event - CarSensor.IGNITION.id

    /**
     * OBSERVED. RPM and fuel send an integer packed into the float's bits, which shows up
     * as a tiny number such as 1.4E-45. Returns that integer, or null for a normal float.
     */
    fun packedInt(value: Float): Int? {
        if (value == 0f || value.isNaN()) return null
        val bits = java.lang.Float.floatToRawIntBits(value)
        val exponent = (bits ushr 23) and 0xFF
        return if (exponent == 0) bits and 0x7FFFFF else null
    }

    /** A sensor value as a number: the packed integer if there is one, else the float. */
    fun number(value: Float): Float = packedInt(value)?.toFloat() ?: value

    /** GUESS. Fuel level read about 25020; we assume millilitres, so about 25.0 litres. */
    fun fuelLitres(value: Float): Float = number(value) / 1000f

    /**
     * GUESS. Sensor 0x404500 (IVirtualSensor FUEL_PERCENTAGE) is the tank in percent. The
     * button app turns it into litres with this Cityray calibration and caps it at the 51 L
     * tank: litres = 4.4 + percent * 0.466. (Preface: 4 + p * 0.45, cap 49; Atlas: 4 + p * 0.5, cap 54.)
     */
    fun fuelLitresFromPercent(percent: Float): Float = (4.4f + percent * 0.466f).coerceIn(0f, 51f)

    /** Turn indicators as (left, right, hazard). */
    data class Turn(val left: Boolean, val right: Boolean, val hazard: Boolean)

    /**
     * GUESS. AOSP TURN_SIGNAL_STATE (0x11400408, see [AndroidCar.TURN_SIGNAL_STATE]): 0 none,
     * 1 right, 2 left in AOSP. The button app also accepts 1 left / 2 right / 3 both, so check
     * the direction in the car. 255 or negative means "no reading".
     */
    fun turnSignalAosp(value: Int): Turn? = when {
        value < 0 || value == 255 -> null
        value == 0 -> Turn(false, false, false)
        value == 3 -> Turn(true, true, true)
        else -> Turn(left = value and 2 != 0, right = value and 1 != 0, hazard = false)
    }

    /**
     * GUESS. Vendor lamp switches ([VendorSignal.BCM_TURN_LEFT] and friends): 0 off,
     * 1..254 on, 255 or negative unknown. That is how the button app reads them.
     */
    fun lampSwitch(value: Int?): Boolean? = when {
        value == null || value < 0 || value == 255 -> null
        else -> value != 0
    }

    /** OBSERVED that RPM is packed. Read 1 with the engine probably off. Recheck with it running. */
    fun rpm(value: Float): Float = number(value)
}
