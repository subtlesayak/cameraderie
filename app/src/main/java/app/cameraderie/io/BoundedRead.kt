package app.cameraderie.io

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

internal fun InputStream.readBounded(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        if (count > maxBytes - output.size()) throw IOException("The selected file is too large")
        output.write(buffer, 0, count)
    }
}
