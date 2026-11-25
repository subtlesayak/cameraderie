package app.cameraderie.gallery.media

import java.util.Locale

internal data class AlbumMediaFile(val name: String, val mime: String, val video: Boolean, val raw: Boolean) {
    val image: Boolean get() = mime.startsWith("image/")

    companion object {
        const val DOWNLOAD_DIRECTORY = "Download/Cameraderie/"

        private val rawTypes = mapOf(
            "dng" to "image/x-adobe-dng", "arw" to "image/x-sony-arw",
            "cr2" to "image/x-canon-cr2", "cr3" to "image/x-canon-cr3",
            "nef" to "image/x-nikon-nef", "nrw" to "image/x-nikon-nrw",
            "raf" to "image/x-fuji-raf", "rw2" to "image/x-panasonic-rw2",
            "orf" to "image/x-olympus-orf", "pef" to "image/x-pentax-pef",
            "srw" to "image/x-samsung-srw",
        )
        private val documentTypes = mapOf(
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
            "heic" to "image/heic", "heif" to "image/heif", "webp" to "image/webp",
            "tif" to "image/tiff", "tiff" to "image/tiff", "avif" to "image/avif",
            "mp4" to "video/mp4", "mov" to "video/quicktime", "mts" to "video/mp2ts", "m2ts" to "video/mp2ts",
            "wav" to "audio/wav", "m4a" to "audio/mp4", "mp3" to "audio/mpeg", "flac" to "audio/flac",
            "gpx" to "application/gpx+xml",
            "insv" to "application/octet-stream", "insp" to "application/octet-stream",
        ) + rawTypes

        fun document(displayName: String): AlbumMediaFile? {
            if (displayName.length > 180 || displayName.any { it == '/' || it == '\\' || it.isISOControl() }) return null
            val dot = displayName.lastIndexOf('.')
            if (dot <= 0 || displayName.substring(0, dot).isBlank()) return null
            val extension = displayName.substring(dot + 1).lowercase(Locale.ROOT)
            val mime = documentTypes[extension] ?: return null
            return AlbumMediaFile(displayName, mime, mime.startsWith("video/"),
                extension in rawTypes)
        }
    }
}
