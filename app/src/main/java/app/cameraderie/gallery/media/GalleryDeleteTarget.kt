package app.cameraderie.gallery.media

import app.cameraderie.gallery.protocol.GalleryMediaPage.Media

/** A handle is usable only when independent records from the same store corroborate it. */
internal class GalleryDeleteTarget private constructor(val file:Media,private val witnesses:List<Media>) {
    enum class Readback { ABSENT, STILL_LISTED, UNVERIFIED }

    fun verify(current:List<Media>):Readback {
        if(current.any { file.sameFile(it) })return Readback.STILL_LISTED
        if(current.any { it.key==file.key || it.handle==file.handle } ||
            witnesses.any { original -> current.none(original::sameFile) })return Readback.UNVERIFIED
        return Readback.ABSENT
    }

    companion object {
        private val ordinaryName=Regex("""^[A-Za-z][A-Za-z0-9-]*_?\d{14}_(\d{4})_D\.(JPG|JPEG|MP4|MOV)$""")
        private fun sequence(media:Media):Long? {
            if(!media.singleFileRecord || media.modifiedStamp==null || media.size<=0)return null
            return ordinaryName.matchEntire(media.name)?.groupValues?.get(1)?.toLongOrNull()
        }

        fun from(file:Media,catalogue:List<Media>):GalleryDeleteTarget? {
            val index=sequence(file) ?: return null
            if(index==0L || catalogue.count { it.handle==file.handle }!=1)return null
            val base=file.handle-index*64
            if(base<=0 || base%64!=0L)return null
            val witnesses=catalogue.asSequence().filter {
                it.key.store==file.key.store && it.key!=file.key &&
                    sequence(it)?.let { other -> other!=0L && it.handle-other*64==base }==true
            }.distinctBy { it.handle }.take(2).toList()
            if(witnesses.size!=2)return null
            return GalleryDeleteTarget(file,witnesses)
        }
    }
}
