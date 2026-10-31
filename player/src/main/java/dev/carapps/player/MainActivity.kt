package dev.carapps.player

import android.app.Activity
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var player: PlayerView
    private lateinit var media: MediaHub
    private lateinit var audio: AudioManager

    // Catches volume changes made elsewhere, such as the steering-wheel buttons.
    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = syncVolume()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        audio = getSystemService(AudioManager::class.java)

        player = PlayerView(this)
        setContentView(player)

        media = MediaHub(this) { track, sources -> player.setTrack(track, sources, media.hasAccess()) }

        player.listener = object : PlayerView.Listener {
            override fun onPlayPause() = media.playPause()
            override fun onNext() = media.next()
            override fun onPrevious() = media.previous()
            override fun onSeek(ms: Long) = media.seekTo(ms)
            override fun onSelectSource(packageName: String) = media.select(packageName)
            override fun onOpenApp(packageName: String) = openApp(packageName)
            override fun onRequestAccess() = openListenerSettings()

            override fun onVolume(fraction: Float) {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                try {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (fraction * max).roundToInt(), 0)
                } catch (_: SecurityException) {
                }
            }
        }

        val prefs = getSharedPreferences("player", MODE_PRIVATE)
        if (!prefs.getBoolean("hinted", false)) {
            player.flashMessage("Swipe the cover to skip  ·  Tap the lit app chip to open it", 7000)
            prefs.edit().putBoolean("hinted", true).apply()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        media.start()
        player.launchApps = findMusicApps()
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
        syncVolume()
    }

    override fun onPause() {
        contentResolver.unregisterContentObserver(volumeObserver)
        media.stop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun syncVolume() {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max > 0) player.setVolumeLevel(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max)
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
