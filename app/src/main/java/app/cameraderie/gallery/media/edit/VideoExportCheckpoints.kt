package app.cameraderie.gallery.media.edit

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

/** A checkpoint belongs to the verified import and exact export settings. */
internal class VideoExportCheckpoints(privateDir: File, legacyCacheDir: File, private val source: VerifiedVideo) {
    private val directory = File(privateDir, "video-export-checkpoints")
    private val legacyDirectory = File(legacyCacheDir, directory.name)
    private val sourceKey = fingerprint {
        writeUTF(source.entry.draft.id)
        writeUTF(checkNotNull(source.entry.receipt?.sha256))
    }

    data class Resume(val stored: File, val reference: File, val working: File, val edit: Media3VideoEdit)

    fun exists(): Boolean = synchronized(lock) { files().isNotEmpty() }

    fun save(edit: Media3VideoEdit, output: File) = synchronized(lock) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Couldn’t open private export storage.")
        cleanIncomplete()
        val previous = files()
        val destination = File(directory, "$sourceKey.${editKey(edit)}.${UUID.randomUUID()}.mp4")
        try {
            DataOutputStream(recipe(destination).outputStream().buffered()).use { it.writeEdit(edit) }
            // The MP4 rename commits the pair. An interruption before it leaves the previous pair in place.
            Files.move(output.toPath(), destination.toPath(), ATOMIC_MOVE)
        } finally {
            if (!destination.isFile) recipe(destination).delete()
        }
        previous.forEach(::remove)
    }

    fun prepare(scratch: File, main10Available: Boolean, cancelled: () -> Boolean): Resume? {
        val reference = File(scratch, "resume-reference.mp4")
        val working = File(scratch, "resume-working.mp4")
        val (stored, edit, input) = synchronized(lock) {
            val file = files().maxByOrNull { recipe(it).lastModified() } ?: return null
            val saved = readEdit(file)
            if (saved.format == VideoOutput.MAIN10 && !main10Available) {
                throw IOException("This device can’t resume the saved 10-bit HEVC export.")
            }
            // An open stream keeps reading this generation if another editor replaces it.
            Triple(file, saved, file.inputStream())
        }
        try {
            input.use {
                if (input.channel.size() > scratch.usableSpace / 2) {
                    throw IOException("Free phone storage to resume this export.")
                }
                reference.outputStream().use { snapshot ->
                    working.outputStream().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            if (cancelled()) throw IOException("Resume preparation canceled.")
                            val count = input.read(buffer)
                            if (count < 0) break
                            snapshot.write(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
        } catch (error: Exception) {
            reference.delete()
            working.delete()
            throw error
        }
        return Resume(stored, reference, working, edit)
    }

    fun remove(file: File) = synchronized(lock) {
        file.delete()
        recipe(file).delete()
    }

    private fun files() = listOf(directory, legacyDirectory).flatMap { folder ->
        folder.listFiles().orEmpty().filter {
            it.name.startsWith("$sourceKey.") && it.extension == "mp4" && recipe(it).isFile
        }
    }

    private fun cleanIncomplete() {
        directory.listFiles().orEmpty().filter { it.name.startsWith("$sourceKey.") }.forEach { file ->
            if (file.extension == "mp4" && !recipe(file).isFile) file.delete()
            if (file.extension == "edit" && !File(directory, "${file.nameWithoutExtension}.mp4").isFile) file.delete()
        }
    }

    private fun recipe(file: File) = File(file.parentFile, "${file.nameWithoutExtension}.edit")

    private fun editKey(edit: Media3VideoEdit) = fingerprint { writeEdit(edit) }

    private fun DataOutputStream.writeEdit(edit: Media3VideoEdit) {
        // Change the schema when the export pipeline changes existing edit semantics.
        writeInt(1)
        writeLong(edit.trim.startMs)
        writeLong(edit.trim.endMs)
        writeUTF(edit.format.name)
        writeBoolean(edit.crop != null)
        edit.crop?.let {
            writeFloat(it.left)
            writeFloat(it.right)
            writeFloat(it.bottom)
            writeFloat(it.top)
            writeInt(it.width)
            writeInt(it.height)
        }
        writeInt(edit.lut?.size ?: 0)
        edit.lut?.rgb?.forEach(::writeFloat)
    }

    private fun readEdit(file: File): Media3VideoEdit = DataInputStream(recipe(file).inputStream().buffered()).use {
        require(it.readInt() == 1)
        val trim = TrimRange(it.readLong(), it.readLong())
        require(trim.endMs <= source.durationMs)
        val format = VideoOutput.valueOf(it.readUTF())
        val crop = if (it.readBoolean()) CropFrame(it.readFloat(), it.readFloat(), it.readFloat(), it.readFloat(),
            it.readInt(), it.readInt()).also { frame ->
            require(frame.left in -1f..1f && frame.right in -1f..1f && frame.left < frame.right)
            require(frame.bottom in -1f..1f && frame.top in -1f..1f && frame.bottom < frame.top)
            require(frame.width in 16..source.width && frame.height in 16..source.height)
        } else null
        val size = it.readInt()
        val lut = if (size == 0) null else CubeLut.fromCheckpoint(size, it::readFloat)
        val edit = Media3VideoEdit(trim, crop, lut, format)
        require(it.read() == -1 && file.name.startsWith("$sourceKey.${editKey(edit)}."))
        edit
    }

    companion object {
        private val lock = Any()
        private val discard = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
        }

        private fun fingerprint(write: DataOutputStream.() -> Unit): String {
            val digest = MessageDigest.getInstance("SHA-256")
            DataOutputStream(DigestOutputStream(discard, digest)).use(write)
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
