package app.cameraderie.gallery

import app.cameraderie.gallery.media.*
import app.cameraderie.gallery.protocol.*
import java.net.*
import java.io.*
import org.junit.Assert.*
import org.junit.Test

class GalleryHttpMediaTest {
    private val media=GalleryMediaPage.Media(GalleryMediaPage.Key(GalleryCameraEvidence.Store.INTERNAL,"DCIM/CUSTOM/FILE 1.JPG"),"MISC/THM/FILE 1.scr",12,4,0,null)
    private class Connection(val body:ByteArray=byteArrayOf(1,2,3,4),val code:Int=200,val length:Long=body.size.toLong(),val headers:Map<String,String> = emptyMap()):HttpURLConnection(URL("http://localhost/")) {
        var closed=false
        override fun getResponseCode()=code
        override fun getContentLengthLong()=length
        override fun getHeaderField(name:String)=headers[name]
        override fun getInputStream():InputStream=ByteArrayInputStream(body)
        override fun disconnect() { closed=true }
        override fun usingProxy()=false
        override fun connect() {}
    }
    private fun adapter(connection:Connection,valid:()->Boolean={true},seen:(URI)->Unit={})=GalleryHttpMedia({ uri ->
        seen(uri);object:GalleryHttpMedia.Request { override val connection=connection;override fun close() { connection.disconnect() } }
    },valid)
    @Test fun selectedStoreAndEscapedPathAddressOriginalNotThumbnail() {
        var uri:URI?=null;val conn=Connection();val out=ByteArrayOutputStream()
        val received=adapter(conn,seen={ uri=it }).original(media,out)
        assertEquals("/v2?storage=1&path=DCIM/CUSTOM/FILE%201.JPG",uri!!.toASCIIString());assertArrayEquals(conn.body,out.toByteArray())
        assertEquals(4L,received.bytes);assertEquals(64,received.sha256!!.length);assertTrue(conn.closed)
        assertEquals("identity",conn.getRequestProperty("Accept-Encoding"));assertEquals("close",conn.getRequestProperty("Connection"));assertFalse(conn.instanceFollowRedirects)
    }
    @Test fun nonFullResponsesLengthMismatchAndEncodedBodiesNeverCopy() {
        val rejected=listOf(Connection(code=206),Connection(code=302),Connection(code=404),Connection(length=5),
            Connection(headers=mapOf("Content-Range" to "bytes 0-3/4")),Connection(headers=mapOf("Content-Encoding" to "gzip")))
        for(c in rejected) {
            val out=ByteArrayOutputStream();try { adapter(c).original(media,out);fail("accepted bad original") } catch(_:IllegalArgumentException) {}
            assertEquals(0,out.size());assertTrue(c.closed)
        }
    }
    @Test fun shortAndExcessBodiesFailEvenWithoutLengthHeader() {
        val short=Connection(ByteArray(3),length=-1)
        assertThrows(EOFException::class.java) { adapter(short).original(media,ByteArrayOutputStream()) }
        assertTrue(short.closed)
        val excess=Connection(ByteArray(5),length=-1)
        assertThrows(IllegalArgumentException::class.java) { adapter(excess).original(media,ByteArrayOutputStream()) }
        assertTrue(excess.closed)
    }
    @Test fun invalidContextOrInterruptPreventsAnyNetworkOpen() {
        val c=Connection();try { adapter(c,valid={false}).original(media,ByteArrayOutputStream());fail() } catch(_:InterruptedIOException) {}
        assertFalse(c.closed)
    }
    @Test fun contextLossAfterFirstChunkAbortsAndClosesRequest() {
        val c=Connection(ByteArray(100000));var valid=true
        try { adapter(c,valid={valid}).original(media.copy(size=100000),ByteArrayOutputStream()) { _,_ -> valid=false };fail() } catch(_:InterruptedIOException) {}
        assertTrue(c.closed)
    }
    @Test fun previewUsesCameraThumbAndFallbackOnlyOnNotFound() {
        val paths=mutableListOf<String>();var count=0
        val api=GalleryHttpMedia({ uri -> paths.add(uri.toASCIIString());val c=if(count++==0)Connection(code=404)else Connection()
            object:GalleryHttpMedia.Request { override val connection=c;override fun close() { c.disconnect() } }
        },{true})
        assertEquals(4,api.preview(media)!!.size);assertEquals(2,paths.size);assertTrue(paths[0].endsWith("FILE%201.scr"));assertTrue(paths[1].endsWith("FILE%201.thm"))
    }
    @Test fun previewCannotAllocateUnboundedBody() {
        val c=Connection(length=GalleryHttpMedia.MAX_PREVIEW+1);assertNull(adapter(c).preview(media));assertTrue(c.closed)
    }
    @Test fun traversalAndCrossOriginPathsAreRejected() {
        for(path in listOf("DCIM/../file.JPG","http://example.com/x","//evil/x","DCIM/file.JPG?evil=1","DCIM/x\\x.JPG")) {
            try { GalleryHttpMedia.uri(media.copy(key=media.key.copy(path=path)),path);fail(path) } catch(_:IllegalArgumentException) {}
        }
    }

    @Test fun previewConnectionFailureFallsBackButContextCancellationDoesNot() {
        for(cancel in listOf(false,true)) {
            var opened=0;var valid=true
            val api=GalleryHttpMedia({ _ ->
                opened++
                if(opened==1) { if(cancel)valid=false;throw java.net.SocketTimeoutException() }
                val c=Connection();object:GalleryHttpMedia.Request { override val connection=c;override fun close()=c.disconnect() }
            },{valid})
            if(cancel) {
                try { api.preview(media);fail() } catch(_:InterruptedIOException) {}
                assertEquals(1,opened)
            } else { assertNotNull(api.preview(media));assertEquals(2,opened) }
        }
    }
    @Test fun explicitCancelClosesRequestAndPreventsFurtherBytes() {
        val c=Connection(ByteArray(100000));lateinit var api:GalleryHttpMedia
        api=adapter(c)
        try { api.original(media.copy(size=100000),ByteArrayOutputStream()) { _,_-> api.close() };fail() } catch(_:InterruptedIOException) {}
        assertTrue(c.closed)
    }


    @Test fun originalPublisherRequiresSupportedFormatAndMatchingManifestType() {
        assertTrue(GalleryOriginalImporter.supports(media))
        val video=media.copy(key=media.key.copy(path="DCIM/CLIP.MP4"),type=3)
        assertTrue(GalleryOriginalImporter.supports(video));assertFalse(GalleryOriginalImporter.supports(video.copy(type=0)))
        assertFalse(GalleryOriginalImporter.supports(media.copy(type=3)))
        assertTrue(GalleryOriginalImporter.supports(media.copy(key=media.key.copy(path="DCIM/FILE.DNG"))))
        for(extension in listOf("HEIC","INSV","OSV","ARW"))
            assertFalse(GalleryOriginalImporter.supports(media.copy(key=media.key.copy(path="DCIM/FILE.$extension"))))
    }

    @Test fun strongValidatorContinuesAtNonzeroOffsetAndHashesWholeFile() {
        val tag="\"version-a\""
        val c=Connection(byteArrayOf(3,4),206,headers=mapOf("Content-Range" to "bytes 2-3/4","ETag" to tag))
        val out=ByteArrayOutputStream()
        val api=adapter(c)
        val complete=api.resume(media,2,tag,{ ByteArrayInputStream(byteArrayOf(1,2)) },out,{}, {_,_->})
        assertEquals("bytes=2-3",c.getRequestProperty("Range"));assertEquals(tag,c.getRequestProperty("If-Range"))
        assertArrayEquals(byteArrayOf(3,4),out.toByteArray())
        assertEquals(adapter(Connection()).original(media,ByteArrayOutputStream()),complete)
    }

    @Test fun noValidatorComparesEveryPrefixByteFromTheSameResponse() {
        val c=Connection(code=206,headers=mapOf("Content-Range" to "bytes 0-3/4"))
        val out=ByteArrayOutputStream()
        val complete=adapter(c).resume(media,2,null,{ ByteArrayInputStream(byteArrayOf(1,2)) },out,{}, {_,_->})
        assertEquals("bytes=0-3",c.getRequestProperty("Range"));assertNull(c.getRequestProperty("If-Range"))
        assertArrayEquals(byteArrayOf(3,4),out.toByteArray())
        assertEquals(adapter(Connection()).original(media,ByteArrayOutputStream()),complete)
    }

    @Test fun changedPrefixRequiresExplicitRestartBeforeAnyAppend() {
        val c=Connection(byteArrayOf(1,9,3,4),206,headers=mapOf("Content-Range" to "bytes 0-3/4"))
        val out=ByteArrayOutputStream()
        try {
            adapter(c).resume(media,2,null,{ ByteArrayInputStream(byteArrayOf(1,2)) },out,{}, {_,_->})
            fail("appended to a changed prefix")
        } catch(_:GalleryHttpMedia.RestartRequired) { }
        assertEquals(0,out.size());assertTrue(c.closed)
    }

    @Test fun unsupportedRangeMalformedExtentAndChangedValidatorDoNotAppend() {
        val ok=mapOf("Content-Range" to "bytes 2-3/4","ETag" to "\"valid\"")
        val responses=listOf(Connection(code=200),Connection(code=416),Connection(code=302),Connection(code=206),
            Connection(byteArrayOf(3,4),206,headers=ok+ ("ETag" to "W/\"valid\"")),
            Connection(byteArrayOf(3,4),206,headers=ok+ ("ETag" to "\"different\"")),
            Connection(byteArrayOf(3,4),206,headers=ok+ ("Content-Range" to "bytes 1-3/4")),
            Connection(byteArrayOf(3,4),206,headers=ok+ ("Content-Range" to "bytes 2-3/5")),
            Connection(byteArrayOf(3,4),206,headers=ok+ ("Content-Encoding" to "gzip")),
            Connection(byteArrayOf(3,4),206,length=3,headers=ok))
        for(c in responses) {
            val out=ByteArrayOutputStream()
            try {
                adapter(c).resume(media,2,"\"valid\"",{ error("invalid response accessed local prefix") },out,{}, {_,_->})
                fail("accepted invalid response")
            } catch(_:GalleryHttpMedia.RestartRequired) { }
            assertEquals(0,out.size());assertTrue(c.closed)
        }
    }

    @Test fun interruptedPrefixVerificationCannotAppend() {
        val c=Connection(code=206,headers=mapOf("Content-Range" to "bytes 0-3/4"))
        val out=ByteArrayOutputStream();var valid=true
        try {
            adapter(c,valid={valid}).resume(media,2,null,{ ByteArrayInputStream(byteArrayOf(1,2)) },out,{}, {_,_->valid=false})
            fail("append continued after ownership was lost")
        } catch(_:InterruptedIOException) { }
        assertEquals(0,out.size());assertTrue(c.closed)
    }

    @Test fun recordedPreviewUsesSameStorageProxyAndConditionalRanges() {
        val clip = media.copy(key=media.key.copy(path="DCIM/CUSTOM/CLIP 1.MP4"),type=3)
        val etag = "\"proxy-v1\""
        val first = Connection(byteArrayOf(1,2,3,4),206,headers=mapOf(
            "Content-Range" to "bytes 0-3/6","ETag" to etag))
        val tail = Connection(byteArrayOf(5,6),206,headers=mapOf(
            "Content-Range" to "bytes 4-5/6","ETag" to etag))
        val connections = listOf(first,tail).iterator()
        val api = GalleryHttpMedia({ uri ->
            assertEquals("/v2?storage=1&path=DCIM/CUSTOM/CLIP%201.LRF",uri.toASCIIString())
            val conn = connections.next()
            object : GalleryHttpMedia.Request {
                override val connection = conn
                override fun close() = conn.disconnect()
            }
        },{ true })
        val descriptor = api.clipBlock(clip,0,4)
        assertEquals(6L,descriptor.length)
        assertArrayEquals(first.body,descriptor.bytes)
        assertArrayEquals(tail.body,api.clipBlock(clip,4,4,descriptor).bytes)
        assertEquals("bytes=4-7",tail.getRequestProperty("Range"))
        assertEquals(etag,tail.getRequestProperty("If-Range"))
        assertTrue(first.closed && tail.closed)
    }

    @Test fun recordedPreviewRejectsChangedOrUnboundedResponsesBeforeReading() {
        val clip = media.copy(key=media.key.copy(path="DCIM/CLIP.MOV"),type=3)
        val expected = GalleryHttpMedia.ClipBlock(byteArrayOf(),6,"\"stable\"")
        val headers = mapOf("Content-Range" to "bytes 4-5/6","ETag" to "\"stable\"")
        val responses = listOf(
            Connection(code=200,headers=headers),
            Connection(code=206,headers=headers+("ETag" to "W/\"stable\"")),
            Connection(code=206,headers=headers+("ETag" to "\"changed\"")),
            Connection(code=206,headers=headers+("Content-Range" to "bytes 4-6/7")),
            Connection(code=206,headers=headers+("Content-Encoding" to "gzip")),
            Connection(code=206,headers=headers,length=4)
        )
        for(response in responses) {
            assertThrows(IOException::class.java) { adapter(response).clipBlock(clip,4,4,expected) }
            assertTrue(response.closed)
        }
    }

    @Test fun companionUsesRevalidatedParentStemAndStoreWithAnExactBody() {
        val clip = media.copy(key=GalleryMediaPage.Key(GalleryCameraEvidence.Store.SD,"DCIM/A/VIDEO 2.MP4"),type=3)
        val wav = pcmWave()
        val response = Connection(wav)
        var requested: URI? = null
        val output = ByteArrayOutputStream()
        val result = adapter(response,seen={ requested=it }).companionFile(clip,GalleryCompanion.WAV,{_,_->}) { length,copy ->
            assertEquals(wav.size.toLong(),length)
            copy(output)
        }
        assertEquals("/v2?storage=0&path=DCIM/A/VIDEO%202.WAV",requested.toString())
        assertEquals(wav.size.toLong(),result!!.bytes)
        assertArrayEquals(wav,output.toByteArray())
        GalleryCompanion.WAV.verify(output.toByteArray().inputStream(),result.bytes)
        assertTrue(response.closed)
    }

    @Test fun companionMissingAndInvalidLengthsNeverStartPublication() {
        val absent = Connection(code=404)
        assertNull(adapter(absent).companionFile(media,GalleryCompanion.DNG,{_,_->}) { _,_ -> fail(); 1 })
        assertTrue(absent.closed)
        for(response in listOf(Connection(length=-1),Connection(code=206),Connection(headers=mapOf("Content-Encoding" to "gzip")))) {
            assertThrows(IOException::class.java) {
                adapter(response).companionFile(media,GalleryCompanion.DNG,{_,_->}) { _,_ -> fail() }
            }
            assertTrue(response.closed)
        }
    }

    @Test fun oversizedCompanionIsRejectedBeforeOpeningTheDestination() {
        val video = media.copy(key = media.key.copy(path = "DCIM/CLIP.MP4"), type = 3)
        for ((parent, kind) in listOf(media to GalleryCompanion.DNG, video to GalleryCompanion.WAV)) {
            val response = Connection(length = kind.maxBytes + 1)
            assertThrows(GalleryCompanion.TooLarge::class.java) {
                adapter(response).companionFile(parent, kind, { _, _ -> }) { _, _ -> fail() }
            }
            assertTrue(response.closed)
        }
    }

    @Test fun dngValidationReadsPrimaryTiffVersionWithoutAnImageDecoder() {
        for(order in listOf(java.nio.ByteOrder.LITTLE_ENDIAN,java.nio.ByteOrder.BIG_ENDIAN)) {
            val bytes = java.nio.ByteBuffer.allocate(26).order(order).apply {
                putShort(if(order==java.nio.ByteOrder.LITTLE_ENDIAN)0x4949 else 0x4d4d)
                putShort(42);putInt(8);putShort(1)
                putShort(50706.toShort());putShort(1);putInt(4);put(byteArrayOf(1,4,0,0))
                putInt(0)
            }.array()
            GalleryCompanion.DNG.verify(bytes.inputStream(),bytes.size.toLong())
            assertThrows(IllegalStateException::class.java) {
                GalleryCompanion.DNG.verify(bytes.copyOf().also { it[18]=0 }.inputStream(),bytes.size.toLong())
            }
        }
    }

    @Test fun fullResolutionExtractionRequestsOnlyTheListedOriginalAndChecksItsSize() {
        val clip=media.copy(key=GalleryMediaPage.Key(GalleryCameraEvidence.Store.SD,"DCIM/CLIP 1.MP4"),type=3)
        val bytes=byteArrayOf(1,2,3,4)
        val headers=mapOf("Content-Range" to "bytes 0-3/4","ETag" to "\"original\"")
        val response=Connection(bytes,206,headers=headers)
        val api=adapter(response,seen={ assertEquals("/v2?storage=0&path=DCIM/CLIP%201.MP4",it.toASCIIString()) })
        assertArrayEquals(bytes,api.clipBlock(clip,0,4,original=true).bytes)
        assertThrows(IOException::class.java) {
            adapter(Connection(bytes,206,headers=headers)).clipBlock(clip.copy(size=8),0,4,original=true)
        }
    }
}
