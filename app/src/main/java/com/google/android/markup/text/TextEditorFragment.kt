package com.google.android.markup.text

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.google.android.markup.ColorButton
import com.google.android.markup.FONT_NAMES
import com.google.android.markup.InkElement
import com.google.android.markup.R
import com.google.android.markup.fontTypeface

class TextEditorFragment : Fragment() {

    /** text, color, font, editing element (null = new), tap position */
    var onCommit: ((String, Int, String, InkElement.Text?, Float?, Float?) -> Unit)? = null
    var onDismissed: (() -> Unit)? = null

    private var editing: InkElement.Text? = null
    private var pendingX: Float? = null
    private var pendingY: Float? = null

    private var textColor: Int = 0
    private var selectedFont = FONT_NAMES[0]

    private lateinit var root: View
    private lateinit var textInput: MarkupEditText
    private lateinit var fontButtons: List<Button>

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.text_editor, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view
        textInput = view.findViewById(R.id.text_input)
        textColor = ContextCompat.getColor(requireContext(), R.color.ink_swatch_white)
        val done: ImageButton = view.findViewById(R.id.text_done)
        val editingLayout: LinearLayout = view.findViewById(R.id.editing_layout)
        val fontList: LinearLayout = view.findViewById(R.id.font_list)
        val colorList: LinearLayout = view.findViewById(R.id.color_list)

        // font row
        fontButtons = (0 until fontList.childCount).mapNotNull { i ->
            fontList.getChildAt(i) as? Button
        }
        for ((i, b) in fontButtons.withIndex()) {
            val name = FONT_NAMES[i]
            b.text = name
            b.typeface = fontTypeface(name)
            b.setOnClickListener { selectedFont = name; refreshFontHighlight() }
        }

        // color row
        val colorButtons = (0 until colorList.childCount).mapNotNull { i ->
            colorList.getChildAt(i) as? ColorButton
        }
        for (cb in colorButtons) {
            cb.setOnClickListener {
                textColor = cb.color
                for (other in colorButtons) other.checked = other === cb
                textInput.setTextColor(textColor)
            }
        }

        done.setOnClickListener { commit() }
        editingLayout.setOnClickListener { commit() }
        textInput.doAfterTextChanged { textInput.setTextColor(textColor) }

        // re-initialize after process death is out of scope for the replica
        refreshFontHighlight()
    }

    fun showForNew(x: Float?, y: Float?) {
        editing = null
        pendingX = x
        pendingY = y
        textColor = ContextCompat.getColor(requireContext(), R.color.ink_swatch_white)
        selectedFont = FONT_NAMES[0]
        textInput.setText("")
        textInput.setTextColor(textColor)
        refreshFontHighlight()
        refreshColorHighlight()
        root.visibility = View.VISIBLE
        textInput.requestFocus()
        root.post {
            val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(textInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun showForEdit(el: InkElement.Text) {
        editing = el
        pendingX = null
        pendingY = null
        textColor = el.color
        selectedFont = if (el.font in FONT_NAMES) el.font else FONT_NAMES[0]
        textInput.setText(el.text)
        textInput.setTextColor(textColor)
        refreshFontHighlight()
        refreshColorHighlight()
        root.visibility = View.VISIBLE
        textInput.requestFocus()
        root.post {
            val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(textInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun dismiss() {
        val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(textInput.windowToken, 0)
        root.visibility = View.GONE
        onDismissed?.invoke()
    }

    private fun commit() {
        val text = textInput.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) {
            onCommit?.invoke(text, textColor, selectedFont, editing, pendingX, pendingY)
        }
        dismiss()
    }

    private fun refreshFontHighlight() {
        for ((i, b) in fontButtons.withIndex()) {
            val selected = FONT_NAMES[i] == selectedFont
            b.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (selected) R.color.toolbar_tint else android.R.color.darker_gray
                )
            )
        }
    }

    private fun refreshColorHighlight() {
        val colorList = view?.findViewById<LinearLayout>(R.id.color_list) ?: return
        for (i in 0 until colorList.childCount) {
            val cb = colorList.getChildAt(i) as? ColorButton ?: continue
            cb.checked = cb.color == textColor
        }
    }
}
