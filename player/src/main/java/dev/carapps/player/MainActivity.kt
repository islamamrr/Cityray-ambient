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
    private lateinit var updater: Updater
    private var update: Updater.Release? = null
    private lateinit var lyricsStore: LyricsStore
    private var lyricsKey = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        player = PlayerView(this)
        setContentView(player)

        lyricsStore = LyricsStore(this)
        media = MediaHub(this) { track, sources ->
            player.setTrack(track, sources, media.hasAccess())
            if (track != null) loadLyrics(track)
        }
        updater = Updater(this)

        player.listener = object : PlayerView.Listener {
            override fun onPlayPause() = media.playPause()
            override fun onNext() = media.next()
            override fun onPrevious() = media.previous()
            override fun onSeek(ms: Long) = media.seekTo(ms)
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
            player.flashMessage("Swipe the cover to skip  ·  Tap the clock to see what the app sends", 7000)
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
    }

    override fun onPause() {
        media.stop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Looks up lyrics once per song; the store answers from its cache when it can. */
    private fun loadLyrics(track: Track) {
        val key = track.artist + "\n" + track.title
        if (key == lyricsKey) return
        lyricsKey = key
        player.lyrics = Lyrics.Loading
        lyricsStore.load(track.title, track.artist, track.album, track.durationMs) {
            player.lyrics = it
            // No internet: try again on the next update from the music app.
            if (it == Lyrics.Offline) lyricsKey = ""
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
