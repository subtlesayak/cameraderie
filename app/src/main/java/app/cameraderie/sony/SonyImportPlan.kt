package app.cameraderie.sony

import app.cameraderie.gallery.media.AlbumMediaFile
import java.util.Locale

internal object SonyImportPlan {
    const val ALBUM="DCIM/Cameraderie/"
    fun unchanged(name:String)=name.substringAfterLast('.',"").lowercase(Locale.ROOT) in setOf("mp4","mov","mts","m2ts","png")
    fun importable(name:String,bytes:Long,resources:List<SonyImportSizePolicy.Resource>)=bytes>0 && (
        unchanged(name) || SonyImportSize.entries.any { SonyImportSizePolicy.choose(it,resources) !is SonyImportSizePolicy.Plan.Unavailable })
    /** Name the offered representation, never a different format from its display title. */
    fun representationName(leaf:String,title:String,mime:String):String? {
        val safeLeaf=leaf.takeIf { it.matches(Regex("[A-Za-z0-9_. -]{1,180}")) && it!="." && it!=".." }
        val extension=safeLeaf?.substringAfterLast('.',"")?.lowercase(Locale.ROOT)
        val allowed=when(mime.substringBefore(';').trim().lowercase(Locale.ROOT)) {
            "image/jpeg" -> listOf("jpg","jpeg")
            "image/png" -> listOf("png")
            "image/x-sony-arw","image/x-arw","image/arw" -> listOf("arw")
            "video/mp4" -> listOf("mp4")
            "video/quicktime" -> listOf("mov")
            "video/mp2t","video/mp2ts","video/vnd.dlna.mpeg-tts" -> listOf("mts","m2ts")
            "application/octet-stream" -> if(extension!=null && runCatching { file(safeLeaf!!) }.isSuccess) listOf(extension) else return null
            else -> return null
        }
        if(extension in allowed) return safeLeaf
        val stem=(safeLeaf ?: title.substringAfterLast('/').substringAfterLast('\\'))
            .substringBeforeLast('.',safeLeaf ?: title).replace(Regex("[\\p{Cntrl}/\\\\]"),"_").trim().ifBlank { "Camera item" }
        val suffix="."+allowed.first().uppercase(Locale.ROOT)
        return stem.take(180-suffix.length)+suffix
    }
    fun file(name:String):AlbumMediaFile {
        val safe=name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\p{Cntrl}]"),"_").take(180)
        require(safe.isNotBlank())
        val extension=safe.substringAfterLast('.',"").lowercase(Locale.ROOT)
        val mime=when(extension) {
            "jpg","jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "arw" -> "image/x-sony-arw"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mts","m2ts" -> "video/mp2ts"
            else -> throw IllegalArgumentException("Camera representation cannot be imported into the media album")
        }
        return AlbumMediaFile(safe,mime,mime.startsWith("video/"),extension=="arw")
    }
}
