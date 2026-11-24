package app.cameraderie.gallery.media

import android.media.MediaDataSource
import app.cameraderie.gallery.protocol.GalleryMediaPage

/** Reads a recorded video through a small, bounded cache without saving the clip. */
internal class GalleryClipDataSource(
    private val http: GalleryHttpMedia,
    private val media: GalleryMediaPage.Media,
    private val original:Boolean=false
) : MediaDataSource() {
    private val blocks = LinkedHashMap<Long, ByteArray>(4, 0.75f, true)
    private var first: GalleryHttpMedia.ClipBlock? = null

    fun checkCurrent() = http.checkCurrent()

    @Synchronized
    override fun getSize(): Long {
        checkCurrent()
        return open().length
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        require(position >= 0 && offset in 0..buffer.size && size in 0..buffer.size-offset)
        checkCurrent()
        if(size == 0) return 0
        val descriptor = open()
        if(position >= descriptor.length) return -1
        val blockSize = GalleryHttpMedia.PROXY_BLOCK_SIZE
        val start = position / blockSize * blockSize
        val bytes = blocks[start] ?: http.clipBlock(media,start,blockSize,descriptor,original).bytes.also {
            blocks[start] = it
            if(blocks.size > 3) blocks.remove(blocks.keys.first())
        }
        checkCurrent()
        val index = (position-start).toInt()
        val count = minOf(size,bytes.size-index)
        bytes.copyInto(buffer,offset,index,index+count)
        return count
    }

    private fun open(): GalleryHttpMedia.ClipBlock {
        return first ?: http.clipBlock(media,0,GalleryHttpMedia.PROXY_BLOCK_SIZE,null,original).also {
            first = it
            blocks[0] = it.bytes
        }
    }

    // Closing must be able to interrupt an HTTP read that holds the monitor.
    override fun close() = http.close()
}
