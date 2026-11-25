package app.cameraderie.gallery.media.edit

import java.io.DataInputStream
import java.io.DataOutputStream

/** Binary edit settings shared by export checkpoints and saved project cuts. */
internal object VideoEditRecipe {
    fun write(output: DataOutputStream, edit: Media3VideoEdit) = with(output) {
        // Change the schema when the export pipeline changes existing edit semantics.
        writeInt(if (edit.projectId == null) 1 else 2)
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
        edit.projectId?.let(::writeUTF)
        Unit
    }


    fun read(input: DataInputStream, width: Int, height: Int, durationMs: Long): Media3VideoEdit = with(input) {
        val version = readInt()
        require(version in 1..2)
        val trim = TrimRange(readLong(), readLong())
        require(trim.endMs <= durationMs)
        val format = VideoOutput.valueOf(readUTF())
        val crop = if (readBoolean()) CropFrame(readFloat(), readFloat(), readFloat(), readFloat(),
            readInt(), readInt()).also { frame ->
            require(frame.left in -1f..1f && frame.right in -1f..1f && frame.left < frame.right)
            require(frame.bottom in -1f..1f && frame.top in -1f..1f && frame.bottom < frame.top)
            require(frame.width in 16..width && frame.height in 16..height)
        } else null
        val size = readInt()
        val lut = if (size == 0) null else CubeLut.fromCheckpoint(size, ::readFloat)
        val projectId = if (version == 2) readUTF().also { require(it.isNotBlank()) } else null
        val edit = Media3VideoEdit(trim, crop, lut, format, projectId)
        require(read() == -1)
        edit
    }
}
