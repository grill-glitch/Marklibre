package org.librelab.marklibre

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import kotlin.math.roundToInt

/**
 * Custom color picker: hue / saturation / brightness / opacity sliders plus
 * a hex field, all kept in sync. Returns an ARGB color (alpha comes from the
 * opacity slider) via [onColorPicked].
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

    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.palette_dialog, null)
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
                    hueBar -> hue = progress / 100f * 360f
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

        AlertDialog.Builder(context)
            .setTitle(R.string.palette)
            .setView(view)
            .setPositiveButton(R.string.palette_apply) { _, _ -> onColorPicked(currentColor()) }
            .setNegativeButton(R.string.crop_cancel, null)
            .show()
    }
}
