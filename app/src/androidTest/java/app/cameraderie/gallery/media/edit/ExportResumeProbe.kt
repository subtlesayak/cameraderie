package app.cameraderie.gallery.media.edit

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Effect
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.transformer.Composition
import androidx.media3.transformer.Codec
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import app.cameraderie.NativeChecks
import app.cameraderie.gallery.media.GalleryDerivedMedia
import app.cameraderie.gallery.media.MediaImportTransaction
import app.cameraderie.gallery.media.history.ImportHistoryDraft
import app.cameraderie.gallery.media.history.ImportHistoryRepository
import app.cameraderie.gallery.media.history.ImportProvenance
import app.cameraderie.sony.SonyOriginalVerification
import com.google.android.material.slider.RangeSlider
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in device probe. The encoder reads and writes only private cache files. */
@OptIn(UnstableApi::class)
class ExportResumeProbe {
    private enum class EditorCheckpointPhase { RECREATE, PAUSE, RESUME }

    private val instrument = NativeChecks.instance
    private val context = instrument.targetContext
    private val main = Handler(Looper.getMainLooper())

    @Test fun resumeLongMp4FromAnExistingVideoPrefix() {
        val requestedPhase = checkNotNull(instrument.exportResumeProbe)
        when (requestedPhase) {
            "avc-publish-trimmed" -> return verifyPublication(VideoOutput.AVC)
            "main10-publish-trimmed" -> return verifyPublication(VideoOutput.MAIN10)
            "avc-production-editor" -> return verifyEditorControls(VideoOutput.AVC)
            "avc-cold-pause" -> return verifyEditorControls(VideoOutput.AVC, EditorCheckpointPhase.PAUSE)
            "avc-cold-resume" -> return verifyEditorControls(VideoOutput.AVC, EditorCheckpointPhase.RESUME)
            "main10-production-editor" -> return verifyEditorControls(VideoOutput.MAIN10)
            "main10-action6-editor" -> return verifyEditorControls(VideoOutput.MAIN10, action6Source = true)
            "main10-cold-pause" -> return verifyEditorControls(VideoOutput.MAIN10, EditorCheckpointPhase.PAUSE)
            "main10-cold-resume" -> return verifyEditorControls(VideoOutput.MAIN10, EditorCheckpointPhase.RESUME)
        }
        val keepAac = requestedPhase.endsWith("-keep-aac")
        val trimmed = requestedPhase.endsWith("-trimmed")
        val phase = requestedPhase.removeSuffix("-keep-aac").removeSuffix("-trimmed")
        val format = when (phase) {
            "true", "avc-checkpoint", "avc-resume", "avc-editor" -> VideoOutput.AVC
            "main10-checkpoint", "main10-resume", "main10-editor" -> VideoOutput.MAIN10
            else -> error("Unknown export resume probe phase.")
        }
        val editorPhase = phase == "avc-editor" || phase == "main10-editor"
        require(!(keepAac || trimmed) || editorPhase) { "This variant requires editor recreation." }
        val folder = if (format == VideoOutput.MAIN10) "export-resume-main10-probe" else "export-resume-probe"
        val directory = File(context.cacheDir, folder + when {
            trimmed -> "-editor-trimmed"
            keepAac -> "-editor-keep-aac"
            editorPhase -> "-editor"
            else -> ""
        })
        val source = File(directory, "source.mp4")
        check(source.isFile) { "Stage the synthetic 90-second source in the app's private probe directory." }
        val old = File(directory, "checkpoint.mp4")
        val working = File(directory, "resume-working.mp4")
        val resumed = File(directory, "resumed.mp4")
        val reference = File(directory, "reference.mp4")
        val durationSeconds = if (trimmed) 60 else 90
        if (trimmed) check(audioSampleRate(source) == 44_100) { "Trimmed probe requires the 44.1 kHz audio fixture." }
        fun composition(): Composition {
            val effects = buildList<Effect> {
                add(Presentation.createForHeight(540))
                if (format == VideoOutput.MAIN10) {
                    val lut = File(directory, "grade.cube").inputStream().use(CubeLut::read)
                    add(Main10LutEffect(lut))
                }
            }
            val item = MediaItem.Builder().setUri(Uri.fromFile(source))
            if (trimmed) item.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(7300).setEndPositionMs(67300).build())
            val audio = if (trimmed) listOf(SonicAudioProcessor().apply { setOutputSampleRateHz(48_000) })
                else emptyList()
            val edited = EditedMediaItem.Builder(item.build()).setEffects(Effects(audio, effects)).build()
            return Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(listOf(edited)))
                .setTransmuxAudio(keepAac).build()
        }

        val resumePhase = phase == "main10-resume" || phase == "avc-resume"
        val editor = if (editorPhase) EditorSession() else null
        try {
            if (!resumePhase) {
                runExport(composition(), old, format, pauseAtPercent = 40, host = editor?.open(source) ?: context)
            }
            val resumeHost = editor?.recreate() ?: context
            verifyResume(::composition, format, phase, old, working, resumed, reference, resumeHost, durationSeconds)
        } finally {
            editor?.close()
        }
    }

    private fun verifyEditorControls(
        format: VideoOutput,
        phase: EditorCheckpointPhase = EditorCheckpointPhase.RECREATE,
        action6Source: Boolean = false,
    ) {
        require(context.packageName == "app.cameraderie.resumeprobe" && instrument.exportResumeImportId == null)
        val directory = File(if (action6Source) context.noBackupFilesDir else context.cacheDir, when {
            action6Source -> "export-resume-action6-controls"
            format == VideoOutput.MAIN10 -> "export-resume-main10-editor-controls"
            else -> "export-resume-editor-controls"
        })
        val trimStartMs = if (action6Source) 2300 else 7300
        val trimEndMs = if (action6Source) 30300 else 67300
        val source = File(directory, "source.mp4")
        val pausedFixture = File(directory, "paused-fixture")
        val repository = ImportHistoryRepository.get(context)
        EditorSession().use { session ->
            val savedImport = if (phase == EditorCheckpointPhase.RESUME) {
                val (id, pid) = pausedFixture.readLines()
                check(pid.toInt() != android.os.Process.myPid()) { "The paused app process is still running." }
                id
            } else null
            var editor = session.open(source, savedImport, discardStagedSource = action6Source)
            if (format == VideoOutput.MAIN10 && savedImport == null) {
                val grade = File(directory, "grade.cube")
                check(grade.isFile)
                editor = session.recreate { state ->
                    state.putString("output-format", format.name)
                    state.putString("lut-uri", Uri.fromFile(grade).toString())
                }
            }
            val id = session.importId
            val original = checkNotNull(repository.store.find(id))
            if (savedImport == null) {
                pauseEditor(editor.window.decorView, format, trimStartMs, trimEndMs)
                if (phase == EditorCheckpointPhase.PAUSE) {
                    pausedFixture.writeText("$id\n${android.os.Process.myPid()}\n")
                    session.retainFixture = true
                    return
                }
                editor = session.recreate()
            } else {
                check(pausedFixture.delete())
                await("The fresh editor starts with its default H.264 format") {
                    editor.window.decorView.button(VideoOutput.AVC.label) != null
                }
            }
            val recreated = editor.window.decorView
            await("The editor offers export resume") { recreated.button("Resume export") != null }
            instrument.runOnMainSync { checkNotNull(recreated.button("Resume export")).performClick() }
            val name = original.receipt?.displayName ?: original.draft.displayName
            val previewError = context.getText(app.cameraderie.R.string.video_editor_preview_unavailable)
            val deadline = SystemClock.uptimeMillis() + 120_000
            var published = repository.list().singleOrNull { it.draft.videoSource == name }
            while (published == null && SystemClock.uptimeMillis() < deadline) {
                var failure: String? = null
                instrument.runOnMainSync {
                    failure = (recreated.findViewMatching {
                        it is TextView && it.isShown && it.text != previewError &&
                            it.text.contains("couldn’t", ignoreCase = true)
                    } as? TextView)?.text?.toString()
                }
                check(failure == null) { checkNotNull(failure) }
                SystemClock.sleep(100)
                published = repository.list().singleOrNull { it.draft.videoSource == name }
            }
            val export = checkNotNull(published) { "Editor resume did not publish an export." }
            check(export.draft.uri != original.draft.uri && export.draft.trimStartUs == trimStartMs * 1000L &&
                export.draft.trimEndUs == trimEndMs * 1000L)
            val receipt = checkNotNull(export.receipt)
            val sourceReceipt = checkNotNull(original.receipt)
            check(receipt.width != sourceReceipt.width && receipt.height != sourceReceipt.height)
            check(repository.store.find(id) == original)
            checkNotNull(context.contentResolver.openInputStream(Uri.parse(original.draft.uri))).use { input ->
                val originalReceipt = checkNotNull(original.receipt)
                SonyOriginalVerification.verify(input, originalReceipt.bytes, false,
                    sourceSha256 = checkNotNull(originalReceipt.sha256))
            }
            val readbackFile = File(directory, "editor-readback.mp4")
            checkNotNull(context.contentResolver.openInputStream(Uri.parse(export.draft.uri))).use { input ->
                readbackFile.outputStream().use { readback ->
                    SonyOriginalVerification.verify(input, receipt.bytes, false,
                        sourceSha256 = checkNotNull(receipt.sha256), verifiedCopy = readback)
                }
            }
            if (format == VideoOutput.MAIN10) Main10Export.verify(readbackFile)
            Log.i("ExportResumeProbe", "Actual ${format.name} editor paused, recreated, resumed and saved ${receipt.bytes} bytes")
        }
    }

    private fun pauseEditor(root: View, format: VideoOutput, trimStartMs: Int, trimEndMs: Int) {
        instrument.runOnMainSync {
            checkNotNull(root.button(format.label))
            if (format == VideoOutput.MAIN10) checkNotNull(root.button("Remove LUT"))
            val trim = checkNotNull(root.findViewMatching { it is RangeSlider }) as RangeSlider
            trim.setValues(trimStartMs.toFloat(), trimEndMs.toFloat())
            val crop = checkNotNull(root.findViewMatching { it is CropOverlay }) as CropOverlay
            repeat(10) { crop.nudge(CropHandle.TOP_LEFT, 0.02f, 0.02f) }
            checkNotNull(root.button("Export MP4")).performClick()
            checkNotNull(root.button("Pause export")) { "The editor did not offer Pause for the SDR fixture." }
        }
        val scratch = context.noBackupFilesDir.listFiles().orEmpty().single { it.name.startsWith("video-edit-") }
        val output = File(scratch, "export.mp4")
        await("Initial encoding produced 4 MB", 25_000) {
            val pause = root.button("Pause export")
            if (output.length() < 4_000_000 || pause == null) false else pause.performClick()
        }
        await("Paused export was saved") { root.button("Resume export") != null }
    }

    private fun await(label: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            var ready = false
            instrument.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        error(label)
    }

    private fun View.findViewMatching(matches: (View) -> Boolean): View? {
        if (matches(this)) return this
        if (this is ViewGroup) {
            for (index in 0 until childCount) getChildAt(index).findViewMatching(matches)?.let { return it }
        }
        return null
    }

    private fun View.button(label: String) = findViewMatching {
        it is Button && it.text.toString() == label && it.isShown && it.isEnabled
    } as? Button

    private fun verifyResume(
        composition: () -> Composition,
        format: VideoOutput,
        phase: String?,
        old: File,
        working: File,
        resumed: File,
        reference: File,
        host: Context,
        durationSeconds: Int
    ) {
        val prefix = readTrack(old, "video/")
        val latestSpliceUs = minOf(70, durationSeconds - 5) * 1_000_000L
        check(prefix.lastSync in 10_000_000..latestSpliceUs) { "Checkpoint has no reusable video prefix: ${prefix.lastSync}" }
        check(readTrack(old, "audio/").last >= prefix.lastSync) { "Checkpoint audio ended before its video prefix." }
        if (format == VideoOutput.MAIN10) Main10Export.verify(old)
        if (phase == "main10-checkpoint" || phase == "avc-checkpoint") {
            Log.i("ExportResumeProbe", "Private ${format.name} checkpoint created at syncUs=${prefix.lastSync}")
            return
        }

        // Media3 reuses oldFilePath as an intermediate in the audio stage. Protect the original checkpoint.
        old.copyTo(working)
        val pausedBytes = old.length()
        val result = checkNotNull(runExport(composition(), resumed, format, resumeFrom = working, host = host))
        check(resumed.length() == working.length() && resumed.length() > pausedBytes) {
            "Resume did not complete its audio/mux stage."
        }
        runExport(composition(), reference, format, host = host)
        if (format == VideoOutput.MAIN10) {
            Main10Export.verify(resumed)
            Main10Export.verify(reference)
        }
        verifyTimeline(resumed, reference, durationSeconds)
        Log.i("ExportResumeProbe", "checkpointBytes=$pausedBytes lastSyncUs=${prefix.lastSync} " +
            "resumedBytes=${resumed.length()} optimizationResult=${result.optimizationResult}")
    }

    private fun verifyPublication(format: VideoOutput) {
        require(context.packageName == "app.cameraderie.resumeprobe")
        val directory = File(context.cacheDir, "export-resume-publication")
        val source = File(directory, "source.mp4")
        val resumed = File(directory, "resumed.mp4")
        val expected = File(directory, "resumed.sha256").readText().trim()
        require(expected.matches(Regex("[0-9a-f]{64}")))
        resumed.inputStream().use {
            SonyOriginalVerification.verify(it, resumed.length(), false, sourceSha256 = expected)
        }

        val checkpoint = File(directory, "checkpoint.mp4")
        val reused = VideoResumeVerification.verify(checkpoint, resumed, 60_000_000, source) { false }
        val truncated = runCatching { VideoResumeVerification.verify(checkpoint, checkpoint, 60_000_000) { false } }
        check(truncated.isFailure) { "The unfinished checkpoint passed as a complete output." }
        val altered = File(directory, "altered-prefix.mp4")
        val changedPrefix = runCatching { VideoResumeVerification.verify(checkpoint, altered, 60_000_000) { false } }
        check(changedPrefix.exceptionOrNull()?.message?.startsWith(
            "The resumed export did not preserve its checkpoint video.") == true) {
            "An altered prefix did not fail the checkpoint comparison."
        }
        val gapped = File(directory, "gapped-suffix.mp4")
        val missingFrames = runCatching {
            VideoResumeVerification.verify(checkpoint, gapped, 60_000_000, source) { false }
        }
        check(missingFrames.exceptionOrNull()?.message?.startsWith("The resumed video has a gap.") == true) {
            "A gap after the splice did not fail the video continuity check."
        }
        Log.i("ExportResumeProbe", "Validated ${reused.reusedSamples} reused samples before ${reused.spliceUs} us")

        val repository = ImportHistoryRepository.get(context)
        val token = UUID.randomUUID().toString()
        var originalUri: Uri? = null
        val verifiedCopy = File(directory, "verified-$token.mp4")
        try {
            val id = publishIsolatedFixture(source, token) { originalUri = it }
            val video = VerifiedVideo.load(id, repository, context.contentResolver, verifiedCopy, CancellationSignal())
            val originalReceipt = checkNotNull(video.entry.receipt)
            val outputName = Media3VideoEdit(TrimRange(7300, 67300), null, null, format)
                .publish(context, video, resumed) { false }
            val output = repository.list().single { it.draft.displayName == outputName }
            val outputReceipt = checkNotNull(output.receipt)
            check(output.draft.uri != video.entry.draft.uri && output.draft.videoSource == video.name)
            check(output.draft.provenance == ImportProvenance.EDITED_FROM_ORIGINAL)
            check(output.draft.trimStartUs == 7_300_000L && output.draft.trimEndUs == 67_300_000L)
            check(outputReceipt.mime == "video/mp4" && outputReceipt.bytes == resumed.length())
            check(outputReceipt.width == 960 && outputReceipt.height == 540 && outputReceipt.sha256 == expected)
            check(repository.local.snapshot(output.draft.uri)?.published == true)
            checkNotNull(context.contentResolver.openInputStream(Uri.parse(output.draft.uri))).use { input ->
                File(directory, "published-readback.mp4").outputStream().use { readback ->
                    SonyOriginalVerification.verify(input, outputReceipt.bytes, false,
                        sourceSha256 = expected, verifiedCopy = readback)
                }
            }
            check(repository.store.find(id) == video.entry)
            checkNotNull(context.contentResolver.openInputStream(checkNotNull(originalUri))).use {
                SonyOriginalVerification.verify(it, originalReceipt.bytes, false,
                    sourceSha256 = checkNotNull(originalReceipt.sha256))
            }
            Log.i("ExportResumeProbe", "Publication verified: bytes=${outputReceipt.bytes} sha256=$expected")
        } finally {
            val syntheticSource = "Cameraderie-synthetic-resume-$token.mp4"
            repository.list().filter { it.draft.videoSource == syntheticSource }.forEach {
                context.contentResolver.delete(Uri.parse(it.draft.uri), null, null)
            }
            originalUri?.let { context.contentResolver.delete(it, null, null) }
            verifiedCopy.delete()
        }
    }

    private fun publishIsolatedFixture(source: File, token: String, onUri: (Uri) -> Unit): String {
        check(context.packageName == "app.cameraderie.resumeprobe") {
            "Supply a synthetic import ID, or use the isolated resumeprobe application ID."
        }
        val name = "Cameraderie-synthetic-resume-$token.mp4"
        val item = MediaImportTransaction.Item(token, name, source.length())
        val result = GalleryDerivedMedia.publish(context, source, item, { false }, { uri ->
            onUri(Uri.parse(uri))
            ImportHistoryDraft(token, uri, name, source.length(), System.currentTimeMillis(), ImportProvenance.DOCUMENT_BYTES)
        }, {})
        check(result.completed == listOf(token))
        return token
    }

    /** Host the private probe on consecutive instances of the actual editor. */
    private inner class EditorSession : AutoCloseable {
        private val application = context.applicationContext as Application
        private val current = AtomicReference<VideoEditorActivity>()
        private val token = UUID.randomUUID().toString()
        private val tokenKey = "app.cameraderie.test.exportResumeEditor"
        private var fixtureUri: Uri? = null
        var retainFixture = false
        private var seedNextState: ((Bundle) -> Unit)? = null
        lateinit var importId: String
            private set
        private val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity is VideoEditorActivity && activity.intent.getStringExtra(tokenKey) == token)
                    current.set(activity)
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {
                if (activity === current.get()) seedNextState?.invoke(state)
            }
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        fun open(
            source: File,
            savedImport: String? = null,
            discardStagedSource: Boolean = false,
        ): VideoEditorActivity {
            val id = savedImport ?: instrument.exportResumeImportId ?: publishIsolatedFixture(source, token) { fixtureUri = it }
            importId = id
            val entry = checkNotNull(ImportHistoryRepository.get(context).store.find(id))
            check(VerifiedVideo.supports(entry))
            if (savedImport != null) {
                check(entry.draft.displayName == "Cameraderie-synthetic-resume-$id.mp4")
                fixtureUri = Uri.parse(entry.draft.uri)
            }
            val receipt = checkNotNull(entry.receipt)
            source.inputStream().use { SonyOriginalVerification.verify(it, receipt.bytes, false, { false }, receipt.sha256) }
            if (discardStagedSource) {
                check(fixtureUri != null && savedImport == null)
                check(source.delete()) { "Couldn’t release the isolated staging copy." }
            }
            val editorIntent = VideoEditorActivity.intent(context, id)
                .putExtra(tokenKey, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            application.registerActivityLifecycleCallbacks(callbacks)
            current.set(instrument.startActivitySync(editorIntent) as VideoEditorActivity)
            return awaitEditor()
        }

        fun recreate(seedState: ((Bundle) -> Unit)? = null): VideoEditorActivity {
            val previous = checkNotNull(current.get())
            instrument.runOnMainSync {
                seedNextState = seedState
                previous.recreate()
            }
            val editor = awaitEditor(previous)
            instrument.runOnMainSync { seedNextState = null }
            Log.i("ExportResumeProbe", "Editor recreated before private resume")
            return editor
        }

        private fun awaitEditor(previous: VideoEditorActivity? = null): VideoEditorActivity {
            val deadline = SystemClock.uptimeMillis() + 20_000
            do {
                var ready: VideoEditorActivity? = null
                instrument.runOnMainSync {
                    val editor = current.get()
                    if (editor != null && editor !== previous && (previous == null || previous.isDestroyed) &&
                        editor.hasWindowFocus() && hasExportButton(editor.window.decorView)) ready = editor
                }
                ready?.let { return it }
                SystemClock.sleep(50)
            } while (SystemClock.uptimeMillis() < deadline)
            error("The editor did not open the verified synthetic import.")
        }

        private fun hasExportButton(view: View): Boolean =
            (view is Button && view.text.toString() == "Export MP4" && view.isShown && view.isEnabled) ||
                (view is ViewGroup && (0 until view.childCount).any { hasExportButton(view.getChildAt(it)) })

        override fun close() {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            instrument.runOnMainSync { current.get()?.finish() }
            if (retainFixture) return
            if (fixtureUri != null) {
                val fixtureId = if (this::importId.isInitialized) importId else token
                val repository = ImportHistoryRepository.get(context)
                repository.list().filter { it.draft.videoSource == "Cameraderie-synthetic-resume-$fixtureId.mp4" }.forEach {
                    context.contentResolver.delete(Uri.parse(it.draft.uri), null, null)
                }
            }
            fixtureUri?.let { context.contentResolver.delete(it, null, null) }
        }
    }

    private fun runExport(
        composition: Composition,
        output: File,
        format: VideoOutput,
        pauseAtPercent: Int? = null,
        resumeFrom: File? = null,
        host: Context = context
    ): ExportResult? {
        check(!output.exists()) { "Use a new private probe directory for each run." }
        val end = CountDownLatch(1)
        var result: ExportResult? = null
        var failure: Exception? = null
        var paused = false
        lateinit var transformer: Transformer
        val poll = object : Runnable {
            override fun run() {
                val progress = ProgressHolder()
                if (transformer.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE &&
                    progress.progress >= checkNotNull(pauseAtPercent)) {
                    try {
                        transformer.cancel()
                        paused = true
                    } catch (error: Exception) {
                        failure = error
                    }
                    end.countDown()
                } else if (end.count > 0) {
                    main.postDelayed(this, 50)
                }
            }
        }
        instrument.runOnMainSync {
            try {
                val builder = Transformer.Builder(host)
                    .setVideoMimeType(format.videoMime)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                if (format == VideoOutput.MAIN10) {
                    // The original composition has resize and LUT effects to trigger encoding.
                    // Let Media3 copy the already graded prefix during a resume.
                    val encoder = Main10Export.encoderFactory(host)
                    builder.setEncoderFactory(if (resumeFrom == null) encoder else object : Codec.EncoderFactory by encoder {
                        override fun videoNeedsEncoding() = false
                    })
                        .setVideoFrameProcessorFactory(DefaultVideoFrameProcessor.Factory.Builder()
                            .setGlObjectsProvider(Main10GlObjects()).build())
                } else {
                    val videoSettings = VideoEncoderSettings.Builder().setiFrameIntervalSeconds(1f).build()
                    builder.setEncoderFactory(DefaultEncoderFactory.Builder(host)
                        .setRequestedVideoEncoderSettings(videoSettings).build())
                }
                transformer = builder.addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            main.removeCallbacks(poll)
                            result = exportResult
                            end.countDown()
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, error: ExportException) {
                            main.removeCallbacks(poll)
                            failure = error
                            end.countDown()
                        }
                    }).build()
                if (resumeFrom != null) transformer.resume(composition, output.path, resumeFrom.path)
                else transformer.start(composition, output.path)
                if (pauseAtPercent != null) main.post(poll)
            } catch (error: Exception) {
                failure = error
                end.countDown()
            }
        }
        if (!end.await(120, TimeUnit.SECONDS)) {
            instrument.runOnMainSync {
                main.removeCallbacks(poll)
                transformer.cancel()
            }
            error("Transformer did not reach a terminal state.")
        }
        failure?.let { throw it }
        check(if (pauseAtPercent != null) paused else result != null)
        return result
    }

    private data class Track(val times: List<Long>, val lastSync: Long) {
        val last: Long get() = times.last()
    }

    private fun readTrack(file: File, prefix: String): Track {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).single {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
            }
            extractor.selectTrack(track)
            val timestamps = mutableListOf<Long>()
            var lastSync = -1L
            while (extractor.sampleTrackIndex >= 0) {
                val time = extractor.sampleTime
                timestamps.add(time)
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) lastSync = maxOf(lastSync, time)
                if (!extractor.advance()) break
            }
            return Track(timestamps.sorted(), lastSync)
        } finally {
            extractor.release()
        }
    }

    private fun audioSampleRate(file: File): Int {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            return (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .single { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                .getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } finally {
            extractor.release()
        }
    }

    private fun verifyTimeline(resumed: File, reference: File, durationSeconds: Int) {
        check(audioSampleRate(resumed) == 48_000 && audioSampleRate(reference) == 48_000) {
            "Unexpected output audio sample rate."
        }
        val video = readTrack(resumed, "video/").times
        val referenceVideo = readTrack(reference, "video/").times
        check(referenceVideo.size == durationSeconds * 30 && video == referenceVideo) {
            "Video frame timing changed after resume: ${video.size}/${referenceVideo.size}."
        }

        val audio = readTrack(resumed, "audio/").times
        val referenceAudio = readTrack(reference, "audio/").times
        // A 48 kHz AAC packet covers 1024 samples. Resume can encode new priming and end padding.
        val aacPacketUs = 21_334L
        check(referenceAudio.last() > durationSeconds * 1_000_000L - 100_000 && audio.zipWithNext().all { (first, next) ->
            next - first in 21_332L..aacPacketUs
        }) { "The AAC track has a gap." }
        check(kotlin.math.abs(audio.first() - referenceAudio.first()) <= aacPacketUs &&
            kotlin.math.abs(audio.last() - referenceAudio.last()) <= aacPacketUs) {
            "The resumed audio differs from the reference by more than one AAC packet."
        }
    }
}
