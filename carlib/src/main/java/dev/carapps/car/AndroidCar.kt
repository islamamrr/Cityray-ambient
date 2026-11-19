package dev.carapps.car

import android.content.Context

/**
 * Android's standard car API (android.car.Car + CarPropertyManager), a second route to
 * car data next to ECARX. Looked up by reflection, so apps still build and run without it.
 *
 * GUESS: the Cityray is an ECARX build of Android 11, and we don't know yet whether it ships
 * the car service, or which properties it lets a normal app read. Every call reports failure
 * as null plus [lastError], never a crash. Read-only.
 */
class AndroidCar(context: Context) {

    private val appContext = context.applicationContext
    private var car: Any? = null
    private var props: Any? = null

    var lastError: String? = null
        private set

    val isConnected: Boolean
        get() = props != null

    fun connect(): Boolean {
        if (props != null) return true
        return try {
            val cls = Reflect.classOrNull("android.car.Car") ?: return fail("android.car not on this device")
            val c = Reflect.findMethod(cls, "createCar", Context::class.java)?.invoke(null, appContext)
                ?: return fail("Car.createCar returned null")
            car = c
            // Older builds need connect(); newer ones are connected on create.
            runCatching {
                val connected = Reflect.call(c, "isConnected") as? Boolean ?: true
                if (!connected) Reflect.call(c, "connect")
            }
            props = Reflect.findMethod(cls, "getCarManager", String::class.java)?.invoke(c, "property")
                ?: return fail("no property service")
            lastError = null
            true
        } catch (t: Throwable) {
            fail(EcarxCar.describe(t))
        }
    }

    fun disconnect() {
        car?.let { c -> runCatching { Reflect.call(c, "disconnect") } }
        car = null
        props = null
    }

    fun float(id: Int, area: Int = 0): Float? = read { pm ->
        Reflect.findMethod(pm.javaClass, "getFloatProperty", Integer.TYPE, Integer.TYPE)?.invoke(pm, id, area) as? Float
    }

    fun int(id: Int, area: Int = 0): Int? = read { pm ->
        Reflect.findMethod(pm.javaClass, "getIntProperty", Integer.TYPE, Integer.TYPE)?.invoke(pm, id, area) as? Int
    }

    /**
     * Every property the Android car service lists for this app, as id -> Java type name
     * ("Integer", "Float", "Integer[]", ...). Includes vendor properties (ids starting 0x2).
     * GUESS: what the Cityray lists is unknown until Car Probe shows it.
     */
    fun propertyList(): Map<Int, String> = read { pm ->
        val list = Reflect.call(pm, "getPropertyList") as? List<*> ?: return@read null
        val out = LinkedHashMap<Int, String>()
        for (cfg in list) {
            if (cfg == null) continue
            val id = (Reflect.call(cfg, "getPropertyId") as? Number)?.toInt() ?: continue
            val type = runCatching { (Reflect.call(cfg, "getPropertyType") as? Class<*>)?.simpleName }.getOrNull() ?: "?"
            out[id] = type
        }
        out
    } ?: emptyMap()

    /** Any property as text, whatever its type, or null if the read failed. Area 0. */
    fun anyValue(id: Int, type: String): String? = read { pm ->
        when (type) {
            "Float" -> Reflect.findMethod(pm.javaClass, "getFloatProperty", Integer.TYPE, Integer.TYPE)?.invoke(pm, id, 0)?.toString()
            "Integer" -> Reflect.findMethod(pm.javaClass, "getIntProperty", Integer.TYPE, Integer.TYPE)?.invoke(pm, id, 0)?.toString()
            "Boolean" -> Reflect.findMethod(pm.javaClass, "getBooleanProperty", Integer.TYPE, Integer.TYPE)?.invoke(pm, id, 0)?.toString()
            else -> {
                val v = Reflect.findMethod(pm.javaClass, "getProperty", Integer.TYPE, Integer.TYPE)?.invoke(pm, id, 0)
                when (val x = v?.let { Reflect.call(it, "getValue") }) {
                    is IntArray -> x.joinToString(",")
                    is FloatArray -> x.joinToString(",")
                    is ByteArray -> x.joinToString("") { b -> "%02x".format(b) }
                    is Array<*> -> x.joinToString(",")
                    else -> x?.toString()
                }
            }
        }
    }

    private inline fun <T> read(block: (Any) -> T?): T? {
        val pm = props ?: return null
        return try {
            block(pm)
        } catch (t: Throwable) {
            lastError = EcarxCar.describe(t)
            null
        }
    }

    private fun fail(msg: String): Boolean {
        lastError = msg
        return false
    }

    companion object {
        /** VehicleProperty ids from AOSP (types.hal). */
        const val GEAR_SELECTION = 0x11400400
        const val CURRENT_GEAR = 0x11400401
        const val PERF_VEHICLE_SPEED = 0x11600207
        const val PERF_STEERING_ANGLE = 0x11600209
        /** GUESS: the button app probes these two for indicators. See CarDecode.turnSignalAosp. */
        const val TURN_SIGNAL_STATE = 0x11400408
        const val HAZARD_LIGHTS_STATE = 0x11400E03

        /** AOSP VehicleGear: N 1, R 2, P 4, D 8 (manual gears are 16 and up). */
        fun gear(value: Int): CarDecode.Gear = when (value) {
            1 -> CarDecode.Gear.N
            2 -> CarDecode.Gear.R
            4 -> CarDecode.Gear.P
            8 -> CarDecode.Gear.D
            0 -> CarDecode.Gear.UNKNOWN
            else -> CarDecode.Gear.OTHER
        }
    }
}
