package app.cameraderie.gallery.media

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.InterruptedIOException

internal object DocumentFolder {
    class TooLarge : Exception()
    data class NamedFile(val uri: Uri, val name: String)

    fun list(resolver: ContentResolver, tree: Uri, cancelled: () -> Boolean): List<Uri> =
        listNamed(resolver,tree,DocumentAlbumImport.MAX_SELECTION,cancelled) { AlbumMediaFile.document(it)!=null }.map { it.uri }

    fun listNamed(resolver: ContentResolver, tree: Uri, limit:Int, cancelled: () -> Boolean,
        accepts: (String) -> Boolean): List<NamedFile> {
        require(DocumentsContract.isTreeUri(tree))
        val folders = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree)))
        val visited = mutableSetOf<String>()
        val files = linkedMapOf<Uri,NamedFile>()
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS)
        var examined = 0
        while (folders.isNotEmpty()) {
            if (cancelled()) throw InterruptedIOException()
            val folder = folders.removeFirst()
            if (!visited.add(folder)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folder)
            checkNotNull(resolver.query(children, columns, null, null, null)).use { cursor ->
                while (cursor.moveToNext()) {
                    if (cancelled()) throw InterruptedIOException()
                    if (++examined > 10_000) throw TooLarge()
                    val id = cursor.getString(0)
                    val name = cursor.getString(1).orEmpty()
                    val mime = cursor.getString(2)
                    if (mime == Document.MIME_TYPE_DIR) {
                        folders.addLast(id)
                    } else if ((cursor.getInt(3) and Document.FLAG_VIRTUAL_DOCUMENT) == 0 &&
                        accepts(name)) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree,id)
                        files.putIfAbsent(uri,NamedFile(uri,name))
                        if (files.size > limit) throw TooLarge()
                    }
                }
            }
        }
        return files.values.toList()
    }
}
