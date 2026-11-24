package app.cameraderie.gallery.media

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.cameraderie.GalleryService
import app.cameraderie.R
import app.cameraderie.gallery.GalleryConnectFlow
import app.cameraderie.gallery.protocol.GalleryCameraEvidence.Store
import app.cameraderie.sony.SonyScreenWindows
import app.cameraderie.ui.UiKit
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.text.NumberFormat

/** Reads the service's existing session. Opening the queue never connects or starts work. */
internal class GalleryQueueDialog(private val activity: AppCompatActivity, private val ui: UiKit,
    private val service: () -> GalleryService?) : AutoCloseable {
    private enum class Section(val label: String) { ACTIVE("Current file"), PENDING("Up next"), HISTORY("History") }
    private data class Entry(val job: GalleryDownloadQueue.Job, val active: Boolean) {
        val section = when {
            active -> Section.ACTIVE
            job.state.pending -> Section.PENDING
            else -> Section.HISTORY
        }
    }

    private val headline = ui.text("",UiKit.Type.section,bold = true).apply {
        setTextColor(ui.tone.onContainer)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val transferIcon = glyph(R.drawable.ic_queue_download,ui.tone.onAccent,ui.tone.accent)
    private val stackCounts = activity.resources.configuration.let { it.screenWidthDp < 360 || it.fontScale >= 1.3f }
    private val pendingCount = CountTile("Pending")
    private val savedCount = CountTile("Saved")
    private val canceledCount = CountTile("Canceled")
    private val counts = LinearLayout(activity).apply {
        orientation = if (stackCounts) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        listOf(pendingCount,savedCount,canceledCount).forEachIndexed { index, tile ->
            addView(tile, if (stackCounts) LinearLayout.LayoutParams(-1,-2).apply {
                if (index > 0) topMargin = ui.dp(4)
            } else LinearLayout.LayoutParams(0,-2,1f).apply {
                if (index > 0) marginStart = ui.dp(6)
            })
        }
    }
    private val detail = ui.text("",UiKit.Type.small).apply {
        setTextColor(ui.tone.onContainer)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val activeFile = ui.text("",UiKit.Type.body,bold = true).apply {
        setTextColor(ui.tone.onContainer)
    }
    private val percentage = ui.text("",UiKit.Type.section,bold = true).apply {
        setTextColor(ui.tone.onContainer)
        fontFeatureSettings = "tnum"
    }
    private val progress = LinearProgressIndicator(activity).apply {
        setIndicatorColor(ui.tone.accent)
        trackColor = ui.tone.surface
        trackCornerRadius = ui.dp(5)
        trackThickness = ui.dp(10)
        contentDescription = "Total download progress"
        visibility = View.GONE
    }
    private val numberFormat = NumberFormat.getIntegerInstance()
    private val percentFormat = NumberFormat.getPercentInstance()
    private var entries = emptyList<Entry>()
    private var restartId: String? = null
    private var confirm: androidx.appcompat.app.AlertDialog? = null
    private val cancel = ui.action("Select downloads to cancel") { selectCancellation() }
    private val rows = object : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = getItem(position).job.state.pending

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? FileRow ?: FileRow()
            val entry = getItem(position)
            val startsSection = position == 0 || entries[position - 1].section != entry.section
            row.bind(entry, startsSection)
            return row
        }
    }
    private val list = object : ListView(activity) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maximum = ui.dp((resources.configuration.screenHeightDp * 0.58f).toInt())
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val height = if (mode == MeasureSpec.UNSPECIFIED) maximum else minOf(maximum, MeasureSpec.getSize(heightMeasureSpec))
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
        }
    }.apply {
        setPaddingRelative(ui.dp(24),0,ui.dp(24),ui.dp(8))
        clipToPadding = false
        divider = null
        addHeaderView(ui.column().apply {
            val overview = ui.column().apply {
                background = rounded(ui.tone.container,24)
                setPadding(ui.dp(16),ui.dp(16),ui.dp(16),ui.dp(16))
                ui.add(this,LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(transferIcon,LinearLayout.LayoutParams(ui.dp(40),ui.dp(40)).apply { marginEnd = ui.dp(12) })
                    addView(headline,LinearLayout.LayoutParams(0,-2,1f))
                })
                ui.add(this,activeFile,10)
                ui.add(this,percentage,8)
                ui.add(this,progress,8)
                ui.add(this,detail,10)
            }
            ui.add(this,overview,4)
            ui.add(this,counts,8)
            ui.add(this,cancel,8)
        },null,false)
        adapter = rows
        setOnItemClickListener { parent, _, position, _ ->
            (parent.getItemAtPosition(position) as? Entry)?.job?.let(::confirmFileCancellation)
        }
    }
    private val dialog = MaterialAlertDialogBuilder(activity).setTitle("Download queue").setView(list)
        .setNegativeButton("Close",null).setPositiveButton("Start downloads",null).create()

    init {
        SonyScreenWindows.showMedia(dialog,activity)
        dialog.getButton(-1).setOnClickListener {
            val bridge = service()
            if (bridge != null && (bridge.galleryMediaState?.queueRunning == true || bridge.resumingGalleryQueue)) bridge.pauseGalleryQueue()
            else if (runCatching { bridge?.startGalleryQueue(restartId) }.getOrNull() != true) {
                Toast.makeText(activity,"Open the matching camera and load or stop its scan before starting.",Toast.LENGTH_LONG).show()
            }
            render()
        }
    }

    fun render() {
        if (!dialog.isShowing) return
        val bridge = service()
        val media = bridge?.galleryMediaState
        val jobs = runCatching { bridge?.galleryQueueItems() }.getOrNull()
        val pending = jobs.orEmpty().count { it.state.pending }
        val completed = jobs.orEmpty().count { it.state == GalleryDownloadQueue.State.COMPLETE }
        val canceled = jobs.orEmpty().count { it.state == GalleryDownloadQueue.State.CANCELED }
        val renewing = bridge?.resumingGalleryQueue == true
        val running = media?.queueRunning == true || renewing
        val library = media?.catalogue
        val ready = bridge?.galleryState?.stage == GalleryConnectFlow.Stage.READY && library?.downloadsAvailable == true
        val activeId = media?.activeQueueJob
        val foundSources = library?.takeIf { it.stoppedForDownloads }?.entries
            ?.asSequence()?.filter { GalleryOriginalImporter.supports(it.media) }
            ?.mapTo(HashSet()) { GalleryDownloadQueue.source(it.media) }
        val next = jobs.orEmpty().firstOrNull { it.state.pending && (foundSources == null || it.source in foundSources) }
        val needsRefresh = next?.state == GalleryDownloadQueue.State.REFRESH
        val listed = needsRefresh && ready && next?.source?.let { source ->
            media?.catalogue?.entries?.any { entry ->
                val file = entry.media
                file.key.store == source.store && file.name == source.name && file.size == source.bytes &&
                    GalleryDownloadQueue.source(file) == source
            }
        } == true
        restartId = next?.id?.takeIf { next.state == GalleryDownloadQueue.State.RESTART }
        val totalPercent = bridge?.galleryQueueTotalPercent?.takeIf { running || pending > 0 }
        val filePercent = media?.percent?.takeIf { activeId != null && running }
        val title = when {
            bridge == null -> "Opening queue…"
            jobs == null -> "Queue unavailable"
            jobs.isEmpty() -> "No downloads yet"
            renewing -> "Refreshing camera connection"
            running -> "Downloading"
            pending == 0 -> "No pending downloads"
            ready && next == null -> "Waiting for more camera files"
            !ready -> "Waiting for camera"
            restartId != null -> "Restart required"
            next?.state == GalleryDownloadQueue.State.RESUME -> "Download paused"
            listed -> "File needs verification"
            needsRefresh -> "File missing from library"
            next?.state == GalleryDownloadQueue.State.REVIEW -> "Check the last download"
            else -> "Ready to download"
        }
        val guidance = when {
            jobs == null -> ""
            jobs.isEmpty() -> "Select files in the camera gallery to add them here."
            renewing -> "Downloads will continue after the camera library reloads."
            running -> media?.transfer.orEmpty()
            pending == 0 -> ""
            ready && next == null -> "Refresh the camera library to find the remaining queued files."
            ready && media?.mediaRequestBusy == true -> "Waiting for the current camera request to stop."
            !ready -> "Open the matching camera and load its library."
            restartId != null -> "Restart discards saved bytes for the next file."
            next?.state == GalleryDownloadQueue.State.RESUME ->
                media?.transfer?.takeIf { it.startsWith("Free phone storage") } ?: "Saved bytes are checked before resuming."
            listed -> "Start downloads to ask the camera to confirm this file again."
            needsRefresh -> "Check the camera storage and refresh the library before retrying."
            next?.state == GalleryDownloadQueue.State.REVIEW -> "Check Library before retrying."
            else -> ""
        }
        headline.update(title)
        transferIcon.setImageResource(when {
            running || pending > 0 || completed == 0 -> R.drawable.ic_queue_download
            else -> R.drawable.ic_queue_done
        })
        counts.visibility = if (jobs.isNullOrEmpty()) View.GONE else View.VISIBLE
        pendingCount.bind(pending)
        savedCount.bind(completed)
        canceledCount.bind(canceled)
        val activeName = jobs.orEmpty().firstOrNull { it.id == activeId }?.source?.name
        val fileProgress = filePercent?.let { "File progress · ${percentFormat.format(it / 100.0)}" }
        activeFile.update(listOfNotNull(activeName, fileProgress).joinToString("\n"))
        detail.update(guidance)
        percentage.update(totalPercent?.let { "Total progress · ${percentFormat.format(it / 100.0)}" }.orEmpty())
        if (running || totalPercent != null) {
            if (progress.isIndeterminate != (totalPercent == null)) {
                progress.visibility = View.GONE
                progress.isIndeterminate = totalPercent == null
            }
            if (totalPercent != null && progress.progress != totalPercent) progress.setProgressCompat(totalPercent,false)
        }
        progress.visibility = if (running || totalPercent != null) View.VISIBLE else View.GONE

        val shown = jobs.orEmpty().map { Entry(it,it.id == activeId) }.sortedBy { it.section }
        if (entries != shown) {
            entries = shown
            rows.notifyDataSetChanged()
        }
        cancel.visibility = if (pending > 0) View.VISIBLE else View.GONE
        val action = when {
            running -> "Pause"
            restartId != null -> "Restart file"
            next?.state == GalleryDownloadQueue.State.RESUME -> "Resume downloads"
            else -> "Start downloads"
        }
        dialog.getButton(-1).apply {
            if (text.toString() != action) text = action
            isEnabled = running || (ready && media?.importing == false && !media.changingMedia &&
                !media.mediaRequestBusy && next != null)
            visibility = if (pending > 0 || running) View.VISIBLE else View.GONE
        }
    }

    private fun TextView.update(value: String) {
        if (text.toString() != value) text = value
        visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        cornerRadius = ui.dp(radius).toFloat()
        setColor(color)
    }

    private fun glyph(resource: Int, foreground: Int, backgroundColor: Int) = ImageView(activity).apply {
            setImageResource(resource)
            imageTintList = ColorStateList.valueOf(foreground)
            background = rounded(backgroundColor,12)
            setPadding(ui.dp(9),ui.dp(9),ui.dp(9),ui.dp(9))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private inner class CountTile(label: String) : LinearLayout(activity) {
        private val value = ui.text("",UiKit.Type.section,bold = true).apply { fontFeatureSettings = "tnum" }

        init {
            val caption = ui.text(label,UiKit.Type.small,secondary = true)
            isScreenReaderFocusable = true
            background = rounded(ui.tone.surface,14)
            setPadding(ui.dp(8),ui.dp(6),ui.dp(8),ui.dp(8))
            if (stackCounts) {
                gravity = Gravity.CENTER_VERTICAL
                addView(value,LayoutParams(-2,-2).apply { marginEnd = ui.dp(12) })
                addView(caption,LayoutParams(0,-2,1f))
            } else {
                orientation = VERTICAL
                ui.add(this,value)
                ui.add(this,caption)
            }
        }

        fun bind(count: Int) = value.update(numberFormat.format(count))
    }

    private inner class FileRow : LinearLayout(activity) {
        private val section = ui.text("",UiKit.Type.body,bold = true).apply {
            isAccessibilityHeading = true
            setPadding(0,ui.dp(14),0,ui.dp(6))
        }
        private val icon = glyph(R.drawable.ic_gallery,ui.tone.onContainer,ui.tone.container)
        private val name = ui.text("",UiKit.Type.body,bold = true)
        private val metadata = ui.text("",UiKit.Type.small,secondary = true)
        private val state = ui.text("",UiKit.Type.small,bold = true).apply {
            setTextColor(ui.tone.onContainer)
            setPadding(ui.dp(8),ui.dp(2),ui.dp(8),ui.dp(2))
        }
        private val cancelHint = ui.text("Cancel…",UiKit.Type.small,bold = true).apply {
            setTextColor(ui.tone.accent)
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setPaddingRelative(0,ui.dp(2),ui.dp(4),0)
        }
        private val content = LinearLayout(activity).apply {
            gravity = Gravity.TOP
            setPadding(ui.dp(12),ui.dp(10),ui.dp(12),ui.dp(10))
            addView(icon,LayoutParams(ui.dp(38),ui.dp(38)).apply { marginEnd = ui.dp(10) })
            addView(ui.column().apply {
                ui.add(this,name)
                ui.add(this,metadata)
                addView(state,LayoutParams(-2,-2).apply { topMargin = ui.dp(4) })
                ui.add(this,cancelHint,2)
            },LayoutParams(0,-2,1f))
        }

        init {
            orientation = VERTICAL
            setPadding(0,0,0,ui.dp(6))
            ui.add(this,section)
            addView(content,LayoutParams(-1,-2))
        }

        fun bind(entry: Entry, startsSection: Boolean) {
            val job = entry.job
            val storage = if (job.source.store == Store.SD) "SD card" else "Internal storage"
            section.update(if (startsSection) entry.section.label else "")
            val extension = job.source.name.substringAfterLast('.',"").lowercase()
            icon.setImageResource(when (extension) {
                "mp4", "mov" -> R.drawable.ic_queue_video
                "wav" -> R.drawable.ic_queue_audio
                else -> R.drawable.ic_gallery
            })
            name.text = job.source.name
            val format = extension.uppercase().takeIf { it.isNotEmpty() }
            metadata.text = listOfNotNull(format,Formatter.formatShortFileSize(activity,job.source.bytes),storage).joinToString(" · ")
            state.text = if (entry.active) "Downloading" else when (job.state) {
                GalleryDownloadQueue.State.WAITING -> "Queued"
                GalleryDownloadQueue.State.RESUME -> "Interrupted"
                GalleryDownloadQueue.State.RESTART -> "Restart required"
                GalleryDownloadQueue.State.REFRESH -> "Refresh needed"
                GalleryDownloadQueue.State.REVIEW -> "Check Library"
                GalleryDownloadQueue.State.COMPLETE -> "Completed"
                GalleryDownloadQueue.State.CANCELED -> "Canceled"
            }
            val highlighted = entry.active || job.state == GalleryDownloadQueue.State.COMPLETE
            state.background = rounded(if (highlighted) ui.tone.container else ui.tone.control,10)
            state.setTextColor(if (highlighted) ui.tone.onContainer else ui.tone.text)
            cancelHint.visibility = if (job.state.pending) View.VISIBLE else View.GONE
            content.background = rounded(if (entry.active) ui.tone.control else ui.tone.surface,16)
        }
    }

    private fun confirmFileCancellation(job: GalleryDownloadQueue.Job) {
        if (!job.state.pending) return
        confirmCancel(setOf(job.id),"Cancel download?","Cancel ${job.source.name}? Other downloads stay in the queue.")
    }

    private fun selectCancellation() {
        val bridge = service() ?: return
        val pending = runCatching { bridge.galleryQueueItems().filter { it.state.pending } }.getOrNull() ?: return
        if (pending.isEmpty()) return
        val checked = BooleanArray(pending.size)
        val chooser = MaterialAlertDialogBuilder(activity).setTitle("Select downloads to cancel")
            .setMultiChoiceItems(pending.map { it.source.name }.toTypedArray(), checked) { window, index, value ->
                checked[index] = value
                val alert = window as androidx.appcompat.app.AlertDialog
                alert.getButton(-1).isEnabled = checked.any { it }
                alert.getButton(-3).text = if (checked.all { it }) "Clear selection" else "Select all"
            }
            .setNegativeButton("Keep downloads", null)
            .setNeutralButton("Select all", null)
            .setPositiveButton("Cancel selected", null)
            .create()
        confirm = chooser
        SonyScreenWindows.showMedia(chooser, activity)
        chooser.getButton(-1).isEnabled = false
        chooser.getButton(-3).setOnClickListener {
            val select = checked.any { !it }
            for (index in pending.indices) {
                checked[index] = select
                chooser.listView.setItemChecked(index, select)
            }
            chooser.getButton(-1).isEnabled = select
            chooser.getButton(-3).text = if (select) "Clear selection" else "Select all"
        }
        chooser.getButton(-1).setOnClickListener {
            val ids = pending.indices.filter { checked[it] }.mapTo(linkedSetOf()) { pending[it].id }
            if (ids.isEmpty()) return@setOnClickListener
            chooser.dismiss()
            confirmCancel(ids,"Cancel selected downloads?","This cancels ${ids.size} pending downloads. Completed files stay in Library.")
        }
    }

    private fun confirmCancel(ids: Set<String>,title: String,message: String) {
        confirm = MaterialAlertDialogBuilder(activity).setTitle(title)
            .setMessage(message)
            .setNegativeButton("Keep",null).setPositiveButton(if (ids.size == 1) "Cancel download" else "Cancel downloads") { _, _ ->
                if (runCatching { checkNotNull(service()).cancelGalleryQueue(ids) }.isFailure) {
                    Toast.makeText(activity,"Could not save the queue change.",Toast.LENGTH_LONG).show()
                }
                render()
            }.create().also { SonyScreenWindows.showMedia(it,activity) }
    }

    override fun close() {
        confirm?.dismiss()
        dialog.dismiss()
    }
}
