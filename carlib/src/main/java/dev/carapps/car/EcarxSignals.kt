package dev.carapps.car

import android.content.Context

/**
 * Raw car signals through ECARX's "car_signal" service (ecarx.car.hardware.signal.CarSignalManager).
 * This is the layer UNDER the sensor API: the individual CAN signals, each with a name such as
 * SignalId_VehSpdLgtA (speed) or SignalId_ExtrLtgStsTurnIndrLe (left indicator). A sensor that
 * is dead in [EcarxCar] can still have a live raw signal here.
 *
 * GUESS until tested in the car. How to reach it is public knowledge from open-source Geely
 * apps: the sensor manager returned by Car.create(...).getSensorManager() is an AbsCarSignal,
 * its getECarXCar() gives the platform object, and getCarManager("car_signal") gives the
 * manager. The signal ids are static fields named SignalId_* on the manager class, so the
 * list always matches the car's own firmware. The service only keeps values for signals
 * somebody subscribed to, so [subscribe] before trusting [value]. Read-only.
 */
class EcarxSignals(context: Context) {

    private val appContext = context.applicationContext
    private var manager: Any? = null
    private var callback: Any? = null
    private var proxy: Any? = null

    var lastError: String? = null
        private set

    /** How we got the manager, for debug screens. */
    var route: String = "-"
        private set

    val isConnected: Boolean get() = manager != null

    /** Signal name (without the SignalId_ prefix) -> id, from the car's own class. Empty off the car. */
    val ids: Map<String, Int> by lazy {
        val cls = Reflect.classOrNull(MANAGER_CLASS) ?: return@lazy emptyMap()
        val out = sortedMapOf<String, Int>()
        for (f in cls.fields) {
            if (!f.name.startsWith(PREFIX) || f.type != Integer.TYPE) continue
            runCatching { out[f.name.removePrefix(PREFIX)] = f.getInt(null) }
        }
        out
    }

    private val names: Map<Int, String> by lazy { ids.entries.associate { it.value to it.key } }

    fun name(id: Int): String = names[id] ?: id.toString()

    /**
     * Gets the signal manager. [sensorManager] is EcarxCar's sensor object when a car is
     * already started (the short route); without it we start ECARX's own proxy, which
     * connects a moment later, so check [isConnected] again after a second.
     */
    fun connect(car: EcarxCar? = null): Boolean {
        if (manager != null) return true
        try {
            val sensors = car?.sensorManager()
            if (sensors != null) {
                val platform = runCatching { Reflect.call(sensors, "getECarXCar") }.getOrNull()
                if (platform != null) {
                    val m = Reflect.call(platform, "getCarManager", serviceName())
                    if (m != null && Reflect.classOrNull(MANAGER_CLASS)?.isInstance(m) == true) {
                        manager = m
                        route = "sensor manager (${sensors.javaClass.simpleName})"
                        lastError = null
                        return true
                    }
                    lastError = "getCarManager gave ${m?.javaClass?.name}"
                } else {
                    lastError = "sensor manager ${sensors.javaClass.name} has no ECarXCar"
                }
            }
            return connectByProxy()
        } catch (t: Throwable) {
            lastError = EcarxCar.describe(t)
            return false
        }
    }

    /** The route gbinder uses: ECarXCarProxy hands us the manager when its service connects. */
    private fun connectByProxy(): Boolean {
        if (proxy != null) return manager != null
        val proxyCls = Reflect.classOrNull(PROXY_CLASS) ?: return fail("ECarXCarProxy not on this device")
        val methodCls = Reflect.classOrNull(PROXY_METHOD_CLASS) ?: return fail("ECarXCarProxyMethod not found")
        val handler = Reflect.proxy(methodCls) { method, args ->
            if (method == "onECarXCarServiceConnected") {
                args.getOrNull(1)?.let { manager = it; route = "ECarXCarProxy"; lastError = null }
            } else if (method == "onECarXCarServiceDeath") {
                manager = null
            }
            null
        }
        val ctor = proxyCls.constructors.firstOrNull { it.parameterTypes.size == 2 }
            ?: return fail("ECarXCarProxy constructor not found")
        val p = ctor.newInstance(appContext, handler)
        proxy = p
        runCatching { Reflect.call(p, "initECarXCar") }
        if (manager == null) manager = runCatching { Reflect.call(p, "getCarSignalManager") }.getOrNull()
            ?.also { route = "ECarXCarProxy" }
        if (manager == null) lastError = "waiting for ECarXCarProxy to connect"
        return manager != null
    }

    /** Current raw value of one signal, or null if the call failed. */
    fun value(id: Int): Int? {
        val m = manager ?: return null
        return try {
            (Reflect.call(m, "getSignalValue", id) as? Number)?.toInt()
        } catch (t: Throwable) {
            lastError = EcarxCar.describe(t)
            null
        }
    }

    /**
     * Subscribes to [signalIds]. [onChange] gets (id, value) on a binder thread, not the main
     * thread. Replaces any earlier subscription. Returns false if the service refused.
     */
    fun subscribe(signalIds: Collection<Int>, onChange: (id: Int, value: Any?) -> Unit): Boolean {
        val m = manager ?: return fail("not connected")
        unsubscribe()
        return try {
            val cbCls = Reflect.classOrNull(CALLBACK_CLASS) ?: return fail("CarSignalEventCallback not found")
            val filterCls = Reflect.classOrNull(FILTER_CLASS) ?: return fail("SignalFilter not found")
            val filter = filterCls.getDeclaredConstructor().newInstance()
            val add = Reflect.findMethod(filterCls, "add", 1) ?: return fail("SignalFilter.add not found")
            for (id in signalIds) add.invoke(filter, id)
            val cb = Reflect.proxy(cbCls) { method, args ->
                if (method == "onChangeEvent") {
                    val v = args.getOrNull(0)
                    if (v != null) runCatching {
                        val id = (Reflect.call(v, "getPropertyId") as Number).toInt()
                        onChange(id, Reflect.call(v, "getValue"))
                    }
                }
                null
            }
            Reflect.call(m, "registerCallback", cb, filter)
            callback = cb
            true
        } catch (t: Throwable) {
            fail(EcarxCar.describe(t))
        }
    }

    fun unsubscribe() {
        val m = manager
        val cb = callback
        if (m != null && cb != null) runCatching { Reflect.call(m, "unregisterCallback", cb) }
        callback = null
    }

    fun disconnect() {
        unsubscribe()
        proxy?.let { p -> runCatching { Reflect.call(p, "cleanup") } }
        proxy = null
        manager = null
    }

    private fun serviceName(): String = runCatching {
        Reflect.classOrNull(PLATFORM_CLASS)?.getField("SIGNAL_SERVICE")?.get(null) as? String
    }.getOrNull() ?: "car_signal"

    private fun fail(msg: String): Boolean {
        lastError = msg
        return false
    }

    companion object {
        const val MANAGER_CLASS = "ecarx.car.hardware.signal.CarSignalManager"
        const val CALLBACK_CLASS = "ecarx.car.hardware.signal.CarSignalManager\$CarSignalEventCallback"
        const val FILTER_CLASS = "ecarx.car.hardware.signal.SignalFilter"
        const val PLATFORM_CLASS = "ecarx.car.ECarXCar"
        const val PROXY_CLASS = "com.ecarx.xui.adaptapi.ECarXCarProxy"
        const val PROXY_METHOD_CLASS = "com.ecarx.xui.adaptapi.ECarXCarProxy\$ECarXCarProxyMethod"
        const val PREFIX = "SignalId_"

        /**
         * Signal names other open-source Geely apps rely on. GUESS for the Cityray: check that
         * each exists in [ids] before use.
         */
        const val SPEED = "VehSpdLgtA"
        const val RPM = "EngNSafeEngN"
        const val TURN_LEFT = "ExtrLtgStsTurnIndrLe"
        const val TURN_RIGHT = "ExtrLtgStsTurnIndrRi"
        const val HIGH_BEAM = "ExtrLtgStsHiBeam"
        const val LOW_BEAM = "ExtrLtgStsLoBeam"
        const val BATTERY_VOLTAGE = "VehBattUSysU"

        /** Name fragments worth showing first when hunting for steering and indicators. */
        val INTERESTING = Regex("Steer|Pinion|WhlAg|TurnInd|Indcr|ExtrLtg|EngN|Yaw|VehSpd|WhlSpd|BrkPedl|AccrPedl|Odo|Dst", RegexOption.IGNORE_CASE)
    }
}
