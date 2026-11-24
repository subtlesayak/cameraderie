package app.cameraderie.gallery.media

import android.content.Context
import app.cameraderie.gallery.protocol.GalleryMediaPage
import app.cameraderie.gallery.media.history.*
import app.cameraderie.sony.SonyMediaStore
import java.security.MessageDigest
import java.util.UUID

/** An original endpoint, with a separate fresh page-identity check by the owning
 * controller. Shared pending publication/readback/history, no resize or RAW rename. */
internal object GalleryOriginalImporter {
    enum class PriorAttempt { RETRY, COMPLETE, REVIEW }

    /** Reconcile only the URI linked to this attempt, under the publication gate. */
    fun priorAttempt(repository: ImportHistoryRepository, id: String, cancelled: () -> Boolean): PriorAttempt {
        val store = repository.store as SqliteImportHistoryStore
        var outcome = PriorAttempt.REVIEW
        ImportHistoryGate.process.tryRecover {
            check(!cancelled())
            val entry = store.find(id)
            if (entry == null) {
                outcome = PriorAttempt.RETRY
            } else if (entry.stage == ImportHistoryStage.COMPLETE) {
                outcome = PriorAttempt.COMPLETE
            } else {
                val local = repository.local
                val state = local.state(entry.draft.uri)
                check(!cancelled())
                when (state) {
                    ImportHistoryRecovery.LocalState.ABSENT -> {
                        store.interrupted(id)
                        outcome = PriorAttempt.RETRY
                    }
                    ImportHistoryRecovery.LocalState.OWNED_PENDING -> if (local.removePending(entry.draft.uri)) {
                        store.interrupted(id)
                        outcome = PriorAttempt.RETRY
                    }
                    ImportHistoryRecovery.LocalState.OWNED_PUBLISHED -> {
                        if (entry.stage == ImportHistoryStage.VERIFIED && local.verify(entry, cancelled)) {
                            check(!cancelled())
                            store.complete(id, System.currentTimeMillis(), ImportCompletionKind.RECOVERED_VERIFIED)
                            outcome = PriorAttempt.COMPLETE
                        }
                    }
                    ImportHistoryRecovery.LocalState.UNAVAILABLE -> Unit
                }
            }
        }
        return outcome
    }

    fun supports(media:GalleryMediaPage.Media):Boolean {
        val extension=media.name.substringAfterLast('.').uppercase(java.util.Locale.ROOT)
        return media.size>0 && if(media.video)extension in setOf("MP4","MOV") else extension in setOf("JPG","JPEG","DNG")
    }
    fun run(context:Context,media:GalleryMediaPage.Media,cameraScope:String,cameraModel:String?,http:GalleryHttpMedia,
        cancelled:()->Boolean,progress:(Long,Long)->Unit,published:(String)->Unit,
        historyId:String=UUID.randomUUID().toString(),staged:GalleryPartialDownloads.Staged?=null,folder:android.net.Uri?=null):MediaImportTransaction.Result {
        require(supports(media));check(!cancelled())
        val reference=MessageDigest.getInstance("SHA-256").digest(
            "$cameraScope\u0000${media.key.store.name}\u0000${media.key.path}\u0000${media.handle}\u0000${media.size}".toByteArray(Charsets.UTF_8)
        ).joinToString("") { "%02x".format(it.toInt() and 255) }
        val item=MediaImportTransaction.Item(reference,media.name,media.size)
        val resolver=context.contentResolver
        val dng=media.name.endsWith(".DNG",ignoreCase=true)
        val copy:(android.net.Uri)->MediaImportTransaction.Copied={ uri ->
            check(!cancelled())
            checkNotNull(resolver.openOutputStream(uri,"w")).use { output ->
                staged?.copyTo(output,cancelled) ?: http.original(media,output,progress)
            }
        }
        fun draft(id:String,uri:String,name:String,tree:String?)=ImportHistoryDraft(id,uri,name,item.bytes,
            System.currentTimeMillis(),ImportProvenance.OFFERED_CAMERA_BYTES,
            requestedQuality=when { dng -> "ORIGINAL_RAW";media.video -> null;else -> "ORIGINAL_JPEG" },cameraModel=cameraModel,
            sourceScope=cameraScope,sourceScopeKind="OPAQUE_CAMERA_SESSION",sourceItemReference=reference,offeredReduced=false,folderTree=tree)
        fun verifyRepresentation(uri:android.net.Uri) {
            if(dng) {
                checkNotNull(resolver.openInputStream(uri)).use { GalleryCompanion.DNG.verify(it,media.size) }
            } else if(!media.video) {
                val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
                checkNotNull(resolver.openInputStream(uri)).use { android.graphics.BitmapFactory.decodeStream(it,null,bounds) }
                check(bounds.outMimeType=="image/jpeg" && bounds.outWidth>0 && bounds.outHeight>0)
            }
        }
        val history=ImportHistoryRepository.get(context)
        if(folder!=null)history.reconcile(cancelled)
        return ImportHistoryGate.process.importing {
            if(folder!=null) {
                check(staged==null)
                return@importing FolderImportOutput(context,folder).publish(checkNotNull(AlbumMediaFile.document(item.name)),
                    item,history,cancelled,copy,::draft,::verifyRepresentation) { published(it.toString()) }
            }
            val base=SonyMediaStore(context.applicationContext).port(
                copy={ _,uri ->
                    copy(uri)
                },cancelled=cancelled,published={ _,uri -> published(uri.toString()) },capture={ null },
                historyDraft={ _,uri -> draft(historyId,uri.toString(),item.name,null) },
                documentFile=if(dng) checkNotNull(AlbumMediaFile.document(item.name)) else null)
            val port=object:MediaImportTransaction.Port by base {
                override fun verify(item:MediaImportTransaction.Item,destination:String,copied:MediaImportTransaction.Copied) {
                    base.verify(item,destination,copied)
                    verifyRepresentation(android.net.Uri.parse(destination))
                }
                override fun afterVerification(item:MediaImportTransaction.Item,destination:String,copied:MediaImportTransaction.Copied) {
                    check(!cancelled());base.afterVerification(item,destination,copied)
                }
                override fun publish(destination:String) { check(!cancelled());base.publish(destination) }
            }
            MediaImportTransaction().run(listOf(item),port,cancelled)
        }
    }
}
