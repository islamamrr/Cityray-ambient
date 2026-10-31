package dev.carapps.guestbook.car

import android.content.Context
import java.util.Locale

/**
 * Reads the standard Android car API (android.car.CarPropertyManager), if the head unit offers it.
 * Everything goes through reflection, so the app still builds and runs on units without it,
 * and any refusal (missing permission, unsupported property) just shows up as "✗" on the status line.
 */
class StandardCarApiSource : CarSignalSource {

    override val name = "Car API"

    private var car: Any? = null
    private var propertyManager: Any? = null
    private var connectError: String? = null
    private val lastResult = LinkedHashMap<String, Boolean>()

    private class Prop(val key: String, val label: String, val id: Int, val kind: Kind, val format: (Any) -> String)
    private enum class Kind { FLOAT, INT, STRING }

    private val props = listOf(
        Prop("odometer_km", "Odometer", 291504644, Kind.FLOAT) { String.format(Locale.US, "%,.0f km", it as Float) },
        Prop("outside_temp_c", "Outside", 291505923, Kind.FLOAT) { String.format(Locale.US, "%.0f °C", it as Float) },
        Prop("speed_kmh", "Speed", 291504647, Kind.FLOAT) { String.format(Locale.US, "%.0f km/h", (it as Float) * 3.6f) },
        Prop("gear", "Gear", 289408000, Kind.INT) { gearName(it as Int) },
        Prop("model", "Car", 286261506, Kind.STRING) { it.toString() },
    )

    override fun connect(context: Context) {
        try {
            val carClass = Class.forName("android.car.Car")
            val created = carClass.getMethod("createCar", Context::class.java).invoke(null, context)
                ?: throw IllegalStateException("createCar returned null")
            car = created
            // Older units need an explicit connect(); newer ones are connected already.
            runCatching {
                val connected = carClass.getMethod("isConnected").invoke(created) as Boolean
                if (!connected) carClass.getMethod("connect").invoke(created)
            }
            propertyManager = carClass.getMethod("getCarManager", String::class.java).invoke(created, "property")
            if (propertyManager == null) connectError = "no property service"
        } catch (e: ClassNotFoundException) {
            connectError = "not on this unit"
        } catch (e: Throwable) {
            connectError = (e.cause ?: e).javaClass.simpleName
        }
    }

    override fun disconnect() {
        runCatching { car?.let { it.javaClass.getMethod("disconnect").invoke(it) } }
        car = null
        propertyManager = null
    }

    override fun read(): List<CarReading> {
        val pm = propertyManager ?: return emptyList()
        val out = ArrayList<CarReading>()
        for (p in props) {
            val value = runCatching { readProp(pm, p) }.getOrNull()
            lastResult[p.label] = value != null
            if (value != null) out.add(CarReading(p.key, p.label, p.format(value)))
        }
        return out
    }

    private fun readProp(pm: Any, p: Prop): Any? {
        val cls = pm.javaClass
        return when (p.kind) {
            Kind.FLOAT -> cls.getMethod("getFloatProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(pm, p.id, 0)
            Kind.INT -> cls.getMethod("getIntProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(pm, p.id, 0)
            Kind.STRING -> {
                val value = cls.getMethod("getProperty", Class::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(pm, String::class.java, p.id, 0) ?: return null
                value.javaClass.getMethod("getValue").invoke(value)?.toString()?.takeIf { it.isNotBlank() }
            }
        }
    }

    override fun status(): String {
        connectError?.let { return it }
        if (lastResult.isEmpty()) return "connected"
        return lastResult.entries.joinToString("  ") { "${it.key.lowercase(Locale.US)} ${if (it.value) "✓" else "✗"}" }
    }

    private fun gearName(gear: Int): String = when (gear) {
        0x0001 -> "N"
        0x0002 -> "R"
        0x0004 -> "P"
        0x0008 -> "D"
        else -> gear.toString()
    }
}
