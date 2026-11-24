package app.cameraderie.zoom

import app.cameraderie.gallery.protocol.DumlFrame

/** One protocol owner per physical DUML connection. All callbacks run on its owner thread. */
interface DumlBleProtocol {
    fun start()
    fun tick()
    fun accept(frame: DumlFrame)
    fun close()
}
