package app.cameraderie.sony

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.view.View
import app.cameraderie.NativeChecks
import app.cameraderie.gallery.media.LocalMediaShare
import app.cameraderie.gallery.media.MediaImportTransaction
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class SonyMediaNativeTest {
    // startActivitySync must observe a newly created activity. NEW_TASK alone can
    // reuse an existing task root and wait forever for a launch notification.
    // Isolated excluded tasks preserve the user's existing task and settings.
    private val context get()=NativeChecks.instance.targetContext
    private val createdMedia=mutableListOf<Uri>()
    private fun tracked(port:MediaImportTransaction.Port)=object:MediaImportTransaction.Port by port {
        override fun createPending(item:MediaImportTransaction.Item)=port.createPending(item).also { createdMedia.add(Uri.parse(it)) }
    }
    @org.junit.After fun removeOnlyCreatedSyntheticMedia() {
        createdMedia.distinct().forEach { uri ->
            val exists=checkNotNull(context.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns._ID),null,null,null)).use { it.moveToFirst() }
            if(exists)assertEquals(1,context.contentResolver.delete(uri,null,null))
        };createdMedia.clear()
    }

    private fun source(width:Int,height:Int):File {
        val file=File.createTempFile("native-check-",".jpg",context.cacheDir)
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(31,107,180))
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,98,it)) };bitmap.recycle()
        ExifInterface(file.path).apply {
            setAttribute("Make","SONY");setAttribute("Model","ILCE-6400");setAttribute("LensModel","E 50mm F1.8 OSS")
            setAttribute("DateTimeOriginal","2026:10:31 19:45:00");setAttribute("FNumber","4.5");setAttribute("ExposureTime","1/1250")
            setAttribute("PhotographicSensitivity","250");setAttribute("Orientation","6");saveAttributes()
        }
        return file
    }
    @Test fun resizedJpegRetainsRealExifAndCorrectsOrientationAndDimensions() {
        val source=source(2560,1440);val output=File.createTempFile("native-reduced-",".jpg",context.cacheDir)
        try {
            val size=SonyJpegReducer.render(source,output,SonyImportSize.TWO_MEGAPIXEL_JPEG,{false})
            assertTrue(size.width<size.height);assertTrue(size.pixels<=2_000_000)
            val exif=ExifInterface(output.path)
            assertTrue(output.inputStream().use { JpegSampling.read(it).is422 })
            assertEquals("SONY",exif.getAttribute("Make"));assertEquals("ILCE-6400",exif.getAttribute("Model"))
            assertEquals("E 50mm F1.8 OSS",exif.getAttribute("LensModel"));assertEquals("2026:10:31 19:45:00",exif.getAttribute("DateTimeOriginal"))
            assertEquals(1,exif.getAttributeInt("Orientation",0));assertEquals(size.width,exif.getAttributeInt("ImageWidth",0))
            val decoded=BitmapFactory.decodeFile(output.path);assertNotNull(decoded);decoded.recycle()
        } finally { source.delete();output.delete() }
    }
    @Test fun captureMetadataUsesVerifiedImageExifBeforeCameraMetadata() {
        val image = source(64, 48)
        val fallback = CameraCaptureTime.parse("2026-10-30")
        try {
            ExifInterface(image.path).apply {
                setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+05:30")
                saveAttributes()
            }
            val hash = "a".repeat(64)
            val receipt = app.cameraderie.gallery.media.history.ImportHistoryReceipt(
                image.length(), hash, "image/jpeg", true, displayName = "Original.JPG")
            val result = app.cameraderie.gallery.media.history.ImportCaptureMetadata.read(fallback) {
                image.inputStream()
            }.describe(receipt)
            assertEquals("2026-10-31", result.captureDate)
            assertEquals("19:45", result.captureTime)
            assertEquals("+05:30", result.captureOffset)
            assertEquals("EXIF", result.captureSource)
            assertEquals(6, result.exifOrientation)
            assertEquals(receipt, result.copy(exifOrientation = null, captureDate = null,
                captureTime = null, captureOffset = null, captureSource = null))

            val cameraOnly = app.cameraderie.gallery.media.history.ImportCaptureMetadata.read(fallback) {
                throw java.io.IOException("Image metadata unavailable")
            }.describe(receipt)
            assertEquals("2026-10-30", cameraOnly.captureDate)
            assertEquals("CAMERA_METADATA", cameraOnly.captureSource)
            assertNull(cameraOnly.captureTime)
            assertNull(cameraOnly.captureOffset)
        } finally {
            image.delete()
        }
    }
    @Test fun pendingMediaPublishesAfterVerificationAndBuildsReadOnlyShareIntent() {
        val source=source(64,48);var created:Uri?=null
        try {
            val bytes=source.readBytes();val digest=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            val port=tracked(SonyMediaStore(context).port({ _,uri ->
                context.contentResolver.openOutputStream(uri,"w")!!.use { it.write(bytes) }
                MediaImportTransaction.Copied(bytes.size.toLong(),digest)
            },{false},{ _,uri -> created=uri }))
            val testItem=MediaImportTransaction.Item("synthetic","GripBridge-native-check-${System.nanoTime()}.JPG",bytes.size.toLong())
            val result=MediaImportTransaction().run(listOf(testItem),port,{false})
            assertEquals(listOf("synthetic"),result.completed);assertNotNull(created)
            context.contentResolver.query(created!!,arrayOf(MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.SIZE),null,null,null)!!.use {
                assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0));assertEquals(bytes.size.toLong(),it.getLong(1))
            }
            context.contentResolver.openInputStream(created!!)!!.use { assertArrayEquals(bytes,it.readBytes()) }
            val chooser=LocalMediaShare(context).chooser(listOf(created!!))
            assertEquals(Intent.ACTION_CHOOSER,chooser.action)
            @Suppress("DEPRECATION") val send=chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND,send.action);assertEquals("image/jpeg",send.type)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
            assertEquals(0,send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertEquals(1,send.clipData!!.itemCount);assertEquals(created,send.clipData!!.getItemAt(0).uri)
        } finally {
            source.delete()
        }
    }
    @Test fun publicationCallbackFailureKeepsVerifiedPublishedMedia() {
        val source=source(64,48);var created:Uri?=null
        try {
            val bytes=source.readBytes();val digest=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            val port=tracked(SonyMediaStore(context).port({ _,uri ->
                context.contentResolver.openOutputStream(uri,"w")!!.use { it.write(bytes) }
                MediaImportTransaction.Copied(bytes.size.toLong(),digest)
            },{false},{ _,uri -> created=uri;throw java.io.IOException("synthetic metadata failure") }))
            val item=MediaImportTransaction.Item("synthetic-postpublish","GripBridge-native-postpublish-${System.nanoTime()}.JPG",bytes.size.toLong())
            val result=MediaImportTransaction().run(listOf(item),port,{false})
            assertEquals(listOf(item.id),result.completed);assertTrue(result.followUpFailed);assertFalse(result.cleanupFailed)
            context.contentResolver.query(checkNotNull(created),arrayOf(MediaStore.MediaColumns.IS_PENDING),null,null,null)!!.use {
                assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))
            }
            context.contentResolver.openInputStream(checkNotNull(created))!!.use { assertArrayEquals(bytes,it.readBytes()) }
        } finally { source.delete() }
    }
    @Test fun pendingCleanupCannotDeletePublishedMediaOrOtherPendingItems() {
        val source=source(64,48);var published:Uri?=null;var pending:Uri?=null
        try {
            val bytes=source.readBytes();val digest=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            val port=tracked(SonyMediaStore(context).port({ _,uri ->
                context.contentResolver.openOutputStream(uri,"w")!!.use { it.write(bytes) }
                MediaImportTransaction.Copied(bytes.size.toLong(),digest)
            },{false},{ _,uri -> published=uri }))
            val first=MediaImportTransaction.Item("synthetic-kept","GripBridge-native-kept-${System.nanoTime()}.JPG",bytes.size.toLong())
            assertEquals(listOf(first.id),MediaImportTransaction().run(listOf(first),port,{false}).completed)
            pending=Uri.parse(port.createPending(MediaImportTransaction.Item("synthetic-unfinished","GripBridge-native-pending-${System.nanoTime()}.JPG",bytes.size.toLong())))
            assertEquals(MediaImportTransaction.Cleanup.RETAINED,port.cleanup(checkNotNull(published).toString()))
            assertFalse(app.cameraderie.gallery.media.history.LocalImportedMedia(context).removePending(checkNotNull(published).toString()))
            context.contentResolver.query(checkNotNull(pending),arrayOf(MediaStore.MediaColumns.IS_PENDING),null,null,null)!!.use {
                assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0))
            }
            assertEquals(MediaImportTransaction.Cleanup.REMOVED,port.cleanup(checkNotNull(pending).toString()))
            context.contentResolver.query(checkNotNull(pending),arrayOf(MediaStore.MediaColumns._ID),null,null,null)!!.use { assertFalse(it.moveToFirst()) }
            context.contentResolver.openInputStream(checkNotNull(published))!!.use { assertArrayEquals(bytes,it.readBytes()) }
        } finally { source.delete() }
    }
    @Test fun durableImportJournalRetainsReceiptsAndNeverGuessesLegacyQuality() {
        val name="import_history_native_test.db"
        context.deleteDatabase(name)
        var store=app.cameraderie.gallery.media.history.SqliteImportHistoryStore(context,name)
        try {
            val first=app.cameraderie.gallery.media.history.ImportHistoryDraft("native-one","content://media/external_primary/images/media/900000000001","test_2M.JPG",10,1,
                app.cameraderie.gallery.media.history.ImportProvenance.OFFERED_CAMERA_BYTES,"ORIGINAL","Synthetic camera","opaque-test-scope","SESSION","opaque-item")
            val receipt=app.cameraderie.gallery.media.history.ImportHistoryReceipt(10,"a".repeat(64),"image/jpeg",true,64,48,1,"2026-06-10","09:00:00",null,"EXIF","Asia/Kolkata")
            store.begin(first)
            try { store.complete(first.id,42,app.cameraderie.gallery.media.history.ImportCompletionKind.OBSERVED_PUBLICATION);fail("staging cannot complete") } catch(_:IllegalStateException) {}
            store.verified(first.id,receipt);store.close()
            store=app.cameraderie.gallery.media.history.SqliteImportHistoryStore(context,name)
            assertEquals(receipt,store.unfinished().single().receipt)
            store.complete(first.id,42,app.cameraderie.gallery.media.history.ImportCompletionKind.OBSERVED_PUBLICATION)
            store.complete(first.id,43,app.cameraderie.gallery.media.history.ImportCompletionKind.RECOVERED_VERIFIED)
            assertEquals(42L,store.completed().single().completedAt)
            store.legacy(first.copy(id="legacy-conflict",uri="content://media/external_primary/file/900000000001",startedAt=null,provenance=app.cameraderie.gallery.media.history.ImportProvenance.LEGACY_UNKNOWN,requestedQuality=null),null,12)
            assertEquals("ORIGINAL",store.completed().single().draft.requestedQuality)
            val legacy=first.copy(id="legacy-two",uri="content://media/external_primary/images/media/900000000002",startedAt=null,
                provenance=app.cameraderie.gallery.media.history.ImportProvenance.LEGACY_UNKNOWN,requestedQuality=null,cameraModel=null,sourceScope=null,sourceScopeKind=null,sourceItemReference=null)
            val metadata=app.cameraderie.gallery.media.history.ImportHistoryReceipt(10,null,"image/jpeg",false,1154,1732)
            store.legacy(legacy,metadata,12);store.legacy(legacy.copy(id="legacy-repeat"),metadata,12)
            assertEquals(2,store.countCompleted())
            val staging=first.copy(id="unverified",uri="content://media/external_primary/images/media/900000000003")
            store.begin(staging)
            store.legacy(staging.copy(id="legacy-staging",startedAt=null,provenance=app.cameraderie.gallery.media.history.ImportProvenance.LEGACY_UNKNOWN,requestedQuality=null),null,12)
            assertEquals(2,store.countCompleted());assertEquals(staging.id,store.unfinished().single().draft.id)
            try { store.verified(staging.id,metadata);fail("metadata without hash is not a verified receipt") } catch(_:IllegalArgumentException) {}
            store.interrupted(first.id);assertEquals(2,store.countCompleted())
            store.close();store=app.cameraderie.gallery.media.history.SqliteImportHistoryStore(context,name)
            val savedLegacy=store.completed().single { it.draft.id==legacy.id }
            assertNull(savedLegacy.draft.requestedQuality);assertNull(savedLegacy.receipt!!.sha256)
            assertEquals(app.cameraderie.gallery.media.history.ImportProvenance.LEGACY_UNKNOWN,savedLegacy.draft.provenance)
            assertEquals(1732,savedLegacy.receipt!!.height)
            assertNull(store.completed().single { it.draft.id==first.id }.receipt!!.captureOffset)
            val page=store.completedAfterId(limit=1)
            val next=store.completedAfterId(afterId=page.single().draft.id,limit=1)
            assertEquals(2,(page+next).map { it.draft.id }.toSet().size)
        } finally { store.close();context.deleteDatabase(name) }
    }
    @Test fun realPublishedImportPersistsActualReceiptAndSourceBeforeUiLifecycle() {
        val namespace="import_history_port_native_test.db";context.deleteDatabase(namespace)
        var journal=app.cameraderie.gallery.media.history.SqliteImportHistoryStore(context,namespace)
        val source=source(64,48);var created:Uri?=null
        try {
            val bytes=source.readBytes();val sha=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            val repository=app.cameraderie.gallery.media.history.ImportHistoryRepository(journal,app.cameraderie.gallery.media.history.LocalImportedMedia(context))
            val port=tracked(SonyMediaStore(context,repository).port({ _,uri ->
                context.contentResolver.openOutputStream(uri,"w")!!.use { it.write(bytes) };MediaImportTransaction.Copied(bytes.size.toLong(),sha)
            },{false},{ _,uri -> created=uri },historyDraft={ item,uri ->
                app.cameraderie.gallery.media.history.ImportHistoryDraft("native-history-one",uri.toString(),item.name,item.bytes,System.currentTimeMillis(),
                    app.cameraderie.gallery.media.history.ImportProvenance.OFFERED_CAMERA_BYTES,"ORIGINAL_JPEG","Synthetic camera","opaque-scope","SESSION","opaque-source")
            }))
            val item=MediaImportTransaction.Item("synthetic-history","GripBridge-native-history-${System.nanoTime()}_2M.JPG",bytes.size.toLong())
            assertEquals(listOf(item.id),MediaImportTransaction().run(listOf(item),port,{false}).completed)
            assertNotNull(created);assertEquals(1,journal.countCompleted())
            journal.close();journal=app.cameraderie.gallery.media.history.SqliteImportHistoryStore(context,namespace)
            val row=journal.completed().single();assertEquals(created.toString(),row.draft.uri)
            assertEquals("ORIGINAL_JPEG",row.draft.requestedQuality);assertEquals(app.cameraderie.gallery.media.history.ImportProvenance.OFFERED_CAMERA_BYTES,row.draft.provenance)
            val receipt=checkNotNull(row.receipt);assertEquals(sha,receipt.sha256);assertTrue(receipt.sourceHashMatched)
            assertEquals("image/jpeg",receipt.mime);assertEquals(64,receipt.width);assertEquals(48,receipt.height);assertEquals(6,receipt.exifOrientation)
            assertEquals("2026-10-31",receipt.captureDate);assertEquals("19:45",receipt.captureTime)
            assertNull(receipt.captureOffset);assertNotNull(receipt.displayName)
            assertTrue(journal.containsUri(app.cameraderie.gallery.media.history.LocalMediaIdentity.key(created.toString())))
            val open=repository.local.open(created.toString())
            assertEquals(Intent.ACTION_VIEW,open.action);assertEquals(created,open.data);assertEquals("image/jpeg",open.type)
            assertTrue(open.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0);assertEquals(0,open.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertEquals(created,open.clipData!!.getItemAt(0).uri)
            assertEquals(Intent.ACTION_CHOOSER,repository.local.share(listOf(created.toString())).action)
            assertEquals(1,context.contentResolver.delete(checkNotNull(created),null,null))
            assertNull(repository.local.available(created.toString()));assertEquals(1,journal.countCompleted())
            try { repository.local.open(created.toString());fail("missing import must not open") } catch(_:IllegalStateException) {}
        } finally { journal.close();context.deleteDatabase(namespace);source.delete() }
    }
    @Test fun failedHistoryCompletionRecoversPublishedMediaWithoutRepeatingImport() {
        val namespace="import_history_recovery_native_test.db";context.deleteDatabase(namespace)
        val journal=app.cameraderie.gallery.media.history.SqliteImportHistoryStore(context,namespace)
        val source=source(64,48);var created:Uri?=null
        try {
            val bytes=source.readBytes();val sha=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            val failedCommit=object:app.cameraderie.gallery.media.history.ImportHistoryStore by journal {
                override fun complete(id:String,observedAt:Long,kind:app.cameraderie.gallery.media.history.ImportCompletionKind) { throw java.io.IOException("synthetic completion failure") }
            }
            val local=app.cameraderie.gallery.media.history.LocalImportedMedia(context)
            val repository=app.cameraderie.gallery.media.history.ImportHistoryRepository(failedCommit,local)
            val port=tracked(SonyMediaStore(context,repository).port({ _,uri ->
                context.contentResolver.openOutputStream(uri,"w")!!.use { it.write(bytes) };MediaImportTransaction.Copied(bytes.size.toLong(),sha)
            },{false},{ _,uri -> created=uri },historyDraft={ item,uri ->
                app.cameraderie.gallery.media.history.ImportHistoryDraft("native-recover-one",uri.toString(),item.name,item.bytes,System.currentTimeMillis(),app.cameraderie.gallery.media.history.ImportProvenance.PHONE_JPEG_REDUCTION,"TWO_MEGAPIXEL_JPEG")
            }))
            val item=MediaImportTransaction.Item("synthetic-recovery","GripBridge-native-recovery-${System.nanoTime()}.JPG",bytes.size.toLong())
            val result=MediaImportTransaction().run(listOf(item),port,{false})
            assertEquals(listOf(item.id),result.completed);assertTrue(result.followUpFailed);assertNotNull(created)
            assertEquals(0,journal.countCompleted());assertEquals(app.cameraderie.gallery.media.history.ImportHistoryStage.VERIFIED,journal.unfinished().single().stage)
            val recovery=app.cameraderie.gallery.media.history.ImportHistoryRecovery(journal,object:app.cameraderie.gallery.media.history.ImportHistoryRecovery.Media {
                private fun scoped(uri:String) { check(uri==created.toString()) }
                override fun state(uri:String):app.cameraderie.gallery.media.history.ImportHistoryRecovery.LocalState { scoped(uri);return local.state(uri) }
                override fun verify(entry:app.cameraderie.gallery.media.history.ImportHistoryEntry):Boolean { scoped(entry.draft.uri);return local.verify(entry,{false}) }
                override fun removePending(uri:String):Boolean { scoped(uri);return local.removePending(uri) }
            },{System.currentTimeMillis()})
            assertEquals(1,recovery.run().recovered);assertEquals(0,recovery.run().recovered)
            assertEquals(1,journal.countCompleted());assertEquals(app.cameraderie.gallery.media.history.ImportCompletionKind.RECOVERED_VERIFIED,journal.completed().single().completionKind)
            context.contentResolver.openInputStream(checkNotNull(created))!!.use { assertArrayEquals(bytes,it.readBytes()) }
        } finally { journal.close();context.deleteDatabase(namespace);source.delete() }
    }

    @Test fun importedImagesUsesIsolatedHistoryRetainsSelectionAndDisablesMissingActions() {
        val instrumentation=NativeChecks.instance
        val fixtureRun=java.util.UUID.randomUUID().toString()
        val namespace="import_history_screen_native_test-$fixtureRun.db"
        context.deleteDatabase(namespace)
        val journal=app.cameraderie.gallery.media.history.SqliteImportHistoryStore(context,namespace)
        val album="DCIM/GripBridge-native-history-ui-$fixtureRun/"
        val local=app.cameraderie.gallery.media.history.LocalImportedMedia(context,album)
        val repository=app.cameraderie.gallery.media.history.ImportHistoryRepository(journal,local,app.cameraderie.gallery.media.history.ImportHistoryGate())
        val source=source(64,48)
        var activity:android.app.Activity?=null
        val screenActivities=mutableListOf<android.app.Activity>()
        val newestActivity=java.util.concurrent.atomic.AtomicReference<android.app.Activity?>()
        val opened=java.util.concurrent.atomic.AtomicReference<Intent?>()
        try {
            fun fixture(name:String,missing:Boolean):String {
                val values=android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,"image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH,album);put(MediaStore.MediaColumns.IS_PENDING,1)
                }
                val uri=checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values));createdMedia.add(uri)
                context.contentResolver.openOutputStream(uri,"w")!!.use { output -> source.inputStream().use { it.copyTo(output) } }
                context.contentResolver.update(uri,android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)
                val draft=app.cameraderie.gallery.media.history.ImportHistoryDraft(name,uri.toString(),name,source.length(),System.currentTimeMillis(),
                    app.cameraderie.gallery.media.history.ImportProvenance.PHONE_JPEG_REDUCTION,"TWO_MEGAPIXEL_JPEG","Synthetic camera")
                journal.begin(draft)
                val hash=MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
                journal.verified(name,app.cameraderie.gallery.media.history.ImportHistoryReceipt(source.length(),hash,"image/jpeg",true,64,48,6,"2026-10-31","19:45:00",null,"EXIF",displayName=name))
                journal.complete(name,System.currentTimeMillis(),app.cameraderie.gallery.media.history.ImportCompletionKind.OBSERVED_PUBLICATION)
                if(missing)context.contentResolver.delete(uri,null,null)
                return name
            }
            val available=fixture("GripBridge synthetic available.JPG",false)
            val missing=fixture("GripBridge synthetic missing.JPG",true)
            instrumentation.importedImagesFactory={ object:app.cameraderie.gallery.media.history.ImportedImagesActivity() {
                override fun createRepository()=repository
                override fun startActivity(intent:Intent) { opened.set(intent) }
            }.also { screenActivities.add(it);newestActivity.set(it) } }
            activity=instrumentation.startActivitySync(Intent(context,app.cameraderie.gallery.media.history.ImportedImagesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
            fun descendants(node:android.view.accessibility.AccessibilityNodeInfo):List<android.view.accessibility.AccessibilityNodeInfo> = listOf(node)+(0 until node.childCount).flatMap { node.getChild(it)?.let { child -> descendants(child) } ?: emptyList() }
            fun await(label:String,predicate:(List<android.view.accessibility.AccessibilityNodeInfo>)->Boolean):List<android.view.accessibility.AccessibilityNodeInfo> {
                val deadline=android.os.SystemClock.uptimeMillis()+8_000
                do {
                    instrumentation.uiAutomation.rootInActiveWindow?.let { root -> descendants(root).let { if(predicate(it))return it } }
                    android.os.SystemClock.sleep(50)
                } while(android.os.SystemClock.uptimeMillis()<deadline)
                throw AssertionError("Timed out awaiting synthetic history $label")
            }
            fun card(nodes:List<android.view.accessibility.AccessibilityNodeInfo>,name:String)=nodes.single { it.contentDescription?.toString()?.startsWith("$name,")==true }
            var nodes=await("loaded files") { list -> list.any { it.text?.toString()?.startsWith("2 saved imports")==true } && list.count { it.contentDescription?.toString()?.startsWith("GripBridge synthetic")==true }==2 }
            assertTrue(card(nodes,available).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK))
            await("selected file") { list ->
                list.any { it.text?.toString()?.startsWith("1 selected")==true } &&
                    list.any { it.contentDescription?.toString()?.startsWith("$available,")==true && it.isChecked }
            }
            instrumentation.runOnMainSync {
                fun views(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
                val cards=views(activity!!.window.decorView).filterIsInstance<com.google.android.material.card.MaterialCardView>()
                assertTrue(cards.isNotEmpty());assertTrue(cards.all { it.checkedIcon==null })
                val recycler = views(activity!!.window.decorView).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().single()
                val span = (recycler.layoutManager as androidx.recyclerview.widget.GridLayoutManager).spanCount
                val widthDp = recycler.width / recycler.resources.displayMetrics.density
                if (recycler.resources.configuration.fontScale <= 1.15f && widthDp >= 352f) assertEquals(4, span)
                if (span > 1) {
                    val card = cards.single { it.contentDescription.toString().startsWith("$available,") }
                    val labels = views(card).filterIsInstance<android.widget.TextView>().filter { it.visibility == View.VISIBLE }
                    val filename = labels.single { it.text.toString() == available }
                    assertEquals(1, filename.maxLines)
                    assertEquals(android.text.TextUtils.TruncateAt.MIDDLE, filename.ellipsize)
                    assertFalse(labels.any { it.text.toString().contains("Phone album") })
                    val thumbnail = views(card).filterIsInstance<app.cameraderie.ui.RoundedMediaImageView>().single()
                    assertTrue(thumbnail.width > 0)
                    assertEquals(thumbnail.width, thumbnail.height)
                    assertTrue(card.height < thumbnail.height * 1.5)
                }
            }
            instrumentation.runOnMainSync { activity!!.recreate() }
            val recreateDeadline=android.os.SystemClock.uptimeMillis()+8_000
            var replacementReady=false
            do {
                instrumentation.runOnMainSync { replacementReady=activity!!.isDestroyed && newestActivity.get()?.let { it!==activity && it.window.decorView.hasWindowFocus() }==true }
                if(replacementReady)break
                android.os.SystemClock.sleep(50)
            } while(android.os.SystemClock.uptimeMillis()<recreateDeadline)
            assertTrue("Distinct replacement activity must be focused after recreation",replacementReady)
            nodes=await("selection after recreation") { list ->
                list.any { it.text?.toString()?.startsWith("2 saved imports")==true } &&
                    list.any { it.contentDescription?.toString()?.startsWith("$available,")==true && it.isChecked } &&
                    list.any { it.contentDescription?.toString()?.startsWith("Done selecting files")==true && it.isEnabled }
            }
            assertEquals(2,journal.countCompleted())
            val done=nodes.single { it.contentDescription?.toString()?.startsWith("Done selecting files")==true }
            assertTrue(done.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            nodes=await("selection done") { it.any { node -> node.contentDescription?.toString()=="Select files" } }
            assertTrue(card(nodes,missing).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            nodes=await("missing detail") { it.any { node -> node.text?.toString()?.contains("File unavailable on this phone")==true } }
            assertFalse(nodes.single { it.text?.toString()=="Open" && it.className?.toString()?.contains("Button")==true }.isEnabled)
            assertFalse(nodes.single { it.text?.toString()=="Share" && it.className?.toString()?.contains("Button")==true }.isEnabled)
            assertTrue(nodes.any { it.text?.toString()?.contains("Camera clock")==true })
            assertTrue(nodes.single { it.text?.toString()=="Show import details" }.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            nodes=await("expanded import details") { it.any { node -> node.text?.toString()?.contains("Created on this phone · 2M JPEG")==true } }
            assertTrue(nodes.single { it.text?.toString()=="Close" && it.className?.toString()?.contains("Button")==true }.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            nodes=await("available file") { it.any { node -> node.contentDescription?.toString()?.startsWith("$available,")==true } }
            assertTrue(card(nodes,available).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            nodes=await("available detail") { it.any { node -> node.text?.toString()?.contains("Saved on this phone")==true } && it.any { node -> node.text?.toString()=="Open" && node.isEnabled } }
            assertTrue(nodes.single { it.text?.toString()=="Open" && it.className?.toString()?.contains("Button")==true }.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            await("intercepted Open") { opened.get()?.action==Intent.ACTION_VIEW }
            assertTrue(opened.get()!!.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
            opened.set(null)
            nodes=await("available file after Open") { it.any { node -> node.contentDescription?.toString()?.startsWith("$available,")==true } }
            assertTrue(card(nodes,available).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            nodes=await("shareable detail") { it.any { node -> node.text?.toString()=="Share" && node.isEnabled } }
            assertTrue(nodes.single { it.text?.toString()=="Share" && it.className?.toString()?.contains("Button")==true }.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            await("intercepted Share") { opened.get()?.action==Intent.ACTION_CHOOSER }
            assertEquals(2,journal.countCompleted()) // Missing local media does not erase history.

        } finally {
            instrumentation.runOnMainSync { screenActivities.forEach { if(!it.isDestroyed)it.finish() } }
            instrumentation.waitForIdleSync();instrumentation.importedImagesFactory=null
            journal.close();context.deleteDatabase(namespace);source.delete()
        }
    }
    @Test fun localOpenAndShareHonorCancellationBeforeProviderWork() {
        val signal=android.os.CancellationSignal().apply { cancel() }
        val uri="content://media/external_primary/file/900000000001"
        val local=app.cameraderie.gallery.media.history.LocalImportedMedia(context)
        try { local.open(uri,signal);fail("cancelled open must stop") } catch(_:android.os.OperationCanceledException) {}
        try { local.share(listOf(uri),signal);fail("cancelled share must stop") } catch(_:android.os.OperationCanceledException) {}
        try { LocalMediaShare(context).chooser(listOf(Uri.parse(uri)),signal);fail("cancelled chooser must stop") } catch(_:android.os.OperationCanceledException) {}
    }
    @Test fun historyPreviewDecodesSavedImageAtBoundedSizeWithOrientationAndNoUpscale() {
        val local=app.cameraderie.gallery.media.history.LocalImportedMedia(context)
        val loader=app.cameraderie.gallery.media.history.ImportedMediaLoader(context,local)
        try {
            for(size in listOf(64 to 48,2560 to 1440)) {
                val source=source(size.first,size.second)
                try {
                    val bytes=source.readBytes();val sha=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                    var created:Uri?=null
                    val port=tracked(SonyMediaStore(context).port({ _,uri ->
                        context.contentResolver.openOutputStream(uri,"w")!!.use { it.write(bytes) };MediaImportTransaction.Copied(bytes.size.toLong(),sha)
                    },{false},{ _,uri -> created=uri }))
                    val name="GripBridge-native-preview-${System.nanoTime()}.JPG"
                    assertEquals(listOf(name),MediaImportTransaction().run(listOf(MediaImportTransaction.Item(name,name,bytes.size.toLong())),port,{false}).completed)
                    val row=app.cameraderie.gallery.media.history.ImportHistoryEntry(
                        app.cameraderie.gallery.media.history.ImportHistoryDraft(name,checkNotNull(created).toString(),name,bytes.size.toLong(),null,app.cameraderie.gallery.media.history.ImportProvenance.LEGACY_UNKNOWN),app.cameraderie.gallery.media.history.ImportHistoryStage.COMPLETE)
                    val completed=java.util.concurrent.CountDownLatch(1)
                    val preview=java.util.concurrent.atomic.AtomicReference<app.cameraderie.gallery.media.history.ImportedMediaLoader.Result?>()
                    val request=loader.load(row,1536) { result -> preview.set(result);completed.countDown() }
                    try {
                        assertTrue("Bounded saved-image decode completes",completed.await(8,java.util.concurrent.TimeUnit.SECONDS))
                        val result=checkNotNull(preview.get());assertEquals(app.cameraderie.gallery.media.history.ImportedMediaLoader.Availability.AVAILABLE,result.availability)
                        val bitmap=checkNotNull(result.bitmap)
                        if(size.first==64) { assertEquals(48,bitmap.width);assertEquals(64,bitmap.height) }
                        else { assertEquals(864,bitmap.width);assertEquals(1536,bitmap.height) }
                    } finally { request.close() }
                } finally { source.delete() }
            }
        } finally { loader.close() }
    }
    @Test fun bundledNoticesRenderActualVersionAndRetainResearchAndLicenseProvenance() {
        val instrumentation=NativeChecks.instance
        val expected = setOf("APACHE_2_0.txt", "DJI_SOURCE_LICENSE.txt", "NORDIC_BLE_LICENSE.txt",
            "KOTLIN_THIRD_PARTY_NOTICES.txt", "OSMOSIS_REFERENCE_LICENSE.txt")
        assertTrue(context.assets.list("").orEmpty().toSet().containsAll(expected))
        for (file in expected) {
            val text = context.assets.open(file).bufferedReader().use { it.readText() }
            assertTrue(text.length > 100)
        }
        val activity=instrumentation.startActivitySync(Intent(context,app.cameraderie.ui.NoticesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
        try {
            val version=context.packageManager.getPackageInfo(context.packageName,0).longVersionCode
            fun nodes(node:android.view.accessibility.AccessibilityNodeInfo):List<android.view.accessibility.AccessibilityNodeInfo> = listOf(node)+(0 until node.childCount).flatMap { node.getChild(it)?.let { child -> nodes(child) } ?: emptyList() }
            val deadline=android.os.SystemClock.uptimeMillis()+5_000;var ready=false
            do {
                val root=instrumentation.uiAutomation.rootInActiveWindow
                if(root!=null) { val current=nodes(root);ready=current.any { it.text?.toString()?.contains("build $version")==true } && current.any { it.text?.toString()?.contains("Apache License")==true } }
                if(ready)break
                android.os.SystemClock.sleep(50)
            } while(android.os.SystemClock.uptimeMillis()<deadline)
            assertTrue("Bundled provenance and actual installed version are readable",ready)
        } finally { instrumentation.runOnMainSync { activity.finish() };instrumentation.waitForIdleSync() }
    }
    @Test fun savedCameraCredentialsSurviveReloadAsCiphertextAndForgetIsScoped() {
        val namespace="sony_saved_wifi_native_test"
        val bookmarks=SonyCameraBookmarks(context,namespace)
        try {
            context.deleteSharedPreferences(namespace)
            val first=bookmarks.remember("ILCE-6400","DIRECT-Test:ILCE-6400","test-only-password")
            val second=bookmarks.remember("ILCE-6400","DIRECT-Demo:ILCE-6400","other-test-password")
            val reloaded=SonyCameraBookmarks(context,namespace).list()
            assertEquals(2,reloaded.size);assertEquals(first.password,reloaded.first { it.id==first.id }.password)
            val stored=context.getSharedPreferences(namespace,0).all.values.joinToString()
            assertFalse(stored.contains(first.password));assertFalse(stored.contains(first.ssid))
            val updated=bookmarks.remember("ILCE-6400",first.ssid,"changed-test-password")
            assertEquals(first.id,updated.id)
            bookmarks.forget(first.id);assertEquals(listOf(second.id),bookmarks.list().map { it.id })
        } finally { context.deleteSharedPreferences(namespace);bookmarks.clearTestKey() }
    }
    @Test fun partialGalleryShowsFilesButSelectionWaitsForCompleteParse() {
        var outcome:Result<Unit>?=null
        NativeChecks.instance.runOnMainSync {
            outcome=runCatching {
                val themed=android.view.ContextThemeWrapper(context,app.cameraderie.R.style.Theme_Cameraderie)
                val browser=SonyMediaBrowser(themed,app.cameraderie.ui.UiKit(themed),{}, {}, {})
                try {
                    fun descendants(view:android.view.View):List<android.view.View> = listOf(view)+
                        if(view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
                    val item=SonyMediaBrowser.Entry("synthetic","synthetic.JPG",100,false,true,{null},{null})
                    browser.setBusy(true);browser.submit(listOf(item),"Photos",false,true)
                    val views=descendants(browser)
                    val recycler=views.filterIsInstance<androidx.recyclerview.widget.RecyclerView>().single()
                    val select=views.filterIsInstance<android.widget.Button>().single { it.text.toString()=="Select files" }
                    assertEquals(1,recycler.adapter!!.itemCount);assertFalse(select.isEnabled)
                    select.performClick();assertEquals("Select files",select.text.toString())
                    browser.submit(listOf(item),"Photos",false,false)
                    assertFalse(select.isEnabled) // Final snapshot alone cannot unlock busy operations.
                    browser.setBusy(false);assertTrue(select.isEnabled)
                    select.performClick();assertTrue(select.text.toString().startsWith("0 selected"))
                } finally { browser.close() }
            }
        }
        checkNotNull(outcome).getOrThrow()
    }
    @Test fun galleryExposesTheGridOnlyWhileItContainsItems() {
        val themed = android.view.ContextThemeWrapper(context, app.cameraderie.R.style.Theme_Cameraderie)
        var outcome: Result<Unit>? = null
        NativeChecks.instance.runOnMainSync {
            outcome = runCatching {
                val browser = SonyMediaBrowser(themed, app.cameraderie.ui.UiKit(themed), {}, {}, {})
                try {
                    val grid = (0 until browser.childCount).map(browser::getChildAt)
                        .filterIsInstance<androidx.recyclerview.widget.RecyclerView>().single()
                    assertEquals(android.view.View.INVISIBLE, grid.visibility)
                    val photo = SonyMediaBrowser.Entry("photo", "photo.JPG", 100, false, true, { null }, { null })
                    browser.submit(listOf(photo), "Camera", false, true)
                    assertEquals(android.view.View.VISIBLE, grid.visibility)
                    browser.submit(emptyList(), "Camera", false, false)
                    assertEquals(android.view.View.INVISIBLE, grid.visibility)
                } finally {
                    browser.close()
                }
            }
        }
        checkNotNull(outcome).getOrThrow()
    }

    @Test fun queueActionsShareOneRowOnPhoneAndMirrorWithoutClipping() {
        var outcome: Result<Unit>? = null
        NativeChecks.instance.runOnMainSync {
            outcome = runCatching {
                fun checkFooter(widthDp: Int, fontScale: Float, rtl: Boolean, horizontal: Boolean) {
                    val config = android.content.res.Configuration(context.resources.configuration).apply {
                        screenWidthDp = widthDp
                        this.fontScale = fontScale
                    }
                    val themed = android.view.ContextThemeWrapper(context.createConfigurationContext(config), app.cameraderie.R.style.Theme_Cameraderie)
                    val ui = app.cameraderie.ui.UiKit(themed)
                    val browser = SonyMediaBrowser(themed, ui, {}, {}, {}, queueSelected = {})
                    try {
                        browser.layoutDirection = if (rtl) android.view.View.LAYOUT_DIRECTION_RTL else android.view.View.LAYOUT_DIRECTION_LTR
                        browser.submit(listOf(SonyMediaBrowser.Entry("video", "clip.MP4", 100, false, true, { null }, { null }, video = true)), "Camera", false, false)
                        fun views(view: android.view.View): Sequence<android.view.View> = sequence {
                            yield(view)
                            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
                        }
                        val select = views(browser).filterIsInstance<android.widget.Button>().single { it.text.toString() == "Select files" }
                        val queue = views(browser).filterIsInstance<android.widget.Button>().single { it.text.toString() == "Queue selected (0)" }
                        assertEquals(android.view.View.GONE, queue.visibility)
                        assertTrue(select.performClick())
                        val width = ui.dp(widthDp - 32)
                        val height = ui.dp(780)
                        val exact = android.view.View.MeasureSpec.EXACTLY
                        browser.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, exact), android.view.View.MeasureSpec.makeMeasureSpec(height, exact))
                        browser.layout(0, 0, width, height)
                        for (button in listOf(select, queue)) {
                            assertTrue(button.height >= ui.dp(48))
                            assertTrue(button.left >= 0 && button.right <= width)
                            assertTrue(button.layout.getLineEnd(button.lineCount - 1) == button.text.length)
                        }
                        if (horizontal) {
                            assertSame(select.parent,queue.parent)
                            assertTrue(select.top < queue.bottom && queue.top < select.bottom)
                            if (rtl) assertTrue(queue.right <= select.left) else assertTrue(select.right <= queue.left)
                        } else {
                            assertTrue(select.bottom <= queue.top)
                        }
                    } finally { browser.close() }
                }
                checkFooter(412, 1f, false, true)
                checkFooter(412, 1f, true, true)
                checkFooter(320, 2f, false, false)
            }
        }
        checkNotNull(outcome).getOrThrow()
    }
    @Test fun importPreferenceSurvivesReloadWithoutChangingOtherChoices() {
        val namespace="media_imports_native_test"
        try {
            context.deleteSharedPreferences(namespace)
            val first=SonyImportPreferences(context,namespace)
            assertEquals(SonyImportPreference.ORIGINAL,first.choice)
            assertFalse(first.allowGalleryScreenshots)
            first.allowGalleryScreenshots=true
            assertTrue(SonyImportPreferences(context,namespace).allowGalleryScreenshots)
            first.choice=SonyImportPreference.TWO_M
            assertEquals(SonyImportPreference.TWO_M,SonyImportPreferences(context,namespace).choice)
            first.choice=SonyImportPreference.ASK
            assertEquals(SonyImportPreference.ASK,SonyImportPreferences(context,namespace).choice)
        } finally { context.deleteSharedPreferences(namespace) }
    }
    @Test fun calendarUsesPhotoTilesAndLeavesEmptyDatesAsPlainNumbers() {
        var outcome:Result<Unit>?=null
        NativeChecks.instance.runOnMainSync {
            outcome=runCatching {
                val themed=android.view.ContextThemeWrapper(context,app.cameraderie.R.style.Theme_Cameraderie)
                val date=java.time.LocalDate.of(2026,11,8)
                val calendar=SonyCaptureCalendar(themed,setOf(date),mapOf(date to SonyCaptureCalendar.Photo("synthetic",{null})),{})
                try {
                    val photo=calendar.view.findViewWithTag<android.widget.FrameLayout>("capture-day-$date")
                    val empty=calendar.view.findViewWithTag<android.widget.FrameLayout>("capture-day-${date.plusDays(1)}")
                    assertTrue(photo.isClickable);assertFalse(empty.isClickable)
                    assertTrue(photo.getChildAt(0) is android.widget.TextView)
                    assertTrue(photo.getChildAt(1) is com.google.android.material.card.MaterialCardView)
                    assertTrue(empty.getChildAt(0) is android.widget.TextView);assertNull(empty.background)
                    assertEquals("9",(empty.getChildAt(0) as android.widget.TextView).text.toString())
                    assertTrue(photo.contentDescription.contains("photos available"))
                } finally { calendar.close() }
            }
        }
        checkNotNull(outcome).getOrThrow()
    }
    @Test fun parseUpdatesScrollToTopAndKeepSelectionLocked() {
        var outcome:Result<Unit>?=null
        NativeChecks.instance.runOnMainSync {
            outcome=runCatching {
                val themed=android.view.ContextThemeWrapper(context,app.cameraderie.R.style.Theme_Cameraderie)
                val browser=SonyMediaBrowser(themed,app.cameraderie.ui.UiKit(themed),{}, {}, {})
                try {
                    fun views(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
                    fun items(count:Int)=(0 until count).map { SonyMediaBrowser.Entry("$it","photo${it.toString().padStart(3,'0')}.JPG",100,false,true,{null},{null}) }
                    fun layout() { browser.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(1600,android.view.View.MeasureSpec.EXACTLY));browser.layout(0,0,1080,1600) }
                    browser.setBusy(true);browser.submit(items(60),"Photos",false,true);layout()
                    val recycler=views(browser).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().single()
                    val manager=recycler.layoutManager as androidx.recyclerview.widget.GridLayoutManager
                    if (manager.spanCount > 1) {
                        val tile = recycler.findViewHolderForAdapterPosition(0)!!.itemView
                        val labels = views(tile).filterIsInstance<android.widget.TextView>().filter { it.visibility == View.VISIBLE }
                        assertEquals(1, labels.single { it.text.startsWith("photo") && it.text.endsWith(".JPG") }.maxLines)
                        assertFalse(labels.any { it.text.toString().contains("100 B") })
                        assertTrue(tile.contentDescription.toString().contains("JPG · 100 B"))
                    }
                    manager.scrollToPositionWithOffset(40,0);layout();assertTrue(manager.findFirstVisibleItemPosition()>=38)
                    browser.submit(items(61),"Photos",false,true);layout();assertEquals(0,manager.findFirstVisibleItemPosition())
                    assertFalse(views(browser).filterIsInstance<android.widget.Button>().single { it.text.toString()=="Select files" }.isEnabled)
                } finally { browser.close() }
            }
        }
        checkNotNull(outcome).getOrThrow()
    }
    @Test fun viewingAShootDoesNotSelectButExplicitSelectDoes() {
        var outcome:Result<Unit>?=null
        NativeChecks.instance.runOnMainSync {
            outcome=runCatching {
                val themed=android.view.ContextThemeWrapper(context,app.cameraderie.R.style.Theme_Cameraderie)
                val browser=SonyMediaBrowser(themed,app.cameraderie.ui.UiKit(themed),{}, {}, {})
                try {
                    fun views(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
                    val capture=CameraCaptureTime.parse("2026-11-08T09:00:00")
                    val entries=(1..2).map { SonyMediaBrowser.Entry("$it","photo$it.JPG",100,false,true,{null},{null},capture) }
                    browser.submit(entries,"Photos",false,false);browser.setBusy(false)
                    val shoot=ShootSessions().group("synthetic",entries.map { ShootSessions.Item(it.key,it.capture) },true).single()
                    browser.showShoot(shoot,false)
                    assertTrue(views(browser).filterIsInstance<android.widget.Button>().any { it.text.toString()=="Select shoot (2)" })
                    browser.showShoot(shoot,true)
                    assertTrue(views(browser).filterIsInstance<android.widget.Button>().any { it.text.toString()=="2 selected · Options" })
                    assertTrue(views(browser).filterIsInstance<android.widget.Button>().any { it.text.toString()=="Import selected (2)" && it.isEnabled })
                } finally { browser.close() }
            }
        }
        checkNotNull(outcome).getOrThrow()
    }
    @Test fun mediaDialogWindowExplicitlyAppliesScreenshotProtection() {
        var outcome:Result<Unit>?=null
        NativeChecks.instance.runOnMainSync {
            outcome=runCatching {
                val themed=android.view.ContextThemeWrapper(context,app.cameraderie.R.style.Theme_Cameraderie)
                val dialog=android.app.Dialog(themed)
                val window=checkNotNull(dialog.window)
                val flag=android.view.WindowManager.LayoutParams.FLAG_SECURE
                SonyScreenWindows.apply(window,SonyScreenProtection.required(false,true))
                assertTrue(window.attributes.flags and flag!=0)
                SonyScreenWindows.apply(window,SonyScreenProtection.required(true,true))
                assertEquals(0,window.attributes.flags and flag)
                SonyScreenWindows.apply(window,SonyScreenProtection.required(true,false))
                assertTrue(window.attributes.flags and flag!=0)
                dialog.dismiss()
            }
        }
        checkNotNull(outcome).getOrThrow()
    }
    @Test fun cameraSessionNotificationHasLocalExplicitDisconnectAndProgress() {
        val notification=SonyGallerySessionNotification.build(context,"synthetic-lease","Importing 1 of 2",42)
        assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT!=0)
        assertEquals(android.app.Notification.CATEGORY_SERVICE,notification.category)
        assertEquals(42,notification.extras.getInt(android.app.Notification.EXTRA_PROGRESS))
        assertEquals("Disconnect",notification.actions.single().title.toString())
        assertEquals(context.packageName,notification.actions.single().actionIntent.creatorPackage)
        assertNotNull(notification.contentIntent)
    }
    @Test fun gallerySelectionSurvivesLayoutAndSectionButNeverCrossesCameraScope() {
        var outcome:Result<Unit>?=null
        NativeChecks.instance.runOnMainSync {
            outcome=runCatching {
                val themed=android.view.ContextThemeWrapper(context,app.cameraderie.R.style.Theme_Cameraderie)
                val browser=SonyMediaBrowser(themed,app.cameraderie.ui.UiKit(themed),{}, {}, {})
                fun views(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
                try {
                    browser.setSourceScope("camera-one")
                    val capture=CameraCaptureTime.parse("2026-06-10T09:00:00")
                    val entries=listOf(SonyMediaBrowser.Entry("a","first.JPG",100,false,true,{null},{null},capture),SonyMediaBrowser.Entry("b","second.JPG",100,false,true,{null},{null},capture),SonyMediaBrowser.Entry("v","clip.MP4",100,false,true,{null},{null},capture,true))
                    browser.submit(entries,"Photos",false,false);browser.setBusy(false)
                    browser.showShoot(ShootSessions().group("camera-one",entries.take(2).map { ShootSessions.Item(it.key,it.capture) },true).single(),true)
                    browser.setLayoutMode(SonyGalleryLayout.Mode.LIST)
                    assertTrue(views(browser).filterIsInstance<android.widget.Button>().any { it.text.toString()=="2 selected · Options" })
                    views(browser).filterIsInstance<android.widget.Button>().single { it.text.toString()=="Videos (0)" }.performClick()
                    views(browser).single { it.contentDescription=="Clear date and shoot filter" }.performClick()
                    assertTrue(views(browser).filterIsInstance<android.widget.Button>().any { it.text.toString()=="2 selected · 2 hidden · Options" })
                    browser.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(1600,android.view.View.MeasureSpec.EXACTLY));browser.layout(0,0,1080,1600)
                    val card=views(browser).filterIsInstance<com.google.android.material.card.MaterialCardView>().single { it.isCheckable }
                    val info=card.createAccessibilityNodeInfo()
                    assertNull(card.checkedIcon);assertTrue(info.isCheckable);assertFalse(info.isChecked);assertEquals(android.widget.CheckBox::class.java.name,info.className.toString())
                    assertTrue(info.contentDescription.contains("clip.MP4"))
                    browser.setSourceScope("camera-two")
                    assertFalse(views(browser).filterIsInstance<android.widget.Button>().any { it.text.toString().startsWith("2 selected") })
                } finally { browser.close() }
            }
        }
        checkNotNull(outcome).getOrThrow()
    }
    @Test fun shootSelectionReports500LimitAndCannotImportAnIncompleteUniverse() {
        var outcome:Result<Unit>?=null
        NativeChecks.instance.runOnMainSync {
            outcome=runCatching {
                val themed=android.view.ContextThemeWrapper(context,app.cameraderie.R.style.Theme_Cameraderie)
                val browser=SonyMediaBrowser(themed,app.cameraderie.ui.UiKit(themed),{}, {}, {})
                fun views(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
                try {
                    val capture=CameraCaptureTime.parse("2026-06-10T09:00:00")
                    val entries=(1..501).map { SonyMediaBrowser.Entry("$it","synthetic$it.JPG",100,false,true,{null},{null},capture) }
                    browser.submit(entries,"Photos",false,false);browser.setBusy(false)
                    browser.showShoot(ShootSessions().group("synthetic",entries.map { ShootSessions.Item(it.key,it.capture) },true).single(),true)
                    assertTrue(views(browser).filterIsInstance<android.widget.Button>().any { it.text.toString()=="Import selected (500)" && it.isEnabled })
                    browser.submit(entries,"Photos",false,true)
                    assertFalse(views(browser).filterIsInstance<android.widget.Button>().single { it.text.toString()=="Import selected (500)" }.isEnabled)
                } finally { browser.close() }
            }
        }
        checkNotNull(outcome).getOrThrow()
    }
    @Test fun calendarCountsUseDeclaredScopeAndScaleWithLargeText() {
        var outcome:Result<Unit>?=null
        NativeChecks.instance.runOnMainSync {
            outcome=runCatching {
                val config=android.content.res.Configuration(context.resources.configuration).apply { fontScale=2f }
                val themed=android.view.ContextThemeWrapper(context.createConfigurationContext(config),app.cameraderie.R.style.Theme_Cameraderie)
                val date=java.time.LocalDate.of(2026,6,10)
                val calendar=SonyCaptureCalendar(themed,setOf(date),emptyMap(),{},mapOf(date to 123),"videos",false)
                try {
                    val cell=calendar.view.findViewWithTag<android.widget.FrameLayout>("capture-day-$date")
                    assertTrue(cell.contentDescription.contains("123 videos"));assertTrue(cell.contentDescription.contains("provisional"))
                    assertTrue((cell.getChildAt(0).layoutParams.height/themed.resources.displayMetrics.density)>=48)
                    val count=(0 until cell.childCount).map { cell.getChildAt(it) }.filterIsInstance<android.widget.TextView>().single { it.text.toString()=="123" }
                    assertTrue(count.layoutParams.height/themed.resources.displayMetrics.density>=36)
                } finally { calendar.close() }
            }
        }
        checkNotNull(outcome).getOrThrow()
    }
    @Test fun switchingDateViewsKeepsTheReplacementRowsAfterOldDismissal() {
        val instrumentation=NativeChecks.instance
        val activity=instrumentation.startActivitySync(android.content.Intent(context,app.cameraderie.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK or android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
        var picker:SonySessionPicker?=null
        try {
            instrumentation.runOnMainSync {
                picker=SonySessionPicker(activity)
                val capture=CameraCaptureTime.parse("2026-06-10T09:00:00")
                val items=listOf(SonyDateSummary.Item("synthetic-known",capture,false),SonyDateSummary.Item("synthetic-unknown",null,false))
                picker!!.show("synthetic-date-ui",items.map { ShootSessions.Item(it.id,it.capture) },true,{_,_->},{},
                    summary=SonyDateSummary.summarize(items,true),countScope=SonyDateSummary.Scope.PHOTOS,viewDate={})
            }
            instrumentation.waitForIdleSync()
            fun nodes(node:android.view.accessibility.AccessibilityNodeInfo):List<android.view.accessibility.AccessibilityNodeInfo> = listOf(node)+(0 until node.childCount).flatMap { node.getChild(it)?.let { child -> nodes(child) } ?: emptyList() }
            val automation=instrumentation.uiAutomation
            // Main-thread idleness precedes WindowManager/accessibility publication.
            // Await the expected window, bounded, instead of assuming its root exists.
            fun awaitWindow(label:String,predicate:(List<android.view.accessibility.AccessibilityNodeInfo>)->Boolean):List<android.view.accessibility.AccessibilityNodeInfo> {
                val deadline=android.os.SystemClock.uptimeMillis()+5_000
                do {
                    val root=automation.rootInActiveWindow
                    if(root!=null) {
                        val published=nodes(root)
                        if(predicate(published))return published
                    }
                    android.os.SystemClock.sleep(50)
                } while(android.os.SystemClock.uptimeMillis()<deadline)
                throw AssertionError("Timed out awaiting $label accessibility window")
            }
            var current=awaitWindow("initial calendar") { list -> list.any { it.text?.toString()=="Date list" } }
            val dateList=current.single { it.text?.toString()=="Date list" }
            check(dateList.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            current=awaitWindow("replacement date list") { list -> list.any { it.contentDescription?.toString()?.startsWith("Unknown capture date,")==true } }
            val calendar=current.single { it.text?.toString()=="Calendar" }
            check(calendar.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            awaitWindow("replacement calendar counts") { list -> list.any { it.contentDescription?.toString()?.contains("2 photos/other files")==true || it.contentDescription?.toString()?.contains("1 photos/other files")==true } }
        } finally {
            instrumentation.runOnMainSync { picker?.close();activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }
    @Test fun calendarViewportFitsSevenColumnsAndOnlyScrollsForLargeText() {
        var outcome:Result<Unit>?=null
        NativeChecks.instance.runOnMainSync {
            outcome=runCatching {
                for(scale in listOf(1f,2f)) {
                    val config=android.content.res.Configuration(context.resources.configuration).apply { fontScale=scale }
                    val themed=android.view.ContextThemeWrapper(context.createConfigurationContext(config),app.cameraderie.R.style.Theme_Cameraderie)
                    val ui=app.cameraderie.ui.UiKit(themed)
                    val date=java.time.LocalDate.of(2026,6,10)
                    val calendar=SonyCaptureCalendar(themed,setOf(date),emptyMap(),{},mapOf(date to 123),"photos/other files",true)
                    try {
                        val viewport=SonyCalendarViewport(themed,calendar.view)
                        val width=ui.dp(360)
                        viewport.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(ui.dp(600),android.view.View.MeasureSpec.AT_MOST))
                        viewport.layout(0,0,width,viewport.measuredHeight)
                        if(scale==1f) {
                            assertEquals(width,calendar.view.width)
                            assertFalse(viewport.canScrollHorizontally(1))
                        } else {
                            assertTrue(calendar.view.width>width)
                            assertTrue(viewport.canScrollHorizontally(1))
                        }
                        fun views(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
                        val weekdays=views(calendar.view).filterIsInstance<android.widget.TextView>().filter { it.contentDescription?.toString() in java.time.DayOfWeek.entries.map { day -> day.getDisplayName(java.time.format.TextStyle.FULL,themed.resources.configuration.locales[0]) } }
                        assertEquals(7,weekdays.size)
                        assertTrue(weekdays.all { it.height>=kotlin.math.ceil(it.paint.fontSpacing).toInt() })
                    } finally { calendar.close() }
                }
            }
        }
        checkNotNull(outcome).getOrThrow()
    }
    @Test fun roundedMediaClipsFitAndCropImagesWithoutStretching() {
        var outcome:Result<Unit>?=null
        NativeChecks.instance.runOnMainSync {
            outcome=runCatching {
                val themed=android.view.ContextThemeWrapper(context,app.cameraderie.R.style.Theme_Cameraderie)
                val source=Bitmap.createBitmap(40,80,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
                try {
                    for(scale in listOf(android.widget.ImageView.ScaleType.FIT_CENTER,android.widget.ImageView.ScaleType.CENTER_CROP)) {
                        val image=app.cameraderie.ui.RoundedMediaImageView(themed,16f).apply { scaleType=scale;setImageBitmap(source) }
                        image.measure(android.view.View.MeasureSpec.makeMeasureSpec(200,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(160,android.view.View.MeasureSpec.EXACTLY))
                        image.layout(0,0,200,160)
                        val target=Bitmap.createBitmap(200,160,Bitmap.Config.ARGB_8888)
                        try {
                            val canvas=android.graphics.Canvas(target);canvas.drawColor(android.graphics.Color.WHITE);image.draw(canvas)
                            assertEquals(android.graphics.Color.RED,target.getPixel(100,20))
                            if(scale==android.widget.ImageView.ScaleType.FIT_CENTER) {
                                assertEquals(android.graphics.Color.WHITE,target.getPixel(61,1))
                                assertEquals(android.graphics.Color.WHITE,target.getPixel(20,80))
                            } else assertEquals(android.graphics.Color.WHITE,target.getPixel(1,1))
                        } finally { target.recycle() }
                        image.setImageDrawable(null)
                    }
                } finally { source.recycle() }
            }
        }
        checkNotNull(outcome).getOrThrow()
    }
    @Test fun jpeg422PreservesColourLevelsAndActualSampling() {
        val colours=intArrayOf(android.graphics.Color.BLACK,android.graphics.Color.WHITE,android.graphics.Color.RED,android.graphics.Color.GREEN,android.graphics.Color.BLUE,android.graphics.Color.rgb(31,107,180))
        val bitmap=Bitmap.createBitmap(96,32,Bitmap.Config.ARGB_8888)
        for(y in 0 until 32) for(x in 0 until 96) bitmap.setPixel(x,y,colours[x/16])
        val file=File.createTempFile("native-422-",".jpg",context.cacheDir)
        try {
            SonyJpeg422.encode(bitmap,file,{false});assertTrue(file.inputStream().use { JpegSampling.read(it).is422 })
            val decoded=BitmapFactory.decodeFile(file.path)
            assertEquals(96,decoded.width);assertEquals(32,decoded.height)
            for(index in colours.indices) {
                val expected=colours[index];val actual=decoded.getPixel(index*16+8,16)
                assertTrue(kotlin.math.abs(android.graphics.Color.red(expected)-android.graphics.Color.red(actual))<=6)
                assertTrue(kotlin.math.abs(android.graphics.Color.green(expected)-android.graphics.Color.green(actual))<=6)
                assertTrue(kotlin.math.abs(android.graphics.Color.blue(expected)-android.graphics.Color.blue(actual))<=6)
            }
            decoded.recycle()
        } finally { bitmap.recycle();file.delete() }
    }

    @Test fun storageFilterKeepsHiddenSelectionAndRepeatedBusyStateDoesNotRestartPreviews() {
        var outcome:Result<Unit>?=null
        NativeChecks.instance.runOnMainSync { outcome=runCatching {
            val themed=android.view.ContextThemeWrapper(context,app.cameraderie.R.style.Theme_Cameraderie)
            val browser=SonyMediaBrowser(themed,app.cameraderie.ui.UiKit(themed),{},{},{})
            fun views(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
            try {
                val time=CameraCaptureTime.parse("2026-06-10T09:00:00")
                val items=listOf(
                    SonyMediaBrowser.Entry("sd:one","same.JPG",100,false,true,{null},{null},time,storageId="SD",storageLabel="SD card"),
                    SonyMediaBrowser.Entry("internal:one","same.JPG",100,false,true,{null},{null},time,storageId="INTERNAL",storageLabel="Internal"))
                browser.submit(items,"Camera",false,false)
                browser.showShoot(ShootSessions().group("synthetic-storage",items.map { ShootSessions.Item(it.key,it.capture) },true).single(),true)
                browser.setStorageFilter("SD")
                assertTrue(views(browser).filterIsInstance<android.widget.Button>().any { it.text.toString()=="2 selected · 1 hidden · Options" })
                val recycler=views(browser).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().single()
                var changes=0
                val observer=object:androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
                    override fun onItemRangeChanged(start:Int,count:Int,payload:Any?) { changes++ }
                }
                recycler.adapter!!.registerAdapterDataObserver(observer)
                browser.setBusy(false);browser.setBusy(false);assertEquals(0,changes)
                browser.setBusy(true);browser.setBusy(true);assertEquals(1,changes)
                browser.setBusy(false);assertEquals(2,changes)
                recycler.adapter!!.unregisterAdapterDataObserver(observer)
            } finally { browser.close() }
        } };checkNotNull(outcome).getOrThrow()
    }
    @Test fun cameraMediaNavigationDoesNotOpenACameraSession() {
        val instrumentation=NativeChecks.instance
        val connected=java.util.concurrent.CountDownLatch(1)
        var bridge:app.cameraderie.GalleryService?=null
        val connection=object:android.content.ServiceConnection {
            override fun onServiceConnected(name:android.content.ComponentName,binder:android.os.IBinder) { bridge=(binder as app.cameraderie.GalleryService.LocalBinder).service;connected.countDown() }
            override fun onServiceDisconnected(name:android.content.ComponentName) {}
        }
        var bound=false;var activity:android.app.Activity?=null
        try {
            instrumentation.runOnMainSync { bound=context.bindService(Intent(context,app.cameraderie.GalleryService::class.java),connection,android.content.Context.BIND_AUTO_CREATE) }
            assertTrue(bound);assertTrue(connected.await(5,java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.runOnMainSync { assertFalse(checkNotNull(bridge).sessionActive);assertNull(bridge!!.galleryMediaState) }
            activity=instrumentation.startActivitySync(Intent(context,app.cameraderie.gallery.media.GalleryMediaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                fun views(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
                assertFalse(bridge!!.sessionActive);assertNull(bridge!!.galleryMediaState)
                assertEquals(app.cameraderie.gallery.GalleryConnectFlow.Stage.IDLE,bridge!!.galleryState.stage)
                val load=views(activity!!.window.decorView).single { it.contentDescription?.toString()=="Refresh camera library" }
                assertFalse(load.isEnabled)
            }
        } finally {
            instrumentation.runOnMainSync { activity?.finish();if(bound)context.unbindService(connection) }
            instrumentation.waitForIdleSync()
        }
    }


    @Test fun inlineGalleryControlsAreRemovedWhileActiveFilterAndIndexingGuardsRemain() {
        var outcome:Result<Unit>?=null
        NativeChecks.instance.runOnMainSync { outcome=runCatching {
            val themed=android.view.ContextThemeWrapper(context,app.cameraderie.R.style.Theme_Cameraderie)
            var filters=0
            val browser=SonyMediaBrowser(themed,app.cameraderie.ui.UiKit(themed),{},{},{},onFilterChanged={filters++})
            fun views(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
            try {
                val time=CameraCaptureTime.parse("2026-06-10T09:00:00")
                val items=listOf(SonyMediaBrowser.Entry("one","one.JPG",100,false,true,{null},{null},time))
                browser.submit(items,"Camera",false,true);browser.setBusy(true)
                assertTrue(browser.browsingControlsEnabled)
                assertFalse(views(browser).filterIsInstance<android.widget.Button>().any { it.text.toString()=="Date & shoot" || it.text.toString().startsWith("Layout:") })
                browser.submit(items,"Camera",false,false);assertFalse(browser.browsingControlsEnabled)
                browser.setBusy(false);assertTrue(browser.browsingControlsEnabled)
                val before=filters
                browser.showShoot(ShootSessions().group("synthetic-menu",items.map { ShootSessions.Item(it.key,it.capture) },true).single(),false)
                assertTrue(browser.hasDateFilter);assertTrue(filters>before)
                val clear=views(browser).single { it.contentDescription=="Clear date and shoot filter" };assertEquals(android.view.View.VISIBLE,clear.visibility)
                clear.performClick();assertFalse(browser.hasDateFilter);assertTrue(filters>before+1)
            } finally { browser.close() }
        } };checkNotNull(outcome).getOrThrow()
    }

    @Test fun recordedPreviewReportsInterruptionAndClosesWhileLoading() {
        val instrumentation = NativeChecks.instance
        val activity = instrumentation.startActivitySync(Intent(context,app.cameraderie.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
        val request = java.util.concurrent.CountDownLatch(1)
        val disconnected = java.util.concurrent.CountDownLatch(1)
        val dismissed = java.util.concurrent.CountDownLatch(1)
        val media = app.cameraderie.gallery.protocol.GalleryMediaPage.Media(
            app.cameraderie.gallery.protocol.GalleryMediaPage.Key(
                app.cameraderie.gallery.protocol.GalleryCameraEvidence.Store.INTERNAL,"DCIM/CUSTOM/fixture.MP4"),
            "MISC/THM/fixture.scr",1,10,3,1)
        val http = app.cameraderie.gallery.media.GalleryHttpMedia({
            val response = object : java.net.HttpURLConnection(java.net.URL("http://localhost/")) {
                override fun getResponseCode(): Int {
                    request.countDown()
                    if (!disconnected.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw java.net.SocketTimeoutException("The camera session did not release its preview request")
                    }
                    throw java.io.InterruptedIOException("The preview request was disconnected")
                }
                override fun connect() = Unit
                override fun disconnect() = disconnected.countDown()
                override fun usingProxy() = false
            }
            object : app.cameraderie.gallery.media.GalleryHttpMedia.Request {
                override val connection = response
                override fun close() = response.disconnect()
            }
        },{ true })
        var player: app.cameraderie.gallery.media.GalleryClipPlayer? = null
        try {
            instrumentation.runOnMainSync {
                val owner = activity as androidx.appcompat.app.AppCompatActivity
                player = app.cameraderie.gallery.media.GalleryClipPlayer(owner,app.cameraderie.ui.UiKit(owner),
                    "Synthetic recording",app.cameraderie.gallery.media.GalleryClipDataSource(http,media)) {
                    dismissed.countDown()
                }
            }
            assertTrue("Preview started after the surface was created",request.await(8,java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.runOnMainSync { http.close() }
            assertTrue("Ending the session disconnected the pending request", disconnected.await(1, java.util.concurrent.TimeUnit.SECONDS))
            fun find(node: android.view.accessibility.AccessibilityNodeInfo, label: String): android.view.accessibility.AccessibilityNodeInfo? {
                if (node.text?.toString()?.startsWith(label) == true) return node
                return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let { child -> find(child, label) } }
            }
            fun awaitNode(label: String): android.view.accessibility.AccessibilityNodeInfo {
                val deadline = android.os.SystemClock.uptimeMillis() + 8_000
                while (android.os.SystemClock.uptimeMillis() < deadline) {
                    instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, label) }?.let { return it }
                    android.os.SystemClock.sleep(50)
                }
                error("Preview did not show $label")
            }
            awaitNode("Preview interrupted.")
            assertFalse(awaitNode("Play preview").isEnabled)
            assertTrue(awaitNode("Close").performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue("Dialog dismissed once without a lifecycle error",dismissed.await(8,java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
        } finally {
            instrumentation.runOnMainSync { player?.close(); activity.finish() }
        }
    }
}
