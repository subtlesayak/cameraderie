package app.cameraderie.gallery.media.history

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import app.cameraderie.sony.SonyScreenWindows
import app.cameraderie.ui.UiKit
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

internal enum class MediaGroup(val label: String) {
    Image("Photos and images"), Video("Videos"), Audio("Audio"),
    Track("GPX tracks"), Insta360("Insta360 originals"),
}

internal data class MediaFacets(
    val camera: String?,
    val captureDate: String?,
    val extension: String?,
    val group: MediaGroup?,
    val shootIds: Set<String>,
    val selectedTake: Boolean = false,
) {
    val cameraKey: String get() = camera?.lowercase(Locale.ROOT).orEmpty()

    companion object {
        fun from(entry: ImportHistoryEntry?, name: String, shoots: Set<String>, source: ImportHistoryEntry? = entry): MediaFacets {
            val extension = name.substringAfterLast('.', "").uppercase(Locale.ROOT)
                .takeIf { it.length in 1..12 && it.all { char -> char in 'A'..'Z' || char in '0'..'9' } }
                .let { if (it == "JPG") "JPEG" else it }
            val mime = entry?.receipt?.mime.orEmpty()
            val group = when {
                extension == "INSV" || extension == "INSP" -> MediaGroup.Insta360
                extension == "GPX" || mime == "application/gpx+xml" -> MediaGroup.Track
                mime.startsWith("image/") -> MediaGroup.Image
                mime.startsWith("video/") -> MediaGroup.Video
                mime.startsWith("audio/") -> MediaGroup.Audio
                else -> null
            }
            val camera = source?.draft?.cameraModel?.trim()?.takeIf(String::isNotEmpty)?.let { model ->
                if (model.startsWith("Sony ILCE-", ignoreCase = true)) {
                    model.substringAfter(' ').uppercase(Locale.ROOT)
                } else model
            }
            return MediaFacets(camera, source?.receipt?.captureDate, extension, group, shoots)
        }

        fun forExport(entry: ImportHistoryEntry, originals: Map<String, ImportHistoryEntry>, shoots: Set<String>): MediaFacets {
            val source = entry.draft.projectExport?.let { origin ->
                originals[origin.sourceImportId]?.takeIf {
                    it.stage == ImportHistoryStage.COMPLETE && it.receipt?.sha256 == origin.sourceSha256
                }
            }
            return from(entry, entry.receipt?.displayName ?: entry.draft.displayName, shoots, source ?: entry)
        }
    }
}

/** Null selects all values. A blank value selects media missing that information. */
internal data class MediaFilters(
    val camera: String? = null,
    val date: String? = null,
    val format: String? = null,
    val shoot: String? = null,
    val selectedTakesOnly: Boolean = false,
) {
    val count get() = listOf(camera, date, format, shoot).count { it != null } + if (selectedTakesOnly) 1 else 0

    fun matches(facts: MediaFacets): Boolean =
        (camera == null || camera == facts.cameraKey) &&
            (date == null || date == facts.captureDate.orEmpty()) &&
            (format == null || format == facts.extension || format == facts.group?.let(::groupKey)) &&
            (!selectedTakesOnly || facts.selectedTake) &&
            when (shoot) {
                null -> true
                "" -> facts.shootIds.isEmpty()
                else -> shoot in facts.shootIds
            }

    fun save() = Bundle().apply {
        putString("camera", camera)
        putString("captureDate", date)
        putString("format", format)
        putString("shoot", shoot)
        putBoolean("selectedTakesOnly", selectedTakesOnly)
    }

    companion object {
        fun groupKey(group: MediaGroup) = "group:" + group.name

        fun restore(bundle: Bundle?): MediaFilters = MediaFilters(
            bundle?.getString("camera"), bundle?.getString("captureDate"),
            bundle?.getString("format"), bundle?.getString("shoot"), bundle?.getBoolean("selectedTakesOnly") == true,
        )
    }
}

internal object MediaFilterDialog {
    private data class Choice(val key: String?, val label: String)

    fun show(
        context: Context, facts: Collection<MediaFacets>, shoots: Map<String, String>?,
        selected: MediaFilters, showTakeFilter: Boolean = false, apply: (MediaFilters) -> Unit,
    ) {
        val ui = UiKit(context)
        val fields = ui.column().apply { setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(6)) }
        val cameras = facts.mapNotNull { item -> item.camera?.let { Choice(item.cameraKey, it) } }
            .distinctBy(Choice::key).sortedBy(Choice::key)
        val cameraOptions = cameras + missing(facts.any { it.camera == null }, "Camera not recorded")
        val dates = facts.mapNotNull(MediaFacets::captureDate).distinct().sortedDescending().map { Choice(it, it) }
        val dateOptions = dates + missing(facts.any { it.captureDate == null }, "Capture date not recorded")
        val groups = MediaGroup.entries.filter { group -> facts.any { it.group == group } }
            .map { Choice(MediaFilters.groupKey(it), it.label) }
        val formats = facts.mapNotNull(MediaFacets::extension).distinct().sorted().map { Choice(it, it) }
        val camera = picker(context, ui, fields, "Camera", "All cameras", cameraOptions, selected.camera)
        val date = picker(context, ui, fields, "Capture date", "All capture dates", dateOptions, selected.date)
        val format = picker(context, ui, fields, "Format", "All formats", groups + formats, selected.format)
        val shoot = shoots?.let { names ->
            val options = names.map { (id, name) -> Choice(id, name) } +
                missing(facts.any { it.shootIds.isEmpty() }, "Unassigned")
            picker(context, ui, fields, "Shoot", "All shoots", options, selected.shoot)
        }
        val takes = if (showTakeFilter) CheckBox(context).apply {
            text = "Selected takes only"
            isChecked = selected.selectedTakesOnly
            minHeight = ui.dp(48)
        }.also { ui.add(fields, it, 10) } else null
        val dialog = MaterialAlertDialogBuilder(context).setTitle("Filter media")
            .setView(ScrollView(context).apply { addView(fields) })
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear filters") { _, _ -> apply(MediaFilters()) }
            .setPositiveButton("Apply filters") { _, _ ->
                apply(MediaFilters(camera(), date(), format(), shoot?.invoke(), takes?.isChecked == true))
            }
            .create()
        SonyScreenWindows.showMedia(dialog, context)
    }

    private fun missing(show: Boolean, label: String): List<Choice> =
        if (show) listOf(Choice("", label)) else emptyList()

    private fun picker(
        context: Context, ui: UiKit, parent: LinearLayout, label: String,
        all: String, options: List<Choice>, selected: String?,
    ): () -> String? {
        val choices = listOf(Choice(null, all)) + options
        val retained = if (choices.any { it.key == selected }) choices
            else choices + Choice(selected, "Previously selected")
        val field = Spinner(context).apply {
            id = View.generateViewId()
            prompt = label
            adapter = ui.choices(retained.map(Choice::label))
            setSelection(retained.indexOfFirst { it.key == selected })
        }
        ui.add(parent, ui.text(label, secondary = true).apply { labelFor = field.id }, 10)
        ui.add(parent, field)
        return { retained.getOrNull(field.selectedItemPosition)?.key }
    }
}
