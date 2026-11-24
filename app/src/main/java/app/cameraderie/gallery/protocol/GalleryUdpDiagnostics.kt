package app.cameraderie.gallery.protocol

/** Numeric datagram metadata only; never stores media, credentials or IDs. */
class GalleryUdpDiagnostics {
    private var sent=0;private var received=0;private var length=0;private var xor=0;private var flag=0;private var session=0
    private var syn=0;private var echo=0;private var echoMismatch=0
    private val types=sortedMapOf<Int,Int>()
    private val storageShapes=linkedSetOf<String>()
    private var frames=0
    private val headers=linkedSetOf<String>()
    private var rejectedFrames=0
    private val rejectedHeaders=linkedSetOf<String>()
    private fun bounded(value: Int)=(value+1).coerceAtMost(100000)
    fun sent() { sent=bounded(sent) }
    fun received(bytes: ByteArray,expectedSession: Int,base: Int) {
        received=bounded(received)
        if(bytes.size !in 8..16383) { length=bounded(length);return }
        val word=DumlDatagram.get16(bytes,0)
        if(word and 0x3FFF != bytes.size) { length=bounded(length);return }
        if(word and 0xC000 != 0x8000) { flag=bounded(flag);return }
        if((0..7).fold(0) { value,i -> value xor (bytes[i].toInt() and 255) } != 0) { xor=bounded(xor);return }
        if(DumlDatagram.get16(bytes,2)!=expectedSession) { session=bounded(session);return }
        val type=bytes[6].toInt() and 255
        if(types.size<16 || type in types) types[type]=bounded(types[type] ?: 0)
        // Inspect only complete CRC8-valid DUML headers in validated envelopes.
        // Numeric metadata diagnoses unsupported versions/CRC16 failures without
        // retaining payload, sequence, session, identifiers or camera media.
        if(type in setOf(1,2,3,5)) inspectFrames(bytes)
        if(type==0) {
            syn=bounded(syn)
            if(bytes.size>=10 && DumlDatagram.get16(bytes,8)==base) echo=bounded(echo) else echoMismatch=bounded(echoMismatch)
        }
    }
    private fun inspectFrames(bytes: ByteArray) {
        fun u(at: Int)=bytes[at].toInt() and 255
        var offset=8
        while(offset+13<=bytes.size) {
            if(u(offset)!=0x55) { offset++;continue }
            val size=u(offset+1) or ((u(offset+2) and 3) shl 8)
            if(size !in 13..1023 || offset+size>bytes.size) { offset++;continue }
            var crc=0x77
            repeat(3) { i ->
                crc=crc xor u(offset+i)
                repeat(8) { crc=if(crc and 1!=0) (crc ushr 1) xor 0x8C else crc ushr 1 }
            }
            if(crc!=u(offset+3)) { offset++;continue }
            if(DumlFrame.decode(bytes.copyOfRange(offset,offset+size))==null) {
                rejectedFrames=bounded(rejectedFrames)
                if(rejectedHeaders.size<16) rejectedHeaders.add("${u(offset+2) ushr 2}/${u(offset+4)}/${u(offset+5)}/${u(offset+9)}/${u(offset+10)}")
            }
            offset+=size
        }
    }
    fun frame(frame: DumlFrame) {
        frames=bounded(frames)
        if(frame.sender==1 && frame.receiver==2 && frame.commandSet==2 && frame.commandId==0xDC && storageShapes.size<8) {
            val p=frame.payload
            fun at(i: Int)=p.getOrNull(i)?.toInt()?.and(255) ?: -1
            fun word(i: Int): Long = if(p.size<i+4) -1 else (0..3).fold(0L) { v,n -> v or (at(i+n).toLong() shl (n*8)) }
            storageShapes.add("${p.size}/${at(2)}/${at(5)}/${word(6)}/${word(10)}/${word(24)}/${word(28)}")
        }
        if(headers.size<16) headers.add("${frame.sender}/${frame.receiver}/${frame.flags}/${frame.commandSet}/${frame.commandId}")
    }
    fun summary()="tx=$sent rx=$received bad_length=$length bad_flag=$flag bad_xor=$xor session_mismatch=$session syn=$syn echo_match=$echo echo_mismatch=$echoMismatch types=${types.entries.joinToString(",") { "${it.key}:${it.value}" }} frames=$frames headers=${headers.joinToString(",")} rejected_frames=$rejectedFrames rejected_headers=${rejectedHeaders.joinToString(",")} storage_shapes=${storageShapes.joinToString(",")}"
}
