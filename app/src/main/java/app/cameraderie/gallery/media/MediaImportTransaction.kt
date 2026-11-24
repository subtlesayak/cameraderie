package app.cameraderie.gallery.media

/** Camera-independent import ordering. Only verified bytes may be published.
 * A failed/cancelled item is removed; earlier completed files are retained. */
class MediaImportTransaction {
    data class Item(val id:String,val name:String,val bytes:Long)
    data class Copied(val bytes:Long,val sha256:String?)
    enum class Phase { COPY, VERIFY, PUBLISH }
    data class Result(val completed:List<String>,val cancelled:Boolean=false,val failedAt:Phase?=null,val cleanupFailed:Boolean=false,
        val reconciliationRequired:Boolean=false,val followUpFailed:Boolean=false,val filenameConflict:Boolean=false)
    internal class FilenameConflict : java.io.IOException("The destination cannot keep the original filename.")
    enum class Cleanup { REMOVED, RETAINED }
    interface Port {
        fun createPending(item:Item):String
        fun copy(item:Item,destination:String):Copied
        fun verify(item:Item,destination:String,copied:Copied)
        /** Required journal receipt after every verifier/policy check; throwing
         * here prevents publication and retains normal pending cleanup. */
        fun afterVerification(item:Item,destination:String,copied:Copied) {}
        fun publish(destination:String)
        fun remove(destination:String)
        /** Cleanup must never delete a published file. RETAINED means its outcome
         * needs reconciliation, not that the caller may retry by deleting it. */
        fun cleanup(destination:String):Cleanup { remove(destination);return Cleanup.REMOVED }
        /** Fallible metadata/UI work runs only after cleanup ownership ends. */
        fun afterPublication(item:Item,destination:String) {}
    }
    fun run(items:List<Item>,port:Port,cancelled:()->Boolean,completed:(String,Int)->Unit={_,_->}):Result {
        require(items.isNotEmpty() && items.size<=500 && items.all { it.bytes>0 } && items.map { it.id }.distinct().size==items.size)
        val done=mutableListOf<String>();var followUpFailed=false
        for(item in items) {
            if(cancelled()) return Result(done.toList(),cancelled=true,followUpFailed=followUpFailed)
            var pending:String?=null;var phase=Phase.COPY
            val published:String
            try {
                pending=port.createPending(item)
                if(cancelled()) throw java.io.InterruptedIOException()
                val copied=port.copy(item,pending)
                phase=Phase.VERIFY
                check(copied.bytes==item.bytes) { "Incomplete camera transfer" }
                if(cancelled()) throw java.io.InterruptedIOException()
                port.verify(item,pending,copied)
                if(cancelled()) throw java.io.InterruptedIOException()
                port.afterVerification(item,pending,copied)
                if(cancelled()) throw java.io.InterruptedIOException()
                phase=Phase.PUBLISH;port.publish(pending)
                published=pending;pending=null
            } catch(error:Exception) {
                var cleanupFailed=false;var retained=false
                pending?.let { try { retained=port.cleanup(it)==Cleanup.RETAINED } catch(_:Exception) { cleanupFailed=true } }
                return Result(done.toList(),cancelled(),if(cancelled()) null else phase,cleanupFailed,
                    reconciliationRequired=retained || (phase==Phase.PUBLISH && cleanupFailed),followUpFailed=followUpFailed,
                    filenameConflict=error is FilenameConflict)
            }
            done.add(item.id)
            try { port.afterPublication(item,published) } catch(_:Exception) { followUpFailed=true }
            try { completed(item.id,done.size) } catch(_:Exception) { followUpFailed=true }
        }
        return Result(done.toList(),followUpFailed=followUpFailed)
    }
}
