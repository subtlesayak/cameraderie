package app.cameraderie.gallery.media.edit

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Editor activities share one process. Directories outside its live set are abandoned. */
internal class VideoEditorScratch private constructor(val directory: File) : AutoCloseable {
    override fun close() {
        synchronized(activeDirectories) {
            if (activeDirectories.remove(directory)) directory.deleteRecursively()
        }
    }

    companion object {
        private const val PREFIX = "video-edit-"
        private val scratchName = Regex("${PREFIX}[0-9]+")
        private val activeDirectories = mutableSetOf<File>()

        fun create(privateDir: File): VideoEditorScratch = synchronized(activeDirectories) {
            val root = privateDir.canonicalFile
            root.listFiles()?.forEach { path ->
                if (scratchName.matches(path.name) && path !in activeDirectories &&
                    Files.isDirectory(path.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    path.deleteRecursively()
                }
            }
            val directory = Files.createTempDirectory(root.toPath(), PREFIX).toFile()
            activeDirectories.add(directory)
            VideoEditorScratch(directory)
        }
    }
}
