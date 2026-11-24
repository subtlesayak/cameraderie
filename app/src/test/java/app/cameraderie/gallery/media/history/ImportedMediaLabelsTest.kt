package app.cameraderie.gallery.media.history

import org.junit.Assert.*
import org.junit.Test

class ImportedMediaLabelsTest {
    private fun entry(provenance:ImportProvenance,quality:String?=null,mime:String="image/jpeg",kind:ImportCompletionKind=ImportCompletionKind.OBSERVED_PUBLICATION)=ImportHistoryEntry(
        ImportHistoryDraft("test","content://media/external_primary/file/1","example_2M.JPG",10,if(provenance==ImportProvenance.LEGACY_UNKNOWN)null else 1,provenance,quality),ImportHistoryStage.COMPLETE,
        ImportHistoryReceipt(10,null,mime,false,captureDate="2026-06-10"),42,kind)
    @Test fun legacyFilenameNeverEstablishesTwoMegapixelQuality() {
        val text=ImportedMediaLabels.representation(entry(ImportProvenance.LEGACY_UNKNOWN))
        assertTrue(text.contains("unknown"));assertFalse(text.contains("2M JPEG"))
    }
    @Test fun phoneReductionIsExplicitlyDifferentFromAnOfferedCameraFile() {
        assertEquals("Created on this phone · 2M JPEG",ImportedMediaLabels.representation(entry(ImportProvenance.PHONE_JPEG_REDUCTION,"TWO_MEGAPIXEL_JPEG")))
        assertEquals("Original JPEG · offered file",ImportedMediaLabels.representation(entry(ImportProvenance.OFFERED_CAMERA_BYTES,"ORIGINAL_JPEG")))
    }
    @Test fun videoDoesNotBecomeTwoMegapixelFromAPhotoPreference() {
        assertEquals("Camera video · unchanged",ImportedMediaLabels.representation(entry(ImportProvenance.OFFERED_CAMERA_BYTES,"TWO_MEGAPIXEL_JPEG","video/mp4")))
    }
    @Test fun recoveryDateIsNotPresentedAsObservedPublicationTime() {
        assertTrue(ImportedMediaLabels.imported(entry(ImportProvenance.OFFERED_CAMERA_BYTES,kind=ImportCompletionKind.RECOVERED_VERIFIED),java.util.Locale.UK,java.time.ZoneOffset.UTC).startsWith("Recovered "))
    }
    @Test fun dateOnlyCaptureNeverInventsATime() {
        val text=ImportedMediaLabels.captured(entry(ImportProvenance.OFFERED_CAMERA_BYTES),java.util.Locale.UK)
        assertTrue(text.contains("date only"));assertFalse(text.contains("00:00"));assertFalse(text.contains("UTC"))
    }
    @Test fun compactLegacySummaryKeepsAddedDateWithoutGuessingQuality() {
        val row=entry(ImportProvenance.LEGACY_UNKNOWN,kind=ImportCompletionKind.LEGACY_ROW_ADDED)
        assertEquals("Phone album",ImportedMediaLabels.tileSummary(row).lineSequence().first())
        assertTrue(ImportedMediaLabels.tileSummary(row).lineSequence().last().startsWith("Added "))
        assertFalse(ImportedMediaLabels.tileSummary(row).contains("2M"))
        assertTrue(ImportedMediaLabels.representation(row).contains("quality unknown"))
        assertTrue(ImportedMediaLabels.tileDate(row,java.util.Locale.UK,java.time.ZoneOffset.UTC).contains("1970"))
    }
    @Test fun detailDatesSeparatePhoneIndexFromCaptureClockAndImportTime() {
        val legacy=entry(ImportProvenance.LEGACY_UNKNOWN,kind=ImportCompletionKind.LEGACY_ROW_ADDED)
        val indexed=legacy.copy(receipt=legacy.receipt!!.copy(captureSource="MEDIASTORE_INDEX",captureTime="19:45:00"))
        val capture=ImportedMediaLabels.captureDetail(indexed,java.util.Locale.UK)
        assertEquals("Capture date",capture.label);assertEquals("10 Jun 2026, 19:45",capture.value);assertEquals("From the phone library",capture.note)
        assertEquals("Added to phone",ImportedMediaLabels.addedDetail(indexed).label)
        assertFalse(capture.value.contains("1970"))
        assertEquals("Camera clock · timezone not recorded",ImportedMediaLabels.captureDetail(indexed.copy(receipt=indexed.receipt!!.copy(captureSource="EXIF"))).note)
    }
    @Test fun namedFolderAndAlbumCopiesHaveDistinctDestinations() {
        val album=entry(ImportProvenance.DOCUMENT_BYTES)
        val folder="content://com.android.externalstorage.documents/tree/primary%3APictures%2FMy%20selects"
        val copy=album.copy(draft=album.draft.copy(folderTree=folder,uri="$folder/document/primary%3APictures%2FMy%20selects%2Fexample.jpg"))
        assertEquals("Phone album",ImportedMediaLabels.destination(album))
        assertEquals("Folder · My selects",ImportedMediaLabels.tileSummary(copy).lineSequence().first())
    }
}
