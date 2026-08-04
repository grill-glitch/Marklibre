package org.librelab.marklibre

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class ColorButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var color: Int = Color.BLACK
        set(value) {
            field = value
            invalidate()
        }

    var checked: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val density: Float
        get() = resources.displayMetrics.density

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.ColorButton)
        color = a.getColor(R.styleable.ColorButton_ink_color, Color.BLACK)
        a.recycle()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 8f * density
        // neutral outline keeps black/white swatches visible on any surface
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            color = context.themeColor(
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                0xFF9E9E9E.toInt()
            )
            alpha = 110
        }
        canvas.drawCircle(cx, cy, r + 1f * density, outline)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = this@ColorButton.color
        }
        canvas.drawCircle(cx, cy, r, ring)
        if (checked) {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = this@ColorButton.color
            }
            canvas.drawCircle(cx, cy, r - 2.5f * density, fill)
            val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f * density
                color = Color.WHITE
            }
            canvas.drawCircle(cx, cy, r - 2.5f * density, halo)
        }
    }
}
