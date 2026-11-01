package dev.carapps.probe

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.carapps.car.CarDecode
import dev.carapps.car.CarSensor
import dev.carapps.car.EcarxCar
import dev.carapps.car.EcarxKeys
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Car Probe: shows every car value the ECARX API gives us, live, plus a log of changes
 * and steering-wheel key codes. It only reads. It never changes a car setting.
 */
class ProbeActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var car: EcarxCar
    private lateinit var keys: EcarxKeys

    private lateinit var statusView: TextView
    private lateinit var tableView: TextView
    private lateinit var logView: TextView
    private lateinit var keysView: TextView
    private lateinit var keysButton: Button

    private var connection = "unknown"
    private val pushCount = HashMap<Int, Int>()
    private val lastChange = HashMap<Int, Long>()
    private val log = ArrayDeque<String>()
    private val keyLog = ArrayDeque<String>()
    private val lastSeen = HashMap<Int, String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val refresh = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 500)
        }
    }

    private val stopKeys = Runnable { releaseKeys("time is up") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        car = EcarxCar(this)
        keys = EcarxKeys(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        connect()
        main.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(refresh)
        releaseKeys("app paused")
        car.stop()
    }

    private fun connect() {
        val ok = car.start(object : EcarxCar.Listener {
            override fun onConnectionChanged(connected: Boolean) {
                connection = if (connected) "connected" else "disconnected"
                addLog("car service $connection")
            }

            override fun onValueChanged(sensorId: Int, value: Float) = pushed(sensorId, "value=${showValue(value)}")
            override fun onEventChanged(sensorId: Int, event: Int) = pushed(sensorId, "event=${showEvent(sensorId, event)}")
            override fun onSupportChanged(sensorId: Int, status: String) = pushed(sensorId, "support=$status")
        })
        addLog(if (ok) "started; listening to ${CarSensor.entries.size} sensors" else "start failed: ${car.lastError}")
    }

    /** Counts every push, but only logs a sensor when its value actually changes. */
    private fun pushed(id: Int, what: String) {
        pushCount[id] = (pushCount[id] ?: 0) + 1
        if (lastSeen[id] == what) return
        lastSeen[id] = what
        lastChange[id] = SystemClock.elapsedRealtime()
        addLog("${name(id)} $what")
    }

    private fun toggleKeys() {
        if (keys.isHolding) {
            releaseKeys("stopped")
            return
        }
        val codes = IntArray(KEY_RANGE_SIZE) { KEY_RANGE_START + it }
        val granted = keys.intercept(codes, object : EcarxKeys.Listener {
            override fun onKeyPressed(code: Int) = addKey("DOWN $code")
            override fun onKeyReleased(code: Int) = addKey("UP   $code")
        })
        if (!keys.isHolding) {
            addLog("buttons: failed: ${keys.lastError}")
            return
        }
        keyLog.clear()
        addKey("listening for ${KEY_LISTEN_SECONDS}s; granted ${granted.size} codes " + summarize(granted))
        addKey("press each wheel button once, slowly")
        keysButton.text = "Stop listening to buttons"
        main.postDelayed(stopKeys, KEY_LISTEN_SECONDS * 1000L)
    }

    private fun releaseKeys(reason: String) {
        main.removeCallbacks(stopKeys)
        if (keys.isHolding) {
            keys.release()
            addKey("released ($reason)")
        }
        if (::keysButton.isInitialized) keysButton.text = "Listen to wheel buttons (${KEY_LISTEN_SECONDS}s)"
    }

    private fun render() {
        statusView.text = buildString {
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL}  |  Android ${Build.VERSION.RELEASE}\n")
            append("Build: ${Build.DISPLAY}\n")
            append("ECARX car API: ${if (car.isAvailable) "found" else "NOT found"}  |  ")
            append("input API: ${if (keys.isAvailable) "found" else "NOT found"}\n")
            append("Started: ${car.isStarted}  |  service: $connection")
            car.lastError?.let { append("\nLast error: $it") }
        }

        val now = SystemClock.elapsedRealtime()
        tableView.text = buildString {
            append(String.format(Locale.US, "%-22s %-10s %-14s %-14s %-7s %s\n", "SENSOR", "ID", "VALUE", "EVENT", "PUSHES", "SUPPORT"))
            for (s in CarSensor.entries) {
                val v = car.latestValue(s.id)?.let { showValue(it) } ?: "-"
                val e = car.latestEvent(s.id)?.let { showEvent(s.id, it) } ?: "-"
                val sup = car.supportStatus(s.id) ?: "-"
                val pushes = pushCount[s.id] ?: 0
                val fresh = lastChange[s.id]?.let { now - it < 1500 } == true
                append(String.format(Locale.US, "%-22s %-10s %-14s %-14s %-7d %s%s\n",
                    s.label, EcarxCar.hex(s.id), v, e, pushes, sup, if (fresh) "  *" else ""))
            }
        }
    }

    private fun addKey(line: String) {
        keyLog.addFirst("${clock.format(Date())}  $line")
        while (keyLog.size > 40) keyLog.removeLast()
        if (::keysView.isInitialized) keysView.text = keyLog.joinToString("\n")
    }

    /** Shows a float, or the integer packed inside it when the sensor sends one that way. */
    private fun showValue(v: Float): String = CarDecode.packedInt(v)?.let { "int $it" } ?: fmt(v)

    /** Shows an event as base id + offset, with the gear name when we know it. */
    private fun showEvent(id: Int, e: Int): String {
        if (e == -1) return "-1"
        return when (id) {
            CarSensor.GEAR.id -> "+0x${Integer.toHexString(e - id)} ${CarDecode.gear(e)}"
            CarSensor.IGNITION.id -> "+${CarDecode.ignitionStep(e)}"
            else -> e.toString()
        }
    }

    private fun addLog(line: String) {
        log.addFirst("${clock.format(Date())}  $line")
        while (log.size > 60) log.removeLast()
        if (::logView.isInitialized) logView.text = log.joinToString("\n")
    }

    private fun buildUi(): ScrollView {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.BLACK)
        }

        root.addView(text(28f, Color.WHITE, bold = true).apply { text = "Car Probe" })
        root.addView(text(13f, Color.LTGRAY).apply {
            text = "Read-only. Change gear, ignition and speed, and watch the values. A * marks a value that just changed."
        })

        statusView = text(15f, Color.rgb(140, 220, 255)).also { root.addView(it, spaced()) }
        tableView = text(15f, Color.WHITE, mono = true).also { root.addView(it, spaced()) }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START }
        row.addView(button("Reconnect") { car.stop(); connect() })
        keysButton = button("Listen to wheel buttons (${KEY_LISTEN_SECONDS}s)") { toggleKeys() }
        row.addView(keysButton)
        row.addView(button("Clear log") { log.clear(); logView.text = "" })
        root.addView(row, spaced())

        keysView = text(22f, Color.rgb(255, 220, 120), mono = true).apply {
            text = "Wheel buttons show up here, newest on top."
        }.also { root.addView(it, spaced()) }

        root.addView(text(13f, Color.LTGRAY).apply {
            text = "While listening, the wheel buttons only show up here and do not do their normal job. It stops by itself after ${KEY_LISTEN_SECONDS} seconds."
        })

        root.addView(text(13f, Color.LTGRAY).apply {
            text = "Sensor log: only real changes are listed, newest on top."
        }, spaced())
        logView = text(14f, Color.rgb(180, 255, 180), mono = true).also { root.addView(it, spaced()) }

        return ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(root, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun text(sizeSp: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
        textSize = sizeSp
        setTextColor(color)
        typeface = when {
            mono -> Typeface.MONOSPACE
            bold -> Typeface.DEFAULT_BOLD
            else -> Typeface.DEFAULT
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setOnClickListener { onClick() }
    }

    private fun spaced() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun name(id: Int) = CarSensor.byId(id)?.label ?: EcarxCar.hex(id)

    private fun fmt(v: Float) = if (v == v.toLong().toFloat()) v.toLong().toString() else String.format(Locale.US, "%.2f", v)

    private fun summarize(codes: List<Int>): String {
        if (codes.isEmpty()) return ""
        val sorted = codes.sorted()
        return if (sorted.size <= 8) sorted.joinToString(prefix = "(", postfix = ")")
        else "(${sorted.first()}..${sorted.last()})"
    }

    companion object {
        // Wheel key codes seen in other apps for this head unit sit in this range.
        const val KEY_RANGE_START = 200000
        const val KEY_RANGE_SIZE = 300
        const val KEY_LISTEN_SECONDS = 20
    }
}
