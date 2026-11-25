package app.cameraderie.projects

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import app.cameraderie.gallery.media.AlbumMediaFile
import app.cameraderie.gallery.media.edit.Media3VideoEdit
import app.cameraderie.gallery.media.edit.ProjectCutStore
import app.cameraderie.gallery.media.edit.ResolvePackage
import app.cameraderie.gallery.media.edit.TrimRange
import app.cameraderie.gallery.media.edit.VerifiedVideo
import app.cameraderie.gallery.media.edit.VideoEditorScratch
import app.cameraderie.gallery.media.history.ImportHistoryRepository
import app.cameraderie.gallery.media.history.ProjectExportOrigin
import java.io.File
import java.io.IOException

internal object ProjectResolveExport {
    fun save(context: Context, store: ProjectStore, projectId: String, shootId: String?, destination: Uri,
             signal: CancellationSignal): Int {
        val project = checkNotNull(store.project(projectId))
        val shoots = store.shoots(projectId)
        val shoot = shootId?.let { id -> checkNotNull(shoots.find { it.id == id }) }
        val positions = shoots.mapIndexed { index, item -> item.id to index }.toMap()
        val selected = store.media(projectId).filter { it.selectedTake && (shootId == null || it.shootId == shootId) }
            .sortedBy { positions[it.shootId] ?: Int.MAX_VALUE }
        val history = ImportHistoryRepository.get(context)
        val entries = history.store.findAll(selected.map(ProjectMedia::importId).toSet())
        val videos = selected.mapNotNull { item ->
            val entry = entries[item.importId]
            if (entry?.receipt?.sha256 == item.sha256 && VerifiedVideo.supports(entry)) item
            else {
                if (AlbumMediaFile.document(item.name)?.video == true && (entry == null || entry.receipt?.sha256 != item.sha256)) {
                    throw IOException("The verified receipt for ${item.name} is unavailable.")
                }
                null
            }
        }
        if (videos.isEmpty()) throw IOException("Mark at least one verified original video as a selected take.")
        val existing = history.completedProjectExports(projectId)
            .sortedByDescending { it.completedAt ?: it.draft.startedAt ?: 0L }
            .groupBy { it.draft.projectExport?.sourceImportId }
        val edits = ProjectCutStore(context.noBackupFilesDir)
        val label = shoot?.let { "${project.name} · ${it.name}" } ?: project.name
        VideoEditorScratch.create(context.noBackupFilesDir).use { scratch ->
            val inputs = videos.asSequence().map { item ->
                signal.throwIfCanceled()
                val file = File(scratch.directory, "original")
                try {
                    val video = VerifiedVideo.load(item.importId, history, context.contentResolver, file, signal)
                    val origin = checkNotNull(store.exportOrigin(projectId, video.entry))
                    check(origin.sourceSha256 == item.sha256 && origin.shootId == item.shootId)
                    val edit = edits.read(origin, video) ?: run {
                        val published = existing[item.importId].orEmpty().firstNotNullOfOrNull { entry ->
                            if (entry.draft.projectExport?.sourceSha256 != item.sha256) return@firstNotNullOfOrNull null
                            val start = entry.draft.trimStartUs?.div(1_000) ?: return@firstNotNullOfOrNull null
                            val end = entry.draft.trimEndUs?.div(1_000) ?: return@firstNotNullOfOrNull null
                            if (start in 0 until end && end <= video.durationMs) TrimRange(start, end) else null
                        }
                        Media3VideoEdit(published ?: TrimRange(0, video.durationMs), null, null, projectId = projectId)
                    }
                    ResolvePackage.Input.from(context, video, edit, origin)
                } finally { file.delete() }
            }
            ResolvePackage.saveProject(context, destination, ResolvePackage.Project(label, projectId, shootId), inputs) { signal.isCanceled }
        }
        return videos.size
    }
}
