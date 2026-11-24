package app.cameraderie.gallery.media

/** Byte progress describes the current copy only; verification/publication are separate. */
object ImportPercentage {
    fun copied(bytes:Long,total:Long):Int? {
        if(bytes<0 || total<=0) return null
        if(bytes>=total) return 100
        return ((bytes.toDouble()/total)*100).toInt().coerceIn(0,99)
    }
}
