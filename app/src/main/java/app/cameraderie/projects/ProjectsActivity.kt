package app.cameraderie.projects

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import app.cameraderie.R
import app.cameraderie.gallery.media.edit.VerifiedVideo
import app.cameraderie.gallery.media.edit.VideoEditorActivity
import app.cameraderie.gallery.media.history.ImportHistoryEntry
import app.cameraderie.gallery.media.history.ImportHistoryRepository
import app.cameraderie.gallery.media.history.ImportedImagesActivity
import app.cameraderie.sony.SonyImportPreferences
import app.cameraderie.sony.SonyScreenProtection
import app.cameraderie.sony.SonyScreenWindows
import app.cameraderie.ui.CameraderieActivity
import app.cameraderie.ui.UiKit
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.Executors

class ProjectsActivity : CameraderieActivity() {
    private val projectId by lazy { intent.getStringExtra(PROJECT_ID) }
    private val shootId by lazy { intent.getStringExtra(SHOOT_ID) }
    private val store by lazy { ProjectStore(applicationContext) }
    private val history by lazy { ImportHistoryRepository.get(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var ui: UiKit
    private lateinit var toolbar: MaterialToolbar
    private lateinit var list: ListView
    private lateinit var header: LinearLayout
    private var message = ""
    private val mediaPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = projectId ?: return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val selected = result.data?.getStringArrayListExtra(ImportedImagesActivity.PICKED_MEDIA).orEmpty().distinct()
        if (selected.isEmpty() || selected.size > 500) return@registerForActivityResult
        work({ store.addMedia(id, selected.mapNotNull(history.store::find), shootId) }) { added ->
            message = when (added) {
                0 -> if (shootId == null) "No new verified files were added."
                else "No new files were added. Use Move to shoot for files already assigned."
                1 -> "Added 1 file."
                else -> "Added $added files."
            }
            load()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this)
        val root = ui.column().apply { setBackgroundColor(ui.tone.background) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        toolbar = MaterialToolbar(this).apply {
            title = "Projects"
            setTitleTextColor(ui.tone.text)
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationContentDescription = "Back"
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar)
        header = ui.column().apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8)) }
        list = ListView(this).apply {
            addHeaderView(header, null, false)
            divider = null
            setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(20))
            clipToPadding = false
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        SonyScreenWindows.apply(window, SonyScreenProtection.required(SonyImportPreferences(this).allowGalleryScreenshots, true))
        load()
    }

    private data class ProjectDetails(
        val project: Project?, val shoots: List<ProjectShoot>, val media: List<ProjectMedia>,
        val exports: List<ImportHistoryEntry>,
    )

    private fun load() {
        val id = projectId
        if (id == null) {
            work(store::projects, ::showProjects)
            return
        }
        work({ ProjectDetails(store.project(id), store.shoots(id), store.media(id), history.completedProjectExports(id)) }) {
            (project, shoots, media, exports) ->
            val currentShoot = shoots.firstOrNull { it.id == shootId }
            when {
                project == null || (shootId != null && currentShoot == null) -> {
                    toast("Project or shoot unavailable.")
                    finish()
                }
                currentShoot == null -> showProject(project, shoots, media, exports)
                else -> showShoot(project, currentShoot, shoots, media.filter { it.shootId == currentShoot.id },
                    exports.filter { it.draft.projectExport?.shootId == currentShoot.id })
            }
        }
    }

    private fun showProjects(projects: List<Project>) {
        header.removeAllViews()
        ui.add(header, ui.action("New project", primary = true) { edit(null) })
        if (projects.isEmpty()) ui.add(header, ui.text("No projects yet.", secondary = true), 12)
        setRows(projects.map { project ->
            Row("${project.name}\n${fileCount(project.mediaCount)}") { open(project.id) }
        })
    }

    private fun showProject(project: Project, shoots: List<ProjectShoot>, media: List<ProjectMedia>,
                            exports: List<ImportHistoryEntry>) {
        toolbar.title = project.name
        header.removeAllViews()
        ui.row(header, ui.action("New shoot", primary = true) { editShoot(project, null) },
            ui.action("Add from Library", onClick = ::pickMedia))
        ui.add(header, ui.action("Edit project") { edit(project) }, 8)
        showNotes(project.notes)
        showStatus(media.size)
        val names = shoots.associate { it.id to it.name }
        setRows(buildList {
            if (shoots.isNotEmpty()) {
                add(Row("Shoots"))
                addAll(shoots.map { shoot ->
                    Row("${shoot.name}\n${fileCount(shoot.mediaCount)}") { open(project.id, shoot.id) }
                })
            }
            if (media.isNotEmpty()) {
                add(Row("Project media"))
                addAll(media.map { item ->
                    Row("${item.name}\n${names[item.shootId] ?: "Unassigned"}") {
                        showMediaActions(project.id, item, shoots)
                    }
                })
            }
            addAll(exportRows(exports, names))
        })
    }

    private fun showShoot(project: Project, shoot: ProjectShoot, shoots: List<ProjectShoot>, media: List<ProjectMedia>,
                          exports: List<ImportHistoryEntry>) {
        toolbar.title = shoot.name
        toolbar.subtitle = project.name
        toolbar.menu.clear()
        toolbar.menu.add("Edit shoot").setOnMenuItemClickListener { editShoot(project, shoot); true }
        val position = shoots.indexOf(shoot)
        if (position > 0) toolbar.menu.add("Move earlier").setOnMenuItemClickListener {
            work({ store.moveShoot(project.id, shoot.id, -1) }) { load() }
            true
        }
        if (position in 0 until shoots.lastIndex) toolbar.menu.add("Move later").setOnMenuItemClickListener {
            work({ store.moveShoot(project.id, shoot.id, 1) }) { load() }
            true
        }
        header.removeAllViews()
        ui.add(header, ui.action("Add from Library", primary = true, onClick = ::pickMedia))
        showNotes(shoot.notes)
        showStatus(media.size)
        setRows(media.map { item -> Row(item.name) { showMediaActions(project.id, item, shoots) } } + exportRows(exports))
    }

    private fun exportRows(exports: List<ImportHistoryEntry>, shootNames: Map<String, String>? = null): List<Row> {
        if (exports.isEmpty()) return emptyList()
        return listOf(Row("Exports")) + exports.map { entry ->
            val origin = checkNotNull(entry.draft.projectExport)
            val label = buildList {
                add(entry.receipt?.displayName ?: entry.draft.displayName)
                shootNames?.let { add(it[origin.shootId] ?: "Unassigned") }
                add("Source · ${entry.draft.videoSource}")
            }.joinToString("\n")
            Row(label) { showExport(entry) }
        }
    }

    private fun showExport(entry: ImportHistoryEntry) {
        val origin = checkNotNull(entry.draft.projectExport)
        work({
            history.store.find(origin.sourceImportId)?.takeIf {
                it.receipt?.sha256 == origin.sourceSha256 && VerifiedVideo.supports(it)
            }
        }) { source ->
            val builder = MaterialAlertDialogBuilder(this)
                .setTitle(entry.receipt?.displayName ?: entry.draft.displayName)
                .setMessage("Source · ${entry.draft.videoSource}")
                .setNegativeButton("Close", null)
                .setPositiveButton("Open export") { _, _ -> openFile(entry.draft.uri) }
            if (source != null) builder.setNeutralButton("Edit original") { _, _ ->
                startActivity(VideoEditorActivity.intent(this, origin.sourceImportId, origin.projectId))
            }
            SonyScreenWindows.showMedia(builder.create(), this)
        }
    }

    private fun showNotes(notes: String) {
        if (notes.isBlank()) return
        ui.add(header, ui.text("Notes", bold = true).apply { isAccessibilityHeading = true }, 12)
        ui.add(header, ui.text(notes).apply { setTextIsSelectable(true) }, 4)
    }

    private fun showStatus(count: Int) {
        ui.add(header, ui.text(message.ifBlank { fileCount(count) }, secondary = true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }, 12)
    }

    private fun open(projectId: String, shootId: String? = null) {
        startActivity(Intent(this, ProjectsActivity::class.java)
            .putExtra(PROJECT_ID, projectId).putExtra(SHOOT_ID, shootId))
    }

    private fun pickMedia() {
        mediaPicker.launch(Intent(this, ImportedImagesActivity::class.java).putExtra(ImportedImagesActivity.PICK_FOR_PROJECT, true))
    }

    private data class Row(val label: String, val open: (() -> Unit)? = null) {
        override fun toString() = label
    }

    private fun setRows(rows: List<Row>) {
        list.adapter = object : ArrayAdapter<Row>(this, android.R.layout.simple_list_item_1, rows) {
            override fun areAllItemsEnabled() = false
            override fun isEnabled(position: Int) = rows[position].open != null
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val row = super.getView(position, convertView, parent) as TextView
                val heading = !isEnabled(position)
                row.setTextColor(if (heading) ui.tone.secondary else ui.tone.text)
                row.minimumHeight = ui.dp(if (heading) 36 else 56)
                row.isAccessibilityHeading = heading
                return row
            }
        }
        list.setOnItemClickListener { _, _, position, _ ->
            rows.getOrNull(position - list.headerViewsCount)?.open?.invoke()
        }
    }

    private fun showMediaActions(id: String, media: ProjectMedia, shoots: List<ProjectShoot>) {
        work({ history.store.find(media.importId)?.takeIf { ProjectMedia.from(it)?.sha256 == media.sha256 } }) { entry ->
            val edit = entry?.let(VerifiedVideo::supports) == true
            val builder = MaterialAlertDialogBuilder(this).setTitle(media.name)
                .setNegativeButton("Close", null)
                .setNeutralButton("Remove") { _, _ ->
                    work({ store.removeMedia(id, media.importId) }) { message = "Removed from project."; load() }
                }
            if (entry != null) builder.setPositiveButton(if (edit) "Edit video" else "Open") { _, _ ->
                if (edit) startActivity(VideoEditorActivity.intent(this, media.importId, id))
                else openFile(entry.draft.uri)
            }
            val dialog = builder.create()
            val items = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8)) }
            if (entry == null) ui.add(items, ui.text("The original Library receipt is unavailable."))
            if (shoots.isNotEmpty()) ui.add(items, ui.action("Move to shoot") {
                dialog.dismiss()
                chooseShoot(id, media, shoots)
            })
            if (items.childCount > 0) dialog.setView(items)
            SonyScreenWindows.showMedia(dialog, this)
        }
    }

    private fun openFile(uri: String) {
        work({ history.local.open(uri) }) { intent ->
            runCatching { startActivity(intent) }.onFailure { toast("No compatible app can open this file.") }
        }
    }

    private fun chooseShoot(id: String, media: ProjectMedia, shoots: List<ProjectShoot>) {
        val names = (listOf("Unassigned") + shoots.map(ProjectShoot::name)).toTypedArray()
        val current = shoots.indexOfFirst { it.id == media.shootId } + 1
        val picker = MaterialAlertDialogBuilder(this)
            .setTitle("Move to shoot")
            .setSingleChoiceItems(names, current) { dialog, index ->
                dialog.dismiss()
                if (index != current) {
                    val target = shoots.getOrNull(index - 1)
                    work({ store.moveMedia(id, media.importId, target?.id) }) {
                        message = target?.let { "Moved to ${it.name}." } ?: "Moved to unassigned."
                        load()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        SonyScreenWindows.showMedia(picker, this)
    }

    private fun edit(project: Project?) {
        editDetails(if (project == null) "New project" else "Edit project", "Project name",
            project?.name.orEmpty(), project?.notes.orEmpty(), project == null) { title, notes ->
            if (project == null) work({ store.create(title, notes) }) { open(it) }
            else work({ store.update(project.id, title, notes) }) { message = "Project saved."; load() }
        }
    }

    private fun editShoot(project: Project, shoot: ProjectShoot?) {
        editDetails(if (shoot == null) "New shoot" else "Edit shoot", "Shoot name",
            shoot?.name.orEmpty(), shoot?.notes.orEmpty(), shoot == null) { title, notes ->
            if (shoot == null) work({ store.createShoot(project.id, title, notes) }) { open(project.id, it) }
            else work({ store.updateShoot(project.id, shoot.id, title, notes) }) { message = "Shoot saved."; load() }
        }
    }

    private fun editDetails(
        title: String, label: String, currentName: String, currentNotes: String, creating: Boolean,
        save: (String, String) -> Unit,
    ) {
        val form = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8)) }
        val name = field(form, label, currentName, ProjectStore.MAX_NAME, false)
        val notes = field(form, "Notes", currentNotes, ProjectStore.MAX_NOTES, true)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (creating) "Create" else "Save", null)
            .create()
        SonyScreenWindows.showMedia(dialog, this)
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = name.text?.toString()?.trim().orEmpty()
            if (value.isBlank()) {
                name.error = "Enter a name."
                return@setOnClickListener
            }
            val text = notes.text?.toString().orEmpty()
            dialog.dismiss()
            save(value, text)
        }
    }

    private fun field(form: LinearLayout, label: String, value: String, limit: Int, multiline: Boolean): TextInputEditText {
        val box = TextInputLayout(this).apply { hint = label }
        val edit = TextInputEditText(box.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(!multiline)
            maxLines = if (multiline) 5 else 1
            filters = arrayOf(InputFilter.LengthFilter(limit))
            setText(value)
        }
        box.addView(edit)
        ui.add(form, box, 8)
        return edit
    }

    private fun <T> work(action: () -> T, completed: (T) -> Unit) {
        worker.execute {
            val result = runCatching(action)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess(completed).onFailure { toast("Couldn’t read or save the project. Try again.") }
            }
        }
    }

    private fun fileCount(count: Int) = if (count == 1) "1 file" else "$count files"
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        worker.execute { store.close() }
        worker.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val PROJECT_ID = "projectId"
        const val SHOOT_ID = "shootId"
    }
}
