package app.cameraderie.gallery.media

import java.util.Locale

internal data class AlbumMediaFile(val name: String, val mime: String, val video: Boolean, val raw: Boolean) {
    companion object {
        private val documentTypes = mapOf(
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
            "heic" to "image/heic", "heif" to "image/heif", "webp" to "image/webp",
            "dng" to "image/x-adobe-dng", "arw" to "image/x-sony-arw", "cr3" to "image/x-canon-cr3",
            "nef" to "image/x-nikon-nef", "raf" to "image/x-fuji-raf",
            "mp4" to "video/mp4", "mov" to "video/quicktime", "mts" to "video/mp2ts", "m2ts" to "video/mp2ts"
        )

        fun document(displayName: String): AlbumMediaFile? {
            if (displayName.length > 180 || displayName.any { it == '/' || it == '\\' || it.isISOControl() }) return null
            val dot = displayName.lastIndexOf('.')
            if (dot <= 0 || displayName.substring(0, dot).isBlank()) return null
            val extension = displayName.substring(dot + 1).lowercase(Locale.ROOT)
            val mime = documentTypes[extension] ?: return null
            return AlbumMediaFile(displayName, mime, mime.startsWith("video/"),
                extension in setOf("dng", "arw", "cr3", "nef", "raf"))
        }
    }
}
