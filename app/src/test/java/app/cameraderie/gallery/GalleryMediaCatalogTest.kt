package app.cameraderie.gallery

import app.cameraderie.gallery.media.*
import app.cameraderie.gallery.protocol.*
import org.junit.Assert.*
import org.junit.Test

class GalleryMediaCatalogTest {
    private val sd=GalleryCameraEvidence.Store.SD
    private val internal=GalleryCameraEvidence.Store.INTERNAL
    private val owner=Any()
    private fun context(sdEpoch:Long=1,internalEpoch:Long=1,sdAvailable:Boolean=true,who:Any=owner)=
        GalleryDatalinkSession.MediaContext(who,mapOf(sd to GalleryCameraEvidence.Storage(sd,if(sdAvailable)1000 else 0,if(sdAvailable)500 else 0),
            internal to GalleryCameraEvidence.Storage(internal,2000,1000)),mapOf(sd to sdEpoch,internal to internalEpoch))
    private fun media(store:GalleryCameraEvidence.Store,h:Long=300,path:String="DCIM/CUSTOM/SAME.JPG")=
        GalleryMediaPage.Media(GalleryMediaPage.Key(store,path),"MISC/THM/SAME.scr",h,100,0,null)
    private fun page(store:GalleryCameraEvidence.Store,vararg files:GalleryMediaPage.Media,last:Boolean=true)=
        GalleryListBatch.Outcome(GalleryMediaPage.Page(store,files.toList(),last,files.size))
    @Test fun observationDoesNotStartListing() {
        val c=GalleryMediaCatalog();c.observe(context());assertNull(c.nextRequest());assertFalse(c.snapshot.started);assertFalse(c.snapshot.complete)
    }
    @Test fun unionNeedsExplicitLastPageForEveryAvailableStore() {
        val c=GalleryMediaCatalog();c.start(context());val r=c.nextRequest()!!
        assertEquals(setOf(sd,internal),r.cursors.keys);assertNull(c.nextRequest())
        c.apply(r,GalleryListBatch.Result(mapOf(sd to page(sd,media(sd),last=false),internal to page(internal,media(internal)))))
        assertFalse(c.snapshot.complete);assertEquals(2,c.snapshot.entries.size)
        val next=c.nextRequest()!!;assertEquals(mapOf(sd to 300L),next.cursors)
        c.apply(next,GalleryListBatch.Result(mapOf(sd to page(sd,media(sd),media(sd,100,"DCIM/CUSTOM/OLDER.JPG")))))
        assertTrue(c.snapshot.complete);assertEquals(3,c.snapshot.entries.size);assertNull(c.nextRequest())
    }
    @Test fun stoppingAScanKeepsOnlyCommittedFilesAvailableForDownload() {
        val c = GalleryMediaCatalog()
        val found = media(sd)
        val older = media(sd, 100, "DCIM/CUSTOM/OLDER.JPG")
        c.start(context())
        assertFalse(c.stopForDownloads())
        c.apply(c.nextRequest()!!, GalleryListBatch.Result(mapOf(
            sd to page(sd, found, last = false), internal to page(internal))))
        val pending = c.nextRequest()!!
        val located = c.find(found.key)!!

        assertTrue(c.stopForDownloads())
        assertFalse(c.snapshot.complete)
        assertTrue(c.snapshot.downloadsAvailable)
        assertTrue(c.current(located))
        assertNull(c.nextRequest())
        c.apply(pending, GalleryListBatch.Result(mapOf(sd to page(sd, older))))
        assertNull(c.find(older.key))

        c.observe(context(sdEpoch = 2))
        assertFalse(c.current(located))
        assertFalse(c.snapshot.downloadsAvailable)
        c.start(context(sdEpoch = 2))
        assertFalse(c.snapshot.stoppedForDownloads)
    }

    @Test fun samePathsAcrossStoresHaveSeparateKeysAndOriginalPageProvenance() {
        val ctx=context();val c=GalleryMediaCatalog();c.start(ctx);val r=c.nextRequest()!!
        c.apply(r,GalleryListBatch.Result(mapOf(sd to page(sd,media(sd)),internal to page(internal,media(internal)))))
        val files=c.snapshot.entries;assertEquals(2,files.map { it.media.key }.distinct().size)
        assertEquals(sd.newestCursor,files.single { it.media.key.store==sd }.requestCursor)
        assertEquals(internal.newestCursor,files.single { it.media.key.store==internal }.requestCursor)
        assertTrue(files.all { it.context===ctx && c.current(it) })
    }
    @Test fun validationFallbacksOnlyUseKnownNearbyHandlesFromTheCurrentSdStore() {
        val c = GalleryMediaCatalog()
        val selected = media(sd, 200, "DCIM/CUSTOM/SELECTED.JPG")
        val neighbors = listOf(320L, 220L, 210L, 190L).map { media(sd, it, "DCIM/CUSTOM/$it.JPG") }
        c.start(context())
        c.apply(c.nextRequest()!!, GalleryListBatch.Result(mapOf(
            sd to page(sd, selected, *neighbors.toTypedArray()),
            internal to page(internal, media(internal, 205)))))
        val item = c.find(selected.key)!!
        assertEquals(listOf(210L, 220L), c.validationFallbacks(item))
        assertTrue(c.validationFallbacks(c.snapshot.entries.single { it.media.key.store == internal }).isEmpty())
        c.observe(context(sdEpoch = 2))
        assertTrue(c.validationFallbacks(item).isEmpty())
    }

    @Test fun favouriteWritesRequireAUniqueHandleAndCurrentReadbackCanUpdateOnlyItsOwnFile() {
        val c=GalleryMediaCatalog()
        val target=media(sd).copy(favourite=false)
        fun load(otherHandle:Long):GalleryMediaCatalog.Located {
            c.start(context())
            c.apply(c.nextRequest()!!,GalleryListBatch.Result(mapOf(
                sd to page(sd,target),internal to page(internal,media(internal,otherHandle).copy(favourite=false)))))
            return c.find(target.key)!!
        }
        assertFalse(c.favouriteTarget(load(target.handle)))
        val current=load(target.handle+1)
        assertTrue(c.favouriteTarget(current))
        assertTrue(c.updateFavourite(current,true))
        assertFalse(c.current(current))
        assertFalse(c.updateFavourite(current,false))
        assertEquals(true,c.find(target.key)?.media?.favourite)
        assertEquals(false,c.snapshot.entries.single { it.media.key.store==internal }.media.favourite)
        assertTrue(c.snapshot.complete)
    }
    @Test fun completedFilteredPagesReconcileOnlyTheirOwnStore() {
        val ctx = context()
        val sdFavourite = media(sd).copy(favourite = true)
        val recent = media(internal, 400, "DCIM/CUSTOM/RECENT.JPG").copy(favourite = false)
        val older = media(internal, 200, "DCIM/CUSTOM/OLDER.JPG").copy(favourite = true)
        val noLongerMarked = media(internal, 100, "DCIM/CUSTOM/LAST.JPG").copy(favourite = true)
        val catalogue = GalleryMediaCatalog()
        catalogue.start(ctx)
        catalogue.apply(catalogue.nextRequest()!!, GalleryListBatch.Result(mapOf(
            sd to page(sd, sdFavourite), internal to page(internal, recent, older, noLongerMarked))))
        assertTrue(catalogue.snapshot.complete)

        val filtered = GalleryMediaCatalog()
        filtered.start(ctx)
        filtered.apply(filtered.nextRequest()!!, GalleryListBatch.Result(mapOf(
            sd to GalleryListBatch.Outcome(error = GalleryListBatch.Error.INVALID_MANIFEST,
                parserError = GalleryMediaPage.Error.EMPTY_NOT_CONFIRMED),
            internal to page(internal, recent.copy(favourite = true), last = false))))
        assertTrue(catalogue.reconcileFavourites(filtered.snapshot, ctx).isEmpty())
        assertEquals(false, catalogue.find(recent.key)?.media?.favourite)

        val next = filtered.nextRequest()!!
        assertEquals(mapOf(internal to recent.handle), next.cursors)
        filtered.apply(next, GalleryListBatch.Result(mapOf(internal to page(internal, older))))
        assertEquals(setOf(internal), catalogue.reconcileFavourites(filtered.snapshot, ctx))
        assertEquals(true, catalogue.find(sdFavourite.key)?.media?.favourite)
        assertEquals(true, catalogue.find(recent.key)?.media?.favourite)
        assertEquals(true, catalogue.find(older.key)?.media?.favourite)
        assertEquals(false, catalogue.find(noLongerMarked.key)?.media?.favourite)
        assertEquals(internal.newestCursor, catalogue.find(older.key)?.requestCursor)
        assertTrue(catalogue.snapshot.complete)
    }

    @Test fun failedStoreCannotTurnPartialUnionIntoCompleteOrEmpty() {
        val c=GalleryMediaCatalog();c.start(context());val r=c.nextRequest()!!
        c.apply(r,GalleryListBatch.Result(mapOf(sd to GalleryListBatch.Outcome(error=GalleryListBatch.Error.TIMEOUT),internal to page(internal,media(internal)))))
        assertFalse(c.snapshot.complete);assertEquals(1,c.snapshot.entries.size);assertNull(c.nextRequest())
        assertEquals(GalleryMediaCatalog.Phase.FAILED,c.snapshot.stores.single { it.store==sd }.phase)
    }
    @Test fun changedOneStoreRejectsOnlyThatStoresInflightResult() {
        val c=GalleryMediaCatalog();c.start(context());val r=c.nextRequest()!!;c.observe(context(sdEpoch=2))
        c.apply(r,GalleryListBatch.Result(mapOf(sd to page(sd,media(sd)),internal to page(internal,media(internal)))))
        assertEquals(listOf(internal),c.snapshot.entries.map { it.media.key.store });assertFalse(c.snapshot.complete)
    }
    @Test fun observedRemovalReinsertWithSameCapacityInvalidatesOldItem() {
        val c=GalleryMediaCatalog();c.start(context());val r=c.nextRequest()!!
        c.apply(r,GalleryListBatch.Result(mapOf(sd to page(sd,media(sd)),internal to page(internal,media(internal)))))
        val original=c.snapshot.entries.single { it.media.key.store==sd };val intact=c.snapshot.entries.single { it.media.key.store==internal }
        c.observe(context(sdEpoch=2,sdAvailable=false));c.observe(context(sdEpoch=3));assertFalse(c.current(original));assertTrue(c.current(intact))
        assertEquals(GalleryMediaCatalog.Phase.CHANGED,c.snapshot.stores.single { it.store==sd }.phase)
    }
    @Test fun refreshedOrClosedCatalogueCannotAcceptLateResults() {
        val c=GalleryMediaCatalog();c.start(context());val old=c.nextRequest()!!;c.start(context())
        assertFalse(c.apply(old,GalleryListBatch.Result(mapOf(sd to page(sd,media(sd))))));assertTrue(c.snapshot.entries.isEmpty())
        val next=c.nextRequest()!!;c.close();assertFalse(c.apply(next,GalleryListBatch.Result(mapOf(sd to page(sd,media(sd))))))
    }
    @Test fun sessionChangeClearsCatalogueWithoutAutoRestart() {
        val c=GalleryMediaCatalog();c.start(context());val r=c.nextRequest()!!;c.observe(context(who=Any()))
        assertFalse(c.apply(r,GalleryListBatch.Result(mapOf(sd to page(sd,media(sd))))));assertFalse(c.snapshot.started);assertNull(c.nextRequest())
    }
    @Test fun cancellationPreservesVerifiedRowsButNotCompletionOfFailedStore() {
        val c=GalleryMediaCatalog();c.start(context());val r=c.nextRequest()!!
        c.apply(r,GalleryListBatch.Result(mapOf(sd to page(sd,media(sd),last=false),internal to page(internal,media(internal)))))
        val next=c.nextRequest()!!;c.cancel();assertEquals(2,c.snapshot.entries.size);assertFalse(c.snapshot.complete)
        assertFalse(c.apply(next,GalleryListBatch.Result(mapOf(sd to page(sd,media(sd))))))
    }
    @Test fun cursorStallRetainsVerifiedRowsAndDoesNotClaimComplete() {
        val c=GalleryMediaCatalog();c.start(context());val r=c.nextRequest()!!
        c.apply(r,GalleryListBatch.Result(mapOf(sd to page(sd,media(sd),last=false),internal to page(internal,media(internal)))))
        val next=c.nextRequest()!!;c.apply(next,GalleryListBatch.Result(mapOf(sd to page(sd,media(sd),last=false))))
        assertEquals(2,c.snapshot.entries.size);assertFalse(c.snapshot.complete);assertNull(c.nextRequest())
    }
    @Test fun changedOverlapClearsOnlyAffectedStore() {
        val c=GalleryMediaCatalog();c.start(context());val r=c.nextRequest()!!
        c.apply(r,GalleryListBatch.Result(mapOf(sd to page(sd,media(sd),last=false),internal to page(internal,media(internal)))))
        val next=c.nextRequest()!!;c.apply(next,GalleryListBatch.Result(mapOf(sd to page(sd,media(sd).copy(size=101)))))
        assertEquals(listOf(internal),c.snapshot.entries.map { it.media.key.store });assertFalse(c.snapshot.complete)
    }
    @Test fun unavailableStoreIsExplicitAndDoesNotReceiveCursor() {
        val c=GalleryMediaCatalog();c.start(context(sdAvailable=false));val r=c.nextRequest()!!;assertEquals(setOf(internal),r.cursors.keys)
        c.apply(r,GalleryListBatch.Result(mapOf(internal to page(internal,media(internal)))))
        assertTrue(c.snapshot.complete);assertEquals(GalleryMediaCatalog.Phase.UNAVAILABLE,c.snapshot.stores.single { it.store==sd }.phase)
    }

    @Test fun onlyTheInitialNoChunkSdTimeoutReceivesOneBoundedRetry() {
        val c=GalleryMediaCatalog();c.start(context());val first=c.nextRequest()!!
        val empty=GalleryListBatch.Outcome(error=GalleryListBatch.Error.TIMEOUT,stream=GalleryManifestStream.Snapshot(GalleryManifestStream.Status.WAITING,0,0,false,false,null))
        c.apply(first,GalleryListBatch.Result(mapOf(sd to empty,internal to page(internal,media(internal)))))
        val retry=c.nextRequest()!!;assertEquals(mapOf(sd to sd.newestCursor),retry.cursors);assertFalse(c.snapshot.complete)
        c.apply(retry,GalleryListBatch.Result(mapOf(sd to empty)));assertNull(c.nextRequest());assertFalse(c.snapshot.complete)
        assertEquals(GalleryMediaCatalog.Phase.FAILED,c.snapshot.stores.single { it.store==sd }.phase)
    }

    @Test fun endOnlyOlderSdPageRetriesOnceFromThePrecedingHandle() {
        val newest=media(sd,400,"DCIM/CUSTOM/NEW.JPG")
        val boundary=media(sd,300,"DCIM/CUSTOM/BOUNDARY.JPG")
        val older=media(sd,200,"DCIM/CUSTOM/OLD.JPG")
        val endOnly=GalleryListBatch.Outcome(error=GalleryListBatch.Error.TIMEOUT,acceptedReceipt=true,
            stream=GalleryManifestStream.Snapshot(GalleryManifestStream.Status.RECEIVING,0,0,false,true,null))
        for(acceptRetry in listOf(true,false)) {
            val c=GalleryMediaCatalog()
            c.start(context())
            c.apply(c.nextRequest()!!,GalleryListBatch.Result(mapOf(
                sd to page(sd,newest,boundary,last=false),internal to page(internal,media(internal)))))
            val next=c.nextRequest()!!
            assertEquals(mapOf(sd to 300L),next.cursors)
            c.apply(next,GalleryListBatch.Result(mapOf(sd to endOnly)))
            val retry=c.nextRequest()!!
            assertEquals(mapOf(sd to 400L),retry.cursors)
            val response=if(acceptRetry)page(sd,newest,boundary,older) else endOnly
            c.apply(retry,GalleryListBatch.Result(mapOf(sd to response)))
            assertNull(c.nextRequest())
            assertEquals(acceptRetry,c.snapshot.complete)
            assertEquals(if(acceptRetry)4 else 3,c.snapshot.entries.size)
            if(acceptRetry)assertEquals(400L,c.snapshot.entries.single { it.media==older }.requestCursor)
            else assertEquals("NO_PAGE_DATA",c.snapshot.stores.single { it.store==sd }.error)
        }
    }

    @Test fun olderSdPageUsesDistinctBoundedAnchorsAndStillRequiresTheOriginalOverlap() {
        val known = (300L..700L step 100).map { media(sd, it, "DCIM/CUSTOM/$it.JPG") }
        val older = media(sd, 200, "DCIM/CUSTOM/200.JPG")
        val endOnly = GalleryListBatch.Outcome(error = GalleryListBatch.Error.TIMEOUT, acceptedReceipt = true,
            stream = GalleryManifestStream.Snapshot(GalleryManifestStream.Status.RECEIVING, 0, 0, false, true, null))
        for (lastReply in listOf(endOnly, page(sd, older), page(sd, known[0], older))) {
            val c = GalleryMediaCatalog()
            c.start(context())
            c.apply(c.nextRequest()!!, GalleryListBatch.Result(mapOf(
                sd to page(sd, *known.toTypedArray(), last = false), internal to page(internal, media(internal)))))
            for (cursor in listOf(300L, 400L, 500L, 600L)) {
                val next = c.nextRequest()!!
                assertEquals(mapOf(sd to cursor), next.cursors)
                c.apply(next, GalleryListBatch.Result(mapOf(sd to if (cursor == 600L) lastReply else endOnly)))
            }
            assertNull(c.nextRequest())
            val accepted = lastReply.page?.entries?.contains(known[0]) == true
            assertEquals(accepted, c.snapshot.complete)
            assertEquals(known.size + if (accepted) 2 else 1, c.snapshot.entries.size)
            assertEquals(accepted, c.find(older.key) != null)
        }
    }

    @Test fun acknowledgedInitialSdSilenceDefersOneProbeWithoutRestartingInternalListing() {
        val endOnly=GalleryListBatch.Outcome(error=GalleryListBatch.Error.TIMEOUT,acceptedReceipt=true,
            stream=GalleryManifestStream.Snapshot(GalleryManifestStream.Status.RECEIVING,0,0,false,true,null))
        val middle=media(internal,300,"DCIM/CUSTOM/MIDDLE.JPG")
        val oldest=media(internal,200,"DCIM/CUSTOM/OLDEST.JPG")
        for(recovered in listOf(true,false)) {
            val c=GalleryMediaCatalog()
            c.start(context())
            c.apply(c.nextRequest()!!,GalleryListBatch.Result(mapOf(sd to endOnly,
                internal to page(internal,media(internal,400),last=false))))
            val immediate=c.nextRequest()!!
            assertEquals(sd.newestCursor,immediate.cursors[sd])
            c.apply(immediate,GalleryListBatch.Result(mapOf(sd to endOnly,internal to page(internal,middle,last=false))))
            assertEquals(GalleryMediaCatalog.Phase.DEFERRED,c.snapshot.stores.single { it.store==sd }.phase)
            val internalOnly=c.nextRequest()!!
            assertEquals(mapOf(internal to 300L),internalOnly.cursors)
            c.apply(internalOnly,GalleryListBatch.Result(mapOf(internal to page(internal,oldest))))
            assertTrue(c.snapshot.loading)
            assertFalse(c.snapshot.complete)
            val deferred=c.nextRequest()!!
            assertEquals(mapOf(sd to sd.newestCursor),deferred.cursors)
            c.apply(deferred,GalleryListBatch.Result(mapOf(sd to if(recovered)page(sd,media(sd)) else endOnly)))
            assertEquals(recovered,c.snapshot.complete)
            assertEquals(3,c.snapshot.stores.single { it.store==internal }.entries.size)
            assertNull(c.nextRequest())
        }
    }

    @Test fun aRetryWithoutTheOriginalBoundaryCannotPublishOrFinishTheStore() {
        val newest=media(sd,400,"DCIM/CUSTOM/NEW.JPG")
        val boundary=media(sd,300,"DCIM/CUSTOM/BOUNDARY.JPG")
        val older=media(sd,200,"DCIM/CUSTOM/OLD.JPG")
        val c=GalleryMediaCatalog()
        c.start(context())
        c.apply(c.nextRequest()!!,GalleryListBatch.Result(mapOf(
            sd to page(sd,newest,boundary,last=false),internal to page(internal,media(internal)))))
        val endOnly=GalleryListBatch.Outcome(error=GalleryListBatch.Error.TIMEOUT,acceptedReceipt=true,
            stream=GalleryManifestStream.Snapshot(GalleryManifestStream.Status.RECEIVING,0,0,false,true,null))
        c.apply(c.nextRequest()!!,GalleryListBatch.Result(mapOf(sd to endOnly)))
        c.apply(c.nextRequest()!!,GalleryListBatch.Result(mapOf(sd to page(sd,newest,older))))
        assertNull(c.nextRequest())
        assertFalse(c.snapshot.complete)
        assertEquals(listOf(newest,boundary),c.snapshot.stores.single { it.store==sd }.entries.map { it.media })
        assertEquals("UNCONFIRMED_OVERLAP",c.snapshot.stores.single { it.store==sd }.error)
    }
}
