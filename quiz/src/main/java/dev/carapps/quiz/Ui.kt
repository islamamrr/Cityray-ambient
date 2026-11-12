package dev.carapps.quiz

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

object Palette {
    const val BG = 0xFF0E1016.toInt()
    const val CARD = 0xFF161922.toInt()
    const val CARD2 = 0xFF1E222D.toInt()
    const val LINE = 0xFF2C3040.toInt()
    const val INK = 0xFFF1F2F5.toInt()
    const val MUTED = 0xFFA3A9B6.toInt()
    const val GOLD = 0xFFF5B84B.toInt()
    const val RIGHT = 0xFF2FBF7A.toInt()
    const val WRONG = 0xFFE5484D.toInt()
    const val DARK_TEXT = 0xFF14161D.toInt()
    val TEAM = intArrayOf(0xFFFF7A59.toInt(), 0xFF4DA3FF.toInt())
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density).roundToInt()

fun Context.rounded(color: Int, radiusDp: Number = 18, strokeDp: Number = 0, strokeColor: Int = 0) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp.toFloat() > 0f) setStroke(dp(strokeDp), strokeColor)
    }

fun Context.label(text: String, sp: Float, color: Int = Palette.INK, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }

/** A flat, rounded, tappable box. Dims a little while pressed. */
@SuppressLint("ClickableViewAccessibility")
fun Context.tile(text: String, sp: Float, bg: Int, fg: Int = Palette.INK, onClick: (() -> Unit)? = null): TextView =
    label(text, sp, fg, bold = true).apply {
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        background = rounded(bg)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        if (onClick != null) {
            isClickable = true
            setOnClickListener { onClick() }
            setOnTouchListener { v, e ->
                if (v.isEnabled) when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.alpha = 0.75f
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 1f
                }
                false
            }
        }
    }

fun Context.column(gapDp: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (gapDp > 0) {
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply { setSize(1, dp(gapDp)); setColor(0) }
    }
}

fun Context.row(gapDp: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    if (gapDp > 0) {
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply { setSize(dp(gapDp), 1); setColor(0) }
    }
}

fun wrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
fun fill() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
fun weight(w: Float = 1f) = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)
fun fillWeight(w: Float = 1f) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, w)

/** The countdown: a rounded bar that shrinks from full to empty. */
class TimerBar(context: Context) : View(context) {
    var fraction = 1f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var color = Palette.GOLD
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        paint.color = Palette.LINE
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(box, r, r, paint)
        paint.color = color
        val w = width * fraction
        if (layoutDirection == LAYOUT_DIRECTION_RTL) box.set(width - w, 0f, width.toFloat(), height.toFloat())
        else box.set(0f, 0f, w, height.toFloat())
        if (w > 0f) canvas.drawRoundRect(box, r, r, paint)
    }
}
