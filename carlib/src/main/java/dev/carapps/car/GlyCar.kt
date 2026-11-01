package dev.carapps.car

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Geely's own car API (com.geely.os.car.GlyCar), a second route to car properties.
 *
 * GUESS: the class and method names come from the button app, which uses this for
 * ignition (property 0x200100, the same id as the ECARX ignition sensor). Argument order
 * is inferred from the types, and the callback's argument layout is unknown, so the raw
 * callback arguments are passed through for Car Probe to show. Read-only.
 */
class GlyCar(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var gly: Any? = null
    private var callback: Any? = null

    var lastError: String? = null
        private set

    val isAvailable: Boolean
        get() = CLASS_NAMES.any { Reflect.classOrNull(it) != null }

    /** Connects. Returns false if the API isn't on this device. */
    fun connect(): Boolean {
        if (gly != null) return true
        return try {
            val cls = CLASS_NAMES.firstNotNullOfOrNull { Reflect.classOrNull(it) } ?: return fail("GlyCar not found")
            val create = Reflect.findMethod(cls, "create", Context::class.java) ?: return fail("GlyCar.create(Context) not found")
            gly = create.invoke(null, appContext) ?: return fail("GlyCar.create returned null")
            true
        } catch (t: Throwable) {
            fail(EcarxCar.describe(t))
        }
    }

    /** Reads an int property. Int.MIN_VALUE seems to mean "no value" in the button app. */
    fun intProperty(id: Int): Int? {
        val g = gly ?: return null
        return try {
            Reflect.call(g, "getIntProperty", id) as? Int
        } catch (t: Throwable) {
            lastError = EcarxCar.describe(t); null
        }
    }

    /**
     * Subscribes to property changes. [onEvent] gets the raw callback arguments.
     * The button app registered with an int array holding 0x200100.
     */
    fun listen(ids: IntArray, onEvent: (args: List<Any?>) -> Unit): Boolean {
        val g = gly ?: return false
        return try {
            val cbCls = CALLBACK_NAMES.firstNotNullOfOrNull { Reflect.classOrNull(it) } ?: return fail("IGlyCarEventCallBack not found")
            val cb = Reflect.proxy(cbCls) { method, args ->
                if (method == "onChangeEvent") main.post { onEvent(args.toList()) }
                null
            }
            val reg = g.javaClass.methods.firstOrNull { it.name == "registerSingleCarEventCallback" && it.parameterTypes.size == 2 }
                ?: return fail("registerSingleCarEventCallback not found")
            reg.isAccessible = true
            val args = reg.parameterTypes.map { if (it == IntArray::class.java) ids else cb }.toTypedArray()
            reg.invoke(g, *args)
            callback = cb
            true
        } catch (t: Throwable) {
            fail(EcarxCar.describe(t))
        }
    }

    fun disconnect() {
        val g = gly ?: return
        callback?.let { cb -> runCatching { Reflect.call(g, "unregisterSingleCarEventCallback", cb) } }
        runCatching { Reflect.call(g, "disconnect") }
        callback = null
        gly = null
    }

    private fun fail(msg: String): Boolean {
        lastError = msg
        return false
    }

    companion object {
        // The button app tries both names; the second looks like an obfuscated build.
        val CLASS_NAMES = listOf("com.geely.os.car.GlyCar", "com.geely.p003os.car.GlyCar")
        val CALLBACK_NAMES = listOf("com.geely.os.car.IGlyCarEventCallBack", "com.geely.p003os.car.IGlyCarEventCallBack")

        /** Same id as CarSensor.IGNITION. */
        const val PROPERTY_IGNITION = 0x200100
    }
}
