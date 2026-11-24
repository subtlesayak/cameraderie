package app.cameraderie.gallery.protocol

/** Metadata-only counters: no payload, credentials, address, identity or raw
 * exception text can be retained. Used to distinguish no RX from rejected RX. */
class GalleryBleDiagnostics {
    private var notifications=0
    private var early=0
    private var bytes=0
    private var dumlPrefix=0
    private var accessoryPrefix=0
    private var frames=0
    private var writes=0
    private var callbacks=0
    private var lastStatus: Int?=null
    private val headers=linkedSetOf<String>()
    fun notification(length: Int,first: Int?,beforeProtocol: Boolean) {
        notifications=(notifications+1).coerceAtMost(100000)
        bytes=(bytes+length.coerceIn(0,1024)).coerceAtMost(100000000)
        if(beforeProtocol) early=(early+1).coerceAtMost(100000)
        if(first==0x55) dumlPrefix=(dumlPrefix+1).coerceAtMost(100000)
        if(first==0xAA) accessoryPrefix=(accessoryPrefix+1).coerceAtMost(100000)
    }
    fun frame(frame: DumlFrame) {
        frames=(frames+1).coerceAtMost(100000)
        if(headers.size<12) headers.add("${frame.sender}/${frame.receiver}/${frame.flags}/${frame.commandSet}/${frame.commandId}")
    }
    fun issued() { writes=(writes+1).coerceAtMost(100000) }
    fun completed(status: Int) { callbacks=(callbacks+1).coerceAtMost(100000);lastStatus=status }
    fun summary()="rx=$notifications early=$early bytes=$bytes prefix55=$dumlPrefix prefixAA=$accessoryPrefix frames=$frames tx=$writes callbacks=$callbacks status=${lastStatus ?: -1} headers=${headers.joinToString(",")}"
}

/** Preserve early device-info requests during CCCD/arming/settle. Delivery is
 * delayed until the protocol has started; unsolicited approval remains guarded. */
class GalleryBleInbox(private val maximum: Int=16) {
    private val frames=ArrayDeque<DumlFrame>()
    init { require(maximum in 1..32) }
    fun offer(frame: DumlFrame): Boolean {
        if(frames.size>=maximum) return false
        frames.addLast(frame);return true
    }
    fun next(): DumlFrame?=frames.removeFirstOrNull()
    fun clear() { frames.clear() }
}
