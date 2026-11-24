package app.cameraderie.sony

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class SonyPtpIpTest {
    private fun b(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    private fun packet(type: Int, body: ByteArray = byteArrayOf()) = b(body.size+8).putInt(body.size+8).putInt(type).put(body).array()
    private fun response(tx: Int, code: Int = 0x2001) = packet(7,b(6).putShort(code.toShort()).putInt(tx).array())
    private fun text(value: String): ByteArray = byteArrayOf((value.length+1).toByte())+value.toByteArray(Charsets.UTF_16LE)+byteArrayOf(0,0)
    private fun u16Array(vararg values: Int) = b(4+values.size*2).apply { putInt(values.size);values.forEach { putShort(it.toShort()) } }.array()
    private fun u32Array(vararg values: Int) = b(4+values.size*4).apply { putInt(values.size);values.forEach { putInt(it) } }.array()
    private fun info(model: String = "ILCE-6400", operations: IntArray = intArrayOf(0x1001,0x1004,0x1007,0x1008,0x1009)): ByteArray =
        b(8).putShort(100).putInt(6).putShort(100).array()+text("Sony")+b(2).putShort(0).array()+
        u16Array(*operations)+u16Array()+u16Array()+u16Array()+u16Array()+text("Sony Corporation")+text(model)+text("2.00")+text("fixture")
    private fun data(tx: Int, payload: ByteArray): ByteArray = packet(9,b(12).putInt(tx).putLong(payload.size.toLong()).array())+
        packet(12,b(payload.size+4).putInt(tx).put(payload).array())+response(tx)
    private fun ack() = packet(2,b(26).putInt(123).putLong(4).putLong(5).putShort(0).putInt(0x10000).array())
    private fun opening(model: String="ILCE-6400", operations: IntArray=intArrayOf(0x1001,0x1004,0x1007,0x1008,0x1009)) = ack()+data(0,info(model,operations))+response(0)
    private fun objectDataset(name: String="DSC00001.ARW",size: Int=9) = b(52).apply {
        putInt(1); putShort(0xB101.toShort());putShort(0);putInt(size)
        position(38);putInt(77);putShort(0);putInt(0);putInt(0)
    }.array()+text(name)+text("")+text("")+text("")

    private class ScriptedSocket(bytes: ByteArray, private val fragment: Int=3, private val waitAtEnd: Boolean=false): Socket() {
        val sent=ByteArrayOutputStream()
        @Volatile var wasClosed=false
        private val source=ByteArrayInputStream(bytes)
        override fun getInputStream(): InputStream = object: InputStream() {
            private fun awaitBytes() { while(waitAtEnd && source.available()==0 && !wasClosed) Thread.sleep(2) }
            override fun read(): Int { awaitBytes();return source.read() }
            override fun read(buffer: ByteArray,off: Int,len: Int): Int { awaitBytes();return source.read(buffer,off,minOf(fragment,len)) }
        }
        override fun getOutputStream()=sent
        override fun setSoTimeout(value: Int) {}
        override fun close() { wasClosed=true }
    }
    private fun client(bytes: ByteArray,expected:String?=null): Pair<SonyPtpIp,ScriptedSocket> {
        val command=ScriptedSocket(bytes)
        return SonyPtpIp(command,ScriptedSocket(packet(4),waitAtEnd=true),expected) to command
    }
    private fun expectIo(action: ()->Unit): IOException {
        try { action(); fail("Expected IOException") } catch(e: IOException) { return e }
        throw AssertionError()
    }

    @Test fun identityEnumerationAndRawDownloadUseOriginalBytesAcrossSplitPackets() {
        val original=byteArrayOf(0x49,0x49,0x2A,0,1,2,3,4,5)
        val transfer=packet(9,b(12).putInt(4).putLong(9).array())+
            packet(10,b(8).putInt(4).put(original,0,4).array())+packet(13)+
            packet(12,b(9).putInt(4).put(original,4,5).array())+response(4)
        val (c,socket)=client(opening()+data(1,u32Array(1))+data(2,u32Array(42))+data(3,objectDataset())+transfer)
        c.open(uuid=UUID(1,2));assertEquals("Sony Corporation",c.manufacturer);assertEquals("ILCE-6400",c.model)
        assertEquals(listOf(1L),c.storageIds());assertEquals(listOf(42L),c.handles(1))
        val item=c.objectInfo(42);assertEquals(9L,item.size);assertEquals(77L,item.parent);assertTrue(item.isRaw);assertFalse(item.isFolder)
        val output=ByteArrayOutputStream(); val progress=mutableListOf<Long>()
        assertEquals(9L,c.download(42,output,item.size,{false},{progress+=it}))
        assertArrayEquals(original,output.toByteArray());assertEquals(9L,progress.last())
        // Operation codes present in the emitted command frames are read-only.
        val raw=ByteBuffer.wrap(socket.sent.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        val codes=mutableListOf<Int>()
        while(raw.hasRemaining()) { val start=raw.position();val size=raw.int;val type=raw.int;if(type==6) { raw.int;codes+=raw.short.toInt() and 65535 };raw.position(start+size) }
        assertEquals(listOf(0x1001,0x1002,0x1004,0x1007,0x1008,0x1009),codes)
        c.close();assertTrue(socket.wasClosed)
    }

    @Test fun anotherCameraCannotBeBrowsedEvenIfItAdvertisesTheSameOperations() {
        val (c,socket)=client(opening("ILCE-7M3"),"ILCE-6400")
        assertTrue(expectIo { c.open() }.message!!.contains("not a matching Sony camera"));assertTrue(socket.wasClosed)
    }

    @Test fun otherSonyModelsUseTheirAdvertisedReadOnlyObjectCapabilities() {
        val (c,socket)=client(opening("ILCE-7M3"),"ILCE-7M3")
        c.open();assertEquals("ILCE-7M3",c.model);c.close();assertTrue(socket.wasClosed)
    }
    @Test fun missingOriginalObjectCapabilityIsExplicitRatherThanClaimingRawSupport() {
        val (c,socket)=client(opening(operations=intArrayOf(0x1001,0x1004,0x1007,0x1008)))
        assertTrue(expectIo { c.open() }.message!!.contains("original-file"));assertTrue(socket.wasClosed)
    }

    @Test fun mismatchedTransactionClosesAnUnusableSession() {
        val (c,socket)=client(opening()+data(55,u32Array(1)))
        c.open();assertTrue(expectIo { c.storageIds() }.message!!.contains("transaction mismatch"));assertTrue(socket.wasClosed)
    }

    @Test fun invalidFrameLengthIsRejectedBeforeAllocation() {
        val (c,socket)=client(opening()+b(8).putInt(Int.MAX_VALUE).putInt(9).array())
        c.open();assertTrue(expectIo { c.storageIds() }.message!!.contains("packet length"));assertTrue(socket.wasClosed)
    }

    @Test fun truncatedFileIsNeverReportedAsSuccessful() {
        val payload=packet(9,b(12).putInt(1).putLong(9).array())+packet(12,b(6).putInt(1).put(byteArrayOf(1,2)).array())+response(1)
        val (c,socket)=client(opening()+payload);assertFalse(c.isOpen);c.open();assertTrue(c.isOpen)
        assertTrue(expectIo { c.download(1,ByteArrayOutputStream(),9,{false},{}) }.message!!.contains("declared length"));assertTrue(socket.wasClosed)
        assertFalse(c.isOpen)
        assertTrue(expectIo { c.download(1,ByteArrayOutputStream(),9,{false},{}) }.message!!.contains("not connected"))
    }
    @Test fun failedSecondImportKeepsPublishedFirstFileAndExposesTransportLossForReconnect() {
        val original=byteArrayOf(1,2)
        val bad=packet(9,b(12).putInt(2).putLong(9).array())+packet(12,b(6).putInt(2).put(original).array())+response(2)
        val (c,_)=client(opening()+data(1,original)+bad);c.open()
        val published=mutableSetOf<String>();val pending=mutableMapOf<String,ByteArrayOutputStream>()
        val transaction=app.cameraderie.gallery.media.MediaImportTransaction()
        val port=object:app.cameraderie.gallery.media.MediaImportTransaction.Port {
            override fun createPending(item:app.cameraderie.gallery.media.MediaImportTransaction.Item)=item.id.also { pending[it]=ByteArrayOutputStream() }
            override fun copy(item:app.cameraderie.gallery.media.MediaImportTransaction.Item,destination:String):app.cameraderie.gallery.media.MediaImportTransaction.Copied {
                val bytes=c.download(item.id.toLong(),pending.getValue(destination),item.bytes,{false},{})
                return app.cameraderie.gallery.media.MediaImportTransaction.Copied(bytes,null)
            }
            override fun verify(item:app.cameraderie.gallery.media.MediaImportTransaction.Item,destination:String,copied:app.cameraderie.gallery.media.MediaImportTransaction.Copied) { assertEquals(item.bytes,pending.getValue(destination).size().toLong()) }
            override fun publish(destination:String) { pending.remove(destination);published.add(destination) }
            override fun remove(destination:String) { pending.remove(destination) }
        }
        val result=transaction.run(listOf(app.cameraderie.gallery.media.MediaImportTransaction.Item("1","one.JPG",2),app.cameraderie.gallery.media.MediaImportTransaction.Item("2","two.JPG",9)),port,{false})
        assertEquals(listOf("1"),result.completed);assertEquals(setOf("1"),published);assertTrue(pending.isEmpty())
        assertFalse(c.isOpen) // Activity observes this signal and disconnects instead of retaining Retry.
    }

    @Test fun completeBytesWithCameraErrorAreNotACompletedDownload() {
        val payload=packet(9,b(12).putInt(1).putLong(2).array())+packet(12,b(6).putInt(1).put(byteArrayOf(1,2)).array())+response(1,0x2005)
        val (c,socket)=client(opening()+payload);c.open();val output=ByteArrayOutputStream()
        assertTrue(expectIo { c.download(1,output,2,{false},{}) }.message!!.contains("rejected"));assertEquals(2,output.size());assertTrue(socket.wasClosed)
    }

    @Test fun cancellationClosesBothSocketsInsteadOfLeavingTheTransactionForNextDownload() {
        val (c,socket)=client(opening());c.open()
        expectIo { c.download(1,ByteArrayOutputStream(),9,{true},{}) };assertTrue(socket.wasClosed)
    }

    @Test fun invalidObjectListCountIsNotAnEmptyLibrary() {
        val (c,socket)=client(opening()+data(1,b(4).putInt(100001).array()));c.open()
        expectIo { c.handles(1) };assertTrue(socket.wasClosed)
    }

    @Test fun objectInformationWithTruncatedFilenameFails() {
        val (c,socket)=client(opening()+data(1,objectDataset().copyOf(54)));c.open()
        expectIo { c.objectInfo(1) };assertTrue(socket.wasClosed)
    }
}
