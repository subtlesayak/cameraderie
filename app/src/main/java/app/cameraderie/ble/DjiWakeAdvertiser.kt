package app.cameraderie.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.cameraderie.core.DjiWakePayload

/** A separate, short connectable advertisement matching the SDK ADV_TYPE_IND. Sony advertising remains independently owned. */
@SuppressLint("MissingPermission")
class DjiWakeAdvertiser(context: Context, private val log: (String) -> Unit) {
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    private var callback: AdvertisingSetCallback? = null
    var active = false; private set

    fun cancel() {
        generation++
        handler.removeCallbacksAndMessages(null)
        val previous = callback; callback = null; active = false
        if(previous != null) try { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(previous) }
        catch(_: SecurityException) { log("Wake advertisement cleanup delegated to Android after permission loss") }
    }
    fun start(address: String, onStarted: () -> Unit, complete: (Boolean) -> Unit) {
        val data = DjiWakePayload.manufacturerData(address)
        cancel()
        check(adapter?.isEnabled == true) { "Enable Bluetooth before waking the camera" }
        val advertiser = adapter.bluetoothLeAdvertiser ?: error("BLE wake advertising unavailable")
        val token = generation
        var started = false
        var stopRequested = false
        fun finish(ok: Boolean) {
            if(token != generation) return
            cancel(); complete(ok)
        }
        val cb = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                if(token != generation) { try { advertiser.stopAdvertisingSet(this) } catch(_: SecurityException) {} ; return }
                if(status != ADVERTISE_SUCCESS || advertisingSet == null) {
                    log("DJI wake advertising failed ($status)"); finish(false); return
                }
                started = true
                log("DJI legacy wake advertisement active for three seconds at requested 100ms interval")
                onStarted()
                handler.postDelayed({
                    if (token != generation) return@postDelayed
                    stopRequested = true
                    try {
                        advertiser.stopAdvertisingSet(this)
                    } catch (_: SecurityException) {
                        finish(false)
                        return@postDelayed
                    }
                    handler.postDelayed({
                        if (token == generation) {
                            log("DJI wake advertisement stop confirmation timed out")
                            finish(false)
                        }
                    }, 2000)
                }, 3000)
            }

            override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
                if (token != generation) return
                callback = null
                finish(stopRequested)
            }
        }
        callback = cb; active = true
        try {
            val parameters = AdvertisingSetParameters.Builder().setLegacyMode(true)
                .setConnectable(true).setScannable(true).setInterval(AdvertisingSetParameters.INTERVAL_LOW).build()
            advertiser.startAdvertisingSet(parameters,
                AdvertiseData.Builder().addManufacturerData(DjiWakePayload.manufacturerId,data).build(),
                null,null,null,cb)
        } catch(e: Exception) { cancel(); throw e }
        handler.postDelayed({ if(token == generation && !started) { log("DJI wake advertisement startup timeout"); finish(false) } },4000)
    }
}
