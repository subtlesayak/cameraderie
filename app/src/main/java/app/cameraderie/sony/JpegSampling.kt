package app.cameraderie.sony

import java.io.InputStream
import java.io.IOException

internal object JpegSampling {
    data class Factors(val yH:Int,val yV:Int,val cbH:Int,val cbV:Int,val crH:Int,val crV:Int) {
        val is422 get()=yH==2*cbH && yV==cbV && cbH==crH && cbV==crV
    }
    fun read(input:InputStream):Factors {
        fun byte():Int=input.read().also { if(it<0) throw IOException("Truncated JPEG header") }
        if(byte()!=255 || byte()!=216) throw IOException("Not a JPEG")
        var consumed=2
        while(consumed<1_048_576) {
            if(byte()!=255) throw IOException("Invalid JPEG marker")
            var marker=byte();while(marker==255) marker=byte()
            if(marker==0xDA || marker==0xD9) break
            if(marker in 0xD0..0xD8 || marker==1) continue
            val length=(byte() shl 8) or byte();require(length>=2);consumed+=length+2
            if(marker in setOf(0xC0,0xC1,0xC2)) {
                val data=ByteArray(length-2);var offset=0
                while(offset<data.size) { val n=input.read(data,offset,data.size-offset);if(n<=0) throw IOException("Truncated JPEG frame");offset+=n }
                if(data.size<15 || data[5].toInt()!=3) throw IOException("Unsupported JPEG components")
                fun h(i:Int)=data[i].toInt() ushr 4 and 15
                fun v(i:Int)=data[i].toInt() and 15
                return Factors(h(7),v(7),h(10),v(10),h(13),v(13))
            }
            repeat(length-2) { byte() }
        }
        throw IOException("JPEG sampling was not found")
    }
}
