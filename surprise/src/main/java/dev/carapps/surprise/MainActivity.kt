package dev.carapps.surprise

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import dev.carapps.car.CarDecode

class MainActivity : Activity() {

    private lateinit var game: GameView
    private lateinit var car: CarLink
    private lateinit var sfx: Sfx

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sfx = Sfx()
        car = CarLink(this) { old, new -> onGear(old, new) }
        game = GameView(this, car, Steering(this), sfx)
        setContentView(game)
    }

    /**
     * Park only. Out of Park the game pauses behind a "Park to play" screen, and on a
     * P -> other shift the app also steps aside so the car's normal screen comes back.
     * Only a known gear blocks, so a missing reading can't lock the app.
     */
    private fun onGear(old: CarDecode.Gear, new: CarDecode.Gear) {
        val moving = new != CarDecode.Gear.P && new != CarDecode.Gear.UNKNOWN
        game.blocked = moving
        if (old == CarDecode.Gear.P && moving) moveTaskToBack(true)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        car.start()
        game.resume()
    }

    override fun onPause() {
        game.pause()
        car.stop()
        game.blocked = false
        super.onPause()
    }

    override fun onDestroy() {
        sfx.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
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
