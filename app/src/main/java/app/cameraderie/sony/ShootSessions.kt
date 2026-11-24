package app.cameraderie.sony

import java.security.MessageDigest
import java.time.*
import java.time.format.DateTimeFormatter

/** Group only camera timestamps, never import time, list position or guessed filenames.
 * Selecting a date is navigation; callers must select one shoot or individual files. */
class ShootSessions(private val gap:Duration=Duration.ofHours(2)) {
    data class Item(val id:String,val capture:CameraCaptureTime?)
    data class Session(val id:String,val date:LocalDate?,val start:LocalDateTime?,val end:LocalDateTime?,
        val itemIds:List<String>,val complete:Boolean,val hasTimes:Boolean) {
        val selectableAsShoot get()=hasTimes && complete
    }
    init { require(!gap.isNegative && !gap.isZero) }
    fun group(scope:String,items:List<Item>,complete:Boolean):List<Session> {
        require(scope.isNotBlank())
        val unique=linkedMapOf<String,Item>()
        for(item in items) { require(item.id.isNotBlank());require(unique[item.id]==null || unique[item.id]==item) { "Camera metadata changed" };unique[item.id]=item }
        val timed=unique.values.filter { it.capture?.wallTime!=null }.sortedWith(compareBy<Item> { it.capture!!.wallTime }.thenBy { it.id })
        val groups=mutableListOf<MutableList<Item>>()
        for(item in timed) {
            val previous=groups.lastOrNull()?.lastOrNull()?.capture
            val current=item.capture!!
            val split=previous==null || current.date!=previous.date || current.offset!=previous.offset ||
                Duration.between(previous.wallTime,current.wallTime)>=gap
            if(split) groups.add(mutableListOf(item)) else groups.last().add(item)
        }
        val result=groups.map { group ->
            val first=group.first().capture!!.wallTime!!;val last=group.last().capture!!.wallTime!!
            val stamp=first.format(DateTimeFormatter.ofPattern("uuuu-MM-dd_HHmmss"))
            Session("$stamp-${suffix(scope,group.first().id,first.toString())}",first.toLocalDate(),first,last,group.map { it.id },complete,true)
        }.toMutableList()
        // Unknown-time entries may be shown under their date, but must not be
        // selected wholesale as a shoot: two unrelated shoots could be mixed.
        for((date,group) in unique.values.filter { it.capture?.wallTime==null }.groupBy { it.capture?.date }) {
            result.add(Session("unknown-${date ?: "date"}-${suffix(scope,group.first().id,"unknown")}",date,null,null,group.map { it.id },complete,false))
        }
        return result.sortedWith(compareByDescending<Session> { it.date }.thenByDescending { it.start })
    }
    private fun suffix(scope:String,first:String,time:String)=MessageDigest.getInstance("SHA-256")
        .digest("$scope\u0000$first\u0000$time".toByteArray(Charsets.UTF_8)).take(4).joinToString("") { "%02x".format(it.toInt() and 255) }
}
