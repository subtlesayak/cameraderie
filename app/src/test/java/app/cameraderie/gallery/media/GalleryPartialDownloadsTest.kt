package app.cameraderie.gallery.media

import app.cameraderie.gallery.protocol.GalleryCameraEvidence.Store
import app.cameraderie.gallery.protocol.GalleryMediaPage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GalleryPartialDownloadsTest {
    @get:Rule val temp=TemporaryFolder()
    private val contents=ByteArray(150_000) { ((it xor (it shr 8)) and 255).toByte() }
    private val media=GalleryMediaPage.Media(GalleryMediaPage.Key(Store.SD,"DCIM/CLIP.MP4"),
        "MISC/CLIP.scr",1,contents.size.toLong(),3,null)
    private val job=GalleryDownloadQueue.Job(UUID.randomUUID().toString(),"test-camera",GalleryDownloadQueue.source(media))
    private val tag="\"version-a\""
    private val boundary=65_536

    private class Reply(private val body:ByteArray,private val status:Int,private val range:String?,
        private val etag:String?):HttpURLConnection(URL("http://camera.test/")) {
        override fun getResponseCode()=status
        override fun getContentLengthLong()=body.size.toLong()
        override fun getInputStream()=ByteArrayInputStream(body)
        override fun getHeaderField(name:String)=when(name) { "Content-Range" -> range;"ETag" -> etag;else -> null }
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy()=false
    }
    private fun http(c:Reply,valid:()->Boolean={true})=GalleryHttpMedia({
        object:GalleryHttpMedia.Request { override val connection=c;override fun close()=c.disconnect() }
    },valid)
    private fun beginPaused(dir:File,tag:String?) {
        var paused=false
        try {
            GalleryPartialDownloads(dir).prepare(job,media,http(Reply(contents,200,null,tag)){ !paused },{ paused }) { _,_->paused=true }
            fail("download continued after pause")
        } catch(_:InterruptedIOException) { }
        assertEquals(boundary.toLong(),File(dir,"${job.id}.part").length())
    }
    @Test fun persistedPauseResumesOnlyRemainingBytesWithStrongValidator() {
        val dir=temp.newFolder()
        beginPaused(dir,tag)
        val suffix=Reply(contents.copyOfRange(boundary,contents.size),206,"bytes $boundary-${contents.lastIndex}/${contents.size}",tag)
        val staged=GalleryPartialDownloads(dir).prepare(job,media,http(suffix),{false}) { _,_-> }
        assertEquals(boundary.toLong(),staged.rangeStart)
        assertEquals("bytes=$boundary-${contents.lastIndex}",suffix.getRequestProperty("Range"))
        val out=ByteArrayOutputStream();val copied=staged.copyTo(out){false}
        assertArrayEquals(contents,out.toByteArray());assertEquals(contents.size.toLong(),copied.bytes)
        val same=GalleryPartialDownloads(dir).prepare(job,media,GalleryHttpMedia({error("verified staging already exists")},{true}),{false}) { _,_-> }
        assertNull(same.rangeStart);assertEquals(copied,same.copyTo(ByteArrayOutputStream()){false})
        GalleryPartialDownloads(dir).discard(job.id)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }
    @Test fun processDeathWithoutValidatorRequiresExactRemotePrefix() {
        val dir=temp.newFolder()
        beginPaused(dir,null)
        assertFalse(File(dir,"${job.id}.receipt").exists())
        val full=Reply(contents,206,"bytes 0-${contents.lastIndex}/${contents.size}",null)
        val staged=GalleryPartialDownloads(dir).prepare(job,media,http(full),{false}) { _,_-> }
        assertEquals(0L,staged.rangeStart);assertEquals(boundary.toLong(),staged.savedBytes)
        val out=ByteArrayOutputStream();staged.copyTo(out){false}
        assertArrayEquals(contents,out.toByteArray())
    }
    @Test fun aCorruptedSavedCheckpointCannotAuthorizeAConditionalRange() {
        val dir=temp.newFolder();beginPaused(dir,tag)
        val file=File(dir,"${job.id}.part")
        java.io.RandomAccessFile(file,"rw").use { it.seek(700);it.write(contents[700].toInt() xor 1) }
        val same=Reply(contents,206,"bytes 0-${contents.lastIndex}/${contents.size}",tag)
        try {
            GalleryPartialDownloads(dir).prepare(job,media,http(same),{false}) { _,_-> }
            fail("continued with damaged saved bytes")
        } catch(_:GalleryHttpMedia.RestartRequired) { }
        assertEquals("bytes=0-${contents.lastIndex}",same.getRequestProperty("Range"));assertNull(same.getRequestProperty("If-Range"))
        assertEquals(boundary.toLong(),file.length())
    }
    @Test fun inadequateSpaceDoesNotStartNetworkOrWriteBytes() {
        val dir=temp.newFolder(); val large=media.copy(size=dir.usableSpace+1)
        val work=job.copy(source=GalleryDownloadQueue.source(large))
        try {
            GalleryPartialDownloads(dir).prepare(work,large,GalleryHttpMedia({error("opened network")},{true}),{false}) { _,_-> }
            fail("insufficient space allowed streaming")
        } catch(_:GalleryPartialDownloads.NoSpace) { }
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }
    @Test fun processRecoveryPurgesTerminalJobsOnly() {
        val dir=temp.newFolder();beginPaused(dir,null)
        val other=UUID.randomUUID().toString();File(dir,"$other.part").writeBytes(byteArrayOf(1))
        GalleryPartialDownloads(dir).removeUnused(setOf(job.id))
        assertTrue(File(dir,"${job.id}.part").exists());assertFalse(File(dir,"$other.part").exists())
    }
}
