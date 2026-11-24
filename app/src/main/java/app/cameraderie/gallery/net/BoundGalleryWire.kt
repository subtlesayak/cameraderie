package app.cameraderie.gallery.net

import app.cameraderie.gallery.GalleryResourceScope
import app.cameraderie.gallery.protocol.GalleryDatalinkSession
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException

/** Network-scoped production IO, supplied to the single gallery worker. */
class BoundGalleryWire(private val network: BoundCameraNetwork): GalleryDatalinkSession.Wire {
    private val resources=GalleryResourceScope()
    private var udp: GalleryResourceScope.Handle<DatagramSocket>?=null
    private val receiveBuffer=ByteArray(16384)
    override fun probe(pairingFrame: ByteArray) {
        network.tcp().use { connection ->
            cameraSocketStep(GalleryDatalinkSession.SocketStep.TCP_WRITE) {
                connection.value.getOutputStream().apply { write(pairingFrame);flush() }
            }
            // Measured reference bootstrap settle; cancellation closes the network immediately.
            Thread.sleep(400)
        }
    }
    override fun openUdp() {
        val handle=network.udp()
        resources.track(handle) { it.close() };udp=handle
    }
    override fun send(packet: ByteArray) {
        val socket=checkNotNull(udp) { "Camera UDP session is not open" }.value
        socket.send(DatagramPacket(packet,packet.size))
    }
    override fun receive(timeoutMs: Int): ByteArray? {
        require(timeoutMs in 1..1000)
        val socket=checkNotNull(udp) { "Camera UDP session is not open" }.value
        socket.soTimeout=timeoutMs
        val packet=DatagramPacket(receiveBuffer,receiveBuffer.size)
        return try { socket.receive(packet);receiveBuffer.copyOf(packet.length) } catch(_: SocketTimeoutException) { null }
    }
    override fun close() { resources.close();network.close() }
}
