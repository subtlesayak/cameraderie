package app.cameraderie.gallery.protocol

/** One requested counter in one live camera session. COLLECTED is transport
 * closure only: the manifest parser must still validate records/completeness. */
class GalleryManifestStream(val store: GalleryCameraEvidence.Store,val counter: Int,
    private val maxBytes: Int=1_048_576,private val maxChunks: Int=2048) {
    enum class Status { WAITING, RECEIVING, COLLECTED, FAILED, CANCELED }
    enum class Error { MALFORMED, CONFLICT, LIMIT }
    data class Snapshot(val status: Status,val chunks: Int,val bytes: Int,val started: Boolean,val ended: Boolean,val error: Error?)
    private val chunks=sortedMapOf<Int,ByteArray>()
    private var bytes=0
    private var started=false
    private var ended=false
    private var terminal: Status?=null
    private var error: Error?=null
    init { require(counter in 1..255 && maxBytes in 1..4_194_304 && maxChunks in 1..4096) }
    val snapshot: Snapshot get() {
        val state=terminal ?: if(ended && chunks.isNotEmpty() && chunks.firstKey()==0 && chunks.lastKey()+1==chunks.size)
            Status.COLLECTED else if(started || ended || chunks.isNotEmpty()) Status.RECEIVING else Status.WAITING
        return Snapshot(state,chunks.size,bytes,started,ended,error)
    }
    /** Input must already have passed DUML CRC and current datalink/session checks. */
    fun accept(frame: DumlFrame) {
        if(terminal!=null || frame.sender!=1 || frame.receiver!=2 || frame.commandSet!=0 || frame.commandId!=0x27) return
        val payload=frame.payload
        if(payload.size<5 || (payload[4].toInt() and 255)!=counter) return
        if(payload.size<10 || payload[0]!=0x4A.toByte()) { fail(Error.MALFORMED);return }
        when(payload[1].toInt() and 255) {
            4 -> { if(payload.size!=10) fail(Error.MALFORMED) else started=true }
            3 -> { if(payload.size!=10) fail(Error.MALFORMED) else ended=true }
            1 -> {
                val sequence=(payload[6].toInt() and 255) or ((payload[7].toInt() and 255) shl 8)
                if(sequence>=maxChunks || payload.size==10) { fail(Error.LIMIT);return }
                val data=payload.copyOfRange(10,payload.size)
                val previous=chunks[sequence]
                if(previous!=null) { if(!previous.contentEquals(data)) fail(Error.CONFLICT);return }
                if(bytes+data.size>maxBytes) { fail(Error.LIMIT);return }
                chunks[sequence]=data;bytes+=data.size
            }
            else -> fail(Error.MALFORMED)
        }
    }
    fun collectedBytes(): ByteArray? {
        if(snapshot.status!=Status.COLLECTED) return null
        val out=ByteArray(bytes);var offset=0
        for(chunk in chunks.values) { chunk.copyInto(out,offset);offset+=chunk.size }
        return out
    }
    fun cancel() { clear();terminal=Status.CANCELED }
    private fun clear() { chunks.values.forEach { it.fill(0) };chunks.clear();bytes=0 }
    private fun fail(reason: Error) { clear();error=reason;terminal=Status.FAILED }
    override fun toString()="GalleryManifestStream(store=${store.name},counter=$counter,status=${snapshot.status})"
}
