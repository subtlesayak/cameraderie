package app.cameraderie.gallery.media.edit

import app.cameraderie.gallery.media.history.ProjectExportOrigin
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

/** A cut follows its exact verified original within one project. */
internal class ProjectCutStore(privateDir: File) {
    private val directory = File(privateDir, "project-video-cuts")

    fun save(origin: ProjectExportOrigin, edit: Media3VideoEdit) {
        require(edit.projectId == origin.projectId)
        Files.createDirectories(directory.toPath())
        val staged = Files.createTempFile(directory.toPath(), "cut-", ".part")
        try {
            FileOutputStream(staged.toFile()).use { file ->
                val output = DataOutputStream(file.buffered())
                VideoEditRecipe.write(output, edit)
                output.flush()
                file.fd.sync()
            }
            Files.move(staged, path(origin).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(staged) }
    }

    fun read(origin: ProjectExportOrigin, video: VerifiedVideo): Media3VideoEdit? =
        read(origin, video.width, video.height, video.durationMs)

    internal fun read(origin: ProjectExportOrigin, width: Int, height: Int, durationMs: Long): Media3VideoEdit? {
        val input = try { Files.newInputStream(path(origin).toPath()) } catch (_: NoSuchFileException) { return null }
        return DataInputStream(input.buffered()).use {
            VideoEditRecipe.read(it, width, height, durationMs).also { edit -> require(edit.projectId == origin.projectId) }
        }
    }

    private fun path(origin: ProjectExportOrigin): File {
        val key = listOf(origin.projectId, origin.sourceImportId, origin.sourceSha256).joinToString("\u0000")
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.edit")
    }
}
