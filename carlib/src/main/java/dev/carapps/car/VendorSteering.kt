package dev.carapps.car

/**
 * Steering wheel angle from the vehicle HAL's vendor properties.
 *
 * CONFIRMED 2026-11-12 over adb (parked, ignition on, wheel turned lock to lock): the HAL
 * pushes these live. They are NOT the ECARX car_signal SteerWhlSnsrAg (that one is HAL
 * property 0x21607D07 and stays 0.0). See CAR_NOTES.md, "Steering angle found".
 *
 * Who can read them: shell uid, e.g. `cmd car_service get-property-value 557849839 0`.
 * A normal app is refused by Android's car service: vendor properties need
 * android.car.permission.CAR_VENDOR_EXTENSION (signature|privileged). [readViaAndroid]
 * exists to show that refusal in Car Probe.
 */
object VendorSteering {

    /** CLUSTER_ARHUD_STEERWHLSNSRAG: steering wheel angle sent to the AR-HUD. int32. CONFIRMED live. */
    const val ARHUD_ANGLE = 0x21401CEF
    /** CLUSTER_ARHUD_STEERWHLSNSRAGSPD: angle speed, 14-bit signed. OBSERVED. */
    const val ARHUD_ANGLE_SPEED = 0x21401CF0
    /** APA_PINIONSTEERAGGROUPPINIONSTEERAG1: parking-assist pinion angle, ~2-3 deg off the wheel angle. CONFIRMED live. */
    const val APA_PINION_ANGLE = 0x2140164C

    /** Degrees per raw step. GUESS: SPA scaling 1/1024 rad. Fits a ~+-480 deg lock-to-lock range. */
    const val DEG_PER_STEP = 180.0 / Math.PI / 1024.0

    /** Raw 15-bit two's-complement value to signed steps. */
    fun signed15(raw: Int): Int {
        val r = raw and 0x7FFF
        return if (r >= 0x4000) r - 0x8000 else r
    }

    /** Raw 14-bit two's-complement value to signed steps (angle speed). */
    fun signed14(raw: Int): Int {
        val r = raw and 0x3FFF
        return if (r >= 0x2000) r - 0x4000 else r
    }

    /** Steering wheel angle in degrees. Left (counter-clockwise) is positive: CONFIRMED by a sign check. */
    fun degrees(raw: Int): Double = signed15(raw) * DEG_PER_STEP

    /**
     * Tries the normal-app route: CarPropertyManager.getIntProperty(id, 0).
     * Returns the raw value, or null with the reason in [AndroidCar.lastError].
     * Expected on the Cityray: refused (CAR_VENDOR_EXTENSION).
     */
    fun readViaAndroid(car: AndroidCar, id: Int = ARHUD_ANGLE): Int? {
        if (!car.connect()) return null
        return car.int(id)
    }

    /** Pulls the int out of a `cmd car_service get-property-value` line, e.g. "...int32Values: [74],...". */
    fun parseCmdLine(line: String): Int? =
        INT32.find(line)?.groupValues?.get(1)?.trim()?.split(',')?.firstOrNull()?.trim()?.toIntOrNull()

    /** The shell command that reads one property; ids must be decimal on this build. */
    fun cmdFor(id: Int): String = "cmd car_service get-property-value $id 0"

    private val INT32 = Regex("""int32Values: \[([^\]]*)]""")
}
