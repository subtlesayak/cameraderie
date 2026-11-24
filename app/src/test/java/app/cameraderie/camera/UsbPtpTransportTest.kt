package app.cameraderie.camera

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class UsbPtpTransportTest {
    @Test fun readsBoundedDataPacketsAndChecksSessionTransactions() {
        val body = ByteArray(85) { it.toByte() }
        val data = container(2, 0x1009, 1, body)
        val exchanges = ArrayDeque(listOf(
            container(3, 0x2001, 0),
            data.copyOfRange(0, 64),
            data.copyOfRange(64, data.size),
            byteArrayOf(),
            container(3, 0x2001, 1),
            container(3, 0x2001, 2),
        ))
        val requests = mutableListOf<ByteArray>()
        val usb = UsbPtpTransport(
            write = { requests.add(it); it.size },
            read = { buffer, _ -> exchanges.removeFirst().also { it.copyInto(buffer) }.size },
            packetSize = 64,
        )
        usb.open()
        val reply = usb.command(0x1009, -16382, maxData = 100)
        usb.close()
        assertEquals(0x2001, reply.code)
        assertArrayEquals(body, reply.data)
        assertArrayEquals(container(1, 0x1002, 0, byteArrayOf(1, 0, 0, 0)), requests.first())
        assertArrayEquals(container(1, 0x1003, 2), requests.last())
    }

    @Test fun preservesRejectionAndFailsMismatchedOrOversizedDataBeforeReadingMore() {
        fun usb(first: ByteArray) = UsbPtpTransport(
            write = { it.size }, read = { buffer, _ -> first.copyInto(buffer); first.size }, packetSize = 64,
        )
        val failure = usb(container(3, 0x2005, 0)).command(UsbPtpTransport.GET_DEVICE_INFO, maxData = 32)
        assertEquals(0x2005, failure.code)
        assertNull(failure.data)
        assertThrows(IOException::class.java) {
            usb(container(3, 0x2001, 7)).command(UsbPtpTransport.GET_DEVICE_INFO, maxData = 32)
        }
        assertThrows(IOException::class.java) {
            usb(container(2, 0x1001, 0, ByteArray(40))).command(UsbPtpTransport.GET_DEVICE_INFO, maxData = 32)
        }
    }

    @Test fun sendsControlDataWithTheMatchingTransaction() {
        val replies = ArrayDeque(listOf(container(3, 0x2001, 0), container(3, 0x2001, 1)))
        val requests = mutableListOf<ByteArray>()
        val usb = UsbPtpTransport(
            write = { requests.add(it); it.size },
            read = { buffer, _ -> replies.removeFirst().also { it.copyInto(buffer) }.size },
            packetSize = 64,
        )
        usb.open()
        val response = usb.command(0x9207, 0xd21e, outgoing = byteArrayOf(-1))
        assertEquals(0x2001, response.code)
        assertArrayEquals(container(1, 0x9207, 1, byteArrayOf(0x1e, 0xd2.toByte(), 0, 0)), requests[1])
        assertArrayEquals(container(2, 0x9207, 1, byteArrayOf(-1)), requests[2])
    }

    private fun container(type: Int, code: Int, transaction: Int, payload: ByteArray = byteArrayOf()): ByteArray =
        ByteBuffer.allocate(12 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(12 + payload.size).putShort(type.toShort()).putShort(code.toShort())
            .putInt(transaction).put(payload).array()
}
