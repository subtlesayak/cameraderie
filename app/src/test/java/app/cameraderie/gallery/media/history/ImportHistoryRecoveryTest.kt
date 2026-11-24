package app.cameraderie.gallery.media.history

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ImportHistoryRecoveryTest {
    private class Store:ImportHistoryStore {
        val rows=linkedMapOf<String,ImportHistoryEntry>();var failComplete=false
        override fun begin(draft:ImportHistoryDraft) { rows[draft.id]=ImportHistoryEntry(draft,ImportHistoryStage.STAGING) }
        override fun verified(id:String,receipt:ImportHistoryReceipt) { rows[id]=rows.getValue(id).copy(stage=ImportHistoryStage.VERIFIED,receipt=receipt) }
        override fun complete(id:String,observedAt:Long,kind:ImportCompletionKind) { if(failComplete)throw IOException();rows[id]=rows.getValue(id).copy(stage=ImportHistoryStage.COMPLETE,completedAt=observedAt,completionKind=kind) }
        override fun completeFolder(id:String,uri:String,observedAt:Long,kind:ImportCompletionKind) {
            complete(id,observedAt,kind)
            val row=rows.getValue(id)
            rows[id]=row.copy(draft=row.draft.copy(uri=uri))
        }
        override fun interrupted(id:String) { rows[id]=rows.getValue(id).copy(stage=ImportHistoryStage.INTERRUPTED) }
        override fun unfinished(limit:Int,afterId:String?)=rows.values.filter { it.stage in setOf(ImportHistoryStage.STAGING,ImportHistoryStage.VERIFIED) && (afterId==null || it.draft.id>afterId) }.sortedBy { it.draft.id }.take(limit)
        override fun completed(offset:Int,limit:Int)=rows.values.filter { it.stage==ImportHistoryStage.COMPLETE }.drop(offset).take(limit)
        override fun completedAfterId(afterId:String?,limit:Int)=rows.values.filter { it.stage==ImportHistoryStage.COMPLETE && (afterId==null || it.draft.id>afterId) }.sortedBy { it.draft.id }.take(limit)
        override fun find(id:String)=rows[id]
        override fun countCompleted()=completed(0,500).size
        override fun containsUri(uri:String)=rows.values.any { it.draft.uri==uri }
        override fun legacy(draft:ImportHistoryDraft,receipt:ImportHistoryReceipt?,rowAddedAt:Long?) { error("not part of recovery") }
    }
    private class Media:ImportHistoryRecovery.Media {
        var state=ImportHistoryRecovery.LocalState.OWNED_PUBLISHED;var matches=true;var throwState=false;var deleted=false;var removeSucceeds=true
        val states=mutableMapOf<String,ImportHistoryRecovery.LocalState>();var onVerify:()->Unit={};var throwVerify=false
        override fun state(uri:String):ImportHistoryRecovery.LocalState { if(throwState)throw IOException();return states[uri] ?: state }
        override fun verify(entry:ImportHistoryEntry):Boolean { onVerify();if(throwVerify)throw IOException();return matches }
        override fun removePending(uri:String):Boolean { check(state==ImportHistoryRecovery.LocalState.OWNED_PENDING);deleted=removeSucceeds;return removeSucceeds }
    }
    private fun fixture(verified:Boolean=true):Pair<Store,Media> {
        val store=Store();store.begin(ImportHistoryDraft("one","content://media/external_primary/images/media/1","same_2M.JPG",10,1,ImportProvenance.OFFERED_CAMERA_BYTES,"ORIGINAL"))
        if(verified)store.verified("one",ImportHistoryReceipt(10,"a".repeat(64),"image/jpeg",true))
        return store to Media()
    }
    @Test fun verifiedFolderPublicationReplacesReservationWithFinalDocument() {
        val id="fb51ee80-f728-4ae0-ab34-9fce7c9bb2b8"
        val folder="content://com.android.externalstorage.documents/tree/primary%3APictures%2FCameraderie"
        val uri="$folder/document/primary%3APictures%2FCameraderie%2Fphoto.JPG"
        val store=Store()
        store.begin(ImportHistoryDraft(id,LocalMediaIdentity.folderReservation(id),"photo.JPG",10,1,
            ImportProvenance.DOCUMENT_BYTES,folderTree=folder))
        store.verified(id,ImportHistoryReceipt(10,"a".repeat(64),"image/jpeg",true))
        val media=object:ImportHistoryRecovery.Media by Media() {
            override fun state(uri:String)=error("A folder reservation is not a MediaStore row")
            override fun recoverFolder(entry:ImportHistoryEntry)=ImportHistoryRecovery.FolderRecovery(publishedUri=uri)
        }
        assertEquals(1,ImportHistoryRecovery(store,media,{42}).run().recovered)
        assertEquals(uri,store.completed().single().draft.uri)
        assertEquals(0,ImportHistoryRecovery(store,media,{42}).run().recovered)
    }
    @Test fun verifiedPublishedFileIsRecoveredOnceWithoutDeletion() {
        val (s,m)=fixture();val recovery=ImportHistoryRecovery(s,m,{42})
        assertEquals(1,recovery.run().recovered);assertEquals(0,recovery.run().recovered)
        assertEquals(ImportCompletionKind.RECOVERED_VERIFIED,s.rows.getValue("one").completionKind);assertFalse(m.deleted)
    }
    @Test fun publishedStagingRowCannotBecomeCompleteWithoutVerification() {
        val (s,m)=fixture(false);assertEquals(1,ImportHistoryRecovery(s,m,{42}).run().interrupted)
        assertEquals(0,s.countCompleted());assertFalse(m.deleted)
    }
    @Test fun pendingFileIsRemovedAndNeverPresentedAsImported() {
        val (s,m)=fixture();m.state=ImportHistoryRecovery.LocalState.OWNED_PENDING
        assertEquals(1,ImportHistoryRecovery(s,m,{42}).run().interrupted);assertTrue(m.deleted);assertEquals(0,s.countCompleted())
    }
    @Test fun unreadableProviderStateIsRetriedWithoutLosingJournal() {
        val (s,m)=fixture();m.throwState=true;assertEquals(1,ImportHistoryRecovery(s,m,{42}).run().unresolved)
        assertEquals(ImportHistoryStage.VERIFIED,s.rows.getValue("one").stage);assertFalse(m.deleted)
    }
    @Test fun mismatchedPublishedBytesArePreservedButNotVerifiedComplete() {
        val (s,m)=fixture();m.matches=false;assertEquals(1,ImportHistoryRecovery(s,m,{42}).run().interrupted)
        assertEquals(0,s.countCompleted());assertFalse(m.deleted)
    }
    @Test fun failedCompletionCommitLeavesVerifiedReceiptForNextRecovery() {
        val (s,m)=fixture();s.failComplete=true;val recovery=ImportHistoryRecovery(s,m,{42})
        assertEquals(1,recovery.run().unresolved);assertFalse(m.deleted)
        s.failComplete=false;assertEquals(1,recovery.run().recovered)
    }
    @Test fun missingFileIsNotRecordedAsComplete() {
        val (s,m)=fixture();m.state=ImportHistoryRecovery.LocalState.ABSENT
        assertEquals(1,ImportHistoryRecovery(s,m,{42}).run().interrupted);assertEquals(0,s.countCompleted())
    }
    @Test fun metadataWithoutVerificationHashCannotBeRecoveredAsVerified() {
        val (s,m)=fixture();s.rows["one"]=s.rows.getValue("one").copy(receipt=ImportHistoryReceipt(10,null,"image/jpeg",false))
        assertEquals(1,ImportHistoryRecovery(s,m,{42}).run().interrupted);assertEquals(0,s.countCompleted());assertFalse(m.deleted)
    }
    @Test fun cancellationBeforeRecoveryLeavesBothMediaAndJournalUntouched() {
        val (s,m)=fixture();val result=ImportHistoryRecovery(s,m,{42}).run(cancelled={true})
        assertTrue(result.cancelled);assertEquals(ImportHistoryStage.VERIFIED,s.rows.getValue("one").stage);assertFalse(m.deleted)
    }
    @Test fun unavailableFirstPageCannotStarveLaterVerifiedPublishedImports() {
        val store=Store();val media=Media()
        for((index,id) in listOf("a","b","z").withIndex()) {
            val draft=ImportHistoryDraft(id,"content://media/external_primary/images/media/${index+1}","same.JPG",10,1,ImportProvenance.OFFERED_CAMERA_BYTES)
            store.begin(draft);store.verified(id,ImportHistoryReceipt(10,"a".repeat(64),"image/jpeg",true))
            if(id!="z")media.states[draft.uri]=ImportHistoryRecovery.LocalState.UNAVAILABLE
        }
        val recovery=ImportHistoryRecovery(store,media,{42});val first=recovery.run(limit=2)
        assertEquals(2,first.unresolved);assertEquals("b",first.nextCursor);assertFalse(first.reachedEnd)
        val next=recovery.run(limit=2,afterId=first.nextCursor)
        assertEquals(1,next.recovered);assertTrue(next.reachedEnd);assertEquals("z",store.completed(0,100).single().draft.id)
        assertFalse(media.deleted)
    }
    @Test fun cancellationAfterVerificationReturnsPreviousCursorForSafeContinuation() {
        val (s,m)=fixture();var stop=false;m.onVerify={stop=true}
        val recovery=ImportHistoryRecovery(s,m,{42});val result=recovery.run(cancelled={stop})
        assertTrue(result.cancelled);assertNull(result.nextCursor);assertEquals(0,s.countCompleted());assertFalse(m.deleted)
        stop=false;m.onVerify={};assertEquals(1,recovery.run(afterId=result.nextCursor).recovered)
    }
    @Test fun cancelledVerificationExceptionDoesNotSkipOrInterruptUntouchedJournal() {
        val (s,m)=fixture();var stop=false;m.onVerify={stop=true};m.throwVerify=true
        val recovery=ImportHistoryRecovery(s,m,{42});val result=recovery.run(cancelled={stop})
        assertTrue(result.cancelled);assertNull(result.nextCursor);assertEquals(ImportHistoryStage.VERIFIED,s.rows.getValue("one").stage)
        stop=false;m.onVerify={};m.throwVerify=false;assertEquals(1,recovery.run(afterId=result.nextCursor).recovered)
    }
}
