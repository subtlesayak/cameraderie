package app.cameraderie.gallery.media.history

/** Durable publication history; completion and current local availability differ. */
enum class ImportHistoryStage { STAGING, VERIFIED, COMPLETE, INTERRUPTED }
enum class ImportProvenance { OFFERED_CAMERA_BYTES, PHONE_JPEG_REDUCTION, LEGACY_UNKNOWN, DOCUMENT_BYTES, FRAME_FROM_ORIGINAL, TRIM_FROM_ORIGINAL, EDITED_FROM_ORIGINAL }
enum class ImportCompletionKind { OBSERVED_PUBLICATION, RECOVERED_VERIFIED, LEGACY_ROW_ADDED }

data class ProjectExportOrigin(
    val projectId: String, val shootId: String?, val sourceImportId: String, val sourceSha256: String,
) {
    init {
        require(projectId.isNotBlank() && (shootId == null || shootId.isNotBlank()) && sourceImportId.isNotBlank())
        require(Regex("[0-9a-f]{64}").matches(sourceSha256))
    }
}

data class ImportHistoryDraft(
    val id:String,val uri:String,val displayName:String,val expectedBytes:Long,
    val startedAt:Long?,val provenance:ImportProvenance,
    val requestedQuality:String?=null,val cameraModel:String?=null,
    val sourceScope:String?=null,val sourceScopeKind:String?=null,val sourceItemReference:String?=null,
    val offeredReduced:Boolean?=null, val folderTree:String?=null,
    val companionFor:String?=null, val companionParentReference:String?=null,
    val videoSource:String?=null,val framePositionMs:Long?=null,
    val trimStartUs:Long?=null,val trimEndUs:Long?=null,
    val projectExport: ProjectExportOrigin? = null,
) {
    init {
        require(id.isNotBlank() && displayName.isNotBlank());LocalMediaIdentity.key(uri)
        if (folderTree != null) LocalMediaIdentity.requireFolderTree(folderTree)
        if (LocalMediaIdentity.isFolderReservation(uri)) require(folderTree != null && uri == LocalMediaIdentity.folderReservation(id))
        if (LocalMediaIdentity.isFolderDocument(uri)) require(folderTree != null && LocalMediaIdentity.inFolder(uri,folderTree))
        require(expectedBytes>0 && (startedAt==null || startedAt>=0))
        if (projectExport != null) require(provenance == ImportProvenance.EDITED_FROM_ORIGINAL && id != projectExport.sourceImportId)
        if(provenance==ImportProvenance.LEGACY_UNKNOWN) require(requestedQuality==null)
        if(provenance==ImportProvenance.FRAME_FROM_ORIGINAL) require(!videoSource.isNullOrBlank() && framePositionMs!=null && framePositionMs>=0)
        if(provenance==ImportProvenance.TRIM_FROM_ORIGINAL || provenance==ImportProvenance.EDITED_FROM_ORIGINAL) require(!videoSource.isNullOrBlank() && trimStartUs!=null && trimEndUs!=null && trimStartUs>=0 && trimEndUs>trimStartUs)
    }
}
data class ImportHistoryReceipt(
    val bytes:Long,val sha256:String?,val mime:String,val sourceHashMatched:Boolean,
    val width:Int?=null,val height:Int?=null,val exifOrientation:Int?=null,
    val captureDate:String?=null,val captureTime:String?=null,val captureOffset:String?=null,
    val captureSource:String?=null,val indexingZone:String?=null,val displayName:String?=null,val verifiedFolderFile:String?=null
) {
    init {
        require(bytes>0 && (sha256==null || Regex("[0-9a-f]{64}").matches(sha256)) && mime.contains('/'))
        require(!sourceHashMatched || sha256!=null)
        require((width==null && height==null) || (width!=null && height!=null && width>0 && height>0))
        require(exifOrientation==null || exifOrientation in 0..8)
        captureDate?.let { java.time.LocalDate.parse(it) }
        captureTime?.let { require(captureDate!=null);java.time.LocalTime.parse(it) }
        captureOffset?.let { require(captureDate!=null);java.time.ZoneOffset.of(it) }
        indexingZone?.let { java.time.ZoneId.of(it) }
    }
}
data class ImportHistoryEntry(
    val draft:ImportHistoryDraft,val stage:ImportHistoryStage,val receipt:ImportHistoryReceipt?=null,
    val completedAt:Long?=null,val completionKind:ImportCompletionKind?=null
)
interface ImportHistoryStore {
    fun begin(draft:ImportHistoryDraft)
    fun verified(id:String,receipt:ImportHistoryReceipt)
    fun complete(id:String,observedAt:Long,kind:ImportCompletionKind)
    fun completeFolder(id:String, uri:String, observedAt:Long, kind:ImportCompletionKind) { error("Folder publication is unsupported") }
    fun interrupted(id:String)
    fun unfinished(limit:Int=100,afterId:String?=null):List<ImportHistoryEntry>
    fun completed(offset:Int=0,limit:Int=100):List<ImportHistoryEntry>
    fun completedAfterId(afterId:String?=null,limit:Int=100):List<ImportHistoryEntry>
    fun find(id:String):ImportHistoryEntry?
    fun projectExports(projectId: String): List<ImportHistoryEntry> = emptyList()
    fun countCompleted():Int
    fun containsUri(uri:String):Boolean
    /** Only previously unknown, app-owned published rows; never overwrites journal state. */
    fun legacy(draft:ImportHistoryDraft,receipt:ImportHistoryReceipt?,rowAddedAt:Long?)
}
