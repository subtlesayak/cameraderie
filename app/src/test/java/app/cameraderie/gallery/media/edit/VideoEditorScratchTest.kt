package app.cameraderie.gallery.media.edit

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoEditorScratchTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun reclaimsAbandonedCopiesWithoutDeletingAnotherEditorsFiles() {
        val orphan = temp.newFolder("video-edit-101")
        File(orphan, "original").writeText("abandoned")
        val other = temp.newFolder("video-edit-other")

        val first = VideoEditorScratch.create(temp.root)
        first.use {
            val original = File(first.directory, "original").apply { writeText("in use") }
            assertFalse(orphan.exists())
            VideoEditorScratch.create(temp.root).use { second ->
                assertTrue(second.directory.isDirectory)
                assertTrue(original.isFile)
            }
            assertTrue(original.isFile)
            assertTrue(other.isDirectory)
        }
        assertFalse(first.directory.exists())
    }
}
