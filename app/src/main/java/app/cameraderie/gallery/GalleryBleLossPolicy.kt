package app.cameraderie.gallery

import app.cameraderie.gallery.ble.GalleryBleClient
import app.cameraderie.gallery.protocol.GalleryBleHandshake

/** After credentials, Wi-Fi/session evidence owns transport liveness. */
internal object GalleryBleLossPolicy {
    fun defersToWifi(credentialsReady:Boolean,failure:GalleryBleClient.Failure,protocol:GalleryBleHandshake.Failure?):Boolean {
        if(failure==GalleryBleClient.Failure.DISCONNECTED && protocol==null) return true
        if(failure==GalleryBleClient.Failure.PROTOCOL_FAILED && protocol==GalleryBleHandshake.Failure.TRANSPORT_LOST) return true
        return credentialsReady && (
            failure==GalleryBleClient.Failure.WRITE_FAILED && protocol==null ||
            failure==GalleryBleClient.Failure.PROTOCOL_FAILED && protocol==GalleryBleHandshake.Failure.WRITE_FAILED)
    }
}
