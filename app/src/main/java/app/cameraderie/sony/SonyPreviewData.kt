package app.cameraderie.sony

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Bounded camera-offered JPEG/PNG preview bytes, never an original-file source. */
internal object SonyPreviewData {
    const val MAX_BYTES=4*1024*1024
    const val MAX_DETAIL_BYTES=24*1024*1024
    fun read(input:InputStream,length:Long?,limit:Int=MAX_BYTES):ByteArray {
        require(limit in 1..MAX_DETAIL_BYTES)
        if(length!=null && length !in 1..limit.toLong()) throw IOException("Preview is too large")
        val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) {
            if(Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Preview cancelled")
            val n=input.read(buffer);if(n<0) break;if(n==0) continue
            if(output.size()+n>limit) throw IOException("Preview is too large")
            output.write(buffer,0,n)
        }
        val bytes=output.toByteArray()
        if(length!=null && bytes.size.toLong()!=length) throw IOException("Preview is incomplete")
        val jpeg=bytes.size>=3 && bytes[0]==0xff.toByte() && bytes[1]==0xd8.toByte() && bytes[2]==0xff.toByte()
        val png=bytes.size>=8 && bytes.copyOfRange(0,8).contentEquals(byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a))
        if(!jpeg && !png) throw IOException("Camera did not offer a JPEG or PNG preview")
        return bytes
    }
}
