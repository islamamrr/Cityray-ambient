package dev.carapps.probe

import android.app.Activity
import android.content.Intent
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
import dev.carapps.car.AndroidCar
import dev.carapps.car.AutolinkCarProxy
import dev.carapps.car.CarDecode
import dev.carapps.car.EcarxCatalog
import dev.carapps.car.EcarxSignals
import dev.carapps.car.SensorGroup
import dev.carapps.car.VendorSignal
import dev.carapps.car.CarFunction
import dev.carapps.car.CarInfo
import dev.carapps.car.GeelyAppStore
import dev.carapps.car.GlyCar
import dev.carapps.car.Zone
import dev.carapps.car.CarSensor
import dev.carapps.car.EcarxCar
import dev.carapps.car.EcarxKeys
import dev.carapps.car.WheelTurn
import java.io.File
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
    private lateinit var gly: GlyCar
    private lateinit var androidCar: AndroidCar
    private lateinit var carProxy: AutolinkCarProxy
    private lateinit var catalog: EcarxCatalog
    private lateinit var scanView: TextView
    private lateinit var scanButton: Button
    @Volatile private var scanning = false
    private lateinit var signals: EcarxSignals
    private lateinit var rawButton: Button
    private lateinit var steerButton: Button
    @Volatile private var rawHunting = false
    @Volatile private var packing = false
    private lateinit var wheelButton: Button
    private var hunting = false
    private lateinit var huntButton: Button
    private val huntSeen = LinkedHashMap<String, MutableList<String>>()
    private var lastScanText: String? = null
    private lateinit var extraView: TextView
    private var tick = 0
    private var appStoreCount: String = "-"

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
    private val lastFunction = HashMap<String, String>()
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
        gly = GlyCar(this)
        androidCar = AndroidCar(this)
        carProxy = AutolinkCarProxy(this)
        signals = EcarxSignals(this)
        catalog = EcarxCatalog.get(this)
        setContentView(buildUi())
        // Speed and energy are "dangerous" car permissions; ask once so the raw hunt can see them.
        runCatching {
            requestPermissions(arrayOf("android.car.permission.CAR_SPEED", "android.car.permission.CAR_ENERGY"), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        connect()
        main.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(refresh)
        main.removeCallbacks(endHunt)
        if (hunting) { hunting = false; huntButton.isEnabled = true }
        releaseKeys("app paused")
        car.stop()
        gly.disconnect()
        androidCar.disconnect()
        carProxy.disconnect()
        rawHunting = false
        signals.disconnect()
    }

    private fun connect() {
        val listener = object : EcarxCar.Listener {
            override fun onConnectionChanged(connected: Boolean) {
                connection = if (connected) "connected" else "disconnected"
                addLog("car service $connection")
            }

            override fun onValueChanged(sensorId: Int, value: Float) = pushed(sensorId, "value=${showValue(value)}")
            override fun onEventChanged(sensorId: Int, event: Int) = pushed(sensorId, "event=${showEvent(sensorId, event)}")
            override fun onSupportChanged(sensorId: Int, status: String) = pushed(sensorId, "support=$status")
            override fun onFunctionChanged(functionId: Int, zone: Int, value: Float) {
                val key = "f:$functionId:$zone"
                val what = fmt(value)
                if (lastFunction[key] == what) return
                lastFunction[key] = what
                val name = CarFunction.byId(functionId)?.label ?: EcarxCar.hex(functionId)
                addLog("FUNC $name ${Zone.label(zone)} = $what")
            }
        }
        val ok = car.start(listener)
        addLog(if (ok) "started; listening to ${CarSensor.usable.size} sensors" else "start failed: ${car.lastError}")
        if (ok) addLog(if (car.watchFunctions(listener)) "watching ${CarFunction.entries.size} car functions" else "functions: ${car.lastError}")

        if (gly.connect()) {
            addLog("GlyCar connected; ignition property = ${gly.intProperty(GlyCar.PROPERTY_IGNITION)}")
            val listening = gly.listen(intArrayOf(GlyCar.PROPERTY_IGNITION)) { args ->
                val v = (args.getOrNull(2) as? Number)?.toInt()
                addLog("GLYCAR event ${args.joinToString()}" + (v?.let { "  -> ${CarDecode.ignition(it)}" } ?: ""))
            }
            if (!listening) addLog("GlyCar listen: ${gly.lastError}")
        } else {
            addLog("GlyCar: ${gly.lastError}")
        }
        if (!androidCar.connect()) addLog("Android car API: ${androidCar.lastError}")
        if (!carProxy.connect()) addLog("Autolink car proxy: ${carProxy.lastError}")
        appStoreCount = GeelyAppStore.listPackages(this)?.size?.toString() ?: "refused"
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
        // 0.5: ask for only the known codes, like the button app does. 0.4 asked for 300 codes and
        // the keys still did their normal jobs; this checks whether a short list lets us consume them.
        val codes = (EcarxKeys.KNOWN_CODES + IntArray(51) { 300000 + it }).distinct().toIntArray()
        val granted = keys.intercept(codes, object : EcarxKeys.Listener {
            override fun onKeyPressed(code: Int) = addKey("DOWN $code  ${EcarxKeys.name(code, catalog)}")
            override fun onKeyReleased(code: Int) = addKey("UP   $code  ${EcarxKeys.name(code, catalog)}")
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
                append(String.format(Locale.US, "%-22s %-10s %-14s %-14s %-7d %s%s  [%s]\n",
                    s.label, EcarxCar.hex(s.id), v, e, pushes, sup, if (fresh) "  *" else "", short(s.confidence.name)))
            }
        }

        // Functions and car info are binder calls on demand; refresh them every 2 seconds.
        if (tick++ % 4 == 0) renderExtra()
    }

    private fun renderExtra() {
        extraView.text = buildString {
            append("CAR INFO  [all GUESS]\n")
            for (i in CarInfo.entries) {
                append(String.format(Locale.US, "%-24s %-10s %-12s %s\n",
                    i.label, EcarxCar.hex(i.id), car.carInfo(i) ?: "-", car.carInfoStatus(i) ?: "-"))
            }
            append("\nCAR FUNCTIONS, read-only  [all GUESS]\n")
            append(String.format(Locale.US, "%-30s %-11s %-11s %-9s %s\n", "FUNCTION", "ID", "ZONE", "VALUE", "SUPPORT"))
            for (f in CarFunction.entries) {
                for (z in f.zones) {
                    val v = car.functionValue(f.id, z)?.toString() ?: "-"
                    append(String.format(Locale.US, "%-30s %-11s %-11s %-9s %s\n",
                        f.label.take(30), EcarxCar.hex(f.id), Zone.label(z), v, car.functionStatus(f.id, z) ?: "-"))
                }
            }
            append("\nSENSOR GROUPS  [GUESS; turn the wheel with the engine on]\n")
            for ((gid, gname) in SensorGroup.ALL) {
                val g = car.sensorGroup(gid)
                append("  $gname ${EcarxCar.hex(gid)}, ${car.supportStatus(gid) ?: "-"}: ")
                append(if (g == null) "no value\n" else g.entries.joinToString("  ") { "${it.key}=${it.value ?: "-"}" } + "\n")
            }
            append("\nTURN SIGNALS  [all GUESS; switch the indicator on and off]\n")
            append("  Android TURN_SIGNAL_STATE: ${androidCar.int(AndroidCar.TURN_SIGNAL_STATE) ?: "-"}")
            append("  hazard: ${androidCar.int(AndroidCar.HAZARD_LIGHTS_STATE) ?: "-"}\n")
            append("  car proxy: ${if (carProxy.isConnected) "connected" else "not connected"}\n")
            for (id in VendorSignal.TURN_CANDIDATES) {
                val ev = car.latestEvent(id)?.toString() ?: "-"
                val fn = car.functionValue(id)?.let { catalog.valueName(EcarxCatalog.Kind.FUNCTION, id, it) ?: it.toString() } ?: "-"
                val cp = carProxy.intProperty(id)?.toString() ?: "-"
                append(String.format(Locale.US, "  %-26s %-10s sensor=%-6s func=%-6s proxy=%s\n",
                    VendorSignal.name(id), EcarxCar.hex(id), ev, fn, cp))
            }
            append("\nGlyCar API: ${if (gly.isAvailable) "found" else "NOT found"}")
            append("  |  ignition property: ${gly.intProperty(GlyCar.PROPERTY_IGNITION) ?: "-"}\n")
            append("Geely launcher app list: $appStoreCount apps\n")
            append("Known wheel keys (from APK): ${EcarxKeys.KNOWN_CODES.size} codes")
        }
    }

    /** Asks the car about every catalog id on a background thread, then shows the active ones. */
    private fun scanAll() {
        if (scanning) return
        if (!car.isStarted) {
            scanView.text = "Scan needs the car API. Tap Reconnect first. ${car.lastError ?: ""}"
            return
        }
        scanning = true
        scanButton.isEnabled = false
        Thread {
            val started = SystemClock.elapsedRealtime()
            val rows = try {
                car.scan(catalog) { done, total -> main.post { scanView.text = "Scanning... $done / $total" } }
            } catch (t: Throwable) {
                main.post { scanView.text = "Scan failed: $t"; scanning = false; scanButton.isEnabled = true }
                return@Thread
            }
            val secs = (SystemClock.elapsedRealtime() - started) / 1000
            val report = buildScanReport(rows, secs)
            val file = runCatching {
                val dir = getExternalFilesDir(null) ?: filesDir
                File(dir, "scan-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt")
                    .also { it.writeText(report) }
            }.getOrNull()
            main.post {
                lastScanText = report
                scanView.text = report + (file?.let { "\nSaved to ${it.absolutePath}" } ?: "")
                scanning = false
                scanButton.isEnabled = true
                addLog("scan done: ${rows.count { it.isActive }} active of ${rows.size}")
            }
        }.start()
    }

    /**
     * Listens to every catalog sensor and car function for [HUNT_SECONDS] and lists the ones
     * whose value changed. Do one thing during it (flick the indicator, turn the wheel) to
     * find the id that follows it. Read-only.
     */
    private fun startHunt() {
        if (hunting) return
        hunting = true
        huntSeen.clear()
        main.removeCallbacks(refresh)
        car.stop()
        val record = { key: String, v: String ->
            val list = huntSeen.getOrPut(key) { ArrayList() }
            if (list.lastOrNull() != v && list.size < 12) list.add(v)
        }
        val listener = object : EcarxCar.Listener {
            override fun onValueChanged(sensorId: Int, value: Float) =
                record("S ${EcarxCar.hex(sensorId)} ${catalog.sensor(sensorId)?.name ?: ""}", showValue(value))
            override fun onEventChanged(sensorId: Int, event: Int) =
                record("S ${EcarxCar.hex(sensorId)} ${catalog.sensor(sensorId)?.name ?: ""}",
                    catalog.valueName(EcarxCatalog.Kind.SENSOR, sensorId, event) ?: EcarxCar.hex(event))
            override fun onFunctionChanged(functionId: Int, zone: Int, value: Float) {
                val v = value.toInt()
                record("F ${EcarxCar.hex(functionId)} ${catalog.function(functionId)?.name ?: ""} ${Zone.label(zone)}".trim(),
                    catalog.valueName(EcarxCatalog.Kind.FUNCTION, functionId, v) ?: fmt(value))
            }
        }
        val sensorIds = catalog.sensors.map { it.id } + VendorSignal.TURN_CANDIDATES.toList()
        val ok = car.start(listener, sensorIds.distinct())
        val allFns = (catalog.functions.map { it.id } + VendorSignal.TURN_CANDIDATES.toList()).distinct()
        // The car may refuse the whole list if it holds ids it doesn't support; retry with the active ones.
        val fnOk = ok && (car.watchFunctions(listener, allFns) ||
            car.watchFunctions(listener, allFns.filter { car.functionStatus(it) == "active" }))
        scanView.text = if (ok) "Listening to everything for ${HUNT_SECONDS}s" +
            (if (fnOk) "" else " (functions refused: ${car.lastError})") + ". Do ONE thing now, for example flick the indicator."
        else "Could not start: ${car.lastError}"
        huntButton.isEnabled = false
        main.postDelayed(endHunt, HUNT_SECONDS * 1000L)
    }

    private val endHunt = Runnable { finishHunt() }

    private fun finishHunt() {
        car.stop()
        val changed = huntSeen.filterValues { it.size > 1 }
        val report = buildString {
            append("CHANGED in ${HUNT_SECONDS}s: ${changed.size} of ${huntSeen.size} ids that reported\n")
            for ((k, vals) in changed) append("$k\n    ${vals.joinToString(" -> ")}\n")
        }
        lastScanText = report
        scanView.text = report
        hunting = false
        huntButton.isEnabled = true
        connect()
        main.post(refresh)
    }

    /**
     * The deep test. Watches every raw CAN signal the car_signal service names, plus every
     * property Android's car service lists, for [HUNT_SECONDS], and reports the ones that
     * changed. Turn the wheel or flick the indicator during it. Read-only.
     */
    private fun startRawHunt() {
        if (rawHunting || hunting) return
        if (!car.isStarted) { scanView.text = "Tap Reconnect first. ${car.lastError ?: ""}"; return }
        rawHunting = true
        rawButton.isEnabled = false
        scanView.text = "Connecting to raw signals..."
        Thread {
            val report = try { runRawHunt() } catch (t: Throwable) { "Raw hunt failed: $t" }
            val file = runCatching {
                File(getExternalFilesDir(null) ?: filesDir,
                    "raw-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt").also { it.writeText(report) }
            }.getOrNull()
            main.post {
                lastScanText = report
                scanView.text = report + (file?.let { "\nSaved to ${it.absolutePath}" } ?: "")
                rawHunting = false
                rawButton.isEnabled = true
            }
        }.start()
    }

    private fun runRawHunt(): String {
        fun say(t: String) = main.post { scanView.text = t }
        var connected = signals.connect(car)
        var waited = 0
        while (!connected && waited < 3000) { Thread.sleep(300); waited += 300; connected = signals.isConnected }
        if (!connected) return "Raw hunt: car_signal NOT connected: ${signals.lastError}"
        say("Checking which of ${signals.ids.size} signals this car knows...")
        val usable = signals.usable(signals.ids.values).toSet()
        val ids = signals.ids.filterValues { it in usable }
        val floats = ids.filterValues { signals.isFloat(it) == true }.keys
        val seen = HashMap<String, MutableList<String>>()
        val lo = HashMap<String, Double>()
        val hi = HashMap<String, Double>()
        fun record(name: String, v: Any?) {
            if (v == null) return
            synchronized(seen) {
                val text = if (v is Float || v is Double) String.format(Locale.US, "%.2f", (v as Number).toDouble()) else v.toString()
                if (v is Number) {
                    val d = v.toDouble()
                    lo[name] = minOf(lo[name] ?: d, d); hi[name] = maxOf(hi[name] ?: d, d)
                }
                val l = seen.getOrPut(name) { ArrayList() }
                if (l.lastOrNull() != text && l.size < 12) l.add(text)
            }
        }
        val subscribed = signals.subscribe(ids.values) { id, v -> record(signals.name(id), v) }
        val subscribeNote = if (subscribed) "subscribed" else "subscribe refused: ${signals.lastError}"
        val end = SystemClock.elapsedRealtime() + RAW_SECONDS * 1000L
        var passes = 0
        var readErrors = 0
        while (rawHunting && SystemClock.elapsedRealtime() < end) {
            val left = (end - SystemClock.elapsedRealtime()) / 1000
            say("Watching ${ids.size} raw signals (${floats.size} decimal), ${left}s left, pass $passes.\n" +
                "Engine running. Drive slowly and turn the wheel fully left and right, flick each indicator.")
            for ((name, id) in ids) {
                val v = signals.number(id)
                if (v == null) readErrors++ else record(name, v)
            }
            passes++
        }
        signals.unsubscribe()
        return buildString {
            append("RAW HUNT 2 ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}  ${Build.DISPLAY}\n")
            append("car_signal: connected via ${signals.route}, $subscribeNote\n")
            append("signals named ${signals.ids.size}, known to this car ${ids.size}, decimal ${floats.size}, passes $passes, failed reads $readErrors\n")
            val snap = synchronized(seen) { seen.mapValues { it.value.toList() } }
            val changed = snap.filterValues { it.size > 1 }.keys
            fun line(n: String) = "$n${if (n in floats) " [dec]" else ""}: ${snap[n]!!.joinToString(" > ")}" +
                (if (n in floats) String.format(Locale.US, "   range %.2f .. %.2f", lo[n], hi[n]) else "") + "\n"
            val dec = changed.filter { it in floats }.sortedByDescending { (hi[it] ?: 0.0) - (lo[it] ?: 0.0) }
            append("\nDECIMAL SIGNALS THAT CHANGED (${dec.size}), widest range first. Steering angle should swing hundreds of degrees.\n")
            for (n in dec) append(line(n))
            val ints = changed.filter { it !in floats }.sorted()
            append("\nWHOLE-NUMBER SIGNALS THAT CHANGED (${ints.size})\n")
            for (n in ints) append(line(n))
            val still = ids.keys.filter { EcarxSignals.INTERESTING.containsMatchIn(it) && it !in changed }
            append("\nINTERESTING NAMES THAT DID NOT CHANGE (${still.size})\n")
            for (n in still) append("$n=${snap[n]?.lastOrNull() ?: "-"}  ")
            append("\n")
        }
    }

    /**
     * The focused test: a short list of steering, speed and indicator signals, read many
     * times a second with every way the signal manager offers, with the error text of each
     * way that fails. Turn the wheel during it. Read-only.
     */
    private fun startSteerWatch() {
        if (rawHunting || hunting) return
        if (!car.isStarted) { scanView.text = "Tap Reconnect first. ${car.lastError ?: ""}"; return }
        rawHunting = true
        steerButton.isEnabled = false
        scanView.text = "Connecting to raw signals..."
        Thread {
            val report = try { runSteerWatch() } catch (t: Throwable) { "Steering watch failed: $t" }
            val file = runCatching {
                File(getExternalFilesDir(null) ?: filesDir,
                    "steer-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt").also { it.writeText(report) }
            }.getOrNull()
            main.post {
                lastScanText = report
                scanView.text = report + (file?.let { "\nSaved to ${it.absolutePath}" } ?: "")
                rawHunting = false
                steerButton.isEnabled = true
            }
        }.start()
    }

    private fun runSteerWatch(): String {
        var connected = signals.connect(car)
        var waited = 0
        while (!connected && waited < 3000) { Thread.sleep(300); waited += 300; connected = signals.isConnected }
        if (!connected) return "Steering watch: car_signal NOT connected: ${signals.lastError}"
        val wanted = signals.ids.filterKeys { STEER_FOCUS.containsMatchIn(it) }.entries.take(80).associate { it.key to it.value }
        val ok = signals.usable(wanted.values).toSet()
        val focus = wanted.filterValues { it in ok }
        val seen = HashMap<String, MutableList<String>>()
        val counts = HashMap<String, Int>()
        val errors = HashMap<String, String>()
        fun record(key: String, v: String) = synchronized(seen) {
            counts[key] = (counts[key] ?: 0) + 1
            val l = seen.getOrPut(key) { ArrayList() }
            if (l.lastOrNull() != v && l.size < 14) l.add(v)
        }
        fun num(n: Number) = if (n is Float || n is Double) String.format(Locale.US, "%.2f", n.toDouble()) else n.toString()
        val subscribed = signals.subscribe(focus.values) { id, v ->
            record("cb " + signals.name(id), if (v is Number) num(v) else "$v(${v?.javaClass?.simpleName})")
        }
        val subscribeNote = if (subscribed) "subscribed" else "subscribe refused: ${signals.lastError}"
        val end = SystemClock.elapsedRealtime() + STEER_SECONDS * 1000L
        var passes = 0
        while (rawHunting && SystemClock.elapsedRealtime() < end) {
            val left = (end - SystemClock.elapsedRealtime()) / 1000
            val live = synchronized(seen) {
                listOf("SteerWhlSnsrAg", "SteerWhlSnsrAgSpd", "IndcrSts").joinToString("  ") { n ->
                    "$n=" + (seen["get $n"]?.lastOrNull() ?: seen["cb $n"]?.lastOrNull() ?: seen["id $n"]?.lastOrNull() ?: "-")
                }
            }
            main.post { scanView.text = "Watching ${focus.size} signals, ${left}s left.\nEngine running. Turn the wheel slowly left and right, then drive a few metres.\n$live" }
            for ((name, id) in focus) {
                if (name == "IndcrSts" || name.startsWith("ExtrLtg")) { signals.value(id)?.let { record("id $name", it.toString()) }; continue }
                val g = signals.read(name)
                if (g != null) record("get $name", num(g)) else if ("get $name" !in errors) errors["get $name"] = signals.lastError ?: "null"
                val v = signals.value(id)
                if (v != null) record("id $name", v.toString()) else if ("id $name" !in errors) errors["id $name"] = signals.lastError ?: "null"
                if (signals.isFloat(id) == true) {
                    val f = signals.floatValue(id)
                    if (f != null) record("fl $name", num(f)) else if ("fl $name" !in errors) errors["fl $name"] = signals.lastError ?: "null"
                }
            }
            passes++
            Thread.sleep(80)
        }
        signals.unsubscribe()
        return buildString {
            append("STEERING WATCH ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}  ${Build.DISPLAY}\n")
            append("car_signal: connected via ${signals.route}, $subscribeNote, ${focus.size} signals, passes $passes\n")
            append("Each signal: typed getter (get), getFloatSignalValue (fl), getSignalValue (id), callback (cb). xN = number of reads.\n")
            append("Cntr / Chks signals are message counters: if they never change, the car is not refreshing that message.\n\n")
            val snap = synchronized(seen) { seen.mapValues { it.value.toList() } }
            for (name in focus.keys.sorted()) {
                append("$name  [getter: ${signals.getterType(name) ?: "none"}]\n")
                for (way in listOf("get", "fl", "id", "cb")) {
                    val k = "$way $name"
                    val vals = snap[k]
                    append("  $way: ")
                    append(when {
                        vals != null -> vals.joinToString(" > ") + "  x${counts[k]}"
                        errors[k] != null -> "FAILED ${errors[k]}"
                        else -> "nothing"
                    })
                    append("\n")
                }
            }
            append("\nMANAGER METHODS\n")
            for (m in signals.generalMethods().take(120)) append("$m\n")
        }
    }

    private fun buildScanReport(rows: List<EcarxCar.ScanRow>, secs: Long): String = buildString {
        append("SCAN ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}  ")
        append("${Build.DISPLAY}  (${secs}s)\n")
        val byStatus = rows.groupingBy { it.kind to it.status }.eachCount()
        for (kind in EcarxCatalog.Kind.entries) {
            val parts = byStatus.filterKeys { it.first == kind }.entries.joinToString { "${it.key.second} ${it.value}" }
            if (parts.isNotEmpty()) append("${kind.name.lowercase()}: $parts\n")
        }
        for (kind in EcarxCatalog.Kind.entries) {
            val active = rows.filter { it.kind == kind && (it.isActive || (kind == EcarxCatalog.Kind.CONFIG && it.value != null)) }
            if (active.isEmpty()) continue
            append("\n${kind.name} (${active.size})\n")
            for (r in active) append(String.format(Locale.US, "%-10s %-44s %s\n", EcarxCar.hex(r.id), r.name.take(44), r.value ?: ""))
        }
        val notActive = rows.filter { it.status == "notactive" }
        if (notActive.isNotEmpty()) {
            append("\nNOT ACTIVE right now (${notActive.size})\n")
            for (r in notActive) append("${EcarxCar.hex(r.id)} ${r.name}\n")
        }
    }

    private fun shareScan() {
        val report = lastScanText ?: run { scanView.text = "Run a scan first."; return }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
        try {
            startActivity(Intent.createChooser(send, "Share scan"))
        } catch (t: Throwable) {
            scanView.text = "No app here can share text. The scan is saved in the file shown below.\n\n$report"
        }
    }

    /**
     * Steering estimate from the four wheel tooth counters (WheelTurn), since the car does not
     * send the steering angle to the head unit. Drive slowly through turns during it. Read-only.
     */
    private fun startWheelWatch() {
        if (rawHunting || hunting) return
        if (!car.isStarted) { scanView.text = "Tap Reconnect first. ${car.lastError ?: ""}"; return }
        rawHunting = true
        wheelButton.isEnabled = false
        scanView.text = "Connecting to raw signals..."
        Thread {
            val report = try { runWheelWatch() } catch (t: Throwable) { "Wheel turn watch failed: $t" }
            val file = runCatching {
                File(getExternalFilesDir(null) ?: filesDir,
                    "wheel-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt").also { it.writeText(report) }
            }.getOrNull()
            main.post {
                lastScanText = report
                scanView.text = report + (file?.let { "\nSaved to ${it.absolutePath}" } ?: "")
                rawHunting = false
                wheelButton.isEnabled = true
            }
        }.start()
    }

    private fun runWheelWatch(): String {
        var connected = signals.connect(car)
        var waited = 0
        while (!connected && waited < 3000) { Thread.sleep(300); waited += 300; connected = signals.isConnected }
        if (!connected) return "Wheel turn watch: car_signal NOT connected: ${signals.lastError}"
        val names = listOf(WheelTurn.FRONT_LEFT, WheelTurn.FRONT_RIGHT, WheelTurn.REAR_LEFT, WheelTurn.REAR_RIGHT, EcarxSignals.SPEED, EcarxSignals.TURN_STATE)
        val ids = names.map { signals.ids[it] ?: return "Wheel turn watch: signal $it not on this car" }
        signals.subscribe(ids) { _, _ -> }
        val turn = WheelTurn()
        val log = StringBuilder()
        val start = SystemClock.elapsedRealtime()
        val end = start + WHEEL_SECONDS * 1000L
        var last: WheelTurn.Estimate? = null
        var samples = 0
        while (rawHunting && SystemClock.elapsedRealtime() < end) {
            val v = ids.map { signals.number(it) }
            val c = v.take(4).map { it?.toInt() }
            if (c.all { it != null }) {
                val e = turn.update(c[0]!!, c[1]!!, c[2]!!, c[3]!!)
                if (e != null) {
                    last = e
                    samples++
                    val t = (SystemClock.elapsedRealtime() - start) / 1000.0
                    if (samples <= 400) log.append(String.format(Locale.US, "%5.1fs  speed %5.2f  indicator %s  teeth L %3d R %3d  radius %7.1f m  wheel %6.1f deg\n",
                        t, v[4]?.toDouble() ?: -1.0, v[5]?.toInt() ?: -1, e.rearLeftTeeth, e.rearRightTeeth,
                        if (e.radiusM.isInfinite()) 9999.0 else e.radiusM, e.steeringDeg))
                }
            }
            val left = (end - SystemClock.elapsedRealtime()) / 1000
            val shown = last?.let { String.format(Locale.US, "steering about %.0f deg (%s)", it.steeringDeg,
                if (it.steeringDeg > 20) "left" else if (it.steeringDeg < -20) "right" else "straight") } ?: "waiting for the wheels to roll"
            main.post { scanView.text = "Wheel turn watch, ${left}s left.\nDrive slowly: straight, then a full-lock left turn, then a full-lock right turn.\n\n$shown\ncounters ${c.joinToString(" ")}" }
            Thread.sleep(50)
        }
        signals.unsubscribe()
        return "WHEEL TURN WATCH ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}  ${Build.DISPLAY}\n" +
            "Estimate from rear wheel tooth counters. GUESS constants: track ${turn.trackM} m, wheelbase ${turn.wheelbaseM} m, steering ratio ${turn.steeringRatio}. Left positive.\n" +
            "samples $samples\n\n$log"
    }

    /** Lists the head unit's camera / parking / vehicle apps, so we can see who draws the guide lines. */
    private fun findCameraApps() {
        val report = try { CameraApps.describe(CameraApps.find(this)) } catch (t: Throwable) { "Find camera apps failed: $t" }
        lastScanText = report
        scanView.text = report + "\nTap Share scan to send this list."
    }

    /** Zips those apps' APK files and opens the share sheet. Copies files only, runs nothing. */
    private fun packCameraApps() {
        if (packing) return
        packing = true
        scanView.text = "Finding camera apps..."
        Thread {
            val msg = try {
                val list = CameraApps.find(this)
                val (file, where) = CameraApps.zip(this, list) { p -> main.post { scanView.text = p } }
                main.post {
                    try { startActivity(android.content.Intent.createChooser(CameraApps.shareIntent(this, file), "Send camera APKs")) }
                    catch (_: Throwable) { }
                }
                String.format(Locale.US, "Packed %d apps, %.1f MB, saved to %s.\nIf the share sheet does not offer Bluetooth, copy that file to a USB stick.\n\n%s",
                    list.size, file.length() / 1e6, where, CameraApps.describe(list))
            } catch (t: Throwable) { "Packing failed: $t" }
            main.post { scanView.text = msg; lastScanText = msg; packing = false }
        }.start()
    }

    private fun short(c: String) = when (c) {
        "CONFIRMED" -> "ok"
        "OBSERVED" -> "seen"
        "GUESS" -> "guess"
        else -> "n/a"
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
            CarSensor.IGNITION.id -> "+${CarDecode.ignitionStep(e)} ${CarDecode.ignition(e)}"
            else -> catalog.valueName(EcarxCatalog.Kind.SENSOR, id, e) ?: e.toString()
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
        row.addView(button("Reconnect") { car.stop(); gly.disconnect(); connect() })
        keysButton = button("Listen to wheel buttons (${KEY_LISTEN_SECONDS}s)") { toggleKeys() }
        row.addView(keysButton)
        row.addView(button("Clear log") { log.clear(); logView.text = "" })
        root.addView(row, spaced())

        val scanRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START }
        scanButton = button("Scan everything (${catalog.entries.size} ids)") { scanAll() }
        scanRow.addView(scanButton)
        scanRow.addView(button("Share scan") { shareScan() })
        huntButton = button("Find what changes (${HUNT_SECONDS}s)") { startHunt() }
        scanRow.addView(huntButton)
        rawButton = button("Raw signal hunt (${RAW_SECONDS}s)") { startRawHunt() }
        scanRow.addView(rawButton)
        steerButton = button("Steering watch (${STEER_SECONDS}s)") { startSteerWatch() }
        scanRow.addView(steerButton)
        root.addView(scanRow, spaced())
        val appRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START }
        appRow.addView(button("Find camera apps") { findCameraApps() })
        appRow.addView(button("Pack and share their APKs") { packCameraApps() })
        wheelButton = button("Wheel turn watch (${WHEEL_SECONDS}s)") { startWheelWatch() }
        appRow.addView(wheelButton)
        root.addView(appRow, spaced())
        scanView = text(14f, Color.rgb(255, 180, 140), mono = true).apply {
            text = "Scan asks the car about every id ECARX names and lists the ones it supports. Read-only, takes under a minute."
        }.also { root.addView(it, spaced()) }

        extraView = text(13f, Color.rgb(220, 200, 255), mono = true).also { root.addView(it, spaced()) }

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
        const val HUNT_SECONDS = 30
        const val STEER_SECONDS = 60
        const val RAW_SECONDS = 90
        const val WHEEL_SECONDS = 90

        /** The short list the steering watch reads many times a second. */
        val STEER_FOCUS = Regex("^SteerWhlSnsr|^SteerActReGroupAg|SteerWhlAg|^ALat|^ALgt|^VehSpdIndcd$|^BrkPedl|^AccrPedl|PinionSteer|WhlAg|^VehSpdLgt(A|Qf|Cntr)$|^WhlSpdCircuml.*(Le|Ri)$|^EngNSafeEngN$|YawRate$|^IndcrSts$|TurnIndr(Le|Ri)$|^ALat1|^ALgt1")
    }
}
