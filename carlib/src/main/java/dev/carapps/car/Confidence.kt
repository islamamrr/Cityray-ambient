package dev.carapps.car

/**
 * How sure we are about a car hook on the Cityray (G426_J1, Android 11).
 * Every id and decoder in this library carries one of these, so apps can tell a tested
 * fact from a lead we still have to check with Car Probe.
 */
enum class Confidence {
    /** Measured in Islam's car and the meaning was checked (for example, gear P/R/N/D). */
    CONFIRMED,

    /** Seen working in the car, but the meaning of the value isn't pinned down yet. */
    OBSERVED,

    /** Found in other apps' code or in ECARX interface names. Not tested in our car yet. */
    GUESS,

    /** Tested in the car and it doesn't work there. */
    NOT_AVAILABLE,
}
