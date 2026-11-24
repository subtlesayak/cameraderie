package app.cameraderie.gallery.media

import app.cameraderie.gallery.protocol.GalleryCameraEvidence.Store
import app.cameraderie.gallery.protocol.GalleryMediaPage
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

class GalleryHttpSocketTest {
    @get:Rule val temp = TemporaryFolder()

    private val original = ByteArray(256 * 1024) { (it xor (it shr 8) xor (it shr 16)).toByte() }
    private val media = GalleryMediaPage.Media(GalleryMediaPage.Key(Store.SD, "DCIM/CAMERA CLIP.MP4"),
        "MISC/CAMERA.scr", 1, original.size.toLong(), 3, null)
    private val job = GalleryDownloadQueue.Job(UUID.randomUUID().toString(), "test-camera", GalleryDownloadQueue.source(media))
    private val tag = "\"original-a\""

    @Test fun interruptedResponseResumesFromThePersistedPrefix() = MockWebServer().use { server ->
        val root = temp.newFolder()
        val saved = interrupt(server, root, tag)
        val suffix = original.copyOfRange(saved.size, original.size)
        server.enqueue(rangeResponse(saved.size, suffix).addHeader("ETag", tag).apply {
            socketPolicy = SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY
        })
        socketMedia(server).use { http ->
            assertThrows(IOException::class.java) {
                GalleryPartialDownloads(root).prepare(job, media, http, { false }) { _, _ -> }
            }
        }
        assertConditionalRange(server, saved.size)
        val next = root.resolve("${job.id}.part").readBytes()
        assertTrue(next.size in (saved.size + 1) until original.size)
        assertArrayEquals(original.copyOf(next.size), next)
        server.enqueue(rangeResponse(next.size, original.copyOfRange(next.size, original.size)).addHeader("ETag", tag))
        val staged = socketMedia(server).use { http ->
            GalleryPartialDownloads(root).prepare(job, media, http, { false }) { _, _ -> }
        }
        assertEquals(next.size.toLong(), staged.rangeStart)
        assertConditionalRange(server, next.size)
        assertCompleted(staged)
    }

    @Test fun interruptedResponseWithoutStrongValidatorRechecksRemoteBytesBeforeAppending() = MockWebServer().use { server ->
        val root = temp.newFolder()
        val saved = interrupt(server, root, "W/$tag")
        assertFalse(root.resolve("${job.id}.receipt").exists())
        val changed = original.clone().apply { this[saved.lastIndex] = (this[saved.lastIndex].toInt() xor 1).toByte() }
        server.enqueue(rangeResponse(0, changed))
        socketMedia(server).use { http ->
            assertThrows(GalleryHttpMedia.RestartRequired::class.java) {
                GalleryPartialDownloads(root).prepare(job, media, http, { false }) { _, _ -> }
            }
        }
        val rejected = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("bytes=0-${original.lastIndex}", rejected.getHeader("Range"))
        assertNull(rejected.getHeader("If-Range"))
        assertArrayEquals(saved, root.resolve("${job.id}.part").readBytes())

        server.enqueue(rangeResponse(0, original))
        val staged = socketMedia(server).use { http ->
            GalleryPartialDownloads(root).prepare(job, media, http, { false }) { _, _ -> }
        }
        assertEquals(0L, staged.rangeStart)
        assertCompleted(staged)
    }

    @Test fun rejectsMalformedRangeHeadersWithoutChangingTheCheckpoint() = MockWebServer().use { server ->
        val root = temp.newFolder()
        val saved = interrupt(server, root, tag)
        val checkpoint = root.resolve("${job.id}.part")
        val receipt = root.resolve("${job.id}.receipt")
        val originalReceipt = receipt.readBytes()
        val suffix = original.copyOfRange(saved.size, original.size)
        val goodRange = "bytes ${saved.size}-${original.lastIndex}/${original.size}"
        val malformed = listOf(
            rangeResponse(saved.size, suffix).setHeader("Content-Range", "bytes ${saved.size}-${original.lastIndex}/*")
                .addHeader("ETag", tag),
            rangeResponse(saved.size, suffix).setHeader("Content-Range", "bytes ${saved.size}-${original.lastIndex}/${original.size + 1}")
                .addHeader("ETag", tag),
            rangeResponse(saved.size, suffix).removeHeader("Content-Range")
                .addHeader("Content-Range", "bytes 0-${original.lastIndex}/${original.size}")
                .addHeader("Content-Range", goodRange).addHeader("ETag", tag),
            rangeResponse(saved.size, suffix).addHeader("ETag", "\"original-b\"").addHeader("ETag", tag),
            rangeResponse(saved.size, suffix.copyOf(suffix.size - 1)).addHeader("ETag", tag),
            MockResponse().setResponseCode(200).setBody(Buffer().write(original)).addHeader("ETag", tag)
        )
        for ((index, response) in malformed.withIndex()) {
            server.enqueue(response)
            socketMedia(server).use { http ->
                assertThrows("Malformed response ${index + 1}", GalleryHttpMedia.RestartRequired::class.java) {
                    GalleryPartialDownloads(root).prepare(job, media, http, { false }) { _, _ -> }
                }
            }
            assertConditionalRange(server, saved.size)
            assertArrayEquals(saved, checkpoint.readBytes())
            assertArrayEquals(originalReceipt, receipt.readBytes())
        }
    }

    private fun interrupt(server: MockWebServer, root: File, etag: String?): ByteArray {
        server.enqueue(MockResponse().setBody(Buffer().write(original)).apply {
            etag?.let { addHeader("ETag", it) }
            socketPolicy = SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY
        })
        socketMedia(server).use { http ->
            assertThrows(IOException::class.java) {
                GalleryPartialDownloads(root).prepare(job, media, http, { false }) { _, _ -> }
            }
        }
        val request = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("/v2?storage=${media.key.store.protocolId}&path=DCIM/CAMERA%20CLIP.MP4", request.path)
        assertEquals("identity", request.getHeader("Accept-Encoding"))
        assertNull(request.getHeader("Range"))
        val saved = root.resolve("${job.id}.part").readBytes()
        assertTrue(saved.isNotEmpty() && saved.size < original.size)
        assertArrayEquals(original.copyOf(saved.size), saved)
        return saved
    }

    private fun assertConditionalRange(server: MockWebServer, offset: Int) {
        val request = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("bytes=$offset-${original.lastIndex}", request.getHeader("Range"))
        assertEquals(tag, request.getHeader("If-Range"))
    }

    private fun rangeResponse(start: Int, bytes: ByteArray) = MockResponse().setResponseCode(206)
        .setBody(Buffer().write(bytes)).addHeader("Content-Range", "bytes $start-${original.lastIndex}/${original.size}")

    private fun socketMedia(server: MockWebServer) = GalleryHttpMedia({ uri ->
        val connection = server.url(uri.toASCIIString()).toUrl().openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        object : GalleryHttpMedia.Request {
            override val connection = connection
            override fun close() = connection.disconnect()
        }
    }, { true })

    private fun assertCompleted(staged: GalleryPartialDownloads.Staged) {
        val bytes = ByteArrayOutputStream()
        val copied = staged.copyTo(bytes) { false }
        assertArrayEquals(original, bytes.toByteArray())
        assertEquals(original.size.toLong(), copied.bytes)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }, copied.sha256)
    }
}
