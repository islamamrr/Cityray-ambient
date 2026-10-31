package dev.carapps.guestbook

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/** One signature, full size. Swipe sideways to leaf through the book. */
class PageScreen(private val activity: MainActivity, private val entries: List<Entry>, private var index: Int) {

    val view: View
    private val page = PageView(activity)
    private val counter = activity.label("", 18f, Palette.textDim).apply { gravity = Gravity.CENTER }
    private val delete = activity.textButton("Delete", Palette.danger) { onDelete() }
    private var armedDelete = false

    init {
        val ctx = activity
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.night)
            setPadding(ctx.dpi(24), ctx.dpi(24), ctx.dpi(24), ctx.dpi(24))
        }
        val bar = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(ctx.textButton("‹  Back") { activity.showWall() })
        bar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(ctx.textButton("Save picture", Palette.gold) {
            val e = entries[index]
            activity.saveToGallery(listOf(e))
        })
        bar.addView(delete)
        root.addView(bar)

        root.addView(page, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = ctx.dpi(16); bottomMargin = ctx.dpi(16)
        })
        root.addView(counter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        page.onSwipe = { next -> show(if (next) index + 1 else index - 1) }
        show(index)
        view = root
    }

    private fun show(i: Int) {
        if (i !in entries.indices) return
        index = i
        val e = entries[i]
        page.entry = e
        page.signature = activity.store.loadSignature(e, 1400)
        counter.text = if (entries.size > 1) "${i + 1} of ${entries.size}   ·   swipe to turn the page" else ""
        disarm()
    }

    /** Delete takes two taps, so a guest can't wipe a page by accident. */
    private fun onDelete() {
        if (!armedDelete) {
            armedDelete = true
            delete.text = "Tap again to delete"
            delete.postDelayed({ disarm() }, 4000)
        } else {
            activity.deleteEntry(entries[index])
        }
    }

    private fun disarm() {
        armedDelete = false
        delete.text = "Delete"
    }
}
