package org.librelab.marklibre

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.Typeface
import android.util.TypedValue

enum class InkTool { PEN, HIGHLIGHTER, ERASER, TEXT, CROP }

enum class StrokeStyle { PEN, HIGHLIGHTER, ERASER }

/** Resolves a theme attribute (e.g. colorOnSurface) to a concrete color. */
fun Context.themeColor(attr: Int, fallback: Int = Color.BLACK): Int {
    val tv = TypedValue()
    return if (theme.resolveAttribute(attr, tv, true)) tv.data else fallback
}

sealed class InkElement {
    class Stroke(
        val path: Path,
        val color: Int,
        var width: Float,
        val style: StrokeStyle
    ) : InkElement()

    class Text(
        var text: String,
        var x: Float,
        var y: Float,
        var size: Float,
        val color: Int,
        val font: String,
        var rotation: Float = 0f
    ) : InkElement()
}

class ImageState(val source: Bitmap?, val elements: List<InkElement>)

sealed class CanvasOp {
    class Add(val element: InkElement) : CanvasOp()
    class Remove(val index: Int, val element: InkElement) : CanvasOp()
    class EditText(val old: InkElement.Text, val new: InkElement.Text) : CanvasOp()
    class ReplaceImage(val prev: ImageState, val next: ImageState) : CanvasOp()
}

val FONT_NAMES = listOf("Bold", "Classic", "Modern", "Script", "Soft", "Bubbly")

fun fontTypeface(name: String): Typeface = when (name) {
    "Bold" -> Typeface.create("sans-serif", Typeface.BOLD)
    "Classic" -> Typeface.create("serif", Typeface.NORMAL)
    "Modern" -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
    "Script" -> Typeface.create("cursive", Typeface.NORMAL)
    "Soft" -> Typeface.create("sans-serif-light", Typeface.NORMAL)
    else -> Typeface.create("casual", Typeface.NORMAL)
}

/** Perceived brightness (0..255), used to pick black/white foregrounds. */
fun Int.luminance(): Float =
    0.299f * Color.red(this) + 0.587f * Color.green(this) + 0.114f * Color.blue(this)
