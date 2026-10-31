package dev.carapps.guestbook

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View

object Palette {
    val night = Color.parseColor("#0B0F18")
    val panel = Color.parseColor("#161C2A")
    val paper = Color.parseColor("#F4EBD9")
    val paperEdge = Color.parseColor("#E2D4B7")
    val paperLine = Color.parseColor("#D8C8A8")
    val gold = Color.parseColor("#C9A227")
    val text = Color.parseColor("#F2F2F2")
    val textDim = Color.parseColor("#8A93A6")
    val ink = Color.parseColor("#2A2A2A")
    val inkDim = Color.parseColor("#7A6F5C")
    val danger = Color.parseColor("#E5484D")
}

object Ink {
    val colors = intArrayOf(
        Color.parseColor("#1D2B53"), // navy
        Color.parseColor("#1A1A1A"), // black
        Color.parseColor("#B3261E"), // red
        Color.parseColor("#1E6B4A"), // green
        Color.parseColor("#6B3FA0"), // purple
        Color.parseColor("#B07D10"), // gold
    )
}

object Fonts {
    val serif: Typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    val serifBold: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    val serifItalic: Typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    val sans: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val sansMedium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

fun Context.dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
fun Context.dpi(v: Int): Int = dp(v.toFloat()).toInt()
fun View.dp(v: Float): Float = context.dp(v)
fun View.dpi(v: Int): Int = context.dpi(v)

fun roundRect(color: Int, radius: Float, strokeColor: Int? = null, strokeWidth: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (strokeColor != null) setStroke(strokeWidth, strokeColor)
}
