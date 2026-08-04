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
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import org.librelab.marklibre.text.TextEditorFragment
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class AnnotateActivity : AppCompatActivity() {

    /**
     * BACK handling via the dispatcher (works on Android 16+ where
     * overriding onBackPressed() is no longer invoked with predictive
     * back). Text editor and crop mode consume BACK first; otherwise
     * unsaved edits ask before discarding.
     */
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (textFragment.view?.visibility == View.VISIBLE) {
                textFragment.dismiss()
                return
            }
            if (cropOverlay.visibility == View.VISIBLE) {
                exitCropMode()
                return
            }
            if (toolbarFragment.collapsePalette()) {
                return
            }
            if (canvas.hasEdits()) {
                AlertDialog.Builder(this@AnnotateActivity)
                    .setTitle(R.string.discard_changes)
                    .setMessage(R.string.discard_changes_message)
                    .setPositiveButton(R.string.discard) { _, _ -> finish() }
                    .setNegativeButton(R.string.crop_cancel, null)
                    .show()
                return
            }
            finish()
        }
    }

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
        onBackPressedDispatcher.addCallback(this, backCallback)

        undoButton.isEnabled = false
        redoButton.isEnabled = false

        inputUri = intent.data ?: intent.clipData?.getItemAt(0)?.uri
        // Used only to confirm before deleting a screenshot (see doDelete).
        isScreenshotSource = intent.getStringExtra("edit_source")?.equals("screenshot", true) == true

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
        findViewById<View>(R.id.quick_reference_button).setOnClickListener { showQuickReference() }
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
                val wasPen = canvas.tool == InkTool.PEN
                canvas.tool = tool
                toolbarFragment.setActiveTool(tool)
                if (tool == InkTool.PEN) {
                    if (wasPen) {
                        // re-tapping the pen toggles the color panel (and
                        // closes the palette panel first if it is open)
                        toolbarFragment.toggleColorPanel()
                    } else {
                        toolbarFragment.setColorPanelVisible(true)
                    }
                } else {
                    toolbarFragment.setColorPanelVisible(
                        tool == InkTool.HIGHLIGHTER
                    )
                }
            }

            override fun onColorSelected(color: Int) {
                canvas.color = color
                toolbarFragment.setSelectedColor(color)
            }

            override fun onRotate() {
                canvas.rotateImage()
            }

            override fun onWidthSelected(widthDp: Float) {
                canvas.penWidthDp = widthDp
            }

            override fun onPickColorRequested() {
                canvas.pickColorMode = true
            }
        }
        canvas.listener = object : DrawingCanvasView.Listener {
            override fun onUndoAvailability(hasUndo: Boolean, hasRedo: Boolean) {
                undoButton.isEnabled = hasUndo
                redoButton.isEnabled = hasRedo
            }

            override fun onColorPicked(color: Int) {
                toolbarFragment.setPickedColor(color)
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
        toolbarFragment.setSelectedPenWidth(canvas.penWidthDp)
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

    // ---------- quick reference (metadata + location) ----------

    private fun showQuickReference() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.quick_reference_sheet, null)
        sheet.setContentView(view)
        fillMetadata(view)
        view.findViewById<View>(R.id.help_button).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.metadata_help_title)
                .setMessage(R.string.metadata_help_body)
                .setPositiveButton(R.string.crop_cancel, null)
                .show()
        }
        view.findViewById<View>(R.id.fire_department_button).setOnClickListener {
            if (stripMetadata()) {
                fillMetadata(view)
                Toast.makeText(this, R.string.metadata_cleared, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.image_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
        sheet.show()
    }

    private fun fillMetadata(view: View) {
        val m = readMetadata()
        fun set(id: Int, value: String) {
            view.findViewById<TextView>(id).text = value
        }
        set(R.id.meta_source_value, m["source"] ?: getString(R.string.metadata_none))
        set(R.id.meta_dimensions_value, m["dimensions"] ?: getString(R.string.metadata_none))
        set(R.id.meta_modified_value, m["modified"] ?: getString(R.string.metadata_none))
        set(R.id.meta_location_value, m["location"] ?: getString(R.string.metadata_none))
        set(R.id.meta_taken_value, m["taken"] ?: getString(R.string.metadata_none))
        set(R.id.meta_camera_value, m["camera"] ?: getString(R.string.metadata_none))
    }

    private fun readMetadata(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val uri = inputUri ?: return out
        // source name: display name when available, else the raw URI
        val displayName = runCatching {
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        out["source"] = displayName ?: uri.toString()

        // dimensions from the decoded bounds (cheap, no full decode)
        runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, opts)
                if (opts.outWidth > 0) {
                    out["dimensions"] = "${opts.outWidth} × ${opts.outHeight}"
                }
            }
        }

        // last modified: file path or MediaStore column
        if (uri.scheme == "file") {
            val lm = runCatching { uri.path?.let { File(it).lastModified() } }.getOrNull() ?: 0L
            if (lm > 0) {
                out["modified"] = java.text.DateFormat.getDateTimeInstance()
                    .format(java.util.Date(lm))
            }
        } else {
            runCatching {
                contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) {
                            val secs = c.getLong(0)
                            if (secs > 0) {
                                out["modified"] = java.text.DateFormat.getDateTimeInstance()
                                    .format(java.util.Date(secs * 1000L))
                            }
                        }
                    }
            }
        }

        // EXIF: location (GPS), taken time, camera
        runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                val ll = FloatArray(2)
                if (exif.getLatLong(ll)) {
                    out["location"] = String.format("%.5f, %.5f", ll[0], ll[1])
                }
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.takeIf { it.isNotBlank() }
                    ?.let { out["taken"] = it }
                val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
                val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
                val camera = listOfNotNull(make, model).distinct().joinToString(" ")
                if (camera.isNotBlank()) out["camera"] = camera
            }
        }
        return out
    }

    /**
     * Strips metadata & location by re-encoding the source bitmap (decoded
     * pixels never carry EXIF) back over the original file. Returns true on
     * success.
     */
    private fun stripMetadata(): Boolean {
        val uri = inputUri ?: return false
        val bm = decodeUri(uri) ?: return false
        return try {
            val out = if (uri.scheme == "file") {
                val f = File(uri.path ?: return false)
                FileOutputStream(f)
            } else {
                contentResolver.openOutputStream(uri) ?: return false
            }
            out.use { bm.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        } catch (e: Exception) {
            false
        }
    }
}
