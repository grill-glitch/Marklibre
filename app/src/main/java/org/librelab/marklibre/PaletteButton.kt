package org.librelab.marklibre

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import kotlin.math.min

/**
 * Custom-color button: shows a rainbow wedge (the "palette" affordance)
 * while idle; once a custom color is picked it renders as a normal color
 * dot in that color and stays checked. Tap opens the palette dialog.
 */
class PaletteButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ColorButton(context, attrs) {

    private val density: Float
        get() = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        if (checked) {
            super.onDraw(canvas)
            return
        }
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 8f * density
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
            color = 0xFF9E9E9E.toInt()
        }
        canvas.drawCircle(cx, cy, r, ring)
        // rainbow wedge
        val sector = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val wedge = RectF(cx - r, cy - r, cx + r, cy + r)
        val rainbow = intArrayOf(
            0xFFFF5252.toInt(), 0xFFFFC400.toInt(), 0xFF00E676.toInt(),
            0xFF00B0FF.toInt(), 0xFFD500F9.toInt()
        )
        val sweep = 360f / rainbow.size
        for ((i, c) in rainbow.withIndex()) {
            sector.color = c
            canvas.drawArc(wedge, i * sweep, sweep, true, sector)
        }
        // small center dot in the last custom color (or neutral gray)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = this@PaletteButton.color
        }
        canvas.drawCircle(cx, cy, r * 0.38f, dot)
    }
}
