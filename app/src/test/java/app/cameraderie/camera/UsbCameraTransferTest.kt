package app.cameraderie.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbCameraTransferTest {
    @Test fun acceptsStandardPictureTransferInterfaces() {
        assertTrue(UsbCameraTransfer.matches(6, 1, 1, null))
        assertTrue(UsbCameraTransfer.matches(6, 1, 1, "Camera"))
    }

    @Test fun acceptsVendorInterfacesThatExplicitlyAdvertiseMtp() {
        assertTrue(UsbCameraTransfer.matches(0xff, 0xff, 0, "MTP"))
        assertFalse(UsbCameraTransfer.matches(0xff, 0xff, 0, "Webcam"))
        assertFalse(UsbCameraTransfer.matches(0xff, 0xff, 1, "MTP"))
    }

    @Test fun ignoresMassStorageAndNonTransferInterfaces() {
        assertFalse(UsbCameraTransfer.matches(8, 6, 80, "Mass storage"))
        assertFalse(UsbCameraTransfer.matches(14, 1, 0, "Video"))
        assertFalse(UsbCameraTransfer.matches(6, 1, 0, "Imaging"))
    }
}
