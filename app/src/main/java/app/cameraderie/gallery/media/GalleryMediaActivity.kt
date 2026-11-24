package app.cameraderie.gallery.media

import android.app.Dialog
import android.content.*
import android.net.Uri
import android.os.*
import android.view.*
import android.widget.*
import app.cameraderie.ui.CameraderieActivity
import app.cameraderie.GalleryService
import app.cameraderie.R
import app.cameraderie.gallery.GalleryConnectFlow
import app.cameraderie.gallery.GalleryController
import app.cameraderie.sony.*
import app.cameraderie.ui.UiKit
import app.cameraderie.ui.RoundedMediaImageView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.Closeable

/** Navigation only binds/reads. Explicit Load starts listing on the existing
 * service-owned session. No second camera connection, command executor or route. */
class GalleryMediaActivity:CameraderieActivity() {
    private var folderRequest: String? = null
    private var selectedFolder: Uri? = null
    private var folderCompanion=false
    private val folderPicker = registerForActivityResult(LocalFolderPicker()) { folder ->
        selectedFolder=folder
        if(folder==null) folderRequest=null else finishFolderSelection()
    }

    private fun finishFolderSelection() {
        val folder=selectedFolder ?: return
        val current=service ?: return
        val request=folderRequest
        val companion=folderCompanion
        folderCompanion=false
        selectedFolder=null;folderRequest=null
        val state=current.galleryMediaState
        val item=state?.catalogue?.entries?.firstOrNull { folderKey(state,it)==request }
        if(item==null) {
            Toast.makeText(this,"The camera library changed. Reopen the file and choose a folder again.",Toast.LENGTH_LONG).show()
            return
        }
        val accepted=runCatching {
            LocalFolderPicker.persist(contentResolver,folder)
            current.importGalleryOriginal(item,folder,companion)
        }.getOrDefault(false)
        if(!accepted) Toast.makeText(this,"Choose a writable device folder while the camera library is ready.",Toast.LENGTH_LONG).show()
        render()
    }

    private fun folderKey(state:GalleryController.MediaState,item:GalleryMediaCatalog.Located) =
        "${state.scope}:${state.catalogue.generation}:${key(item)}"

    private lateinit var ui:UiKit
    private lateinit var toolbar:MaterialToolbar
    private lateinit var status:TextView
    private lateinit var indicator:LinearProgressIndicator
    private lateinit var stopScan:View
    private lateinit var load:MenuItem
    private lateinit var cancel:MenuItem
    private lateinit var dateMenu:MenuItem
    private lateinit var layoutMenu:MenuItem
    private lateinit var clearDateMenu:MenuItem
    private lateinit var favouritesMenu:MenuItem
    private var favouritesOnly=false
    private var captureMenu:MenuItem?=null
    private lateinit var browser:SonyMediaBrowser
    private val previews=SonyPreviewLoader()
    private val main=Handler(Looper.getMainLooper())
    private var service:GalleryService?=null
    private var bound=false
    private var displayedScope=""
    private var revision=-1L
    private var rows=emptyMap<String,GalleryMediaCatalog.Located>()
    private var dialog:Dialog?=null
    private var detailTask:Closeable?=null
    private var clipPlayer:GalleryClipPlayer?=null
    private var queueDialog:GalleryQueueDialog?=null
    private var openQueueOnBind=false
    private var uiActive=false
    private val refresh=object:Runnable { override fun run() { if(uiActive) { render();main.postDelayed(this,500) } } }
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName,binder:IBinder) {
            service=(binder as GalleryService.LocalBinder).service
            if(openQueueOnBind) { openQueueOnBind=false;showQueue() }
            finishFolderSelection()
            render()
        }
        override fun onServiceDisconnected(name:ComponentName) { service=null;render() }
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);ui=UiKit(this);openQueueOnBind=state==null && intent.getBooleanExtra(EXTRA_SHOW_QUEUE,false)
        folderRequest=state?.getString("folderRequest")
        folderCompanion=state?.getBoolean("folderCompanion") ?: false
        selectedFolder=state?.getString("selectedFolder")?.let(Uri::parse)
        favouritesOnly=state?.getBoolean("favouritesOnly") ?: false
        SonyScreenWindows.apply(window,SonyScreenProtection.required(SonyImportPreferences(this).allowGalleryScreenshots,true))
        val root=ui.column().apply { setBackgroundColor(ui.tone.background) }
        root.setOnApplyWindowInsetsListener { view,insets ->
            val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left+ui.dp(16),bars.top,bars.right+ui.dp(16),bars.bottom);WindowInsets.CONSUMED
        }
        toolbar=MaterialToolbar(this).apply {
            title="Camera gallery";setTitleTextColor(ui.tone.text);setSubtitleTextColor(ui.tone.secondary)
            setNavigationIcon(R.drawable.ic_arrow_back);navigationContentDescription="Back to gallery connections";setNavigationOnClickListener { finish() }
            load=menu.add("Refresh library").apply {
                setIcon(R.drawable.ic_refresh);iconTintList=android.content.res.ColorStateList.valueOf(ui.tone.text)
                contentDescription="Refresh camera library";setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                setOnMenuItemClickListener { if(service?.loadGalleryMedia()!=true)Toast.makeText(this@GalleryMediaActivity,"Wait for an idle, ready gallery connection.",Toast.LENGTH_LONG).show();render();true }
            }
            cancel=menu.add("Cancel").apply {
                setIcon(R.drawable.ic_close);contentDescription="Cancel file loading or import";setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                setOnMenuItemClickListener {
                    if(service?.galleryMediaState?.queueRunning==true)service?.pauseGalleryQueue() else service?.cancelGalleryMedia()
                    render();true
                }
            }
            dateMenu=menu.add("Date & shoot").apply { setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);setOnMenuItemClickListener { browser.pickSession();true } }
            layoutMenu=menu.add("Layout").apply { setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);setOnMenuItemClickListener { browser.pickLayout();true } }
            clearDateMenu=menu.add("Clear date/shoot filter").apply { setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);setOnMenuItemClickListener { browser.clearDateFilter();render();true } }
            favouritesMenu=menu.add("Favourites only").apply {
                isCheckable=true
                setOnMenuItemClickListener {
                    favouritesOnly=!favouritesOnly
                    browser.setFavouritesOnly(favouritesOnly)
                    if(!favouritesOnly)service?.cancelGalleryFavourites()
                    render()
                    true
                }
            }
            if(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0)captureMenu=menu.add("Capture private list diagnostics").apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);isCheckable=true
                setOnMenuItemClickListener { capturePrompt();true }
            }
            menu.add("Storage").setOnMenuItemClickListener { storagePicker();true }
            menu.add("Download queue").setOnMenuItemClickListener { showQueue();true }
            menu.add("Library").setOnMenuItemClickListener { startActivity(Intent(this@GalleryMediaActivity,app.cameraderie.gallery.media.history.ImportedImagesActivity::class.java));true }
            menu.add("Exit camera session").setOnMenuItemClickListener { service?.closeCameraGallery();render();true }
        }
        ui.add(root,toolbar)
        status=ui.text("No active camera gallery. Return to connections to open a session.",UiKit.Type.small,secondary=true).apply {
            accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        };ui.add(root,status,8)
        indicator=LinearProgressIndicator(this).apply {
            isIndeterminate=true;visibility=View.GONE;contentDescription="Camera gallery progress"
        };ui.add(root,indicator,8)
        stopScan=ui.action("Stop scanning and download") {
            if(service?.stopGalleryScanForDownloads()!=true) {
                Toast.makeText(this,"Wait until Gallery finds a downloadable file.",Toast.LENGTH_SHORT).show()
            }
            render()
        }.apply { visibility=View.GONE }
        ui.add(root,stopScan,8)
        browser=SonyMediaBrowser(this,ui,::inspect,{},{},queueSelected=::enqueueSelected)
        browser.setFavouritesOnly(favouritesOnly)
        root.addView(browser,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        bound=bindService(Intent(this,GalleryService::class.java),connection,Context.BIND_AUTO_CREATE)
    }
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putString("folderRequest",folderRequest)
        outState.putBoolean("folderCompanion",folderCompanion)
        outState.putBoolean("favouritesOnly",favouritesOnly)
        outState.putString("selectedFolder",selectedFolder?.toString())
        super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume();uiActive=true;main.post(refresh) }
    override fun onPause() {
        uiActive=false;main.removeCallbacks(refresh)
        service?.galleryMediaState?.let { if(it.importing && !it.queueRunning)service?.cancelGalleryMedia() }
        queueDialog?.close();queueDialog=null
        clipPlayer?.close();clipPlayer=null
        dialog?.dismiss();detailTask?.close();detailTask=null;super.onPause()
    }
    override fun onDestroy() {
        main.removeCallbacks(refresh);queueDialog?.close();dialog?.dismiss();detailTask?.close();previews.close();browser.close()
        if(bound)unbindService(connection);service=null;super.onDestroy()
    }
    private fun capturePrompt() {
        val prompt=MaterialAlertDialogBuilder(this).setTitle("Capture private listing diagnostics?")
            .setMessage("For the next explicit refresh only: keep up to 2 MB of media-list protocol messages in app-private storage. These may include filenames and other camera metadata. Credential commands and photograph/video download bodies are not recorded. Captures are excluded from diagnostic exports and are never uploaded. At most two captures are retained.")
            .setNegativeButton("Cancel",null).setPositiveButton("Enable for next refresh") { _,_ ->
                if(service?.armGalleryMediaCapture()==true)Toast.makeText(this,"Enabled for one refresh. Tap the refresh icon to start.",Toast.LENGTH_LONG).show();render()
            }.create();SonyScreenWindows.showMedia(prompt,this)
    }
    private fun storagePicker() {
        val options=arrayOf("All storage","SD card","Internal storage")
        val chooser=MaterialAlertDialogBuilder(this).setTitle("Show storage").setItems(options) { _,index ->
            browser.setStorageFilter(when(index) { 1 -> "SD";2 -> "INTERNAL";else -> null })
        }.setNegativeButton("Close",null).create();SonyScreenWindows.showMedia(chooser,this)
    }
    private fun key(item:GalleryMediaCatalog.Located)="${item.media.key.store.name}:${item.revision}:${item.media.key.path}"
    private fun enqueueSelected(keys:List<String>) {
        val items=keys.mapNotNull(rows::get)
        val added=if(items.size!=keys.size)null else runCatching { service?.enqueueGalleryOriginals(items) }.getOrNull()
        if(added==null) {
            Toast.makeText(this,"Could not queue these files. Check the camera connection and queue space.",Toast.LENGTH_LONG).show()
        } else {
            browser.saved(keys)
            showQueue()
        }
    }
    private fun showQueue() {
        queueDialog?.close()
        queueDialog=GalleryQueueDialog(this,ui,{ service })
        queueDialog?.render()
    }
    private fun MenuItem.enableWhen(enabled:Boolean) {
        if(isEnabled!=enabled)isEnabled=enabled
    }
    private fun render() {
        if(!::browser.isInitialized)return
        val current=service;val state=current?.galleryMediaState
        val ready=current?.galleryState?.stage==GalleryConnectFlow.Stage.READY
        stopScan.visibility=if(ready && state?.let { media ->
            val library=media.catalogue
            !media.importing && !media.changingMedia && !library.downloadsAvailable &&
                library.entries.any { GalleryOriginalImporter.supports(it.media) }
        }==true && current.renewingGalleryMedia!=true)View.VISIBLE else View.GONE
        queueDialog?.render()
        val changing=state?.changingMedia==true
        val working=state?.importing==true || changing
        browser.setBusy(!ready || working)
        load.enableWhen(ready && !working && state?.catalogue?.loading!=true)
        cancel.enableWhen(!changing && (state?.importing==true || state?.catalogue?.loading==true))
        if(cancel.isVisible!=cancel.isEnabled)cancel.isVisible=cancel.isEnabled
        val cancelLabel=if(state?.queueRunning==true)"Pause download queue" else "Cancel file loading or import"
        if(cancel.contentDescription!=cancelLabel)cancel.contentDescription=cancelLabel
        val browse=ready && browser.browsingControlsEnabled
        dateMenu.enableWhen(browse);layoutMenu.enableWhen(browse)
        if(clearDateMenu.isVisible!=browser.hasDateFilter)clearDateMenu.isVisible=browser.hasDateFilter
        clearDateMenu.enableWhen(browse)
        if(favouritesMenu.isChecked!=favouritesOnly)favouritesMenu.isChecked=favouritesOnly
        favouritesMenu.enableWhen(ready && !working && (favouritesOnly || state?.catalogue?.complete==true))
        captureMenu?.let {
            it.enableWhen(load.isEnabled)
            val armed=state?.captureArmed==true
            if(it.isChecked!=armed)it.isChecked=armed
        }
        if(state==null || !ready) {
            clipPlayer?.close();clipPlayer=null
            val unavailable = if (current?.renewingGalleryMedia == true) "Reconnecting to reload camera files…"
                else "Camera gallery is not ready. Return to connections for session status or reconnect."
            if(status.text.toString()!=unavailable)status.text=unavailable
            indicator.visibility=View.GONE;browser.setBusy(true)
            if(displayedScope.isNotEmpty()) { displayedScope="";rows=emptyMap();browser.setSourceScope("disconnected");browser.submit(emptyList(),"Camera",false,true) }
            return
        }
        if(toolbar.subtitle?.toString()!=state.cameraName)toolbar.subtitle=state.cameraName
        val library=state.catalogue
        if(favouritesOnly && library.complete && !working && state.favouritesStatus==null)current.checkGalleryFavourites()
        val loadTitle=if(library.started)"Refresh library" else "Load camera files"
        if(load.title.toString()!=loadTitle)load.title=loadTitle
        val storage=library.stores.joinToString(" · ") { store ->
            val label=if(store.store.name=="SD")"SD" else "Internal"
            "$label: ${store.entries.size} · "+when(store.phase) {
                GalleryMediaCatalog.Phase.COMPLETE -> "complete"
                GalleryMediaCatalog.Phase.UNAVAILABLE -> "unavailable"
                GalleryMediaCatalog.Phase.CHANGED -> "changed; refresh"
                GalleryMediaCatalog.Phase.FAILED -> if(store.error=="NO_PAGE_DATA")"incomplete (camera sent no page data)"
                    else if (store.error=="COUNTER_LIMIT") "incomplete (session limit reached; refresh to reconnect)"
                    else "incomplete (refresh camera library)"
                GalleryMediaCatalog.Phase.CANCELED -> "canceled; incomplete"
                GalleryMediaCatalog.Phase.DEFERRED -> "retry after internal indexing"
                GalleryMediaCatalog.Phase.IDLE -> if(store.error!=null)"retrying listing" else if(library.started)"next page" else "not loaded"
                GalleryMediaCatalog.Phase.LOADING -> "indexing"
            }
        }
        val percent=state.percent?.let { it / 10 * 10 }
        val statusText=if(state.transfer.isNotBlank())state.transfer+(percent?.let { " $it%" } ?: "")+
            (if(favouritesOnly && !working)state.favouritesStatus?.let { "\n$it" }.orEmpty() else "")
            else if(favouritesOnly && library.complete && state.favouritesStatus!=null)state.favouritesStatus
            else storage + "\n" + when {
                library.complete -> "Library loaded. Select files to queue original downloads."
                library.stoppedForDownloads -> if(state.mediaRequestBusy) "Stopping the current scan. You can select the files already found."
                    else "Scan stopped. Select files already found to queue original downloads."
                library.loading -> "Loading camera files. Results are incomplete."
                library.started -> "Results are incomplete. Tap Refresh camera library to try again."
                else -> "Tap Refresh camera library to load files."
            } + if (library.complete) "" else " Capture dates are unavailable for this camera listing."
        if(status.text.toString()!=statusText)status.text=statusText
        indicator.visibility=if(library.loading || working)View.VISIBLE else View.GONE
        indicator.isIndeterminate=state.percent==null
        state.percent?.let { indicator.setProgressCompat(it,false) }
        browser.setBusy(working)
        val scope="${state.scope}:${library.generation}"
        if(displayedScope!=scope) { displayedScope=scope;revision=-1;browser.setSourceScope(scope);previews.reset() }
        browser.allowPartialQueueSelection(library.stoppedForDownloads && library.downloadsAvailable)
        if(revision!=library.revision) {
            revision=library.revision;rows=library.entries.associateBy(::key)
            val items=rows.map { (id,item) ->
                val source=current.galleryMediaPreview(item) ?: { null }
                val displaySize=item.media.size.takeIf { it>0 } ?: -1L
                SonyMediaBrowser.Entry(id,item.media.name,displaySize,false,GalleryOriginalImporter.supports(item.media),source,source,
                    video=item.media.video,storageId=item.media.key.store.name,storageLabel=if(item.media.key.store.name=="SD")"SD card" else "Internal",
                    favourite=item.media.favourite)
            }
            browser.submit(items,"Camera library",false,!library.complete)
        }
    }
    private fun inspect(id:String) {
        val item=rows[id] ?: return;val current=service ?: return
        if(current.galleryMediaState?.let { it.importing || it.changingMedia }!=false)return
        dialog?.dismiss();detailTask?.close()
        val body=ui.column().apply { setPadding(ui.dp(24),0,ui.dp(24),ui.dp(16)) }
        val image=RoundedMediaImageView(this,16f).apply { scaleType=ImageView.ScaleType.FIT_CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        body.addView(image,LinearLayout.LayoutParams(-1,ui.dp(240)))
        val size=if(item.media.size>0)"${item.media.size/1024} KB" else "File size unavailable"
        ui.add(body,ui.text("${if(item.media.key.store.name=="SD")"SD card" else "Internal storage"} · ${item.media.name.substringAfterLast('.')} · $size",secondary=true),12)
        ui.add(body,ui.text("Camera preview. Import downloads the original endpoint without resizing, then verifies saved-file integrity. Keep this screen open during the import.",UiKit.Type.small,secondary=true),8)
        if(!GalleryOriginalImporter.supports(item.media)) {
            val unavailable=if(item.media.size==0L)"Original import requires the camera to report a file size."
                else "Original import for this format is not implemented yet."
            ui.add(body,ui.text(unavailable,secondary=true),8)
        }
        if(GalleryHttpMedia.supportsProxy(item.media) && current.galleryMediaState?.catalogue?.complete==true) {
            ui.add(body,ui.action("Preview recording") {
                val source=current.galleryClip(item)
                if(source==null) {
                    Toast.makeText(this,"Reconnect the camera and reload its library to preview this clip.",Toast.LENGTH_LONG).show()
                } else {
                    dialog?.dismiss()
                    clipPlayer?.close()
                    val export:((GalleryVideoEdit)->Unit)?=if(GalleryFrameExporter.supports(item.media)) { edit ->
                        if(current.exportGalleryVideo(item,edit)) clipPlayer?.close()
                        else Toast.makeText(this,"Reconnect the camera and reload its library to export from the original.",Toast.LENGTH_LONG).show()
                        render()
                    } else null
                    clipPlayer=GalleryClipPlayer(this,ui,item.media.name,source,export) { player ->
                        if(clipPlayer===player)clipPlayer=null
                    }
                }
            },8)
        }
        if(current.galleryMediaState?.catalogue?.complete==true) {
            item.media.favourite?.let { marked ->
                ui.add(body,ui.action(if(marked) "Remove from favourites" else "Add to favourites") {
                    if(current.setGalleryFavourite(item,!marked))dialog?.dismiss()
                    else Toast.makeText(this,"Refresh the camera library to confirm this file before changing its favourite.",Toast.LENGTH_LONG).show()
                    render()
                },8)
            }
            fun openFolder(companion:Boolean) {
                val state=current.galleryMediaState ?: return
                dialog?.dismiss()
                folderRequest=folderKey(state,item)
                folderCompanion=companion
                try { folderPicker.launch(null) }
                catch (_: ActivityNotFoundException) {
                    folderRequest=null;folderCompanion=false
                    Toast.makeText(this,"Android’s folder picker is unavailable.",Toast.LENGTH_LONG).show()
                }
            }
            if(GalleryOriginalImporter.supports(item.media)) {
                ui.add(body,ui.action("Import to folder") { openFolder(false) },8)
            }
            GalleryCompanion.forParent(item.media)?.let { companion ->
                ui.add(body,ui.action("Import ${companion.extension} companion") { openFolder(true) },8)
            }
            if(current.canDeleteGalleryFile(item)) {
                ui.add(body,ui.action("Delete from camera…") { confirmCameraDelete(item) }.apply {
                    setTextColor(getColor(R.color.gb_error))
                },16)
            }
        }
        val scroll=ScrollView(this).apply { addView(body) }
        val opened=MaterialAlertDialogBuilder(this).setTitle(item.media.name).setView(scroll).setNegativeButton("Close",null)
            .setPositiveButton("Import original") { _,_ ->
                if(current.importGalleryOriginal(item)!=true)Toast.makeText(this,"Finish loading the complete library, or refresh changed media before importing.",Toast.LENGTH_LONG).show()
                render()
            }.create()
        dialog=opened;SonyScreenWindows.showMedia(opened,this)
        opened.getButton(-1).isEnabled=GalleryOriginalImporter.supports(item.media) &&
            current.galleryMediaState?.let { it.catalogue.downloadsAvailable && !it.mediaRequestBusy }==true
        opened.setOnDismissListener { detailTask?.close();detailTask=null }
        current.galleryMediaPreview(item)?.let { source -> detailTask=previews.load("$displayedScope:$id:detail",source,1536) { bitmap ->
            if(dialog===opened && opened.isShowing && bitmap!=null)image.setImageBitmap(bitmap)
        } }
    }
    private fun confirmCameraDelete(item:GalleryMediaCatalog.Located) {
        val store=if(item.media.key.store.name=="SD")"SD card" else "Internal storage"
        dialog?.dismiss()
        val confirmation=MaterialAlertDialogBuilder(this).setTitle("Delete from camera?")
            .setMessage("${item.media.name}\n$store\n\nThis permanently deletes the camera file and may remove its companion files. Imported copies on this phone are kept.")
            .setNegativeButton("Keep file",null)
            .setPositiveButton("Delete from camera") { _,_ ->
                if(service?.deleteGalleryFile(item)!=true) {
                    Toast.makeText(this,"Refresh the camera library and confirm this file again.",Toast.LENGTH_LONG).show()
                }
                render()
            }.create()
        dialog=confirmation
        SonyScreenWindows.showMedia(confirmation,this)
        confirmation.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(getColor(R.color.gb_error))
    }
    companion object { const val EXTRA_SHOW_QUEUE="show_download_queue" }
}
