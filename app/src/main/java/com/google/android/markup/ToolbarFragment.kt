package com.google.android.markup

import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
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

        colorButtons = colorPanelChildren()

        cropButton.setOnClickListener { callbacks?.onToolSelected(InkTool.CROP) }
        textButton.setOnClickListener { callbacks?.onToolSelected(InkTool.TEXT) }
        eraserButton.setOnClickListener { callbacks?.onToolSelected(InkTool.ERASER) }
        penButton.setOnClickListener { callbacks?.onToolSelected(InkTool.PEN) }
        highlighterButton.setOnClickListener { callbacks?.onToolSelected(InkTool.HIGHLIGHTER) }

        for (cb in colorButtons) {
            cb.setOnClickListener { callbacks?.onColorSelected(cb.color) }
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
        val primary = colorPrimary()
        val neutral = requireContext().themeColor(
            com.google.android.material.R.attr.colorOnSurface
        )
        cropButton.imageTintList = ColorStateList.valueOf(
            if (tool == InkTool.CROP) primary else neutral
        )
        textButton.imageTintList = ColorStateList.valueOf(
            if (tool == InkTool.TEXT) primary else neutral
        )
        eraserButton.imageTintList = ColorStateList.valueOf(
            if (tool == InkTool.ERASER) primary else neutral
        )
        penButton.active = tool == InkTool.PEN
        highlighterButton.active = tool == InkTool.HIGHLIGHTER
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

    private fun colorPrimary(): Int {
        val tv = TypedValue()
        requireContext().theme.resolveAttribute(
            android.R.attr.colorPrimary, tv, true
        )
        return tv.data
    }
}
