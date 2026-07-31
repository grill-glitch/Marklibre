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
 * Toolbar button for pen/highlighter.
 * - active: whole button filled with colorPrimaryContainer
 * - showColor (any color tool active): icon tinted with the current ink color
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

    private var defaultBackground: Drawable? = null

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.PenButton)
        neutralColor = a.getColor(R.styleable.PenButton_neutral_color, Color.BLACK)
        a.recycle()
        scaleType = ScaleType.CENTER_INSIDE
        refresh()
    }

    private fun refresh() {
        background = if (active) {
            ContextCompat.getDrawable(context, R.drawable.tool_button_highlight)
        } else {
            defaultBackground ?: defaultSelectableBg()
        }
        imageTintList = ColorStateList.valueOf(
            if (showColor) activeColor else neutralColor
        )
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
