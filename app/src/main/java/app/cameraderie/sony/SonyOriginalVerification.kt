package app.cameraderie.sony

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Read-back integrity is separate from equality with the memory-card original. */
object SonyOriginalVerification {
    data class Receipt(val bytes:Long,val sha256:String)
    fun verify(input:InputStream,expected:Long,raw:Boolean,cancelled:()->Boolean={false},sourceSha256:String?=null,verifiedCopy:OutputStream?=null):Receipt {
        require(expected>0)
        val digest=MessageDigest.getInstance("SHA-256")
        val prefix=ByteArray(4);var prefixSize=0;var total=0L
        val buffer=ByteArray(65536)
        while(true) {
            if(cancelled()) throw java.io.InterruptedIOException("File verification cancelled")
            val count=input.read(buffer)
            if(count<0) break
            if(count==0) continue
            if(count.toLong()>expected-total) throw IOException("Saved file exceeds expected length")
            val copy=minOf(4-prefixSize,count)
            if(copy>0) { buffer.copyInto(prefix,prefixSize,0,copy);prefixSize+=copy }
            verifiedCopy?.write(buffer,0,count)
            digest.update(buffer,0,count);total+=count
        }
        if(total!=expected) throw IOException("Saved file length does not match camera file")
        if(raw && (prefixSize!=4 || !(prefix.contentEquals(byteArrayOf(0x49,0x49,0x2a,0)) || prefix.contentEquals(byteArrayOf(0x4d,0x4d,0,0x2a)))))
            throw IOException("Camera did not provide a TIFF/ARW file")
        val sha=digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if(sourceSha256!=null && !sha.equals(sourceSha256,true)) throw IOException("Saved file does not match received bytes")
        return Receipt(total,sha)
    }
}
