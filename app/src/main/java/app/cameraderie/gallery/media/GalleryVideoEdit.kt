package app.cameraderie.gallery.media

internal sealed interface GalleryVideoEdit {
    data class Frame(val positionMs:Long):GalleryVideoEdit {
        init { require(positionMs in 0..Int.MAX_VALUE.toLong()) }
    }
}
