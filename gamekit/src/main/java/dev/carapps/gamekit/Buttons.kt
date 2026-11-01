package dev.carapps.gamekit

import android.graphics.RectF
import android.view.MotionEvent

/**
 * Tap targets for immediate-mode views: register buttons while drawing,
 * then feed touches through [onTouch]. A button fires when the finger lifts inside it.
 */
class Buttons {
    private class Btn(val id: String, val rect: RectF, val action: () -> Unit)

    private val list = ArrayList<Btn>()
    private var pressedId: String? = null

    fun clear() = list.clear()

    fun add(id: String, rect: RectF, action: () -> Unit) {
        list.add(Btn(id, RectF(rect), action))
    }

    fun isPressed(id: String) = pressedId == id

    /** Returns true when the touch landed on a button. */
    fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedId = find(e.x, e.y)?.id
                return pressedId != null
            }
            MotionEvent.ACTION_UP -> {
                val hit = find(e.x, e.y)
                val fire = hit != null && hit.id == pressedId
                pressedId = null
                if (fire) hit!!.action()
                return fire
            }
            MotionEvent.ACTION_CANCEL -> pressedId = null
        }
        return pressedId != null
    }

    private fun find(x: Float, y: Float): Btn? = list.lastOrNull { it.rect.contains(x, y) }
}
