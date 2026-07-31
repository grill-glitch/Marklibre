package com.google.android.markup.sticker

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.markup.ColorButton
import com.google.android.markup.DrawingCanvasView
import com.google.android.markup.InkTool
import com.google.android.markup.R
import com.google.android.markup.themeColor
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
        val onContainer = themeColor(com.google.android.material.R.attr.colorOnPrimaryContainer)

        fun highlight(active: ImageButton) {
            fun apply(btn: ImageButton) {
                btn.background = if (active === btn) ring else defaultBg
                btn.imageTintList = android.content.res.ColorStateList.valueOf(
                    if (active === btn) onContainer else neutral
                )
            }
            apply(pen)
            apply(highlighter)
            apply(eraser)
        }

        pen.setOnClickListener { canvas.tool = InkTool.PEN; highlight(pen) }
        highlighter.setOnClickListener { canvas.tool = InkTool.HIGHLIGHTER; highlight(highlighter) }
        eraser.setOnClickListener { canvas.tool = InkTool.ERASER; highlight(eraser) }

        val colorButtons = (0 until (findViewById<View>(R.id.sticker_colors) as android.view.ViewGroup).childCount)
            .mapNotNull { i ->
                (findViewById<View>(R.id.sticker_colors) as android.view.ViewGroup)
                    .getChildAt(i) as? ColorButton
            }
        for (cb in colorButtons) {
            cb.setOnClickListener {
                canvas.color = cb.color
                for (other in colorButtons) other.checked = other === cb
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
                    val uri = FileProvider.getUriForFile(this, "com.google.android.markup", file)
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
