package com.google.android.markup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Drawing canvas: renders a source bitmap (optional) plus an ink layer
 * (strokes + text). Eraser removes ink only, never the image - matching the
 * original Google Markup semantics.
 */
class DrawingCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onUndoAvailability(hasUndo: Boolean, hasRedo: Boolean)
        fun onRequestNewText(x: Float, y: Float)
        fun onRequestTextEdit(element: InkElement.Text)
    }

    var listener: Listener? = null

    var tool: InkTool = InkTool.PEN
        set(value) {
            field = value
            selectedText = null
            invalidate()
        }

    var color: Int = Color.BLACK

    private var source: Bitmap? = null
    private val elements = ArrayList<InkElement>()
    private val undoStack = ArrayDeque<CanvasOp>()
    private val redoStack = ArrayDeque<CanvasOp>()

    private var inkLayer: Bitmap? = null

    private val matrix = Matrix()
    private val inverse = Matrix()

    // In-progress stroke
    private var activePath: Path? = null
    private var activeStyle = StrokeStyle.PEN
    private var activeColor = Color.BLACK
    private var activeWidth = 8f
    private var lastX = 0f
    private var lastY = 0f

    // Text selection / drag / pinch
    private var selectedText: InkElement.Text? = null
    private var draggingText = false
    private var scalingText = false
    private var dragOffX = 0f
    private var dragOffY = 0f
    private var baseSize = 0f
    private var lastSpan = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    // Double-tap detection
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private val density: Float
        get() = resources.displayMetrics.density

    fun setSourceBitmap(bm: Bitmap?) {
        source = bm
        elements.clear()
        undoStack.clear()
        redoStack.clear()
        selectedText = null
        computeMatrix()
        renderInk()
        notifyUndo()
    }

    fun sourceRectInView(): RectF? {
        val src = source ?: return null
        val r = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        matrix.mapRect(r)
        return r
    }

    fun isStickerMode(): Boolean = source == null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        inkLayer = Bitmap.createBitmap(max(w, 1), max(h, 1), Bitmap.Config.ARGB_8888)
        computeMatrix()
        renderInk()
    }

    private fun computeMatrix() {
        val bm = source
        if (bm == null) {
            matrix.reset()
            inverse.reset()
            return
        }
        // Account for padding (nav-bar inset + ink_canvas_margin_bottom) so
        // the image never fills the screen edge-to-edge behind the toolbar.
        val vw = (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(1f)
        val vh = (height - paddingTop - paddingBottom).toFloat().coerceAtLeast(1f)
        val scale = min(vw / bm.width, vh / bm.height)
        val dx = paddingLeft + (vw - bm.width * scale) / 2f
        val dy = paddingTop + (vh - bm.height * scale) / 2f
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        matrix.invert(inverse)
    }

    // ---------- painting helpers ----------

    private fun strokePaint(style: StrokeStyle, color: Int, width: Float): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = width
            when (style) {
                StrokeStyle.PEN -> {
                    this.color = color
                    alpha = 255
                }
                StrokeStyle.HIGHLIGHTER -> {
                    this.color = color
                    alpha = 120
                }
                StrokeStyle.ERASER -> {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }
            }
        }

    private fun textPaint(color: Int, font: String, size: Float): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = fontTypeface(font)
            isSubpixelText = true
        }

    private fun textBounds(el: InkElement.Text): RectF {
        val p = textPaint(el.color, el.font, el.size)
        val w = p.measureText(el.text)
        // ascent() is negative: em-box height = descent - ascent
        val h = p.descent() - p.ascent()
        val pad = 12f * density
        return RectF(el.x - pad, el.y - pad, el.x + w + pad, el.y + h + pad)
    }

    private fun renderInk() {
        val layer = inkLayer ?: return
        if (layer.width != width || layer.height != height) return
        layer.eraseColor(Color.TRANSPARENT)
        val c = Canvas(layer)
        for (el in elements) {
            when (el) {
                is InkElement.Stroke ->
                    c.drawPath(el.path, strokePaint(el.style, el.color, el.width))
                is InkElement.Text -> {
                    val p = textPaint(el.color, el.font, el.size)
                    c.drawText(el.text, el.x, el.y - p.ascent(), p)
                }
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        source?.let { canvas.drawBitmap(it, matrix, null) }
        inkLayer?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        activePath?.let {
            canvas.drawPath(it, strokePaint(activeStyle, activeColor, activeWidth))
        }
        selectedText?.let { drawSelection(canvas, it) }
    }

    private fun drawSelection(canvas: Canvas, el: InkElement.Text) {
        val p = textPaint(el.color, el.font, el.size)
        val w = p.measureText(el.text)
        // ascent() is negative: text spans [el.y, el.y + descent - ascent]
        val h = p.descent() - p.ascent()
        val rect = RectF(el.x, el.y, el.x + w, el.y + h)
        val pad = 6f * density
        rect.inset(-pad, -pad)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = el.color
        }
        canvas.drawRect(rect, stroke)
        val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = el.color }
        val hs = 6f * density
        for ((hx, hy) in listOf(
            rect.left to rect.top, rect.right to rect.top,
            rect.left to rect.bottom, rect.right to rect.bottom
        )) {
            canvas.drawRect(hx - hs, hy - hs, hx + hs, hy + hs, handle)
        }
    }

    // ---------- element ops ----------

    fun addText(text: String, x: Float?, y: Float?, color: Int, font: String) {
        val size = 30f * density
        var p = textPaint(color, font, size)
        var w = p.measureText(text)
        var effectiveSize = size
        if (w > width - 16f) {
            effectiveSize = size * (width - 16f) / w
            p = textPaint(color, font, effectiveSize)
            w = p.measureText(text)
        }
        val h = p.descent() - p.ascent()
        val px = (x ?: (width - w) / 2f).coerceIn(8f, max(8f, width - w - 8f))
        val py = (y ?: (height - h) / 2f).coerceIn(8f, max(8f, height - h - 8f))
        val el = InkElement.Text(text, px, py, effectiveSize, color, font)
        elements.add(el)
        undoStack.addLast(CanvasOp.Add(el))
        redoStack.clear()
        selectText(el)
        renderInk()
        notifyUndo()
    }

    fun updateText(old: InkElement.Text, text: String, color: Int, font: String) {
        val idx = elements.indexOf(old)
        if (idx < 0) return
        val size = old.size
        var p = textPaint(color, font, size)
        var w = p.measureText(text)
        var effectiveSize = size
        if (w > width - 16f) {
            effectiveSize = size * (width - 16f) / w
            p = textPaint(color, font, effectiveSize)
            w = p.measureText(text)
        }
        val h = p.descent() - p.ascent()
        val el = InkElement.Text(text, old.x, old.y, effectiveSize, color, font)
        el.x = el.x.coerceIn(0f, max(0f, width - w))
        el.y = el.y.coerceIn(0f, max(0f, height - h))
        elements[idx] = el
        undoStack.addLast(CanvasOp.EditText(old, el))
        redoStack.clear()
        selectText(el)
        renderInk()
        notifyUndo()
    }

    fun applyCrop(rect: RectF) {
        val src = source ?: return
        if (rect.width() < 16f || rect.height() < 16f) return
        val flat = flattenFullRes()
        val r = RectF(rect)
        inverse.mapRect(r)
        r.left = r.left.coerceIn(0f, src.width.toFloat())
        r.top = r.top.coerceIn(0f, src.height.toFloat())
        r.right = r.right.coerceIn(0f, src.width.toFloat())
        r.bottom = r.bottom.coerceIn(0f, src.height.toFloat())
        val l = r.left.toInt()
        val t = r.top.toInt()
        val w = (r.right - r.left).toInt()
        val h = (r.bottom - r.top).toInt()
        if (w < 8 || h < 8) return
        val cropped = Bitmap.createBitmap(flat, l, t, w, h)
        undoStack.addLast(
            CanvasOp.ReplaceImage(
                ImageState(src, ArrayList(elements)),
                ImageState(cropped, emptyList())
            )
        )
        redoStack.clear()
        source = cropped
        elements.clear()
        selectedText = null
        computeMatrix()
        renderInk()
        notifyUndo()
    }

    fun undo() {
        val op = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(op)
        applyOp(op, forward = false)
    }

    fun redo() {
        val op = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(op)
        applyOp(op, forward = true)
    }

    private fun applyOp(op: CanvasOp, forward: Boolean) {
        when (op) {
            is CanvasOp.Add ->
                if (forward) elements.add(op.element) else elements.remove(op.element)
            is CanvasOp.EditText ->
                if (forward) replaceElement(op.old, op.new) else replaceElement(op.new, op.old)
            is CanvasOp.ReplaceImage -> {
                val st = if (forward) op.next else op.prev
                source = st.source
                elements.clear()
                st.elements.let { elements.addAll(it) }
            }
        }
        selectedText = null
        computeMatrix()
        renderInk()
        notifyUndo()
    }

    private fun replaceElement(old: InkElement, new: InkElement) {
        val i = elements.indexOf(old)
        if (i >= 0) elements[i] = new
    }

    private fun notifyUndo() {
        listener?.onUndoAvailability(undoStack.isNotEmpty(), redoStack.isNotEmpty())
    }

    /**
     * Flattens source + ink into a full-resolution bitmap (source pixel size).
     * Eraser strokes affect the ink layer only - the image is never damaged.
     */
    fun flattenFullRes(): Bitmap {
        val src = source
        if (src == null) {
            // sticker mode: view-sized transparent canvas
            val out = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(Color.TRANSPARENT)
            inkLayer?.let { c.drawBitmap(it, 0f, 0f, null) }
            return out
        }
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(src, 0f, 0f, null)

        val ink = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val ic = Canvas(ink)
        val k = matrix.mapRadius(1f)
        val invK = if (k == 0f) 1f else 1f / k
        for (el in elements) {
            when (el) {
                is InkElement.Stroke -> {
                    val p = Path(el.path)
                    p.transform(inverse)
                    ic.drawPath(p, strokePaint(el.style, el.color, el.width * invK))
                }
                is InkElement.Text -> {
                    val p = textPaint(el.color, el.font, el.size * invK)
                    val pts = floatArrayOf(el.x, el.y)
                    inverse.mapPoints(pts)
                    ic.drawText(el.text, pts[0], pts[1] - p.ascent(), p)
                }
            }
        }
        c.drawBitmap(ink, 0f, 0f, null)
        return out
    }

    // ---------- touch ----------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (tool == InkTool.CROP) return false
        return if (tool == InkTool.TEXT) handleTextTool(event) else handleDrawTool(event)
    }

    private fun handleDrawTool(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val sel = selectedText
                if (sel != null && textBounds(sel).contains(event.x, event.y)) {
                    draggingText = true
                    dragOffX = event.x - sel.x
                    dragOffY = event.y - sel.y
                    return true
                }
                val hit = hitTestText(event.x, event.y)
                if (hit != null) {
                    if (isDoubleTap(event.x, event.y)) {
                        listener?.onRequestTextEdit(hit)
                    } else {
                        selectText(hit)
                    }
                    return true
                }
                selectedText = null
                invalidate()
                startStroke(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingText) {
                    moveSelectedText(event.x, event.y)
                    return true
                }
                if (scalingText) {
                    scaleSelectedText(event)
                    return true
                }
                continueStroke(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (selectedText != null && draggingText) {
                    draggingText = false
                    scalingText = true
                    baseSize = selectedText!!.size
                    lastSpan = pointerSpan(event)
                    val (fx, fy) = pointerFocus(event)
                    lastFocusX = fx
                    lastFocusY = fy
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingText) {
                    draggingText = false
                    return true
                }
                if (scalingText) {
                    scalingText = false
                    return true
                }
                endStroke()
                recordTap(event.x, event.y)
            }
        }
        return true
    }

    private fun handleTextTool(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val hit = hitTestText(event.x, event.y)
            if (hit != null) {
                selectText(hit)
                listener?.onRequestTextEdit(hit)
            } else {
                listener?.onRequestNewText(event.x, event.y)
            }
        }
        return true
    }

    private fun hitTestText(x: Float, y: Float): InkElement.Text? {
        for (i in elements.indices.reversed()) {
            val el = elements[i]
            if (el is InkElement.Text && textBounds(el).contains(x, y)) return el
        }
        return null
    }

    private fun selectText(el: InkElement.Text) {
        selectedText = el
        invalidate()
    }

    private fun moveSelectedText(x: Float, y: Float) {
        val el = selectedText ?: return
        el.x = x - dragOffX
        el.y = y - dragOffY
        renderInk()
    }

    private fun scaleSelectedText(event: MotionEvent) {
        val el = selectedText ?: return
        val span = pointerSpan(event)
        if (span <= 0f) return
        val ratio = span / lastSpan
        lastSpan = span
        val (fx, fy) = pointerFocus(event)
        el.x += fx - lastFocusX
        el.y += fy - lastFocusY
        lastFocusX = fx
        lastFocusY = fy
        el.size = (el.size * ratio).coerceIn(10f * density, 200f * density)
        renderInk()
    }

    private fun pointerSpan(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        return hypot(
            e.getX(0) - e.getX(1),
            e.getY(0) - e.getY(1)
        )
    }

    private fun pointerFocus(e: MotionEvent): Pair<Float, Float> {
        if (e.pointerCount < 2) return e.x to e.y
        return ((e.getX(0) + e.getX(1)) / 2f) to ((e.getY(0) + e.getY(1)) / 2f)
    }

    private fun recordTap(x: Float, y: Float) {
        lastTapTime = SystemClock.uptimeMillis()
        lastTapX = x
        lastTapY = y
    }

    private fun isDoubleTap(x: Float, y: Float): Boolean {
        val dt = SystemClock.uptimeMillis() - lastTapTime
        val dist = hypot(x - lastTapX, y - lastTapY)
        return dt < 350 && dist < 60f * density
    }

    private fun startStroke(event: MotionEvent) {
        activeStyle = when (tool) {
            InkTool.PEN -> StrokeStyle.PEN
            InkTool.HIGHLIGHTER -> StrokeStyle.HIGHLIGHTER
            else -> StrokeStyle.ERASER
        }
        activeColor = color
        activeWidth = when (activeStyle) {
            StrokeStyle.PEN -> 8f * density
            StrokeStyle.HIGHLIGHTER -> 26f * density
            StrokeStyle.ERASER -> 30f * density
        }
        lastX = event.x
        lastY = event.y
        activePath = Path().apply { moveTo(lastX, lastY) }
        invalidate()
    }

    private fun continueStroke(event: MotionEvent) {
        val path = activePath ?: return
        val midX = (lastX + event.x) / 2f
        val midY = (lastY + event.y) / 2f
        path.quadTo(lastX, lastY, midX, midY)
        lastX = event.x
        lastY = event.y
        invalidate()
    }

    private fun endStroke() {
        val path = activePath ?: return
        activePath = null
        val el = InkElement.Stroke(Path(path), activeColor, activeWidth, activeStyle)
        elements.add(el)
        undoStack.addLast(CanvasOp.Add(el))
        redoStack.clear()
        renderInk()
        notifyUndo()
    }
}
