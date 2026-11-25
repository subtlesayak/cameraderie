package app.cameraderie.gallery.media.history

import java.net.URI

/** MediaStore collection URIs can alias the same Files row.
 * Keep the original usable reference, but key history by one canonical identity. */
object LocalMediaIdentity {
    private const val authority = "com.android.externalstorage.documents"
    private val tree = Regex("/tree/[^/]+")
    private val document = Regex("/tree/[^/]+/document/[^/]+")
    private val reservation = Regex("urn:cameraderie:folder:[0-9a-f-]{36}")

    fun folderReservation(id:String):String = "urn:cameraderie:folder:$id".also { require(isFolderReservation(it)) }
    fun isFolderReservation(value:String) = reservation.matches(value)
    private fun folderUri(value:String, path:Regex):Boolean = runCatching {
        val uri=URI(value)
        uri.scheme=="content" && uri.rawAuthority==authority && uri.rawQuery==null &&
            uri.rawFragment==null && path.matches(uri.rawPath.orEmpty())
    }.getOrDefault(false)
    fun requireFolderTree(value:String) { require(folderUri(value,tree)) }
    fun isFolderDocument(value:String) = folderUri(value,document)
    fun inFolder(value:String, root:String) = isFolderDocument(value) && value.startsWith("$root/document/")

    fun key(value:String):String {
        if (isFolderDocument(value) || isFolderReservation(value)) return value
        val uri=URI(value)
        require(uri.scheme=="content" && uri.rawAuthority=="media" && uri.rawQuery==null && uri.rawFragment==null)
        val parts=uri.rawPath.split('/').drop(1)
        require(parts.firstOrNull()=="external_primary")
        require((parts.size==3 && parts[1] in setOf("file","downloads")) || (parts.size==4 && parts[1] in setOf("images","video") && parts[2]=="media"))
        require(Regex("[1-9][0-9]*").matches(parts.last()))
        val id=parts.last().toLongOrNull();require(id!=null && id>0)
        return "content://media/external_primary/file/$id"
    }
}
