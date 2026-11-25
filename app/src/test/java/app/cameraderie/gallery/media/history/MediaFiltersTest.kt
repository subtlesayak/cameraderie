package app.cameraderie.gallery.media.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFiltersTest {
    @Test fun combinesCameraCaptureDateFormatAndShoot() {
        val original = entry("camera-image", "Departure.JPG", "image/jpeg", "SONY ILCE-6400", "2026-11-24", 'a')
        val facets = MediaFacets.from(original, original.draft.displayName, setOf("day-one", "another-project"))
        val filters = MediaFilters("ilce-6400", "2026-11-24", "JPEG", "day-one")
        assertEquals("JPEG", facets.extension)
        assertTrue(filters.matches(facets))
        assertTrue(filters.copy(shoot = "another-project").matches(facets))
        assertTrue(filters.copy(format = MediaFilters.groupKey(MediaGroup.Image)).matches(facets))
        assertFalse(filters.copy(camera = "another camera").matches(facets))
        assertFalse(filters.copy(date = "2026-11-25").matches(facets))
        assertFalse(filters.copy(format = "MP4").matches(facets))
        assertFalse(filters.copy(shoot = "day-two").matches(facets))
        val selected = filters.copy(selectedTakesOnly = true)
        assertEquals(5, selected.count)
        assertFalse(selected.matches(facets))
        assertTrue(selected.matches(facets.copy(selectedTake = true)))
        assertFalse(selected.matches(facets.copy(selectedTake = true, shootIds = setOf("day-two"))))
    }

    @Test fun unknownsCanBeSelectedAndOpaque360FilesStayFindableByFormat() {
        val original = entry("camera-file", "Route.insv", "application/octet-stream", null, null, 'a')
        val facets = MediaFacets.from(original, original.draft.displayName, emptySet())
        assertTrue(MediaFilters(camera = "", date = "", format = "INSV", shoot = "").matches(facets))
        assertTrue(MediaFilters(format = MediaFilters.groupKey(MediaGroup.Insta360)).matches(facets))
        assertFalse(MediaFilters(format = MediaFilters.groupKey(MediaGroup.Video)).matches(facets))
        assertFalse(MediaFilters(shoot = "day-one").matches(facets))
    }

    @Test fun exportUsesOnlyTheExactCompletedSourceReceiptForCameraAndDate() {
        val source = entry("source", "Ride.MOV", "video/quicktime", "DJI Osmo Action 6", "2026-11-24", 'a')
        val normal = entry("export", "Ride Trim.MP4", "video/mp4", null, null, 'b')
        val exported = normal.copy(draft = normal.draft.copy(
            provenance = ImportProvenance.EDITED_FROM_ORIGINAL,
            videoSource = source.draft.displayName,
            trimStartUs = 1_000, trimEndUs = 2_000,
            projectExport = ProjectExportOrigin("project", "shoot", source.draft.id, "a".repeat(64)),
        ))
        val selected = MediaFilters("dji osmo action 6", "2026-11-24", "MP4", "shoot")
        assertTrue(selected.matches(MediaFacets.forExport(exported, mapOf(source.draft.id to source), setOf("shoot"))))
        val wrongHash = source.copy(receipt = checkNotNull(source.receipt).copy(sha256 = "c".repeat(64)))
        for (unavailable in listOf(wrongHash, source.copy(stage = ImportHistoryStage.VERIFIED))) {
            val facts = MediaFacets.forExport(exported, mapOf(source.draft.id to unavailable), setOf("shoot"))
            assertFalse(selected.matches(facts))
            assertTrue(MediaFilters(camera = "", date = "", format = "MP4", shoot = "shoot").matches(facts))
        }
    }

    private fun entry(id: String, name: String, mime: String, camera: String?, date: String?, hash: Char): ImportHistoryEntry {
        return ImportHistoryEntry(
            ImportHistoryDraft(id, "content://media/external_primary/file/1", name, 12, 1,
                ImportProvenance.DOCUMENT_BYTES, cameraModel = camera),
            ImportHistoryStage.COMPLETE,
            ImportHistoryReceipt(12, hash.toString().repeat(64), mime, true, captureDate = date),
        )
    }
}
