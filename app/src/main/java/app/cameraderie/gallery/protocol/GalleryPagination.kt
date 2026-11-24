package app.cameraderie.gallery.protocol

/** Per-camera library state. A store cannot borrow another store's path, cursor
 * or completion. Failed pages never commit partial records or advance a cursor. */
class GalleryPagination(val store: GalleryCameraEvidence.Store,private val maxEntries: Int=50_000) {
    enum class Error { STORE, CHANGED_MEDIA, CURSOR_UNAVAILABLE, CURSOR_STALLED, UNCONFIRMED_OVERLAP, LIMIT, FINISHED }
    class Invalid(val error: Error): RuntimeException(error.name)
    private val records=linkedMapOf<GalleryMediaPage.Key,GalleryMediaPage.Media>()
    var cursor=store.newestCursor;private set
    var finished=false;private set
    val size get()=records.size
    fun entries(): List<GalleryMediaPage.Media> = records.values.toList()
    fun overlapRetryCursor(previousRetry: Long? = null): Long? {
        if(finished || records.values.none { it.handle==cursor }) return null
        // Revisit an earlier indexed record while still requiring the original
        // boundary in the returned page. Each retry moves to a distinct handle.
        val lowerBound = previousRetry ?: cursor
        return records.values.asSequence().map { it.handle }.filter { it>lowerBound }.minOrNull()
    }
    fun accept(page: GalleryMediaPage.Page,requireBoundaryOverlap:Boolean=false): List<GalleryMediaPage.Media> {
        if(finished) throw Invalid(Error.FINISHED)
        if(page.store!=store || page.entries.any { it.key.store!=store }) throw Invalid(Error.STORE)
        if(page.entries.any { records[it.key]?.sameFile(it)==false }) throw Invalid(Error.CHANGED_MEDIA)
        val additions=page.entries.filter { it.key !in records }
        if(requireBoundaryOverlap && (page.entries.none { it.handle==cursor && records[it.key]?.sameFile(it)==true } ||
            additions.any { it.handle>=cursor } ||
            (!page.last && additions.isEmpty()))) throw Invalid(Error.UNCONFIRMED_OVERLAP)
        if(records.size+additions.size>maxEntries) throw Invalid(Error.LIMIT)
        val next=if(page.last) null else {
            page.entries.map { it.handle }.filter { it>0 }
                .filter { cursor==store.newestCursor || it<cursor }.minOrNull()
                ?: throw Invalid(if(cursor==store.newestCursor) Error.CURSOR_UNAVAILABLE else Error.CURSOR_STALLED)
        }
        if(!page.last && (additions.isEmpty() || next==cursor)) throw Invalid(Error.CURSOR_STALLED)
        for(entry in additions) records[entry.key]=entry
        if(next!=null) cursor=next
        finished=page.last
        return additions
    }
}

/** Proven read-only list surface; counters must not be reused within a session. */
object GalleryListQuery {
    fun page(counter: Int,store: GalleryCameraEvidence.Store,cursor: Long=store.newestCursor,pageSize: Int=45,
        favouritesOnly:Boolean=false): ByteArray {
        require(counter in 1..255 && pageSize in 1..45 && cursor in 1..0xFFFFFFFFL)
        val out=hex("4a002a10010000000000010000002d000d0100ffffffffffffffff000100000000000000000000000000")
        out[4]=counter.toByte();repeat(4) { out[10+it]=(cursor ushr (8*it)).toByte() };out[14]=pageSize.toByte()
        if(favouritesOnly)out[18]=1
        return out
    }
    fun trigger(counter: Int)=hex("4a040e1001000000000001000000").also { require(counter in 1..255);it[4]=counter.toByte() }
    private fun hex(value: String)=ByteArray(value.length/2) { value.substring(it*2,it*2+2).toInt(16).toByte() }
}
