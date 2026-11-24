@file:Suppress("DEPRECATION")
package app.cameraderie.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.*
import app.cameraderie.core.*
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.UUID

@SuppressLint("MissingPermission")
class DjiCameraClient(
    context: Context,
    private val log: (String) -> Unit,
    private val changed: () -> Unit,
    private val onControlLinkOpened: (Long) -> Unit,
    private val onControlLinkClosed: () -> Unit,
) {
    data class Found(val device: BluetoothDevice, val name: String, val rssi: Int)
    private val app = context.applicationContext
    private val adapter get() = app.getSystemService(BluetoothManager::class.java).adapter
    private val prefs = app.getSharedPreferences("dji",Context.MODE_PRIVATE)
    private val cameraMemory = RememberedCamera(object : CameraBookmarkStore {
        override fun read() = CameraBookmark.create(prefs.getString("saved_address",null),prefs.getString("saved_name",null))
        override fun migrationDone() = prefs.getBoolean("selection_migrated",false)
        override fun authorizedAddresses() = prefs.all.entries.filter { it.key.startsWith("paired_") && it.value == true }.map { it.key.removePrefix("paired_") }
        override fun save(camera: CameraBookmark?, migrated: Boolean): Boolean {
            val edit = prefs.edit().putBoolean("selection_migrated",migrated)
            if (camera == null) edit.remove("saved_address").remove("saved_name")
            else edit.putString("saved_address",camera.address).putString("saved_name",camera.name)
            return edit.commit()
        }
    })
    val savedCamera get() = cameraMemory.current
    internal val hasAuthorizedBookmark get() = savedCamera?.let { prefs.getBoolean("paired_${it.address}",false) } == true
    internal val hasOpenGatt get() = setup.connection != null
    var autoConnectSaved: Boolean
        get() = prefs.getBoolean("auto_connect_saved",true)
        set(value) { prefs.edit().putBoolean("auto_connect_saved",value).apply() }
    fun forgetSavedCamera() {
        check(cameraMemory.forget()) { "Could not clear saved camera; try again" }
        log("Saved camera shortcut cleared; camera pairing kept"); changed()
    }
    private val random = SecureRandom()
    private val handler = Handler(Looper.getMainLooper())
    private val wakeAdvertiser = DjiWakeAdvertiser(app,log)
    private val wakeAttempt = DjiWakeAttempt(SystemClock::elapsedRealtime,{
        try { connectSaved(preserveWake = true) }
        catch(e: Exception) { fail("Wake reconnect failed: ${e.message}") }
    },{ message -> link = message; log(message); changed() })
    val waking get() = wakeAttempt.active
    private val setup = DjiGattSetup<ControlConnection>()
    internal var controlLinkOpenedAtNanos = 0L
        private set
    private var scan: ScanCallback? = null
    private var scanGeneration = 0
    private val scanBackoff = BleScanBackoff()
    private var scheduledScan: Runnable? = null
    private val discovered = linkedMapOf<String,Found>()
    val cameras get() = discovered.values.toList()
    var link = "Disconnected"; private set
    var connected = false; private set
    var scanning = false; private set
    var verificationCode = 0; private set
    private var pairingFirst = true
    private var selectedAddress = ""
    private var selectedName = "Camera"
    private var setupDeadline = 0L
    private enum class WriteOrigin { LOCAL, REMOTE, GAMEPAD }
    private data class Write(
        val bytes: ByteArray, val done: (Boolean) -> Unit, val origin: WriteOrigin,
        val limit: WriteLimit?, var offset: Int = 0,
    )
    private var writeOrigin = WriteOrigin.LOCAL
    internal data class WriteLimit(val owner: Any, val deadlineMs: Long)
    private var writeLimit: WriteLimit? = null
    private val writes = ArrayDeque<Write>()
    private var writing = false
    private var writeDeadline = 0L
    private var nextWriteAt = 0L
    private val resumeWrites = Runnable { pump() }
    private val identity: Long = prefs.getLong("identity",0).takeIf { it != 0L } ?: (random.nextLong() and 0xFFFFFFFFL).coerceAtLeast(1).also { prefs.edit().putLong("identity",it).commit() }
    private val protocolAddress: ByteArray = prefs.getString("protocol_address",null)?.let(::hexBytes)
        ?: ByteArray(6).also { random.nextBytes(it); it[0] = ((it[0].toInt() or 2) and 0xFE).toByte(); prefs.edit().putString("protocol_address",it.hex()).commit() }
    val session = DjiSession(identity,protocolAddress,SystemClock::elapsedRealtime,::enqueue,log,changed) {
        prefs.edit().putBoolean("paired_$selectedAddress",true).putInt("code_$selectedAddress",verificationCode).apply()
        if (!cameraMemory.rememberAuthorized(selectedAddress,selectedName)) log("Camera authorized, but saving its shortcut failed")
        else log("Camera authorized and saved locally")
        link = "BLE connected; camera authorized"
    }
    private val framer = DjiFramer(log)

    fun scan() {
        check(!waking) { "Wait for the camera wake test to finish" }
        startScan()
    }
    private fun startScan(wakeAddress: String? = null) {
        stopScan()
        check(adapter?.isEnabled == true) { "Bluetooth is off" }
        if(wakeAddress == null) discovered.clear()
        val token = scanGeneration
        val requestedAt = SystemClock.elapsedRealtime()
        val durationMs = if(wakeAddress == null) 12000L else 4000L
        val expiresAt = requestedAt + durationMs
        scanning = true
        fun begin() {
            if(token != scanGeneration) return
            val now = SystemClock.elapsedRealtime()
            val delay = scanBackoff.delayMs(now)
            if(now + delay >= expiresAt) {
                scanning = false
                log(if(wakeAddress == null) "DJI scan cooling down; retry shortly" else "Wake scan skipped during Bluetooth scan cooldown; bounded reconnect remains available")
                changed(); return
            }
            if(delay > 0) {
                val pending = Runnable {
                    try { begin() }
                    catch(e: Exception) { log("Delayed DJI scan could not start: ${e.message}"); changed() }
                }
                scheduledScan = pending
                handler.postDelayed(pending,delay)
                log("DJI scan delayed ${delay}ms to preserve Bluetooth scan budget"); changed(); return
            }
            scheduledScan = null
            val scanner = adapter?.bluetoothLeScanner
            if(scanner == null) { stopScan(); log("DJI BLE scanner unavailable"); changed(); return }
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) { handler.post {
                    if(scan !== this || token != scanGeneration) return@post
                    scanBackoff.receivedResult()
                    if(wakeAddress != null) {
                        // An FFF0 beacon alone is not the saved camera's identity.
                        if(!result.device.address.equals(wakeAddress,true) || !result.isConnectable) return@post
                        log("Saved camera is advertising connectably during wake test")
                        stopScan()
                        if(!hasOpenGatt) wakeAttempt.savedCameraAdvertising()
                        else log("Keeping existing sleeping camera link during wake recovery")
                        changed(); return@post
                    }
                    val manufacturer = result.scanRecord?.getManufacturerSpecificData(0x08AA)
                    val name = result.scanRecord?.deviceName ?: result.device.name ?: "Camera"
                    val djiBeacon = manufacturer != null && manufacturer.size >= 3 && manufacturer[2] == 0xFA.toByte()
                    if(!djiBeacon && !name.startsWith("OsmoAction6",true)) return@post
                    if(discovered.size < 30 || discovered.containsKey(result.device.address)) {
                        discovered[result.device.address] = Found(result.device,name,result.rssi); changed()
                    }
                } }
                override fun onScanFailed(errorCode: Int) { handler.post {
                    if(scan === this) {
                        scanBackoff.failed(SystemClock.elapsedRealtime()); stopScan()
                        val reason = when(errorCode) {
                            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "application registration failed"
                            ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "scanning too frequently"
                            else -> "error $errorCode"
                        }
                        log("DJI scan failed ($reason); retries use scan backoff"); changed()
                    }
                } }
            }
            scan = cb
            // Count before calling Android: a synchronous failed registration also consumes an attempt.
            scanBackoff.started(now)
            try {
                val filters = if(wakeAddress == null) null else listOf(ScanFilter.Builder().setDeviceAddress(wakeAddress).build())
                scanner.startScan(filters,ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),cb)
            } catch(e: Exception) {
                scanBackoff.failed(SystemClock.elapsedRealtime()); stopScan(); throw e
            }
            handler.postDelayed({
                if(scan === cb) {
                    stopScan()
                    log(if(wakeAddress == null) "DJI scan finished (${discovered.size} candidates)" else "Bounded sleeping-camera scan finished")
                    changed()
                }
            },expiresAt-now)
            log(if(wakeAddress == null) "Scanning DJI cameras; select the intended camera" else "Scanning saved camera after wake advertisement")
            changed()
        }
        begin()
    }
    fun stopScan() {
        scanGeneration++
        scheduledScan?.let(handler::removeCallbacks); scheduledScan = null
        val previous = scan; scan = null; scanning = false
        previous?.let { adapter?.bluetoothLeScanner?.stopScan(it) }
    }
    fun connect(index: Int, forcePair: Boolean) {
        val found = cameras.getOrNull(index) ?: error("Scan and select a camera first")
        connectDevice(found.device,found.name,forcePair)
    }
    fun connectSaved(preserveWake: Boolean = false) {
        val camera = savedCamera ?: error("Choose a camera once to save it")
        check(adapter?.isEnabled == true) { "Enable Bluetooth to reconnect the camera" }
        val device = adapter.getRemoteDevice(camera.address)
        connectDevice(device,device.name?.takeIf { it.isNotBlank() } ?: camera.name,false,preserveWake)
    }
    fun wakeSavedCamera() {
        val camera = savedCamera ?: error("Choose and authorize a camera before waking it")
        check(prefs.getBoolean("paired_${camera.address}",false)) { "Authorize the selected camera before waking it" }
        check(adapter?.isEnabled == true) { "Enable Bluetooth before wake test" }
        check(!session.busy) { "Wait for the current camera command before wake test" }
        check(!(session.hasFreshStatus && session.camera?.power == 0)) { "Camera is already awake; wake test not needed" }
        stopScan()
        wakeAttempt.begin()
        // Keep the sleeping GATT link until the camera responds or the bounded reconnect begins.
        try {
            wakeAdvertiser.start(camera.address, wakeAttempt::advertisingStarted) { sent ->
                wakeAttempt.advertised(sent)
                if (wakeAttempt.state == DjiWakeAttempt.State.WAITING_RESPONSE && !hasOpenGatt) {
                    try { startScan(camera.address) }
                    catch (e: Exception) {
                        log("Wake scan could not start: ${e.message}; bounded reconnect remains available")
                    }
                }
            }
        }
        catch(e: Exception) { wakeAdvertiser.cancel(); stopScan(); wakeAttempt.fail("Wake startup failed: ${e.message}"); throw e }
        changed()
    }
    private fun connectDevice(
        device: BluetoothDevice,
        name: String,
        forcePair: Boolean,
        preserveWake: Boolean = false,
        retryLinkFailure: Boolean = !preserveWake,
    ) {
        check(adapter?.isEnabled == true) { "Bluetooth is off" }
        disconnect(preserveWake)
        selectedAddress = device.address
        selectedName = name
        pairingFirst = forcePair || !prefs.getBoolean("paired_$selectedAddress",false)
        verificationCode = if (pairingFirst) random.nextInt(9000)+1000 else prefs.getInt("code_$selectedAddress",1000)
        link = "Connecting to selected DJI camera"; setupDeadline = SystemClock.elapsedRealtime()+25000
        val connection = ControlConnection()
        controlLinkOpenedAtNanos = SystemClock.elapsedRealtimeNanos()
        setup.open(connection)
        onControlLinkOpened(controlLinkOpenedAtNanos)
        try {
            connection.start(device, retryLinkFailure)
        } catch (error: RuntimeException) {
            disconnect(preserveWake)
            throw error
        }
        changed()
    }

    internal fun remoteAction(gamepad: Boolean = false, limit: WriteLimit? = null, block: () -> Unit) {
        val previous = writeOrigin
        val previousLimit = writeLimit
        writeOrigin = if (gamepad) WriteOrigin.GAMEPAD else WriteOrigin.REMOTE
        writeLimit = limit
        try {
            block()
        } finally {
            writeOrigin = previous
            writeLimit = previousLimit
        }
    }

    internal fun discardUnsentRemoteActions(): Boolean {
        val first = writes.peekFirst()
        if (first != null && first.origin != WriteOrigin.LOCAL && (writing || first.offset > 0)) return false
        discardWrites(writes.filter { it.origin != WriteOrigin.LOCAL })
        return true
    }

    internal fun discardUnsentGamepadActions() {
        discardUnsentWrites { it.origin == WriteOrigin.GAMEPAD }
    }

    internal fun discardUnsentAction(owner: Any) {
        discardUnsentWrites { it.limit?.owner === owner }
    }

    private fun discardUnsentWrites(matches: (Write) -> Boolean) {
        val started = writes.peekFirst()?.takeIf { writing || it.offset > 0 }
        val discarded = writes.filter { it !== started && matches(it) }
        if (discarded.isNotEmpty()) discardWrites(discarded)
    }

    private fun discardWrites(discarded: List<Write>) {
        writes.removeAll(discarded.toSet())
        discarded.forEach { it.done(false) }
        pump()
    }

    private fun enqueue(bytes: ByteArray, done: (Boolean) -> Unit) {
        if (!setup.ready || writes.size >= 16) { done(false); return }
        writes.add(Write(bytes.copyOf(), done, writeOrigin, writeLimit)); pump()
    }
    private fun pump() {
        if (writing || writes.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val delay = nextWriteAt - now
        if(delay > 0) {
            handler.removeCallbacks(resumeWrites); handler.postDelayed(resumeWrites,delay); return
        }
        val connection = setup.connection ?: return
        val job = writes.first
        val limit = job.limit
        if (job.offset == 0 && limit != null && now >= limit.deadlineMs) {
            writes.removeFirst()
            job.done(false)
            log("Dropped unsent camera macro write after deadline")
            pump()
            return
        }
        val sleepFrame = job.bytes.size >= 14 && job.bytes[12] == 0.toByte() && job.bytes[13] == 0x1A.toByte()
        if(session.trafficSuspended && !sleepFrame) {
            writes.removeFirst(); job.done(false); log("Suppressed queued ordinary camera traffic while asleep"); pump(); return
        }
        val count = minOf(connection.chunkSize, job.bytes.size - job.offset)
        val data = job.bytes.copyOfRange(job.offset, job.offset + count)
        writing = true
        writeDeadline = SystemClock.elapsedRealtime() + 8000
        // Keep DJI's pacing in addition to Nordic's write completion.
        if (connection.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            nextWriteAt = SystemClock.elapsedRealtime() + 40
        }
        connection.send(data) {
            if (!setup.isCurrent(connection) || writes.peekFirst() !== job) return@send
            writing = false
            job.offset += count
            if (job.offset == job.bytes.size) {
                writes.removeFirst()
                job.done(true)
            }
            pump()
        }
    }

    private fun fail(reason: String) {
        val wakingNow = wakeAttempt.active
        log(reason); disconnect(preserveWake = wakingNow)
        if (wakingNow) {
            wakeAdvertiser.cancel()
            wakeAttempt.fail("Wake not confirmed: $reason. Power on camera and reconnect")
        } else link = "Error: $reason"
        changed()
    }
    private inner class ControlConnection : BleManager(app, handler), ConnectionObserver {
        private var notification: BluetoothGattCharacteristic? = null
        private var writer: BluetoothGattCharacteristic? = null

        val chunkSize get() = (mtu - 3).coerceIn(20, 512)
        var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            private set

        fun start(device: BluetoothDevice, retryLinkFailure: Boolean) {
            setConnectionObserver(this)
            if (GattConnectionDiagnostics.available()) {
                setConnectionParametersListener { _, interval, latency, supervisionTimeout ->
                    if (setup.isCurrent(this) && connected) {
                        GattConnectionDiagnostics.parameters(interval, latency, supervisionTimeout)?.let {
                            log("DJI control GATT parameters $it")
                        }
                    }
                }
            }
            log("DJI control GATT using Nordic BLE 2.11.0")
            connect(device)
                .useAutoConnect(false)
                .timeout(25_000)
                .done {
                    if (!setup.isCurrent(this)) return@done
                    if (!setup.markReady(this)) {
                        fail("DJI notifications were not enabled")
                        return@done
                    }
                    setupDeadline = 0
                    link = "BLE connected; authorize on camera"
                    session.begin(pairingFirst, verificationCode)
                    changed()
                }
                .fail { _, status ->
                    if (retryLinkFailure && status == GATT_ERROR && setup.isCurrent(this) && !connected) {
                        scheduleLinkRetry(device)
                    } else onLinkLost(status)
                }
                .invalid { if (setup.isCurrent(this)) fail("DJI GATT connection could not start") }
                .enqueue()
        }

        private fun scheduleLinkRetry(device: BluetoothDevice) {
            log("DJI connection failed with GATT error 133; retrying once before authorization")
            handler.postDelayed({
                if (!setup.isCurrent(this)) return@postDelayed
                if (wakeAttempt.active) {
                    onLinkLost(GATT_ERROR)
                    return@postDelayed
                }
                try {
                    connectDevice(device, selectedName, pairingFirst, retryLinkFailure = false)
                } catch (error: RuntimeException) {
                    fail("DJI reconnect failed: ${error.message}")
                }
            }, 800)
        }

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val service = gatt.getService(SERVICE)
            val notify = service?.getCharacteristic(NOTIFY) ?: return false
            val write = service.getCharacteristic(WRITE) ?: return false
            if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0 ||
                notify.getDescriptor(CCCD) == null
            ) return false
            val writeProperties = write.properties
            writeType = when {
                writeProperties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ->
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                writeProperties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ->
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else -> return false
            }
            notification = notify
            writer = write
            return true
        }

        override fun initialize() {
            val notify = notification ?: return
            if (!setup.beginSubscription(this)) return
            // Nordic discovers services first, then serializes MTU and CCCD requests.
            requestMtu(517)
                .with { _, size ->
                    if (setup.isCurrent(this)) log("DJI ATT MTU negotiated: $size, chunk payload $chunkSize")
                }
                .fail { _, status ->
                    if (setup.isCurrent(this)) log("DJI MTU request failed ($status); using chunk payload $chunkSize")
                }
                .enqueue()
            setNotificationCallback(notify).with { _, data ->
                data.value?.let { accept(this, it) }
            }
            enableNotifications(notify)
                .done { setup.subscriptionCompleted(this) }
                .fail { _, status -> if (setup.isCurrent(this)) fail("DJI CCCD rejected ($status)") }
                .invalid { if (setup.isCurrent(this)) fail("Cannot enable DJI notifications") }
                .enqueue()
        }

        override fun onServicesInvalidated() {
            notification = null
            writer = null
            handler.post {
                if (setup.isCurrent(this) && isConnected && setup.ready) fail("DJI GATT services changed; reconnect manually")
            }
        }

        fun send(data: ByteArray, completed: () -> Unit) {
            val characteristic = writer ?: run {
                fail("DJI write characteristic unavailable")
                return
            }
            writeCharacteristic(characteristic, data, writeType)
                .timeout(8000)
                .done { completed() }
                .fail { _, status -> onWriteFailed(status) }
                .invalid { if (setup.isCurrent(this)) fail("DJI GATT write could not start") }
                .enqueue()
        }

        fun release() {
            setConnectionObserver(null)
            try {
                cancelQueue()
                disconnect().enqueue()
            } finally {
                // Gallery waits for the ACL to settle before it opens its own GATT.
                close()
            }
        }

        override fun onDeviceConnecting(device: BluetoothDevice) = Unit

        override fun onDeviceConnected(device: BluetoothDevice) {
            if (!setup.isCurrent(this)) return
            connected = true
            link = "BLE connected; discovering protocol services"
            changed()
        }

        override fun onDeviceReady(device: BluetoothDevice) = Unit
        override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) = Unit
        override fun onDeviceDisconnecting(device: BluetoothDevice) = Unit
        override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) = onLinkLost(reason)

        private fun onWriteFailed(status: Int) {
            if (!setup.isCurrent(this)) return
            if (!isConnected) onLinkLost(status)
            else fail("DJI GATT write failed ($status)")
        }

        private fun onLinkLost(status: Int) {
            if (!setup.isCurrent(this)) return
            if (wakeAttempt.active && wakeAttempt.state != DjiWakeAttempt.State.RECONNECTING) {
                log("DJI link closed during wake test ($status); deferring to bounded reconnect")
                this@DjiCameraClient.disconnect(preserveWake = true)
                wakeAttempt.disconnected()
            } else fail("DJI disconnected ($status); reconnect manually, no commands replayed")
        }
    }

    private fun accept(connection: ControlConnection, bytes: ByteArray) {
        if (!setup.isCurrent(connection) || !setup.ready) return
        framer.accept(bytes).forEach { frame ->
            if (frame.set != 0x1D || frame.id !in setOf(2,6)) log("DJI RX set=%02X id=%02X seq=%d response=%s".format(frame.set,frame.id,frame.sequence,frame.response))
            session.receive(frame)
            if(frame.set == 0x1D && frame.id == 2 && !frame.response) {
                if (wakeAttempt.observeStatus(session.auth == DjiSession.Auth.AUTHORIZED, session.hasFreshStatus, session.camera?.power == 0)) {
                    wakeAdvertiser.cancel()
                    stopScan()
                }
            }
        }
        if (session.auth == DjiSession.Auth.FAILED) fail("Protocol authentication failed; reconnect manually")
    }
    fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (setupDeadline != 0L && now >= setupDeadline) { fail("DJI connection/setup timeout"); return }
        if (writing && now >= writeDeadline) { fail("DJI GATT operation timeout"); return }
        wakeAttempt.tick()
        session.tick()
        if (session.auth == DjiSession.Auth.FAILED) fail("DJI authorization timeout/failure")
    }
    fun disconnect(preserveWake: Boolean = false) {
        if(!preserveWake) { wakeAdvertiser.cancel(); wakeAttempt.cancel() }
        val old = setup.close()
        if (old != null) onControlLinkClosed()
        stopScan(); setupDeadline = 0
        handler.removeCallbacks(resumeWrites); nextWriteAt = 0
        session.disconnect(); framer.reset(); writing = false
        val failed = writes.toList(); writes.clear()
        connected = false
        try { old?.release() } finally { failed.forEach { it.done(false) } }
        link = "Disconnected"; changed()
    }
    companion object {
        private const val GATT_ERROR = 133
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val SERVICE: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val NOTIFY: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
        val WRITE: UUID = UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb")
    }
}
