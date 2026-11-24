package app.cameraderie.gallery.media.edit

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import app.cameraderie.R
import app.cameraderie.gallery.media.GalleryFrameExporter
import app.cameraderie.telemetry.TelemetryActivity
import app.cameraderie.gallery.media.DocumentFolder
import app.cameraderie.gallery.media.history.ImportHistoryRepository
import app.cameraderie.sony.SonyImportPreferences
import app.cameraderie.sony.SonyScreenProtection
import app.cameraderie.sony.SonyScreenWindows
import app.cameraderie.ui.CameraderieActivity
import app.cameraderie.ui.UiKit
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.RangeSlider
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.Executors
import kotlin.math.roundToLong

@OptIn(UnstableApi::class)
class VideoEditorActivity : CameraderieActivity() {
    private enum class State { VERIFYING, READY, LOADING_LUT, EXPORTING, SAVING, UNAVAILABLE }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelOnExit = CancellationSignal()
    private var scratch: VideoEditorScratch? = null
    private var state = State.VERIFYING
    private var original: VerifiedVideo? = null
    private var lut: CubeLut? = null
    private var lutUri: Uri? = null
    private var lutName: String? = null
    private var pendingLut: Uri? = null
    private var pendingLutFolder: Uri? = null
    private var pendingSliders: FloatArray? = null
    private var interruptedExport = false
    private var crop = CropSelection.FULL
    private var cropping = false
    private var format = VideoOutput.AVC
    private var main10Available = false
    private var previewEdit: Media3VideoEdit? = null
    private var player: ExoPlayer? = null
    private var transformer: Transformer? = null
    private var checkpoints: VideoExportCheckpoints? = null
    private var hasCheckpoint = false
    private var pauseAction: (() -> Unit)? = null
    private var resumeFiles: VideoExportCheckpoints.Resume? = null
    private var started = false

    private lateinit var ui: UiKit
    private lateinit var options: LinearLayout
    private lateinit var playerView: PlayerView
    private lateinit var sourceInfo: TextView
    private lateinit var previewInfo: TextView
    private lateinit var lutInfo: TextView
    private lateinit var status: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var trimSlider: RangeSlider
    private lateinit var trimFilmstrip: TrimFilmstrip
    private lateinit var cropOverlay: CropOverlay
    private lateinit var cropButton: MaterialButton
    private lateinit var cropHelp: TextView
    private lateinit var cropSize: TextView
    private lateinit var resetCrop: MaterialButton
    private lateinit var nudgeCrop: MaterialButton
    private lateinit var formatButton: MaterialButton
    private lateinit var chooseLut: MaterialButton
    private lateinit var removeLut: MaterialButton
    private lateinit var preview: MaterialButton
    private lateinit var export: MaterialButton
    private lateinit var resumeExport: MaterialButton
    private lateinit var pause: MaterialButton
    private lateinit var cancel: MaterialButton
    private lateinit var frame: MaterialButton
    private lateinit var ride: MaterialButton

    private val lutPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            if (state == State.READY) loadLut(uri) else if (state == State.VERIFYING) pendingLut = uri
        }
    }
    private val lutFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            if (state == State.READY) findLuts(uri) else if (state == State.VERIFYING) pendingLutFolder = uri
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingSliders = savedInstanceState?.getFloatArray(SLIDER_VALUES)
        pendingLut = savedInstanceState?.getString(LUT_URI)?.let(Uri::parse)
        pendingLutFolder = savedInstanceState?.getString(LUT_FOLDER_URI)?.let(Uri::parse)
        interruptedExport = savedInstanceState?.getBoolean(EXPORT_ACTIVE) == true
        crop = CropSelection.restore(savedInstanceState?.getFloatArray(CROP_BOX))
        cropping = savedInstanceState?.getBoolean(CROP_ACTIVE) == true
        format = VideoOutput.entries.firstOrNull { it.name == savedInstanceState?.getString(OUTPUT_FORMAT) }
            ?: VideoOutput.AVC
        protect()
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        val id = intent.getStringExtra(IMPORT_ID)
        if (id.isNullOrBlank()) {
            setState(State.UNAVAILABLE, "Open the original video from Library.")
            return
        }
        setState(State.VERIFYING, "Verifying the saved original…")
        worker.execute {
            val loaded = runCatching {
                val staging = VideoEditorScratch.create(noBackupFilesDir)
                scratch = staging
                val video = VerifiedVideo.load(id, ImportHistoryRepository.get(this), contentResolver,
                    File(staging.directory, "original"), cancelOnExit)
                val main10 = video.acceptsRec709Lut && runCatching(Main10Export::available).getOrDefault(false)
                val store = VideoExportCheckpoints(noBackupFilesDir, cacheDir, video)
                Triple(video, main10, store to store.exists())
            }
            if (loaded.isFailure) scratch?.close()
            deliver {
                loaded.fold(onSuccess = { (video, main10, saved) ->
                    original = video
                    checkpoints = saved.first
                    hasCheckpoint = saved.second
                    configureTrim(video, pendingSliders)
                    cropOverlay.setVideoSize(video.width, video.height)
                    cropOverlay.setSelection(crop)
                    main10Available = main10
                    if (!main10Available) format = VideoOutput.AVC
                    updateCropLabel()
                    updateFormatLabel()
                    pendingSliders = null
                    sourceInfo.text = getString(R.string.video_editor_source, video.name, video.width, video.height,
                        seconds(video.durationMs))
                    setState(State.READY, when {
                        hasCheckpoint -> "Resume export uses the saved trim, crop, LUT and video format."
                        interruptedExport -> "Export interrupted. Export again when you’re ready."
                        else -> ""
                    })
                    interruptedExport = false
                    showPreview(null)
                    updateLut()
                    val restoredLut = pendingLut
                    pendingLut = null
                    val restoredFolder = pendingLutFolder
                    pendingLutFolder = null
                    if (video.acceptsRec709Lut) {
                        if (restoredFolder != null) findLuts(restoredFolder)
                        else if (restoredLut != null) loadLut(restoredLut)
                    }
                }, onFailure = { error ->
                    Log.w(TAG, "Original could not be opened", error)
                    setState(State.UNAVAILABLE, (error as? IOException)?.message ?: "Couldn’t open the verified original.")
                })
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putFloatArray(SLIDER_VALUES, if (original == null) pendingSliders else trimSlider.values.toFloatArray())
        outState.putFloatArray(CROP_BOX, crop.savedValues())
        outState.putBoolean(CROP_ACTIVE, cropping)
        outState.putString(OUTPUT_FORMAT, format.name)
        outState.putString(LUT_URI, (pendingLut ?: lutUri)?.toString())
        outState.putString(LUT_FOLDER_URI, pendingLutFolder?.toString())
        outState.putBoolean(EXPORT_ACTIVE, interruptedExport || state == State.EXPORTING || state == State.SAVING)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        started = true
        protect()
        if (state == State.READY || state == State.LOADING_LUT) {
            showPreview(previewEdit)
            original?.let(trimFilmstrip::start)
        }
    }

    override fun onStop() {
        started = false
        trimFilmstrip.stop()
        releasePreview()
        super.onStop()
    }

    override fun onDestroy() {
        cancelOnExit.cancel()
        trimFilmstrip.stop()
        transformer?.cancel()
        transformer = null
        releasePreview()
        main.removeCallbacksAndMessages(null)
        // Serialized behind any source check or publication still winding down.
        worker.execute { scratch?.close() }
        worker.shutdown()
        super.onDestroy()
    }

    private fun protect() =
        SonyScreenWindows.apply(window, SonyScreenProtection.required(SonyImportPreferences(this).allowGalleryScreenshots, true))

    private fun buildUi() {
        ui = UiKit(this)
        val root = ui.column().apply { setBackgroundColor(ui.tone.background) }
        root.addView(MaterialToolbar(this).apply {
            title = "Edit video"
            setTitleTextColor(ui.tone.text)
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationContentDescription = "Back to Library"
            setNavigationOnClickListener { leave() }
        })

        val (scroll, body) = ui.page()
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        sourceInfo = ui.text("", UiKit.Type.small, secondary = true).apply { setTextIsSelectable(true) }
        ui.add(body, sourceInfo)

        options = ui.column().apply { visibility = View.GONE }
        ui.add(body, options)
        preview = ui.action("Preview edits") {
            settings()?.let {
                setCropping(false)
                showPreview(it)
                scroll.smoothScrollTo(0, 0)
            }
        }
        val display = ui.card(options, "Preview")
        buildCropPreview(display)
        frame = ui.action("Save full-resolution frame") { saveFrame() }
        ui.add(display, frame, 8)
        ride = ui.action("Ride telemetry") {
            original?.let { startActivity(TelemetryActivity.intent(this, it, originalPlayhead(it))) }
        }
        ui.add(options, ride, 12)

        buildTrim(ui.card(options, "Trim"))

        val color = ui.card(options, "Colour LUT")
        lutInfo = ui.text("", UiKit.Type.small, secondary = true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        ui.add(color, lutInfo)
        chooseLut = ui.action("Find LUT") { showLutFinder() }
        removeLut = ui.action("Remove LUT") {
            lut = null
            lutUri = null
            lutName = null
            updateLut()
            if (previewEdit?.lut != null) showPreview(null)
        }
        ui.row(color, chooseLut, removeLut)
        val output = ui.card(options, "Video format")
        formatButton = ui.action("") { chooseFormat() }
        ui.add(output, formatButton)
        updateFormatLabel()

        val footer = ui.column().apply {
            setBackgroundColor(ui.tone.background)
            setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(12))
        }
        status = ui.text("", UiKit.Type.small, secondary = true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        ui.add(footer, status)
        progress = LinearProgressIndicator(this).apply { visibility = View.GONE }
        ui.add(footer, progress, 6)
        export = ui.action("Export MP4", primary = true) { settings()?.let { startExport(it) } }
        resumeExport = ui.action("Resume export") { prepareResume() }
        ui.row(footer, export, resumeExport)
        pause = ui.action("Pause export", primary = true) { pauseAction?.invoke() }
        cancel = ui.action("Cancel export") { cancelExport() }
        ui.row(footer, pause, cancel)
        root.addView(footer)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        root.isFocusableInTouchMode = true
        root.requestFocus()
        setContentView(root)
    }

    private fun buildTrim(parent: LinearLayout) {
        val start = ui.text("", secondary = true)
        val end = ui.text("", secondary = true).apply { gravity = Gravity.END }
        val labels = LinearLayout(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(start, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(4) })
            addView(end, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(4) })
        }
        ui.add(parent, labels)
        trimSlider = RangeSlider(this).apply {
            contentDescription = "Trim range"
            minimumHeight = ui.dp(48)
            setLabelFormatter { "${seconds(it.roundToLong())} s" }
            addOnChangeListener { range, _, _ ->
                val (first, last) = range.values
                start.text = getString(R.string.video_editor_slider_value, "Start", "${seconds(first.roundToLong())} s")
                end.text = getString(R.string.video_editor_slider_value, "End", "${seconds(last.roundToLong())} s")
            }
            setValues(0f, 1f)
        }
        ui.add(parent, trimSlider, 4)
        trimFilmstrip = TrimFilmstrip(this, ui)
        parent.addView(trimFilmstrip, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = ui.dp(4)
            marginStart = trimSlider.trackSidePadding
            marginEnd = trimSlider.trackSidePadding
        })
    }

    private fun buildCropPreview(parent: LinearLayout) {
        val viewport = FrameLayout(this)
        playerView = layoutInflater.inflate(R.layout.video_editor_player, viewport, false) as PlayerView
        viewport.addView(playerView)
        cropOverlay = CropOverlay(this).apply {
            visibility = View.GONE
            onSelectionChanged = { selection ->
                crop = selection
                updateCropLabel()
            }
        }
        viewport.addView(cropOverlay, FrameLayout.LayoutParams(-1, -1))
        parent.addView(viewport, LinearLayout.LayoutParams(-1, ui.dp(212)))
        previewInfo = ui.text("Verified original", UiKit.Type.small, secondary = true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        ui.add(parent, previewInfo, 8)

        cropHelp = ui.text("Drag the box to move it. Drag a corner to resize.", UiKit.Type.small, secondary = true)
        cropSize = ui.text("", UiKit.Type.small, secondary = true)
        cropButton = ui.action("Crop video") {
            if (cropping) {
                setCropping(false)
                settings()?.let(::showPreview)
            } else {
                val position = (player?.currentPosition ?: 0L) + (previewEdit?.trim?.startMs ?: 0L)
                setCropping(true)
                if (previewEdit != null) showPreview(null, position) else player?.pause()
            }
        }
        resetCrop = ui.action("Reset crop") {
            crop = CropSelection.FULL
            cropOverlay.setSelection(crop)
            updateCropLabel()
        }
        nudgeCrop = ui.action("Fine-tune crop") { chooseCropHandle() }
        ui.add(parent, cropHelp, 8)
        ui.add(parent, cropSize, 6)
        ui.row(parent, cropButton, preview)
        ui.row(parent, resetCrop, nudgeCrop)
    }

    private fun chooseCropHandle() {
        val handles = CropHandle.entries
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Fine-tune crop")
            .setItems(handles.map { it.label }.toTypedArray()) { _, which -> nudgeDialog(handles[which]) }
            .setNegativeButton("Close", null)
            .create()
        SonyScreenWindows.showMedia(dialog, this)
    }

    private fun nudgeDialog(handle: CropHandle) {
        val summary = ui.text(cropSize.text.toString(), secondary = true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val controls = ui.column().apply { setPadding(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(12)) }
        ui.add(controls, summary)
        fun direction(label: String, x: Float, y: Float) = ui.action(label) {
            cropOverlay.nudge(handle, x, y)
            summary.text = cropSize.text
        }
        ui.row(controls, direction("Left", -0.01f, 0f), direction("Right", 0.01f, 0f))
        ui.row(controls, direction("Up", 0f, -0.01f), direction("Down", 0f, 0.01f))
        val dialog = MaterialAlertDialogBuilder(this).setTitle(handle.label)
            .setView(controls).setPositiveButton("Done", null).create()
        SonyScreenWindows.showMedia(dialog, this)
    }

    private fun updateCropLabel() {
        val video = original ?: return
        val frame = crop.frame(video.width, video.height)
        val label = if (frame == null) getString(R.string.video_editor_full_frame) else
            getString(R.string.video_editor_crop_size, frame.width, frame.height)
        if (cropSize.text.toString() != label) cropSize.text = label
    }

    private fun setCropping(active: Boolean) {
        cropping = active
        updateCropControls()
    }

    private fun updateCropControls() {
        val ready = state == State.READY
        val editing = ready && cropping
        cropOverlay.visibility = if (editing) View.VISIBLE else View.GONE
        cropOverlay.isEnabled = editing
        playerView.useController = !cropping
        cropButton.isEnabled = ready && (original?.width ?: 0) >= 16 && (original?.height ?: 0) >= 16
        cropButton.text = if (cropping) "Done cropping" else "Crop video"
        cropHelp.visibility = if (editing) View.VISIBLE else View.GONE
        resetCrop.visibility = if (editing) View.VISIBLE else View.GONE
        nudgeCrop.visibility = if (editing) View.VISIBLE else View.GONE
    }

    private fun chooseFormat() {
        val formats = if (main10Available) VideoOutput.entries else listOf(VideoOutput.AVC)
        val builder = MaterialAlertDialogBuilder(this).setTitle("Video format")
        if (main10Available) {
            builder.setSingleChoiceItems(formats.map { it.label }.toTypedArray(), formats.indexOf(format)) { dialog, which ->
                format = formats[which]
                updateFormatLabel()
                dialog.dismiss()
            }
        } else {
            builder.setMessage(if (original?.acceptsRec709Lut == true)
                "This device doesn’t offer 10-bit HEVC encoding. Export is set to 8-bit H.264."
            else "10-bit HEVC Rec.709 export needs an SDR Rec.709 source. Export is set to 8-bit H.264.")
        }
        SonyScreenWindows.showMedia(builder.setNegativeButton("Close", null).create(), this)
    }

    private fun updateFormatLabel() {
        formatButton.text = format.label
        formatButton.contentDescription = getString(R.string.video_editor_format_choice, format.label)
    }

    private fun configureTrim(video: VerifiedVideo, restored: FloatArray?) {
        val limit = video.durationMs.toFloat()
        val end = (restored?.getOrNull(1) ?: limit).coerceIn(1f, limit)
        val start = (restored?.getOrNull(0) ?: 0f).coerceIn(0f, end - 1f)
        trimSlider.valueTo = limit
        trimSlider.setMinSeparationValue(1f)
        trimSlider.setValues(start, end)
    }

    private fun setState(next: State, message: String) {
        state = next
        val ready = next == State.READY
        if (next == State.EXPORTING || next == State.SAVING) trimFilmstrip.stop()
        if (ready && started) original?.let(trimFilmstrip::start)
        options.visibility = if (original == null) View.GONE else View.VISIBLE
        trimSlider.isEnabled = ready
        updateCropControls()
        frame.isEnabled = ready
        ride.isEnabled = ready
        formatButton.isEnabled = ready
        preview.isEnabled = ready
        chooseLut.isEnabled = ready
        removeLut.isEnabled = ready
        export.visibility = if (ready) View.VISIBLE else View.GONE
        resumeExport.visibility = if (ready && hasCheckpoint) View.VISIBLE else View.GONE
        pause.visibility = if (next == State.EXPORTING && pauseAction != null) View.VISIBLE else View.GONE
        cancel.visibility = if (next == State.EXPORTING) View.VISIBLE else View.GONE
        val activity = next == State.VERIFYING || next == State.LOADING_LUT || next == State.SAVING
        progress.visibility = View.GONE
        progress.isIndeterminate = activity
        if (activity || next == State.EXPORTING) {
            if (!activity) progress.progress = 0
            progress.visibility = View.VISIBLE
        }
        status.text = message
        status.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateLut() {
        val video = original ?: return
        val guide = video.lutGuide
        val identity = listOfNotNull(guide.camera,guide.profile).joinToString(" · ")
        lutInfo.text = when {
            !video.acceptsRec709Lut -> "${guide.recommendation}\nApplying LUTs requires SDR footage in Rec.709."
            lut != null -> lutName?.let { "Selected: $it" } ?: "LUT selected"
            else -> guide.recommendation
        }.let { if(identity.isBlank())it else "$identity\n$it" }
        chooseLut.visibility = if (video.acceptsRec709Lut) View.VISIBLE else View.GONE
        removeLut.visibility = if (lut != null) View.VISIBLE else View.GONE
        chooseLut.text = if (lut == null) "Find LUT" else "Replace LUT"
    }

    private fun showLutFinder() {
        val guide=original?.lutGuide ?: return
        val choices=ui.column().apply { setPadding(ui.dp(24),0,ui.dp(24),0) }
        ui.add(choices,ui.text(guide.recommendation,secondary=true))
        val finder=MaterialAlertDialogBuilder(this).setTitle("Find a colour LUT")
            .setView(choices)
            .setNegativeButton("Cancel",null).create()
        ui.row(choices,ui.action("Choose folder",primary=true) { finder.dismiss();openLutPicker(folder=true) },
            ui.action("Browse file") { finder.dismiss();openLutPicker(folder=false) })
        SonyScreenWindows.showMedia(finder,this)
    }

    private fun openLutPicker(folder:Boolean) {
        try {
            if(folder)lutFolderPicker.launch(null) else lutPicker.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            setState(State.READY,"Android’s file picker is unavailable.")
        }
    }

    private fun findLuts(tree:Uri) {
        val guide=original?.lutGuide ?: return
        pendingLutFolder=tree
        setState(State.LOADING_LUT,"Looking for .cube files…")
        worker.execute {
            val result=runCatching {
                DocumentFolder.listNamed(contentResolver,tree,200,{ cancelOnExit.isCanceled }) {
                    it.endsWith(".cube",ignoreCase=true)
                }.sortedWith(compareByDescending<DocumentFolder.NamedFile> { guide.score(it.name) }.thenBy { it.name })
            }
            deliver {
                pendingLutFolder=null
                val files=result.getOrNull().orEmpty()
                if(files.isEmpty()) {
                    setState(State.READY,when(result.exceptionOrNull()) {
                        is DocumentFolder.TooLarge -> "Choose a smaller LUT folder or browse for a file."
                        null -> "No .cube files in the selected folder."
                        else -> "Couldn’t read the selected LUT folder."
                    })
                } else {
                    setState(State.READY,"")
                    val names=files.map {
                        val score=guide.score(it.name)
                        when {
                            score>=13 -> "Suggested · ${it.name}"
                            score>=8 -> "Profile match · ${it.name}"
                            score>0 -> "Camera match · ${it.name}"
                            else -> it.name
                        }
                    }
                    val picker=MaterialAlertDialogBuilder(this).setTitle("Choose a .cube")
                        .setItems(names.toTypedArray()) { _, index -> loadLut(files[index].uri) }
                        .setNeutralButton("Browse file") { _, _ -> openLutPicker(folder=false) }
                        .setNegativeButton("Cancel",null).create()
                    SonyScreenWindows.showMedia(picker,this)
                }
            }
        }
    }

    private fun loadLut(uri: Uri) {
        pendingLut = uri
        setState(State.LOADING_LUT, "Reading the color LUT…")
        player?.pause()
        worker.execute {
            val loaded = runCatching {
                val cube=checkNotNull(contentResolver.openInputStream(uri)).use(CubeLut::read)
                val name=runCatching { contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)
                    ?.use { if(it.moveToFirst())it.getString(0) else null } }.getOrNull()
                cube to name
            }
            deliver {
                pendingLut = null
                if (loaded.isSuccess) {
                    val (cube,name)=loaded.getOrThrow()
                    lut = cube
                    lutUri = uri
                    lutName = name
                    if (previewEdit?.lut != null) showPreview(null)
                }
                updateLut()
                setState(State.READY, if (loaded.isSuccess) "" else "Choose a valid 3D .cube LUT.")
            }
        }
    }

    private fun settings(): Media3VideoEdit? {
        val video = original ?: return null
        return try {
            val (start, end) = trimSlider.values.map { it.roundToLong().coerceAtMost(video.durationMs) }
            val trim = TrimRange(start, end)
            val frame = crop.frame(video.width, video.height)
            setState(State.READY, "")
            Media3VideoEdit(trim, frame, lut, format)
        } catch (e: IllegalArgumentException) {
            setState(State.READY, e.message ?: "Check the trim and crop values.")
            null
        }
    }

    private fun showPreview(edit: Media3VideoEdit?, seekMs: Long = 0L) {
        val video = original ?: return
        previewEdit = edit
        if (!started) return
        releasePreview()
        val current = ExoPlayer.Builder(this).build()
        player = current
        playerView.player = current
        previewInfo.text = if (edit == null) "Verified original" else "Preview of selected edits"
        current.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (edit == null && videoSize.height > 0) {
                    cropOverlay.setVideoAspect(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Editor preview failed", error)
                previewInfo.setText(R.string.video_editor_preview_unavailable)
            }
        })
        val selection = edit ?: Media3VideoEdit(TrimRange(0, video.durationMs), null, null)
        current.setVideoEffects(selection.effects())
        current.setMediaItem(selection.item(video), seekMs.coerceIn(0L, video.durationMs - 1L))
        current.prepare()
    }

    private fun originalPlayhead(video: VerifiedVideo): Long =
        ((player?.currentPosition ?: 0L) + (previewEdit?.trim?.startMs ?: 0L)).coerceIn(0, video.durationMs - 1)

    private fun saveFrame() {
        val video = original ?: return
        if (state != State.READY) return
        val position = originalPlayhead(video)
        setState(State.SAVING, "Saving a frame from the verified original…")
        worker.execute {
            val saved = runCatching { GalleryFrameExporter.saveLocal(this, video, position) { cancelOnExit.isCanceled } }
            deliver { setState(State.READY, saved.fold({ "Full-resolution JPEG saved to Library." },
                { "Couldn’t save a full-resolution frame at this position." })) }
        }
    }

    private fun releasePreview() {
        playerView.player = null
        player?.release()
        player = null
    }

    private fun prepareResume() {
        val store = checkpoints ?: return
        val directory = checkNotNull(scratch).directory
        val supportsMain10 = main10Available
        setState(State.SAVING, "Preparing the paused export…")
        worker.execute {
            val result = runCatching { store.prepare(directory, supportsMain10) { cancelOnExit.isCanceled } }
            deliver {
                result.fold(onSuccess = { saved ->
                    if (saved != null) startExport(saved.edit, saved)
                    else {
                        hasCheckpoint = false
                        setState(State.READY, "The paused export is unavailable. Use Export MP4 to start again.")
                    }
                }, onFailure = { error ->
                    Log.w(TAG, "Export checkpoint preparation failed", error)
                    setState(State.READY, "Couldn’t open the paused export. Check free space or export again.")
                })
            }
        }
    }

    private fun startExport(edit: Media3VideoEdit, saved: VideoExportCheckpoints.Resume? = null) {
        val source = original ?: return
        val output = File(checkNotNull(scratch).directory, "export.mp4")
        resumeFiles = saved
        pauseAction = if (saved == null && source.resumeCompatible) ({ pauseExport(edit, output) }) else null
        setCropping(false)
        releasePreview()
        setState(State.EXPORTING, if (saved == null) "Encoding the edited MP4…" else "Resuming the edited MP4…")
        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                transformer = null
                main.removeCallbacks(updateProgress)
                saveExport(edit, source, output, saved)
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                Log.w(TAG, "MP4 encoding failed", exportException)
                val message = when (exportException.errorCode) {
                    ExportException.ERROR_CODE_DECODER_INIT_FAILED,
                    ExportException.ERROR_CODE_DECODING_FAILED,
                    ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                        "This device couldn’t decode the original video."
                    ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
                    ExportException.ERROR_CODE_ENCODING_FAILED,
                    ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ->
                        if (edit.format == VideoOutput.MAIN10)
                            "Couldn’t encode this crop as 10-bit HEVC. Try a different crop or choose H.264."
                        else "Couldn’t encode the MP4. Try a different crop or a shorter range."
                    else -> "Couldn’t render the edited MP4. Try previewing the edits."
                }
                exportStopped(output, message)
            }
        }
        try {
            val builder = Transformer.Builder(this)
                .setVideoMimeType(edit.format.videoMime)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(listener)
            if (edit.format == VideoOutput.MAIN10) {
                val encoder = Main10Export.encoderFactory(this)
                builder.setEncoderFactory(if (saved == null) encoder else object : Codec.EncoderFactory by encoder {
                    // The saved prefix already contains the crop and high-precision LUT.
                    override fun videoNeedsEncoding() = false
                })
                builder.setVideoFrameProcessorFactory(DefaultVideoFrameProcessor.Factory.Builder()
                    .setGlObjectsProvider(Main10GlObjects())
                    .build())
            }
            transformer = builder.build()
            val composition = edit.composition(source)
            if (saved == null) checkNotNull(transformer).start(composition, output.path)
            else checkNotNull(transformer).resume(composition, output.path, saved.working.path)
            main.post(updateProgress)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start MP4 export", e)
            exportStopped(output, "Couldn’t start the MP4 export.")
        }
    }

    private fun pauseExport(edit: Media3VideoEdit, output: File) {
        val store = checkpoints ?: return
        try {
            transformer?.cancel()
        } catch (error: Exception) {
            Log.w(TAG, "Could not pause MP4 export", error)
            exportStopped(output, "Couldn’t pause this export. You can export again.")
            return
        }
        transformer = null
        pauseAction = null
        main.removeCallbacks(updateProgress)
        setState(State.SAVING, "Saving export progress…")
        worker.execute {
            val result = runCatching {
                check(VideoResumeVerification.canResume(output, edit.durationUs) { cancelOnExit.isCanceled })
                store.save(edit, output)
            }
            output.delete()
            deliver {
                if (result.isSuccess) hasCheckpoint = true
                else Log.w(TAG, "Export checkpoint could not be saved", result.exceptionOrNull())
                setState(State.READY, if (result.isSuccess) "Export paused."
                    else "Couldn’t save export progress. Let the export run longer before pausing.")
                showPreview(previewEdit)
            }
        }
    }

    private val updateProgress = object : Runnable {
        override fun run() {
            val current = transformer ?: return
            val value = ProgressHolder()
            if (current.getProgress(value) == Transformer.PROGRESS_STATE_AVAILABLE)
                progress.setProgressCompat(value.progress, false)
            main.postDelayed(this, 250)
        }
    }

    private fun saveExport(edit: Media3VideoEdit, video: VerifiedVideo, output: File, saved: VideoExportCheckpoints.Resume?) {
        setState(State.SAVING, "Verifying and saving the new MP4…")
        worker.execute {
            val active = { cancelOnExit.isCanceled }
            val result = runCatching {
                if (saved != null) {
                    try {
                        VideoResumeVerification.verify(saved.reference, output, edit.durationUs, video.file,
                            trimStartUs = edit.trim.startMs * 1000, cancelled = active)
                    } catch (error: Exception) {
                        throw IOException("Couldn’t verify the resumed MP4. Use Export MP4 to start again.", error)
                    }
                }
                val name = edit.publish(applicationContext, video, output, active)
                if (saved != null) checkpoints?.remove(saved.stored)
                name
            }
            output.delete()
            saved?.reference?.delete()
            saved?.working?.delete()
            val checkpointAvailable = checkpoints?.exists() == true
            deliver {
                resumeFiles = null
                pauseAction = null
                hasCheckpoint = checkpointAvailable
                result.fold(onSuccess = { name ->
                    setState(State.READY, "Saved $name in Library.")
                }, onFailure = { error ->
                    Log.w(TAG, "MP4 publication failed", error)
                    setState(State.READY, (error as? IOException)?.message ?: "Couldn’t save the MP4.")
                })
                showPreview(previewEdit)
            }
        }
    }

    private fun cancelExport() {
        transformer?.cancel()
        exportStopped(File(checkNotNull(scratch).directory, "export.mp4"), "Export canceled.")
    }

    private fun exportStopped(output: File, message: String) {
        transformer = null
        pauseAction = null
        main.removeCallbacks(updateProgress)
        resumeFiles?.let {
            it.reference.delete()
            it.working.delete()
        }
        resumeFiles = null
        output.delete()
        setState(State.READY, message)
        showPreview(previewEdit)
    }

    private fun leave() {
        if (state == State.SAVING) return
        if (cropping) {
            setCropping(false)
            return
        }
        if (state != State.EXPORTING) {
            finish()
            return
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Cancel export?")
            .setNegativeButton("Keep exporting", null)
            .setPositiveButton("Cancel export") { _, _ ->
                if (state == State.EXPORTING) cancelExport()
                finish()
            }.create()
        SonyScreenWindows.showMedia(dialog, this)
    }

    private fun deliver(action: () -> Unit) = main.post { if (!cancelOnExit.isCanceled) action() }

    private fun seconds(ms: Long) = BigDecimal.valueOf(ms, 3).stripTrailingZeros().toPlainString()

    companion object {
        private const val TAG = "CameraderieVideoEditor"
        private const val IMPORT_ID = "import-id"
        private const val SLIDER_VALUES = "slider-values"
        private const val CROP_BOX = "crop-box"
        private const val CROP_ACTIVE = "crop-active"
        private const val OUTPUT_FORMAT = "output-format"
        private const val LUT_URI = "lut-uri"
        private const val LUT_FOLDER_URI = "lut-folder-uri"
        private const val EXPORT_ACTIVE = "export-active"

        internal fun intent(context: Context, id: String) =
            Intent(context, VideoEditorActivity::class.java).putExtra(IMPORT_ID, id)
    }
}
