package dev.carapps.gamekit

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/** Small immediate-mode drawing helpers shared by the games. */
class Ui {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    /** Draws [s] with its vertical center at [y]. */
    fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        bold: Boolean = false, align: Paint.Align = Paint.Align.CENTER,
    ) {
        setText(size, color, bold, align)
        val fm = paint.fontMetrics
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2f, paint)
    }

    /** Word-wraps [s] to [maxW], centered on [x], starting with its first line centered at [y]. Returns the height used. */
    fun wrap(
        c: Canvas, s: String, x: Float, y: Float, maxW: Float, size: Float, color: Int,
        bold: Boolean = false, align: Paint.Align = Paint.Align.CENTER,
    ): Float {
        val lines = lines(s, maxW, size, bold)
        val step = size * 1.35f
        lines.forEachIndexed { i, line -> text(c, line, x, y + i * step, size, color, bold, align) }
        return lines.size * step
    }

    fun lines(s: String, maxW: Float, size: Float, bold: Boolean = false): List<String> {
        setText(size, Palette.TEXT, bold, Paint.Align.LEFT)
        val out = ArrayList<String>()
        for (para in s.split('\n')) {
            var line = ""
            for (word in para.split(' ')) {
                val tryLine = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && paint.measureText(tryLine) > maxW) {
                    out.add(line)
                    line = word
                } else {
                    line = tryLine
                }
            }
            out.add(line)
        }
        return out
    }

    fun measure(s: String, size: Float, bold: Boolean = false): Float {
        setText(size, Palette.TEXT, bold, Paint.Align.LEFT)
        return paint.measureText(s)
    }

    fun round(c: Canvas, r: RectF, radius: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        c.drawRoundRect(r, radius, radius, paint)
    }

    fun outline(c: Canvas, r: RectF, radius: Float, color: Int, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.color = color
        c.drawRoundRect(r, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }

    fun circle(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        c.drawCircle(x, y, r, paint)
    }

    fun ring(c: Canvas, x: Float, y: Float, r: Float, color: Int, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.color = color
        c.drawCircle(x, y, r, paint)
        paint.style = Paint.Style.FILL
    }

    /** A filled rounded button with a centered label. */
    fun button(
        c: Canvas, r: RectF, label: String, fill: Int, textColor: Int, size: Float,
        pressed: Boolean = false,
    ) {
        round(c, r, r.height() * 0.28f, if (pressed) Palette.darken(fill, 0.25f) else fill)
        text(c, label, r.centerX(), r.centerY(), size, textColor, bold = true)
    }

    private fun setText(size: Float, color: Int, bold: Boolean, align: Paint.Align) {
        paint.style = Paint.Style.FILL
        paint.textSize = size
        paint.color = color
        paint.typeface = if (bold) this.bold else Typeface.DEFAULT
        paint.textAlign = align
    }
}
