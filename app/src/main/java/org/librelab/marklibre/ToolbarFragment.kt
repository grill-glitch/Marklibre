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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import org.librelab.marklibre.widget.CustomColorPicker

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

    // The inline panel + Compose host live in the activity layout; resolve
    // them lazily because the fragment view is inflated DURING the activity
    // layout inflation, when the panel (declared after toolbar_container)
    // does not exist yet - resolve them on first expansion instead.
    private val palettePanel: View by lazy {
        requireActivity().findViewById<View>(R.id.palette_panel)
    }
    private val paletteComposeView: ComposeView by lazy {
        requireActivity().findViewById<ComposeView>(R.id.palette_compose_view)
    }
    private val paletteViewport: View by lazy {
        requireActivity().findViewById<View>(R.id.palette_viewport)
    }
    private var paletteInited = false

    /**
     * The three positions the palette panel can rest in. [PEEK] slides it
     * down so only its top third stays visible above the toolbar - enough to
     * stay reachable without covering the image being edited.
     */
    enum class PaletteState { CLOSED, OPEN, PEEK }

    private var paletteState = PaletteState.CLOSED

    /** Fraction of its own height the panel slides down when peeking. */
    private val palettePeekSlide = 2f / 3f

    // Non-linear transitions, tuned per direction: opening springs with a
    // slight overshoot and is the slowest; peeking settles; closing is a
    // quick accelerate away.
    private val paletteOpenDurationMs = 320L
    private val palettePeekDurationMs = 260L
    private val paletteCloseDurationMs = 220L

    /** Color the picker opened with (for the Original swatch + Cancel). */
    private var paletteOriginalColor = Color.BLACK

    /** Bumped every time we re-emit Compose content (open / reopen / setPickedColor /
     *  Cancel-then-reopen) so the Compose layer can re-key its remember{} state. */
    private var paletteSessionKey = 0

    /**
     * Live color published while the eyedropper is sampling. null when the
     * eyedropper is not armed. Updates only trigger the picker's Current /
     * hex / track display - the underlying HSV state and slider position are
     * not touched, so the picker can keep showing whatever the user set up
     * before arming the eyedropper.
     */
    private val paletteLivePreviewColor = mutableStateOf<Int?>(null)

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
        setSliderFor(InkTool.PEN)
        updateWidthPreview()
        penWidthSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                updateWidthPreview()
                if (fromUser) {
                    val w = widthFor(currentTool, progress)
                    if (currentTool == InkTool.HIGHLIGHTER) currentHighlighterWidth = w
                    else currentPenWidth = w
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
        // the panel is a vertical stack (color row + pen width row), so the
        // dots are nested one level down - collect recursively
        return panel.findViewById<ViewGroup>(R.id.color_panel).colorButtons()
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
        setSliderFor(tool)
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
        val isInkTool = tool == InkTool.PEN || tool == InkTool.HIGHLIGHTER
        val inkColor = when (tool) {
            InkTool.PEN -> penButton.activeColor
            InkTool.HIGHLIGHTER -> highlighterButton.activeColor
            else -> 0
        }
        // PenButton.neutralColor always resolves from ?attr/colorOnSurface,
        // so onSurface is the equivalent neutral for every tool.
        val from = toolIconTint(false, isInkTool, inkColor, onPrimary, onSurface)
        val to = toolIconTint(true, isInkTool, inkColor, onPrimary, onSurface)
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
            toolIconTint(active, false, 0, onPrimary, onSurface)
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
        colorButtons.checkOnly(color)
        // the palette button is "checked" only when the current color is a
        // custom one (not one of the presets)
        currentInkColor = color
        updatePaletteButton()
        penButton.activeColor = color
        highlighterButton.activeColor = color
        updateWidthPreview()
        // tapping a preset color closes the palette panel
        collapsePalette()
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
            if (currentInkColor.luminance() > 140f) Color.BLACK else Color.WHITE
        }
        paletteButton.imageTintList = ColorStateList.valueOf(tint)
    }

    /** Width (dp) the slider progress represents for [tool]. */
    private fun widthFor(tool: InkTool, progress: Int): Float =
        if (tool == InkTool.HIGHLIGHTER) minHighlighterWidth + progress
        else minPenWidth + progress

    /** Slider progress for [widthDp] of [tool] (clamped to the range). */
    private fun progressFor(tool: InkTool, widthDp: Float): Int =
        if (tool == InkTool.HIGHLIGHTER) {
            (widthDp - minHighlighterWidth).toInt()
                .coerceIn(0, (maxHighlighterWidth - minHighlighterWidth).toInt())
        } else {
            (widthDp - minPenWidth).toInt()
                .coerceIn(0, (maxPenWidth - minPenWidth).toInt())
        }

    /** Sets the slider range + value for [tool] (2..16 dp pen, 8..32 dp highlighter). */
    private fun setSliderFor(tool: InkTool) {
        penWidthSlider.max =
            if (tool == InkTool.HIGHLIGHTER) (maxHighlighterWidth - minHighlighterWidth).toInt()
            else (maxPenWidth - minPenWidth).toInt()
        penWidthSlider.progress = progressFor(
            tool,
            if (tool == InkTool.HIGHLIGHTER) currentHighlighterWidth else currentPenWidth
        )
    }

    /** Highlights the pen width option matching [widthDp]. */
    fun setSelectedPenWidth(widthDp: Float) {
        currentPenWidth = widthDp
        if (currentTool == InkTool.PEN) {
            penWidthSlider.progress = progressFor(InkTool.PEN, widthDp)
        }
        updateWidthPreview()
    }

    /** Syncs the slider to the highlighter's width (used on start-up). */
    fun setSelectedHighlighterWidth(widthDp: Float) {
        currentHighlighterWidth = widthDp
        if (currentTool == InkTool.HIGHLIGHTER) {
            penWidthSlider.progress = progressFor(InkTool.HIGHLIGHTER, widthDp)
        }
        updateWidthPreview()
    }

    /** Renders the live width preview bar (height = current width, rounded). */
    private fun updateWidthPreview() {
        val widthDp = widthFor(currentTool, penWidthSlider.progress)
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

    /** Expands the inline palette panel (CLOSED <-> OPEN). */
    fun togglePalette() {
        if (paletteState != PaletteState.CLOSED) {
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
        startPaletteSession(if (paletteHasMemory) paletteRememberedColor else currentInkColor)
        setPaletteState(PaletteState.OPEN)
    }

    /** Collapses the palette panel; returns true if it was showing. */
    fun collapsePalette(): Boolean {
        val wasShowing = paletteState != PaletteState.CLOSED
        if (wasShowing) setPaletteState(PaletteState.CLOSED)
        return wasShowing
    }

    /**
     * Slides the panel down to [PaletteState.PEEK], leaving its top third
     * visible above the toolbar so the image being edited is not covered.
     *
     * Deliberately wired to nothing yet - the trigger is a separate decision.
     * A future trigger only has to call this (or [setPaletteState]); the state
     * and its animation already exist.
     */
    fun peekPalette() {
        if (paletteState == PaletteState.CLOSED) return
        setPaletteState(PaletteState.PEEK)
    }

    /**
     * Moves the panel to [target] with a non-linear transition: OPEN springs
     * up with a slight overshoot before settling, PEEK eases back down, and
     * CLOSED accelerates away and is then removed from the layout.
     *
     * [PaletteState.PEEK] has no caller yet by design.
     */
    fun setPaletteState(target: PaletteState) {
        if (target == paletteState) return
        val previous = paletteState
        paletteState = target
        val panel = palettePanel

        if (target == PaletteState.CLOSED) {
            val height = panel.height
            if (height <= 0) {
                // Never laid out (or already gone): nothing to animate.
                panel.visibility = View.GONE
                return
            }
            panel.animate().cancel()
            panel.animate()
                .translationY(height.toFloat())
                .setDuration(paletteCloseDurationMs)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    // A reopen that landed mid-close wins: only hide if we
                    // are still supposed to be closed.
                    if (paletteState == PaletteState.CLOSED) {
                        panel.visibility = View.GONE
                    }
                }
                .start()
            return
        }

        layoutPaletteViewport()
        if (previous == PaletteState.CLOSED) {
            // GONE views have no height. Show it first, then move it below the
            // clip line during the pre-draw pass: setting the translation
            // there means it is never drawn at its resting spot, so there is
            // no one-frame flash before the slide-up starts.
            panel.visibility = View.VISIBLE
            panel.translationY = 0f
            panel.doOnPreDraw {
                panel.translationY = panel.height.toFloat()
                slidePaletteTo(target)
            }
        } else {
            slidePaletteTo(target)
        }
    }

    /** Animates the panel from wherever it is to [target]'s resting offset. */
    private fun slidePaletteTo(target: PaletteState) {
        val panel = palettePanel
        val height = panel.height.toFloat()
        val endY = when (target) {
            PaletteState.OPEN -> 0f
            PaletteState.PEEK -> height * palettePeekSlide
            PaletteState.CLOSED -> height
        }
        val opening = target == PaletteState.OPEN
        panel.animate().cancel()
        panel.animate()
            .translationY(endY)
            .setDuration(if (opening) paletteOpenDurationMs else palettePeekDurationMs)
            .setInterpolator(
                if (opening) OvershootInterpolator(1.1f) else DecelerateInterpolator()
            )
            .start()
    }

    /**
     * Sizes the clipping viewport so its bottom edge sits just above the
     * toolbar; the panel is laid out against that edge. Sliding the panel
     * down therefore clips it at the toolbar instead of layering it over the
     * tools - and because the clip lives on the panel's parent, the hidden
     * part cannot swallow taps meant for the toolbar either.
     */
    private fun layoutPaletteViewport() {
        val toolbar = requireActivity().findViewById<View>(R.id.toolbar_container)
        val frame = paletteViewport.parent as? View ?: return
        val gap = (8f * resources.displayMetrics.density).toInt()
        val height = (frame.height - toolbar.height - gap).coerceAtLeast(0)
        val lp = paletteViewport.layoutParams as FrameLayout.LayoutParams
        if (lp.height != height) {
            lp.height = height
            paletteViewport.layoutParams = lp
        }
    }

    /**
     * Wire the ComposeView host once. Subsequent opens reuse it; [startPaletteSession]
     * re-keys the Compose state with a fresh color.
     */
    private fun setupPalette() {
        paletteComposeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
    }

    /**
     * (Re-)emit Compose content for a picker session. Bumping [paletteSessionKey]
     * forces the Compose layer's `remember(key)` blocks to re-evaluate with the
     * new initial color, mirroring what the old View-based `initPaletteFrom`
     * did for the four SeekBars.
     */
    private fun startPaletteSession(initialColor: Int) {
        paletteSessionKey++
        paletteComposeView.setContent {
            MaterialTheme(
                colorScheme = appColorScheme(),
            ) {
                CustomColorPicker(
                    initialColor = initialColor,
                    originalColor = paletteOriginalColor,
                    livePreviewColor = paletteLivePreviewColor.value,
                    onPickColorFromImage = {
                        callbacks?.onPickColorRequested()
                    },
                    onApply = { argb ->
                        callbacks?.onColorSelected(argb)
                        paletteRememberedColor = argb
                        paletteHasMemory = true
                        palettePrefs.edit().putInt("palette_color", argb).apply()
                        updatePaletteButton()
                        collapsePalette()
                    },
                    onCancel = {
                        // restore: just close; the next open() will reset
                        // because startPaletteSession re-keys Compose state.
                        collapsePalette()
                    },
                )
            }
        }
    }

    private fun isSystemInDarkMode(): Boolean {
        val night = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode()
        if (night == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) return true
        if (night == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO) return false
        val cfg = resources.configuration
        return (cfg.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * The app's Material 3 colour scheme, resolved from the Activity theme.
     *
     * The app uses [Theme.Material3.DynamicColors.DayNight] so every attribute
     * carries the wallpaper-derived (dynamic) palette. Resolving them into a
     * Compose [ColorScheme] keeps the picker popup visually identical to the
     * View UI instead of falling back to Material's baseline purple.
     */
    private fun appColorScheme(): ColorScheme {
        val ctx = requireContext()
        val base = if (isSystemInDarkMode()) darkColorScheme() else lightColorScheme()
        fun attr(
            androidAttr: Int,
            fallback: androidx.compose.ui.graphics.Color,
        ): androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(
            ctx.themeColor(androidAttr, fallback.toArgb()),
        )
        return base.copy(
            primary = attr(
                androidx.appcompat.R.attr.colorPrimary, base.primary,
            ),
            onPrimary = attr(
                com.google.android.material.R.attr.colorOnPrimary, base.onPrimary,
            ),
            primaryContainer = attr(
                com.google.android.material.R.attr.colorPrimaryContainer, base.primaryContainer,
            ),
            onPrimaryContainer = attr(
                com.google.android.material.R.attr.colorOnPrimaryContainer, base.onPrimaryContainer,
            ),
            surface = attr(
                com.google.android.material.R.attr.colorSurface, base.surface,
            ),
            onSurface = attr(
                com.google.android.material.R.attr.colorOnSurface, base.onSurface,
            ),
            onSurfaceVariant = attr(
                com.google.android.material.R.attr.colorOnSurfaceVariant, base.onSurfaceVariant,
            ),
            outline = attr(
                com.google.android.material.R.attr.colorOutline, base.outline,
            ),
        )
    }

    /** Applies a color picked from the image into the open panel. */
    fun setPickedColor(color: Int) {
        if (paletteState == PaletteState.CLOSED) return
        startPaletteSession(color)
    }

    /**
     * Live preview update from the eyedropper. Does NOT re-emit the picker:
     * it just publishes the sampled color, so only the Current swatch /
     * hex / track follow the finger. Slider and HSV state stay where they
     * were when the eyedropper was armed.
     */
    fun setLivePreviewColor(color: Int) {
        if (paletteState == PaletteState.CLOSED) return
        paletteLivePreviewColor.value = color
    }

    /**
     * Eyedropper gesture ended: drop the live preview. The picker returns
     * to whatever its underlying HSV state had been; the sampled color is
     * not committed unless the user later taps Apply.
     */
    fun clearLivePreview() {
        paletteLivePreviewColor.value = null
    }
}