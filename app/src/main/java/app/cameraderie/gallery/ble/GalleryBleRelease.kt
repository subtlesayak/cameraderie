@file:Suppress("DEPRECATION")
package app.cameraderie.gallery.ble

import android.bluetooth.BluetoothDevice
import android.content.*
import android.os.*
import app.cameraderie.gallery.GalleryReleaseGate

/** Main-thread system-event barrier, registered before R-SDK disconnect. No
 * bonding, radio reset or disconnect of another app's camera connection. */
class GalleryBleRelease(private val context: Context,private val device: BluetoothDevice,private val log: (String)->Unit): AutoCloseable {
    private val handler=Handler(Looper.getMainLooper())
    private val gate=GalleryReleaseGate(SystemClock::elapsedRealtime)
    private var registered=false
    private var closed=false
    private var released: (() -> Unit)?=null
    private var timedOut: (() -> Unit)?=null
    private fun serial()=check(Looper.myLooper()==Looper.getMainLooper())
    private val receiver=object: BroadcastReceiver() {
        override fun onReceive(context: Context,intent: Intent) {
            if(closed || intent.action!=BluetoothDevice.ACTION_ACL_DISCONNECTED) return
            val remote=if(Build.VERSION.SDK_INT>=33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice::class.java)
                else intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            if(remote!=device) return
            val transport=if(Build.VERSION.SDK_INT>=33) intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT,BluetoothDevice.TRANSPORT_AUTO)
                else BluetoothDevice.TRANSPORT_AUTO
            if(transport !in setOf(BluetoothDevice.TRANSPORT_LE,BluetoothDevice.TRANSPORT_AUTO)) return
            if(gate.disconnected()) log("Gallery BLE handoff=RELEASE_OBSERVED")
        }
    }
    private val tick=object: Runnable { override fun run() {
        if(closed) return
        gate.tick()
        when(gate.state) {
            GalleryReleaseGate.State.OPEN -> {
                val done=released;released=null;timedOut=null;stopWatching()
                log("Gallery BLE handoff=OPEN_AFTER_RELEASE");done?.invoke()
            }
            GalleryReleaseGate.State.TIMED_OUT -> {
                val failure=timedOut;released=null;timedOut=null;stopWatching()
                log("Gallery BLE handoff=RELEASE_TIMEOUT");failure?.invoke()
            }
            GalleryReleaseGate.State.WAITING,GalleryReleaseGate.State.SETTLING -> handler.postDelayed(this,50)
            else -> Unit
        }
    } }
    /** Observe first, then the owner closes its R-SDK client, then await. */
    fun watch() {
        serial();check(!closed && !registered)
        val filter=IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        // Bluetooth can originate in a privileged UID distinct from system UID.
        // This is a protected system action, filtered again by device/transport.
        if(Build.VERSION.SDK_INT>=33) context.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver,filter)
        registered=true
    }
    fun await(onReleased: ()->Unit,onTimeout: ()->Unit) {
        serial();check(!closed && registered)
        released=onReleased;timedOut=onTimeout;gate.begin()
        log("Gallery BLE handoff=WAITING_FOR_RELEASE");handler.post(tick)
    }
    private fun stopWatching() {
        handler.removeCallbacks(tick)
        if(registered) { registered=false;try { context.unregisterReceiver(receiver) } catch(_: IllegalArgumentException) {} }
    }
    override fun close() {
        serial();if(closed) return
        closed=true;gate.cancel();released=null;timedOut=null;stopWatching()
    }
}
