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

    /** Current raw value of one whole-number signal, or null if the call failed. Float signals fail here: use [number]. */
    fun value(id: Int): Int? {
        val m = manager ?: return null
        return try {
            (Reflect.call(m, "getSignalValue", id) as? Number)?.toInt()
        } catch (t: Throwable) {
            lastError = EcarxCar.describe(t)
            null
        }
    }

    private val floatCache = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    /** True if the car stores [id] as a float (angles, speeds, temperatures). Null if unknown. Cached. */
    fun isFloat(id: Int): Boolean? {
        floatCache[id]?.let { return it }
        val m = manager ?: return null
        val f = runCatching { Reflect.call(m, "isFloatSignal", id) as? Boolean }.getOrNull() ?: return null
        floatCache[id] = f
        return f
    }

    /**
     * The ids from [candidates] that this car's service accepts. Some SignalId_ fields exist in
     * the class but not in the car (the service says "Invalid property Id"), and one such id
     * makes [subscribe] refuse the whole list.
     */
    fun usable(candidates: Collection<Int>): List<Int> = candidates.filter { id ->
        number(id) != null || lastError?.contains("Invalid property Id") != true
    }

    /** Current value of a float signal via getFloatSignalValue(id), or null if the call failed. */
    fun floatValue(id: Int): Float? {
        val m = manager ?: return null
        return try {
            (Reflect.call(m, "getFloatSignalValue", id) as? Number)?.toFloat()
        } catch (t: Throwable) {
            lastError = EcarxCar.describe(t)
            null
        }
    }

    /** Any signal's current value, using the float call when the car says it is a float. */
    fun number(id: Int): Number? {
        // isFloatSignal says "float" for nearly every signal on the Cityray (2242 of 2244), so
        // it is only a first guess: on a type error try the other call and remember which worked.
        val asFloat = kind[id] ?: (isFloat(id) == true)
        val first = if (asFloat) floatValue(id) else value(id)
        if (first != null) { kind[id] = asFloat; return first }
        if (lastError?.contains("Invalid property type") != true) return null
        val second = if (asFloat) value(id) else floatValue(id)
        if (second != null) kind[id] = !asFloat
        return second
    }

    private val kind = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    /**
     * Reads one signal through its own typed getter, for example getSteerWhlSnsrAg(). The
     * manager has one getter per signal; number-like signals (angles, speeds, temperatures)
     * return float there and do not answer [value]. Null if there is no getter or it threw.
     */
    fun read(name: String): Number? {
        val m = manager ?: return null
        return try {
            val g = getter(name) ?: run { lastError = "no getter get$name"; return null }
            g.invoke(m) as? Number
        } catch (t: Throwable) {
            lastError = EcarxCar.describe(t)
            null
        }
    }

    /** Return type of the typed getter for [name] ("float", "int", ...), or null if there is none. */
    fun getterType(name: String): String? = getter(name)?.returnType?.simpleName

    /** Signatures of the manager's general methods (not per-signal getters/setters), for debug reports. */
    fun generalMethods(): List<String> {
        val cls = Reflect.classOrNull(MANAGER_CLASS) ?: return emptyList()
        return cls.methods.filter { m ->
            m.declaringClass != Any::class.java &&
                !((m.name.startsWith("get") || m.name.startsWith("set")) && m.name.drop(3) in ids)
        }.map { m -> "${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})" }
            .distinct().sorted()
    }

    private fun getter(name: String) =
        Reflect.classOrNull(MANAGER_CLASS)?.let { Reflect.findMethod(it, "get$name", 0) }?.also { it.isAccessible = true }

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
         * OBSERVED on the Cityray, 2026-11-11 (Probe 0.9): the indicator lamps, 1 while the lamp
         * is lit and 0 while dark, so they blink with it. [TURN_STATE] does not blink.
         */
        const val TURN_LEFT = "ExtrLtgStsTurnIndrLe"
        const val TURN_RIGHT = "ExtrLtgStsTurnIndrRi"

        /** OBSERVED: indicator state without the blinking. Decode with [CarDecode.turnState]. */
        const val TURN_STATE = "IndcrSts"

        /** OBSERVED: brake lights, 1 while the pedal is pressed. */
        const val BRAKE_LIGHT = "ExtrLtgStsStopLi"

        /**
         * Named on the Cityray but not read yet (GUESS). The steering sensor's message does
         * arrive (its counter and quality signals are live), the angle itself needs [read].
         */
        const val STEERING_ANGLE = "SteerWhlSnsrAg"
        const val STEERING_SPEED = "SteerWhlSnsrAgSpd"
        const val SPEED = "VehSpdLgtA"
        const val RPM = "EngNSafeEngN"
        const val YAW_RATE = "AgDataRawSafeYawRate"
        const val HIGH_BEAM = "ExtrLtgStsHiBeam"
        const val LOW_BEAM = "ExtrLtgStsLoBeam"
        const val BATTERY_VOLTAGE = "VehBattUSysU"

        /** Name fragments worth showing first when hunting for steering and indicators. */
        val INTERESTING = Regex("Steer|Pinion|WhlAg|TurnInd|Indcr|ExtrLtg|EngN|Yaw|VehSpd|WhlSpd|BrkPedl|AccrPedl|Odo|Dst", RegexOption.IGNORE_CASE)
    }
}
