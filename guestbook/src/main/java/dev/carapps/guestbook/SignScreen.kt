package dev.carapps.guestbook

import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/** The signing page: name, a sheet to sign on, pen colours, and an optional note. */
class SignScreen(private val activity: MainActivity) {

    val view: View
    private val pad = SignaturePad(activity)
    private val name = activity.field("Your name", multiLine = false)
    private val note = activity.field("Leave a note for the driver (optional)", multiLine = true)
    private val save = activity.pillButton("Sign the book", Palette.gold, Palette.night, 24f) { submit() }
    private val swatches = ArrayList<View>()

    init {
        val ctx = activity
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.night)
            setPadding(ctx.dpi(28), ctx.dpi(24), ctx.dpi(28), ctx.dpi(24))
        }
        fun lp(weight: Float = 0f, top: Int = 0) = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (weight > 0) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
            weight,
        ).apply { topMargin = ctx.dpi(top) }

        // Top bar
        val bar = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(ctx.textButton("‹  Back") { activity.showWall() })
        bar.addView(ctx.label("Guest #${activity.store.nextNumber()}", 20f, Palette.textDim).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar, lp())

        root.addView(ctx.label("Sign the guest book", 38f, Palette.text, Fonts.serifBold), lp(top = 8))
        root.addView(name, lp(top = 20))
        root.addView(pad, lp(weight = 1f, top = 18))

        // Pen colours, undo and clear
        val tools = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        for (color in Ink.colors) {
            val sw = View(ctx).apply {
                tag = color
                isClickable = true
                setOnClickListener { pickInk(color) }
            }
            swatches.add(sw)
            tools.addView(sw, LinearLayout.LayoutParams(ctx.dpi(52), ctx.dpi(52)).apply { marginEnd = ctx.dpi(12) })
        }
        tools.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        tools.addView(ctx.textButton("Undo") { pad.undo() })
        tools.addView(ctx.textButton("Clear") { pad.clear() })
        root.addView(tools, lp(top = 14))

        root.addView(note, lp(top = 14))
        root.addView(save, lp(top = 18))

        pad.onChanged = { refresh() }
        name.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        pickInk(Ink.colors[0])
        refresh()
        view = root
    }

    private fun pickInk(color: Int) {
        pad.inkColor = color
        for (sw in swatches) {
            val selected = sw.tag == color
            sw.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(sw.tag as Int)
                setStroke(activity.dpi(if (selected) 5 else 2), if (selected) Palette.gold else Palette.paperEdge)
            }
        }
    }

    private fun canSave() = !pad.isEmpty || name.text.isNotBlank()

    private fun refresh() {
        save.alpha = if (canSave()) 1f else 0.4f
    }

    private fun submit() {
        if (!canSave()) {
            activity.flash("Sign or write your name first")
            return
        }
        activity.saveEntry(name.text.toString(), note.text.toString(), pad.inkColor, pad.export())
    }
}
