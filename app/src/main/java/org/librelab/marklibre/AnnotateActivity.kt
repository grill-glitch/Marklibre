package org.librelab.marklibre

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import org.librelab.marklibre.text.TextEditorFragment
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class AnnotateActivity : AppCompatActivity() {

    private lateinit var canvas: DrawingCanvasView
    private lateinit var toolbarFragment: ToolbarFragment
    private lateinit var textFragment: TextEditorFragment
    private lateinit var cropOverlay: CropOverlayView
    private lateinit var trashDrop: ImageView
    private var trashOnPrimaryContainer = 0
    private var trashOnPrimary = 0
    private lateinit var cropActions: View
    private lateinit var toolbarContainer: View
    private lateinit var progress: View
    private lateinit var saveButton: MaterialButton
    private lateinit var undoButton: ImageButton
    private lateinit var redoButton: ImageButton

    private var inputUri: Uri? = null
    private var isScreenshotSource = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.annotate_activity_layout)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        canvas = findViewById(R.id.drawing_canvas)
        cropOverlay = findViewById(R.id.crop_overlay)
        cropActions = findViewById(R.id.crop_actions)
        progress = findViewById(R.id.progress)
        saveButton = findViewById(R.id.save)
        undoButton = findViewById(R.id.undo_button)
        redoButton = findViewById(R.id.redo_button)
        toolbarFragment = supportFragmentManager.findFragmentById(R.id.toolbar_fragment) as ToolbarFragment
        trashDrop = findViewById(R.id.trash_drop)
        trashOnPrimaryContainer = themeColor(
            com.google.android.material.R.attr.colorOnPrimaryContainer
        )
        trashOnPrimary = themeColor(com.google.android.material.R.attr.colorOnPrimary)
        textFragment = supportFragmentManager.findFragmentById(R.id.text_fragment) as TextEditorFragment

        setupInsets()
        setupActions()
        setupToolbar()
        setupTextEditor()
        setupCrop()

        undoButton.isEnabled = false
        redoButton.isEnabled = false

        inputUri = intent.data ?: intent.clipData?.getItemAt(0)?.uri
        isScreenshotSource = intent.getStringExtra("edit_source")?.equals("screenshot", true) == true
        if (isScreenshotSource) saveButton.setText(R.string.share)

        if (inputUri == null) {
            Toast.makeText(this, R.string.image_load_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        loadImage(inputUri!!)
    }

    private fun setupInsets() {
        window.decorView.setOnApplyWindowInsetsListener { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsets.Type.systemBars())
            val actionBar = view.findViewById<View>(R.id.action_bar)
            actionBar.setPadding(
                actionBar.paddingLeft,
                insets.top,
                actionBar.paddingRight,
                actionBar.paddingBottom
            )
            view.findViewById<View>(R.id.drawing_canvas).setPadding(0, 0, 0, insets.bottom)
            view.findViewById<View>(R.id.crop_overlay).setPadding(0, 0, 0, insets.bottom)
            view.findViewById<View>(R.id.crop_actions).setPadding(0, 0, 0, insets.bottom)
            view.findViewById<View>(R.id.progress).setPadding(0, insets.top, 0, insets.bottom)
            view.findViewById<View>(R.id.text_fragment).setPadding(0, 0, 0, 0)
            view.setPadding(insets.left, 0, insets.right, 0)
            // Do NOT consume: children (TextEditorFragment) need the raw
            // systemBars/IME insets to pad themselves (edge-to-edge).
            windowInsets
        }
    }

    private fun setupActions() {
        saveButton.setOnClickListener { doSave() }
        findViewById<View>(R.id.share).setOnClickListener { doShare() }
        findViewById<View>(R.id.delete).setOnClickListener { doDelete() }
        undoButton.setOnClickListener { canvas.undo() }
        redoButton.setOnClickListener { canvas.redo() }
    }

    private fun setupToolbar() {
        toolbarContainer = findViewById(R.id.toolbar_container)
        toolbarFragment.callbacks = object : ToolbarFragment.Callbacks {
            override fun onToolSelected(tool: InkTool) {
                if (tool == InkTool.CROP) {
                    enterCropMode()
                    return
                }
                canvas.tool = tool
                toolbarFragment.setActiveTool(tool)
                toolbarFragment.setColorPanelVisible(
                    tool == InkTool.PEN || tool == InkTool.HIGHLIGHTER
                )
            }

            override fun onColorSelected(color: Int) {
                canvas.color = color
                toolbarFragment.setSelectedColor(color)
            }

            override fun onRotate() {
                canvas.rotateImage()
            }
        }
        canvas.listener = object : DrawingCanvasView.Listener {
            override fun onUndoAvailability(hasUndo: Boolean, hasRedo: Boolean) {
                undoButton.isEnabled = hasUndo
                redoButton.isEnabled = hasRedo
            }

            override fun onRequestNewText(x: Float, y: Float) {
                textFragment.showForNew(x, y)
            }

            override fun onRequestTextEdit(element: InkElement.Text) {
                textFragment.showForEdit(element)
            }

            override fun onTextDragChanged(dragging: Boolean, x: Float, y: Float) {
                if (!dragging) {
                    hideTrash()
                    return
                }
                if (trashDrop.visibility != View.VISIBLE) showTrash()
                val active = trashBounds().contains(x, y)
                trashDrop.setBackgroundResource(
                    if (active) R.drawable.trash_sheet_active else R.drawable.trash_sheet
                )
                trashDrop.imageTintList = ColorStateList.valueOf(
                    if (active) trashOnPrimaryContainer else trashOnPrimary
                )
            }

            override fun onTextDropTargetContains(x: Float, y: Float): Boolean =
                trashBounds().contains(x, y)
        }
        toolbarFragment.setActiveTool(InkTool.PEN)
        toolbarFragment.setSelectedColor(canvas.color)
    }

    private fun setupTextEditor() {
        textFragment.onCommit = { text, color, font, editing, x, y ->
            if (editing != null) {
                canvas.updateText(editing, text, color, font)
            } else {
                canvas.addText(text, x, y, color, font)
            }
            canvas.tool = InkTool.PEN
            toolbarFragment.setActiveTool(InkTool.PEN)
            toolbarFragment.setColorPanelVisible(false)
        }
        textFragment.onDismissed = {
            canvas.tool = InkTool.PEN
            toolbarFragment.setActiveTool(InkTool.PEN)
            toolbarFragment.setColorPanelVisible(false)
        }
    }

    /** Trash bounds in canvas coordinates (canvas fills the FrameLayout). */
    private fun trashBounds(): RectF {
        val l = trashDrop.left.toFloat()
        val t = trashDrop.top.toFloat()
        return RectF(l, t, l + trashDrop.width, t + trashDrop.height)
    }

    private var trashShowing = false

    /** Slides the trash up into place with a fade-in (once per drag). */
    private fun showTrash() {
        trashShowing = true
        trashDrop.animate().cancel()
        trashDrop.visibility = View.VISIBLE
        trashDrop.alpha = 0f
        trashDrop.translationY = 56f * resources.displayMetrics.density
        trashDrop.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(160)
            .start()
    }

    /** Slides the trash down and fades it out, then hides it. */
    private fun hideTrash() {
        if (!trashShowing) return
        trashShowing = false
        trashDrop.animate().cancel()
        trashDrop.animate()
            .alpha(0f)
            .translationY(56f * resources.displayMetrics.density)
            .setDuration(160)
            .withEndAction {
                if (!trashShowing) trashDrop.visibility = View.GONE
            }
            .start()
    }

    private fun setupCrop() {
        findViewById<View>(R.id.crop_cancel).setOnClickListener { exitCropMode() }
        findViewById<View>(R.id.crop_confirm).setOnClickListener {
            canvas.applyCrop(cropOverlay.rect)
            exitCropMode()
        }
    }

    private fun enterCropMode() {
        canvas.tool = InkTool.CROP
        val r = canvas.sourceRectInView()
        if (r == null) return
        cropOverlay.setClampRect(r)
        cropOverlay.setInitialRect(r)
        cropOverlay.visibility = View.VISIBLE
        cropActions.visibility = View.VISIBLE
        toolbarContainer?.visibility = View.GONE
        undoButton.isEnabled = false
        redoButton.isEnabled = false
    }

    private fun exitCropMode() {
        cropOverlay.visibility = View.GONE
        cropActions.visibility = View.GONE
        toolbarContainer?.visibility = View.VISIBLE
        canvas.tool = InkTool.PEN
        toolbarFragment.setActiveTool(InkTool.PEN)
        // re-push the real undo/redo availability (a cancelled crop changes
        // nothing; a confirmed one already pushed a ReplaceImage op)
        canvas.refreshUndoState()
    }

    private fun loadImage(uri: Uri) {
        progress.visibility = View.VISIBLE
        Thread {
            val bm = decodeUri(uri)
            runOnUiThread {
                progress.visibility = View.GONE
                if (bm == null) {
                    Toast.makeText(this, R.string.image_load_failed, Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    canvas.setSourceBitmap(bm)
                }
            }
        }.start()
    }

    private fun decodeUri(uri: Uri): Bitmap? {
        return try {
            val resolver = contentResolver
            resolver.openInputStream(uri)?.use { input ->
                val opts = BitmapFactory.Options()
                opts.inJustDecodeBounds = true
                BitmapFactory.decodeStream(input, null, opts)
                var sample = 1
                val maxDim = 4096
                while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) {
                    sample *= 2
                }
                resolver.openInputStream(uri)?.use { input2 ->
                    val o2 = BitmapFactory.Options()
                    o2.inSampleSize = sample
                    BitmapFactory.decodeStream(input2, null, o2)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun doSave() {
        progress.visibility = View.VISIBLE
        Thread {
            try {
                val flat = canvas.flattenFullRes()
                val uri = saveToMediaStore(flat)
                runOnUiThread {
                    progress.visibility = View.GONE
                    val result = Intent().apply {
                        data = uri
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    setResult(Activity.RESULT_OK, result)
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    Toast.makeText(this, R.string.image_save_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /**
     * Writes the edited image into MediaStore (Pictures/Markup) so it shows up
     * in the gallery. Returns the MediaStore URI (readable by any gallery app).
     */
    private fun saveToMediaStore(bm: Bitmap): Uri {
        val ts = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "markup_$ts.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/Markup"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(collection, values)
            ?: throw IOException("MediaStore insert failed")
        contentResolver.openOutputStream(uri)?.use { out ->
            if (!bm.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw IOException("PNG compress failed")
            }
        } ?: throw IOException("MediaStore open failed")
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun doShare() {
        progress.visibility = View.VISIBLE
        Thread {
            try {
                val flat = canvas.flattenFullRes()
                val file = writePng(flat, "edited")
                runOnUiThread {
                    progress.visibility = View.GONE
                    val uri = FileProvider.getUriForFile(this, "org.librelab.marklibre", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, null))
                }
            } catch (e: IOException) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    Toast.makeText(this, R.string.image_save_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun doCopy() {
        progress.visibility = View.VISIBLE
        Thread {
            try {
                val flat = canvas.flattenFullRes()
                val uri = saveToMediaStore(flat)
                runOnUiThread {
                    progress.visibility = View.GONE
                    val clip = ClipData.newUri(contentResolver, "markup", uri)
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(clip)
                    Toast.makeText(this, R.string.copy, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    Toast.makeText(this, R.string.image_save_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun doDelete() {
        if (isScreenshotSource) {
            AlertDialog.Builder(this)
                .setMessage(R.string.delete_screenshot)
                .setPositiveButton(R.string.delete) { _, _ -> finish() }
                .setNegativeButton(R.string.crop_cancel, null)
                .show()
        } else {
            finish()
        }
    }

    private fun writePng(bm: Bitmap, subdir: String): File {
        val dir = File(cacheDir, subdir)
        dir.mkdirs()
        val file = File(dir, "markup_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bm.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file
    }

    override fun onBackPressed() {
        if (textFragment.view?.visibility == View.VISIBLE) {
            textFragment.dismiss()
            return
        }
        if (cropOverlay.visibility == View.VISIBLE) {
            exitCropMode()
            return
        }
        super.onBackPressed()
    }
}
