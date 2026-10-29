package dev.carapps.ambient

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

    private lateinit var ambient: AmbientView
    private lateinit var media: MediaWatcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = getSharedPreferences("ambient", MODE_PRIVATE)

        ambient = AmbientView(this)
        ambient.themeIndex = prefs.getInt("theme", 0)
        ambient.dimmed = prefs.getBoolean("dim", false)
        setContentView(ambient)

        media = MediaWatcher(this) { np -> ambient.setNowPlaying(np, media.hasAccess()) }

        ambient.listener = object : AmbientView.Listener {
            override fun onThemeChanged(theme: Int) {
                prefs.edit().putInt("theme", theme).apply()
            }

            override fun onDimChanged(dimmed: Boolean) {
                prefs.edit().putBoolean("dim", dimmed).apply()
            }

            override fun onPlayPause() = media.playPause()
            override fun onNext() = media.next()
            override fun onPrevious() = media.previous()
            override fun onRequestAccess() = openListenerSettings()
        }

        if (!prefs.getBoolean("hinted", false)) {
            ambient.flashMessage("Swipe to change theme  ·  Double-tap to dim", 6000)
            prefs.edit().putBoolean("hinted", true).apply()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        media.start()
        ambient.invalidate()
    }

    override fun onPause() {
        media.stop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
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
        ambient.flashMessage("Settings are locked here. Grant access over ADB (see README).", 6000)
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
