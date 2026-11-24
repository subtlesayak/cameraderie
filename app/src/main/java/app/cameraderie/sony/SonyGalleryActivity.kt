package app.cameraderie.sony

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.net.*
import android.net.wifi.WifiNetworkSpecifier
import android.os.*
import android.provider.DocumentsContract
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.*
import app.cameraderie.ui.CameraderieActivity
import app.cameraderie.ui.UiKit
import app.cameraderie.R
import app.cameraderie.camera.UsbCameraTransfer
import app.cameraderie.gallery.media.AlbumMediaFile
import app.cameraderie.gallery.net.CameraWifiAccess
import app.cameraderie.gallery.media.FolderImportOutput
import app.cameraderie.gallery.media.LocalFolderPicker
import app.cameraderie.gallery.media.MediaImportTransaction
import app.cameraderie.gallery.media.history.ImportHistoryDraft
import app.cameraderie.gallery.media.history.ImportHistoryGate
import app.cameraderie.gallery.media.history.ImportHistoryRepository
import app.cameraderie.gallery.media.history.ImportProvenance
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.Closeable
import java.io.OutputStream
import java.net.*
import java.util.concurrent.Executors

/** Explicit read-only camera media session. No capture, remote commands or camera deletion. */
class SonyGalleryActivity : CameraderieActivity() {
    companion object {
        private const val CONNECT_ATTACHED_USB = "connect-attached-usb"
        private const val USB_ONLY = "usb-only"

        internal fun attachedUsbCamera(context: Context): UsbDevice? =
            context.getSystemService(UsbManager::class.java)?.deviceList?.values?.firstOrNull { it.vendorId == 0x054c }

        internal fun connectionIntent(context: Context, useUsb: Boolean): Intent =
            Intent(context, SonyGalleryActivity::class.java).putExtra(CONNECT_ATTACHED_USB, useUsb)

        internal fun usbConnectionIntent(context: Context): Intent =
            connectionIntent(context, useUsb = true).putExtra(USB_ONLY, true)
    }

    private val usbOnly get() = intent.getBooleanExtra(USB_ONLY, false)

    private lateinit var ui: UiKit
    private lateinit var status: TextView
    private var backgroundLease:String?=null
    private var lastNotice:String?=null
    private var sessionStatus:String
        get()=status.text.toString()
        set(value) { status.text=value;sessionNotice(value) }
    private fun sessionNotice(text:String,percent:Int?=null) {
        val lease=backgroundLease ?: return
        val fingerprint="$text|$percent"
        if(lastNotice==fingerprint) return
        lastNotice=fingerprint
        runCatching { SonyGalleryKeepAlive.update(this,lease,text,percent) }
    }
    private fun beginBackgroundSession():Boolean {
        return try {
            backgroundLease=SonyGalleryKeepAlive.begin(this) { disconnect("Disconnected from the camera notification.") }
            lastNotice=null;true
        } catch(_:RuntimeException) {
            disconnect("Android could not keep this camera session active. Return to Cameraderie and retry the connection.");false
        }
    }
    private lateinit var browser: SonyMediaBrowser
    private lateinit var setupScroll: ScrollView
    private lateinit var connectionOptions: android.view.MenuItem
    private lateinit var cancelGallery: android.view.MenuItem
    private lateinit var refreshLibrary:android.view.MenuItem
    private lateinit var dateMenu:android.view.MenuItem
    private lateinit var layoutMenu:android.view.MenuItem
    private lateinit var clearDateMenu:android.view.MenuItem
    private lateinit var cameraContext: TextView
    private val visibleItems=linkedMapOf<String,Item>()
    private var showingSetup=false
    private var manualWifiExpanded=false
    private var readAllForSessions=false
    private var folderRequest:Pair<Int,List<Item>>?=null
    private val folderPicker=registerForActivityResult(LocalFolderPicker()) { folder ->
        val request=folderRequest.also { folderRequest=null }
        if(folder==null) return@registerForActivityResult
        if(request!=null && live(request.first) && !busy &&
            request.second.all { visibleItems[it.id] === it }) {
            try {
                LocalFolderPicker.persist(contentResolver,folder)
                importItems(request.second,folder=folder)
            } catch (_: RuntimeException) {
                sessionStatus="Choose a writable device folder to import the selected files."
            }
        } else {
            sessionStatus="The camera selection ended. Reconnect if needed, then select the files and a folder again."
        }
    }
    private val importedLocal=linkedMapOf<String,Uri>()
    private var foreground=false
    private lateinit var importIndicator:com.google.android.material.progressindicator.LinearProgressIndicator
    private lateinit var importPercent:TextView
    private lateinit var savedCameraList:LinearLayout
    private lateinit var savedCameraCard:LinearLayout
    private val bookmarks by lazy { SonyCameraBookmarks(this) }
    private var pendingWifiBookmark:WifiBookmark?=null
    private var cameraScope=java.util.UUID.randomUUID().toString()
    private var cameraScopeKind="SESSION"
    private class WifiBookmark(val ssid:String,val password:String) { override fun toString()="WifiBookmark(<redacted>)" }
    private lateinit var shareReady:Button
    private var readyToShare=emptyList<Uri>()
    private lateinit var ssid: EditText
    private lateinit var password: EditText
    private lateinit var connect: Button
    private lateinit var qr: Button
    private var scanGeneration: Int?=null
    private data class WifiRequest(val generation: Int, val expectedCamera: String?)
    private var pendingWifiPermission: WifiRequest? = null
    private var pendingWifiEnable: WifiRequest? = null
    private val wifiPanel=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        val request = pendingWifiEnable
        pendingWifiEnable = null
        if (request != null && request.generation == generation && !busy && session == null && !isFinishing) {
            if (getSystemService(android.net.wifi.WifiManager::class.java).isWifiEnabled) join(request.expectedCamera)
            else sessionStatus="Wi-Fi is off. Turn it on, then scan the camera QR or tap Connect."
        }
    }
    private val qrScanner=registerForActivityResult(ScanContract()) { result ->
        val token=scanGeneration;scanGeneration=null
        if(token!=generation || busy || isFinishing) return@registerForActivityResult
        val raw=result.contents
        if(raw==null) { status.setText(R.string.qr_scan_cancelled);return@registerForActivityResult }
        val credentials=SonyWifiQr.parse(raw)
        if(credentials==null) { status.setText(R.string.qr_unsupported);return@registerForActivityResult }
        ssid.setText(credentials.ssid);password.setText(credentials.password)
        status.setText(R.string.qr_ready)
        join(credentials.cameraName)
    }
    private fun scanCameraQr() {
        if(busy || session!=null) { status.setText(R.string.qr_disconnect_first);return }
        scanGeneration=generation
        qrScanner.launch(ScanOptions().setCaptureActivity(SonyQrCaptureActivity::class.java).setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(getString(R.string.qr_prompt)).setBeepEnabled(false)
            .setBarcodeImageEnabled(false).setOrientationLocked(false).setTimeout(60000))
    }
    private lateinit var usb: Button

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r,"Sony-media").apply { isDaemon=true } }
    @Volatile private var generation = 0
    @Volatile private var session: Session? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val sockets = java.util.Collections.synchronizedList(mutableListOf<Socket>())
    private val openingLock=Any()
    private var openingHttp:SonyHttpGallery?=null
    private var handles = emptyList<Long>()
    private var offset = 0
    private var container="0"
    private val folders=ArrayDeque<Pair<String,String>>()
    private var folderName="Camera library"
    private var busy = false
    private var receiverRegistered = false
    private var usbRequest:Pair<Int,Int>? = null
    private val manager get() = getSystemService(ConnectivityManager::class.java)
    private val usbManager get() = getSystemService(UsbManager::class.java)
    private val usbAction get() = "$packageName.SONY_USB_PERMISSION"
    @Volatile private var connectionAttempt:SonyConnectionAttempt?=null
    private var connectionWatchdog:Runnable?=null
    private fun connectionTimeout(stage:SonyConnectionAttempt.Stage)=when(stage) {
        SonyConnectionAttempt.Stage.WIFI -> "Camera Wi-Fi request timed out. Keep Select on Smartphone open, then reconnect the saved camera."
        SonyConnectionAttempt.Stage.DISCOVERY -> "Camera Wi-Fi joined, but media discovery timed out. Keep transfer mode open, close other camera apps and retry."
        SonyConnectionAttempt.Stage.PTP -> "Camera Wi-Fi joined, but the gallery service did not open before its deadline. Keep transfer mode open and retry."
        SonyConnectionAttempt.Stage.METADATA -> "The camera's initial file metadata timed out. Keep transfer mode open and reconnect to retry."
    }
    private fun armConnectionWatchdog(token:Int,attempt:SonyConnectionAttempt) {
        if(!live(token) || connectionAttempt!==attempt) return
        var watch=connectionWatchdog
        if(watch==null) {
            watch=object:Runnable {
                override fun run() {
                    if(!live(token) || connectionAttempt!==attempt || connectionWatchdog!==this) return
                    val current=attempt.snapshot()
                    if(current.state==SonyConnectionAttempt.State.TIMED_OUT) disconnect(connectionTimeout(checkNotNull(current.failedStage)))
                    else if(current.state==SonyConnectionAttempt.State.ACTIVE) main.postDelayed(this,current.remainingMs.coerceAtLeast(1))
                }
            }
            connectionWatchdog=watch
        }
        main.removeCallbacks(watch)
        val current=attempt.snapshot()
        if(current.state==SonyConnectionAttempt.State.TIMED_OUT) disconnect(connectionTimeout(checkNotNull(current.failedStage)))
        else if(current.state==SonyConnectionAttempt.State.ACTIVE) main.postDelayed(watch,current.remainingMs.coerceAtLeast(1))
    }
    private fun advanceConnection(token:Int,attempt:SonyConnectionAttempt,stage:SonyConnectionAttempt.Stage):Boolean {
        if(!live(token) || connectionAttempt!==attempt) return false
        if(!attempt.enter(stage)) {
            post(token) { if(connectionAttempt===attempt) disconnect(connectionTimeout(attempt.timeout() ?: attempt.stage)) }
            return false
        }
        post(token) {
            if(connectionAttempt===attempt && attempt.stage==stage) {
                sessionStatus=when(stage) {
                    SonyConnectionAttempt.Stage.WIFI -> "Requesting camera Wi-Fi…"
                    SonyConnectionAttempt.Stage.DISCOVERY -> "Camera Wi-Fi joined · Finding the media service…"
                    SonyConnectionAttempt.Stage.PTP -> "Opening the camera's PTP gallery service…"
                    SonyConnectionAttempt.Stage.METADATA -> "Reading initial camera file metadata…"
                }
                armConnectionWatchdog(token,attempt)
            }
        }
        return true
    }
    private fun finishConnection(token:Int,attempt:SonyConnectionAttempt):Boolean {
        if(!live(token) || connectionAttempt!==attempt) return false
        if(!attempt.complete()) { disconnect(connectionTimeout(attempt.timeout() ?: attempt.stage));return false }
        connectionWatchdog?.let { main.removeCallbacks(it) };connectionWatchdog=null;connectionAttempt=null
        return true
    }

    private data class Item(val handle:Long,val size:Long,val name:String,val folder:Boolean,val id:String=handle.toString(),val downloadable:Boolean=true,val capture:CameraCaptureTime?=null,val video:Boolean=name.substringAfterLast('.',"").lowercase() in setOf("mp4","mov","mts","m2ts")) {
        val raw get()=name.endsWith(".arw",true)
    }
    private interface Session : Closeable {
        val model:String
        val usable:Boolean get()=true
        val needsFolderStaging:Boolean get()=false
        val originalsOnly:Boolean get()=false
        val hierarchical:Boolean get()=false
        fun page(container:String,start:Int,count:Int):List<Item> = error("Flat gallery")
        fun totalListed():Int?=null
        fun updateListed():String?=null
        fun handles():List<Long>
        fun item(handle:Long):Item
        fun preview(item:Item,detail:Boolean=false):ByteArray?=null
        fun copy(item:Item,uri:Uri,token:Int):Transfer
        fun resources(item:Item):List<SonyImportSizePolicy.Resource> {
            val mime=runCatching { SonyImportPlan.file(item.name).mime }.getOrNull() ?: return emptyList()
            return listOf(SonyImportSizePolicy.Resource(item.id,item.name,mime,item.size))
        }
        fun copyResource(item:Item,resource:SonyImportSizePolicy.Resource,uri:Uri,token:Int):Transfer {
            check(resource.id==item.id && resource.bytes==item.size)
            return copy(item,uri,token)
        }
    }
    private data class Transfer(val bytes:Long,val sourceSha256:String?=null)
    private fun copyToDestination(uri:Uri,copy:(OutputStream)->Long):Transfer {
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        val bytes=contentResolver.openOutputStream(uri,"w")!!.use { destination ->
            java.security.DigestOutputStream(destination,digest).use { copy(it) }
        }
        return Transfer(bytes,digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        ui=UiKit(this)
        val root=ui.column().apply { setBackgroundColor(ui.tone.background);setPadding(ui.dp(16),0,ui.dp(16),0) }
        root.setOnApplyWindowInsetsListener { view,insets ->
            val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left+ui.dp(16),bars.top,bars.right+ui.dp(16),bars.bottom); WindowInsets.CONSUMED
        }
        val toolbar=com.google.android.material.appbar.MaterialToolbar(this).apply {
            title=if(usbOnly) "USB camera" else "Camera gallery";setTitleTextColor(ui.tone.text)
            setNavigationIcon(R.drawable.ic_arrow_back);navigationContentDescription="Back to Cameraderie"
            setNavigationOnClickListener { finish() }
        }
        ui.add(root,toolbar)
        refreshLibrary=toolbar.menu.add("Refresh library").apply {
            setIcon(R.drawable.ic_refresh);iconTintList=android.content.res.ColorStateList.valueOf(ui.tone.text)
            contentDescription="Refresh camera library";setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { if(session!=null && !showingSetup && !busy)loadCatalogue(refreshHandles=true);true }
        }
        dateMenu=toolbar.menu.add("Date & shoot").apply { setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);setOnMenuItemClickListener { browser.pickSession();true } }
        layoutMenu=toolbar.menu.add("Layout").apply { setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);setOnMenuItemClickListener { browser.pickLayout();true } }
        clearDateMenu=toolbar.menu.add("Clear date/shoot filter").apply { setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);setOnMenuItemClickListener { browser.clearDateFilter();updateGalleryMenu();true } }
        cancelGallery=toolbar.menu.add("Cancel").apply {
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);isVisible=false
            setOnMenuItemClickListener { disconnect("Disconnected. Camera files are unchanged.");true }
        }
        connectionOptions=toolbar.menu.add("Connection options").apply {
            setOnMenuItemClickListener { if(session!=null) { showingSetup=!showingSetup;updatePresentation() };true }
        }
        if(!usbOnly) toolbar.menu.add("Import settings").setOnMenuItemClickListener { SonyImportPreferences(this).show(this);true }
        toolbar.menu.add("Disconnect").setOnMenuItemClickListener { disconnect("Disconnected. Camera files are unchanged.");true }
        cameraContext=ui.text("Camera · Not connected",UiKit.Type.small,secondary=true);ui.add(root,cameraContext,4)
        status=ui.text("Connect to browse camera media",UiKit.Type.small,secondary=true).apply { accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        ui.add(root,status,8)
        importIndicator=com.google.android.material.progressindicator.LinearProgressIndicator(this).apply { max=100;visibility=View.GONE;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_NONE }
        ui.add(root,importIndicator,8)
        importPercent=ui.text("",UiKit.Type.small,secondary=true).apply { visibility=View.GONE;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_NONE }
        ui.add(root,importPercent,4)
        shareReady=ui.action("Share imported files") { if(readyToShare.isNotEmpty()) showShare(readyToShare) }.apply { visibility=View.GONE }
        ui.add(root,shareReady,4)
        setupScroll=ScrollView(this).apply { isFillViewport=false }
        val body=ui.column();setupScroll.addView(body)
        root.addView(setupScroll,LinearLayout.LayoutParams(-1,0,1f))
        savedCameraCard=ui.card(body,"Saved cameras")
        savedCameraList=ui.column();ui.add(savedCameraCard,savedCameraList)
        (savedCameraCard.parent as View).visibility=View.GONE
        val setup=ui.card(body,"Connect camera")
        qr=ui.action(getString(R.string.qr_scan_button), primary = true) { scanCameraQr() }
        usb=ui.action("Connect over USB") { connectUsb() }
        if(usbOnly) ui.add(setup,usb) else ui.row(setup,qr,usb)
        val manual = ui.column()
        manualWifiExpanded = state?.getBoolean("manualWifiExpanded") == true
        fun Button.updateDisclosure() {
            text = if (manualWifiExpanded) "Hide Wi-Fi details" else "Enter Wi-Fi details"
            stateDescription = if (manualWifiExpanded) "Expanded" else "Collapsed"
            manual.visibility = if (manualWifiExpanded) View.VISIBLE else View.GONE
        }
        val showManual = ui.action("") {}.apply {
            updateDisclosure()
            setOnClickListener { manualWifiExpanded = !manualWifiExpanded; updateDisclosure() }
        }
        if(!usbOnly) {
            ui.add(setup,showManual,8)
            ui.add(setup,manual,8)
        }
        ui.add(manual,ui.text("On the camera, open Network → Send to Smartphone → Select on Smartphone. Keep that screen open and close Imaging Edge.",secondary=true))
        ssid=field(manual,"Camera Wi-Fi name",false)
        password=field(manual,"Camera Wi-Fi password",true)
        connect=ui.action("Connect over Wi-Fi",true) { join() }; ui.add(manual,connect,16)
        val help = if(usbOnly) "Use a USB data cable and choose PTP, MTP, or file transfer on your camera. For USB storage, use Import from files or a memory card."
            else "Available file formats depend on the camera and connection. Wi-Fi transfer may offer JPEG copies of RAW photos."
        ui.add(setup,ui.text(help,UiKit.Type.small,secondary=true),8)
        ui.add(body,ui.text("Choose the phone album or a folder when importing. Home or switching apps keeps this session and imports active. Use the notification to return or disconnect. Back to Cameraderie closes the connection and removes the current incomplete import.",UiKit.Type.small,secondary=true),16)
        browser=SonyMediaBrowser(this,ui,{ key -> visibleItems[key]?.let { inspect(it) } },
            { keys -> saveBatch(keys) },{ keys -> shareItems(keys.mapNotNull { visibleItems[it] }) },
            { keys -> importItems(keys.mapNotNull { visibleItems[it] },chooseQuality=true) },onFilterChanged={ updateGalleryMenu() },
            folderSelected={ keys -> chooseImportFolder(keys.mapNotNull { visibleItems[it] }) },showQualityOptions=!usbOnly)
        root.addView(browser,LinearLayout.LayoutParams(-1,0,1f))
        if(state?.getBoolean("hadCameraSession")==true) {
            sessionStatus="The camera session ended. Reconnect to continue browsing or importing."
        }
        updatePresentation()
        setContentView(root)
        val cleanupToken=generation
        worker.execute { runCatching { SonyMediaStore(this).cleanupInterrupted { !live(cleanupToken) || Thread.currentThread().isInterrupted } } };refreshSavedCameras()
        val filter=IntentFilter(usbAction)
        if(Build.VERSION.SDK_INT>=33) registerReceiver(usbPermission,filter,Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(usbPermission,filter)
        receiverRegistered=true
    }
    private fun refreshSavedCameras() {
        if(usbOnly) return
        val token=generation
        worker.execute {
            val saved=runCatching { bookmarks.list() }
            post(token) {
                savedCameraList.removeAllViews()
                val cameras=saved.getOrNull()
                (savedCameraCard.parent as View).visibility=if(cameras?.isEmpty()==true) View.GONE else View.VISIBLE
                if(cameras==null) {
                    ui.add(savedCameraList,ui.text("Saved Wi-Fi details could not be read. Scan the camera QR to connect; no plaintext fallback is used.",UiKit.Type.small,secondary=true));return@post
                }
                for(camera in cameras) {
                    ui.add(savedCameraList,ui.text("${camera.model} · ${camera.ssid}",bold=true),8)
                    ui.row(savedCameraList,ui.action("Connect saved camera",true) {
                        if(!busy && session==null) { ssid.setText(camera.ssid);password.setText(camera.password);join(camera.model) }
                    },ui.action("Forget saved Wi-Fi") {
                        if(!busy && session==null) com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle("Forget this camera’s saved Wi-Fi?").setMessage("This removes its remembered Wi-Fi details from this phone. Bluetooth pairing is separate.")
                            .setNegativeButton("Cancel",null).setPositiveButton("Forget") { _,_ ->
                                worker.execute { runCatching { bookmarks.forget(camera.id) };post(token) { refreshSavedCameras() } }
                            }.create().also { SonyScreenWindows.showPrivate(it) }
                    })
                }
                ui.add(savedCameraList,ui.text("Remembered on this phone with Android Keystore encryption. Android may still ask to approve the camera network.",UiKit.Type.small,secondary=true),8)
                updateSavedControls(savedCameraList)
            }
        }
    }
    private fun updateSavedControls(view:View) {
        if(view is Button) view.isEnabled=!busy && session==null
        if(view is android.view.ViewGroup) for(i in 0 until view.childCount) updateSavedControls(view.getChildAt(i))
    }
    private fun field(parent:LinearLayout,label:String,secret:Boolean):EditText {
        val input=EditText(this).apply {
            id=View.generateViewId();textSize=16f;minHeight=ui.dp(56);setSingleLine(true)
            inputType=if(secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSaveEnabled=false;importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        ui.add(parent,ui.text(label).apply { labelFor=input.id },16);ui.add(parent,input,4)
        return input
    }
    private fun setBusy(value:Boolean) {
        busy=value;if(::cancelGallery.isInitialized) cancelGallery.isVisible=value
        connect.isEnabled=!value && session==null;usb.isEnabled=!value && session==null
        qr.isEnabled=!value && session==null
        if(::browser.isInitialized) browser.setBusy(value)
        if(::importIndicator.isInitialized) {
            importIndicator.visibility=if(value) View.VISIBLE else View.GONE
            importPercent.visibility=if(value) View.VISIBLE else View.GONE
            if(value) { importIndicator.isIndeterminate=true;importPercent.text="Working…" }
        }
        if(::savedCameraList.isInitialized) updateSavedControls(savedCameraList)
        updateGalleryMenu()
    }
    private fun updateGalleryMenu() {
        if(!::refreshLibrary.isInitialized)return
        val library=session!=null && !showingSetup
        refreshLibrary.isVisible=library;refreshLibrary.isEnabled=library && !busy
        val browse=library && ::browser.isInitialized && browser.browsingControlsEnabled
        dateMenu.isVisible=library;dateMenu.isEnabled=browse
        layoutMenu.isVisible=library;layoutMenu.isEnabled=browse
        clearDateMenu.isVisible=library && ::browser.isInitialized && browser.hasDateFilter;clearDateMenu.isEnabled=browse
    }
    private fun updatePresentation() {
        val connected=session!=null
        setupScroll.visibility=if(!connected || showingSetup) View.VISIBLE else View.GONE
        browser.visibility=if(connected && !showingSetup) View.VISIBLE else View.GONE
        connectionOptions.isEnabled=connected
        connectionOptions.title=if(showingSetup) "Return to library" else "Connection options"
        cameraContext.text=if(connected) "${session?.model} · Connected" else "Camera · Not connected"
        val secure=SonyScreenProtection.required(SonyImportPreferences(this).allowGalleryScreenshots,connected && !showingSetup)
        SonyScreenWindows.apply(window,secure);updateGalleryMenu()
    }
    private fun clearItems() { readAllForSessions=false;visibleItems.clear();browser.submit(emptyList(),folderName,folders.isNotEmpty(),false) }
    private fun inspect(item:Item) {
        if(busy || session==null || item.folder) return
        browser.details(entry(item),{ save(item) },{ shareItems(listOf(item)) },{ importItems(listOf(item),chooseQuality=true) },
            folder={ chooseImportFolder(listOf(item)) })
    }
    private fun entry(item:Item):SonyMediaBrowser.Entry {
        val current=session;val token=generation
        return SonyMediaBrowser.Entry(item.id,item.name,item.size,item.folder,item.downloadable,
            preview={ if(current==null || !live(token)) null else current.preview(item).takeIf { live(token) } },
            detailPreview={ if(current==null || !live(token)) null else current.preview(item,true).takeIf { live(token) } },capture=item.capture,video=item.video)
    }
    private fun live(token:Int)=generation==token && !isDestroyed
    private fun post(token:Int,block:()->Unit) { main.post { if(live(token)) block() } }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode!=10) return
        val request = pendingWifiPermission
        pendingWifiPermission = null
        if (request == null || request.generation != generation || isFinishing || isDestroyed || busy || session != null) return
        val missing = CameraWifiAccess.missing(this)
        if (missing.isEmpty()) join(request.expectedCamera)
        else {
            sessionStatus = "Camera Wi-Fi access is off. Allow access in Settings, then connect again."
            CameraWifiAccess.showDenied(this, missing)
        }
    }
    private fun join(expectedCamera:String?=null) {
        val name=ssid.text.toString();val pass=password.text.toString()
        val expectedModel=expectedCamera ?: SonyCameraIdentity.expectedFromSsid(name)
        if(!name.startsWith("DIRECT-",true)) { ssid.error="Enter the DIRECT- name shown by the camera";return }
        if(pass.length !in 8..63) { password.error="Enter the camera’s Wi-Fi password";return }
        val spec=try { WifiNetworkSpecifier.Builder().setSsid(name).setWpa2Passphrase(pass).build() }
            catch(_:IllegalArgumentException) { sessionStatus="Check the camera Wi-Fi name and password.";return }
        val missing = CameraWifiAccess.missing(this)
        if (missing.isNotEmpty()) {
            pendingWifiPermission = WifiRequest(generation, expectedModel)
            CameraWifiAccess.request(this, missing, 10)
            return
        }
        if(!getSystemService(android.net.wifi.WifiManager::class.java).isWifiEnabled) {
            pendingWifiEnable = WifiRequest(generation, expectedModel)
            sessionStatus="Turn on Wi-Fi to join the camera. Connection will continue when you return."
            wifiPanel.launch(Intent(android.provider.Settings.Panel.ACTION_WIFI));return
        }
        disconnect("Requesting camera Wi-Fi…");pendingWifiBookmark=WifiBookmark(name,pass);password.text.clear();setBusy(true)
        val token=generation
        if(!beginBackgroundSession()) return
        val attempt=SonyConnectionAttempt { SystemClock.elapsedRealtime() };connectionAttempt=attempt
        armConnectionWatchdog(token,attempt)
        var started=false
        val callback=object:ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network:Network,properties:LinkProperties) {
                if(!live(token) || started) return
                val gateway=properties.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway ?: return
                if(!gateway.isSiteLocalAddress) { disconnect("Camera network has no private IPv4 gateway.");return }
                if(!advanceConnection(token,attempt,SonyConnectionAttempt.Stage.DISCOVERY)) return
                started=true
                worker.execute {
                    var mediaDiscovery=""
                    try {
                        val http=SonyHttpGallery(network,gateway,expectedModel)
                        synchronized(openingLock) {
                            if(!live(token)) { http.close();return@execute }
                            openingHttp=http
                        }
                        try {
                            http.open { description ->
                                // Bounded local debugging only; excluded from bridge log export.
                                if(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0) runCatching {
                                    val folder=java.io.File(filesDir,"private-camera-diagnostics").apply { mkdirs() }
                                    java.io.File(folder,"sony-description.xml").writeText(description,Charsets.UTF_8)
                                }
                            }
                            val httpItems=java.util.concurrent.ConcurrentHashMap<String,SonyHttpItem>()
                            val httpSession=object:Session {
                                override val model get()=http.model
                                override val hierarchical=true
                                override fun handles()=emptyList<Long>()
                                override fun item(handle:Long):Item=error("HTTP gallery uses content IDs")
                                override fun page(container:String,start:Int,count:Int):List<Item> = http.list(container,start,count).map { obj ->
                                    require(httpItems.size<100000 || httpItems.containsKey(obj.id)) { "Camera listing limit reached" }
                                    httpItems[obj.id]=obj
                                    val fallback=obj.importResources.firstOrNull { it.jpeg && !it.thumbnail && it.bytes>0 }
                                    val size=if(obj.size>0) obj.size else fallback?.bytes ?: -1
                                    val name=if(obj.url!=null) obj.name else fallback?.name ?: obj.name
                                    Item(0,size,name,obj.folder,obj.id,
                                        (obj.url!=null || fallback!=null) && SonyImportPlan.importable(name,size,obj.importResources),CameraCaptureTime.parse(obj.captureDate),obj.importResources.any { it.mime.startsWith("video/") })
                                }
                                override fun totalListed()=http.totalMatches.takeIf { it>=0 }
                                override fun updateListed()=http.updateId
                                override fun resources(item:Item)=checkNotNull(httpItems[item.id]).importResources
                                override fun copyResource(item:Item,resource:SonyImportSizePolicy.Resource,uri:Uri,token:Int):Transfer = copyToDestination(uri) { output ->
                                    val obj=checkNotNull(httpItems[item.id]);check(resource in obj.importResources)
                                    http.download(obj.copy(name=resource.name,size=resource.bytes,url=resource.id),output,{!live(token)}) { bytes -> progress(token,bytes,resource.bytes) }
                                }
                                override fun preview(item:Item,detail:Boolean)=http.preview(checkNotNull(httpItems[item.id]),detail)
                                override fun copy(item:Item,uri:Uri,token:Int):Transfer = copyToDestination(uri) { output ->
                                    http.download(checkNotNull(httpItems[item.id]),output,{ !live(token) }) { bytes -> progress(token,bytes,item.size) }
                                }
                                override fun close()=http.close()
                            }
                            connected(httpSession,token)
                            return@execute
                        } catch(error:Exception) {
                            mediaDiscovery="${http.diagnostic()} (${safeReason(error)})"
                            http.close();synchronized(openingLock) { if(openingHttp===http) openingHttp=null }
                            if(!live(token)) return@execute
                        }
                        if(!advanceConnection(token,attempt,SonyConnectionAttempt.Stage.PTP)) return@execute
                        fun socket():Socket {
                            val result=Socket();sockets.add(result)
                            if(!live(token)) { result.close();throw java.io.IOException("Cancelled") }
                            network.bindSocket(result);result.connect(InetSocketAddress(gateway,15740),8000);result.soTimeout=15000
                            return result
                        }
                        val command=socket();val events=socket()
                        val client=SonyPtpIp(command,events,expectedModel);client.open()
                        val connected=object:Session {
                            override val model get()=client.model
                            override val usable get()=client.isOpen
                            override fun handles()=client.storageIds().flatMap { client.handles(it) }.distinct()
                            override fun item(handle:Long)=client.objectInfo(handle).let { Item(it.handle,it.size,it.name,it.isFolder,capture=CameraCaptureTime.parse(it.captureDate)) }
                            override fun copy(item:Item,uri:Uri,token:Int):Transfer = copyToDestination(uri) { output ->
                                client.download(item.handle,output,item.size,{ !live(token) }) { bytes -> progress(token,bytes,item.size) }
                            }
                            override fun close()=client.close()
                        }
                        connected(connected,token)
                    } catch(error:Exception) { post(token) { disconnect("Camera Wi-Fi joined, but the gallery service did not open. $mediaDiscovery; PTP=${safeReason(error)}. Keep Select on Smartphone open, close other camera apps, and retry.") } }
                }
            }
            override fun onUnavailable() { if(live(token)) disconnect("Camera Wi-Fi was not available. Check the camera and retry.") }
            override fun onLost(network:Network) { if(live(token)) disconnect("Camera Wi-Fi disconnected. Reconnect to browse again.") }
        }
        networkCallback=callback
        try {
            manager.requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec).build(),callback,main,45000)
        } catch(_:Exception) { disconnect("Could not request camera Wi-Fi. Check Wi-Fi and Nearby devices access.") }
    }
    private fun connected(value:Session,token:Int) {
        // Publication is serialized with Disconnect. Late worker completion must
        // close its new session rather than publishing after cancellation.
        main.post {
            if(!live(token)) { value.close();return@post }
            val openingAttempt=connectionAttempt
            if(backgroundLease==null && !beginBackgroundSession()) { value.close();return@post }
            if(openingAttempt!=null && !advanceConnection(token,openingAttempt,SonyConnectionAttempt.Stage.METADATA)) { value.close();return@post }
            session=value
            val toRemember=pendingWifiBookmark;pendingWifiBookmark=null
            worker.execute {
                try {
                    val remembered=toRemember?.let { runCatching { bookmarks.remember(value.model,it.ssid,it.password) }.getOrNull() }
                    if(toRemember!=null && remembered==null) post(token) { Toast.makeText(this,"Connected, but the camera’s Wi-Fi details could not be remembered. Scan its QR again next time.",Toast.LENGTH_LONG).show() }
                    val found=value.handles();require(found.size<=100000)
                    if(remembered!=null) post(token) { cameraScope=remembered.id;cameraScopeKind="SAVED_WIFI_CAMERA";refreshSavedCameras() }
                    post(token) {
                        if(openingAttempt!=null && !finishConnection(token,openingAttempt)) return@post
                        handles=found;offset=0;container="0";folderName="Camera library";folders.clear();clearItems();showingSetup=false
                        updatePresentation();sessionStatus="Reading the complete camera library…";setBusy(false);loadCatalogue()
                    }
                } catch(error:Exception) { post(token) { disconnect("Camera file listing failed (${safeReason(error)}). Reconnect in transfer mode.") } }
            }
        }
    }
    private fun safeReason(error:Exception):String = Regex("0x[0-9a-fA-F]{4,8}").find(error.message.orEmpty())?.value ?: error.javaClass.simpleName
    private fun loadCatalogue(refreshHandles:Boolean=false) {
        val current=session ?: return;if(busy) return
        visibleItems.clear();browser.reset();browser.submit(emptyList(),"Photos",false,true)
        val token=generation;setBusy(true)
        worker.execute {
            try {
                val indexed=linkedMapOf<String,Item>();var lastUpdate=0L
                fun progress(files:Int,folders:Int) {
                    val now=SystemClock.elapsedRealtime();if(now-lastUpdate<500) return;lastUpdate=now
                    // Snapshot on the listing worker. UI callbacks never iterate its
                    // mutable index, and partial entries remain non-selectable.
                    val incoming=indexed.values.map { entry(it) }
                    post(token) {
                        browser.setSourceScope(cameraScope);browser.submit(incoming,"Photos",false,true)
                        sessionStatus="Reading all camera files: $files files · $folders folders. Selection is available when listing completes."
                        importPercent.text="Loading gallery metadata · Previews available as files arrive"
                    }
                }
                if(current.hierarchical) {
                    SonyCatalogScanner().scan({ parent,start,count ->
                        val page=current.page(parent,start,count)
                        page.filterNot { it.folder }.forEach { indexed[it.id]=it }
                        SonyCatalogScanner.Page(page.map { SonyCatalogScanner.Entry(it.id,it.name,it.folder,it.size,it.video,it.capture) },current.totalListed(),current.updateListed())
                    },{!live(token)},::progress)
                } else {
                    val currentHandles=if(refreshHandles)current.handles() else handles.toList()
                    require(currentHandles.size<=100000)
                    currentHandles.forEach { handle ->
                        check(live(token));val item=current.item(handle)
                        if(!item.folder) indexed[item.id]=item
                        progress(indexed.size,0)
                    }
                }
                post(token) {
                    visibleItems.clear();visibleItems.putAll(indexed)
                    browser.setSourceScope(cameraScope);browser.submit(indexed.values.map { entry(it) },"Photos",false,false)
                    setBusy(false)
                    val videos=indexed.values.count { it.video }
                    sessionStatus="Gallery loaded: ${indexed.size-videos} photos/other files · $videos videos."
                }
            } catch(_:Exception) { post(token) { disconnect("The complete camera file list could not be read or changed during loading. Keep transfer mode open and reconnect the saved camera to retry.") } }
        }
    }
    private fun save(item:Item) { importItems(listOf(item)) }
    private fun saveBatch(keys:List<String>) { importItems(keys.mapNotNull { visibleItems[it] }) }
    private fun chooseImportFolder(items:List<Item>) {
        if(busy || session?.usable!=true || items.isEmpty() || items.any { !it.downloadable || it.size<=0 })return
        folderRequest=generation to items
        try { folderPicker.launch(null) }
        catch (_: ActivityNotFoundException) {
            folderRequest=null
            sessionStatus="Android’s folder picker is unavailable."
        }
    }
    @Volatile private var importPrefix="Importing"
    private fun importItems(items:List<Item>,shareAfter:Boolean=false,chooseQuality:Boolean=false,folder:Uri?=null) {
        val current=session ?: return
        if(!current.usable) { disconnect("The camera connection ended. Reconnect the saved camera before importing again.");return }
        if(busy || items.isEmpty() || items.size>500 || items.any { it.folder || !it.downloadable || it.size<=0 }) return
        if(current.originalsOnly || items.all { it.video }) { importSized(items,SonyImportSize.ORIGINAL_JPEG,shareAfter,folder);return }
        val modes=SonyImportSize.entries
        fun unchanged(item:Item)=SonyImportPlan.unchanged(item.name)
        val available=modes.map { size -> items.all { item ->
            (unchanged(item) && size!=SonyImportSize.ORIGINAL_RAW) || SonyImportSizePolicy.choose(size,current.resources(item)) !is SonyImportSizePolicy.Plan.Unavailable
        } }
        val preference=SonyImportPreferences(this).choice
        val preferred=preference.size?.let { modes.indexOf(it) }
        if(!chooseQuality && preferred!=null && available[preferred]) {
            importSized(items,modes[preferred],shareAfter,folder);return
        }
        val savedUnavailable=!chooseQuality && preferred!=null && !available[preferred]
        val labels=listOf("Original JPEG","2M JPEG (up to 2 megapixels)","VGA JPEG (up to 640 × 480)","Original RAW (ARW)")
        val adapter=object:android.widget.ArrayAdapter<String>(this,android.R.layout.simple_list_item_single_choice,labels.mapIndexed { index,label -> if(available[index]) label else "$label — unavailable" }) {
            override fun areAllItemsEnabled()=false
            override fun isEnabled(position:Int)=available[position]
        }
        var selected=available.indexOfFirst { it }.coerceAtLeast(0)
        val dialog=com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(if(savedUnavailable) "Saved import quality is unavailable" else "Choose import size")
            .setSingleChoiceItems(adapter,selected) { _,position -> selected=position }
            .setView(ui.text((if(savedUnavailable) "${preference.label} is not offered for every selected file. Choose an available quality for this import or cancel. " else "")+"2M/VGA may be resized on this phone after downloading a larger JPEG. Camera copies are used when suitable; previews are not originals. Videos stay unchanged. RAW is never resized.",UiKit.Type.small,secondary=true).apply { setPadding(ui.dp(24),ui.dp(12),ui.dp(24),0) })
            .setNegativeButton("Cancel",null).setPositiveButton("Import") { _,_ ->
                if(available[selected] && session===current && !busy) importSized(items,modes[selected],shareAfter,folder)
            }.create()
        SonyScreenWindows.showMedia(dialog,this);dialog.getButton(-1).isEnabled=available.any { it }
    }
    private fun importSized(items:List<Item>,size:SonyImportSize,shareAfter:Boolean=false,folder:Uri?=null) {
        val current=session ?: return
        if(busy) return
        val historySource=cameraScope;val historySourceKind=cameraScopeKind;val historyModel=current.model
        val token=generation;setBusy(true);shareReady.visibility=View.GONE;readyToShare=emptyList()
        val destination=if(folder==null) "Gallery → Cameraderie (DCIM/Cameraderie)" else "the selected folder"
        sessionStatus="Importing ${items.size} files to $destination… You can switch apps; progress stays in the camera notification."
        worker.execute {
            val history=ImportHistoryRepository.get(this)
            if(folder!=null) runCatching { history.reconcile { !live(token) } }
            ImportHistoryGate.process.importing {
            val store=SonyMediaStore(this,history)
            var completed=0;var failed=false;var cleanupFailed=false;var reconciliationRequired=false;var followUpFailed=false
            var filenameConflict = false
            val published=linkedMapOf<String,Uri>()
            for((index,item) in items.withIndex()) {
                if(!live(token)) break
                val temporary=mutableListOf<java.io.File>()
                try {
                    importPrefix="Importing ${index+1} of ${items.size}"
                    val unchanged=current.originalsOnly || (SonyImportPlan.unchanged(item.name) && size!=SonyImportSize.ORIGINAL_RAW)
                    val plan=if(unchanged) null else SonyImportSizePolicy.choose(size,current.resources(item))
                    var local:java.io.File?=null
                    val resource=when(plan) {
                        null -> null
                        is SonyImportSizePolicy.Plan.CameraFile -> plan.resource
                        is SonyImportSizePolicy.Plan.LocalJpeg -> {
                            require(plan.source.bytes<=64L*1024*1024) { "JPEG too large for local reduction" }
                            post(token) { sessionStatus="$importPrefix · Creating a reduced JPEG on this phone; downloading its source first." }
                            val source=java.io.File.createTempFile("sony-size-source-",".jpg",cacheDir).also { temporary.add(it) }
                            val received=current.copyResource(item,plan.source,Uri.fromFile(source),token)
                            check(received.bytes==plan.source.bytes)
                            source.inputStream().use { SonyOriginalVerification.verify(it,plan.source.bytes,false,{!live(token)},received.sourceSha256) }
                            val output=java.io.File.createTempFile("sony-size-output-",".jpg",cacheDir).also { temporary.add(it) }
                            post(token) { sessionStatus="$importPrefix · Processing JPEG…";importIndicator.isIndeterminate=true;importPercent.text="Processing" }
                            SonyJpegReducer.render(source,output,size) { !live(token) };local=output
                            null
                        }
                        is SonyImportSizePolicy.Plan.Unavailable -> error(plan.reason)
                    }
                    val file=local
                    val suffix=if(size==SonyImportSize.TWO_MEGAPIXEL_JPEG) "_2M.JPG" else "_VGA.JPG"
                    val name=if(file!=null) item.name.substringBeforeLast('.',item.name)+suffix else resource?.name ?: item.name
                    val expected=file?.length() ?: resource?.bytes ?: item.size
                    val documentFile = if(current.originalsOnly) checkNotNull(AlbumMediaFile.document(name)) else null
                    val importItem=MediaImportTransaction.Item(item.id,name,expected)
                    fun copy(uri:Uri):MediaImportTransaction.Copied {
                        check(live(token))
                        post(token) { sessionStatus="$importPrefix · Copying";importIndicator.isIndeterminate=false;importIndicator.setProgressCompat(0,false);importPercent.text="$importPrefix · 0% copied" }
                        fun copyCamera(destination:Uri) = if(resource!=null) current.copyResource(item,resource,destination,token)
                            else current.copy(item,destination,token)
                        val staged = file ?: if(current.originalsOnly || (folder!=null && current.needsFolderStaging)) {
                            check(cacheDir.usableSpace>=expected) { "Insufficient space to verify the camera file" }
                            val scratch = java.io.File.createTempFile("usb-camera-", ".part", cacheDir).also { temporary.add(it) }
                            check(copyCamera(Uri.fromFile(scratch)).bytes==expected)
                            scratch
                        } else null
                        val received=if(staged==null) copyCamera(uri) else copyToDestination(uri) { out ->
                            staged.inputStream().use { input ->
                                val buffer=ByteArray(65536);var bytes=0L
                                while(true) {
                                    check(live(token));val count=input.read(buffer);if(count<0) break
                                    check(count.toLong()<=expected-bytes)
                                    out.write(buffer,0,count);bytes+=count;progress(token,bytes,expected)
                                }
                                check(bytes==expected)
                                bytes
                            }
                        }
                        return MediaImportTransaction.Copied(received.bytes,received.sourceSha256)
                    }
                    fun draft(id:String,uri:String,displayName:String,tree:String?)=ImportHistoryDraft(
                        id,uri,displayName,expected,System.currentTimeMillis(),
                        if(file!=null) ImportProvenance.PHONE_JPEG_REDUCTION else ImportProvenance.OFFERED_CAMERA_BYTES,
                        requestedQuality=if(unchanged)null else size.name,cameraModel=historyModel,sourceScope=historySource,sourceScopeKind=historySourceKind,
                        sourceItemReference=java.security.MessageDigest.getInstance("SHA-256").digest(item.id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) },
                        offeredReduced=resource?.reduced,folderTree=tree)
                    fun validate(uri:Uri) {
                        if(!unchanged && size!=SonyImportSize.ORIGINAL_RAW) {
                            val options=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
                            contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it,null,options) }
                            check(options.outMimeType=="image/jpeg")
                            val w=options.outWidth;val h=options.outHeight
                            check(w>0 && h>0)
                            if(size==SonyImportSize.TWO_MEGAPIXEL_JPEG) check(w.toLong()*h<=2_000_000)
                            if(size==SonyImportSize.VGA_JPEG) check(maxOf(w,h)<=640 && minOf(w,h)<=480)
                            if(file==null && resource?.bounds!=null) check(w==resource.bounds!!.width && h==resource.bounds!!.height)
                        }
                    }
                    val result=if(folder!=null) {
                        FolderImportOutput(this,folder).publish(documentFile ?: SonyImportPlan.file(name),
                            importItem,history,{!live(token)},::copy,::draft,::validate,cameraTime=item.capture)
                    } else {
                        val basePort=store.port({ _,uri -> copy(uri) },{!live(token)},
                            { imported,uri -> published[imported.id]=uri },{ _ -> item.capture },
                            historyDraft={ imported,uri -> draft(java.util.UUID.randomUUID().toString(),uri.toString(),imported.name,null) },
                            documentFile=documentFile)
                        val port=object:MediaImportTransaction.Port by basePort {
                            override fun verify(item:MediaImportTransaction.Item,destination:String,copied:MediaImportTransaction.Copied) {
                                post(token) { sessionStatus="$importPrefix · Verifying…";importIndicator.isIndeterminate=true;importPercent.text="Verifying file" }
                                basePort.verify(item,destination,copied)
                                validate(Uri.parse(destination))
                            }
                        }
                        MediaImportTransaction().run(listOf(importItem),port,{!live(token)})
                    }
                    cleanupFailed=cleanupFailed || result.cleanupFailed
                    reconciliationRequired=reconciliationRequired || result.reconciliationRequired
                    followUpFailed=followUpFailed || result.followUpFailed
                    filenameConflict = result.filenameConflict
                    if(result.completed.isEmpty()) { failed=true;break }
                    completed++
                    val count=completed
                    post(token) { browser.saved(listOf(item.id));sessionStatus="Imported and verified $count of ${items.size}." }
                } catch(_:Exception) { failed=true;break }
                finally { temporary.forEach { if(!it.delete() && it.exists()) cleanupFailed=true } }
            }
            post(token) {
                if(!current.usable) {
                    disconnect("Import stopped after $completed completed files. Saved files remain in $destination. The camera connection ended; reconnect the saved camera to retry remaining files."+
                        if(reconciliationRequired) " Check Library before trying the file again." else if(cleanupFailed) " An incomplete item could not be confirmed removed." else "")
                    return@post
                }
                setBusy(false)
                sessionStatus=if(!failed && completed==items.size) "Verified $completed files in $destination."+(if(followUpFailed) " Some saved-file details could not be refreshed." else "")
                    else "Import stopped after $completed completed files. ${if(reconciliationRequired) "Check Library before trying the file again." else if(cleanupFailed) "An incomplete item could not be confirmed removed." else if(filenameConflict) "The destination cannot keep the original filename. Choose another folder." else "Unfinished pending files were removed."} Select remaining files to retry."
                if(!failed && completed==items.size) {
                    importIndicator.visibility=View.VISIBLE;importIndicator.isIndeterminate=false;importIndicator.setProgressCompat(100,true)
                    importPercent.visibility=View.VISIBLE;importPercent.text="100% · $completed files imported"
                }
                importedLocal.putAll(published)
                if(shareAfter && !failed && completed==items.size) {
                    val uris=items.mapNotNull { published[it.id] }
                    if(uris.size==items.size) showShare(uris) else sessionStatus="Files were imported. Open Library to share the saved files."
                }
            }
        } }
    }
    private fun shareItems(items:List<Item>) {
        if(busy || items.isEmpty() || session==null) return
        val local=items.mapNotNull { importedLocal[it.id] }
        if(local.size==items.size) showShare(local) else importItems(items,true)
    }
    private fun showShare(uris:List<Uri>) {
        if(busy || uris.isEmpty()) return
        val token=generation;setBusy(true);sessionStatus="Checking published imports for sharing…"
        worker.execute {
            try {
                check(live(token))
                val chooser=app.cameraderie.gallery.media.LocalMediaShare(this).chooser(uris)
                post(token) {
                    setBusy(false);readyToShare=uris.toList()
                    if(foreground && window.decorView.hasWindowFocus()) {
                        shareReady.visibility=View.GONE;startActivity(chooser)
                        sessionStatus="Choose Quick Share, Blip or another available app."
                    } else { shareReady.visibility=View.VISIBLE;sessionStatus="Imported files are ready. Tap Share imported files when you return." }
                }
            } catch(_:Exception) { post(token) { setBusy(false);sessionStatus="A published import is unavailable for sharing. Select the camera files and import again." } }
        }
    }
    override fun onResume() {
        super.onResume()
        foreground = true
        if (::browser.isInitialized) updatePresentation()
        if (intent.getBooleanExtra(CONNECT_ATTACHED_USB, false)) {
            intent.removeExtra(CONNECT_ATTACHED_USB)
            connectUsb()
        }
    }
    override fun onPause() { foreground=false;super.onPause() }
    private var lastProgress=0L
    private fun progress(token:Int,bytes:Long,total:Long) {
        val now=SystemClock.elapsedRealtime();if(now-lastProgress<500) return;lastProgress=now
        val percentage=app.cameraderie.gallery.media.ImportPercentage.copied(bytes,total)
        post(token) {
            importIndicator.visibility=View.VISIBLE;importPercent.visibility=View.VISIBLE
            importIndicator.isIndeterminate=percentage==null
            if(percentage!=null) importIndicator.setProgressCompat(percentage,true)
            importPercent.text=if(percentage==null) "$importPrefix · Copying" else "$importPrefix · $percentage% copied"
            sessionNotice(importPercent.text.toString(),percentage)
        }
    }
    private fun deleteOutput(uri:Uri) { try { DocumentsContract.deleteDocument(contentResolver,uri) } catch(_:Exception) { try { contentResolver.delete(uri,null,null) } catch(_:Exception) {} } }
    private fun connectUsb() {
        if(busy) return
        val devices=if(usbOnly) usbManager?.deviceList?.values.orEmpty().filter(UsbCameraTransfer::supports).sortedBy { it.deviceId }
            else listOfNotNull(attachedUsbCamera(this))
        when(devices.size) {
            0 -> sessionStatus="Connect a camera with a USB data cable and select PTP or MTP on the camera."
            1 -> requestUsbAccess(devices.single())
            else -> com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Choose a USB camera")
                .setItems(devices.mapIndexed { index, device -> SonyCameraIdentity.label(device.productName) ?: "USB camera ${index+1}" }.toTypedArray()) { _, index ->
                    requestUsbAccess(devices[index])
                }.setNegativeButton("Cancel",null).show()
        }
    }

    private fun requestUsbAccess(device:UsbDevice) {
        if(busy || usbManager?.deviceList?.get(device.deviceName)?.deviceId!=device.deviceId) return
        if(!usbManager.hasPermission(device)) {
            disconnect("Waiting for USB access…");setBusy(true)
            if(!beginBackgroundSession()) return
            usbRequest=generation to device.deviceId
            usbManager.requestPermission(device,PendingIntent.getBroadcast(this,0,Intent(usbAction).setPackage(packageName),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
            return
        }
        openUsb(device)
    }
    private val usbPermission=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            if(intent.action!=usbAction) return
            @Suppress("DEPRECATION") val device=intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            val request=usbRequest ?: return
            usbRequest=null
            if(request.first!=generation || request.second!=device.deviceId || isDestroyed) return
            if(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false) && usbManager.hasPermission(device)) openUsb(device)
            else disconnect("USB access was not allowed. Tap Connect over USB to retry.")
        }
    }
    private fun openUsb(device:UsbDevice) {
        disconnect("Opening camera USB transfer…");setBusy(true)
        if(!beginBackgroundSession()) return
        val token=generation
        worker.execute {
            var mtp:MtpDevice?=null
            try {
                val connection=usbManager.openDevice(device) ?: error("USB unavailable")
                val camera=MtpDevice(device);mtp=camera
                if(!camera.open(connection)) { connection.close();error("MTP unavailable") }
                val info=camera.deviceInfo ?: error("No identity")
                val modelName=if(usbOnly) SonyCameraIdentity.label(info.model) ?: SonyCameraIdentity.label(device.productName) ?: "USB camera"
                    else checkNotNull(SonyCameraIdentity.ptp(info.manufacturer.orEmpty(),info.model.orEmpty(),null))
                val value=object:Session {
                    override val model=modelName
                    override val needsFolderStaging=true
                    override val originalsOnly=usbOnly
                    override fun handles():List<Long> {
                        val storageIds=checkNotNull(camera.storageIds).also { check(it.isNotEmpty()) }
                        return storageIds.flatMap { storage ->
                            checkNotNull(camera.getObjectHandles(storage,0,0)).map { it.toLong() and 0xffffffffL }
                        }.distinct()
                    }
                    override fun item(handle:Long):Item {
                        val obj=camera.getObjectInfo(handle.toInt()) ?: error("Object unavailable")
                        val name=obj.name.orEmpty()
                        val capture=if(obj.dateCreated>0) java.time.Instant.ofEpochMilli(obj.dateCreated).atZone(java.time.ZoneId.systemDefault())
                            .let { CameraCaptureTime(it.toLocalDate(),it.toLocalTime(),it.offset) } else null
                        return Item(handle,obj.compressedSizeLong,name,obj.format==MtpConstants.FORMAT_ASSOCIATION,
                            downloadable=!originalsOnly || AlbumMediaFile.document(name)!=null,capture=capture)
                    }
                    override fun copy(item:Item,uri:Uri,token:Int):Transfer {
                        contentResolver.openFileDescriptor(uri,"rw")!!.use { descriptor ->
                            check(live(token) && camera.importFile(item.handle.toInt(),descriptor))
                            check(live(token) && descriptor.statSize==item.size) { "Original size mismatch" }
                            return Transfer(descriptor.statSize)
                        }
                    }
                    override fun close()=camera.close()
                }
                connected(value,token)
            } catch(_:Exception) { mtp?.close();post(token) { disconnect("Camera USB transfer failed. Select PTP or MTP on the camera, close other camera apps, then reconnect the cable.") } }
        }
    }
    private fun disconnect(message:String) {
        generation++;backgroundLease?.let { runCatching { SonyGalleryKeepAlive.end(this,it) } };backgroundLease=null;lastNotice=null
        pendingWifiBookmark=null;cameraScope=java.util.UUID.randomUUID().toString();cameraScopeKind="SESSION";importedLocal.clear();readyToShare=emptyList();readAllForSessions=false;scanGeneration=null;pendingWifiPermission=null;pendingWifiEnable=null;usbRequest=null;folders.clear();container="0";folderName="Camera library";connectionWatchdog?.let { main.removeCallbacks(it) };connectionWatchdog=null;connectionAttempt?.cancel();connectionAttempt=null
        synchronized(openingLock) { try { openingHttp?.close() } catch(_:Exception) {};openingHttp=null }
        session?.let { try { it.close() } catch(_:Exception) {} };session=null
        synchronized(sockets) { sockets.forEach { try { it.close() } catch(_:Exception) {} };sockets.clear() }
        networkCallback?.let { try { manager.unregisterNetworkCallback(it) } catch(_:Exception) {} };networkCallback=null
        handles=emptyList();offset=0
        if(::status.isInitialized) {
            sessionStatus=message;setBusy(false);showingSetup=false
            shareReady.visibility=View.GONE;browser.reset();clearItems();updatePresentation()
        }
    }
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putBoolean("manualWifiExpanded", manualWifiExpanded)
        outState.putBoolean("hadCameraSession",backgroundLease!=null || session!=null || folderRequest!=null)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        disconnect("Disconnected")
        if(receiverRegistered) unregisterReceiver(usbPermission)
        browser.close();worker.shutdownNow();super.onDestroy()
    }
}
