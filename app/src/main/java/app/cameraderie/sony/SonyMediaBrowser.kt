package app.cameraderie.sony

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.cameraderie.ui.UiKit
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.Closeable
import java.util.Locale

/** Real camera media only. All original-file selection and verification stays in the session. */
internal class SonyMediaBrowser(
    context:Context,private val ui:UiKit,private val select:(String)->Unit,
    private val saveSelected:(List<String>)->Unit,private val shareSelected:(List<String>)->Unit,
    private val importOptions:(List<String>)->Unit={},private val allowBatch:Boolean=true,private val onFilterChanged:()->Unit={},
    private val queueSelected:((List<String>)->Unit)?=null,private val folderSelected:((List<String>)->Unit)?=null,
    private val showQualityOptions:Boolean=true
):LinearLayout(context),Closeable {
    class Entry(val key:String,val name:String,val size:Long,val folder:Boolean,val downloadable:Boolean,val preview:()->ByteArray?,val detailPreview:()->ByteArray?,val capture:CameraCaptureTime?=null,val video:Boolean=false,val storageId:String?=null,val storageLabel:String?=null,val favourite:Boolean?=null) {
        val format get()=if(folder) "Folder" else name.substringAfterLast('.',"").uppercase(Locale.ROOT).ifBlank { "File" }
        val metadata get()=if(folder) "Folder" else "${storageLabel?.let { "$it · " } ?: ""}${if(video) "Video · " else ""}$format · ${fileSize(size)}${if(favourite==true) " · Favourite" else ""}"
    }
    private val loader=SonyPreviewLoader()
    private val sessionPicker=SonySessionPicker(context)
    private val path=ui.text("Camera library",UiKit.Type.section,bold=true).apply { isAccessibilityHeading=true }
    private val sessionFilter=ui.action("Date & shoot") { pickSession() }.apply { setPadding(ui.dp(12),ui.dp(12),ui.dp(12),ui.dp(12)) }
    private val clearFilterButton=com.google.android.material.button.MaterialButton(context,null,com.google.android.material.R.attr.materialIconButtonStyle).apply {
        setIconResource(app.cameraderie.R.drawable.ic_close);contentDescription="Clear date and shoot filter"
        minWidth=ui.dp(48);minHeight=ui.dp(48);visibility=GONE
        setOnClickListener { if(!busy || !listingComplete) clearFilter() }
    }
    private val filterContext=ui.text("",UiKit.Type.small,secondary=true)
    private lateinit var activeFilterRow:LinearLayout
    val browsingControlsEnabled get()=!closed && (!busy || !listingComplete)
    val hasDateFilter get()=sessionIds!=null || dateFilter
    fun pickLayout()=showLayoutOptions()
    fun clearDateFilter() { if(browsingControlsEnabled)clearFilter() }
    private val catalogueCounts=ui.text("",12f,secondary=true)
    private var allEntries=emptyList<Entry>()
    private var sessionIds:Set<String>?=null
    private var activeShoot:ShootSessions.Session?=null
    private var storageFilter:String?=null
    private var favouritesOnly=false
    private var dateFilter=false
    private var activeDate:java.time.LocalDate?=null
    private var videoMode=false
    private var listingComplete=false
    private var partialQueueSelection=false
    private val selectionReady get()=listingComplete || (queueSelected!=null && partialQueueSelection)

    fun allowPartialQueueSelection(allowed:Boolean) {
        if(partialQueueSelection==allowed)return
        partialQueueSelection=allowed
        refreshSelection()
    }
    private val photos=ui.action("Photos") { if(!busy || !listingComplete) { videoMode=false;filterSection() } }
    private val videos=ui.action("Videos") { if(!busy || !listingComplete) { videoMode=true;filterSection() } }
    private val emptySection=ui.text("",UiKit.Type.small,secondary=true)
    private val selection=SonyMediaSelection()
    private var selecting=false
    private val selectionMenu=ui.action("Select files") {
        if(allowBatch && !busy && selectionReady) {
            if(!selecting) {
                selecting=true
                if(activeShoot?.selectableAsShoot==true) selection.selectAll(entries.map { selectionItem(it) })
                refreshSelection()
            } else selectionOptions()
        }
    }
    private val shareSelection=ui.action("Share selected") { if(!busy && listingComplete) shareSelected(selection.keys()) }
    private val selectionAction get()=if(queueSelected==null)"Import selected" else "Queue selected"
    private val saveSelection=ui.action(selectionAction,true) {
        if(allowBatch && !busy && selectionReady && selection.keys().isNotEmpty()) (queueSelected ?: saveSelected)(selection.keys())
    }
    private val viewMode=ui.action("Layout") { showLayoutOptions() }
    private val recycler=RecyclerView(context).apply { visibility = INVISIBLE }
    private var wantedLayout=SonyGalleryLayout.Mode.COMPACT
    private var closed=false
    private val layout=GridLayoutManager(context,columns())
    private val adapter=MediaAdapter()
    private var entries=emptyList<Entry>()
    private var busy=false
    private var hasMore=false
    private var dialog:Dialog?=null
    init {
        orientation=VERTICAL
        path.visibility=GONE
        val filterControls=LinearLayout(context).apply { gravity=android.view.Gravity.CENTER_VERTICAL }
        filterControls.addView(filterContext,LayoutParams(0,-2,1f))
        filterControls.addView(clearFilterButton,LayoutParams(ui.dp(48),ui.dp(48)))
        activeFilterRow=filterControls;activeFilterRow.visibility=GONE
        ui.add(this,filterControls)
        val row=ui.column();ui.add(this,row)
        ui.row(row,photos,videos)
        ui.add(this,catalogueCounts,4)
        ui.add(this,emptySection,8)
        recycler.layoutManager=layout;recycler.adapter=adapter;recycler.itemAnimator=null
        recycler.clipToPadding=false;recycler.setPadding(0,ui.dp(8),0,ui.dp(8))
        addView(recycler,LayoutParams(-1,0,1f))
        if (queueSelected != null) {
            ui.row(this, selectionMenu, saveSelection)
        } else {
            ui.add(this, selectionMenu, 8)
            ui.row(this, saveSelection, shareSelection)
        }
        saveSelection.visibility=GONE;shareSelection.visibility=GONE
        if(!allowBatch)selectionMenu.visibility=GONE
        viewModeText()
    }
    private fun contentWidthDp()=if(width>0) (width/resources.displayMetrics.density).toInt() else (resources.configuration.screenWidthDp-32).coerceAtLeast(0)
    private fun columns()=SonyGalleryLayout.columns(wantedLayout,contentWidthDp(),resources.configuration.fontScale)
    private fun viewModeText() { val n=columns();viewMode.text=if(n==1) "Layout: list" else "Layout: $n columns";viewMode.contentDescription="Gallery layout: ${if(n==1) "list" else "$n columns"}. Choose layout." }
    private fun showLayoutOptions() {
        if(busy && listingComplete) return
        val modes=SonyGalleryLayout.Mode.entries
        val compact=SonyGalleryLayout.compactAvailable(contentWidthDp(),resources.configuration.fontScale)
        val labels=modes.map { it.label+(if(it==SonyGalleryLayout.Mode.COMPACT && !compact) " — unavailable at this text/window size" else "") }
        val choices=object:ArrayAdapter<String>(context,android.R.layout.simple_list_item_single_choice,labels) {
            override fun areAllItemsEnabled()=false
            override fun isEnabled(position:Int)=position!=0 || compact
            override fun getView(position:Int,convertView:View?,parent:ViewGroup)=super.getView(position,convertView,parent).apply { isEnabled=position!=0 || compact }
        }
        val chooser=MaterialAlertDialogBuilder(context).setTitle("Gallery layout").setSingleChoiceItems(choices,modes.indexOf(wantedLayout)) { dialog,index ->
            if(choices.isEnabled(index)) { setLayoutMode(modes[index]);dialog.dismiss() }
        }.setNegativeButton("Close",null).create()
        SonyScreenWindows.showMedia(chooser,context)
    }
    internal fun setLayoutMode(mode:SonyGalleryLayout.Mode) {
        if(mode==SonyGalleryLayout.Mode.COMPACT && !SonyGalleryLayout.compactAvailable(contentWidthDp(),resources.configuration.fontScale)) return
        wantedLayout=mode;layout.spanCount=columns();viewModeText();adapter.notifyDataSetChanged()
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        if(w!=oldw) post { if(!closed) { val n=columns();if(layout.spanCount!=n) { layout.spanCount=n;adapter.notifyDataSetChanged() };viewModeText() } }
    }
    fun setBusy(value:Boolean) {
        if(busy==value)return
        busy=value;photos.isEnabled=!value || !listingComplete;videos.isEnabled=photos.isEnabled;viewMode.isEnabled=photos.isEnabled
        selectionMenu.isEnabled=!value && selectionReady;saveSelection.isEnabled=!value && selectionReady && selection.keys().isNotEmpty();shareSelection.isEnabled=saveSelection.isEnabled;sessionFilter.isEnabled=!value || !listingComplete;clearFilterButton.isEnabled=sessionFilter.isEnabled
        adapter.notifyItemRangeChanged(0,entries.size,"busy")
    }
    private fun selectionItem(item:Entry)=SonyMediaSelection.Item(item.key,item.name,item.size,item.folder,item.downloadable)
    private fun selectionOptions() {
        val eligible=entries.count { it.downloadable && !it.folder && it.size>0 }
        val options=buildList<Pair<String,()->Unit>> {
            val selectAll=if(eligible>500) "Select first 500 of $eligible eligible files in view" else "Select all $eligible eligible files in view"
            add(selectAll to { selection.selectAll(entries.map { selectionItem(it) });Unit })
            add("Clear selection" to { selection.clear() })
            add("Done selecting" to { selecting=false;selection.clear() })
            if(queueSelected==null && showQualityOptions) add("Import with different quality…" to {
                if(selection.keys().isNotEmpty()) importOptions(selection.keys())
            })
            if(folderSelected!=null && selection.keys().isNotEmpty()) add("Import to folder" to { folderSelected.invoke(selection.keys()) })
        }
        val choices=object:ArrayAdapter<String>(context,android.R.layout.simple_list_item_1,options.map { it.first }) {
            override fun areAllItemsEnabled()=false
            override fun isEnabled(position:Int)=position!=0 || activeShoot?.hasTimes!=false
        }
        MaterialAlertDialogBuilder(context).setTitle("${selection.keys().size} selected").setAdapter(choices) { _,index ->
            options[index].second()
            refreshSelection()
        }.create().also { SonyScreenWindows.showMedia(it,context) }
    }
    private fun refreshSelection() {
        selectionMenu.text=selectionLabel()
        selectionMenu.isEnabled=!busy && selectionReady
        saveSelection.visibility=if(selecting) VISIBLE else GONE
        shareSelection.visibility=if(queueSelected==null)saveSelection.visibility else GONE
        saveSelection.text="$selectionAction (${selection.keys().size})"
        saveSelection.isEnabled=!busy && selectionReady && selection.keys().isNotEmpty();shareSelection.isEnabled=saveSelection.isEnabled
        selectionMenu.stateDescription=if(!selectionReady) "Available after the complete catalogue loads" else "${selection.keys().size} selected. Maximum 500 files."
        adapter.notifyDataSetChanged()
    }
    fun saved(keys:List<String>) { keys.forEach { selection.remove(it) };refreshSelection() }
    fun submit(incoming:List<Entry>,folder:String,canGoUp:Boolean,moreAvailable:Boolean) {
        val wasParsing=hasMore
        allEntries=SonyMediaOrder.newestFirst(incoming,{it.capture},{it.name},{it.key})
        if(incoming.isEmpty()) { sessionIds=null;activeShoot=null;dateFilter=false;activeDate=null;sessionFilter.text="Date & shoot";clearFilterButton.visibility=GONE }
        listingComplete=!moreAvailable
        photos.isEnabled=!busy || !listingComplete;videos.isEnabled=photos.isEnabled;viewMode.isEnabled=photos.isEnabled
        val next=allEntries.filter { accepts(it) && it.video==videoMode }
        val previous=entries
        val anchorPosition=layout.findFirstVisibleItemPosition()
        val anchor=previous.getOrNull(anchorPosition)?.key
        val anchorTop=layout.findViewByPosition(anchorPosition)?.top ?: 0
        entries=next;path.text=if(videoMode) "Videos" else "Photos";currentFolder=folder;updateSectionLabels()
        if(allEntries.isEmpty()) { selecting=false;selection.clear() }
        else selection.retain(allEntries.map { selectionItem(it) })
        selectionMenu.text=selectionLabel()
        saveSelection.visibility=if(selecting) VISIBLE else GONE
        shareSelection.visibility=if(queueSelected==null)saveSelection.visibility else GONE
        saveSelection.text="$selectionAction (${selection.keys().size})"
        saveSelection.isEnabled=!busy && selectionReady && selection.keys().isNotEmpty();shareSelection.isEnabled=saveSelection.isEnabled
        hasMore=moreAvailable;selectionMenu.isEnabled=!busy && selectionReady
        if(next.size>=previous.size && previous.indices.all { previous[it].key==next[it].key &&
            previous[it].name==next[it].name && previous[it].size==next[it].size && previous[it].favourite==next[it].favourite }) {
            if(next.size>previous.size) adapter.notifyItemRangeInserted(previous.size,next.size-previous.size)
        } else {
            adapter.notifyDataSetChanged()
            // Newer files may arrive before the current view during parsing.
            // Preserve its item/offset instead of jumping to the top repeatedly.
            val restored=next.indexOfFirst { it.key==anchor }
            if(restored>=0) layout.scrollToPositionWithOffset(restored,anchorTop) else recycler.scrollToPosition(0)
        }
        if(moreAvailable || wasParsing) recycler.scrollToPosition(0)
    }
    private fun selectionLabel():String {
        if(selecting) { val hidden=selection.keys().count { key -> entries.none { it.key==key } };return "${selection.keys().size} selected"+(if(hidden>0) " · $hidden hidden" else "")+" · Options" }
        val eligible=entries.count { it.downloadable && it.size>0 }
        return if(activeShoot?.selectableAsShoot==true) { if(eligible>500) "Select first 500 of $eligible" else "Select shoot ($eligible)" } else "Select files"
    }
    private fun scopeAccepts(item:Entry)=(storageFilter==null || item.storageId==storageFilter) &&
        (!favouritesOnly || item.favourite==true)
    fun setStorageFilter(id:String?) { storageFilter=id;clearFilter() }
    fun setFavouritesOnly(enabled:Boolean) {
        if(favouritesOnly==enabled)return
        favouritesOnly=enabled
        filterSection()
    }
    private fun accepts(item:Entry)=scopeAccepts(item) && (sessionIds?.contains(item.key) ?: (!dateFilter || item.capture?.date==activeDate))
    private fun clearFilter() {
        sessionIds=null;activeShoot=null;dateFilter=false;activeDate=null;sessionFilter.text="Date & shoot";clearFilterButton.visibility=GONE
        sessionFilter.stateDescription=null;filterSection()
    }
    internal fun showShoot(shoot:ShootSessions.Session,selectFiles:Boolean) {
        if(selectFiles && (!allowBatch || !listingComplete || !shoot.selectableAsShoot)) return
        sessionIds=shoot.itemIds.toSet();activeShoot=shoot;dateFilter=false
        sessionFilter.text=SonyShootSummary.filter(shoot);clearFilterButton.visibility=VISIBLE
        sessionFilter.stateDescription="Filtered to ${SonyShootSummary.time(shoot)}. Clear filter to show all files."
        filterSection()
        if(selectFiles && shoot.selectableAsShoot) {
            selecting=true;selection.clear();selection.selectAll(entries.map { selectionItem(it) });refreshSelection()
        }
    }
    private fun showDate(date:java.time.LocalDate?) {
        sessionIds=null;activeShoot=null;dateFilter=true;activeDate=date
        sessionFilter.text=date?.format(java.time.format.DateTimeFormatter.ofPattern("d MMM uuuu",resources.configuration.locales[0])) ?: "Unknown capture date"
        clearFilterButton.visibility=VISIBLE;sessionFilter.stateDescription="Date filter active. Opening dates does not select files."
        filterSection()
    }
    fun pickSession() {
        if(busy && listingComplete) return
        val storageEntries=allEntries.filter(::scopeAccepts)
        val scoped=storageEntries.filter { !it.folder && it.video==videoMode }
        sessionPicker.show(sourceScope,scoped.map { ShootSessions.Item(it.key,it.capture) },listingComplete,
            { shoot,selectFiles -> showShoot(shoot,selectFiles) },{ clearFilter() },
            photos=storageEntries.filter { !it.folder && !it.video && it.capture!=null }
                .groupBy { it.capture!!.date }.mapValues { (_,items) ->
                    val photo=items.first();SonyCaptureCalendar.Photo("$sourceScope:${photo.key}:${photo.size}",photo.preview)
                },
            previews=scoped.associate { it.key to SonyCaptureCalendar.Photo("$sourceScope:${it.key}:${it.size}",it.preview) },
            importable=if(allowBatch)scoped.filter { it.downloadable && it.size>0 }.map { it.key }.toSet() else emptySet(),
            section=if(videoMode) "videos" else "photos/other files",
            summary=SonyDateSummary.summarize(storageEntries.filter { !it.folder }.map { SonyDateSummary.Item(it.key,it.capture,it.video) },listingComplete),
            countScope=if(videoMode) SonyDateSummary.Scope.VIDEOS else SonyDateSummary.Scope.PHOTOS,viewDate={ showDate(it) })
    }
    private fun updateSectionLabels() {
        filterContext.text=sessionFilter.text
        activeFilterRow.visibility=if(hasDateFilter)VISIBLE else GONE
        photos.isCheckable=true;videos.isCheckable=true
        photos.isChecked=!videoMode;videos.isChecked=videoMode
        photos.stateDescription=if(!videoMode) "Selected section" else "Not selected"
        videos.stateDescription=if(videoMode) "Selected section" else "Not selected"
        val scoped=allEntries.filter { accepts(it) }
        photos.text="Photos (${scoped.count { !it.folder && !it.video }})";videos.text="Videos (${scoped.count { !it.folder && it.video }})"
        val files=allEntries.filter { !it.folder }
        catalogueCounts.text="${if(listingComplete) "Library (all storage)" else "Provisional library (all storage)"}: ${files.size} files · ${files.count { !it.video }} photos/other · ${files.count { it.video }} videos\nShowing ${entries.count { !it.folder }} ${if(videoMode) "videos" else "photos/other files"}${if(sessionIds!=null || dateFilter || storageFilter!=null) " in this filter" else ""}${if(favouritesOnly) " · Favourites only" else ""}"
        recycler.visibility = if (entries.isEmpty()) INVISIBLE else VISIBLE
        emptySection.visibility=if(entries.isEmpty() && listingComplete) VISIBLE else GONE
        emptySection.text=if(favouritesOnly) "No confirmed favourites in this view."
        else if(videoMode) {
            if(sessionIds!=null) "No videos in this shoot. Clear the date/shoot filter to see other files."
            else "This camera session offered no video files. Available formats depend on the camera and connection."
        } else "No photos in this selection. Open Videos or clear the date or shoot filter."
        onFilterChanged()
    }
    private fun filterSection() {
        entries=allEntries.filter { accepts(it) && it.video==videoMode }
        path.text=if(videoMode) "Videos" else "Photos"
        selection.retain(allEntries.map { selectionItem(it) });refreshSelection();updateSectionLabels();recycler.scrollToPosition(0)
    }
    private var sourceScope="sony-folder"
    fun setSourceScope(value:String) { if(sourceScope!=value) { reset();allEntries=emptyList();entries=emptyList();listingComplete=false;partialQueueSelection=false;sessionIds=null;dateFilter=false;activeDate=null;activeShoot=null;sessionFilter.text="Date & shoot";clearFilterButton.visibility=GONE;refreshSelection();updateSectionLabels() };sourceScope=value }
    private var currentFolder="Camera library"
    fun reset() { sessionPicker.close();dialog?.dismiss();dialog=null;loader.reset();selecting=false;selection.clear();refreshSelection() }
    override fun close() { closed=true;reset();recycler.adapter=null;loader.close() }
    fun details(item:Entry,save:()->Unit,options:()->Unit={},folder:(()->Unit)?=null) {
        if(busy) return
        dialog?.dismiss()
        val body=ui.column().apply { setPadding(ui.dp(24),0,ui.dp(24),ui.dp(16)) }
        val image=app.cameraderie.ui.RoundedMediaImageView(context,16f).apply { scaleType=ImageView.ScaleType.FIT_CENTER;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
        body.addView(image,LayoutParams(-1,ui.dp(240)))
        val previewStatus=ui.text("Loading camera preview…",UiKit.Type.small,secondary=true).apply { accessibilityLiveRegion=ACCESSIBILITY_LIVE_REGION_POLITE }
        val cached=loader.cached(cacheKey(item))
        if(cached!=null) { image.setImageBitmap(cached);previewStatus.text="Camera thumbnail · Loading larger preview…" }
        ui.add(body,previewStatus,8);ui.add(body,ui.text(item.metadata),12)
        if(!item.downloadable) ui.add(body,ui.text("No downloadable full-size representation with a known size was offered.",UiKit.Type.small,secondary=true),8)
        if(showQualityOptions) {
            ui.add(body,ui.text("Preview is separate from the file offered for import. Sony can offer a JPEG conversion instead of a card-original ARW.",UiKit.Type.small,secondary=true),8)
            ui.add(body,ui.text("Import quality: ${SonyImportPreferences(context).choice.label}",UiKit.Type.small,secondary=true),8)
            ui.add(body,ui.action("Choose quality for this import…") { dialog?.dismiss();options() },8)
        }
        if(item.downloadable && folder!=null)ui.add(body,ui.action("Import to folder") { dialog?.dismiss();folder() },8)
        val scroll=ScrollView(context).apply { addView(body) }
        val close = com.google.android.material.button.MaterialButton(
            context, null, com.google.android.material.R.attr.materialIconButtonStyle,
        ).apply {
            setIconResource(app.cameraderie.R.drawable.ic_close)
            contentDescription = "Close"
        }
        val heading = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(ui.dp(24), ui.dp(12), ui.dp(12), ui.dp(8))
            val name = ui.text(item.name, UiKit.Type.section, bold = true).apply {
                isAccessibilityHeading = true
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            addView(name, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(close, LayoutParams(ui.dp(48), ui.dp(48)).apply { marginStart = ui.dp(8) })
        }
        val opened = MaterialAlertDialogBuilder(context).setCustomTitle(heading).setView(scroll)
            .setPositiveButton("Import") { _, _ -> save() }.create()
        close.setOnClickListener { opened.dismiss() }
        var request:Closeable?=null
        opened.setOnDismissListener { request?.close();if(dialog===opened) dialog=null }
        dialog=opened;SonyScreenWindows.showMedia(opened,context);opened.getButton(-1).isEnabled=item.downloadable
        loader.retry(cacheKey(item)+":detail")
        request=loader.load(cacheKey(item)+":detail",item.detailPreview,2048) { bitmap ->
            if(dialog===opened) {
                if(bitmap!=null) image.setImageBitmap(bitmap)
                previewStatus.text=if(bitmap==null) {
                    if(cached!=null) "Camera thumbnail · Larger preview unavailable; try reopening" else "Preview unavailable from this camera session"
                } else "Camera preview · ${bitmap.width} × ${bitmap.height}"
            }
        }
    }
    private fun cacheKey(item:Entry)="${item.key}:${item.size}"
    private inner class Holder(val card:MaterialCardView,val image:ImageView,val imageBox:FrameLayout,val marker:SonySelectionMarker,val list:Boolean,val title:TextView,val metadata:TextView,val previewState:TextView):RecyclerView.ViewHolder(card) {
        var request:Closeable?=null
        var binding=0
    }
    private inner class MediaAdapter:RecyclerView.Adapter<Holder>() {
        override fun getItemCount()=entries.size
        override fun getItemViewType(position:Int)=if(columns()==1) 1 else 0
        override fun onCreateViewHolder(parent:ViewGroup,type:Int):Holder {
            val list=type==1
            val card=object:MaterialCardView(context) {
                override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(info)
                    info.className=if(isCheckable) CheckBox::class.java.name else Button::class.java.name
                    info.isCheckable=isCheckable;info.isChecked=isChecked
                    info.contentDescription=contentDescription;info.stateDescription=stateDescription
                }
            }.apply {
                radius=ui.dp(20).toFloat();cardElevation=0f;strokeWidth=0;checkedIcon=null;setCardBackgroundColor(ui.tone.surface)
                layoutParams=RecyclerView.LayoutParams(-1,-2).apply { setMargins(ui.dp(4),ui.dp(4),ui.dp(4),ui.dp(4)) }
                isClickable=true;isFocusable=true
            }
            val body=LinearLayout(context).apply { orientation=if(list) HORIZONTAL else VERTICAL;setPadding(ui.dp(8),ui.dp(8),ui.dp(8),ui.dp(8));importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
            val image=app.cameraderie.ui.RoundedMediaImageView(context,12f).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
            val imageBox=FrameLayout(context);imageBox.addView(image,FrameLayout.LayoutParams(-1,-1))
            val marker=SonySelectionMarker(context)
            imageBox.addView(marker,FrameLayout.LayoutParams(ui.dp(28),ui.dp(28),android.view.Gravity.TOP or android.view.Gravity.END).apply { topMargin=ui.dp(4);marginEnd=ui.dp(4) })
            body.addView(imageBox,LayoutParams(if(list)ui.dp(72) else -1,ui.dp(if(list)72 else 128)).apply { if(list) marginEnd=ui.dp(12) })
            val words=ui.column();body.addView(words,if(list)LayoutParams(0,-2,1f) else LayoutParams(-1,-2).apply { topMargin=ui.dp(8) })
            val title=ui.text("",UiKit.Type.body,bold=true).apply { ellipsize=android.text.TextUtils.TruncateAt.END };ui.add(words,title)
            val metadata=ui.text("",UiKit.Type.small,secondary=true);ui.add(words,metadata,4)
            val state=ui.text("",12f,secondary=true);ui.add(words,state,4)
            card.addView(body);return Holder(card,image,imageBox,marker,list,title,metadata,state)
        }
        override fun onBindViewHolder(holder:Holder,position:Int) {
            holder.request?.close();holder.request=null;holder.binding++
            val item=entries[position];val binding=holder.binding
            val compact=columns()==3
            holder.title.text = item.name
            holder.title.textSize = if (compact) 13f else UiKit.Type.body
            holder.title.setSingleLine(!holder.list && !item.folder)
            holder.title.maxLines = if (holder.list) 3 else if (item.folder) 2 else 1
            holder.title.ellipsize = if (holder.list || item.folder) android.text.TextUtils.TruncateAt.END else android.text.TextUtils.TruncateAt.MIDDLE
            holder.metadata.visibility = if (holder.list && !item.folder) VISIBLE else GONE
            if (holder.metadata.visibility == VISIBLE) {
                val date = item.capture?.date?.format(java.time.format.DateTimeFormatter.ofPattern("d MMM uuuu", resources.configuration.locales[0]))
                holder.metadata.text = "${item.metadata}\n${date ?: "Capture date unknown"}"
            }
            if(!holder.list) holder.imageBox.layoutParams.height=ui.dp((contentWidthDp()/columns()-24).coerceAtLeast(64))
            holder.card.contentDescription="${if(item.folder) "Open folder" else if(selecting) "Select file" else "Preview and details"}: ${item.name}, ${item.metadata}"
            holder.card.isEnabled=!busy && (!selecting || (selectionReady && !item.folder && item.downloadable && item.size>0))
            holder.card.isCheckable=selecting && !item.folder && item.downloadable
            holder.card.isChecked=selection.contains(item.key)
            holder.card.strokeWidth=if(holder.card.isChecked) ui.dp(2) else 0
            holder.card.strokeColor=ui.tone.accent
            holder.card.setCardBackgroundColor(if(holder.card.isChecked)ui.tone.container else ui.tone.surface)
            holder.title.setTextColor(if(holder.card.isChecked)ui.tone.onContainer else ui.tone.text)
            holder.metadata.setTextColor(if(holder.card.isChecked)ui.tone.onContainer else ui.tone.secondary)
            holder.previewState.setTextColor(if(holder.card.isChecked)ui.tone.onContainer else ui.tone.secondary)
            holder.marker.visibility=if(selecting && !item.folder) VISIBLE else GONE;holder.marker.checked=holder.card.isChecked
            holder.card.stateDescription=if(selecting) { if(!selectionReady) "Selection unavailable while indexing" else if(item.folder || !item.downloadable) "Cannot select" else "${if(holder.card.isChecked) "Selected" else "Not selected"}. ${selection.keys().size} selected in library." } else null
            holder.card.setOnClickListener {
                if(!busy && (!selecting || selectionReady)) {
                    if(selecting) { selection.toggle(selectionItem(item));refreshSelection() }
                    else select(item.key)
                }
            }
            holder.card.setOnLongClickListener {
                if(allowBatch && !busy && selectionReady && !item.folder && item.downloadable) {
                    selecting=true;selection.toggle(selectionItem(item));refreshSelection();true
                } else false
            }
            holder.image.setImageDrawable(null)
            holder.previewState.visibility=VISIBLE
            holder.previewState.text=if(item.folder) "Open folder" else "Loading preview…"
            holder.imageBox.visibility=if(item.folder) GONE else VISIBLE
            if(item.folder) return
            holder.request=loader.load(cacheKey(item),item.preview) { bitmap:Bitmap? ->
                if(holder.binding==binding) {
                    holder.image.setImageBitmap(bitmap)
                    holder.previewState.text=if(bitmap==null) "Preview unavailable · Tap for details" else ""
                    holder.previewState.visibility=if(bitmap==null) VISIBLE else GONE
                }
            }
        }
        override fun onViewRecycled(holder:Holder) { holder.binding++;holder.request?.close();holder.request=null;holder.image.setImageDrawable(null) }
    }
    companion object {
        private fun fileSize(bytes:Long):String = when {
            bytes<0 -> "Size unknown"
            bytes<1024 -> "$bytes B"
            bytes<1024*1024 -> String.format(Locale.getDefault(),"%.1f KB",bytes/1024.0)
            bytes<1024L*1024*1024 -> String.format(Locale.getDefault(),"%.1f MB",bytes/(1024.0*1024))
            else -> String.format(Locale.getDefault(),"%.1f GB",bytes/(1024.0*1024*1024))
        }
    }
}
