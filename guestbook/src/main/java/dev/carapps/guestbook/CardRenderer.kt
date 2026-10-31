package dev.carapps.guestbook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * Draws one guest book page. Used for the small cards on the wall, the big page view,
 * and the pictures saved to the gallery, so they all look the same.
 * Every size is a fraction of the card width, so it scales to any size.
 */
object CardRenderer {

    private val dateFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    private val dateTimeFmt = SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault())

    private val paper = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val name = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val note = TextPaint(Paint.ANTI_ALIAS_FLAG)

    fun draw(c: Canvas, r: RectF, e: Entry, signature: Bitmap?, full: Boolean) {
        val u = r.width() / 100f
        val pad = u * (if (full) 7f else 6f)

        paper.style = Paint.Style.FILL
        paper.color = Palette.paper
        c.drawRoundRect(r, u * 3, u * 3, paper)
        paper.style = Paint.Style.STROKE
        paper.strokeWidth = u * 0.5f
        paper.color = Palette.paperEdge
        c.drawRoundRect(r, u * 3, u * 3, paper)

        // Header: guest number and date.
        small.typeface = Fonts.sansMedium
        small.textSize = u * (if (full) 3.4f else 6f)
        small.color = Palette.inkDim
        small.textAlign = Paint.Align.LEFT
        val headY = r.top + pad + small.textSize
        c.drawText("GUEST #${e.number}", r.left + pad, headY, small)
        small.textAlign = Paint.Align.RIGHT
        c.drawText((if (full) dateTimeFmt else dateFmt).format(Date(e.createdAt)), r.right - pad, headY, small)

        // Footer area (bottom up): car readings, note, name.
        var bottom = r.bottom - pad
        if (full && e.car.isNotEmpty()) {
            small.typeface = Fonts.sans
            small.textAlign = Paint.Align.CENTER
            small.textSize = u * 3.2f
            val text = e.car.joinToString("   ·   ") { "${it.label} ${it.value}" }
            c.drawText(TextUtils.ellipsize(text, small, r.width() - pad * 2, TextUtils.TruncateAt.END).toString(), r.centerX(), bottom, small)
            bottom -= small.textSize + u * 4
        }

        if (full && e.note.isNotEmpty()) {
            note.typeface = Fonts.serifItalic
            note.textSize = u * 4.6f
            note.color = Palette.ink
            val width = (r.width() - pad * 2).toInt()
            val layout = StaticLayout.Builder.obtain("“${e.note}”", 0, e.note.length + 2, note, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(6)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0f, 1.15f)
                .build()
            val top = bottom - layout.height
            c.save()
            c.translate(r.left + pad, top)
            layout.draw(c)
            c.restore()
            bottom = top - u * 5
        }

        name.typeface = Fonts.serifBold
        name.color = Palette.ink
        name.textAlign = Paint.Align.CENTER
        name.textSize = u * (if (full) 6.5f else 8.5f)
        val shownName = e.name.ifEmpty { "A guest" }
        val nameText = TextUtils.ellipsize(shownName, name, r.width() - pad * 2, TextUtils.TruncateAt.END).toString()
        c.drawText(nameText, r.centerX(), bottom, name)
        bottom -= name.textSize + u * 2.5f

        // Signature line and the signature itself, filling whatever space is left.
        line.color = Palette.gold
        line.strokeWidth = u * 0.5f
        c.drawLine(r.left + pad * 1.5f, bottom, r.right - pad * 1.5f, bottom, line)

        val sigBox = RectF(r.left + pad, headY + u * 3, r.right - pad, bottom - u * 1)
        if (signature != null && sigBox.height() > 0) {
            val s = min(sigBox.width() / signature.width, sigBox.height() / signature.height)
            val w = signature.width * s
            val h = signature.height * s
            val dst = RectF(sigBox.centerX() - w / 2, sigBox.bottom - h, sigBox.centerX() + w / 2, sigBox.bottom)
            c.drawBitmap(signature, Rect(0, 0, signature.width, signature.height), dst, bmpPaint)
        }
    }

    /** A standalone picture of a page, for saving to the gallery. */
    fun renderPicture(e: Entry, signature: Bitmap?, width: Int = 1200): Bitmap {
        val height = (width * 1.35f).toInt()
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Palette.night)
        val m = width * 0.04f
        draw(c, RectF(m, m, width - m, height - m), e, signature, full = true)
        return bmp
    }
}
