package app.cameraderie.gallery.net

import android.Manifest.permission
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraWifiAccessTest {
    @Test fun android12StillRequiresPreciseLocationForJoining() {
        for (sdk in 31..32) {
            assertEquals(listOf(permission.ACCESS_FINE_LOCATION), CameraWifiAccess.required(sdk, 37))
        }
    }

    @Test fun localNetworkPermissionRequiresAndroid17AndTarget37() {
        val nearbyWifi = listOf(permission.NEARBY_WIFI_DEVICES)
        for (sdk in 33..36) {
            assertEquals(nearbyWifi, CameraWifiAccess.required(sdk, 37))
        }
        assertEquals(nearbyWifi, CameraWifiAccess.required(37, 36))
        assertEquals(nearbyWifi + permission.ACCESS_LOCAL_NETWORK, CameraWifiAccess.required(37, 37))
    }
}
