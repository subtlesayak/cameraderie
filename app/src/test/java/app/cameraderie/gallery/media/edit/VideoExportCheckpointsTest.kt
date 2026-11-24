package app.cameraderie.gallery.media.edit

import app.cameraderie.gallery.media.history.ImportHistoryDraft
import app.cameraderie.gallery.media.history.ImportHistoryEntry
import app.cameraderie.gallery.media.history.ImportHistoryReceipt
import app.cameraderie.gallery.media.history.ImportHistoryStage
import app.cameraderie.gallery.media.history.ImportProvenance
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoExportCheckpointsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun storageRefusalLeavesThePauseAvailableForRetry() {
        val fixture = Fixture()
        fixture.store().save(fixture.edit, fixture.newOutput())
        val scratch = object : File(fixture.scratch.path) {
            override fun getUsableSpace() = fixture.checkpoint.size * 2L - 1L
        }

        val error = assertThrows(IOException::class.java) {
            fixture.store().prepare(scratch, main10Available = false) { false }
        }
        assertEquals("Free phone storage to resume this export.", error.message)
        fixture.assertScratchClean()

        fixture.assertPrepared(fixture.edit)
    }

    @Test fun interruptedResumeCopyRemovesBothPartialFilesAndKeepsThePause() {
        val fixture = Fixture()
        fixture.store().save(fixture.edit, fixture.newOutput())
        var polls = 0
        val error = assertThrows(IOException::class.java) {
            fixture.store().prepare(fixture.scratch, main10Available = false) {
                polls++
                polls == 2
            }
        }
        assertEquals("Resume preparation canceled.", error.message)
        assertEquals(2, polls)
        fixture.assertScratchClean()
        fixture.assertPrepared(fixture.edit)
    }

    @Test fun failedReplacementKeepsPriorTrimAndCheckpoint() {
        val fixture = Fixture()
        fixture.store().save(fixture.edit, fixture.newOutput())
        val replacement = fixture.edit.copy(trim = TrimRange(4_300, 5_100))
        val missingOutput = File(fixture.scratch, "missing.mp4")

        assertThrows(IOException::class.java) { fixture.store().save(replacement, missingOutput) }
        assertEquals(2, File(fixture.privateDir, "video-export-checkpoints").listFiles()?.size)

        fixture.assertPrepared(fixture.edit)
    }

    @Test fun pausedProjectExportKeepsItsOriginWhenTheEditorReopens() {
        val fixture = Fixture()
        val edit = fixture.edit.copy(projectId = "malaysian-diaries")
        fixture.store().save(edit, fixture.newOutput())
        fixture.assertPrepared(edit)
    }

    private inner class Fixture {
        val privateDir = temp.newFolder("private")
        private val cache = temp.newFolder("cache")
        val scratch = temp.newFolder("scratch")
        val checkpoint = ByteArray(300_000) { (it % 251).toByte() }
        val edit = Media3VideoEdit(TrimRange(2_300, 8_100), CropSelection(0f, 0f, 0.8f, 1f).frame(640, 360), null)
        private val originalBytes = "verified source bytes".encodeToByteArray()
        private val originalFile = File(privateDir, "original.mp4").apply { writeBytes(originalBytes) }
        private val receipt = ImportHistoryReceipt(
            originalBytes.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(originalBytes).joinToString("") { "%02x".format(it.toInt() and 255) },
            "video/mp4", true,
        )
        private val entry = ImportHistoryEntry(
            ImportHistoryDraft("original", "content://media/external_primary/video/media/42", originalFile.name,
                receipt.bytes, 1, ImportProvenance.DOCUMENT_BYTES),
            ImportHistoryStage.COMPLETE, receipt,
        )
        private val source = VerifiedVideo(entry, originalFile, 10_000, 640, 360, true, CameraLutGuide(null, null), true)

        fun store() = VideoExportCheckpoints(privateDir, cache, source)

        fun newOutput() = File(scratch, "paused.mp4").apply { writeBytes(checkpoint) }

        fun assertScratchClean() {
            assertEquals(emptyList<String>(), scratch.list()?.toList())
            assertTrue(store().exists())
        }

        fun assertPrepared(expected: Media3VideoEdit) {
            val reopened = store()
            val saved = checkNotNull(reopened.prepare(scratch, main10Available = false) { false })
            assertEquals(expected, saved.edit)
            for (file in listOf(saved.stored, saved.reference, saved.working)) {
                assertArrayEquals(checkpoint, file.readBytes())
            }
            saved.working.writeText("mux work")
            assertArrayEquals(checkpoint, saved.stored.readBytes())
            assertArrayEquals(checkpoint, saved.reference.readBytes())
            assertArrayEquals(originalBytes, originalFile.readBytes())
            reopened.remove(saved.stored)
            assertFalse(reopened.exists())
        }
    }
}
