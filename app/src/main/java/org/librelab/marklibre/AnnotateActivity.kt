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
import com.google.android.material.materialswitch.MaterialSwitch
import org.librelab.marklibre.text.TextEditorFragment
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

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

    private val appPrefs by lazy {
        getSharedPreferences("marklibre", Context.MODE_PRIVATE)
    }

    override fun onStop() {
        super.onStop()
        // Final write in case the last change happened between two
        // onProgressChanged events that didn't trigger a persist (e.g.
        // very rapid slider gestures), or the process is being killed.
        toolbarFragment.persistToolState()
    }

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
                // Switching to any tool other than CROP while a crop is
                // open commits the crop first - the user implicitly said
                // the visible crop is what they want, then asked to draw
                // with the new tool.
                if (tool != InkTool.CROP) commitCropIfActive()
                if (tool == InkTool.CROP) {
                    // CROP is treated like every other tool: the toolbar
                    // stays visible, the button gets highlighted, and
                    // switching away commits the pending crop.
                    canvas.tool = tool
                    toolbarFragment.setActiveTool(tool)
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

            override fun onColorSelected(color: Int, source: ToolbarFragment.ColorSource) {
                // Picking a color mid-crop commits the crop first.
                commitCropIfActive()
                canvas.color = color
                toolbarFragment.setSelectedColor(color, source)
                // setSelectedColor already persists; no extra write needed.
            }

            override fun onRotate() {
                commitCropIfActive()
                canvas.rotateImage()
            }

            override fun onWidthSelected(widthDp: Float) {
                commitCropIfActive()
                if (canvas.tool == InkTool.HIGHLIGHTER) {
                    canvas.highlighterWidthDp = widthDp
                } else {
                    canvas.penWidthDp = widthDp
                }
            }

            override fun onPickColorRequested() {
                canvas.pickColorMode = true
                // Slide the panel down to PEEK so its top third stays above
                // the toolbar (visible, non-interactive); the image being
                // picked stays uncovered. We do not change the panel's rest
                // graph or layer order - the canvas's onTouchEvent already
                // receives the gesture because the panel only covers a thin
                // band between the image bottom and the toolbar.
                toolbarFragment.peekPalette()
            }
        }
        canvas.listener = object : DrawingCanvasView.Listener {
            override fun onUndoAvailability(hasUndo: Boolean, hasRedo: Boolean) {
                undoButton.isEnabled = hasUndo
                redoButton.isEnabled = hasRedo
            }

            override fun onColorPicked(color: Int) {
                // Eyedropper sample: published to the picker as a live
                // preview (Current swatch / hex / track follow the finger
                // without re-keying the slider). onColorPickFinished below
                // is what actually clears it on UP / CANCEL.
                toolbarFragment.setLivePreviewColor(color)
            }

            override fun onColorPickFinished() {
                // Eyedropper released. The live preview is dropped, the
                // picker is re-emitted with the last sample as its
                // starting color (so Current / hex / sliders land on the
                // sampled value rather than flashing back), and the panel
                // returns to its fully open position. Apply is still
                // required to commit the color to the ink.
                toolbarFragment.commitLivePreview()
                toolbarFragment.setPaletteState(ToolbarFragment.PaletteState.OPEN)
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
        // Restore last-used tool state from prefs (tool / color with the right
        // button highlighted / pen width / highlighter width). canvas.* is
        // the source of truth for the actual ink; sync those back too so a
        // stroke drawn before any UI interaction matches what the user saw
        // on the toolbar.
        val restored = toolbarFragment.restoreStateOrNull()
        if (restored != null) {
            canvas.tool = restored.tool
            canvas.color = restored.colorArgb
            canvas.penWidthDp = restored.penWidth
            canvas.highlighterWidthDp = restored.highlighterWidth
            // Mirror the color-row visibility a manual tool switch would
            // have produced, so a restored ERASER/TEXT does not come back
            // with the ink swatches showing. (CROP re-hides it itself, via
            // enterCropMode once the bitmap is in.)
            toolbarFragment.setColorPanelVisible(
                restored.tool == InkTool.PEN || restored.tool == InkTool.HIGHLIGHTER
            )
        } else {
            toolbarFragment.setActiveTool(InkTool.PEN)
            toolbarFragment.setSelectedColor(canvas.color)
            toolbarFragment.setSelectedPenWidth(canvas.penWidthDp)
            toolbarFragment.setSelectedHighlighterWidth(canvas.highlighterWidthDp)
        }
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
        // crop_cancel drops the pending crop without applying it. The
        // explicit confirm button is gone - commit-on-switch + commit-on-
        // save are the two ways to actually apply a crop.
        findViewById<View>(R.id.crop_cancel).setOnClickListener { exitCropMode() }
    }

    private fun enterCropMode() {
        canvas.tool = InkTool.CROP
        val r = canvas.sourceRectInView()
        if (r == null) return
        cropOverlay.setClampRect(r)
        cropOverlay.setInitialRect(r)
        cropOverlay.visibility = View.VISIBLE
        cropActions.visibility = View.VISIBLE
        // Toolbar stays visible - crop is a regular tool now, not a mode
        // that hides the rest of the UI.
        toolbarContainer?.visibility = View.VISIBLE
        // The ink color is irrelevant while cropping, so the swatch row
        // would just be noise. Hide it (and the pen-width row, which is
        // already hidden for non-brush tools). Also drop any open
        // picker popup - none of its controls are meaningful in crop
        // mode either.
        toolbarFragment.collapsePalette()
        toolbarFragment.setColorPanelVisible(false)
        undoButton.isEnabled = false
        redoButton.isEnabled = false
    }

    private fun exitCropMode(switchToolbarTo: InkTool? = InkTool.PEN) {
        cropOverlay.visibility = View.GONE
        cropActions.visibility = View.GONE
        toolbarContainer?.visibility = View.VISIBLE
        // Drop out of crop mode: the canvas needs to be in a drawable
        // state again. The toolbar button highlight is switched to
        // [switchToolbarTo] (defaults to PEN) only when the caller has
        // not already done so - the commit-on-switch paths pass null so
        // the caller's setActiveTool call is the single source of truth.
        canvas.tool = InkTool.PEN
        switchToolbarTo?.let { toolbarFragment.setActiveTool(it) }
        // Restore the ink color row: brush tools show it, non-brush
        // tools hide it. The current canvas tool is PEN at this point
        // (set just above) - if [switchToolbarTo] is non-null it
        // matches, otherwise the caller's setActiveTool already
        // re-evaluated color panel visibility.
        if (switchToolbarTo != null) {
            toolbarFragment.setColorPanelVisible(
                switchToolbarTo == InkTool.PEN || switchToolbarTo == InkTool.HIGHLIGHTER
            )
        }
        // re-push the real undo/redo availability (a cancelled crop changes
        // nothing; a confirmed one already pushed a ReplaceImage op)
        canvas.refreshUndoState()
    }

    /**
     * If a crop is currently in progress, commit it and exit the crop
     * overlay without changing the toolbar highlight. Used by every
     * "user did something other than cropping" path - the caller has
     * already (or will shortly) call setActiveTool with the new tool,
     * so this helper just cleans up the overlay / canvas state.
     */
    private fun commitCropIfActive() {
        if (cropOverlay.visibility != View.VISIBLE) return
        canvas.applyCrop(cropOverlay.rect)
        exitCropMode(switchToolbarTo = null)
    }

    /**
     * Save/share while the crop tool is still open: commit the pending crop
     * first so the output matches what the user sees. Without this, tapping
     * Save or Share mid-crop would silently save the uncropped image and
     * drop the crop selection.
     */
    private fun commitPendingCrop() {
        commitCropIfActive()
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
                    // Restoring CROP from prefs cannot show the overlay in
                    // onCreate - the source rect is unknown until the bitmap
                    // is in. Now that it is, enter crop mode so the restored
                    // tool state is consistent (canvas.tool is already CROP,
                    // so without this the canvas would be inert with no
                    // visible crop UI).
                    if (canvas.tool == InkTool.CROP) enterCropMode()
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
        runAfterProgressShown {
            commitPendingCrop()
            Thread { saveInBackground() }.start()
        }
    }

    private fun saveInBackground() {
        try {
            val flat = canvas.flattenFullRes()
            val uri = saveToMediaStore(
                flat,
                appPrefs.getBoolean("strip_on_save", false)
            )
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
    }

    /**
     * Shows the progress overlay and runs [block] only once that overlay has
     * actually reached the screen. Committing a pending crop flattens the
     * whole image at full resolution on the main thread, so running it before
     * the overlay was visible made Save/Share mid-crop stall with no feedback
     * at all. The double postOnAnimation lands [block] in the frame after the
     * one that draws the overlay.
     */
    private fun runAfterProgressShown(block: () -> Unit) {
        progress.visibility = View.VISIBLE
        progress.postOnAnimation { progress.postOnAnimation { block() } }
    }

    /**
     * Output format follows the source image (JPEG/PNG/WebP), so the edited
     * image keeps the same container as the original.
     */
    private fun sourceFormat(): Bitmap.CompressFormat {
        val uri = inputUri ?: return Bitmap.CompressFormat.JPEG
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull()
        val name = (uri.path ?: "").lowercase()
        return when {
            mime?.contains("png") == true || name.endsWith(".png") ->
                Bitmap.CompressFormat.PNG
            mime?.contains("webp") == true || name.endsWith(".webp") ->
                Bitmap.CompressFormat.WEBP_LOSSY
            else -> Bitmap.CompressFormat.JPEG
        }
    }

    private fun formatMime(format: Bitmap.CompressFormat): String = when (format) {
        Bitmap.CompressFormat.PNG -> "image/png"
        Bitmap.CompressFormat.WEBP_LOSSY, Bitmap.CompressFormat.WEBP ->
            "image/webp"
        else -> "image/jpeg"
    }

    private fun formatExt(format: Bitmap.CompressFormat): String = when (format) {
        Bitmap.CompressFormat.PNG -> "png"
        Bitmap.CompressFormat.WEBP_LOSSY, Bitmap.CompressFormat.WEBP -> "webp"
        else -> "jpg"
    }

    /**
     * Writes the edited image into MediaStore (Pictures/Markup) so it shows up
     * in the gallery. The output keeps the source's format; when
     * [stripMetadata] is true it is written without EXIF (JPEG) / without any
     * metadata (PNG, WebP), otherwise a JPEG keeps the source EXIF.
     * Returns the MediaStore URI (readable by any gallery app).
     */
    private fun saveToMediaStore(bm: Bitmap, stripMetadata: Boolean): Uri {
        val ts = System.currentTimeMillis()
        val format = sourceFormat()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "markup_$ts.${formatExt(format)}")
            put(MediaStore.Images.Media.MIME_TYPE, formatMime(format))
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
            val ok = if (format == Bitmap.CompressFormat.JPEG && !stripMetadata) {
                writeJpegWithSourceExif(bm, out)
            } else {
                bm.compress(format, 95, out)
            }
            if (!ok) {
                throw IOException("compress failed")
            }
        } ?: throw IOException("MediaStore open failed")
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun doShare() {
        runAfterProgressShown {
            commitPendingCrop()
            Thread { shareInBackground() }.start()
        }
    }

    private fun shareInBackground() {
        try {
            val flat = canvas.flattenFullRes()
            val strip = appPrefs.getBoolean("strip_on_share", true)
            val format = sourceFormat()
            val file = when {
                format == Bitmap.CompressFormat.JPEG && !strip ->
                    writeJpegWithExif(flat, "edited")
                else -> writeImage(flat, "edited", format)
            }
            runOnUiThread {
                progress.visibility = View.GONE
                val uri = FileProvider.getUriForFile(this, "org.librelab.marklibre", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = formatMime(format)
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
    }

    private fun doCopy() {
        progress.visibility = View.VISIBLE
        Thread {
            try {
                val flat = canvas.flattenFullRes()
                val uri = saveToMediaStore(
                    flat,
                    appPrefs.getBoolean("strip_on_save", false)
                )
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

    private fun writeImage(bm: Bitmap, subdir: String, format: Bitmap.CompressFormat): File {
        val dir = File(cacheDir, subdir)
        dir.mkdirs()
        val file = File(dir, "markup_${System.currentTimeMillis()}.${formatExt(format)}")
        FileOutputStream(file).use { out ->
            bm.compress(format, 95, out)
        }
        return file
    }

    private fun writeJpegWithExif(bm: Bitmap, subdir: String): File {
        val dir = File(cacheDir, subdir)
        dir.mkdirs()
        val file = File(dir, "markup_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            writeJpegWithSourceExif(bm, out)
        }
        return file
    }

    /**
     * Compresses [bm] to JPEG and splices the source image's raw APP1 (Exif)
     * segment right after the SOI marker, so the output keeps the original
     * metadata. Returns false on failure (caller should fall back).
     */
    private fun writeJpegWithSourceExif(bm: Bitmap, out: OutputStream): Boolean {
        val jpeg = ByteArrayOutputStream()
        if (!bm.compress(Bitmap.CompressFormat.JPEG, 95, jpeg)) return false
        val bytes = jpeg.toByteArray()
        val app1 = readJpegApp1(inputUri)
        return try {
            if (app1 == null) {
                out.write(bytes)
            } else {
                out.write(bytes, 0, 2)   // SOI
                out.write(app1)          // FFE1 + len + "Exif\0\0" + TIFF
                out.write(bytes, 2, bytes.size - 2)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Raw APP1 (Exif) segment of a JPEG source, or null (not JPEG / none). */
    private fun readJpegApp1(uri: Uri?): ByteArray? {
        val data = uri?.let {
            runCatching {
                contentResolver.openInputStream(it)?.use { s -> s.readBytes() }
            }.getOrNull()
        } ?: return null
        return Jpeg.readApp1Exif(data)
    }

    // ---------- quick reference (metadata + location) ----------

    private fun showQuickReference() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.quick_reference_sheet, null)
        sheet.setContentView(view)
        fillMetadata(view)
        val stripShare = view.findViewById<MaterialSwitch>(R.id.strip_on_share_switch)
        val stripSave = view.findViewById<MaterialSwitch>(R.id.strip_on_save_switch)
        stripShare.isChecked = appPrefs.getBoolean("strip_on_share", true)
        stripSave.isChecked = appPrefs.getBoolean("strip_on_save", false)
        stripShare.setOnCheckedChangeListener { _, c ->
            appPrefs.edit().putBoolean("strip_on_share", c).apply()
        }
        stripSave.setOnCheckedChangeListener { _, c ->
            appPrefs.edit().putBoolean("strip_on_save", c).apply()
        }
        view.findViewById<View>(R.id.help_button).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.metadata_help_title)
                .setMessage(R.string.metadata_help_body)
                .setPositiveButton(R.string.crop_cancel, null)
                .show()
        }
        view.findViewById<View>(R.id.fire_department_button).setOnClickListener {
            // strip on a worker thread: the JPEG path is a fast binary pass,
            // but large non-JPEG images still re-encode
            val btn = view.findViewById<View>(R.id.fire_department_button)
            btn.isEnabled = false
            Thread {
                val ok = stripMetadata()
                runOnUiThread {
                    btn.isEnabled = true
                    if (ok) {
                        fillMetadata(view)
                        Toast.makeText(this, R.string.metadata_cleared, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, R.string.image_save_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
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
     * Strips metadata & location from the source image. For JPEG this is a
     * fast binary pass that drops the APP1 (Exif) segments without decoding
     * or re-encoding the pixels; other formats fall back to re-encoding the
     * decoded bitmap. Runs on the calling thread (the caller uses a worker).
     */
    private fun stripMetadata(): Boolean {
        val uri = inputUri ?: return false
        return try {
            if (uri.scheme == "file") {
                val f = File(uri.path ?: return false)
                if (sourceFormat() == Bitmap.CompressFormat.JPEG) {
                    val data = f.readBytes()
                    val stripped = Jpeg.stripExif(data)
                    FileOutputStream(f).use { it.write(stripped) }
                    true
                } else {
                    val bm = decodeUri(uri) ?: return false
                    FileOutputStream(f).use { bm.compress(sourceFormat(), 95, it) }
                    true
                }
            } else {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
                val outBytes = if (sourceFormat() == Bitmap.CompressFormat.JPEG) {
                    Jpeg.stripExif(bytes)
                } else {
                    val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
                    val bos = ByteArrayOutputStream()
                    if (!bm.compress(sourceFormat(), 95, bos)) return false
                    bos.toByteArray()
                }
                contentResolver.openOutputStream(uri)?.use { it.write(outBytes) } ?: return false
                true
            }
        } catch (e: Exception) {
            false
        }
    }

}
