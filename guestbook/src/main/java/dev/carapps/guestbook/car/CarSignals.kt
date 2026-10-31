package dev.carapps.guestbook.car

import android.content.Context

/**
 * One car reading stamped on a signature, e.g. key "odometer_km", label "Odometer", value "12,345 km".
 * The key is stable (for saving), the label and value are for showing.
 */
data class CarReading(val key: String, val label: String, val value: String)

/**
 * Anything that can read the car. Add a new source here once we learn the Cityray's own hooks
 * (for example the ECARX signal service), and register it in [CarSignals.create].
 */
interface CarSignalSource {
    /** Short name shown on the status line, e.g. "Car API". */
    val name: String

    /** Called once when the app opens. */
    fun connect(context: Context) {}

    /** Called when the app closes. */
    fun disconnect() {}

    /** Reads whatever this source can right now. Must never throw; return an empty list instead. */
    fun read(): List<CarReading>

    /** One line for the status footer, e.g. "odometer ✓  outside temp ✗". */
    fun status(): String
}

/** Asks every source in turn and merges what they return. The first source to give a key wins. */
class CarSignals private constructor(private val sources: List<CarSignalSource>) {

    fun connect(context: Context) = sources.forEach { runCatching { it.connect(context) } }

    fun disconnect() = sources.forEach { runCatching { it.disconnect() } }

    fun snapshot(): List<CarReading> {
        val seen = HashSet<String>()
        val out = ArrayList<CarReading>()
        for (source in sources) {
            val readings = runCatching { source.read() }.getOrDefault(emptyList())
            for (r in readings) if (seen.add(r.key)) out.add(r)
        }
        return out
    }

    fun status(): String = sources.joinToString("   ·   ") { "${it.name}: ${runCatching { it.status() }.getOrDefault("error")}" }

    companion object {
        fun create(): CarSignals = CarSignals(
            listOf(
                StandardCarApiSource(),
                // Add the Cityray-specific source here when we have it.
            )
        )
    }
}
