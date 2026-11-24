package app.cameraderie.gallery.protocol

/** Action 6 CompositePack page. Paths are local media metadata, never diagnostics.
 * Transport must require the 00/27 stream end before calling this parser. */
object GalleryMediaPage {
    enum class Error { LIMIT, MALFORMED, UNSUPPORTED_LAYOUT, INCOMPLETE, EMPTY_NOT_CONFIRMED, STORAGE_MISMATCH }
    class Invalid(val error: Error): RuntimeException(error.name)
    data class Key(val store: GalleryCameraEvidence.Store,val path: String) {
        override fun toString()="GalleryMediaKey(store=${store.name},path=redacted)"
    }
    data class Media(val key: Key,val thumbnailPath: String,val handle: Long,val size: Long,val type: Int,val durationSeconds: Int?,
        val favourite:Boolean?=null,val modifiedStamp:Long?=null,val singleFileRecord:Boolean=false) {
        val name get()=key.path.substringAfterLast('/')
        val video get()=type==3
        fun sameFile(other:Media)=this==other.copy(favourite=favourite)
        override fun toString()="GalleryMedia(store=${key.store.name},bytes=$size,type=$type)"
    }
    data class Page(val store: GalleryCameraEvidence.Store,val entries: List<Media>,val last: Boolean,val declared: Int) {
        override fun toString()="GalleryMediaPage(store=${store.name},records=${entries.size},last=$last)"
    }
    private data class Field(val value: String,val offset: Int,val end: Int)
    private fun u(b: Byte)=b.toInt() and 255
    private fun u32(b: ByteArray,p: Int)=(0..3).fold(0L) { v,i -> v or ((b[p+i].toLong() and 255) shl (8*i)) }
    private fun fail(error: Error): Nothing=throw Invalid(error)
    private fun ascii(bytes: ByteArray,start: Int,end: Int): String? =
        if(start<0 || end>bytes.size || start>=end || (start until end).any { u(bytes[it]) !in 32..126 }) null
        else String(bytes,start,end-start,Charsets.US_ASCII)
    private fun path(value: String,prefix: String): Boolean = value.startsWith(prefix) && !value.contains('\\') &&
        value.matches(Regex("[A-Za-z0-9_ ./-]+")) &&
        value.split('/').none { it.isEmpty() || it=="." || it==".." } && value.length<=249
    private fun fields(bytes: ByteArray,start: Int,end: Int,sub: Int,prefix: String): List<Field> {
        val out=mutableListOf<Field>();var p=start
        while(p+6<=end) {
            if(bytes[p]==0x1A.toByte() && bytes[p+2]==0.toByte() && bytes[p+3]==0.toByte() && bytes[p+4]==0.toByte() && u(bytes[p+5])==sub) {
                val length=u(bytes[p+1]);val limit=p+length
                if(length>=6 && limit<=end) {
                    val value=ascii(bytes,p+6,limit)
                    if(value!=null && path(value,prefix)) { out.add(Field(value,p,limit));p=limit;continue }
                }
            }
            p++
        }
        return out
    }
    fun decode(bytes: ByteArray,store: GalleryCameraEvidence.Store,pageSize: Int=45): Page {
        require(pageSize in 1..45)
        if(bytes.size !in 4..1_048_576) fail(Error.LIMIT)
        val declared=u32(bytes,0)
        if(declared>pageSize) fail(Error.UNSUPPORTED_LAYOUT)
        val markers=mutableListOf<Int>()
        for(p in 22 until bytes.size-1) if(bytes[p]==0x19.toByte() && bytes[p+1]==6.toByte() &&
            u(bytes[p-2]) in setOf(0,3,4) && u(bytes[p-1]) in setOf(0xFF,0xFE,0xF6,0xC7,0xE5,0xE7,0xF2)) markers.add(p)
        val allPaths=fields(bytes,4,bytes.size,1,"DCIM/")
        if(allPaths.isEmpty()) fail(if(declared>0) Error.INCOMPLETE else Error.EMPTY_NOT_CONFIRMED)
        if(markers.size!=allPaths.size || allPaths.size>pageSize) fail(Error.UNSUPPORTED_LAYOUT)
        if(declared>0 && declared!=allPaths.size.toLong()) fail(Error.INCOMPLETE)
        val entries=mutableListOf<Media>();var last=false
        for((index,t) in markers.withIndex()) {
            val start=t-18;val end=if(index+1<markers.size) markers[index+1]-18 else bytes.size
            if(start<4 || end<=t+2) fail(Error.MALFORMED)
            val own=fields(bytes,t+2,end,1,"DCIM/").singleOrNull() ?: fail(Error.INCOMPLETE)
            val base=own.value.substringAfterLast('/')
            var filename: Field?=null
            for(p in t+2 until end-2) if(bytes[p]==13.toByte()) {
                val limit=p+2+u(bytes[p+1]);if(limit>end) continue
                val value=ascii(bytes,p+2,limit) ?: continue
                if(value.startsWith("$base.") && value.substringAfterLast('.') in setOf("MP4","MOV","JPG","JPEG","HEIC","DNG","OSV","INSV")) {
                    if(!value.matches(Regex("[A-Za-z0-9_. -]+")) || value.contains("..")) fail(Error.MALFORMED)
                    if(filename!=null) fail(Error.MALFORMED)
                    filename=Field(value,p,limit)
                }
            }
            val file=filename ?: fail(Error.INCOMPLETE)
            val thumb=fields(bytes,t+2,end,2,"MISC/").filter { it.value.substringAfterLast('/')==base }.singleOrNull() ?: fail(Error.INCOMPLETE)
            val handle=u32(bytes,t-10);val size=u32(bytes,t-14);val type=u(bytes[t-2])
            // Action 6 can return MP4 records with a valid handle and an
            // unreported length. Keep them in the catalogue, but never infer
            // a transfer length from adjacent fields.
            if(handle==0L || (size==0L && type!=3)) fail(Error.UNSUPPORTED_LAYOUT)
            // Store belongs to the uniquely echoed request counter, not a
            // handle namespace bit. Some per-store photo cursors cross that bit.
            if(type==3 && file.value.substringAfterLast('.') !in setOf("MP4","MOV","OSV","INSV")) fail(Error.MALFORMED)
            val endMarker=file.offset>=2 && bytes[file.offset-2]==12.toByte() && bytes[file.offset-1]==1.toByte()
            if(endMarker && index!=markers.lastIndex) fail(Error.MALFORMED)
            last=last || endMarker
            val mediaPath=own.value+file.value.removePrefix(base)
            if(!path(mediaPath,"DCIM/") || !path(thumb.value+".scr","MISC/")) fail(Error.MALFORMED)
            // Action 6 puts this flag in the record prefix, before the media path.
            val favourite=if(t+8<own.offset) when(u(bytes[t+8])) { 0 -> false;1 -> true;else -> null } else null
            entries.add(Media(Key(store,mediaPath),thumb.value+".scr",handle,size,type,
                if(type==3) u(bytes[t-6]) or (u(bytes[t-5]) shl 8) else null,favourite,
                u32(bytes,t-18).takeIf { it!=0L },u(bytes[t-1]) in setOf(0xFF,0xFE)))
        }
        if(entries.map { it.key }.distinct().size!=entries.size || entries.map { it.handle }.distinct().size!=entries.size) fail(Error.MALFORMED)
        // A short answer without the camera's last-file marker may have lost its
        // final chunk even when the transport end arrived. Never call it empty/end.
        if(entries.size<pageSize && !last) fail(Error.INCOMPLETE)
        return Page(store,entries.toList(),last,declared.toInt())
    }
}
