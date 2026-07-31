package com.google.android.markup

import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Typeface

enum class InkTool { PEN, HIGHLIGHTER, ERASER, TEXT, CROP }

enum class StrokeStyle { PEN, HIGHLIGHTER, ERASER }

sealed class InkElement {
    class Stroke(
        val path: Path,
        val color: Int,
        val width: Float,
        val style: StrokeStyle
    ) : InkElement()

    class Text(
        var text: String,
        var x: Float,
        var y: Float,
        var size: Float,
        val color: Int,
        val font: String
    ) : InkElement()
}

class ImageState(val source: Bitmap?, val elements: List<InkElement>)

sealed class CanvasOp {
    class Add(val element: InkElement) : CanvasOp()
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
