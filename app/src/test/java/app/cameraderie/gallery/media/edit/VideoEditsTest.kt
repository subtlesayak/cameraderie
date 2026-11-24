package app.cameraderie.gallery.media.edit

import app.cameraderie.gallery.media.history.ImportHistoryDraft
import app.cameraderie.gallery.media.history.ImportHistoryEntry
import app.cameraderie.gallery.media.history.ImportHistoryReceipt
import app.cameraderie.gallery.media.history.ImportHistoryStage
import app.cameraderie.gallery.media.history.ImportProvenance
import org.junit.Assert.*
import org.junit.Test

class VideoEditsTest {
    @Test fun trimRejectsNegativeReversedAndEmptyRanges() {
        assertThrows(IllegalArgumentException::class.java) { TrimRange(-1, 2000) }
        assertThrows(IllegalArgumentException::class.java) { TrimRange(1000, 999) }
        assertThrows(IllegalArgumentException::class.java) { TrimRange(1000, 1000) }
    }

    @Test fun boxUsesDisplayCoordinatesAndEvenExportDimensions() {
        val frame = requireNotNull(CropSelection(0.10f, 0.20f, 0.95f, 1f).frame(640, 360))
        assertEquals(-0.8f, frame.left, 0.001f)
        assertEquals(0.9f, frame.right, 0.001f)
        assertEquals(-1f, frame.bottom, 0.001f)
        assertEquals(0.6f, frame.top, 0.001f)
        val odd = requireNotNull(CropSelection(0f, 0.01f, 0.99f, 1f).frame(641, 361))
        assertEquals(634, odd.width)
        assertEquals(356, odd.height)
        assertNull(CropSelection.FULL.frame(641, 361))
    }

    @Test fun movingTheBoxKeepsItsSizeAndStopsAtTheVideoEdge() {
        val box = CropSelection(0.25f, 0.25f, 0.75f, 0.75f)
        assertEquals(CropSelection(0.5f, 0f, 1f, 0.5f),
            box.adjust(CropHandle.MOVE, 1f, -1f, 0.2f, 0.2f))
    }

    @Test fun movingACornerFixesTheOppositeCornerAndPreventsCollapse() {
        val box = CropSelection(0.25f, 0.25f, 0.75f, 0.75f)
        assertEquals(CropSelection(0.5f, 0.5f, 0.75f, 0.75f),
            box.adjust(CropHandle.TOP_LEFT, 0.9f, 0.9f, 0.25f, 0.25f))
        assertEquals(CropSelection(0.25f, 0.25f, 1f, 1f),
            box.adjust(CropHandle.BOTTOM_RIGHT, 0.9f, 0.9f, 0.25f, 0.25f))
        assertEquals(box, CropSelection.restore(box.savedValues()))
        assertEquals(CropSelection.FULL, CropSelection.restore(floatArrayOf(0.9f, 0f, 0.1f, 1f)))
    }

    @Test fun onlyVerifiedPublishedOriginalVideoReceiptsCanOpenTheEditor() {
        val draft = ImportHistoryDraft("original", "content://media/external_primary/video/media/42", "clip.mp4",
            200, 1, ImportProvenance.OFFERED_CAMERA_BYTES)
        val receipt = ImportHistoryReceipt(200, "a".repeat(64), "video/mp4", false)
        val entry = ImportHistoryEntry(draft, ImportHistoryStage.COMPLETE, receipt)
        assertTrue(VerifiedVideo.supports(entry))
        assertTrue(VerifiedVideo.supports(entry.copy(draft = draft.copy(provenance = ImportProvenance.DOCUMENT_BYTES))))
        assertFalse(VerifiedVideo.supports(entry.copy(draft = draft.copy(offeredReduced = true))))
        assertFalse(VerifiedVideo.supports(entry.copy(stage = ImportHistoryStage.VERIFIED)))
        assertFalse(VerifiedVideo.supports(entry.copy(receipt = receipt.copy(sha256 = null))))
        assertFalse(VerifiedVideo.supports(entry.copy(receipt = receipt.copy(bytes = 201))))
        assertFalse(VerifiedVideo.supports(entry.copy(draft = draft.copy(provenance = ImportProvenance.EDITED_FROM_ORIGINAL,
            videoSource = "source.mp4", trimStartUs = 0, trimEndUs = 1000))))
    }
}
