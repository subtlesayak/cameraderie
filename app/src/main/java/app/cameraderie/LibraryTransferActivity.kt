package app.cameraderie

import android.app.Application
import android.content.Intent
import android.net.Uri
import app.cameraderie.io.readBounded
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import app.cameraderie.gallery.media.DocumentFolder
import app.cameraderie.gallery.media.GalleryDownloadQueue
import app.cameraderie.gallery.media.GalleryQueueJournal
import app.cameraderie.gallery.media.history.ImportHistoryEntry
import app.cameraderie.gallery.media.history.ImportHistoryRepository
import app.cameraderie.gallery.media.history.ImportHistoryStage
import app.cameraderie.gallery.media.history.ImportCompletionKind
import app.cameraderie.gallery.media.history.LocalMediaIdentity
import app.cameraderie.gallery.media.history.SqliteImportHistoryStore
import app.cameraderie.gallery.protocol.GalleryCameraEvidence.Store
import app.cameraderie.sony.SonyOriginalVerification
import app.cameraderie.ui.CameraderieActivity
import app.cameraderie.ui.UiKit
import org.json.JSONObject
import java.io.InterruptedIOException
import java.util.concurrent.Executors

class LibraryTransferActivity : CameraderieActivity() {
    private lateinit var model: LibraryTransferModel
    private var manifest: Uri? = null
    private val selectManifest = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val granted = runCatching { keepReadAccess(uri) }.isSuccess
            if (granted) manifest = uri
            model.report(if (granted) "Transfer selected. Choose a folder or restore the queue." else "Couldn’t retain read access to the transfer file.")
        }
    }
    private val selectFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folder ->
        val source = manifest
        if (folder != null && source != null) {
            val granted = runCatching { LocalMediaIdentity.requireFolderTree(folder.toString()); keepReadAccess(folder) }.isSuccess
            if (granted) model.link(source, folder)
            else model.report("Choose a folder on your phone, SD card, or USB storage and allow read access.")
        }
    }

    private fun keepReadAccess(uri: Uri) = contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manifest = savedInstanceState?.getString("manifest")?.let(Uri::parse)
        model = ViewModelProvider(this)[LibraryTransferModel::class.java]
        val ui = UiKit(this)
        val (scroll, body) = ui.page()
        scroll.setBackgroundColor(ui.tone.background)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        ui.add(body, ui.text("Relink OpenGrip library", UiKit.Type.title, bold = true).apply { isAccessibilityHeading = true }, 20)
        ui.add(body, ui.text("In OpenGrip, open Imported Images and choose Export transfer for Cameraderie. Select that file here, then choose each folder that holds your imports. Files are linked when their saved checksums match."), 12)
        val pick = ui.action("Choose transfer file", primary = true) { selectManifest.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
        val folder = ui.action("Choose media folder") { selectFolder.launch(null) }
        val queue = ui.action("Restore download queue") { manifest?.let(model::restoreQueue) }
        listOf(pick, folder, queue).forEach { ui.add(body, it, 12) }
        ui.add(body, ui.text("Restored downloads start from the beginning after you authorize and reconnect the same DJI camera."), 12)
        val status = ui.text("", secondary = true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        ui.add(body, status, 16)
        setContentView(scroll)
        model.state.observe(this) { state ->
            status.text = state.message
            pick.isEnabled = !state.busy
            folder.isEnabled = !state.busy && manifest != null
            queue.isEnabled = !state.busy && manifest != null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        manifest?.let { outState.putString("manifest", it.toString()) }
        super.onSaveInstanceState(outState)
    }
}

internal class LibraryTransferModel(application: Application) : AndroidViewModel(application) {
    data class State(val message: String, val busy: Boolean = false)
    val state = MutableLiveData(State("Choose the JSON file exported by OpenGrip."))
    private val app = application
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = { Thread.currentThread().isInterrupted }

    fun report(message: String) { state.value = State(message) }

    fun link(manifest: Uri, tree: Uri) = run("Verifying files in the selected folder…") {
        val data = read(manifest)
        val receipts = data.getJSONArray("receipts")
        check(receipts.length() <= MAX_RECEIPTS)
        val history = ImportHistoryRepository.get(app)
        val store = history.store as SqliteImportHistoryStore
        val records = List(receipts.length()) { index -> receipts.getJSONObject(index) }
        val names = records.map { name(it) }.toSet()
        val byName = DocumentFolder.listNamed(app.contentResolver, tree, MAX_RECEIPTS, cancelled, names::contains).groupBy { it.name }
        var linked = 0
        for (record in records) {
            if (cancelled()) throw InterruptedIOException()
            val receipt = store.decodeReceipt(record.getJSONObject("receipt")).copy(verifiedFolderFile = null)
            val hash = checkNotNull(receipt.sha256)
            val name = name(record)
            for (file in byName[name].orEmpty()) {
                val matches = runCatching {
                    checkNotNull(app.contentResolver.openInputStream(file.uri)).use {
                        SonyOriginalVerification.verify(it, receipt.bytes, false, cancelled, hash)
                    }
                }.isSuccess
                if (!matches) continue
                val draft = store.decodeDraft(record.getJSONObject("draft")
                    .put("uri", file.uri.toString()).put("folderTree", tree.toString()))
                val time = record.optLong("completed", -1).takeIf { it >= 0 }
                val completion = record.optString("completionKind").takeIf { it.isNotEmpty() }?.let(ImportCompletionKind::valueOf)
                if (store.relink(ImportHistoryEntry(draft, ImportHistoryStage.COMPLETE, receipt, time, completion))) {
                    linked++
                    break
                }
            }
        }
        "$linked of ${records.size} receipts linked from this folder. Choose any other import folders to continue."
    }

    fun restoreQueue(manifest: Uri) = run("Restoring camera download entries…") {
        val rows = read(manifest).getJSONArray("queue")
        check(rows.length() <= GalleryDownloadQueue.MAX_FILES)
        val candidates = List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val camera = row.getString("camera").also { check(HEX.matches(it)) }
            val file = row.getString("file").also { check(HEX.matches(it)) }
            val name = safeName(row.getString("name"))
            val bytes = row.getLong("bytes").also { check(it > 0) }
            camera to GalleryDownloadQueue.Source(file, name, bytes, Store.valueOf(row.getString("storage")))
        }
        val added = GalleryQueueJournal.get(app).restore(candidates)
        "$added new downloads restored. Connect the same camera and refresh its library to start them."
    }

    private fun name(record: JSONObject): String = safeName(record.getJSONObject("receipt").optString("actualName")
        .ifBlank { record.getJSONObject("draft").getString("name") })

    private fun safeName(value: String): String = value.also {
        check(it.length in 1..255 && it !in setOf(".", "..") && it.none { ch -> ch == '/' || ch == '\\' || ch.isISOControl() })
    }

    private fun read(uri: Uri): JSONObject {
        check(uri.scheme == "content")
        val bytes = checkNotNull(app.contentResolver.openInputStream(uri)).use { it.readBounded(MAX_BYTES) }
        return JSONObject(bytes.toString(Charsets.UTF_8)).also {
            check(it.getString("format") == "opengrip-library-transfer" && it.getInt("version") == 1)
        }
    }

    private fun run(message: String, work: () -> String) {
        if (state.value?.busy == true) return
        state.value = State(message, busy = true)
        worker.execute {
            val result = runCatching(work).getOrElse {
                "Couldn’t complete the transfer. Check the selected file, folder access, and available queue space, then try again."
            }
            state.postValue(State(result))
        }
    }

    override fun onCleared() { worker.shutdownNow() }

    companion object {
        private val HEX = Regex("[0-9a-f]{64}")
        private const val MAX_BYTES = 8 * 1024 * 1024
        private const val MAX_RECEIPTS = 10_000
    }
}
