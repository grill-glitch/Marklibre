package com.google.android.markup

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.content.ContextCompat

/**
 * Toolbar button for pen/highlighter: the icon stays in the neutral theme
 * color; the ACTIVE state is shown by an outer highlight ring around the
 * button, so the current ink color never makes the tool invisible.
 */
class PenButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageButton(context, attrs) {

    var neutralColor: Int = Color.BLACK

    var active: Boolean = false
        set(value) {
            field = value
            updateBackground()
        }

    var activeColor: Int = Color.BLACK

    private var defaultBackground: Drawable? = null

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.PenButton)
        neutralColor = a.getColor(R.styleable.PenButton_neutral_color, Color.BLACK)
        a.recycle()
        scaleType = ScaleType.CENTER_INSIDE
        updateTint()
    }

    private fun updateTint() {
        imageTintList = ColorStateList.valueOf(neutralColor)
    }

    private fun updateBackground() {
        background = if (active) {
            ContextCompat.getDrawable(context, R.drawable.tool_button_highlight)
        } else {
            defaultBackground ?: defaultSelectableBg()
        }
    }

    private fun defaultSelectableBg(): Drawable? {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, tv, true
            )
        ) {
            context.getDrawable(tv.resourceId).also { defaultBackground = it }
        } else {
            null
        }
    }
}
