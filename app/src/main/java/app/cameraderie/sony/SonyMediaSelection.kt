package app.cameraderie.sony

/** Selection is scoped to loaded metadata in the current camera folder/session. */
internal class SonyMediaSelection(private val limit:Int=500) {
    data class Item(val id:String,val name:String,val size:Long,val folder:Boolean,val downloadable:Boolean)
    private val selected=linkedMapOf<String,Item>()
    init { require(limit in 1..5000) }
    fun keys()=selected.keys.toList()
    fun contains(id:String)=id in selected
    private fun eligible(item:Item)=!item.folder && item.downloadable && item.size>0
    fun toggle(item:Item) {
        if(selected.remove(item.id)!=null) return
        if(eligible(item) && selected.size<limit) selected[item.id]=item
    }
    fun selectAll(items:List<Item>) { for(item in items) if(eligible(item) && selected.size<limit) selected[item.id]=item }
    fun retain(items:List<Item>) { val current=items.associateBy { it.id };selected.entries.removeAll { current[it.key]!=it.value } }
    fun clear()=selected.clear()
    fun remove(id:String) { selected.remove(id) }
}
