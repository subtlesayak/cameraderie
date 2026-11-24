package app.cameraderie.gallery.media

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import app.cameraderie.ui.CameraderieActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import app.cameraderie.R
import app.cameraderie.gallery.media.history.LocalMediaIdentity
import app.cameraderie.ui.UiKit
import com.google.android.material.appbar.MaterialToolbar
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DocumentImportActivity : CameraderieActivity() {
    private val model by lazy { ViewModelProvider(this)[DocumentImportModel::class.java] }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files ->
        if (files.isNotEmpty()) model.select(files)
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folder ->
        if (folder != null) model.selectFolder(folder)
    }

    private val destinationPicker = registerForActivityResult(LocalFolderPicker()) { folder ->
        if (folder != null) model.start(folder)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = UiKit(this)
        val root = ui.column().apply { setBackgroundColor(ui.tone.background) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        root.addView(MaterialToolbar(this).apply {
            title = "Import files"
            setTitleTextColor(ui.tone.text)
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationContentDescription = "Back to Imported Images"
            setNavigationOnClickListener { finish() }
        })
        val (page, body) = ui.page()
        root.addView(page)
        val card = ui.card(body, "Photos and videos")
        ui.add(card, ui.text("Choose files from Android storage or an attached SD reader. Verified copies appear in Imported Images.", secondary = true))
        val choose = ui.action("Choose files") {
            try {
                // Some document providers report manufacturer RAW as application/octet-stream.
                picker.launch(arrayOf("*/*"))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "Android’s file picker is unavailable.", Toast.LENGTH_LONG).show()
            }
        }
        val chooseFolder = ui.action("Choose source folder") {
            try {
                folderPicker.launch(null)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "Android’s folder picker is unavailable.", Toast.LENGTH_LONG).show()
            }
        }
        ui.row(card, choose, chooseFolder)
        val status = ui.text("", secondary = true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        ui.add(card, status, 12)
        val start = ui.action("Import to phone album", primary = true) { model.start() }
        val toFolder = ui.action("Import to folder") {
            try { destinationPicker.launch(null) }
            catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "Android’s folder picker is unavailable.", Toast.LENGTH_LONG).show()
            }
        }
        ui.row(card, start, toFolder)
        val cancel = ui.action("Cancel import") { model.cancel(); status.text = "Stopping…" }
        ui.add(card, cancel, 12)
        model.state.observe(this) { current ->
            status.text = current.message
            status.visibility = if (current.message.isBlank()) View.GONE else View.VISIBLE
            choose.isEnabled = !current.busy
            chooseFolder.isEnabled = !current.busy
            start.isEnabled = current.files.isNotEmpty() && !current.busy
            toFolder.isEnabled = start.isEnabled
            cancel.text = if (current.scanning) "Cancel scan" else "Cancel import"
            cancel.visibility = if (current.busy) View.VISIBLE else View.GONE
        }
        setContentView(root)
    }
}

internal class DocumentImportModel(application: Application) : AndroidViewModel(application) {
    data class State(val files: List<Uri> = emptyList(), val busy: Boolean = false, val message: String = "", val scanning: Boolean = false)

    val state = MutableLiveData(State())
    private val cancelled = AtomicBoolean(false)

    fun select(files: List<Uri>) {
        if (state.value?.busy == true) return
        val unique = files.distinct()
        state.value = when {
            unique.isEmpty() -> State(message = "This folder has no supported photos or videos.")
            unique.size > DocumentAlbumImport.MAX_SELECTION -> State(message = "Choose up to ${DocumentAlbumImport.MAX_SELECTION} files at a time.")
            else -> selection(unique)
        }
    }

    fun selectFolder(folder: Uri) {
        if (state.value?.busy == true) return
        cancelled.set(false)
        state.value = State(busy = true, message = "Looking for photos and videos in the folder…", scanning = true)
        worker.execute {
            val result = try {
                val app = getApplication<Application>()
                val files = DocumentFolder.list(app.contentResolver, folder, cancelled::get)
                if (cancelled.get()) State(message = "Folder scan canceled.")
                else if (files.isEmpty()) State(message = "This folder has no supported photos or videos.")
                else selection(files)
            } catch (_: DocumentFolder.TooLarge) {
                State(message = "This folder is too large to import together. Choose a smaller folder or select individual files.")
            } catch (_: Exception) {
                State(message = if (cancelled.get()) "Folder scan canceled." else "Could not read the folder. Choose it again or select individual files.")
            }
            state.postValue(result)
        }
    }

    private fun selection(files: List<Uri>) = State(files, message = "Selected ${files.size} ${if (files.size == 1) "file" else "files"}.")

    fun start(destination: Uri? = null) {
        val current = checkNotNull(state.value)
        if (current.busy || current.files.isEmpty()) return
        val files = current.files
        cancelled.set(false)
        state.value = State(busy = true, message = "Preparing import…")
        worker.execute {
            val result = try {
                val app = getApplication<Application>()
                if (destination != null) {
                    LocalFolderPicker.persist(app.contentResolver,destination)
                }
                DocumentAlbumImport(app).run(files, cancelled::get, destination) { index ->
                    if (!cancelled.get()) state.postValue(State(busy = true, message = "Checking file $index of ${files.size}…"))
                }
            } catch (_: Exception) {
                state.postValue(State(message = if (cancelled.get()) "Import canceled." else "Could not start the import. Choose a writable device folder or check available phone storage."))
                return@execute
            }
            val message = buildList {
                if (result.cancelled) add("Import canceled.")
                add("Imported ${result.imported} ${if (result.imported == 1) "file" else "files"}.")
                if (result.duplicates > 0) add("${result.duplicates} already imported.")
                if (result.unsupported > 0) add("${result.unsupported} unsupported.")
                if (result.filenameConflicts > 0) add("${result.filenameConflicts} could not keep the original filename. Choose another folder.")
                if (result.failed > 0) add("${result.failed} failed.")
                if (result.needsReview) add("Check Imported Images before trying again.")
            }.joinToString(" ")
            state.postValue(State(message = message))
        }
    }

    fun cancel() { cancelled.set(true) }

    override fun onCleared() { cancel() }

    companion object {
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "Cameraderie-document-import").apply { isDaemon = true } }
    }
}

internal class LocalFolderPicker : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: android.content.Context, input: Uri?): Intent =
        super.createIntent(context,input).putExtra(Intent.EXTRA_LOCAL_ONLY,true)

    companion object {
        fun persist(resolver:android.content.ContentResolver,folder:Uri) {
            LocalMediaIdentity.requireFolderTree(folder.toString())
            resolver.takePersistableUriPermission(folder,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }
}
