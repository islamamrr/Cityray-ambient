package dev.carapps.player

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the repo's "player-latest" GitHub release for a newer build and installs it
 * with Android's package installer. The release notes carry "versionCode=N".
 */
class Updater(context: Context) {

    private val context = context.applicationContext

    class Release(val versionCode: Int, val apkUrl: String)

    private val main = Handler(Looper.getMainLooper())

    fun installedVersion(): Int = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    } catch (_: Exception) {
        0
    }

    /** Calls back on the main thread with a newer release, or null if there's none or no internet. */
    fun check(onResult: (Release?) -> Unit) {
        Thread {
            val release = try {
                val json = JSONObject(get(RELEASE_URL, api = true).bufferedReader().use { it.readText() })
                val code = Regex("versionCode=(\\d+)").find(json.optString("body"))?.groupValues?.get(1)?.toInt() ?: 0
                val assets = json.getJSONArray("assets")
                val url = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") }
                    ?.optString("browser_download_url")
                if (url != null && code > installedVersion()) Release(code, url) else null
            } catch (_: Exception) {
                null
            }
            main.post { onResult(release) }
        }.start()
    }

    /**
     * Downloads the APK into an install session. Android then shows its own
     * "Do you want to update this app?" screen. [onStatus] gets short progress text.
     */
    fun install(release: Release, onStatus: (String) -> Unit) {
        Thread {
            try {
                post(onStatus, "Downloading update…")
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    get(release.apkUrl).use { input ->
                        session.openWrite("player.apk", 0, -1).use { out ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
                    }
                    listenForResult(id, onStatus)
                    val intent = Intent(ACTION_RESULT).setPackage(context.packageName)
                    val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT)
                    session.commit(pending.intentSender)
                }
            } catch (e: Exception) {
                post(onStatus, "Update failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }.start()
    }

    private fun listenForResult(sessionId: Int, onStatus: (String) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != sessionId) return
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                        try {
                            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {
                            onStatus("The car blocked the installer. Install the APK from the file explorer.")
                        }
                        return
                    }
                    PackageInstaller.STATUS_SUCCESS -> onStatus("Updated")
                    else -> onStatus(
                        "Update failed: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown reason")
                    )
                }
                runCatching { context.unregisterReceiver(this) }
            }
        }
        // Registered before commit() so the result can't arrive first.
        context.registerReceiver(receiver, IntentFilter(ACTION_RESULT), null, main)
    }

    private fun get(url: String, api: Boolean = false) = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 10_000
        readTimeout = 30_000
        if (api) setRequestProperty("Accept", "application/vnd.github+json")
        instanceFollowRedirects = true
        inputStream
    }

    private fun post(onStatus: (String) -> Unit, text: String) = main.post { onStatus(text) }

    companion object {
        private const val RELEASE_URL =
            "https://api.github.com/repos/islamamrr/Cityray-ambient/releases/tags/player-latest"
        private const val ACTION_RESULT = "dev.carapps.player.INSTALL_RESULT"
    }
}
