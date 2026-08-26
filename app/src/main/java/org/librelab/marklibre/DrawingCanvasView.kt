package org.librelab.marklibre

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
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import kotlin.math.atan2
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
        /** Eyedropper result: a color picked from the image. */
        fun onColorPicked(color: Int)
    }

    var listener: Listener? = null

    var tool: InkTool = InkTool.PEN
        set(value) {
            field = value
            selectedText = null
            invalidate()
        }

    var color: Int = Color.BLACK

    /** Pen stroke width in dp (user-selectable via the width row). */
    var penWidthDp: Float = 8f

    /** Highlighter stroke width in dp (user-selectable via the width row). */
    var highlighterWidthDp: Float = 24f

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

    // Rotation via the knob below the selection box
    private var rotatingText = false
    private var rotateStartAngle = 0f
    private var rotateStartRotation = 0f
    private var rotationIcon: Drawable? = null

    // Two-finger canvas zoom/pan (pen tools only). The gesture matrix is a
    // view-space transform applied on top of the base fit matrix while the
    // gesture runs; on release it is committed into the element coordinates
    // (paths/widths/positions), so idle rendering stays on the fast ink
    // layer bitmap path.
    private var gestureMatrix = Matrix()
    private var gestureActive = false

    /** When true, the next touch picks a source pixel instead of drawing. */
    var pickColorMode = false
    private var gestureSpanPrev = 0f
    private var gestureFocusPrev = PointF()

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
        gestureActive = false
        gestureMatrix.reset()
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

    /** True when there are unsaved edits (the undo stack is non-empty). */
    fun hasEdits(): Boolean = undoStack.isNotEmpty()

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
                    // custom palette colors carry their own alpha (opacity)
                    alpha = Color.alpha(color)
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

    /** Measured text dimensions (em-box: width, height = descent - ascent). */
    private data class TextMetrics(val paint: Paint, val w: Float, val h: Float, val size: Float)

    private fun textMetrics(el: InkElement.Text): TextMetrics =
        textMetrics(el.color, el.font, el.size, el.text)

    private fun textMetrics(color: Int, font: String, size: Float, text: String): TextMetrics {
        val p = textPaint(color, font, size)
        // ascent() is negative: em-box height = descent - ascent
        return TextMetrics(p, p.measureText(text), p.descent() - p.ascent(), size)
    }

    /**
     * Measures [text] at [size], shrinking the size until the text fits the
     * canvas width (used when creating/editing a text element).
     */
    private fun fitTextMetrics(text: String, color: Int, font: String, size: Float): TextMetrics {
        var p = textPaint(color, font, size)
        var w = p.measureText(text)
        var effectiveSize = size
        if (w > width - 16f) {
            effectiveSize = size * (width - 16f) / w
            p = textPaint(color, font, effectiveSize)
            w = p.measureText(text)
        }
        return TextMetrics(p, w, p.descent() - p.ascent(), effectiveSize)
    }

    private fun textBounds(el: InkElement.Text): RectF {
        val m = textMetrics(el)
        val pad = 12f * density
        return RectF(el.x - pad, el.y - pad, el.x + m.w + pad, el.y + m.h + pad)
    }

    private fun renderInk() {
        val layer = inkLayer ?: return
        if (layer.width != width || layer.height != height) return
        layer.eraseColor(Color.TRANSPARENT)
        val c = Canvas(layer)
        for (el in elements) drawElement(c, el)
        invalidate()
    }

    /** Draws one element in view coordinates (shared by ink layer + gesture pass). */
    private fun drawElement(c: Canvas, el: InkElement) {
        when (el) {
            is InkElement.Stroke ->
                c.drawPath(el.path, strokePaint(el.style, el.color, el.width))
            is InkElement.Text -> {
                val m = textMetrics(el)
                val p = m.paint
                if (el.rotation != 0f) {
                    c.save()
                    c.rotate(el.rotation, el.x + m.w / 2f, el.y + m.h / 2f)
                    c.drawText(el.text, el.x, el.y - p.ascent(), p)
                    c.restore()
                } else {
                    c.drawText(el.text, el.x, el.y - p.ascent(), p)
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        source?.let {
            if (gestureActive) {
                // Two-finger gesture running: draw image + ink vectorially
                // under the gesture transform (the ink layer bitmap cannot
                // be scaled without blurring).
                canvas.save()
                canvas.concat(gestureMatrix)
                canvas.drawBitmap(it, matrix, null)
                for (el in elements) drawElement(canvas, el)
                val path = activePath
                if (path != null) {
                    canvas.drawPath(path, strokePaint(activeStyle, activeColor, activeWidth))
                }
                canvas.restore()
                return
            }
            canvas.drawBitmap(it, matrix, null)
        }
        // Eraser preview composites onto the ink layer (CLEAR) so it only
        // clears ink - never the image below (a CLEAR path drawn on the main
        // canvas would punch a transparent hole through the image, exposing
        // the dark parent background as a black streak).
        // Pen/highlighter preview is drawn directly on the canvas instead:
        // the persistent ink layer would re-composite the growing active
        // path every frame, stacking the highlighter's alpha to opaque.
        val path = activePath
        val layer = inkLayer
        if (path != null && activeStyle == StrokeStyle.ERASER) {
            layer?.let { Canvas(it).drawPath(path, strokePaint(activeStyle, activeColor, activeWidth)) }
        }
        inkLayer?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        if (path != null && activeStyle != StrokeStyle.ERASER) {
            canvas.drawPath(path, strokePaint(activeStyle, activeColor, activeWidth))
        }
        selectedText?.let { drawSelection(canvas, it) }
    }

    private fun drawSelection(canvas: Canvas, el: InkElement.Text) {
        val m = textMetrics(el)
        // ascent() is negative: text spans [el.y, el.y + descent - ascent]
        val rect = RectF(el.x - 6f * density, el.y - 6f * density, el.x + m.w + 6f * density, el.y + m.h + 6f * density)
        val rotated = el.rotation != 0f
        if (rotated) {
            canvas.save()
            canvas.rotate(el.rotation, el.x + m.w / 2f, el.y + m.h / 2f)
        }
        // border + corner handles (small white circles)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = el.color
        }
        canvas.drawRect(rect, stroke)
        val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val hs = 6f * density
        for ((hx, hy) in listOf(
            rect.left to rect.top, rect.right to rect.top,
            rect.left to rect.bottom, rect.right to rect.bottom
        )) {
            canvas.drawCircle(hx, hy, hs, handle)
        }
        // rotation handle: line below the box, dot + refresh icon at its end
        drawRotationHandle(canvas, el, rect, stroke)
        if (rotated) canvas.restore()
    }

    private fun drawRotationHandle(
        canvas: Canvas,
        el: InkElement.Text,
        box: RectF,
        linePaint: Paint
    ) {
        val lineLen = 24f * density
        val dotR = 9f * density
        val x = box.centerX()
        val y0 = box.bottom
        val y1 = y0 + lineLen
        canvas.drawLine(x, y0, x, y1, linePaint)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = el.color }
        canvas.drawCircle(x, y1, dotR, dot)
        // refresh icon on the dot, tinted for contrast against the dot fill
        val icon = rotationIcon ?: ContextCompat.getDrawable(context, R.drawable.refresh_24)
            ?.mutate().also { rotationIcon = it }
        icon?.let { d ->
            val lum = 0.299f * Color.red(el.color) +
                0.587f * Color.green(el.color) +
                0.114f * Color.blue(el.color)
            d.setTint(if (lum > 140f) Color.BLACK else Color.WHITE)
            val isz = 16f * density
            d.bounds = Rect(
                (x - isz / 2f).toInt(),
                (y1 - isz / 2f).toInt(),
                (x + isz / 2f).toInt(),
                (y1 + isz / 2f).toInt()
            )
            d.draw(canvas)
        }
    }

    // ---------- element ops ----------

    fun addText(text: String, x: Float?, y: Float?, color: Int, font: String) {
        val m = fitTextMetrics(text, color, font, 30f * density)
        val px = (x ?: (width - m.w) / 2f).coerceIn(8f, max(8f, width - m.w - 8f))
        val py = (y ?: (height - m.h) / 2f).coerceIn(8f, max(8f, height - m.h - 8f))
        val el = InkElement.Text(text, px, py, m.size, color, font)
        elements.add(el)
        pushUndo(CanvasOp.Add(el))
        selectText(el)
        renderInk()
        notifyUndo()
    }

    fun updateText(old: InkElement.Text, text: String, color: Int, font: String) {
        val idx = elements.indexOf(old)
        if (idx < 0) return
        val m = fitTextMetrics(text, color, font, old.size)
        val el = InkElement.Text(text, old.x, old.y, m.size, color, font, old.rotation)
        el.x = el.x.coerceIn(0f, max(0f, width - m.w))
        el.y = el.y.coerceIn(0f, max(0f, height - m.h))
        elements[idx] = el
        pushUndo(CanvasOp.EditText(old, el))
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
        replaceSourceWith(Bitmap.createBitmap(flat, l, t, w, h))
    }

    /**
     * Rotates the whole image (source + ink) 90° clockwise as one undoable
     * op. Like crop, the flattened result replaces the canvas state — the
     * ink becomes pixels, and undo restores the previous vector elements.
     */
    fun rotateImage() {
        if (source == null) return  // sticker mode has no image to rotate
        val flat = flattenFullRes()
        val rot = Matrix().apply { postRotate(90f) }
        val rotated = Bitmap.createBitmap(flat, 0, 0, flat.width, flat.height, rot, true)
        flat.recycle()
        replaceSourceWith(rotated)
    }

    /**
     * Replaces the whole canvas state with a flattened [newSource] bitmap
     * (crop / rotate). The previous image + element list is snapshotted as
     * one undoable ReplaceImage op; undo restores the full prior state.
     */
    private fun replaceSourceWith(newSource: Bitmap) {
        pushUndo(
            CanvasOp.ReplaceImage(
                ImageState(source, ArrayList(elements)),
                ImageState(newSource, emptyList())
            )
        )
        activePath = null
        source = newSource
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

    /** Pushes an op onto the undo stack; any new op invalidates the redo stack. */
    private fun pushUndo(op: CanvasOp) {
        undoStack.addLast(op)
        redoStack.clear()
    }

    private fun notifyUndo() {
        listener?.onUndoAvailability(undoStack.isNotEmpty(), redoStack.isNotEmpty())
    }

    /** Re-pushes undo/redo availability to the listener (e.g. after a mode switch). */
    fun refreshUndoState() = notifyUndo()

    /** Scale factor (length of the transformed x-basis vector) of a matrix. */
    private fun Matrix.scaleFactor(): Float {
        val v = FloatArray(9)
        getValues(v)
        return hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y])
    }

    /** Scale factor of the active gesture transform (1f when idle). */
    private fun gestureScale(): Float =
        gestureMatrix.scaleFactor().coerceAtLeast(1e-4f)

    /**
     * Flattens source + ink into a full-resolution bitmap (source pixel size).
     * Eraser strokes affect the ink layer only - the image is never damaged.
     * An in-flight two-finger gesture is folded into the mapping so a save
     * during the gesture still lands correctly.
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
        val gs = gestureScale()
        for (el in elements) {
            when (el) {
                is InkElement.Stroke -> {
                    val p = Path(el.path)
                    p.transform(gestureMatrix)
                    p.transform(inverse)
                    ic.drawPath(p, strokePaint(el.style, el.color, el.width * gs * invK))
                }
                is InkElement.Text -> {
                    val m = textMetrics(el.color, el.font, el.size * gs * invK, el.text)
                    val p = m.paint
                    val pts = floatArrayOf(el.x, el.y)
                    gestureMatrix.mapPoints(pts)
                    inverse.mapPoints(pts)
                    if (el.rotation != 0f) {
                        ic.save()
                        ic.rotate(el.rotation, pts[0] + m.w / 2f, pts[1] + m.h / 2f)
                        ic.drawText(el.text, pts[0], pts[1] - p.ascent(), p)
                        ic.restore()
                    } else {
                        ic.drawText(el.text, pts[0], pts[1] - p.ascent(), p)
                    }
                }
            }
        }
        c.drawBitmap(ink, 0f, 0f, null)
        return out
    }

    // ---------- touch ----------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (tool == InkTool.CROP) return false
        if (pickColorMode) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                pickColorMode = false
                listener?.onColorPicked(pickColorAt(event.x, event.y))
            }
            return true
        }
        return if (tool == InkTool.TEXT) handleTextTool(event) else handleDrawTool(event)
    }

    /**
     * Reads the source pixel under a screen point (accounting for the
     * current fit/gesture matrix). Used by the eyedropper.
     */
    fun pickColorAt(x: Float, y: Float): Int {
        val src = source ?: return Color.TRANSPARENT
        val pts = floatArrayOf(x, y)
        inverse.mapPoints(pts)
        val sx = pts[0].toInt()
        val sy = pts[1].toInt()
        return if (sx in 0 until src.width && sy in 0 until src.height) {
            src.getPixel(sx, sy)
        } else {
            Color.TRANSPARENT
        }
    }

    private fun handleDrawTool(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // text interactions win over drawing when they hit
                if (textDown(event.x, event.y)) return true
                startStroke(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (gestureActive) {
                    updateCanvasGesture(event)
                    return true
                }
                if (rotatingText) {
                    rotateSelectedText(event.x, event.y)
                    return true
                }
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
                } else if (!gestureActive && event.pointerCount >= 2) {
                    // second finger down -> canvas zoom/pan gesture
                    startCanvasGesture(event)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (gestureActive) {
                    endCanvasGesture()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (gestureActive) {
                    endCanvasGesture()
                    return true
                }
                if (finishTextGesture(event.x, event.y)) return true
                endStroke()
            }
            MotionEvent.ACTION_CANCEL -> {
                draggingText = false
                scalingText = false
                resizeCorner = null
                rotatingText = false
                dragStartState = null
                gestureActive = false
                gestureMatrix.reset()
                listener?.onTextDragChanged(false, 0f, 0f)
            }
        }
        return true
    }

    // ---------- two-finger canvas zoom/pan ----------

    /** Begins a two-finger gesture; cancels any in-progress stroke/text state. */
    private fun startCanvasGesture(event: MotionEvent) {
        gestureActive = true
        activePath = null
        selectedText = null
        gestureMatrix.reset()
        gestureSpanPrev = pointerSpan(event)
        val (fx, fy) = pointerFocus(event)
        gestureFocusPrev = PointF(fx, fy)
        listener?.onTextDragChanged(false, 0f, 0f)
        invalidate()
    }

    private fun updateCanvasGesture(event: MotionEvent) {
        val span = pointerSpan(event)
        if (span <= 0f) return
        val (fx, fy) = pointerFocus(event)
        var ds = span / gestureSpanPrev
        // clamp total zoom to [0.2x, 8x] of the base fit scale
        val cur = gestureMatrix.scaleFactor().coerceAtLeast(1e-4f)
        val target = (cur * ds).coerceIn(0.2f, 8f)
        ds = target / cur
        // scale around the current focus, then follow the focus movement
        gestureMatrix.postScale(ds, ds, fx, fy)
        gestureMatrix.postTranslate(fx - gestureFocusPrev.x, fy - gestureFocusPrev.y)
        gestureSpanPrev = span
        gestureFocusPrev = PointF(fx, fy)
        invalidate()
    }

    /** Commits the gesture transform into the image matrix + element coordinates. */
    private fun endCanvasGesture() {
        if (!gestureActive) return
        gestureActive = false
        if (!gestureMatrix.isIdentity) {
            // Fold the gesture into the persistent fit matrix (M_new = G * M_old)
            // so the zoom/pan survives the fingers lifting, then migrate the
            // elements into the new view space so ink stays glued to the image.
            // NOTE: preConcat(other) computes M*other (gesture applied in SOURCE
            // space, wrong); postConcat computes other*M = G*B (gesture on top).
            matrix.postConcat(gestureMatrix)
            matrix.invert(inverse)
            val s = gestureMatrix.scaleFactor()
            for (el in elements) {
                when (el) {
                    is InkElement.Stroke -> {
                        el.path.transform(gestureMatrix)
                        el.width *= s
                    }
                    is InkElement.Text -> {
                        val pts = floatArrayOf(el.x, el.y)
                        gestureMatrix.mapPoints(pts)
                        el.x = pts[0]
                        el.y = pts[1]
                        el.size *= s
                    }
                }
            }
            gestureMatrix.reset()
        }
        renderInk()
        invalidate()
    }

    private fun handleTextTool(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (textDown(event.x, event.y)) return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (rotatingText) {
                    rotateSelectedText(event.x, event.y)
                    return true
                }
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
                rotatingText = false
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
        if (rotatingText) {
            rotatingText = false
            commitTextTransform()
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
        InkElement.Text(el.text, el.x, el.y, el.size, el.color, el.font, el.rotation)

    /** Pushes one undoable op for a move/resize gesture (if anything moved). */
    private fun commitTextTransform() {
        val start = dragStartState ?: return
        val el = selectedText ?: return
        dragStartState = null
        if (start.x == el.x && start.y == el.y && start.size == el.size &&
            start.rotation == el.rotation
        ) {
            return
        }
        pushUndo(CanvasOp.EditText(start, el))
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
            el.rotation = start.rotation
        }
        dragStartState = null
        elements.removeAt(idx)
        pushUndo(CanvasOp.Remove(idx, el))
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
            val lp = toLocal(sel, x, y)
            // rotation knob below the box
            val knob = rotationKnob(sel)
            if (hypot(lp.x - knob.x, lp.y - knob.y) <= 26f * density) {
                dragStartState = snapState(sel)
                startRotation(sel, x, y)
                return true
            }
            cornerAt(lp.x, lp.y, sel)?.let { corner ->
                dragStartState = snapState(sel)
                startCornerResize(sel, corner, lp.x, lp.y)
                return true
            }
            if (textBounds(sel).contains(lp.x, lp.y)) {
                // drag moves the selected text; a tap edits it (see UP)
                dragStartState = snapState(sel)
                draggingText = true
                dragOffX = lp.x - sel.x
                dragOffY = lp.y - sel.y
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

    /** Transforms a screen-space point into the text's local (unrotated) space. */
    private fun toLocal(el: InkElement.Text, x: Float, y: Float): PointF {
        val rot = el.rotation
        if (rot == 0f) return PointF(x, y)
        val m = textMetrics(el)
        val cx = el.x + m.w / 2f
        val cy = el.y + m.h / 2f
        val dx = x - cx
        val dy = y - cy
        val rad = Math.toRadians((-rot).toDouble())
        val cos = kotlin.math.cos(rad)
        val sin = kotlin.math.sin(rad)
        return PointF(
            cx + (dx * cos - dy * sin).toFloat(),
            cy + (dx * sin + dy * cos).toFloat()
        )
    }

    /** Rotation knob center in the text's local space (below the box). */
    private fun rotationKnob(el: InkElement.Text): PointF {
        val m = textMetrics(el)
        val pad = 6f * density
        val lineLen = 24f * density
        return PointF(el.x + m.w / 2f, el.y + m.h + pad + lineLen)
    }

    private fun startRotation(el: InkElement.Text, x: Float, y: Float) {
        rotatingText = true
        val m = textMetrics(el)
        val cx = el.x + m.w / 2f
        val cy = el.y + m.h / 2f
        rotateStartAngle = Math.toDegrees(atan2(y - cy, x - cx).toDouble()).toFloat()
        rotateStartRotation = el.rotation
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun rotateSelectedText(x: Float, y: Float) {
        val el = selectedText ?: return
        val m = textMetrics(el)
        val cx = el.x + m.w / 2f
        val cy = el.y + m.h / 2f
        val angle = Math.toDegrees(atan2(y - cy, x - cx).toDouble()).toFloat()
        el.rotation = rotateStartRotation + (angle - rotateStartAngle)
        renderInk()
    }

    private fun cornerAt(x: Float, y: Float, el: InkElement.Text): Int? {
        val m = textMetrics(el)
        val pad = 6f * density
        val corners = listOf(
            el.x - pad to el.y - pad,
            el.x + m.w + pad to el.y - pad,
            el.x - pad to el.y + m.h + pad,
            el.x + m.w + pad to el.y + m.h + pad
        )
        val tol = 26f * density
        for ((i, c) in corners.withIndex()) {
            if (hypot(x - c.first, y - c.second) <= tol) return i
        }
        return null
    }

    private fun startCornerResize(el: InkElement.Text, corner: Int, x: Float, y: Float) {
        val m = textMetrics(el)
        val pad = 6f * density
        val corners = listOf(
            PointF(el.x - pad, el.y - pad),
            PointF(el.x + m.w + pad, el.y - pad),
            PointF(el.x - pad, el.y + m.h + pad),
            PointF(el.x + m.w + pad, el.y + m.h + pad)
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
        val lp = toLocal(el, x, y)
        val newX = lp.x - resizeGrabX
        val newY = lp.y - resizeGrabY
        val ratio = hypot(newX - resizeOpposite.x, newY - resizeOpposite.y) / resizeDist0
        if (ratio <= 0.05f) return
        val size = (resizeSize0 * ratio).coerceIn(10f * density, 200f * density)
        el.size = size
        val m = textMetrics(el)
        val pad = 6f * density
        val wBox = m.w + 2 * pad
        val hBox = m.h + 2 * pad
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
            if (el is InkElement.Text) {
                val lp = toLocal(el, x, y)
                if (textBounds(el).contains(lp.x, lp.y)) return el
            }
        }
        return null
    }

    private fun selectText(el: InkElement.Text) {
        selectedText = el
        invalidate()
    }

    private fun moveSelectedText(x: Float, y: Float) {
        val el = selectedText ?: return
        val lp = toLocal(el, x, y)
        el.x = lp.x - dragOffX
        el.y = lp.y - dragOffY
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
            StrokeStyle.PEN -> penWidthDp * density
            StrokeStyle.HIGHLIGHTER -> highlighterWidthDp * density
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
        pushUndo(CanvasOp.Add(el))
        renderInk()
        notifyUndo()
    }
}
