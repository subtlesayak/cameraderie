package app.cameraderie.camera

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice

/** Detect the USB transfer interface independently of manufacturer or model. */
internal object UsbCameraTransfer {
    fun supports(device: UsbDevice): Boolean = (0 until device.interfaceCount).any { index ->
        val descriptor = device.getInterface(index)
        matches(descriptor.interfaceClass, descriptor.interfaceSubclass, descriptor.interfaceProtocol, descriptor.name)
    }

    fun matches(interfaceClass: Int, subclass: Int, protocol: Int, name: String?): Boolean =
        (interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE && subclass == 1 && protocol == 1) ||
            (interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC && subclass == 0xff && protocol == 0 && name == "MTP")
}
