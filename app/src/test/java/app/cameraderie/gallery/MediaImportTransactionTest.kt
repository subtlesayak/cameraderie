package app.cameraderie.gallery

import app.cameraderie.gallery.media.MediaImportTransaction
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class MediaImportTransactionTest {
    private class Rig:MediaImportTransaction.Port {
        val calls=mutableListOf<String>();var stop=false;var fail:String?=null;var wrongSize=false;var removeFails=false
        private fun step(name:String) { calls.add(name);if(fail==name) throw IOException("private details") }
        override fun createPending(item:MediaImportTransaction.Item):String { step("create:${item.id}");return item.id }
        override fun copy(item:MediaImportTransaction.Item,destination:String):MediaImportTransaction.Copied { step("copy:$destination");return MediaImportTransaction.Copied(if(wrongSize) 9 else item.bytes,"stream-sha") }
        override fun verify(item:MediaImportTransaction.Item,destination:String,copied:MediaImportTransaction.Copied) { step("verify:$destination");if(fail=="cancel-verify") stop=true }
        override fun publish(destination:String) { step("publish:$destination") }
        override fun remove(destination:String) { calls.add("remove:$destination");if(removeFails) throw IOException() }
    }
    private val items=listOf(MediaImportTransaction.Item("a","A.JPG",10),MediaImportTransaction.Item("b","B.JPG",10))
    @Test fun publishesOnlyAfterCopyAndReadbackVerification() {
        val r=Rig();val result=MediaImportTransaction().run(items,r,{r.stop})
        assertEquals(listOf("a","b"),result.completed)
        assertEquals(listOf("create:a","copy:a","verify:a","publish:a","create:b","copy:b","verify:b","publish:b"),r.calls)
    }
    @Test fun verificationFailureRemovesPendingAndKeepsCompletedFile() {
        val r=Rig();r.fail="verify:b";val result=MediaImportTransaction().run(items,r,{r.stop})
        assertEquals(listOf("a"),result.completed);assertEquals(MediaImportTransaction.Phase.VERIFY,result.failedAt)
        assertTrue("remove:b" in r.calls);assertFalse("remove:a" in r.calls);assertFalse("publish:b" in r.calls)
    }
    @Test fun cancellationBeforeCreateDoesNotTouchMediaStore() {
        val r=Rig();r.stop=true;assertTrue(MediaImportTransaction().run(items,r,{r.stop}).cancelled);assertTrue(r.calls.isEmpty())
    }
    @Test fun cancellationAfterVerifyCannotPublishOrStartNextFile() {
        val r=Rig();r.fail="cancel-verify";val result=MediaImportTransaction().run(items,r,{r.stop})
        assertTrue(result.cancelled);assertEquals(listOf("create:a","copy:a","verify:a","remove:a"),r.calls)
    }
    @Test fun wrongLengthCannotReachVerifierOrPublication() {
        val r=Rig();r.wrongSize=true;val result=MediaImportTransaction().run(items,r,{r.stop})
        assertEquals(MediaImportTransaction.Phase.VERIFY,result.failedAt);assertFalse("verify:a" in r.calls);assertEquals("remove:a",r.calls.last())
    }
    @Test fun failedPublicationAndCleanupAreReportedAccurately() {
        val r=Rig();r.fail="publish:a";r.removeFails=true
        val result=MediaImportTransaction().run(items,r,{r.stop});assertTrue(result.completed.isEmpty());assertTrue(result.cleanupFailed);assertEquals(MediaImportTransaction.Phase.PUBLISH,result.failedAt)
    }
    @Test fun aDestinationProposingAnotherNameNeverPublishesThePendingCopy() {
        val rig = Rig()
        val port = object : MediaImportTransaction.Port by rig {
            override fun copy(item: MediaImportTransaction.Item, destination: String): MediaImportTransaction.Copied {
                throw MediaImportTransaction.FilenameConflict()
            }
        }
        val result = MediaImportTransaction().run(items, port, { false })
        assertTrue(result.filenameConflict)
        assertEquals(emptyList<String>(), result.completed)
        assertEquals(listOf("create:a", "remove:a"), rig.calls)
    }
    @Test fun destinationCreationFailureCannotDeleteExistingMedia() {
        val r=Rig();r.fail="create:a";val result=MediaImportTransaction().run(items,r,{r.stop})
        assertTrue(result.completed.isEmpty());assertEquals(listOf("create:a"),r.calls)
    }
    @Test fun failedMetadataAfterPublicationNeverRollsBackCompletedFiles() {
        val r=Rig()
        val port=object:MediaImportTransaction.Port by r {
            override fun afterPublication(item:MediaImportTransaction.Item,destination:String) { throw IOException("metadata unavailable") }
        }
        val result=MediaImportTransaction().run(items,port,{false})
        assertEquals(listOf("a","b"),result.completed);assertTrue(result.followUpFailed)
        assertNull(result.failedAt);assertFalse(result.cleanupFailed)
        assertFalse(r.calls.any { it.startsWith("remove:") })
    }
    @Test fun publishedOrUncertainDestinationIsRetainedForReconciliation() {
        val r=Rig();r.fail="publish:a"
        val port=object:MediaImportTransaction.Port by r {
            override fun cleanup(destination:String)=MediaImportTransaction.Cleanup.RETAINED
        }
        val result=MediaImportTransaction().run(items,port,{false})
        assertTrue(result.completed.isEmpty());assertTrue(result.reconciliationRequired)
        assertFalse(result.cleanupFailed);assertEquals(MediaImportTransaction.Phase.PUBLISH,result.failedAt)
        assertFalse(r.calls.any { it.startsWith("remove:") })
    }
    @Test fun failedCompletionObserverCannotDeleteOrDuplicatePublishedMedia() {
        val r=Rig();val result=MediaImportTransaction().run(items,r,{false}) { _,_ -> throw IOException() }
        assertEquals(listOf("a","b"),result.completed);assertTrue(result.followUpFailed)
        assertEquals(1,r.calls.count { it=="publish:a" });assertEquals(1,r.calls.count { it=="publish:b" })
        assertFalse(r.calls.any { it.startsWith("remove:") })
    }
    @Test fun followUpFailureRemainsReportedWhenTheNextTransferFails() {
        val r=Rig();r.fail="copy:b"
        val port=object:MediaImportTransaction.Port by r {
            override fun afterPublication(item:MediaImportTransaction.Item,destination:String) { throw IOException() }
        }
        val result=MediaImportTransaction().run(items,port,{false})
        assertEquals(listOf("a"),result.completed);assertTrue(result.followUpFailed)
        assertEquals(MediaImportTransaction.Phase.COPY,result.failedAt)
        assertTrue("remove:b" in r.calls);assertFalse("remove:a" in r.calls)
    }
    @Test fun failedVerifiedJournalCommitCannotPublishThePendingFile() {
        val r=Rig();val port=object:MediaImportTransaction.Port by r {
            override fun afterVerification(item:MediaImportTransaction.Item,destination:String,copied:MediaImportTransaction.Copied) { throw IOException("journal full") }
        }
        val result=MediaImportTransaction().run(items,port,{false})
        assertTrue(result.completed.isEmpty());assertEquals(MediaImportTransaction.Phase.VERIFY,result.failedAt)
        assertTrue("remove:a" in r.calls);assertFalse(r.calls.any { it.startsWith("publish:") })
    }
    @Test fun durableReceiptFollowsFinalOutputValidationAndPrecedesPublication() {
        val r=Rig();val port=object:MediaImportTransaction.Port by r {
            override fun verify(item:MediaImportTransaction.Item,destination:String,copied:MediaImportTransaction.Copied) { r.verify(item,destination,copied);r.calls.add("policy:$destination") }
            override fun afterVerification(item:MediaImportTransaction.Item,destination:String,copied:MediaImportTransaction.Copied) { r.calls.add("receipt:$destination") }
        }
        assertEquals(listOf("a"),MediaImportTransaction().run(items.take(1),port,{false}).completed)
        assertEquals(listOf("create:a","copy:a","verify:a","policy:a","receipt:a","publish:a"),r.calls)
    }
}
