package app.cameraderie.gallery

import app.cameraderie.gallery.protocol.*
import org.junit.Assert.*
import org.junit.Test

/** Public captured fixtures and a virtual clock; not camera acceptance. */
class GalleryListBatchTest {
    private val sd=GalleryCameraEvidence.Store.SD
    private val internal=GalleryCameraEvidence.Store.INTERNAL
    private fun fixture(name:String="oa6_sd_3.bin")=javaClass.classLoader!!.getResourceAsStream("gallery-fixtures/$name")!!.use { it.readBytes() }
    private data class Sent(val role:GalleryListBatch.Role,val counter:Int,val sequence:Int,val time:Long)
    private inner class Rig(cursors:Map<GalleryCameraEvidence.Store,Long>,
        counters:GalleryMediaCounters.Pair=GalleryMediaCounters.Pair(1,2)) {
        var time=0L;var sequence=100
        val delays=mutableMapOf<GalleryListBatch.Role,Long>()
        val sent=mutableListOf<Sent>()
        val batch=GalleryListBatch(cursors,counters,{time}) { role,payload ->
            time+=delays[role] ?: 0
            val allocated=sequence++;sent.add(Sent(role,payload[4].toInt() and 255,allocated,time));allocated
        }
        fun tick(at:Long) { time=at;batch.tick() }
        fun message(counter:Int,type:Int,chunk:Int=0,data:ByteArray=byteArrayOf())=DumlFrame(1,2,800,0,0,0x27,
            ByteArray(10).also { it[0]=0x4A;it[1]=type.toByte();it[4]=counter.toByte();it[6]=chunk.toByte();it[7]=(chunk ushr 8).toByte() }+data)
        fun data(counter:Int,bytes:ByteArray,start:Boolean=true) {
            if(start)batch.accept(message(counter,4))
            batch.accept(message(counter,1,data=bytes));batch.accept(message(counter,3))
        }
        fun ack(role:GalleryListBatch.Role,code:Int,wrong:Boolean=false,empty:Boolean=false) {
            val seq=sent.single { it.role==role }.sequence+(if(wrong)1 else 0)
            batch.accept(DumlFrame(1,2,seq,0xC0,0,0x26,if(empty)byteArrayOf() else byteArrayOf(code.toByte())))
        }
    }
    @Test fun separateCounterStreamsProvePagesWithoutRequiringAnAck() {
        val r=Rig(mapOf(sd to 1L,internal to internal.newestCursor));r.tick(0)
        r.data(1,fixture(),start=false);r.tick(1)
        assertFalse(r.batch.finished)
        r.tick(1600);r.tick(2400);r.data(2,fixture("oa6_internal_2.bin"));r.tick(2401)
        val result=checkNotNull(r.batch.result)
        assertEquals(3,result.stores.getValue(sd).page!!.entries.size)
        assertEquals(2,result.stores.getValue(internal).page!!.entries.size)
        assertTrue(result.successful);assertTrue(result.stores.values.none { it.acceptedReceipt })
        assertEquals(listOf(1,1,2),r.sent.map { it.counter })
        assertEquals(3,r.sent.map { it.sequence }.distinct().size)
    }
    @Test fun dataBeforeTargetQueryAndMissingEchoNeverAcquireStoreIdentity() {
        for(counter in listOf(0,2,3)) {
            val r=Rig(mapOf(internal to internal.newestCursor));r.tick(0)
            r.data(counter,fixture("oa6_internal_2.bin"));r.tick(1600);r.tick(2400);r.tick(12000)
            val outcome=r.batch.result!!.stores.getValue(internal)
            assertEquals(GalleryListBatch.Error.TIMEOUT,outcome.error);assertNull(outcome.page)
            assertEquals(0,outcome.stream!!.chunks)
        }
    }
    @Test fun emptyOrWrongSequenceReceiptDoesNotRejectOrCompleteARead() {
        val r=Rig(mapOf(sd to 1L));r.tick(0)
        r.ack(GalleryListBatch.Role.SD_QUERY,0,empty=true)
        r.ack(GalleryListBatch.Role.SD_QUERY,0xD8,wrong=true);r.tick(1)
        assertFalse(r.batch.finished)
        r.data(1,fixture());r.tick(2)
        val outcome=r.batch.result!!.stores.getValue(sd)
        assertNotNull(outcome.page);assertFalse(outcome.acceptedReceipt);assertTrue(outcome.receipts.isEmpty())
    }
    @Test fun triggerReceiptIsDistinctAndDoesNotDiscardValidInternalPage() {
        val r=Rig(mapOf(sd to 1L,internal to internal.newestCursor));r.tick(0);r.tick(1600)
        r.ack(GalleryListBatch.Role.TRIGGER,0xD8);r.tick(2400)
        r.ack(GalleryListBatch.Role.INTERNAL_QUERY,0);r.data(2,fixture("oa6_internal_2.bin"));r.tick(2401)
        val result=r.batch.result!!;val rejected=result.stores.getValue(sd)
        assertEquals(GalleryListBatch.Error.REJECTED,rejected.error)
        assertEquals(GalleryListBatch.Role.TRIGGER,rejected.role);assertEquals(0xD8,rejected.responseCode)
        assertFalse(rejected.acceptedReceipt)
        assertTrue(result.stores.getValue(internal).acceptedReceipt)
        assertNotNull(result.stores.getValue(internal).page)
    }
    @Test fun aSuccessfulTriggerIsNotAQuerySuccessReceipt() {
        val r=Rig(mapOf(sd to 1L));r.tick(0);r.tick(1600);r.ack(GalleryListBatch.Role.TRIGGER,0)
        r.data(1,fixture());r.tick(1601)
        val outcome=r.batch.result!!.stores.getValue(sd)
        assertFalse(outcome.acceptedReceipt);assertEquals(mapOf(GalleryListBatch.Role.TRIGGER to 0),outcome.receipts)
    }
    @Test fun sendsRespectActualPreviousCompletionAfterAWorkerStall() {
        val r=Rig(mapOf(sd to 1L,internal to internal.newestCursor))
        r.delays[GalleryListBatch.Role.SD_QUERY]=300;r.delays[GalleryListBatch.Role.TRIGGER]=1000
        r.tick(0);r.tick(1899);assertEquals(1,r.sent.size)
        r.tick(1900);assertEquals(2900L,r.sent.last().time)
        r.tick(3699);assertEquals(2,r.sent.size)
        r.tick(3700);assertEquals(GalleryListBatch.Role.INTERNAL_QUERY,r.sent.last().role)
        assertEquals(800L,r.sent[2].time-r.sent[1].time)
    }
    @Test fun zeroCountAndStartOnlyCannotBecomeAnEmptyCompleteLibrary() {
        val zero=Rig(mapOf(sd to 1L));zero.tick(0);zero.data(1,ByteArray(4));zero.tick(1)
        assertFalse(zero.batch.finished);zero.tick(12000)
        val outcome=zero.batch.result!!.stores.getValue(sd)
        assertEquals(GalleryListBatch.Error.INVALID_MANIFEST,outcome.error)
        assertEquals(GalleryMediaPage.Error.EMPTY_NOT_CONFIRMED,outcome.parserError);assertNull(outcome.page)
        val start=Rig(mapOf(sd to 1L));start.tick(0);start.batch.accept(start.message(1,4));start.tick(12000)
        assertEquals(GalleryListBatch.Error.TIMEOUT,start.batch.result!!.stores.getValue(sd).error)
    }
    @Test fun outOfOrderEndAndRejectedPrefixContinueUntilStrictPageValidation() {
        val r=Rig(mapOf(sd to 1L));r.tick(0);val bytes=fixture();val split=415
        r.batch.accept(r.message(1,1,data=bytes.copyOf(split)));r.batch.accept(r.message(1,3))
        r.tick(1);assertFalse(r.batch.finished)
        r.batch.accept(r.message(1,1,1,bytes.copyOfRange(split,bytes.size)));r.tick(2)
        assertEquals(3,r.batch.result!!.stores.getValue(sd).page!!.entries.size)
        assertTrue(r.batch.result!!.stores.getValue(sd).page!!.last)
    }
    @Test fun conflictingDuplicateFailsStreamInsteadOfParsingPartialData() {
        val r=Rig(mapOf(sd to 1L));r.tick(0)
        r.batch.accept(r.message(1,1,data=byteArrayOf(1,2,3)))
        r.batch.accept(r.message(1,1,data=byteArrayOf(1,2,4)));r.tick(1)
        val outcome=r.batch.result!!.stores.getValue(sd)
        assertEquals(GalleryListBatch.Error.INVALID_STREAM,outcome.error)
        assertEquals(GalleryManifestStream.Error.CONFLICT,outcome.stream!!.error)
    }
    @Test fun changedStorageInvalidatesItsPreviouslyParsedPageOnly() {
        val r=Rig(mapOf(sd to 1L,internal to internal.newestCursor));r.tick(0)
        r.data(1,fixture());r.tick(1);r.batch.storageChanged(sd)
        r.tick(1600);r.tick(2400);r.data(2,fixture("oa6_internal_2.bin"));r.tick(2401)
        assertEquals(GalleryListBatch.Error.STORAGE_CHANGED,r.batch.result!!.stores.getValue(sd).error)
        assertNull(r.batch.result!!.stores.getValue(sd).page)
        assertNotNull(r.batch.result!!.stores.getValue(internal).page)
    }
    @Test fun cancellationAndUnavailableStoreDoNotIssueLaterQueries() {
        val r=Rig(mapOf(sd to 1L));r.tick(0);r.batch.cancel();r.tick(12000)
        assertEquals(1,r.sent.size);assertEquals(GalleryListBatch.Error.CANCELED,r.batch.result!!.stores.getValue(sd).error)
        val unavailable=Rig(mapOf(sd to 1L));unavailable.batch.unavailable(sd);unavailable.tick(0)
        assertTrue(unavailable.sent.isEmpty());assertEquals(GalleryListBatch.Error.UNAVAILABLE,unavailable.batch.result!!.stores.getValue(sd).error)
    }
    @Test fun explicitCounterContextsKeepIdenticalFilesOnTwoStoresDistinct() {
        val r=Rig(mapOf(sd to 1L,internal to internal.newestCursor));r.tick(0)
        r.data(1,fixture());r.tick(1600);r.tick(2400);r.data(2,fixture());r.tick(2401)
        val entries=r.batch.result!!.stores.values.flatMap { it.page!!.entries }
        assertEquals(6,entries.map { it.key }.toSet().size)
    }
    @Test fun lastCounterCanEndWithAnAcceptedButEmptyFileCheck() {
        val counters = GalleryMediaCounters(253)
        val r = Rig(mapOf(sd to 1L), checkNotNull(counters.allocate()))
        r.tick(0)
        r.ack(GalleryListBatch.Role.SD_QUERY, 0)
        r.tick(1600)
        r.batch.accept(r.message(253, 4))
        r.batch.accept(r.message(253, 3))
        r.tick(12000)
        val outcome = checkNotNull(r.batch.result).stores.getValue(sd)
        assertTrue(outcome.endedWithoutPageData)
        assertNull(outcome.page)
        assertTrue(counters.exhausted)
        assertNull(counters.allocate())
    }

    @Test fun mediaCounterPairsNeverWrapOrReuseWithinTheSession() {
        val counters=GalleryMediaCounters()
        val allocated=(1..127).map {
            assertFalse(counters.exhausted)
            counters.allocate()!!
        }
        assertTrue(counters.exhausted)
        assertEquals(254,allocated.flatMap { listOf(it.sd,it.internal) }.toSet().size)
        assertEquals(1,allocated.first().sd);assertEquals(254,allocated.last().internal)
        repeat(3) { assertNull(counters.allocate()) }
    }
    @Test fun laterMatchingTriggerRejectionInvalidatesAnAlreadyParsedSdSlice() {
        val r=Rig(mapOf(sd to 1L,internal to internal.newestCursor));r.tick(0)
        r.data(1,fixture());r.tick(1);r.ack(GalleryListBatch.Role.SD_QUERY,0)
        r.tick(1600);r.ack(GalleryListBatch.Role.TRIGGER,0xD8)
        r.tick(2400);r.data(2,fixture("oa6_internal_2.bin"));r.tick(2401)
        val sdResult=r.batch.result!!.stores.getValue(sd)
        assertEquals(GalleryListBatch.Error.REJECTED,sdResult.error);assertNull(sdResult.page)
        assertEquals(mapOf(GalleryListBatch.Role.SD_QUERY to 0,GalleryListBatch.Role.TRIGGER to 0xD8),sdResult.receipts)
        assertTrue(sdResult.acceptedReceipt);assertNotNull(r.batch.result!!.stores.getValue(internal).page)
    }
    @Test fun lateQueryReceiptIsRetainedBeforeBatchCompletion() {
        val r=Rig(mapOf(sd to 1L,internal to internal.newestCursor));r.tick(0)
        r.data(1,fixture());r.tick(1);r.ack(GalleryListBatch.Role.SD_QUERY,0)
        r.tick(1600);r.tick(2400);r.data(2,fixture("oa6_internal_2.bin"));r.tick(2401)
        assertTrue(r.batch.result!!.stores.getValue(sd).acceptedReceipt)
    }
}
