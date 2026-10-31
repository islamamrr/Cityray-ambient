package dev.carapps.guestbook

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView
import dev.carapps.guestbook.car.CarSignals
import kotlin.concurrent.thread

class MainActivity : Activity() {

    lateinit var store: EntryStore
        private set
    private val car = CarSignals.create()

    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var flashView: TextView
    private val main = Handler(Looper.getMainLooper())
    private var onWall = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = EntryStore(this)
        car.connect(this)

        content = FrameLayout(this)
        flashView = label("", 22f, Palette.night, Fonts.sansMedium).apply {
            gravity = Gravity.CENTER
            background = roundRect(Palette.gold, dp(28f))
            setPadding(dpi(28), dpi(16), dpi(28), dpi(16))
            visibility = View.GONE
            elevation = dp(8f)
        }
        root = FrameLayout(this).apply {
            setBackgroundColor(Palette.night)
            addView(content)
            addView(flashView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = dpi(40) })
        }
        setContentView(root)
        showWall()
    }

    override fun onDestroy() {
        car.disconnect()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Deprecated("Still the simplest way to catch Back on Android 11")
    override fun onBackPressed() {
        if (onWall) super.onBackPressed() else showWall()
    }

    // Screens

    fun showWall() {
        car.snapshot() // refreshes the status line
        setScreen(WallScreen(this, store.all(), car.status()).view, wall = true)
    }

    fun showSign() = setScreen(SignScreen(this).view, wall = false)

    fun showPage(index: Int) = setScreen(PageScreen(this, store.all(), index).view, wall = false)

    private fun setScreen(v: View, wall: Boolean) {
        hideKeyboard()
        onWall = wall
        content.removeAllViews()
        content.addView(v)
    }

    // Actions

    fun saveEntry(name: String, note: String, ink: Int, signature: Bitmap?) {
        val entry = store.add(name, note, ink, signature, car.snapshot())
        showWall()
        val who = entry.name.ifEmpty { "friend" }
        flash("Thank you, $who! You're guest #${entry.number}")
    }

    fun deleteEntry(entry: Entry) {
        store.delete(entry)
        showWall()
        flash("Page removed")
    }

    fun saveAllToGallery() = saveToGallery(store.all())

    fun saveToGallery(entries: List<Entry>) {
        flash("Saving…")
        val appContext = applicationContext
        thread {
            var ok = 0
            for (e in entries) {
                val pic = CardRenderer.renderPicture(e, store.loadSignature(e, 1400))
                if (Gallery.save(appContext, pic, Gallery.fileName(e))) ok++
                pic.recycle()
            }
            main.post {
                flash(
                    when {
                        ok == entries.size && ok == 1 -> "Saved to Pictures/${Gallery.FOLDER}"
                        ok == entries.size -> "Saved $ok pages to Pictures/${Gallery.FOLDER}"
                        ok == 0 -> "Couldn't save to the gallery"
                        else -> "Saved $ok of ${entries.size} pages"
                    }
                )
            }
        }
    }

    fun flash(message: String) {
        flashView.text = message
        flashView.visibility = View.VISIBLE
        flashView.alpha = 0f
        flashView.animate().alpha(1f).setDuration(200).start()
        main.removeCallbacksAndMessages(FLASH_TOKEN)
        main.postAtTime({
            flashView.animate().alpha(0f).setDuration(300).withEndAction { flashView.visibility = View.GONE }.start()
        }, FLASH_TOKEN, android.os.SystemClock.uptimeMillis() + 3500)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    private companion object {
        val FLASH_TOKEN = Any()
    }
}
