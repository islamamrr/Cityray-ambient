package dev.carapps.surprise

import android.content.Context
import kotlin.math.abs

/**
 * Turns the raw steering angle into game input: -1 is "half a turn left", +1 is
 * "half a turn right". We don't know the sensor's units or sign for sure, so a quick
 * calibration (straight, then left) measures both.
 */
class Steering(context: Context) {

    private val prefs = context.getSharedPreferences("wheel", Context.MODE_PRIVATE)

    var center = prefs.getFloat("center", 0f)
        private set
    private var range = prefs.getFloat("range", 0f)
    private var sign = prefs.getFloat("sign", 1f)

    val calibrated: Boolean
        get() = range > 0f

    fun input(raw: Float): Float {
        if (!calibrated) return 0f
        val s = ((raw - center) / range * sign).coerceIn(-1.5f, 1.5f)
        return if (abs(s) < 0.03f) 0f else s
    }

    /** Saves a calibration from a straight reading and a "turned left" reading. False if the wheel barely moved. */
    fun calibrate(straight: Float, left: Float): Boolean {
        val d = left - straight
        if (abs(d) < MIN_TURN) return false
        center = straight
        range = abs(d)
        sign = if (d < 0) 1f else -1f
        prefs.edit().putFloat("center", center).putFloat("range", range).putFloat("sign", sign).apply()
        return true
    }

    companion object {
        /** Smallest change we accept as "the wheel turned" (5 degrees, or 5 raw steps). */
        const val MIN_TURN = 5f
    }
}
