package app.cameraderie.gallery.protocol

/** CRC-validated frames in one already validated camera datagram. Data-channel
 * prefixes vary; telemetry also rides type1 alongside window heartbeats. Never concatenate
 * datagrams, and never let an incomplete candidate hide a later complete frame. */
object DumlDatagramFrames {
    fun decode(packet: DumlDatagram): List<DumlFrame> {
        if(packet.type !in setOf(1,2,3,5)) return emptyList()
        val bytes=packet.payload
        val frames=mutableListOf<DumlFrame>()
        var offset=0
        while(offset+13<=bytes.size) {
            if(bytes[offset]!=0x55.toByte()) { offset++;continue }
            val length=(bytes[offset+1].toInt() and 255) or ((bytes[offset+2].toInt() and 3) shl 8)
            if((bytes[offset+2].toInt() and 255) ushr 2!=1 || length !in 13..1023 || offset+length>bytes.size) { offset++;continue }
            val frame=DumlFrame.decode(bytes.copyOfRange(offset,offset+length))
            if(frame==null) offset++ else { frames.add(frame);offset+=length }
        }
        return frames
    }
}
