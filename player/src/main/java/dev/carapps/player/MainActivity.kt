package dev.carapps.player

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

class MainActivity : Activity() {

    private lateinit var player: PlayerView
    private lateinit var media: MediaHub
    private lateinit var drive: DriveWatcher
    private lateinit var updater: Updater
    private var update: Updater.Release? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        player = PlayerView(this)
        setContentView(player)

        media = MediaHub(this) { track, sources -> player.setTrack(track, sources, media.hasAccess()) }
        drive = DriveWatcher(this) { driving -> player.driving = driving }
        updater = Updater(this)

        player.listener = object : PlayerView.Listener {
            override fun onPlayPause() = media.playPause()
            override fun onNext() = media.next()
            override fun onPrevious() = media.previous()
            override fun onSeek(ms: Long) = media.seekTo(ms)
            override fun onSelectSource(packageName: String) = media.select(packageName)
            override fun onOpenApp(packageName: String) = openApp(packageName)
            override fun onRequestAccess() = openListenerSettings()
            override fun onAction(action: String) = media.sendAction(action)
            override fun onQueueItem(id: Long) = media.skipToQueueItem(id)

            override fun onInstallUpdate() {
                val r = update ?: return
                updater.install(r) { status -> player.flashMessage(status, 5000) }
            }
        }

        val prefs = getSharedPreferences("player", MODE_PRIVATE)
        if (!prefs.getBoolean("hinted", false)) {
            player.flashMessage("Swipe the cover to skip  ·  Tap the app name to switch apps", 7000)
            prefs.edit().putBoolean("hinted", true).apply()
        }

        // Once per launch: is there a newer build on GitHub?
        updater.check { release ->
            update = release
            player.updateVersion = release?.versionCode ?: 0
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        media.start()
        player.launchApps = findMusicApps()
        drive.start()
    }

    override fun onPause() {
        drive.stop()
        media.stop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Installed apps that say they play music, for the "nothing playing" screen. */
    private fun findMusicApps(): List<LaunchApp> {
        val pm = packageManager
        val found = LinkedHashMap<String, LaunchApp>()
        val queries = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC),
            Intent("android.media.browse.MediaBrowserService"),
        )
        for (q in queries) {
            val results = try {
                if (q.action == Intent.ACTION_MAIN) pm.queryIntentActivities(q, 0) else pm.queryIntentServices(q, 0)
            } catch (_: Exception) {
                emptyList()
            }
            for (r in results) {
                val pkg = (r.activityInfo ?: r.serviceInfo)?.packageName ?: continue
                if (pkg == packageName || pkg in found || pm.getLaunchIntentForPackage(pkg) == null) continue
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    found[pkg] = LaunchApp(pkg, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info))
                } catch (_: Exception) {
                }
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    private fun openApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            player.flashMessage("Can't open that app from here.", 3000)
            return
        }
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            player.flashMessage("Can't open that app from here.", 3000)
        }
    }

    private fun openListenerSettings() {
        val candidates = listOf(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
        player.flashMessage("Settings are locked here. Grant access over ADB (see README).", 6000)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }
}
