package app.cameraderie.gallery.protocol

/** Independent DUML framing; protocol reference: Osmosis MEDIA_PROTOCOL.md (MIT).
 * Separate from accessory R-SDK 0xAA frames. Never prints payloads or credentials. */
class DumlFrame(val sender: Int, val receiver: Int, val sequence: Int,
    val flags: Int, val commandSet: Int, val commandId: Int, payload: ByteArray) {
    private val data = payload.copyOf()
    val payload get() = data.copyOf()
    init {
        require(listOf(sender,receiver,flags,commandSet,commandId).all { it in 0..255 })
        require(sequence in 0..65535 && data.size <= 1010)
    }
    fun encode(): ByteArray {
        val size=data.size+13;val result=ByteArray(size)
        result[0]=0x55;result[1]=size.toByte();result[2]=((size ushr 8) or 4).toByte()
        result[3]=crc(result,3,0x77,0x8C,255).toByte()
        result[4]=sender.toByte();result[5]=receiver.toByte()
        result[6]=sequence.toByte();result[7]=(sequence ushr 8).toByte()
        result[8]=flags.toByte();result[9]=commandSet.toByte();result[10]=commandId.toByte()
        data.copyInto(result,11)
        val checksum=crc(result,size-2,0x3692,0x8408,65535)
        result[size-2]=checksum.toByte();result[size-1]=(checksum ushr 8).toByte()
        return result
    }
    override fun toString() = "DUML set=$commandSet command=$commandId sequence=$sequence payloadBytes=${data.size}"
    companion object {
        private fun u(b: Byte)=b.toInt() and 255
        private fun crc(bytes: ByteArray, length: Int, initial: Int, polynomial: Int, mask: Int): Int {
            var value=initial
            for(i in 0 until length) {
                value=value xor u(bytes[i])
                repeat(8) { value=if(value and 1 != 0) (value ushr 1) xor polynomial else value ushr 1 }
            }
            return value and mask
        }
        fun decode(bytes: ByteArray): DumlFrame? {
            if(bytes.size !in 13..1023 || u(bytes[0]) != 0x55) return null
            val length=u(bytes[1]) or ((u(bytes[2]) and 3) shl 8)
            if(length != bytes.size || u(bytes[2]) ushr 2 != 1) return null
            if(crc(bytes,3,0x77,0x8C,255) != u(bytes[3])) return null
            val tail=u(bytes[length-2]) or (u(bytes[length-1]) shl 8)
            if(crc(bytes,length-2,0x3692,0x8408,65535) != tail) return null
            return DumlFrame(u(bytes[4]),u(bytes[5]),u(bytes[6]) or (u(bytes[7]) shl 8),
                u(bytes[8]),u(bytes[9]),u(bytes[10]),bytes.copyOfRange(11,length-2))
        }
        internal fun headerLength(bytes: ByteArray, count: Int): Int? {
            if(count < 4 || u(bytes[0]) != 0x55 || u(bytes[2]) ushr 2 != 1 ||
                crc(bytes,3,0x77,0x8C,255) != u(bytes[3])) return null
            return (u(bytes[1]) or ((u(bytes[2]) and 3) shl 8)).takeIf { it in 13..1023 }
        }
    }
}

/** Bounded stream collector for fragmented/coalesced BLE notifications and datalink payloads. */
class DumlFramer {
    private val buffer=ByteArray(1023)
    private var used=0
    fun reset() { buffer.fill(0);used=0 }
    fun accept(bytes: ByteArray): List<DumlFrame> {
        val frames=mutableListOf<DumlFrame>()
        fun discard(count: Int) {
            buffer.copyInto(buffer,0,count,used);buffer.fill(0,used-count,used);used-=count
        }
        for(byte in bytes) {
            if(used == buffer.size) discard(1)
            buffer[used++]=byte
            while(used >= 4) {
                val length=DumlFrame.headerLength(buffer,used)
                if(length == null) { discard(1);continue }
                if(used < length) break
                val frame=DumlFrame.decode(buffer.copyOf(length))
                if(frame == null) discard(1)
                else { frames.add(frame);discard(length) }
            }
        }
        return frames
    }
}
