package dev.carapps.car

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Steering-wheel and hardware keys through the ECARX input API
 * (com.ecarx.xui.adaptapi.input.Input).
 *
 * This API intercepts keys: while we hold a key code, the head unit does not run its
 * normal action for it. Always call [release] when the screen that uses the keys goes
 * away. Key codes on this head unit are in the 200000 range (for example 200082 and
 * 200164 are used by other apps). Which button sends which code is not known yet.
 */
class EcarxKeys(context: Context) {

    interface Listener {
        fun onKeyPressed(code: Int) {}
        fun onKeyReleased(code: Int) {}
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var input: Any? = null
    private var callback: Any? = null

    var lastError: String? = null
        private set

    val isAvailable: Boolean
        get() = Reflect.classOrNull(INPUT_CLASS) != null

    val isHolding: Boolean
        get() = callback != null

    /**
     * Asks the head unit for [codes]. Returns the codes it actually granted, or an empty
     * list if nothing was granted. Key events arrive on the main thread.
     */
    fun intercept(codes: IntArray, listener: Listener): List<Int> {
        release()
        try {
            val inputCls = Reflect.classOrNull(INPUT_CLASS) ?: return fail("ECARX input API not found on this device")
            val cbCls = Reflect.classOrNull(KEY_CALLBACK_CLASS) ?: return fail("IKeyCallback not found")
            val create = Reflect.findMethod(inputCls, "create", Context::class.java)
                ?: return fail("Input.create(Context) not found")
            val inp = create.invoke(null, appContext) ?: return fail("Input.create returned null")

            val cb = Reflect.proxy(cbCls) { method, args ->
                val code = args.getOrNull(0) as? Int ?: return@proxy false
                when (method) {
                    "onKeyPressed" -> { main.post { listener.onKeyPressed(code) }; true }
                    "onKeyReleased" -> { main.post { listener.onKeyReleased(code) }; true }
                    else -> null
                }
            }
            val request = Reflect.findMethod(inputCls, "requestKeysInterception", IntArray::class.java, cbCls)
                ?: return fail("requestKeysInterception not found")
            val granted = request.invoke(inp, codes, cb) as? IntArray
            input = inp
            callback = cb
            lastError = null
            return granted?.toList() ?: emptyList()
        } catch (t: Throwable) {
            return fail(EcarxCar.describe(t))
        }
    }

    /** Gives every held key back to the head unit. Safe to call more than once. */
    fun release() {
        val inp = input
        val cb = callback
        if (inp != null && cb != null) runCatching { Reflect.call(inp, "abandonKeysInterception", cb) }
        input = null
        callback = null
    }

    private fun fail(msg: String): List<Int> {
        lastError = msg
        return emptyList()
    }

    companion object {
        const val INPUT_CLASS = "com.ecarx.xui.adaptapi.input.Input"
        const val KEY_CALLBACK_CLASS = "com.ecarx.xui.adaptapi.input.IKeyCallback"
    }
}
