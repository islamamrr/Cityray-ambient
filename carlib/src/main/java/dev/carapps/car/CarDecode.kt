package dev.carapps.car

/**
 * Turns raw ECARX sensor values into meaning, as measured with Car Probe on a Cityray.
 */
object CarDecode {

    enum class Gear { P, R, N, D, UNKNOWN }

    /**
     * Gear events are 0x200200 plus a step of 0x10: +0x40 P, +0x10 R, +0x20 N, +0x30 D.
     * Confirmed in the car on 2026-10-31.
     */
    fun gear(event: Int): Gear = when (event - CarSensor.GEAR.id) {
        0x40 -> Gear.P
        0x10 -> Gear.R
        0x20 -> Gear.N
        0x30 -> Gear.D
        else -> Gear.UNKNOWN
    }

    /** Ignition events are 0x200100 plus a state number. Seen so far: +7. Meaning not mapped yet. */
    fun ignitionStep(event: Int): Int = event - CarSensor.IGNITION.id

    /**
     * Some sensors (RPM, fuel) send an integer packed into the float's bits, which shows
     * up as a tiny number such as 1.4E-45. This returns the integer when that happens.
     */
    fun packedInt(value: Float): Int? {
        if (value == 0f || value.isNaN()) return null
        val bits = java.lang.Float.floatToRawIntBits(value)
        val exponent = (bits ushr 23) and 0xFF
        return if (exponent == 0) bits and 0x7FFFFF else null
    }
}
