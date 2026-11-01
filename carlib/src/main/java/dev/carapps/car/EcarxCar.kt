package dev.carapps.car

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Read-only access to the head unit's car sensors through the ECARX car API
 * (com.ecarx.xui.adaptapi.car.Car), which ships in the Cityray system image.
 *
 * Only reads data. Nothing here changes a car setting.
 *
 * Usage:
 *   val car = EcarxCar(context)
 *   car.start(listener)      // listener calls arrive on the main thread
 *   car.latestValue(CarSensor.SPEED.id)
 *   car.stop()
 */
class EcarxCar(context: Context) {

    interface Listener {
        fun onConnectionChanged(connected: Boolean) {}
        /** Continuous sensors such as speed or RPM. */
        fun onValueChanged(sensorId: Int, value: Float) {}
        /** Event sensors such as gear or ignition. */
        fun onEventChanged(sensorId: Int, event: Int) {}
        fun onSupportChanged(sensorId: Int, status: String) {}
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var car: Any? = null
    private var sensors: Any? = null
    private var sensorListener: Any? = null
    private var connectable: Any? = null

    /** The last problem we hit, for display in debug tools. */
    var lastError: String? = null
        private set

    /** True when the ECARX classes exist on this device (the head unit, not a phone). */
    val isAvailable: Boolean
        get() = Reflect.classOrNull(CAR_CLASS) != null

    val isStarted: Boolean
        get() = sensors != null

    /** Connects to the car service and subscribes to every sensor in [ids]. */
    fun start(listener: Listener, ids: List<Int> = CarSensor.entries.map { it.id }): Boolean {
        stop()
        try {
            val carCls = Reflect.classOrNull(CAR_CLASS) ?: return fail("ECARX car API not found on this device")
            val create = Reflect.findMethod(carCls, "create", Context::class.java)
                ?: return fail("Car.create(Context) not found")
            val c = create.invoke(null, appContext) ?: return fail("Car.create returned null")
            car = c

            watchConnection(c, listener)

            val s = Reflect.call(c, "getSensorManager") ?: return fail("getSensorManager returned null")
            sensors = s

            val listenerCls = Reflect.classOrNull(SENSOR_LISTENER_CLASS)
                ?: return fail("ISensorListener not found")
            val proxy = Reflect.proxy(listenerCls) { method, args ->
                val id = args.getOrNull(0) as? Int ?: return@proxy null
                when (method) {
                    "onSensorValueChanged" -> {
                        val v = (args.getOrNull(1) as? Number)?.toFloat() ?: return@proxy null
                        main.post { listener.onValueChanged(id, v) }
                    }
                    "onSensorEventChanged" -> {
                        val e = (args.getOrNull(1) as? Number)?.toInt() ?: return@proxy null
                        main.post { listener.onEventChanged(id, e) }
                    }
                    "onSensorSupportChanged" -> {
                        val st = args.getOrNull(1)?.toString() ?: "?"
                        main.post { listener.onSupportChanged(id, st) }
                    }
                }
                null
            }
            sensorListener = proxy

            val register = Reflect.findMethod(s.javaClass, "registerListener", listenerCls, Integer.TYPE)
                ?: return fail("registerListener(listener, int) not found")
            register.isAccessible = true
            val failed = ids.filter { id ->
                runCatching { register.invoke(s, proxy, id) as? Boolean ?: false }.getOrDefault(false).not()
            }
            lastError = if (failed.isEmpty()) null
            else "registerListener refused: " + failed.joinToString { CarSensor.byId(it)?.name ?: hex(it) }
            return true
        } catch (t: Throwable) {
            return fail(describe(t))
        }
    }

    /** "active", "notactive", "notavailable", "error", or null if unknown. */
    fun supportStatus(id: Int): String? = sensorCall { Reflect.call(it, "isSensorSupported", id)?.toString() }

    /** Latest value of a continuous sensor, such as speed. */
    fun latestValue(id: Int): Float? = sensorCall { (Reflect.call(it, "getSensorLatestValue", id) as? Number)?.toFloat() }

    /** Latest value of an event sensor, such as gear. */
    fun latestEvent(id: Int): Int? = sensorCall { (Reflect.call(it, "getSensorEvent", id) as? Number)?.toInt() }

    fun stop() {
        val s = sensors
        val l = sensorListener
        if (s != null && l != null) runCatching { Reflect.call(s, "unregisterListener", l) }
        connectable?.let { c -> runCatching { Reflect.call(c, "unregisterConnectWatcher") } }
        sensors = null
        sensorListener = null
        connectable = null
        car = null
    }

    private fun watchConnection(c: Any, listener: Listener) {
        val connCls = Reflect.classOrNull(CONNECTABLE_CLASS) ?: return
        if (!connCls.isInstance(c)) return
        val watcherCls = Reflect.classOrNull(CONNECT_WATCHER_CLASS) ?: return
        val watcher = Reflect.proxy(watcherCls) { method, _ ->
            when (method) {
                "onConnected" -> main.post { listener.onConnectionChanged(true) }
                "onDisConnected" -> main.post { listener.onConnectionChanged(false) }
            }
            null
        }
        runCatching { Reflect.call(c, "registerConnectWatcher", watcher) }
            .onSuccess { connectable = c }
    }

    private inline fun <T> sensorCall(block: (Any) -> T?): T? {
        val s = sensors ?: return null
        return try { block(s) } catch (t: Throwable) { lastError = describe(t); null }
    }

    private fun fail(msg: String): Boolean {
        lastError = msg
        return false
    }

    companion object {
        const val CAR_CLASS = "com.ecarx.xui.adaptapi.car.Car"
        const val SENSOR_LISTENER_CLASS = "com.ecarx.xui.adaptapi.car.sensor.ISensor\$ISensorListener"
        const val CONNECTABLE_CLASS = "com.ecarx.xui.adaptapi.binder.IConnectable"
        const val CONNECT_WATCHER_CLASS = "com.ecarx.xui.adaptapi.binder.IConnectable\$IConnectWatcher"

        fun hex(id: Int) = "0x" + Integer.toHexString(id)

        internal fun describe(t: Throwable): String {
            val root = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            return "${root.javaClass.simpleName}: ${root.message}"
        }
    }
}
