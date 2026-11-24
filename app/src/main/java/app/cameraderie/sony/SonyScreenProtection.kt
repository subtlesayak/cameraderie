package app.cameraderie.sony

/** User consent permits screenshots of media, never Wi-Fi/QR setup. */
internal object SonyScreenProtection {
    fun required(allowGalleryScreenshots:Boolean,galleryVisible:Boolean)=!allowGalleryScreenshots || !galleryVisible
}
