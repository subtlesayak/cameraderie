package app.cameraderie.gallery.media

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Explicit debug-only capture of already validated media list traffic. Never
 * accepts BLE credentials/identity, pictures, GPS or other protocol commands.
 * Memory/records are bounded; caller saves the result app-privately, outside export. */
class GalleryListCapture(private val maximum:Int=2*1024*1024,private val maxRecords:Int=4096) {
    private val lock=Any()
    private val bytes=ByteArray(maximum)
    private var used=5
    private var records=0
    private var closed=false
    var dropped=0;private set
    init { require(maximum in 32..2*1024*1024 && maxRecords in 1..4096);"GBMD1".toByteArray(Charsets.US_ASCII).copyInto(bytes) }
    /** direction0=sent,1=received. No hostname, address, pairing identity or key. */
    fun record(direction:Int,command:Int,sequence:Int,flags:Int,payload:ByteArray,elapsedMs:Long) = synchronized(lock) {
        if(closed)return@synchronized
        require(direction in 0..1 && command in setOf(0x26,0x27) && sequence in 0..65535 && flags in 0..255 && elapsedMs>=0)
        if(payload.size !in 1..4096 || records>=maxRecords || payload.size>maximum-used-17) { dropped++;return@synchronized }
        val out=ByteBuffer.wrap(bytes,used,17+payload.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put(direction.toByte());out.put(command.toByte());out.putShort(sequence.toShort());out.put(flags.toByte())
        out.putLong(elapsedMs);out.putInt(payload.size);out.put(payload)
        used+=17+payload.size;records++
    }
    /** Idempotent retirement and zeroing; saving happens off the datalink/UI workers. */
    fun finish():ByteArray?=synchronized(lock) {
        if(closed)return@synchronized null
        closed=true;bytes.copyOf(used).also { bytes.fill(0) }
    }
}
