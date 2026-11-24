package app.cameraderie.sony

/** Reads every advertised folder/page before publishing a selectable catalogue. */
internal class SonyCatalogScanner(private val maxFiles:Int=100_000,private val maxFolders:Int=10_000) {
    data class Entry(val id:String,val name:String,val folder:Boolean,val bytes:Long,val video:Boolean,val capture:CameraCaptureTime?)
    data class Page(val entries:List<Entry>,val total:Int?=null,val update:String?=null)
    fun scan(source:(String,Int,Int)->Page,cancelled:()->Boolean,progress:(Int,Int)->Unit={_,_->}):List<Entry> {
        val files=linkedMapOf<String,Entry>();val seen=mutableMapOf<String,Entry>()
        val queue=java.util.ArrayDeque<String>();val visited=mutableSetOf("0");queue.add("0")
        fun active() { if(cancelled()) throw java.io.InterruptedIOException("Gallery listing cancelled") }
        while(queue.isNotEmpty()) {
            active();val folder=queue.removeFirst();var offset=0;var expected:Int?=null;var update:String?=null
            val folderEntries=mutableSetOf<String>()
            while(true) {
                active();val page=source(folder,offset,40);active()
                require(page.entries.size<=40 && page.entries.map { it.id }.distinct().size==page.entries.size) { "Invalid gallery page" }
                if(page.total!=null) {
                    require(page.total>=offset+page.entries.size && (expected==null || expected==page.total)) { "Gallery count changed" };expected=page.total
                }
                if(page.update!=null) { require(update==null || update==page.update) { "Gallery changed during listing" };update=page.update }
                for(entry in page.entries) {
                    require(entry.id.isNotBlank() && folderEntries.add(entry.id)) { "Gallery page repeated or overlapped" }
                    require(seen[entry.id]==null || seen[entry.id]==entry) { "Camera metadata changed" };seen[entry.id]=entry
                    if(entry.folder) {
                        if(visited.add(entry.id)) { require(visited.size<=maxFolders);queue.add(entry.id) }
                    } else { files[entry.id]=entry;require(files.size<=maxFiles) }
                }
                offset+=page.entries.size;progress(files.size,visited.size)
                if(expected!=null && offset>=expected!!) break
                if(page.entries.isEmpty()) { require(expected==null || offset==expected) { "Incomplete gallery page" };break }
                if(expected==null && page.entries.size<40) break
            }
        }
        active();return files.values.toList()
    }
}
