package org.librelab.marklibre

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.PopupWindow
import android.widget.SeekBar
import kotlin.math.roundToInt

/**
 * Custom color picker shown as a popup anchored to the palette button
 * (non-modal, dismisses on outside tap). Hue / saturation / brightness /
 * opacity sliders plus a hex field, all kept in sync. Returns an ARGB
 * color (alpha from the opacity slider) via [onColorPicked].
 */
class PaletteDialog(
    private val context: Context,
    private val initialColor: Int,
    private val onColorPicked: (Int) -> Unit
) {

    private var hue = 0f
    private var saturation = 1f
    private var brightness = 1f
    private var alpha = 255
    private var syncing = false

    fun show(anchor: View) {
        val view = LayoutInflater.from(context).inflate(R.layout.palette_popup, null)
        val preview = view.findViewById<View>(R.id.palette_preview)
        val hueBar = view.findViewById<SeekBar>(R.id.palette_hue)
        val satBar = view.findViewById<SeekBar>(R.id.palette_saturation)
        val valBar = view.findViewById<SeekBar>(R.id.palette_value)
        val alphaBar = view.findViewById<SeekBar>(R.id.palette_opacity)
        val hexEdit = view.findViewById<EditText>(R.id.palette_hex)

        fun currentColor(): Int = Color.HSVToColor(alpha, floatArrayOf(hue, saturation, brightness))

        fun refreshPreview() {
            preview.setBackgroundColor(currentColor())
        }

        fun refreshHex() {
            if (syncing) return
            syncing = true
            hexEdit.setText(
                String.format(
                    "#%02X%02X%02X",
                    Color.red(currentColor()),
                    Color.green(currentColor()),
                    Color.blue(currentColor())
                )
            )
            syncing = false
        }

        fun refreshSliders() {
            if (syncing) return
            syncing = true
            hueBar.progress = (hue / 360f * 100f).roundToInt()
            satBar.progress = (saturation * 100f).roundToInt()
            valBar.progress = (brightness * 100f).roundToInt()
            alphaBar.progress = (alpha / 255f * 100f).roundToInt()
            syncing = false
        }

        fun applyHex() {
            val text = hexEdit.text.toString().trim().removePrefix("#")
            if (text.length != 6) return
            val rgb = text.toIntOrNull(16) ?: return
            val hsv = FloatArray(3)
            Color.colorToHSV(0xFF000000.toInt() or rgb, hsv)
            hue = hsv[0]
            saturation = hsv[1]
            brightness = hsv[2]
            refreshSliders()
            refreshPreview()
        }

        // dynamic slider tracks
        hueBar.progressDrawable = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(),
                0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFFFF00FF.toInt(),
                0xFFFF0000.toInt()
            )
        )
        val satTrack = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xFF888888.toInt(), 0xFFFF0000.toInt())
        )
        val valTrack = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xFF000000.toInt(), 0xFFFF0000.toInt())
        )
        val alphaTrack = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0x00000000, 0xFF000000.toInt())
        )

        fun tintSliders() {
            val rgb = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
            satTrack.colors = intArrayOf(Color.rgb(128, 128, 128), rgb)
            valTrack.colors = intArrayOf(Color.BLACK, rgb)
            satBar.progressDrawable = satTrack
            valBar.progressDrawable = valTrack
            alphaBar.progressDrawable = alphaTrack
        }

        // initialize from the current ink color
        val hsv = FloatArray(3)
        Color.colorToHSV(initialColor, hsv)
        hue = hsv[0]
        saturation = hsv[1]
        brightness = hsv[2]
        alpha = Color.alpha(initialColor)
        refreshSliders()
        refreshHex()
        refreshPreview()
        tintSliders()

        val sliderListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (syncing || !fromUser) return
                when (sb) {
                    hueBar -> {
                        hue = progress / 100f * 360f
                        // black/gray colors have sat=0/val=0, which would keep
                        // every hue black - restore color when the user drags hue
                        if (saturation < 0.05f) {
                            saturation = 1f
                            satBar.progress = 100
                        }
                        if (brightness < 0.05f) {
                            brightness = 1f
                            valBar.progress = 100
                        }
                    }
                    satBar -> saturation = progress / 100f
                    valBar -> brightness = progress / 100f
                    alphaBar -> alpha = (progress / 100f * 255f).roundToInt()
                }
                tintSliders()
                refreshHex()
                refreshPreview()
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        }
        hueBar.setOnSeekBarChangeListener(sliderListener)
        satBar.setOnSeekBarChangeListener(sliderListener)
        valBar.setOnSeekBarChangeListener(sliderListener)
        alphaBar.setOnSeekBarChangeListener(sliderListener)

        hexEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (syncing) return
                val text = s.toString().trim().removePrefix("#")
                if (text.length == 6 && text.toIntOrNull(16) != null) {
                    applyHex()
                    tintSliders()
                    refreshPreview()
                }
            }
        })

        // measure at the fixed popup width before showing so we can anchor
        // above the button
        val density = context.resources.displayMetrics.density
        val popupWidth = (300f * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popup = PopupWindow(
            view,
            popupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isFocusable = true
            isOutsideTouchable = true
            setBackgroundDrawable(
                GradientDrawable().apply {
                    cornerRadius = 16f * density
                    setColor(context.themeColor(com.google.android.material.R.attr.colorSurfaceContainer))
                }
            )
        }
        // BACK must not reach the activity: hide the IME first, then dismiss
        // the popup (otherwise BACK closes the whole editor)
        view.isFocusable = true
        view.setOnKeyListener { _, keyCode, _ ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                if (imm?.isAcceptingText == true) {
                    hexEdit.clearFocus()
                    imm.hideSoftInputFromWindow(hexEdit.windowToken, 0)
                } else {
                    popup.dismiss()
                }
                true
            } else {
                false
            }
        }
        // pop centered above the palette button
        val xoff = -(popupWidth - anchor.width) / 2
        val yoff = -(view.measuredHeight + anchor.height + (8f * density)).toInt()
        popup.showAsDropDown(anchor, xoff, yoff)

        // ADJUST_PAN does not move PopupWindows, so shift the popup up by
        // the keyboard height manually whenever the IME shows (and back on
        // dismiss) - otherwise the keyboard covers the hex field / apply.
        var panned = false
        val root = anchor.rootView
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            val rect = android.graphics.Rect()
            root.getWindowVisibleDisplayFrame(rect)
            val keyboardHeight = root.height - rect.bottom
            if (keyboardHeight > 100) {
                if (!panned) {
                    // shift up by the keyboard height, but never above the
                    // status bar (popup top in screen coords = anchor.bottom + y);
                    // rect.top is 0 when the status bar is a transparent overlay
                    val statusBar = if (rect.top > 0) {
                        rect.top
                    } else {
                        val res = context.resources
                        val id = res.getIdentifier("status_bar_height", "dimen", "android")
                        if (id > 0) res.getDimensionPixelSize(id) else 0
                    }
                    val minY = statusBar - anchor.bottom
                    popup.update(xoff, maxOf(yoff - keyboardHeight, minY), -1, -1)
                    panned = true
                }
            } else if (panned) {
                popup.update(xoff, yoff, -1, -1)
                panned = false
            }
        }
        root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        popup.setOnDismissListener {
            root.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        }

        view.findViewById<View>(R.id.palette_apply).setOnClickListener {
            popup.dismiss()
            onColorPicked(currentColor())
        }
    }
}
