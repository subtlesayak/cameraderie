@file:Suppress("DEPRECATION")
package app.cameraderie.gallery.ble

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import app.cameraderie.gallery.protocol.*
import app.cameraderie.zoom.DumlBleProtocol
import java.util.ArrayDeque
import java.util.UUID

/** DUML media GATT transport, deliberately independent of the R-SDK client.
 * No automatic bond/reset, credential logging or bridge-session takeover. */
class GalleryBleClient(context: Context,private val ownsGallery: () -> Boolean,private val listener: Listener,
    private val protocolFactory: ((GalleryIdentity, (DumlFrame, (Boolean) -> Unit) -> Boolean) -> DumlBleProtocol)? = null,
) {
    enum class Setup { CONNECTING, NEGOTIATING_MTU, DISCOVERING, SUBSCRIBING, ARMING, READY }
    enum class Failure { OWNERSHIP_REQUIRED, PERMISSION_REQUIRED, CONNECTION_FAILED, SETUP_TIMEOUT, MTU_NEGOTIATION_FAILED, MTU_TOO_SMALL, MISSING_SERVICE, NOTIFICATION_FAILED, ARM_FAILED, EARLY_NOTIFICATION_LIMIT, WRITE_FAILED, DISCONNECTED, IDENTITY_UNAVAILABLE, PROTOCOL_FAILED }
    interface Listener {
        fun setupChanged(setup: Setup)
        fun protocolChanged(phase: GalleryBleHandshake.Phase)
        fun credentialsReady(credentials: CameraWifiCredentials,identity: GalleryIdentity)
        fun failed(failure: Failure,protocol: GalleryBleHandshake.Failure?=null)
    }
    private val app=context.applicationContext
    private val handler=Handler(Looper.getMainLooper())
    private var current: Attempt?=null
    private val deferredClose=linkedSetOf<BluetoothGatt>()
    var lastTransportDiagnostic: String?=null; private set
    val active get()=current!=null
    var negotiatedMtu: Int?=null; private set
    var lastProtocolDiagnostic: GalleryBleHandshake.Diagnostic?=null; private set
    private class Write(val bytes: ByteArray,val complete: (Boolean)->Unit)
    private inner class Attempt(val identity: GalleryIdentity) {
        var gatt: BluetoothGatt?=null
        var setup=Setup.CONNECTING
        var deadline=SystemClock.elapsedRealtime()+15000
        var mtu=23
        var descriptor: BluetoothGattDescriptor?=null
        var notifyIndex=0
        var arm: BluetoothGattCharacteristic?=null
        var writer: BluetoothGattCharacteristic?=null
        var writing=false
        var writeDeadline=0L
        var nextWrite=0L
        var readyAt=Long.MAX_VALUE
        val writes=ArrayDeque<Write>()
        val streams=mapOf(FFF4 to DumlFramer(),FFF5 to DumlFramer())
        val telemetry=GalleryBleDiagnostics()
        val inbox=GalleryBleInbox()
        var engine: GalleryBleHandshake?=null
        var custom: DumlBleProtocol?=null
        val tick=object: Runnable { override fun run() {
            if(current !== this@Attempt) return
            if(!ownsGallery()) { close();return }
            val now=SystemClock.elapsedRealtime()
            if(setup != Setup.READY && now >= deadline) { fail(this@Attempt,Failure.SETUP_TIMEOUT);return }
            if(writing && now >= writeDeadline) { fail(this@Attempt,Failure.WRITE_FAILED);return }
            if(setup == Setup.READY && engine == null && custom == null && now >= readyAt) startProtocol(this@Attempt)
            if(current !== this@Attempt) return
            engine?.tick();custom?.tick();pump(this@Attempt)
            if(current === this@Attempt) handler.postDelayed(this,50)
        } }
    }
    private fun requireMain()=check(Looper.myLooper()==Looper.getMainLooper())
    private fun hasPermission()=app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
    /** The coordinator must own a current gallery lease before calling this. */
    fun connect(device: BluetoothDevice) {
        requireMain();close();negotiatedMtu=null;lastProtocolDiagnostic=null;lastTransportDiagnostic=null
        if(!ownsGallery()) { listener.failed(Failure.OWNERSHIP_REQUIRED);return }
        if(!hasPermission()) { listener.failed(Failure.PERMISSION_REQUIRED);return }
        if(deferredClose.isNotEmpty()) { listener.failed(Failure.CONNECTION_FAILED);return }
        val preferences=app.getSharedPreferences("gallery",Context.MODE_PRIVATE)
        val identity=try { GalleryIdentity.load(object: GalleryIdentity.Store {
            override fun read()=preferences.getString("pairing_identity",null)
            override fun save(value: String)=preferences.edit().putString("pairing_identity",value).commit()
        }) } catch(_: RuntimeException) { listener.failed(Failure.IDENTITY_UNAVAILABLE);return }
        val attempt=Attempt(identity);current=attempt
        listener.setupChanged(Setup.CONNECTING)
        if(current !== attempt) return
        if(!ownsGallery()) { close();return }
        try {
            attempt.gatt=device.connectGatt(app,false,callback(attempt),BluetoothDevice.TRANSPORT_LE)
            if(attempt.gatt == null) { fail(attempt,Failure.CONNECTION_FAILED);return }
            handler.post(attempt.tick)
        } catch(_: SecurityException) { fail(attempt,Failure.PERMISSION_REQUIRED) }
        catch(_: RuntimeException) { fail(attempt,Failure.CONNECTION_FAILED) }
    }
    private fun isCurrent(a: Attempt,g: BluetoothGatt)=current === a && a.gatt === g && ownsGallery()
    private fun move(a: Attempt,value: Setup): Boolean {
        a.setup=value;a.deadline=SystemClock.elapsedRealtime()+8000;listener.setupChanged(value)
        return current === a && ownsGallery()
    }
    private fun callback(a: Attempt)=object: BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt,status: Int,newState: Int) { handler.post {
            if(!isCurrent(a,g)) return@post
            if(status != BluetoothGatt.GATT_SUCCESS || newState==BluetoothProfile.STATE_DISCONNECTED) {
                fail(a,if(a.setup==Setup.CONNECTING) Failure.CONNECTION_FAILED else Failure.DISCONNECTED);return@post
            }
            if(newState==BluetoothProfile.STATE_CONNECTED && a.setup==Setup.CONNECTING) {
                if(!move(a,Setup.NEGOTIATING_MTU)) return@post
                try {
                    // Request the documented value. Android 14+ can negotiate 517 regardless;
                    // never claim a requested MTU is the actual one or emulate a fake 500.
                    if(!g.requestMtu(500)) fail(a,Failure.CONNECTION_FAILED)
                } catch(_: SecurityException) { fail(a,Failure.PERMISSION_REQUIRED) }
            }
        } }
        override fun onMtuChanged(g: BluetoothGatt,mtu: Int,status: Int) { handler.post {
            if(!isCurrent(a,g) || a.setup!=Setup.NEGOTIATING_MTU) return@post
            if(status!=BluetoothGatt.GATT_SUCCESS) { fail(a,Failure.MTU_NEGOTIATION_FAILED);return@post }
            if(mtu<80) { fail(a,Failure.MTU_TOO_SMALL);return@post }
            a.mtu=mtu;negotiatedMtu=mtu;if(!move(a,Setup.DISCOVERING)) return@post
            try { if(!g.discoverServices()) fail(a,Failure.CONNECTION_FAILED) }
            catch(_: SecurityException) { fail(a,Failure.PERMISSION_REQUIRED) }
        } }
        override fun onServicesDiscovered(g: BluetoothGatt,status: Int) { handler.post {
            if(!isCurrent(a,g) || a.setup!=Setup.DISCOVERING) return@post
            val service=g.getService(SERVICE)
            val four=service?.getCharacteristic(FFF4);val five=service?.getCharacteristic(FFF5)
            if(status!=BluetoothGatt.GATT_SUCCESS || four==null || five==null ||
                four.properties and BluetoothGattCharacteristic.PROPERTY_WRITE==0 ||
                five.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE==0 ||
                listOf(four,five).any { it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY==0 || it.getDescriptor(CCCD)==null }) {
                fail(a,Failure.MISSING_SERVICE);return@post
            }
            a.arm=four;a.writer=five
            if(move(a,Setup.SUBSCRIBING)) subscribeNext(a)
        } }
        override fun onDescriptorWrite(g: BluetoothGatt,descriptor: BluetoothGattDescriptor,status: Int) { handler.post {
            if(!isCurrent(a,g) || a.setup!=Setup.SUBSCRIBING || a.descriptor !== descriptor) return@post
            a.descriptor=null
            if(status!=BluetoothGatt.GATT_SUCCESS) { fail(a,Failure.NOTIFICATION_FAILED);return@post }
            a.notifyIndex++;subscribeNext(a)
        } }
        override fun onCharacteristicWrite(g: BluetoothGatt,characteristic: BluetoothGattCharacteristic,status: Int) { handler.post {
            if(!isCurrent(a,g)) return@post
            if(a.setup==Setup.ARMING && characteristic === a.arm) {
                if(status!=BluetoothGatt.GATT_SUCCESS) { fail(a,Failure.ARM_FAILED);return@post }
                // READY describes GATT only; protocol authorization is still unknown.
                a.readyAt=SystemClock.elapsedRealtime()+200;move(a,Setup.READY)
            } else if(a.setup==Setup.READY && characteristic === a.writer && a.writing) {
                if(status!=BluetoothGatt.GATT_SUCCESS) { fail(a,Failure.WRITE_FAILED);return@post }
                a.telemetry.completed(status)
                a.writing=false;val completed=a.writes.pollFirst();a.nextWrite=SystemClock.elapsedRealtime()+120
                completed?.bytes?.fill(0);completed?.complete?.invoke(true)
            }
        } }
        override fun onCharacteristicChanged(g: BluetoothGatt,characteristic: BluetoothGattCharacteristic,value: ByteArray) {
            notification(a,g,characteristic.uuid,value.copyOf())
        }
        override fun onCharacteristicChanged(g: BluetoothGatt,characteristic: BluetoothGattCharacteristic) {
            notification(a,g,characteristic.uuid,characteristic.value?.copyOf() ?: byteArrayOf())
        }
    }
    private fun subscribeNext(a: Attempt) {
        if(current !== a || !ownsGallery()) return
        val g=a.gatt ?: return
        if(a.notifyIndex>=2) {
            if(!move(a,Setup.ARMING)) return
            try {
                if(!write(g,a.arm!!,byteArrayOf(1,0),BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)) fail(a,Failure.ARM_FAILED)
            } catch(_: SecurityException) { fail(a,Failure.PERMISSION_REQUIRED) }
            return
        }
        val ch=if(a.notifyIndex==0) a.arm!! else a.writer!!
        val descriptor=ch.getDescriptor(CCCD) ?: run { fail(a,Failure.NOTIFICATION_FAILED);return }
        a.descriptor=descriptor
        try {
            if(!g.setCharacteristicNotification(ch,true)) { fail(a,Failure.NOTIFICATION_FAILED);return }
            val ok=if(Build.VERSION.SDK_INT>=33) g.writeDescriptor(descriptor,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)==BluetoothStatusCodes.SUCCESS
                else { descriptor.value=BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;g.writeDescriptor(descriptor) }
            if(!ok) fail(a,Failure.NOTIFICATION_FAILED)
        } catch(_: SecurityException) { fail(a,Failure.PERMISSION_REQUIRED) }
    }
    private fun startProtocol(a: Attempt) {
        if(current !== a || !ownsGallery()) return
        val factory=protocolFactory
        if(factory!=null) {
            val protocol=factory(a.identity) { frame,complete -> enqueue(a,frame,complete) }
            a.custom=protocol;protocol.start()
            while(current===a && ownsGallery()) protocol.accept(a.inbox.next() ?: break)
            return
        }
        val engine=GalleryBleHandshake(a.identity,SystemClock::elapsedRealtime,{frame ->
            enqueue(a,frame) {}
        },object: GalleryBleHandshake.Listener {
            override fun phaseChanged(phase: GalleryBleHandshake.Phase) { if(current === a && ownsGallery()) listener.protocolChanged(phase) }
            override fun credentialsReady(credentials: CameraWifiCredentials) { if(current === a && ownsGallery()) listener.credentialsReady(credentials,a.identity) }
            override fun failed(reason: GalleryBleHandshake.Failure) { fail(a,Failure.PROTOCOL_FAILED,reason) }
        })
        a.engine=engine;engine.start()
        while(current===a && ownsGallery()) engine.accept(a.inbox.next() ?: break)
    }
    private fun enqueue(a: Attempt,frame: DumlFrame,complete: (Boolean)->Unit): Boolean {
        if(current !== a || !ownsGallery() || a.setup!=Setup.READY || a.writes.size>=16) return false
        val bytes=frame.encode()
        if(bytes.size>a.mtu-3) { bytes.fill(0);return false }
        a.writes.add(Write(bytes,complete));return true
    }
    private fun notification(a: Attempt,g: BluetoothGatt,source: UUID,value: ByteArray) { handler.post {
        try {
            if(!isCurrent(a,g)) return@post
            val stream=a.streams[source] ?: return@post
            a.telemetry.notification(value.size,value.firstOrNull()?.toInt()?.and(255),a.engine==null && a.custom==null)
            for(frame in stream.accept(value)) {
                if(!isCurrent(a,g)) break
                a.telemetry.frame(frame)
                val engine=a.engine
                if(a.custom!=null) a.custom?.accept(frame)
                else if(engine!=null) engine.accept(frame)
                else if(!a.inbox.offer(frame)) { fail(a,Failure.EARLY_NOTIFICATION_LIMIT);break }
            }
        } finally { value.fill(0) }
    } }
    private fun pump(a: Attempt) {
        if(current !== a || !ownsGallery() || a.setup!=Setup.READY || a.writing || SystemClock.elapsedRealtime()<a.nextWrite) return
        val bytes=a.writes.peekFirst()?.bytes ?: return
        val g=a.gatt ?: return;val ch=a.writer ?: return
        a.writing=true;a.writeDeadline=SystemClock.elapsedRealtime()+5000
        try {
            if(!write(g,ch,bytes,BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)) fail(a,Failure.WRITE_FAILED)
            else a.telemetry.issued()
        }
        catch(_: SecurityException) { fail(a,Failure.PERMISSION_REQUIRED) }
    }
    @android.annotation.SuppressLint("MissingPermission")
    private fun write(g: BluetoothGatt,ch: BluetoothGattCharacteristic,bytes: ByteArray,type: Int): Boolean =
        if(Build.VERSION.SDK_INT>=33) g.writeCharacteristic(ch,bytes,type)==BluetoothStatusCodes.SUCCESS
        else { ch.writeType=type;ch.value=bytes.copyOf();g.writeCharacteristic(ch) }
    private fun fail(a: Attempt,reason: Failure,protocol: GalleryBleHandshake.Failure?=null) {
        if(current !== a) return
        lastProtocolDiagnostic=a.engine?.diagnostic
        close();listener.failed(reason,protocol)
    }
    /** Idempotent; invalidates callbacks before closing Android resources. */
    fun close() {
        requireMain();val a=current;current=null
        if(a!=null) {
            lastTransportDiagnostic=a.telemetry.summary();a.inbox.clear()
            handler.removeCallbacks(a.tick);a.engine?.close();a.engine=null;a.custom?.close();a.custom=null
            a.streams.values.forEach { it.reset() };a.writes.forEach { it.bytes.fill(0);it.complete(false) };a.writes.clear()
            a.gatt?.let { deferredClose.add(it) };a.gatt=null
        }
        // Permission can be revoked between entry and cleanup. Retain the handle
        // for another close after access returns; never open more GATT handles over it.
        for(g in deferredClose.toList()) {
            try { g.disconnect() } catch(_: SecurityException) {}
            try { g.close();deferredClose.remove(g) } catch(_: SecurityException) {}
        }
    }
    companion object {
        private fun uuid(short: String)=UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
        private val SERVICE=uuid("fff0");private val FFF4=uuid("fff4");private val FFF5=uuid("fff5")
        private val CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
