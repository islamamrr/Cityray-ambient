package dev.carapps.probe

import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.shell.AdbShellPacket
import dev.carapps.car.VendorSteering
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Reads the steering angle by talking to this head unit's own wireless-debugging adbd, with no
 * laptop and no Shizuku. The one-time pairing code is typed into Car Probe itself (the head
 * unit has no notification shade, so Shizuku's own pairing can't finish). After pairing, the
 * app keeps its key and connects to the wireless-debugging port on 127.0.0.1.
 *
 * What this means: Car Probe holds an adb key that this head unit trusts, so it can run shell
 * commands. It only ever runs the read command `cmd car_service get-property-value`. Unpair it
 * in Developer options > Wireless debugging > Paired devices, or clear the app's data.
 * Uses the Kadb library (Apache-2.0), through its documented API.
 */
class SelfAdb(private val dir: File) {

    private val certFile = File(dir, "selfadb-cert.pem")
    private val keyFile = File(dir, "selfadb-key.pem")

    @Volatile private var kadb: Kadb? = null

    /** True once a key pair has been made (pairing may still be needed). */
    val hasKey: Boolean get() = certFile.exists() && keyFile.exists()

    private fun ensureKeys() {
        if (hasKey) {
            val ok = runCatching { KadbCert.set(certFile.readBytes(), keyFile.readBytes()) }.isSuccess
            if (ok) return
        }
        val (cert, key) = KadbCert.get()
        certFile.writeBytes(cert)
        keyFile.writeBytes(key)
    }

    /** One-time pairing with the code and port shown in Wireless debugging > Pair with pairing code. Returns an error text or null. */
    fun pair(pairPort: Int, code: String): String? = try {
        ensureKeys()
        runBlocking { Kadb.pair(HOST, pairPort, code.trim()) }
        null
    } catch (t: Throwable) {
        "pairing failed: ${t.message ?: t}"
    }

    /**
     * Connects to the wireless-debugging [port] and calls [onRaw] with every reading of [id]
     * (raw int32). Blocks until [stop] or the connection ends. Returns an error text or null.
     */
    fun run(port: Int, id: Int = VendorSteering.ARHUD_ANGLE, onRaw: (Int) -> Unit): String? {
        val k = try {
            ensureKeys()
            Kadb.create(HOST, port)
        } catch (t: Throwable) {
            return "connect failed: ${t.message ?: t}"
        }
        kadb = k
        try {
            k.openShell("while true; do ${VendorSteering.cmdFor(id)}; done").use { shell ->
                val buf = StringBuilder()
                while (true) {
                    when (val p = shell.read()) {
                        is AdbShellPacket.StdOut -> {
                            buf.append(String(p.payload))
                            while (true) {
                                val nl = buf.indexOf("\n")
                                if (nl < 0) break
                                val line = buf.substring(0, nl)
                                buf.delete(0, nl + 1)
                                VendorSteering.parseCmdLine(line)?.let(onRaw)
                            }
                        }
                        is AdbShellPacket.Exit -> return "shell ended (exit ${p.payload.firstOrNull()})"
                        else -> {}
                    }
                }
            }
        } catch (t: Throwable) {
            if (kadb != null) return "adb failed: ${t.message ?: t}"
        } finally {
            runCatching { k.close() }
            kadb = null
        }
        return null
    }

    fun stop() {
        val k = kadb
        kadb = null
        runCatching { k?.close() }
    }

    companion object {
        const val HOST = "127.0.0.1"
    }
}
