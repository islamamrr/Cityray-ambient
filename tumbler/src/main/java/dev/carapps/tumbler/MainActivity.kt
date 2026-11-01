package dev.carapps.tumbler

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import dev.carapps.car.CarDecode

class MainActivity : Activity() {

    private lateinit var view: TumblerView
    private lateinit var wheel: Wheel
    private lateinit var sounds: Sounds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sounds = Sounds(this)
        wheel = Wheel(this) { old, new -> onGear(old, new) }
        view = TumblerView(this, wheel, sounds)
        setContentView(view)
    }

    /**
     * Park only. Out of Park the game pauses behind a "Shift to P" screen, and on a
     * P -> other shift the app also steps aside so the car's normal screen comes back.
     * Only a known gear blocks, so a missing reading can't lock the app.
     */
    private fun onGear(old: CarDecode.Gear, new: CarDecode.Gear) {
        val moving = new != CarDecode.Gear.P && new != CarDecode.Gear.UNKNOWN
        view.blocked = moving
        if (old == CarDecode.Gear.P && moving) moveTaskToBack(true)
    }

    /** Arrow keys and the wheel's next / previous buttons (if the head unit passes them on) nudge the dial. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val step = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> 1
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> -1
            else -> 0
        }
        if (step != 0 && view.nudge(step)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        wheel.start()
        view.resume()
    }

    override fun onPause() {
        view.pause()
        wheel.stop()
        view.blocked = false
        super.onPause()
    }

    override fun onDestroy() {
        sounds.release()
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
