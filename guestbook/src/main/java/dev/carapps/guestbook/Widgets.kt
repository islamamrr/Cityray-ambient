package dev.carapps.guestbook

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView

/** A rounded button big enough to hit easily on a car screen. */
fun Context.pillButton(label: String, bg: Int, fg: Int, sizeSp: Float = 22f, onClick: () -> Unit): TextView =
    TextView(this).apply {
        text = label
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = Fonts.sansMedium
        gravity = Gravity.CENTER
        setPadding(dpi(26), dpi(14), dpi(26), dpi(14))
        minHeight = dpi(64)
        background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
            roundRect(bg, dp(32f)),
            null,
        )
        isClickable = true
        setOnClickListener { onClick() }
    }

/** A plain text button for top bars. */
fun Context.textButton(label: String, color: Int = Palette.text, onClick: () -> Unit): TextView =
    pillButton(label, Color.TRANSPARENT, color, 20f, onClick).apply {
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(50, 255, 255, 255)), null, roundRect(Color.WHITE, dp(32f)))
    }

fun Context.field(hint: String, multiLine: Boolean): EditText = EditText(this).apply {
    this.hint = hint
    setHintTextColor(Palette.textDim)
    setTextColor(Palette.text)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
    background = roundRect(Palette.panel, dp(16f))
    setPadding(dpi(20), dpi(16), dpi(20), dpi(16))
    if (multiLine) {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = 2
        maxLines = 4
        gravity = Gravity.TOP or Gravity.START
    } else {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_DONE
    }
}

fun Context.label(text: String, sizeSp: Float, color: Int, face: android.graphics.Typeface = Fonts.sans): TextView =
    TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = face
    }
