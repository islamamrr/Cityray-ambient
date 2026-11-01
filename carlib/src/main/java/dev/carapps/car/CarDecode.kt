package dev.carapps.car

/**
 * Turns raw ECARX sensor values into meaning, as measured with Car Probe on a Cityray.
 * Each decoder says how sure we are.
 */
object CarDecode {

    /** OTHER is a gear reading we don't recognise. Treat it as "not in Park". UNKNOWN means no reading. */
    enum class Gear { P, R, N, D, OTHER, UNKNOWN }

    /**
     * P (+0x40) CONFIRMED in the car on 2026-10-31. R, N and D were assigned from the
     * order of shifting and are not certain: on 2026-11-01 reverse did not register as
     * leaving Park. So any value other than P comes back as at least [Gear.OTHER].
     */
    fun gear(event: Int): Gear = when (event - CarSensor.GEAR.id) {
        0x40 -> Gear.P
        0x10 -> Gear.R
        0x20 -> Gear.N
        0x30 -> Gear.D
        else -> Gear.OTHER
    }

    /**
     * OBSERVED. Ignition events are 0x200100 plus a state number. We have only seen +7
     * (car on, parked, engine probably off). Other states are not mapped yet.
     */
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

    /** OBSERVED that RPM is packed. Read 1 with the engine probably off. Recheck with it running. */
    fun rpm(value: Float): Float = number(value)
}
