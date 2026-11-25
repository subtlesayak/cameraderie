package app.cameraderie.gallery.media

import app.cameraderie.gallery.media.history.LocalMediaIdentity

/** Only already-published local imports enter the Android share sheet. */
object VerifiedMediaShare {
    data class Item(val uri:String,val mime:String,val bytes:Long,val pending:Boolean,val owned:Boolean)
    data class Plan(val multiple:Boolean,val mime:String,val uris:List<String>)
    fun plan(items:List<Item>):Plan {
        require(items.size in 1..500)
        require(items.all { it.owned && !it.pending && it.bytes>0 && safeUri(it.uri) && Regex("[a-z0-9.+-]+/[a-z0-9.+-]+").matches(it.mime) })
        require(items.groupBy { it.uri }.values.all { it.distinct().size==1 }) { "Published media changed" }
        val unique=items.distinctBy { it.uri }
        val exact=unique.map { it.mime }.distinct()
        val category=unique.map { it.mime.substringBefore('/') }.distinct()
        val mime=if(exact.size==1) exact.single() else if(category.size==1 && category.single() in setOf("image","video","audio")) "${category.single()}/*" else "*/*"
        return Plan(unique.size>1,mime,unique.map { it.uri })
    }
    private fun safeUri(raw: String): Boolean = runCatching {
        LocalMediaIdentity.key(raw)
        !LocalMediaIdentity.isFolderReservation(raw)
    }.getOrDefault(false)
}
