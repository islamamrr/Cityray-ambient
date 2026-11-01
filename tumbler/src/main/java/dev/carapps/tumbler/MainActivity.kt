package dev.carapps.tumbler

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

class MainActivity : Activity() {

    private lateinit var view: TumblerView
    private lateinit var wheel: Wheel
    private lateinit var sounds: Sounds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sounds = Sounds(this)
        wheel = Wheel(this) { old, new -> onPark(old, new) }
        view = TumblerView(this, wheel, sounds)
        setContentView(view)
        askCarPermissions()
    }

    /**
     * Android's car API guards gear, speed and steering behind these. The ones the head unit
     * treats as runtime permissions get a prompt; the rest are granted at install or refused.
     */
    private fun askCarPermissions() {
        val wanted = CAR_PERMISSIONS.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) runCatching { requestPermissions(wanted.toTypedArray(), 1) }
    }

    /**
     * Park only. Out of Park the game pauses behind a "Shift to P" screen, and on a
     * P -> other shift the app also steps aside so the car's normal screen comes back.
     * With no gear reading at all (a phone) nothing blocks.
     */
    private fun onPark(old: Wheel.Park, new: Wheel.Park) {
        val moving = new == Wheel.Park.NOT_PARKED
        view.blocked = moving
        if (old == Wheel.Park.PARKED && moving && !view.showingSignals) moveTaskToBack(true)
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

    companion object {
        private val CAR_PERMISSIONS = listOf(
            "android.car.permission.CAR_POWERTRAIN",
            "android.car.permission.CAR_SPEED",
            "android.car.permission.READ_CAR_STEERING",
        )
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
