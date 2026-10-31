package dev.carapps.guestbook

import android.graphics.Bitmap
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout

/** Home screen: every signature so far, newest first, and a big button to sign. */
class WallScreen(
    private val activity: MainActivity,
    private val entries: List<Entry>,
    carStatus: String,
) {
    private val store = activity.store
    private val thumbs = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    val view: View

    init {
        val ctx = activity
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.night)
            setPadding(ctx.dpi(28), ctx.dpi(36), ctx.dpi(28), ctx.dpi(20))
        }

        // Header
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(ctx.label("Guest Book", 46f, Palette.text, Fonts.serifBold))
        titles.addView(ctx.label(subtitle(entries.size), 20f, Palette.textDim))
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (entries.isNotEmpty()) {
            header.addView(ctx.textButton("Save all to gallery", Palette.gold) { activity.saveAllToGallery() })
        }
        root.addView(header)

        // Gold rule under the title
        root.addView(View(ctx).apply { setBackgroundColor(Palette.gold) },
            LinearLayout.LayoutParams(ctx.dpi(96), ctx.dpi(3)).apply { topMargin = ctx.dpi(14); bottomMargin = ctx.dpi(22) })

        // The wall
        val wallBox = FrameLayout(ctx)
        if (entries.isEmpty()) {
            wallBox.addView(ctx.label("No signatures yet.\nBe the first to sign the book.", 26f, Palette.textDim, Fonts.serifItalic).apply {
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.3f)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            val grid = GridView(ctx).apply {
                numColumns = 2
                horizontalSpacing = ctx.dpi(18)
                verticalSpacing = ctx.dpi(18)
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                selector = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                adapter = Adapter()
                setOnItemClickListener { _, _, position, _ -> activity.showPage(position) }
            }
            wallBox.addView(grid)
        }
        root.addView(wallBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Sign button
        root.addView(ctx.pillButton("✍  Sign the guest book", Palette.gold, Palette.night, 26f) { activity.showSign() }.apply {
            minHeight = ctx.dpi(84)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dpi(20)
        })

        // Car link status, so we can see which readings the car allows.
        root.addView(ctx.label(carStatus, 13f, Palette.textDim).apply {
            gravity = Gravity.CENTER
            setPadding(0, ctx.dpi(12), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        view = root
    }

    private fun subtitle(n: Int) = when (n) {
        0 -> "Everyone who rides along signs here"
        1 -> "1 guest has signed"
        else -> "$n guests have signed"
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = entries[position].createdAt

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val card = (convertView as? EntryCardView) ?: EntryCardView(activity)
            val e = entries[position]
            card.entry = e
            card.signature = thumbs.get(e.id) ?: store.loadSignature(e, 500)?.also { thumbs.put(e.id, it) }
            card.invalidate()
            return card
        }
    }
}

