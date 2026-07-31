package org.librelab.marklibre

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

class ToolbarFragment : Fragment() {

    interface Callbacks {
        fun onToolSelected(tool: InkTool)
        fun onColorSelected(color: Int)
    }

    var callbacks: Callbacks? = null

    private lateinit var colorPanel: View
    private lateinit var cropButton: ImageButton
    private lateinit var textButton: ImageButton
    private lateinit var eraserButton: ImageButton
    private lateinit var penButton: PenButton
    private lateinit var highlighterButton: PenButton
    private lateinit var colorButtons: List<ColorButton>

    private var highlightView: View? = null
    private var dimBg: Drawable? = null
    private var defaultBg: Drawable? = null
    private var dimmedButton: View? = null
    private var highlightReady = false
    private var onSurface = 0
    private var onPrimary = 0
    private var primaryColor = 0
    private var containerColor = 0
    private var highlightGradient: GradientDrawable? = null
    private var dimFadeAnim: ValueAnimator? = null
    private var highlightColorAnim: ValueAnimator? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.toolbar, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        colorPanel = view.findViewById(R.id.color_panel)
        cropButton = view.findViewById(R.id.crop_button)
        textButton = view.findViewById(R.id.ink_text_button)
        eraserButton = view.findViewById(R.id.ink_eraser_button)
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
        primaryColor = ctx.themeColor(android.R.attr.colorPrimary)
        containerColor = ctx.themeColor(com.google.android.material.R.attr.colorPrimaryContainer)
        // the bright highlight's fill is animated (container -> primary)
        highlightGradient = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(primaryColor)
        }
        highlightView?.background = highlightGradient

        colorButtons = colorPanelChildren()

        cropButton.setOnClickListener { callbacks?.onToolSelected(InkTool.CROP) }
        textButton.setOnClickListener { callbacks?.onToolSelected(InkTool.TEXT) }
        eraserButton.setOnClickListener { callbacks?.onToolSelected(InkTool.ERASER) }
        penButton.setOnClickListener { callbacks?.onToolSelected(InkTool.PEN) }
        highlighterButton.setOnClickListener { callbacks?.onToolSelected(InkTool.HIGHLIGHTER) }

        for (cb in colorButtons) {
            cb.setOnClickListener { callbacks?.onColorSelected(cb.color) }
        }

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
        applyButtonState(cropButton, tool == InkTool.CROP)
        applyButtonState(textButton, tool == InkTool.TEXT)
        applyButtonState(eraserButton, tool == InkTool.ERASER)
        penButton.active = tool == InkTool.PEN
        highlighterButton.active = tool == InkTool.HIGHLIGHTER
        // only the highlighted brush itself shows the current ink color
        penButton.showColor = tool == InkTool.PEN
        highlighterButton.showColor = tool == InkTool.HIGHLIGHTER

        // tool-switch animation: the newly clicked tool gets a dim highlight
        // immediately, while the bright highlight slides over to it
        val newButton = buttonFor(tool)
        dimmedButton?.background = defaultBg
        dimmedButton = newButton
        newButton.background = dimBg
        fadeDimIn()
        positionHighlight(newButton, animate = highlightReady)
        highlightReady = true
    }

    /** Fades the dim highlight in on the newly selected tool (alpha 0->1). */
    private fun fadeDimIn() {
        dimFadeAnim?.cancel()
        val d = dimBg ?: return
        d.alpha = 0
        dimFadeAnim = ValueAnimator.ofInt(0, 255).apply {
            duration = 160
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { d.alpha = it.animatedValue as Int }
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
            highlightGradient?.setColor(primaryColor)
            // the bright highlight is home: clear the dim underneath
            dimmedButton?.background = defaultBg
            return
        }
        hv.animate()
            .translationX(target)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator(2f))
            .withEndAction {
                // only clear the dim if this button is still the target
                if (dimmedButton === btn) dimmedButton?.background = defaultBg
            }
            .start()
        // color gradient: the traveling highlight fades from the dim
        // container tone to the full primary as it reaches the new tool
        highlightColorAnim?.cancel()
        highlightColorAnim = ValueAnimator.ofObject(ArgbEvaluator(), containerColor, primaryColor).apply {
            duration = 220
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                highlightGradient?.setColor(it.animatedValue as Int)
            }
            start()
        }
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
        penButton.activeColor = color
        highlighterButton.activeColor = color
    }
}
