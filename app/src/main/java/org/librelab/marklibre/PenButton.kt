package org.librelab.marklibre

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageButton

/**
 * Toolbar button for pen/highlighter. Its BACKGROUND is managed by the
 * toolbar (dim highlight when newly selected, bright moving highlight
 * underneath); this view only controls the icon tint:
 * - showColor (this brush is active): icon shows the current ink color
 * - otherwise: icon in neutral colorOnSurface
 */
class PenButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageButton(context, attrs) {

    var neutralColor: Int = Color.BLACK

    var activeColor: Int = Color.BLACK
        set(value) {
            field = value
            refresh()
        }

    var active: Boolean = false
        set(value) {
            field = value
            refresh()
        }

    var showColor: Boolean = false
        set(value) {
            field = value
            refresh()
        }

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.PenButton)
        neutralColor = a.getColor(R.styleable.PenButton_neutral_color, Color.BLACK)
        a.recycle()
        scaleType = ScaleType.CENTER_INSIDE
        refresh()
    }

    private fun refresh() {
        imageTintList = ColorStateList.valueOf(
            if (showColor) activeColor else neutralColor
        )
    }
}
