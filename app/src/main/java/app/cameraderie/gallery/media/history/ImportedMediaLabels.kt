package app.cameraderie.gallery.media.history

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object ImportedMediaLabels {
    data class DateDetail(val label:String,val value:String,val note:String?=null)
    fun captureDetail(entry:ImportHistoryEntry,locale:Locale=Locale.getDefault()):DateDetail {
        val receipt=entry.receipt
        val date=receipt?.captureDate?.let { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("d MMM uuuu",locale)) }
            ?: return DateDetail("Capture date","Not recorded")
        val time=receipt.captureTime?.let { LocalTime.parse(it).format(DateTimeFormatter.ofPattern("HH:mm",locale)) }
        val offset=receipt.captureOffset?.let { " · UTC$it" }.orEmpty()
        val note=when {
            receipt.captureSource=="MEDIASTORE_INDEX" -> "From the phone library"
            time==null -> "Date only"
            receipt.captureOffset==null -> "Camera clock · timezone not recorded"
            else -> null
        }
        return DateDetail("Capture date",date+(time?.let { ", $it" } ?: "")+offset,note)
    }
    fun addedDetail(entry:ImportHistoryEntry,locale:Locale=Locale.getDefault(),zone:ZoneId=ZoneId.systemDefault()):DateDetail {
        val label=when(entry.completionKind) {
            ImportCompletionKind.LEGACY_ROW_ADDED -> "Added to phone"
            ImportCompletionKind.RECOVERED_VERIFIED -> "Recovered on phone"
            else -> "Imported to phone"
        }
        return DateDetail(label,entry.completedAt?.let { Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm",locale)) } ?: "Not recorded")
    }
    fun quality(value:String?)=when(value) {
        "ORIGINAL_JPEG" -> "Original JPEG"
        "TWO_MEGAPIXEL_JPEG" -> "2M JPEG"
        "VGA_JPEG" -> "VGA JPEG"
        "ORIGINAL_RAW" -> "Original RAW"
        else -> null
    }
    fun representation(entry:ImportHistoryEntry):String {
        if(entry.draft.provenance==ImportProvenance.EDITED_FROM_ORIGINAL)return "Edited MP4 · from verified original"
        if(entry.draft.provenance==ImportProvenance.TRIM_FROM_ORIGINAL)return "Trimmed video · stream copy"
        if(entry.draft.provenance==ImportProvenance.FRAME_FROM_ORIGINAL)return "JPEG frame from original video"
        if(entry.draft.provenance==ImportProvenance.DOCUMENT_BYTES)return "Selected file · unchanged"
        if(entry.draft.provenance==ImportProvenance.LEGACY_UNKNOWN)return "Earlier import · quality unknown"
        if(entry.draft.provenance==ImportProvenance.PHONE_JPEG_REDUCTION)return "Created on this phone · ${quality(entry.draft.requestedQuality) ?: "JPEG"}"
        val mime=entry.receipt?.mime.orEmpty()
        if(entry.draft.companionFor!=null) return if(mime.startsWith("audio/")) "Camera audio backup" else "Camera RAW companion"
        if(mime.startsWith("video/"))return "Camera video · unchanged"
        if(entry.draft.requestedQuality=="ORIGINAL_RAW")return "Camera RAW · offered file"
        if(mime=="image/png")return "Camera PNG · unchanged"
        if(entry.draft.offeredReduced==true)return "Camera copy · ${quality(entry.draft.requestedQuality) ?: "JPEG"}"
        return if(entry.draft.requestedQuality=="ORIGINAL_JPEG") "Original JPEG · offered file" else "Camera file · unchanged"
    }
    fun destination(entry:ImportHistoryEntry):String {
        val folder = entry.draft.folderTree ?: return if (
            entry.draft.uri.startsWith("content://media/external_primary/downloads/")
        ) "Downloads · Cameraderie" else "Phone album"
        val documentId=java.net.URI(folder).path.removePrefix("/tree/")
        val name=documentId.substringAfter(':', "").trimEnd('/').substringAfterLast('/')
        return if(name.isBlank()) "Selected folder" else "Folder · $name"
    }
    fun tileSummary(entry:ImportHistoryEntry)=buildList {
        add(destination(entry))
        if(entry.draft.provenance!=ImportProvenance.LEGACY_UNKNOWN) add(representation(entry))
        add(tileDate(entry))
    }.joinToString("\n")
    fun tileDate(entry:ImportHistoryEntry,locale:Locale=Locale.getDefault(),zone:ZoneId=ZoneId.systemDefault()):String {
        val time=entry.completedAt ?: return "Import date unknown"
        val label=when(entry.completionKind) {
            ImportCompletionKind.OBSERVED_PUBLICATION -> "Imported"
            ImportCompletionKind.RECOVERED_VERIFIED -> "Recovered"
            ImportCompletionKind.LEGACY_ROW_ADDED -> "Added"
            null -> return "Import date unknown"
        }
        return "$label ${Instant.ofEpochMilli(time).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM uuuu",locale))}"
    }
    fun imported(entry:ImportHistoryEntry,locale:Locale=Locale.getDefault(),zone:ZoneId=ZoneId.systemDefault()):String {
        val time=entry.completedAt ?: return "Import date not recorded"
        val label=when(entry.completionKind) {
            ImportCompletionKind.OBSERVED_PUBLICATION -> "Imported"
            ImportCompletionKind.RECOVERED_VERIFIED -> "Recovered"
            ImportCompletionKind.LEGACY_ROW_ADDED -> "Added to phone"
            null -> return "Import date not recorded"
        }
        return "$label ${Instant.ofEpochMilli(time).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm",locale))}"
    }
    fun captured(entry:ImportHistoryEntry,locale:Locale=Locale.getDefault()):String {
        val r=entry.receipt ?: return "Capture date not recorded"
        val date=r.captureDate?.let { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("d MMM uuuu",locale)) } ?: return "Capture date not recorded"
        val time=r.captureTime?.let { " · ${LocalTime.parse(it).format(DateTimeFormatter.ofPattern("HH:mm",locale))}" }.orEmpty()
        val prefix=if(r.captureSource=="MEDIASTORE_INDEX") "Phone-indexed capture" else "Captured"
        val clock=when { r.captureTime==null -> " · date only";r.captureOffset!=null -> " · UTC${r.captureOffset}";r.captureSource!="MEDIASTORE_INDEX" -> " · camera clock";else -> "" }
        return "$prefix $date$time$clock"
    }
}
