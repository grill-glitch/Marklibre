package com.google.android.markup

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Dimming overlay + draggable crop rectangle with corner/edge handles.
 * Rect is in view coordinates.
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var rect = RectF()
        private set

    /** Image bounds in view coordinates - the crop rect may never leave it. */
    var clampBounds = RectF()
        private set

    var onRectChange: ((RectF) -> Unit)? = null

    private val density: Float
        get() = resources.displayMetrics.density

    private val dimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.WHITE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }

    private var mode = Mode.NONE
    private var touchOffX = 0f
    private var touchOffY = 0f

    private enum class Mode { NONE, MOVE, LEFT, RIGHT, TOP, BOTTOM, TL, TR, BL, BR }

    fun setClampRect(r: RectF) {
        clampBounds.set(r)
        invalidate()
    }

    fun setInitialRect(r: RectF) {
        rect.set(r)
        clampToBounds()
        invalidate()
    }

    private fun clampToBounds() {
        val l = clampL()
        val t = clampT()
        val r = clampR()
        val b = clampB()
        if (r <= l || b <= t) return
        rect.left = safeClamp(rect.left, l, r)
        rect.top = safeClamp(rect.top, t, b)
        rect.right = safeClamp(rect.right, l, r)
        rect.bottom = safeClamp(rect.bottom, t, b)
    }

    /** maxOf/minOf clamping - never throws on empty ranges (unlike coerceIn). */
    private fun safeClamp(v: Float, lo: Float, hi: Float): Float =
        maxOf(lo, minOf(v, hi))

    private fun clampL() = if (clampBounds.isEmpty) 0f else clampBounds.left
    private fun clampT() = if (clampBounds.isEmpty) 0f else clampBounds.top
    private fun clampR() = if (clampBounds.isEmpty) width.toFloat() else clampBounds.right
    private fun clampB() = if (clampBounds.isEmpty) height.toFloat() else clampBounds.bottom

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // dim everything outside the crop rect
        canvas.drawRect(0f, 0f, width.toFloat(), rect.top, dimPaint)
        canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dimPaint)
        canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, dimPaint)
        // border + handles
        canvas.drawRect(rect, borderPaint)
        val hs = 6f * density
        val points = listOf(
            rect.left to rect.top,
            rect.right to rect.top,
            rect.left to rect.bottom,
            rect.right to rect.bottom,
            rect.centerX() to rect.top,
            rect.centerX() to rect.bottom,
            rect.left to rect.centerY(),
            rect.right to rect.centerY()
        )
        for ((hx, hy) in points) {
            canvas.drawRect(hx - hs, hy - hs, hx + hs, hy + hs, handlePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = hitTest(event.x, event.y)
                if (mode != Mode.NONE) {
                    touchOffX = event.x
                    touchOffY = event.y
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.NONE) return true
                val dx = event.x - touchOffX
                val dy = event.y - touchOffY
                touchOffX = event.x
                touchOffY = event.y
                applyDelta(mode, dx, dy)
                invalidate()
                onRectChange?.invoke(rect)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = Mode.NONE
            }
        }
        return true
    }

    private fun hitTest(x: Float, y: Float): Mode {
        val tol = 28f * density
        fun near(px: Float, py: Float) = hypot(x - px, y - py) <= tol
        if (near(rect.left, rect.top)) return Mode.TL
        if (near(rect.right, rect.top)) return Mode.TR
        if (near(rect.left, rect.bottom)) return Mode.BL
        if (near(rect.right, rect.bottom)) return Mode.BR
        if (x >= rect.left - tol && x <= rect.right + tol && hypot(y - rect.top, 0f) <= tol) return Mode.TOP
        if (x >= rect.left - tol && x <= rect.right + tol && hypot(y - rect.bottom, 0f) <= tol) return Mode.BOTTOM
        if (y >= rect.top - tol && y <= rect.bottom + tol && hypot(x - rect.left, 0f) <= tol) return Mode.LEFT
        if (y >= rect.top - tol && y <= rect.bottom + tol && hypot(x - rect.right, 0f) <= tol) return Mode.RIGHT
        if (rect.contains(x, y)) return Mode.MOVE
        return Mode.NONE
    }

    private fun applyDelta(mode: Mode, dx: Float, dy: Float) {
        val minSize = 60f * density
        when (mode) {
            Mode.MOVE -> {
                val nx = safeClamp(rect.left + dx, clampL(), clampR() - rect.width())
                val ny = safeClamp(rect.top + dy, clampT(), clampB() - rect.height())
                rect.offset(nx - rect.left, ny - rect.top)
            }
            Mode.LEFT -> {
                rect.left = safeClamp(rect.left + dx, clampL(), rect.right - minSize)
            }
            Mode.RIGHT -> {
                rect.right = safeClamp(rect.right + dx, rect.left + minSize, clampR())
            }
            Mode.TOP -> {
                rect.top = safeClamp(rect.top + dy, clampT(), rect.bottom - minSize)
            }
            Mode.BOTTOM -> {
                rect.bottom = safeClamp(rect.bottom + dy, rect.top + minSize, clampB())
            }
            Mode.TL -> {
                rect.left = safeClamp(rect.left + dx, clampL(), rect.right - minSize)
                rect.top = safeClamp(rect.top + dy, clampT(), rect.bottom - minSize)
            }
            Mode.TR -> {
                rect.right = safeClamp(rect.right + dx, rect.left + minSize, clampR())
                rect.top = safeClamp(rect.top + dy, clampT(), rect.bottom - minSize)
            }
            Mode.BL -> {
                rect.left = safeClamp(rect.left + dx, clampL(), rect.right - minSize)
                rect.bottom = safeClamp(rect.bottom + dy, rect.top + minSize, clampB())
            }
            Mode.BR -> {
                rect.right = safeClamp(rect.right + dx, rect.left + minSize, clampR())
                rect.bottom = safeClamp(rect.bottom + dy, rect.top + minSize, clampB())
            }
            Mode.NONE -> {}
        }
    }
}
