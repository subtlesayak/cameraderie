package app.cameraderie.sony

import java.time.LocalDate

/** One identity universe feeds calendar, date list and category totals. Unknown
 * dates remain real groups; no filename/import-clock fallback is used. */
internal object SonyDateSummary {
    enum class Scope(val label:String) { ALL("files"),PHOTOS("photos/other files"),VIDEOS("videos") }
    data class Item(val id:String,val capture:CameraCaptureTime?,val video:Boolean)
    data class Counts(val all:Int,val photos:Int,val videos:Int) {
        init { require(all==photos+videos && photos>=0 && videos>=0) }
        fun inScope(scope:Scope)=when(scope) { Scope.ALL->all;Scope.PHOTOS->photos;Scope.VIDEOS->videos }
    }
    data class Group(val date:LocalDate?,val items:List<Item>,val counts:Counts)
    data class Summary(val complete:Boolean,val counts:Counts,val groups:List<Group>) {
        fun visible(scope:Scope)=groups.filter { it.counts.inScope(scope)>0 }
        fun datedCounts(scope:Scope)=visible(scope).filter { it.date!=null }.associate { it.date!! to it.counts.inScope(scope) }
        fun unknown(scope:Scope)=groups.firstOrNull { it.date==null }?.counts?.inScope(scope) ?: 0
    }
    fun summarize(items:List<Item>,complete:Boolean):Summary {
        val identities=linkedMapOf<String,Item>()
        for(item in items) {
            require(item.id.isNotBlank())
            require(identities[item.id]==null || identities[item.id]==item) { "Camera metadata changed" }
            identities[item.id]=item
        }
        fun counts(values:Collection<Item>)=Counts(values.size,values.count { !it.video },values.count { it.video })
        val groups=identities.values.groupBy { it.capture?.date }.map { (date,values) -> Group(date,values.toList(),counts(values)) }
            .sortedWith(compareByDescending<Group> { it.date })
        val total=counts(identities.values)
        check(groups.sumOf { it.counts.all }==total.all && groups.sumOf { it.counts.photos }==total.photos && groups.sumOf { it.counts.videos }==total.videos)
        return Summary(complete,total,groups)
    }
}
