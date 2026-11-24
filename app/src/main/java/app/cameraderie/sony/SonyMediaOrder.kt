package app.cameraderie.sony

/** Camera wall-clock dates, newest first; unknown dates follow known dates.
 * Filename/identity fallbacks are deterministic and never create capture times. */
internal object SonyMediaOrder {
    fun <T> newestFirst(items:List<T>,capture:(T)->CameraCaptureTime?,name:(T)->String,key:(T)->String):List<T> =
        items.sortedWith(compareByDescending<T> { capture(it)?.date }
            .thenByDescending { capture(it)?.time }
            .thenByDescending { name(it).lowercase(java.util.Locale.ROOT) }
            .thenByDescending { key(it) })
}
