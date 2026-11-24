package app.cameraderie.gallery

import app.cameraderie.gallery.ble.GalleryBleClient.Failure
import app.cameraderie.gallery.protocol.GalleryBleHandshake
import org.junit.Assert.*
import org.junit.Test

class GalleryBleLossPolicyTest {
    @Test fun postCredentialWriteLossUsesTheSameHandoffPathInEitherCallbackOrder() {
        for(order in listOf(listOf(Failure.WRITE_FAILED,Failure.DISCONNECTED),listOf(Failure.DISCONNECTED,Failure.WRITE_FAILED)))
            for(failure in order) assertTrue(GalleryBleLossPolicy.defersToWifi(true,failure,null))
        assertTrue(GalleryBleLossPolicy.defersToWifi(true,Failure.PROTOCOL_FAILED,GalleryBleHandshake.Failure.WRITE_FAILED))
    }
    @Test fun writesBeforeCredentialsAndNonTransportFailuresRemainFatal() {
        assertFalse(GalleryBleLossPolicy.defersToWifi(false,Failure.WRITE_FAILED,null))
        assertFalse(GalleryBleLossPolicy.defersToWifi(false,Failure.PROTOCOL_FAILED,GalleryBleHandshake.Failure.WRITE_FAILED))
        for(failure in Failure.entries.filter { it !in setOf(Failure.WRITE_FAILED,Failure.DISCONNECTED,Failure.PROTOCOL_FAILED) })
            assertFalse(failure.name,GalleryBleLossPolicy.defersToWifi(true,failure,null))
        for(failure in GalleryBleHandshake.Failure.entries.filter { it !in setOf(GalleryBleHandshake.Failure.TRANSPORT_LOST,GalleryBleHandshake.Failure.WRITE_FAILED) })
            assertFalse(failure.name,GalleryBleLossPolicy.defersToWifi(true,Failure.PROTOCOL_FAILED,failure))
    }
}
