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
        /** A car function (see [CarFunction]) changed. Int values arrive as floats too. */
        fun onFunctionChanged(functionId: Int, zone: Int, value: Float) {}
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var car: Any? = null
    private var sensors: Any? = null
    private var sensorListener: Any? = null
    private var connectable: Any? = null
    private var functions: Any? = null
    private var info: Any? = null
    private var functionWatcher: Any? = null

    /** The last problem we hit, for display in debug tools. */
    var lastError: String? = null
        private set

    /** True when the ECARX classes exist on this device (the head unit, not a phone). */
    val isAvailable: Boolean
        get() = Reflect.classOrNull(CAR_CLASS) != null

    val isStarted: Boolean
        get() = sensors != null

    /** Connects to the car service and subscribes to every sensor in [ids]. */
    fun start(listener: Listener, ids: List<Int> = CarSensor.usable.map { it.id }): Boolean {
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

    /**
     * Watches car functions (climate, seat heating, ...) for changes. GUESS: the ids come
     * from other apps. Call after [start]. Returns false if the API refused.
     */
    fun watchFunctions(listener: Listener, ids: List<Int> = CarFunction.entries.map { it.id }): Boolean {
        val f = functionManager() ?: return false
        val watcherCls = Reflect.classOrNull(FUNCTION_WATCHER_CLASS) ?: return fail("IFunctionValueWatcher not found")
        val w = Reflect.proxy(watcherCls) { method, args ->
            val id = args.getOrNull(0) as? Int ?: return@proxy null
            val zone = args.getOrNull(1) as? Int ?: Zone.NONE
            val v = when (method) {
                "onFunctionValueChanged" -> (args.getOrNull(2) as? Number)?.toFloat()
                "onCustomizeFunctionValueChanged" -> (args.getOrNull(2) as? Number)?.toFloat()
                else -> null
            } ?: return@proxy null
            main.post { listener.onFunctionChanged(id, zone, v) }
            null
        }
        return try {
            val reg = Reflect.findMethod(f.javaClass, "registerFunctionValueWatcher", IntArray::class.java, watcherCls)
                ?: return fail("registerFunctionValueWatcher(int[], watcher) not found")
            reg.isAccessible = true
            val ok = reg.invoke(f, ids.toIntArray(), w) as? Boolean ?: false
            if (ok) functionWatcher = w else lastError = "registerFunctionValueWatcher refused"
            ok
        } catch (t: Throwable) {
            fail(describe(t))
        }
    }

    /** Current value of a car function, read-only. GUESS ids, see [CarFunction]. */
    fun functionValue(id: Int, zone: Int = Zone.NONE): Int? = managerCall(functionManager()) {
        (if (zone == Zone.NONE) Reflect.call(it, "getFunctionValue", id)
        else Reflect.call(it, "getFunctionValue", id, zone)) as? Int
    }

    /** Float ("customize") value of a car function, such as a temperature. */
    fun functionFloat(id: Int, zone: Int = Zone.NONE): Float? = managerCall(functionManager()) {
        ((if (zone == Zone.NONE) Reflect.call(it, "getCustomizeFunctionValue", id)
        else Reflect.call(it, "getCustomizeFunctionValue", id, zone)) as? Number)?.toFloat()
    }

    /** "active", "notactive", "notavailable", "error", or null. */
    fun functionStatus(id: Int, zone: Int = Zone.NONE): String? = managerCall(functionManager()) {
        (if (zone == Zone.NONE) Reflect.call(it, "isFunctionSupported", id)
        else Reflect.call(it, "isFunctionSupported", id, zone))?.toString()
    }

    /** A fixed fact about the car, see [CarInfo]. */
    fun carInfo(item: CarInfo): String? = managerCall(infoManager()) {
        if (item.isFloat) (Reflect.call(it, "getCarInfoFloat", item.id) as? Number)?.toString()
        else (Reflect.call(it, "getCarInfoInt", item.id) as? Number)?.toString()
    }

    fun carInfoStatus(item: CarInfo): String? = managerCall(infoManager()) {
        Reflect.call(it, "isCarInfoSupported", item.id)?.toString()
    }

    private fun functionManager(): Any? {
        functions?.let { return it }
        val c = car ?: return null
        return runCatching { Reflect.call(c, "getICarFunction") }.getOrNull()?.also { functions = it }
    }

    private fun infoManager(): Any? {
        info?.let { return it }
        val c = car ?: return null
        return runCatching { Reflect.call(c, "getCarInfoManager") }.getOrNull()?.also { info = it }
    }

    private inline fun <T> managerCall(manager: Any?, block: (Any) -> T?): T? {
        val m = manager ?: return null
        return try { block(m) } catch (t: Throwable) { lastError = describe(t); null }
    }

    fun stop() {
        val s = sensors
        val l = sensorListener
        if (s != null && l != null) runCatching { Reflect.call(s, "unregisterListener", l) }
        val f = functions
        val w = functionWatcher
        if (f != null && w != null) runCatching { Reflect.call(f, "unregisterFunctionValueWatcher", w) }
        functions = null
        info = null
        functionWatcher = null
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
        const val FUNCTION_WATCHER_CLASS = "com.ecarx.xui.adaptapi.car.base.ICarFunction\$IFunctionValueWatcher"

        fun hex(id: Int) = "0x" + Integer.toHexString(id)

        internal fun describe(t: Throwable): String {
            val root = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            return "${root.javaClass.simpleName}: ${root.message}"
        }
    }
}
