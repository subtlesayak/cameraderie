package app.cameraderie.gallery.protocol

/** Independent Action 6 datalink codec. Validates the envelope before using its
 * sequence/window fields. A UDP packet or command ACK alone never proves playback. */
class DumlDatagram(val session: Int,val sequence: Int,val type: Int,payload: ByteArray) {
    private val body=payload.copyOf()
    val payload get()=body.copyOf()
    init { require(session in 1..65535 && sequence in 0..65535 && type in 0..255 && body.size<=16375) }
    fun encode(): ByteArray {
        val out=ByteArray(body.size+8)
        put16(out,0,0x8000 or out.size);put16(out,2,session);put16(out,4,sequence);out[6]=type.toByte()
        out[7]=(0..6).fold(0) { value,i -> value xor u(out[i]) }.toByte();body.copyInto(out,8)
        return out
    }
    override fun toString()="DumlDatagram(type=$type sequence=$sequence payloadBytes=${body.size})"
    companion object {
        private fun u(byte: Byte)=byte.toInt() and 255
        internal fun get16(bytes: ByteArray,offset: Int)=u(bytes[offset]) or (u(bytes[offset+1]) shl 8)
        internal fun put16(bytes: ByteArray,offset: Int,value: Int) { bytes[offset]=value.toByte();bytes[offset+1]=(value ushr 8).toByte() }
        fun decode(bytes: ByteArray,expectedSession: Int): DumlDatagram? {
            if(bytes.size !in 8..16383 || expectedSession !in 1..65535) return null
            val word=get16(bytes,0)
            if(word and 0xC000 != 0x8000 || word and 0x3FFF != bytes.size || get16(bytes,2)!=expectedSession ||
                (0..7).fold(0) { value,i -> value xor u(bytes[i]) } != 0) return null
            return DumlDatagram(expectedSession,get16(bytes,4),u(bytes[6]),bytes.copyOfRange(8,bytes.size))
        }
    }
}

/** One new instance per camera session. All sends/receives run on one worker. */
class DumlDatalinkState(val session: Int,val base: Int) {
    var peerChannel: Int?=null;private set
    private var sequence=0
    private var frameSequence=0xA000
    private var counter=0
    private var videoCursor=0
    private var downloadCursor=0
    var synchronized=false;private set
    init { require(session in 1..65535 && base in 8..65528 && base and 7==0) }
    private fun packet(type: Int,body: ByteArray,advance: Boolean=true): ByteArray {
        val result=DumlDatagram(session,if(type==4) 0 else sequence,type,body).encode()
        if(advance) sequence=(sequence+8) and 65535
        return result
    }
    fun handshake(): ByteArray {
        val payload=hex("000064006400c005140000640000019001c005140000640014006400c00514000064000101040102")
        DumlDatagram.put16(payload,0,base)
        return packet(0,payload)
    }
    /** Valid status frames alone supply window cursors. Wrong sessions/headers cannot mutate them. */
    fun receive(bytes: ByteArray): DumlDatagram? {
        val packet=DumlDatagram.decode(bytes,session) ?: return null
        val body=packet.payload
        if(packet.type==1 && body.size==26) {
            DumlDatagram.get16(body,0).takeIf { it!=0 }?.let { peerChannel=it }
            videoCursor=DumlDatagram.get16(body,2)
            downloadCursor=DumlDatagram.get16(body,10)
        }
        return packet
    }
    fun synchronizeToPeer(): Boolean {
        if(synchronized) return true
        val channel=peerChannel ?: return false
        sequence=(channel+8) and 65535;synchronized=true;return true
    }
    fun acknowledge(): ByteArray {
        val payload=ByteArray(26)
        for((offset,value) in listOf(0 to videoCursor,8 to downloadCursor,16 to base)) {
            DumlDatagram.put16(payload,offset,value);DumlDatagram.put16(payload,offset+2,value)
        }
        return packet(4,payload,advance=false)
    }
    fun command(receiver: Int,set: Int,id: Int,payload: ByteArray,flags: Int=0x40): ByteArray {
        check(synchronized) { "Camera datalink sequence is not synchronized" }
        val frame=DumlFrame(2,receiver,frameSequence,flags,set,id,payload)
        frameSequence=(frameSequence+1) and 65535
        return routed(frame)
    }
    fun response(request: DumlFrame,payload: ByteArray): ByteArray {
        check(synchronized && request.receiver==2 && request.flags==0x40)
        return routed(DumlFrame(2,request.sender,request.sequence,0xC0,request.commandSet,request.commandId,payload))
    }
    private fun routed(frame: DumlFrame): ByteArray {
        val route=ByteArray(12)
        DumlDatagram.put16(route,0,(sequence-8) and 65535);DumlDatagram.put16(route,2,sequence)
        counter=(counter+1) and 255;route[8]=counter.toByte();route[9]=1
        return packet(5,route+frame.encode())
    }
    companion object {
        private fun hex(value: String)=ByteArray(value.length/2) { value.substring(it*2,it*2+2).toInt(16).toByte() }
    }
}
