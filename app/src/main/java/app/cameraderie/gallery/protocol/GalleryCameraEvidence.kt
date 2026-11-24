package app.cameraderie.gallery.protocol

/** Observed DUML camera state, scoped to the current session. Command ACKs cannot
 * create playback/storage evidence. Storage capacities do not imply an empty media list. */
class GalleryCameraEvidence(private val now: ()->Long,private val freshMs: Long=5000) {
    enum class Store(val protocolId: Int,val newestCursor: Long) { SD(0,1),INTERNAL(1,0x40000001L) }
    data class Storage(val store: Store,val totalMiB: Long,val freeMiB: Long) { val available get()=totalMiB>0 }
    enum class Readiness { WAITING_PLAYBACK, SETTLING, WAITING_STORAGE, UNAVAILABLE, READY }
    var playback: Boolean?=null;private set
    private var playbackAt: Long?=null
    private var playbackEnteredAt: Long?=null
    private var storageAt: Long?=null
    private var storage=emptyList<Storage>()
    private fun fresh(at: Long?)=at!=null && now()-at in 0..freshMs
    val playbackFresh get()=fresh(playbackAt)
    val storageFresh get()=fresh(storageAt)
    fun stores(): List<Storage> = if(storageFresh) storage.map { it.copy() } else emptyList()
    val readiness: Readiness get() {
        if(!playbackFresh || playback!=true) return Readiness.WAITING_PLAYBACK
        if(playbackEnteredAt==null || now()-playbackEnteredAt!!<1700) return Readiness.SETTLING
        if(!storageFresh) return Readiness.WAITING_STORAGE
        return if(storage.any { it.available }) Readiness.READY else Readiness.UNAVAILABLE
    }
    fun accept(frame: DumlFrame) {
        if(frame.sender!=1 || frame.receiver!=2 || frame.commandSet!=2) return
        val bytes=frame.payload
        if(frame.commandId==0x80 && bytes.size>=13) {
            val value=u32(bytes,0) and 0x40000000L != 0L
            if(value && (playback!=true || !playbackFresh)) playbackEnteredAt=now()
            if(!value) playbackEnteredAt=null
            playback=value;playbackAt=now()
        } else if(frame.commandId==0xDC && bytes.size>=22) {
            val count=bytes[2].toInt() and 255
            if(count !in 1..2 || (count==2 && bytes.size<32)) return
            val values=mutableListOf(Storage(Store.SD,u32(bytes,6),u32(bytes,10)))
            if(count==2) values.add(Storage(Store.INTERNAL,u32(bytes,24),u32(bytes,28)))
            if(values.any { it.totalMiB !in 0..50_000_000 || it.freeMiB !in 0..it.totalMiB }) return
            storage=values;storageAt=now()
        }
    }
    fun reset() { playback=null;playbackAt=null;playbackEnteredAt=null;storageAt=null;storage=emptyList() }
    companion object {
        private fun u32(bytes: ByteArray,offset: Int): Long = (0..3).fold(0L) { value,i -> value or ((bytes[offset+i].toLong() and 255) shl (i*8)) }
    }
}
