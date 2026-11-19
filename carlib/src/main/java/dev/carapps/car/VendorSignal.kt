package dev.carapps.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel

/**
 * Vendor signal ids outside the ECARX catalog. GUESS: all come from the button app
 * (com.aleksan.button 2.7.4), which tries them one after another to find the turn
 * indicators on Geely cars. Read them with [EcarxCar.latestEvent] / [EcarxCar.functionValue]
 * or [AutolinkCarProxy.intProperty], and decode with [CarDecode.lampSwitch].
 */
object VendorSignal {
    /** Body control module lamp switches: 0 off, other values on, 255 unknown. */
    const val BCM_TURN_LEFT = 0x21401664
    const val BCM_TURN_RIGHT = 0x21401665
    /** IBcm.BCM_FUNC_LIGHT_HAZARD_FLASHERS, a regular ECARX car function. */
    const val BCM_HAZARD = 0x21050F00

    /**
     * ILamp SETTING_FUNC_LAMP_LR_TRAFFIC_LIGHT, an ECARX car function. The button app reads
     * 0x2B020101 as left and 0x2B020102 as right. More likely it is the headlight setting for
     * left- or right-hand traffic: the 2026-11-10 scan read RIGHT while driving in Egypt,
     * which drives on the right. Kept as a candidate until the indicator test rules it out.
     */
    const val LAMP_LR_TRAFFIC_LIGHT = 0x2B020100
    const val LAMP_LR_LEFT = 0x2B020101
    const val LAMP_LR_RIGHT = 0x2B020102

    /** Autolink signal-state ids the button app subscribes to while hunting for indicators. */
    const val AUTOLINK_SIGNAL_REQUEST_STATE = 0x2140165B
    const val AUTOLINK_SIGNAL_STATE = 0x21401666
    const val AUTOLINK_SIGNAL_DISPLAY_STATE = 0x21402009
    const val CARPROXY_SIGNAL_STATE = 0x21407898
    const val CARPROXY_TURN_LEFT = 0x21407951
    const val CARPROXY_TURN_RIGHT = 0x21407952
    const val CARPROXY_FLASH = 0x21407944

    val TURN_CANDIDATES = intArrayOf(
        LAMP_LR_TRAFFIC_LIGHT, BCM_TURN_LEFT, BCM_TURN_RIGHT, BCM_HAZARD,
        AUTOLINK_SIGNAL_REQUEST_STATE, AUTOLINK_SIGNAL_STATE, AUTOLINK_SIGNAL_DISPLAY_STATE,
        CARPROXY_SIGNAL_STATE, CARPROXY_TURN_LEFT, CARPROXY_TURN_RIGHT, CARPROXY_FLASH,
    )

    fun name(id: Int): String? = when (id) {
        LAMP_LR_TRAFFIC_LIGHT -> "Lamp L/R traffic light"
        BCM_TURN_LEFT -> "BCM turn left"
        BCM_TURN_RIGHT -> "BCM turn right"
        BCM_HAZARD -> "BCM hazard"
        AUTOLINK_SIGNAL_REQUEST_STATE -> "Autolink signal request"
        AUTOLINK_SIGNAL_STATE -> "Autolink signal state"
        AUTOLINK_SIGNAL_DISPLAY_STATE -> "Autolink signal display"
        CARPROXY_SIGNAL_STATE -> "CarProxy signal state"
        CARPROXY_TURN_LEFT -> "CarProxy turn left"
        CARPROXY_TURN_RIGHT -> "CarProxy turn right"
        CARPROXY_FLASH -> "CarProxy flash"
        else -> null
    }
}

/**
 * Autolink's car proxy service (com.autolink.carproxyservice), a third route to raw car
 * signals next to ECARX and GlyCar. Autolink builds this head unit's software.
 *
 * GUESS: the button app binds to it and reads ints with binder transaction 5
 * ("getIntProperty(id)", interface com.autolink.adapterbinder.ICarProxyService). We only
 * read. Binding may be refused for normal apps; [lastError] says so.
 */
class AutolinkCarProxy(context: Context) {

    private val appContext = context.applicationContext
    private var binder: IBinder? = null
    private var connection: ServiceConnection? = null

    var lastError: String? = null
        private set

    val isConnected: Boolean get() = binder?.isBinderAlive == true

    /** Starts binding. Connection completes later; check [isConnected]. */
    fun connect(): Boolean {
        if (connection != null) return true
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binder = service }
            override fun onServiceDisconnected(name: ComponentName?) { binder = null }
        }
        return try {
            val ok = appContext.bindService(Intent().setComponent(COMPONENT), conn, Context.BIND_AUTO_CREATE)
            if (ok) connection = conn else lastError = "bindService refused (service missing or not allowed)"
            ok
        } catch (t: Throwable) {
            lastError = EcarxCar.describe(t)
            false
        }
    }

    fun disconnect() {
        connection?.let { runCatching { appContext.unbindService(it) } }
        connection = null
        binder = null
    }

    /** Reads one int signal, or null if not connected or the call failed. */
    fun intProperty(id: Int): Int? {
        val b = binder ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(id)
            b.transact(TX_GET_INT_PROPERTY, data, reply, 0)
            reply.readException()
            reply.readInt()
        } catch (t: Throwable) {
            lastError = EcarxCar.describe(t)
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    companion object {
        val COMPONENT = ComponentName("com.autolink.carproxyservice", "com.autolink.carproxyservice.CarProxyService")
        const val DESCRIPTOR = "com.autolink.adapterbinder.ICarProxyService"
        const val TX_GET_INT_PROPERTY = 5
    }
}
