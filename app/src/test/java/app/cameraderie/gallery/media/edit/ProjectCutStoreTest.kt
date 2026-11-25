package app.cameraderie.gallery.media.edit

import app.cameraderie.gallery.media.history.ProjectExportOrigin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectCutStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun cutFollowsExactOriginalWithinAProjectAndCanBeReplaced() {
        val origin = ProjectExportOrigin("project", "departure", "import-1", "a".repeat(64))
        val look = CubeLut.fromCheckpoint(2) { 0.25f }
        val crop = CropFrame(-0.5f, 0.5f, -1f, 1f, 320, 360)
        val edit = Media3VideoEdit(TrimRange(250, 2100), crop, look, VideoOutput.MAIN10, origin.projectId)
        ProjectCutStore(folder.root).save(origin, edit)

        val moved = origin.copy(shootId = "mountain-pass")
        val store = ProjectCutStore(folder.root)
        val saved = checkNotNull(store.read(moved, 640, 360, 5000))
        assertEquals(edit.trim, saved.trim)
        assertEquals(edit.crop, saved.crop)
        assertEquals(edit.format, saved.format)
        assertArrayEquals(look.rgb, saved.lut?.rgb, 0f)
        assertNull(store.read(moved.copy(projectId = "another-project"), 640, 360, 5000))
        assertNull(store.read(moved.copy(sourceImportId = "import-2"), 640, 360, 5000))
        assertNull(store.read(moved.copy(sourceSha256 = "b".repeat(64)), 640, 360, 5000))

        val updated = edit.copy(trim = TrimRange(0, 3500), lut = null)
        store.save(moved, updated)
        assertEquals(updated, ProjectCutStore(folder.root).read(origin, 640, 360, 5000))
    }
}
