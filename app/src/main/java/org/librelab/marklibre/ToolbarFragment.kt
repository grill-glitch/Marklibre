package org.librelab.marklibre

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

class ToolbarFragment : Fragment() {

    interface Callbacks {
        fun onToolSelected(tool: InkTool)
        /** Momentary action (not a tool): rotate the whole image 90° CW. */
        fun onRotate()
        fun onColorSelected(color: Int)
        /** A pen width option (in dp) was picked. */
        fun onWidthSelected(widthDp: Float)
        /** The custom-color (palette) button was tapped. */
        fun onPaletteClicked()
    }

    var callbacks: Callbacks? = null

    private lateinit var colorPanel: View
    private lateinit var penWidthRow: View
    private lateinit var penWidthPreview: View
    private lateinit var penWidthSlider: SeekBar
    private lateinit var paletteButton: ImageView
    private lateinit var cropButton: ImageButton
    private lateinit var textButton: ImageButton
    private lateinit var eraserButton: ImageButton
    private lateinit var rotateButton: ImageButton
    private lateinit var penButton: PenButton
    private lateinit var highlighterButton: PenButton
    private lateinit var colorButtons: List<ColorButton>

    /** Pen width range: 2..16 dp (slider progress 0..14). */
    private val minPenWidth = 2f

    /** Current ink color, mirrored into the width preview bar. */
    private var currentInkColor: Int = Color.BLACK

    private var highlightView: View? = null
    private var dimBg: Drawable? = null
    private var defaultBg: Drawable? = null
    private var dimmedButton: View? = null
    private var highlightReady = false
    private var onSurface = 0
    private var onPrimary = 0
    private var iconTintAnim: ValueAnimator? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.toolbar, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        colorPanel = view.findViewById(R.id.color_panel)
        penWidthRow = view.findViewById(R.id.pen_width_row)
        penWidthPreview = view.findViewById(R.id.pen_width_preview)
        penWidthSlider = view.findViewById(R.id.pen_width_slider)
        paletteButton = view.findViewById(R.id.palette_button)
        cropButton = view.findViewById(R.id.crop_button)
        textButton = view.findViewById(R.id.ink_text_button)
        eraserButton = view.findViewById(R.id.ink_eraser_button)
        rotateButton = view.findViewById(R.id.rotate_button)
        penButton = view.findViewById(R.id.ink_pen_button)
        highlighterButton = view.findViewById(R.id.ink_highlighter_button)
        penButton.setImageResource(R.drawable.ic_pen)
        highlighterButton.setImageResource(R.drawable.ic_highlighter)

        highlightView = view.findViewById(R.id.tool_highlight)
        dimBg = ContextCompat.getDrawable(requireContext(), R.drawable.tool_button_highlight_dim)
        defaultBg = cropButton.background
        val ctx = requireContext()
        onSurface = ctx.themeColor(com.google.android.material.R.attr.colorOnSurface)
        // highlighted tools mimic the Save button: colorPrimary fill +
        // colorOnPrimary icon (same as the Save button's text color)
        onPrimary = ctx.themeColor(com.google.android.material.R.attr.colorOnPrimary)

        colorButtons = colorPanelChildren()

        cropButton.setOnClickListener { callbacks?.onToolSelected(InkTool.CROP) }
        textButton.setOnClickListener { callbacks?.onToolSelected(InkTool.TEXT) }
        eraserButton.setOnClickListener { callbacks?.onToolSelected(InkTool.ERASER) }
        rotateButton.setOnClickListener { callbacks?.onRotate() }
        penButton.setOnClickListener { callbacks?.onToolSelected(InkTool.PEN) }
        highlighterButton.setOnClickListener { callbacks?.onToolSelected(InkTool.HIGHLIGHTER) }

        for (cb in colorButtons) {
            cb.setOnClickListener { callbacks?.onColorSelected(cb.color) }
        }

        // pen width slider: 2..16 dp, live preview bar mirrors the width
        penWidthSlider.progress = (8f - minPenWidth).toInt()
        updateWidthPreview()
        penWidthSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                updateWidthPreview()
                if (fromUser) callbacks?.onWidthSelected(minPenWidth + progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        paletteButton.setOnClickListener { callbacks?.onPaletteClicked() }

        // anchor the bright highlight on the pen once positions are known
        view.post {
            positionHighlight(penButton, animate = false)
            highlightView?.visibility = View.VISIBLE
        }
    }

    private fun colorPanelChildren(): List<ColorButton> {
        val panel = view ?: return emptyList()
        val vg = panel.findViewById<ViewGroup>(R.id.color_panel)
        val out = ArrayList<ColorButton>()
        for (i in 0 until vg.childCount) {
            (vg.getChildAt(i) as? ColorButton)?.let { out.add(it) }
        }
        return out
    }

    fun setActiveTool(tool: InkTool) {
        // icon states revert instantly for every tool except the new one,
        // whose icon fades to its active tint (see animateIconTint)
        applyButtonState(cropButton, false)
        applyButtonState(textButton, false)
        applyButtonState(eraserButton, false)
        penButton.showColor = false
        highlighterButton.showColor = false
        penButton.active = tool == InkTool.PEN
        highlighterButton.active = tool == InkTool.HIGHLIGHTER
        // the pen-width row is a pen-only setting
        penWidthRow.visibility = if (tool == InkTool.PEN) View.VISIBLE else View.GONE

        // tool-switch animation: the newly clicked tool gets a dim highlight
        // immediately, while the bright highlight slides over to it
        val newButton = buttonFor(tool)
        dimmedButton?.background = defaultBg
        dimmedButton = newButton
        newButton.background = dimBg
        positionHighlight(newButton, animate = highlightReady)
        highlightReady = true
        // icon color gradient: the new tool's icon fades from its neutral
        // tone to its active tint (onPrimary, or the ink color for brushes)
        animateIconTint(newButton, tool)
    }

    private fun animateIconTint(btn: View, tool: InkTool) {
        iconTintAnim?.cancel()
        val from: Int
        val to: Int
        when (tool) {
            InkTool.PEN -> {
                from = penButton.neutralColor
                to = penButton.activeColor
            }
            InkTool.HIGHLIGHTER -> {
                from = highlighterButton.neutralColor
                to = highlighterButton.activeColor
            }
            else -> {
                from = onSurface
                to = onPrimary
            }
        }
        (btn as? ImageButton)?.imageTintList = ColorStateList.valueOf(from)
        iconTintAnim = ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            duration = 120
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                (btn as? ImageButton)?.imageTintList =
                    ColorStateList.valueOf(it.animatedValue as Int)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // brushes: hand the tint over to the ink-color state
                    when (tool) {
                        InkTool.PEN -> penButton.showColor = true
                        InkTool.HIGHLIGHTER -> highlighterButton.showColor = true
                        else -> {}
                    }
                }
            })
            start()
        }
    }

    private fun buttonFor(tool: InkTool): View = when (tool) {
        InkTool.CROP -> cropButton
        InkTool.TEXT -> textButton
        InkTool.ERASER -> eraserButton
        InkTool.PEN -> penButton
        InkTool.HIGHLIGHTER -> highlighterButton
    }

    private fun positionHighlight(btn: View, animate: Boolean) {
        val hv = highlightView ?: return
        val target = btn.left + btn.width / 2f - hv.width / 2f
        if (!animate) {
            hv.animate().cancel()
            hv.translationX = target
            // the bright highlight is home: clear the dim underneath
            dimmedButton?.background = defaultBg
            return
        }
        hv.animate()
            .translationX(target)
            .setDuration(120)
            .setInterpolator(DecelerateInterpolator(2f))
            .withEndAction {
                // only clear the dim if this button is still the target
                if (dimmedButton === btn) dimmedButton?.background = defaultBg
            }
            .start()
    }

    private fun applyButtonState(button: ImageButton, active: Boolean) {
        button.imageTintList = ColorStateList.valueOf(
            if (active) onPrimary else onSurface
        )
    }

    fun setColorPanelVisible(visible: Boolean) {
        colorPanel.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setSelectedColor(color: Int) {
        for (cb in colorButtons) {
            cb.checked = cb.color == color
        }
        // the palette button is "checked" only when the current color is a
        // custom one (not one of the presets)
        currentInkColor = color
        updatePaletteButton()
        penButton.activeColor = color
        highlighterButton.activeColor = color
        updateWidthPreview()
    }

    /** Palette button: ring sized like the color dots; tinted like toolbar icons. */
    private fun updatePaletteButton() {
        val selected = colorButtons.none { it.color == currentInkColor }
        val d = resources.displayMetrics.density
        val inset = (8f * d).toInt()
        val oval = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke((1.5f * d).toInt(), 0x669E9E9E.toInt())
            setColor(if (selected) currentInkColor else Color.TRANSPARENT)
        }
        paletteButton.background = InsetDrawable(oval, inset, inset, inset, inset)
        // idle: follow the theme like the other toolbar icons; selected:
        // pick black/white by the fill's luminance so the icon always reads
        val tint = if (!selected) {
            requireContext().themeColor(com.google.android.material.R.attr.colorOnSurface)
        } else {
            val lum = 0.299f * Color.red(currentInkColor) +
                0.587f * Color.green(currentInkColor) +
                0.114f * Color.blue(currentInkColor)
            if (lum > 140f) Color.BLACK else Color.WHITE
        }
        paletteButton.imageTintList = ColorStateList.valueOf(tint)
    }

    /** Highlights the pen width option matching [widthDp]. */
    fun setSelectedPenWidth(widthDp: Float) {
        penWidthSlider.progress = (widthDp - minPenWidth).toInt().coerceIn(0, 14)
        updateWidthPreview()
    }

    /** Renders the live width preview bar (height = current width, rounded). */
    private fun updateWidthPreview() {
        val widthDp = minPenWidth + penWidthSlider.progress
        val h = (widthDp * 2f * resources.displayMetrics.density).coerceIn(4f, 200f)
        penWidthPreview.layoutParams = penWidthPreview.layoutParams.apply { height = h.toInt() }
        val stroke = 1.5f * resources.displayMetrics.density
        penWidthPreview.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = h / 2f
            setStroke(stroke.toInt(), 0x999E9E9E.toInt())
            // fill with the current ink color (visible on any surface)
            setColor(currentInkColor)
        }
    }

    /** Anchor for the palette popup (the palette button itself). */
    fun paletteAnchor(): View = paletteButton
}
