package app.cameraderie.gallery.protocol

/** Small, documented command surface for the registered gallery session. */
internal object GalleryCommands {
    fun pairing(identity: GalleryIdentity,sequence: Int=0xA000): DumlFrame {
        val id=identity.bytes();val token="osmo".toByteArray(Charsets.US_ASCII)
        val payload=byteArrayOf(id.size.toByte())+id+byteArrayOf(token.size.toByte())+token
        val frame=DumlFrame(2,7,sequence,0x40,7,0x45,payload)
        id.fill(0);payload.fill(0);return frame
    }
    fun deviceInfo()=ByteArray(62).also { it[1]=65;it[2]=80;it[3]=80;it[41]=2;it[50]=2;it[51]=8 }
    fun presence()=hex("170046237c415050000000000002")
    fun initialize()=hex("05ffffffff")
    fun playback(enter: Boolean)=byteArrayOf(1,1,0,if(enter) 1 else 0)
    fun favourite(handle:Long,counter:Long,on:Boolean):ByteArray {
        require(handle in 1..0xFFFFFFFFL && counter in 1..0xFFFFFFFFL)
        return ByteArray(15).also { bytes ->
            bytes[0]=1;bytes[1]=1
            repeat(4) { index ->
                bytes[2+index]=(handle ushr (index*8)).toByte()
                bytes[6+index]=(counter ushr (index*8)).toByte()
            }
            bytes[11]=if(on) 1 else 0
        }
    }
    fun deleteFile(handle:Long,counter:Long):ByteArray {
        require(handle in 1..0xFFFFFFFFL && counter in 1..0xFFFFFFFFL)
        return ByteArray(18).also { bytes ->
            bytes[0]=1
            repeat(4) { index ->
                bytes[1+index]=(handle ushr (index*8)).toByte()
                bytes[5+index]=(counter ushr (index*8)).toByte()
            }
            bytes[10]=1
            bytes[14]=1
            bytes[15]=1
        }
    }
    fun subscription(name: String,id: Int): ByteArray {
        require(name in setOf("cam_storage","cam_status"))
        val key=name.toByteArray(Charsets.US_ASCII)
        val out=ByteArray(19+key.size)
        out[0]=2;out[1]=2
        repeat(4) { out[4+it]=(id ushr (it*8)).toByte() }
        DumlDatagram.put16(out,11,key.size+6);DumlDatagram.put16(out,13,key.size)
        key.copyInto(out,15);return out
    }
    private fun hex(text: String)=ByteArray(text.length/2) { text.substring(it*2,it*2+2).toInt(16).toByte() }
}
