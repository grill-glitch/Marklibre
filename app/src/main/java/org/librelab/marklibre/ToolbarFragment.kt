package org.librelab.marklibre

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import kotlin.math.roundToInt

class ToolbarFragment : Fragment() {

    interface Callbacks {
        fun onToolSelected(tool: InkTool)
        /** Momentary action (not a tool): rotate the whole image 90° CW. */
        fun onRotate()
        fun onColorSelected(color: Int)
        /** A pen width option (in dp) was picked. */
        fun onWidthSelected(widthDp: Float)
        /** The eyedropper button was tapped: enter canvas pick mode. */
        fun onPickColorRequested()
    }

    var callbacks: Callbacks? = null

    private lateinit var colorPanel: View
    private lateinit var penWidthRow: View
    private lateinit var penWidthPreview: View
    private lateinit var penWidthSlider: SeekBar
    private lateinit var paletteButton: ImageView

    // The inline panel and its controls live in the activity layout; all
    // lazy because the fragment view is inflated DURING the activity layout
    // inflation, when the panel (declared after toolbar_container) does not
    // exist yet - resolve them on first expansion instead.
    private val palettePanel: View by lazy {
        requireActivity().findViewById<View>(R.id.palette_panel)
    }
    private val palettePreview: View by lazy {
        requireActivity().findViewById<View>(R.id.palette_preview)
    }
    private val paletteHue: SeekBar by lazy {
        requireActivity().findViewById<SeekBar>(R.id.palette_hue)
    }
    private val paletteSat: SeekBar by lazy {
        requireActivity().findViewById<SeekBar>(R.id.palette_saturation)
    }
    private val paletteVal: SeekBar by lazy {
        requireActivity().findViewById<SeekBar>(R.id.palette_value)
    }
    private val paletteAlpha: SeekBar by lazy {
        requireActivity().findViewById<SeekBar>(R.id.palette_opacity)
    }
    private val paletteHex: EditText by lazy {
        requireActivity().findViewById<EditText>(R.id.palette_hex)
    }
    private val paletteOriginalPreview: View by lazy {
        requireActivity().findViewById<View>(R.id.palette_preview_original)
    }
    private var paletteInited = false

    /** Color the picker opened with (for the Original swatch + Cancel). */
    private var paletteOriginalColor = Color.BLACK

    /** Remembered custom palette color (persisted across launches). */
    private val palettePrefs by lazy {
        requireContext().getSharedPreferences("marklibre", Context.MODE_PRIVATE)
    }
    private var paletteRememberedColor = 0
    private var paletteHasMemory = false

    private lateinit var cropButton: ImageButton
    private lateinit var textButton: ImageButton
    private lateinit var eraserButton: ImageButton
    private lateinit var rotateButton: ImageButton
    private lateinit var penButton: PenButton
    private lateinit var highlighterButton: PenButton
    private lateinit var colorButtons: List<ColorButton>

    /** Pen width range: 2..16 dp (slider progress 0..14). */
    private val minPenWidth = 2f
    private val maxPenWidth = 16f

    /** Highlighter width range: 8..32 dp (slider progress 0..24). */
    private val minHighlighterWidth = 8f
    private val maxHighlighterWidth = 32f

    /** Current widths per tool, kept in sync with the canvas. */
    private var currentPenWidth = 8f
    private var currentHighlighterWidth = 24f
    private var currentTool = InkTool.PEN

    /** Current ink color, mirrored into the width preview bar. */
    private var currentInkColor: Int = Color.BLACK

    // Inline palette picker state (HSV + alpha)
    private var pHue = 0f
    private var pSat = 1f
    private var pVal = 1f
    private var pAlpha = 255
    private var pSyncing = false
    private val paletteSatTrack = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(0xFF888888.toInt(), 0xFFFF0000.toInt())
    )
    private val paletteValTrack = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(0xFF000000.toInt(), 0xFFFF0000.toInt())
    )
    private val paletteAlphaTrack = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(0x00000000, 0xFF000000.toInt())
    )

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

        // width slider: 2..16 dp for the pen, 8..32 dp for the highlighter;
        // live preview bar mirrors the width
        currentTool = InkTool.PEN
        penWidthSlider.max = (maxPenWidth - minPenWidth).toInt()
        penWidthSlider.progress = (currentPenWidth - minPenWidth).toInt()
        updateWidthPreview()
        penWidthSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                updateWidthPreview()
                if (fromUser) {
                    val w = if (currentTool == InkTool.HIGHLIGHTER) {
                        minHighlighterWidth + progress
                    } else {
                        minPenWidth + progress
                    }
                    if (currentTool == InkTool.HIGHLIGHTER) {
                        currentHighlighterWidth = w
                    } else {
                        currentPenWidth = w
                    }
                    callbacks?.onWidthSelected(w)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        paletteButton.setOnClickListener { togglePalette() }
        paletteRememberedColor = palettePrefs.getInt("palette_color", -1)
        paletteHasMemory = paletteRememberedColor != -1

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
        // the panel is a vertical stack (color row + pen width row), so the
        // dots are nested one level down - walk recursively
        fun walk(v: View) {
            if (v is ColorButton) {
                out.add(v)
            } else if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(vg)
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
        // the width row is shared by the pen and the highlighter; the slider
        // range and value switch with the tool
        currentTool = tool
        penWidthRow.visibility =
            if (tool == InkTool.PEN || tool == InkTool.HIGHLIGHTER) View.VISIBLE else View.GONE
        if (tool == InkTool.HIGHLIGHTER) {
            penWidthSlider.max = (maxHighlighterWidth - minHighlighterWidth).toInt()
            penWidthSlider.progress =
                (currentHighlighterWidth - minHighlighterWidth).toInt().coerceIn(0, (maxHighlighterWidth - minHighlighterWidth).toInt())
        } else if (tool == InkTool.PEN) {
            penWidthSlider.max = (maxPenWidth - minPenWidth).toInt()
            penWidthSlider.progress =
                (currentPenWidth - minPenWidth).toInt().coerceIn(0, (maxPenWidth - minPenWidth).toInt())
        }
        updateWidthPreview()

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

    /**
     * Pen-button behavior: closes the palette panel first, then toggles the
     * color panel. Returns true when the panel ended up visible.
     */
    fun toggleColorPanel(): Boolean {
        collapsePalette()
        return if (colorPanel.visibility == View.VISIBLE) {
            colorPanel.visibility = View.GONE
            false
        } else {
            colorPanel.visibility = View.VISIBLE
            true
        }
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
        // tapping a preset color closes the palette panel
        if (palettePanel.visibility == View.VISIBLE) {
            collapsePalette()
        }
    }

    /** Palette button: ring sized like the color dots; tinted like toolbar icons. */
    private fun updatePaletteButton() {
        val selected = colorButtons.none { it.color == currentInkColor }
        val d = resources.displayMetrics.density
        val inset = (8f * d).toInt()
        // like the preset dots, the ring shows the palette's own color once a
        // custom color exists (remembered); neutral gray before that
        val ringColor = if (paletteHasMemory) paletteRememberedColor else 0x669E9E9E.toInt()
        val oval = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke((1.5f * d).toInt(), ringColor)
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
        currentPenWidth = widthDp
        if (currentTool == InkTool.PEN) {
            penWidthSlider.progress = (widthDp - minPenWidth).toInt().coerceIn(0, 14)
        }
        updateWidthPreview()
    }

    /** Syncs the slider to the highlighter's width (used on start-up). */
    fun setSelectedHighlighterWidth(widthDp: Float) {
        currentHighlighterWidth = widthDp
        if (currentTool == InkTool.HIGHLIGHTER) {
            penWidthSlider.progress =
                (widthDp - minHighlighterWidth).toInt()
                    .coerceIn(0, (maxHighlighterWidth - minHighlighterWidth).toInt())
        }
        updateWidthPreview()
    }

    /** Renders the live width preview bar (height = current width, rounded). */
    private fun updateWidthPreview() {
        val widthDp = if (currentTool == InkTool.HIGHLIGHTER) {
            minHighlighterWidth + penWidthSlider.progress
        } else {
            minPenWidth + penWidthSlider.progress
        }
        val h = (widthDp * 1.5f * resources.displayMetrics.density).coerceIn(4f, 130f)
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

    /** Expands/collapses the inline palette panel. */
    fun togglePalette() {
        if (palettePanel.visibility == View.VISIBLE) {
            collapsePalette()
            return
        }
        if (!paletteInited) {
            setupPalette()
            paletteInited = true
        }
        // remember the color we opened with (Original swatch + Cancel)
        paletteOriginalColor = currentInkColor
        // start from the remembered custom color (or the current ink color)
        initPaletteFrom(if (paletteHasMemory) paletteRememberedColor else currentInkColor)
        // float the panel right above the toolbar (same window, so taps
        // on the color dots / tools below pass straight through)
        val toolbar = requireActivity().findViewById<View>(R.id.toolbar_container)
        val lp = palettePanel.layoutParams as FrameLayout.LayoutParams
        lp.bottomMargin = toolbar.height + (8f * resources.displayMetrics.density).toInt()
        palettePanel.layoutParams = lp
        palettePanel.visibility = View.VISIBLE
    }

    /** Collapses the palette panel; returns true if it was open. */
    fun collapsePalette(): Boolean {
        val open = palettePanel.visibility == View.VISIBLE
        if (open) palettePanel.visibility = View.GONE
        return open
    }

    private fun paletteColor(): Int = Color.HSVToColor(pAlpha, floatArrayOf(pHue, pSat, pVal))

    private fun refreshPalettePreview() {
        palettePreview.setBackgroundColor(paletteColor())
    }

    private fun refreshPaletteOriginal() {
        paletteOriginalPreview.setBackgroundColor(paletteOriginalColor)
    }

    private fun refreshPaletteHex() {
        if (pSyncing) return
        pSyncing = true
        paletteHex.setText(
            String.format(
                "#%02X%02X%02X",
                Color.red(paletteColor()),
                Color.green(paletteColor()),
                Color.blue(paletteColor())
            )
        )
        pSyncing = false
    }

    private fun refreshPaletteSliders() {
        if (pSyncing) return
        pSyncing = true
        paletteHue.progress = (pHue / 360f * 100f).roundToInt()
        paletteSat.progress = (pSat * 100f).roundToInt()
        paletteVal.progress = (pVal * 100f).roundToInt()
        paletteAlpha.progress = (pAlpha / 255f * 100f).roundToInt()
        pSyncing = false
    }

    private fun applyPaletteHex() {
        val text = paletteHex.text.toString().trim().removePrefix("#")
        if (text.length != 6) return
        val rgb = text.toIntOrNull(16) ?: return
        val hsv = FloatArray(3)
        Color.colorToHSV(0xFF000000.toInt() or rgb, hsv)
        pHue = hsv[0]
        pSat = hsv[1]
        pVal = hsv[2]
        refreshPaletteSliders()
        refreshPalettePreview()
    }

    private fun tintPaletteSliders() {
        val rgb = Color.HSVToColor(floatArrayOf(pHue, 1f, 1f))
        paletteSatTrack.colors = intArrayOf(Color.rgb(128, 128, 128), rgb)
        paletteValTrack.colors = intArrayOf(Color.BLACK, rgb)
        paletteSat.progressDrawable = paletteSatTrack
        paletteVal.progressDrawable = paletteValTrack
        paletteAlpha.progressDrawable = paletteAlphaTrack
    }

    /** Initializes the picker sliders/hex/preview from [color]. */
    private fun initPaletteFrom(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        pHue = hsv[0]
        pSat = hsv[1]
        pVal = hsv[2]
        pAlpha = Color.alpha(color)
        refreshPaletteSliders()
        refreshPaletteHex()
        refreshPalettePreview()
        refreshPaletteOriginal()
        tintPaletteSliders()
    }

    /** Wires the inline palette picker (sliders, hex, apply). */
    private fun setupPalette() {
        paletteHue.progressDrawable = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(),
                0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFFFF00FF.toInt(),
                0xFFFF0000.toInt()
            )
        )

        val sliderListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (pSyncing || !fromUser) return
                when (sb) {
                    paletteHue -> {
                        pHue = progress / 100f * 360f
                        // black/gray start colors have sat/val 0 - restore them
                        // so dragging hue produces color, not more black
                        if (pSat < 0.05f) {
                            pSat = 1f
                            paletteSat.progress = 100
                        }
                        if (pVal < 0.05f) {
                            pVal = 1f
                            paletteVal.progress = 100
                        }
                    }
                    paletteSat -> pSat = progress / 100f
                    paletteVal -> pVal = progress / 100f
                    paletteAlpha -> pAlpha = (progress / 100f * 255f).roundToInt()
                }
                tintPaletteSliders()
                refreshPaletteHex()
                refreshPalettePreview()
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        }
        paletteHue.setOnSeekBarChangeListener(sliderListener)
        paletteSat.setOnSeekBarChangeListener(sliderListener)
        paletteVal.setOnSeekBarChangeListener(sliderListener)
        paletteAlpha.setOnSeekBarChangeListener(sliderListener)

        paletteHex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (pSyncing) return
                val text = s.toString().trim().removePrefix("#")
                if (text.length == 6 && text.toIntOrNull(16) != null) {
                    applyPaletteHex()
                    tintPaletteSliders()
                    refreshPalettePreview()
                }
            }
        })

        requireActivity().findViewById<View>(R.id.palette_apply).setOnClickListener {
            val color = paletteColor()
            callbacks?.onColorSelected(color)
            // remember the custom color for next launch
            paletteRememberedColor = color
            paletteHasMemory = true
            palettePrefs.edit().putInt("palette_color", color).apply()
            updatePaletteButton()
            collapsePalette()
        }

        // Cancel: restore the color the picker opened with and close
        requireActivity().findViewById<View>(R.id.palette_cancel).setOnClickListener {
            initPaletteFrom(paletteOriginalColor)
            collapsePalette()
        }

        // Eyedropper: hand off to the activity, which puts the canvas into
        // pick mode; the picked color comes back via setPickedColor()
        requireActivity().findViewById<View>(R.id.palette_colorize).setOnClickListener {
            callbacks?.onPickColorRequested()
        }

        initPaletteFrom(if (paletteHasMemory) paletteRememberedColor else currentInkColor)
    }

    /** Applies a color picked from the image into the open panel. */
    fun setPickedColor(color: Int) {
        if (palettePanel.visibility != View.VISIBLE) return
        initPaletteFrom(color)
    }
}