package com.google.android.markup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
        /** Drag-to-delete feedback: the drop target (trash) follows the drag. */
        fun onTextDragChanged(dragging: Boolean, x: Float, y: Float)
        /** True when (x, y) is over the text delete drop target. */
        fun onTextDropTargetContains(x: Float, y: Float): Boolean
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

    // Single-finger corner resize of the selection box
    private var resizeCorner: Int? = null
    private var resizeOpposite = PointF()
    private var resizeSize0 = 0f
    private var resizeDist0 = 0f
    private var resizeGrabX = 0f
    private var resizeGrabY = 0f

    // Tap detection (down position to tell tap from drag)
    private var downX = 0f
    private var downY = 0f

    // Element state captured at the start of a move/resize gesture, so the
    // whole gesture becomes a single undoable transform op.
    private var dragStartState: InkElement.Text? = null

    private val touchSlop: Float by lazy {
        ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    }

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
        // corner handles: small white circles
        val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val hs = 6f * density
        for ((hx, hy) in listOf(
            rect.left to rect.top, rect.right to rect.top,
            rect.left to rect.bottom, rect.right to rect.bottom
        )) {
            canvas.drawCircle(hx, hy, hs, handle)
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
            is CanvasOp.Remove -> {
                if (forward) {
                    if (op.index < elements.size) elements.removeAt(op.index)
                    else elements.remove(op.element)
                } else {
                    if (!elements.contains(op.element)) {
                        elements.add(op.index.coerceAtMost(elements.size), op.element)
                    }
                }
            }
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
                // text interactions win over drawing when they hit
                if (textDown(event.x, event.y)) return true
                startStroke(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (resizeCorner != null) {
                    resizeSelectedText(event.x, event.y)
                    return true
                }
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
                    if (dragStartState == null) dragStartState = snapState(selectedText!!)
                    draggingText = false
                    scalingText = true
                    listener?.onTextDragChanged(false, 0f, 0f)
                    baseSize = selectedText!!.size
                    lastSpan = pointerSpan(event)
                    val (fx, fy) = pointerFocus(event)
                    lastFocusX = fx
                    lastFocusY = fy
                }
            }
            MotionEvent.ACTION_UP -> {
                if (finishTextGesture(event.x, event.y)) return true
                endStroke()
            }
            MotionEvent.ACTION_CANCEL -> {
                draggingText = false
                scalingText = false
                resizeCorner = null
                dragStartState = null
                listener?.onTextDragChanged(false, 0f, 0f)
            }
        }
        return true
    }

    private fun handleTextTool(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (textDown(event.x, event.y)) return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (resizeCorner != null) {
                    resizeSelectedText(event.x, event.y)
                    return true
                }
                if (draggingText) {
                    moveSelectedText(event.x, event.y)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (finishTextGesture(event.x, event.y)) return true
                val hit = hitTestText(event.x, event.y)
                if (hit == null) {
                    listener?.onRequestNewText(event.x, event.y)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                draggingText = false
                resizeCorner = null
                dragStartState = null
                listener?.onTextDragChanged(false, 0f, 0f)
            }
        }
        return true
    }

    /** Ends a text drag/resize gesture; returns true when it consumed the up. */
    private fun finishTextGesture(x: Float, y: Float): Boolean {
        if (draggingText) {
            val wasTap = hypot(x - downX, y - downY) <= touchSlop
            draggingText = false
            if (!wasTap && listener?.onTextDropTargetContains(x, y) == true) {
                // dropped on the trash -> delete the text
                deleteDraggedText()
            } else {
                if (!wasTap) commitTextTransform()
                if (wasTap) {
                    // tap on the selected text -> open the editor (content + color)
                    selectedText?.let { listener?.onRequestTextEdit(it) }
                }
            }
            // hide the trash AFTER the drop check (it gates on its own bounds)
            listener?.onTextDragChanged(false, x, y)
            return true
        }
        if (scalingText) {
            scalingText = false
            commitTextTransform()
            listener?.onTextDragChanged(false, 0f, 0f)
            return true
        }
        if (resizeCorner != null) {
            resizeCorner = null
            commitTextTransform()
            return true
        }
        return false
    }

    private fun snapState(el: InkElement.Text): InkElement.Text =
        InkElement.Text(el.text, el.x, el.y, el.size, el.color, el.font)

    /** Pushes one undoable op for a move/resize gesture (if anything moved). */
    private fun commitTextTransform() {
        val start = dragStartState ?: return
        val el = selectedText ?: return
        dragStartState = null
        if (start.x == el.x && start.y == el.y && start.size == el.size) return
        undoStack.addLast(CanvasOp.EditText(start, el))
        redoStack.clear()
        notifyUndo()
    }

    private fun deleteDraggedText() {
        val el = selectedText ?: return
        val idx = elements.indexOf(el)
        if (idx < 0) return
        // restore the pre-drag state so undo brings the text back where it
        // was BEFORE the drag toward the trash started
        dragStartState?.let { start ->
            el.x = start.x
            el.y = start.y
            el.size = start.size
        }
        dragStartState = null
        elements.removeAt(idx)
        undoStack.addLast(CanvasOp.Remove(idx, el))
        redoStack.clear()
        selectedText = null
        renderInk()
        notifyUndo()
    }

    /**
     * Shared text-gesture entry. Returns true when the event was consumed by a
     * text interaction (select, drag, corner resize).
     */
    private fun textDown(x: Float, y: Float): Boolean {
        downX = x
        downY = y
        val sel = selectedText
        if (sel != null) {
            cornerAt(x, y, sel)?.let { corner ->
                dragStartState = snapState(sel)
                startCornerResize(sel, corner, x, y)
                return true
            }
            if (textBounds(sel).contains(x, y)) {
                // drag moves the selected text; a tap edits it (see UP)
                dragStartState = snapState(sel)
                draggingText = true
                dragOffX = x - sel.x
                dragOffY = y - sel.y
                return true
            }
        }
        val hit = hitTestText(x, y)
        if (hit != null) {
            // single tap selects and shows the box
            selectText(hit)
            return true
        }
        selectedText = null
        invalidate()
        return false
    }

    private fun cornerAt(x: Float, y: Float, el: InkElement.Text): Int? {
        val p = textPaint(el.color, el.font, el.size)
        val w = p.measureText(el.text)
        val h = p.descent() - p.ascent()
        val pad = 6f * density
        val corners = listOf(
            el.x - pad to el.y - pad,
            el.x + w + pad to el.y - pad,
            el.x - pad to el.y + h + pad,
            el.x + w + pad to el.y + h + pad
        )
        val tol = 26f * density
        for ((i, c) in corners.withIndex()) {
            if (hypot(x - c.first, y - c.second) <= tol) return i
        }
        return null
    }

    private fun startCornerResize(el: InkElement.Text, corner: Int, x: Float, y: Float) {
        val p = textPaint(el.color, el.font, el.size)
        val w = p.measureText(el.text)
        val h = p.descent() - p.ascent()
        val pad = 6f * density
        val corners = listOf(
            PointF(el.x - pad, el.y - pad),
            PointF(el.x + w + pad, el.y - pad),
            PointF(el.x - pad, el.y + h + pad),
            PointF(el.x + w + pad, el.y + h + pad)
        )
        val c = corners[corner]
        // opposite corner stays fixed while scaling
        resizeOpposite = when (corner) {
            0 -> PointF(corners[3].x, corners[3].y)
            1 -> PointF(corners[2].x, corners[2].y)
            2 -> PointF(corners[1].x, corners[1].y)
            else -> PointF(corners[0].x, corners[0].y)
        }
        resizeSize0 = el.size
        resizeDist0 = hypot(c.x - resizeOpposite.x, c.y - resizeOpposite.y)
        resizeGrabX = x - c.x
        resizeGrabY = y - c.y
        resizeCorner = corner
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun resizeSelectedText(x: Float, y: Float) {
        val el = selectedText ?: return
        val corner = resizeCorner ?: return
        val newX = x - resizeGrabX
        val newY = y - resizeGrabY
        val ratio = hypot(newX - resizeOpposite.x, newY - resizeOpposite.y) / resizeDist0
        if (ratio <= 0.05f) return
        val size = (resizeSize0 * ratio).coerceIn(10f * density, 200f * density)
        el.size = size
        val p = textPaint(el.color, el.font, size)
        val w = p.measureText(el.text)
        val h = p.descent() - p.ascent()
        val pad = 6f * density
        val wBox = w + 2 * pad
        val hBox = h + 2 * pad
        // anchor on the OPPOSITE corner: it must not move while scaling
        when (corner) {
            0 -> { el.x = resizeOpposite.x - wBox + pad; el.y = resizeOpposite.y - hBox + pad }
            1 -> { el.x = resizeOpposite.x + pad; el.y = resizeOpposite.y - hBox + pad }
            2 -> { el.x = resizeOpposite.x - wBox + pad; el.y = resizeOpposite.y + pad }
            else -> { el.x = resizeOpposite.x + pad; el.y = resizeOpposite.y + pad }
        }
        renderInk()
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
        listener?.onTextDragChanged(true, x, y)
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
