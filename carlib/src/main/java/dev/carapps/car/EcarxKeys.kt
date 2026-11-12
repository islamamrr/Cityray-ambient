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
                    "onKeyCanceled" -> true
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
        /**
         * Steering-wheel codes and names. CONFIRMED on 2026-11-08: 200024/200025 volume,
         * 200164 mute, 200231 voice/phone, 200087/200088 next/previous, 200082 menu,
         * 200400 mode. The rest are GUESS (from APK; not sent by Islam's wheel so far). Names come
         * from the button app's own labels and ECARX's KeyCode table (bundled in gbinder).
         * The button app intercepts exactly these on Cityray cars and treats 200003 like Home.
         */
        val WHEEL_KEYS: Map<Int, String> = linkedMapOf(
            200087 to "Next",
            200088 to "Previous",
            200085 to "Play/Pause",
            200164 to "Mute",
            200082 to "Menu",
            200231 to "Voice / Phone",
            200003 to "Home",
            200004 to "Back",
            200005 to "Call",
            200006 to "End call",
            200024 to "Volume up",
            200025 to "Volume down",
            200110 to "Mode (alt)",
            200176 to "Settings",
            200400 to "Mode",
            200600 to "Multi-function",
            110007 to "Knob volume up",
            110008 to "Knob volume down",
            110009 to "Knob tune up",
            110010 to "Knob tune down",
        )

        val KNOWN_CODES: IntArray = WHEEL_KEYS.keys.toIntArray()

        fun name(code: Int): String = WHEEL_KEYS[code] ?: "unknown"

        /** Our label if we have one, else ECARX's KEYCODE_* name from the catalog. */
        fun name(code: Int, catalog: EcarxCatalog): String =
            WHEEL_KEYS[code] ?: catalog.keyName(code)?.removePrefix("KEYCODE_") ?: "unknown"

        const val INPUT_CLASS = "com.ecarx.xui.adaptapi.input.Input"
        const val KEY_CALLBACK_CLASS = "com.ecarx.xui.adaptapi.input.IKeyCallback"
    }
}
