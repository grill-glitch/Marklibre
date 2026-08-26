package org.librelab.marklibre.sticker

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.librelab.marklibre.ColorButton
import org.librelab.marklibre.DrawingCanvasView
import org.librelab.marklibre.checkOnly
import org.librelab.marklibre.colorButtons
import org.librelab.marklibre.toolIconTint
import org.librelab.marklibre.InkTool
import org.librelab.marklibre.R
import org.librelab.marklibre.themeColor
import java.io.File
import java.io.FileOutputStream

/**
 * Sticker editor: draw on a transparent canvas, save as a PNG sticker.
 * Mirrors the original StickerActivity (SAVE_STICKER permission flow).
 */
class StickerActivity : AppCompatActivity() {

    private lateinit var canvas: DrawingCanvasView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.sticker_activity)
        canvas = findViewById(R.id.sticker_canvas)
        canvas.tool = InkTool.PEN

        val pen = findViewById<ImageButton>(R.id.sticker_pen)
        val highlighter = findViewById<ImageButton>(R.id.sticker_highlighter)
        val eraser = findViewById<ImageButton>(R.id.sticker_eraser)

        val ring = androidx.core.content.ContextCompat.getDrawable(
            this, R.drawable.tool_button_highlight
        )
        val defaultBg = pen.background
        val neutral = themeColor(com.google.android.material.R.attr.colorOnSurface)
        // highlighted tools mimic the Save button (colorPrimary + colorOnPrimary)
        val onPrimary = themeColor(com.google.android.material.R.attr.colorOnPrimary)
        var activeTool: ImageButton = pen

        fun highlight(active: ImageButton) {
            activeTool = active
            fun apply(btn: ImageButton) {
                btn.background = if (active === btn) ring else defaultBg
                btn.imageTintList = android.content.res.ColorStateList.valueOf(
                    toolIconTint(
                        active === btn,
                        btn === pen || btn === highlighter,
                        canvas.color,
                        onPrimary,
                        neutral
                    )
                )
            }
            apply(pen)
            apply(highlighter)
            apply(eraser)
        }

        pen.setOnClickListener { canvas.tool = InkTool.PEN; highlight(pen) }
        highlighter.setOnClickListener { canvas.tool = InkTool.HIGHLIGHTER; highlight(highlighter) }
        eraser.setOnClickListener { canvas.tool = InkTool.ERASER; highlight(eraser) }

        val colorButtons = findViewById<ViewGroup>(R.id.sticker_colors).colorButtons()
        for (cb in colorButtons) {
            cb.setOnClickListener {
                canvas.color = cb.color
                colorButtons.checkOnly(cb.color)
                highlight(activeTool)
            }
        }
        colorButtons.firstOrNull()?.let { it.checked = true }

        findViewById<View>(R.id.sticker_save).setOnClickListener { saveSticker() }
    }

    private fun saveSticker() {
        Thread {
            try {
                val bm = canvas.flattenFullRes()
                val dir = File(cacheDir, "stickers")
                dir.mkdirs()
                val file = File(dir, "sticker_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { out ->
                    bm.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                runOnUiThread {
                    val uri = FileProvider.getUriForFile(this, "org.librelab.marklibre", file)
                    Toast.makeText(this, R.string.sticker_saved, Toast.LENGTH_SHORT).show()
                    val result = Intent().apply {
                        data = uri
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    setResult(Activity.RESULT_OK, result)
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, R.string.image_save_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}
