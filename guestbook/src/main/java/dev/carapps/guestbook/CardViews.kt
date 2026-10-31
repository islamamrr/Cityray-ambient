package dev.carapps.guestbook

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/** A small page on the signature wall. */
class EntryCardView(context: Context) : View(context) {
    var entry: Entry? = null
    var signature: Bitmap? = null
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.9f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val e = entry ?: return
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        CardRenderer.draw(canvas, rect, e, signature, full = false)
    }
}

/** One full page, centred in the space it has. Swipe sideways to turn to the next page. */
class PageView(context: Context) : View(context) {
    var entry: Entry? = null
        set(value) { field = value; invalidate() }
    var signature: Bitmap? = null
        set(value) { field = value; invalidate() }
    var onSwipe: ((next: Boolean) -> Unit)? = null

    private val rect = RectF()

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vx) > abs(vy) && abs(vx) > dp(400f)) {
                onSwipe?.invoke(vx < 0)
                return true
            }
            return false
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent) = gestures.onTouchEvent(event)

    override fun onDraw(canvas: Canvas) {
        val e = entry ?: return
        val aspect = 1.35f
        val w = min(width.toFloat(), height / aspect)
        val h = w * aspect
        rect.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        CardRenderer.draw(canvas, rect, e, signature, full = true)
    }
}
