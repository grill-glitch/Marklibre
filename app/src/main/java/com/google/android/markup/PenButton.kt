package com.google.android.markup

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageButton

/**
 * Toolbar button for pen/highlighter: icon is tinted with the current ink
 * color while the tool is active, neutral otherwise (mirrors the original
 * [com.google.android.markup.PenButton]).
 */
class PenButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageButton(context, attrs) {

    var neutralColor: Int = Color.BLACK

    var active: Boolean = false
        set(value) {
            field = value
            updateTint()
        }

    var activeColor: Int = Color.BLACK
        set(value) {
            field = value
            updateTint()
        }

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.PenButton)
        neutralColor = a.getColor(R.styleable.PenButton_neutral_color, Color.BLACK)
        a.recycle()
        scaleType = ScaleType.CENTER_INSIDE
        updateTint()
    }

    private fun updateTint() {
        val t = if (active) activeColor else neutralColor
        imageTintList = ColorStateList.valueOf(t)
    }
}
