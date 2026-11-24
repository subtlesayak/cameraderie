package app.cameraderie.gallery.media

import android.content.Context
import android.util.AtomicFile
import app.cameraderie.gallery.protocol.GalleryCameraEvidence.Store
import java.io.File
import java.io.FileNotFoundException
import org.json.JSONArray
import org.json.JSONObject

/** Private local metadata. No network credentials, URI, raw address or remote path. */
internal class GalleryQueueJournal(path: File) : GalleryDownloadQueue.Journal {
    private val file = AtomicFile(path)

    override fun read(): List<GalleryDownloadQueue.Job> {
        val bytes = try { file.readFully() } catch (_: FileNotFoundException) { return emptyList() }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        check(root.getInt("version") == 1)
        val rows = root.getJSONArray("jobs")
        check(rows.length() <= GalleryDownloadQueue.MAX_FILES)
        return List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val source = GalleryDownloadQueue.Source(row.getString("file"), row.getString("name"), row.getLong("bytes"),
                Store.valueOf(row.getString("storage")))
            GalleryDownloadQueue.Job(row.getString("id"), row.getString("camera"), source,
                GalleryDownloadQueue.State.valueOf(row.getString("state")),
                if (row.isNull("attempt")) null else row.getString("attempt"))
        }
    }

    override fun write(jobs: List<GalleryDownloadQueue.Job>) {
        val rows = JSONArray()
        for (job in jobs) rows.put(JSONObject().apply {
            put("id", job.id); put("camera", job.camera); put("file", job.source.identity)
            put("name", job.source.name); put("bytes", job.source.bytes); put("storage", job.source.store.name); put("state", job.state.name)
            put("attempt", job.attempt ?: JSONObject.NULL)
        })
        val bytes = JSONObject().put("version", 1).put("jobs", rows).toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }

    companion object {
        private var process: GalleryDownloadQueue? = null
        @Synchronized fun get(context: Context): GalleryDownloadQueue = process ?: GalleryDownloadQueue(
            GalleryQueueJournal(File(context.noBackupFilesDir, "action6-download-queue.json"))).also { queue ->
            partials(context).removeUnused(queue.snapshot().filter { it.state.pending }.map { it.id }.toSet())
            process=queue
        }
        fun partials(context:Context) = GalleryPartialDownloads(File(context.noBackupFilesDir,"action6-download-parts"))
    }
}
