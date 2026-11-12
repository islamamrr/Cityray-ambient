package dev.carapps.probe

import android.content.pm.PackageManager
import dev.carapps.car.VendorSteering
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Reads the steering angle through Shizuku: Shizuku runs a shell-uid process for us, and that
 * process loops `cmd car_service get-property-value` (the only route that works for the
 * vendor steering properties). Read-only: it never runs set or inject commands.
 */
class ShizukuSteering {

    enum class State { NOT_RUNNING, NO_PERMISSION, READY }

    fun state(): State = when {
        !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> State.NOT_RUNNING
        Shizuku.isPreV11() -> State.NOT_RUNNING
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> State.NO_PERMISSION
        else -> State.READY
    }

    /** Shizuku's uid: 2000 shell (wireless debugging start) or 0 root. */
    fun uid(): Int = runCatching { Shizuku.getUid() }.getOrDefault(-1)

    fun requestPermission(code: Int) = Shizuku.requestPermission(code)

    @Volatile private var process: Process? = null

    /**
     * Starts the read loop and calls [onRaw] for every reading of [id] (raw int32).
     * Blocks the calling thread until [stop] or the process ends. Returns an error text or null.
     */
    fun run(id: Int = VendorSteering.ARHUD_ANGLE, onRaw: (Int) -> Unit): String? {
        val cmd = arrayOf("sh", "-c", "while true; do ${VendorSteering.cmdFor(id)}; done")
        val p = try {
            newProcess(cmd)
        } catch (t: Throwable) {
            return "Shizuku newProcess failed: ${t.cause ?: t}"
        }
        process = p
        try {
            BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    VendorSteering.parseCmdLine(line)?.let(onRaw)
                }
            }
        } catch (t: Throwable) {
            if (process != null) return "read failed: $t"
        } finally {
            runCatching { p.destroy() }
            process = null
        }
        return null
    }

    fun stop() {
        val p = process
        process = null
        runCatching { p?.destroy() }
    }

    /** Shizuku.newProcess is private in API 13 (Shizuku prefers UserService), so call it by reflection. */
    private fun newProcess(cmd: Array<String>): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, cmd, null, null) as Process
    }
}
