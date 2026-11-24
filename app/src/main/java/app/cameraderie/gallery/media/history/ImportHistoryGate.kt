package app.cameraderie.gallery.media.history

import java.util.concurrent.locks.ReentrantReadWriteLock

/** One process-wide gate: history recovery cannot delete an active import's
 * pending URI; ordinary completed-history reads need no exclusive lock. */
class ImportHistoryGate {
    private val lock=ReentrantReadWriteLock(true)
    fun <T> importing(block:()->T):T {
        val reader=lock.readLock();reader.lockInterruptibly()
        try { return block() } finally { reader.unlock() }
    }
    fun tryRecover(block:()->Unit):Boolean {
        val writer=lock.writeLock()
        if(!writer.tryLock()) return false
        try { block();return true } finally { writer.unlock() }
    }
    companion object { val process=ImportHistoryGate() }
}
