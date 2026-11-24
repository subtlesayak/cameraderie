package app.cameraderie.gallery.media.history

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import app.cameraderie.ui.CameraderieActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.cameraderie.R
import app.cameraderie.gallery.media.DocumentImportActivity
import app.cameraderie.gallery.media.edit.VideoEditorActivity
import app.cameraderie.gallery.media.edit.VerifiedVideo
import app.cameraderie.projects.ProjectMedia
import app.cameraderie.sony.SonyGalleryLayout
import app.cameraderie.sony.SonyImportPreferences
import app.cameraderie.sony.SonyScreenProtection
import app.cameraderie.sony.SonyScreenWindows
import app.cameraderie.sony.SonySelectionMarker
import app.cameraderie.ui.RoundedMediaImageView
import app.cameraderie.ui.UiKit
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.appbar.MaterialToolbar
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Local completed imports only. This activity never binds a camera service,
 * joins Wi-Fi, starts the bridge, or imports anything from a camera. */
open class ImportedImagesActivity:CameraderieActivity() {
    protected open fun createRepository()=ImportHistoryRepository.get(this)
    private lateinit var ui:UiKit
    private lateinit var repository:ImportHistoryRepository
    private lateinit var loader:ImportedMediaLoader
    private lateinit var status:TextView
    private lateinit var empty:TextView
    private lateinit var progress:LinearProgressIndicator
    private lateinit var toolbar:MaterialToolbar
    private lateinit var selectButton:android.view.MenuItem
    private lateinit var shareButton:android.view.MenuItem
    private lateinit var clearButton:android.view.MenuItem
    private lateinit var layoutButton:android.view.MenuItem
    private lateinit var recycler:RecyclerView
    private lateinit var grid:GridLayoutManager
    private lateinit var adapter:MediaAdapter
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var generation=0
    private var task:Future<*>?=null
    private var actionSignal:android.os.CancellationSignal?=null
    private val selectionPayload=Any()
    private var records=emptyList<ImportHistoryEntry>()
    private var showing=emptyList<ImportHistoryEntry>()
    private val selected=linkedSetOf<String>()
    private var selecting=false
    private val pickingForProject by lazy { intent.getBooleanExtra(PICK_FOR_PROJECT, false) }
    private var loading=false
    private var busy=false
    private lateinit var emptyImport: android.widget.Button
    private var resumed=false
    private var filter=0
    private var mode=SonyGalleryLayout.Mode.COMPACT
    private var detail:Dialog?=null
    private var unavailable=mutableSetOf<String>()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        ui=UiKit(this);repository=createRepository();loader=ImportedMediaLoader(this,repository.local)
        selecting = pickingForProject || savedInstanceState?.getBoolean("selecting") == true
        selected.addAll(savedInstanceState?.getStringArrayList("selection").orEmpty().take(500))
        mode=runCatching { SonyGalleryLayout.Mode.valueOf(savedInstanceState?.getString("layout").orEmpty()) }.getOrDefault(mode)
        filter=savedInstanceState?.getInt("filter")?.coerceIn(0,2) ?: 0
        protect()
        val root=object:LinearLayout(this) {
            override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
                super.onSizeChanged(w,h,oldw,oldh)
                if(w!=oldw)post { if(!isDestroyed && ::grid.isInitialized)updateLayout() }
            }
        }.apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(ui.tone.background);setPadding(ui.dp(16),0,ui.dp(16),0) }
        root.setOnApplyWindowInsetsListener { view,insets ->
            val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left+ui.dp(16),bars.top,bars.right+ui.dp(16),bars.bottom);WindowInsets.CONSUMED
        }
        toolbar=MaterialToolbar(this).apply {
            title="Library";subtitle="On this phone";setTitleTextColor(ui.tone.text);setSubtitleTextColor(ui.tone.secondary)
            setNavigationIcon(R.drawable.ic_arrow_back);navigationContentDescription=if(pickingForProject) "Back to project" else "Back to Cameraderie";setNavigationOnClickListener { finish() }
            menu.add("Import from phone or SD card").setOnMenuItemClickListener {
                openFileImport()
                true
            }
            menu.add("Refresh").setOnMenuItemClickListener { if(!busy)load();true }
            selectButton=menu.add("Select files").apply {
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
                iconTintList=android.content.res.ColorStateList.valueOf(ui.tone.text)
                setOnMenuItemClickListener { if(!loading && !busy) { selecting=!selecting;if(!selecting)selected.clear();refreshSelection() };true }
            }
            shareButton=menu.add(if (pickingForProject) "Add to project" else "Share selected").apply {
                setShowAsAction(if (pickingForProject) android.view.MenuItem.SHOW_AS_ACTION_ALWAYS else android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
                iconTintList=android.content.res.ColorStateList.valueOf(ui.tone.text)
                setOnMenuItemClickListener {
                    if (selected.isNotEmpty()) {
                        if (pickingForProject) {
                            setResult(android.app.Activity.RESULT_OK, Intent().putStringArrayListExtra(PICKED_MEDIA, ArrayList(selected)))
                            finish()
                        } else prepareAction(records.filter { it.draft.id in selected }, true)
                    }
                    true
                }
            }
            clearButton=menu.add("Clear selection").apply { setOnMenuItemClickListener { selected.clear();refreshSelection();true } }
            menu.add("Filter").setOnMenuItemClickListener { chooseFilter();true }
            layoutButton=menu.add("Layout").apply { setOnMenuItemClickListener { chooseLayout();true } }
        }
        ui.add(root,toolbar)
        status=ui.text("Reading saved imports…",UiKit.Type.small,secondary=true).apply { accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE;maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END }
        ui.add(root,status,4)
        progress=LinearProgressIndicator(this).apply { isIndeterminate=true };ui.add(root,progress,8)
        empty=ui.text("",UiKit.Type.body,secondary=true).apply { gravity=Gravity.CENTER;setPadding(ui.dp(16),ui.dp(24),ui.dp(16),ui.dp(24));visibility=View.GONE }
        ui.add(root,empty)
        emptyImport = ui.action("Import from phone or SD card", primary = true, onClick = ::openFileImport).apply {
            visibility = View.GONE
        }
        ui.add(root, emptyImport)
        recycler=RecyclerView(this).apply { itemAnimator=null;clipToPadding=false;setPadding(0,ui.dp(8),0,ui.dp(8)) }
        grid=GridLayoutManager(this,columns());adapter=MediaAdapter();recycler.layoutManager=grid;recycler.adapter=adapter
        root.addView(recycler,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root);refreshSelection();updateLayout()
    }

    private fun openFileImport() {
        if (!busy) startActivity(Intent(this, DocumentImportActivity::class.java))
    }
    private fun protect()=SonyScreenWindows.apply(window,SonyScreenProtection.required(SonyImportPreferences(this).allowGalleryScreenshots,true))
    override fun onResume() { super.onResume();resumed=true;protect();load() }
    override fun onPause() { resumed=false;super.onPause() }
    override fun onStop() { generation++;cancelTask();detail?.dismiss();loading=false;busy=false;super.onStop() }
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putBoolean("selecting",selecting);outState.putStringArrayList("selection",ArrayList(selected));outState.putString("layout",mode.name);outState.putInt("filter",filter)
        super.onSaveInstanceState(outState)
    }
    private fun cancelTask() { actionSignal?.cancel();actionSignal=null;task?.cancel(true);task=null }
    private fun chooseFilter() {
        val options=arrayOf("All imports","Photos and images","Videos")
        MaterialAlertDialogBuilder(this).setTitle("Show imported media").setSingleChoiceItems(options,filter) { dialog,index ->
            filter=index;dialog.dismiss();applyFilter()
        }.setNegativeButton("Close",null).create().also { SonyScreenWindows.showMedia(it,this) }
    }
    private fun live(token:Int)=generation==token && !isDestroyed
    private fun load() {
        val token=++generation;cancelTask();loading=true;busy=false;progress.visibility=View.VISIBLE
        emptyImport.visibility = View.GONE
        status.text="Checking local imports…";refreshSelection()
        task=worker.submit {
            try {
                val recovery=repository.reconcile { !live(token) || Thread.currentThread().isInterrupted }
                val rows=repository.list { !live(token) || Thread.currentThread().isInterrupted }
                main.post {
                    if(!live(token))return@post
                    records = if (pickingForProject) rows.filter { ProjectMedia.from(it) != null } else rows
                    unavailable.clear();selected.retainAll(records.map { it.draft.id }.toSet());loading=false;progress.visibility=View.GONE
                    val count = if (records.size == 1) "1 saved import" else "${records.size} saved imports"
                    status.text = when {
                        !recovery.ran -> "$count · Import in progress"
                        recovery.unresolved > 0 -> "$count · ${recovery.unresolved} unfinished imports"
                        else -> count
                    }
                    applyFilter()
                }
            } catch(_:Exception) { main.post {
                if(live(token)) {
                    loading=false;progress.visibility=View.GONE;status.text="Couldn’t refresh local imports. Try Refresh."
                    if(records.isEmpty()) { empty.text="Saved imports could not be read.";empty.visibility=View.VISIBLE };refreshSelection()
                }
            } }
        }
    }
    private fun applyFilter() {
        showing=records.filter { row -> when(filter) { 1 -> row.receipt?.mime?.startsWith("image/")==true;2 -> row.receipt?.mime?.startsWith("video/")==true;else -> true } }
        empty.text = when {
            records.isEmpty() && pickingForProject -> "No verified imports yet."
            records.isEmpty() -> "No saved imports yet."
            else -> "No imports match this filter."
        }
        empty.visibility=if(!loading && showing.isEmpty())View.VISIBLE else View.GONE
        emptyImport.visibility = if (!loading && records.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged();refreshSelection()
    }
    private fun columns():Int {
        val width=if(::recycler.isInitialized && recycler.width>0)(recycler.width/resources.displayMetrics.density).toInt() else (resources.configuration.screenWidthDp-32).coerceAtLeast(0)
        return SonyGalleryLayout.importedColumns(mode, width, resources.configuration.fontScale)
    }
    private fun updateLayout() {
        val span=columns();grid.spanCount=span;layoutButton.title=if(span==1)"Layout: list" else "Layout: $span columns"
        adapter.notifyDataSetChanged()
    }
    private fun chooseLayout() {
        val choices=arrayOf("Compact grid","Comfortable grid","List")
        MaterialAlertDialogBuilder(this).setTitle("Imported media layout").setSingleChoiceItems(choices,mode.ordinal) { dialog,index ->
            mode=SonyGalleryLayout.Mode.entries[index];dialog.dismiss();updateLayout()
        }.setNegativeButton("Close",null).create().also { SonyScreenWindows.showMedia(it,this) }
    }
    private fun refreshSelection() {
        selectButton.title=if(selecting) "Done" else "Select files"
        selectButton.setIcon(if(selecting)R.drawable.ic_close else R.drawable.ic_select_files)
        toolbar.title=if(selecting) "${selected.size} selected" else "Library"
        toolbar.subtitle=if(selecting) "Library" else when(filter) { 1 -> "Photos and images · on this phone";2 -> "Videos · on this phone";else -> "On this phone" }
        selectButton.isEnabled=!loading && !busy && records.isNotEmpty()
        selectButton.contentDescription=if(loading)"Select files, available when local history finishes loading" else if(selecting)"Done selecting files. ${selected.size} selected. Maximum 500 files." else "Select files"
        selectButton.isVisible = !pickingForProject
        shareButton.isVisible=selecting;clearButton.isVisible=selecting
        if (pickingForProject) {
            toolbar.title = if (selected.isEmpty()) "Select media" else "${selected.size} selected"
            toolbar.subtitle = "Add to project"
            shareButton.contentDescription = "Add ${selected.size} selected files to project"
        } else {
            shareButton.title="Share";shareButton.setIcon(R.drawable.ic_share);shareButton.contentDescription="Share ${selected.size} selected files"
        }
        shareButton.isEnabled=!loading && !busy && selected.isNotEmpty() && selected.none { it in unavailable }
        clearButton.isEnabled=!busy && selected.isNotEmpty();layoutButton.isEnabled=!busy
        adapter.notifyItemRangeChanged(0,showing.size,selectionPayload)
    }
    private fun toggle(entry:ImportHistoryEntry) {
        if(loading || busy)return
        val id=entry.draft.id
        if(id in selected) { selected.remove(id);refreshSelection();return }
        if(id in unavailable) { Toast.makeText(this,"This file is unavailable on this phone.",Toast.LENGTH_SHORT).show();return }
        if(selected.size>=500) { Toast.makeText(this,"Select up to 500 files at a time.",Toast.LENGTH_SHORT).show();return }
        selected.add(id);refreshSelection()
    }
    private fun prepareAction(entries:List<ImportHistoryEntry>,share:Boolean) {
        if(loading || busy || entries.isEmpty())return
        val token=generation;val signal=android.os.CancellationSignal();actionSignal=signal;busy=true;refreshSelection();status.text="Checking saved files…"
        task=worker.submit {
            try {
                val intent=if(share)repository.local.share(entries.map { it.draft.uri },signal) else repository.local.open(entries.single().draft.uri,signal)
                main.post {
                    if(!live(token))return@post
                    actionSignal=null;busy=false;refreshSelection()
                    if(!resumed || !(window.decorView.hasWindowFocus() || detail?.window?.decorView?.hasWindowFocus()==true)) { status.text="Files are ready. Choose Open or Share again when you return.";return@post }
                    detail?.dismiss()
                    try { startActivity(intent);status.text=if(share)"Choose a sharing app and recipient." else "Opened the saved file in another app." }
                    catch(_:ActivityNotFoundException) { status.text="No compatible app is installed to open this file." }
                    catch(_:Exception) { status.text="The saved file could not be opened. Refresh and try again." }
                }
            } catch(_:Exception) { main.post { if(live(token)) { actionSignal=null;busy=false;status.text="A selected file is unavailable on this phone. Refresh to check it again.";refreshSelection() } } }
        }
    }
    private fun details(entry:ImportHistoryEntry) {
        if(loading || busy)return
        detail?.dismiss()
        val body=ui.column().apply { setPadding(ui.dp(24),ui.dp(8),ui.dp(24),ui.dp(16)) }
        val image=RoundedMediaImageView(this,16f).apply { scaleType=ImageView.ScaleType.FIT_CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val previewHeight=(resources.configuration.screenHeightDp*0.36f).toInt().coerceIn(120,300)
        body.addView(image,LinearLayout.LayoutParams(-1,ui.dp(previewHeight)))
        val filename=ui.text(entry.receipt?.displayName ?: entry.draft.displayName,UiKit.Type.body,bold=true).apply { setTextIsSelectable(true) }
        ui.add(body,filename,12)
        if (VerifiedVideo.supports(entry)) {
            ui.add(body, ui.action("Edit video", primary = true) {
                detail?.dismiss()
                startActivity(VideoEditorActivity.intent(this, entry.draft.id))
            }, 8)
        }
        val availability=ui.text("Checking saved file…",UiKit.Type.small,secondary=true);ui.add(body,availability,4)
        val facts=ui.text("",UiKit.Type.body,bold=true);ui.add(body,facts,12)
        val dimensions=ui.text("",UiKit.Type.small,secondary=true);ui.add(body,dimensions,2)
        fun dateRow(value:ImportedMediaLabels.DateDetail) {
            ui.add(body,ui.text(value.label,UiKit.Type.small,secondary=true),12)
            ui.add(body,ui.text(value.value,UiKit.Type.body),2)
            value.note?.let { ui.add(body,ui.text(it,UiKit.Type.small,secondary=true),2) }
        }
        dateRow(ImportedMediaLabels.DateDetail("Saved to",ImportedMediaLabels.destination(entry)))
        entry.draft.companionFor?.let { dateRow(ImportedMediaLabels.DateDetail("Camera companion for",it)) }
        entry.draft.videoSource?.let { dateRow(ImportedMediaLabels.DateDetail("Original video",it)) }
        fun position(ms:Long):String {
            val seconds=ms/1000
            return String.format(java.util.Locale.getDefault(),"%d:%02d.%03d",seconds/60,seconds%60,ms%1000)
        }
        entry.draft.framePositionMs?.let { dateRow(ImportedMediaLabels.DateDetail("Requested video position",position(it))) }
        val trimStart=entry.draft.trimStartUs
        val trimEnd=entry.draft.trimEndUs
        if(trimStart!=null && trimEnd!=null) {
            dateRow(ImportedMediaLabels.DateDetail("Saved video range",position(trimStart/1000)+" – "+position(trimEnd/1000)))
        }
        dateRow(ImportedMediaLabels.captureDetail(entry));dateRow(ImportedMediaLabels.addedDetail(entry))
        val more=ui.text(listOf(ImportedMediaLabels.representation(entry),entry.draft.cameraModel?.let { "Camera: $it" },
            if(entry.draft.provenance==ImportProvenance.LEGACY_UNKNOWN) "Import quality and file verification were not recorded for this earlier import." else "Details recorded at import. Original refers to the file offered by the connection.")
            .filterNotNull().joinToString("\n"),UiKit.Type.small,secondary=true).apply { visibility=View.GONE }
        val disclosure=ui.action("Show import details") {}.apply {
            minHeight=ui.dp(48);textSize=UiKit.Type.small
            setOnClickListener { val expanded=more.visibility!=View.VISIBLE;more.visibility=if(expanded)View.VISIBLE else View.GONE;text=if(expanded)"Hide import details" else "Show import details";stateDescription=if(expanded)"Expanded" else "Collapsed" }
            stateDescription="Collapsed"
        }
        ui.add(body,disclosure,8);ui.add(body,more,4)
        val title=when { entry.receipt?.mime?.startsWith("image/")==true -> "Photo details";entry.receipt?.mime?.startsWith("video/")==true -> "Video details";else -> "File details" }
        val dialog=MaterialAlertDialogBuilder(this).setTitle(title)
            .setView(ScrollView(this).apply { isFillViewport=false;addView(body) }).setNegativeButton("Close",null).setPositiveButton("Open",null).setNeutralButton("Share",null).create()
        detail=dialog;var request:Closeable?=null
        dialog.setOnDismissListener { request?.close();if(detail===dialog)detail=null }
        SonyScreenWindows.showMedia(dialog,this)
        val open=dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);val share=dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)
        open.isEnabled=false;share.isEnabled=false
        open.setOnClickListener { prepareAction(listOf(entry),false) };share.setOnClickListener { prepareAction(listOf(entry),true) }
        request=loader.load(entry,1536) { result ->
            if(detail!==dialog)return@load
            image.setImageBitmap(result.bitmap)
            val item=result.item;val receipt=entry.receipt
            filename.text=item?.name ?: receipt?.displayName ?: entry.draft.displayName
            val bytes=item?.bytes ?: receipt?.bytes ?: entry.draft.expectedBytes
            val mime=item?.mime ?: receipt?.mime
            val format=when(mime) { "image/jpeg" -> "JPEG";"image/png" -> "PNG";"video/mp4" -> "MP4";"image/x-adobe-dng" -> "DNG";"audio/wav","audio/x-wav" -> "WAV";else -> mime?.substringAfter('/')?.uppercase() ?: "Format unknown" }
            val pixels=if(receipt?.width!=null && receipt.height!=null)receipt.width.toLong()*receipt.height else null
            val megapixels=pixels?.let { String.format(java.util.Locale.getDefault()," · %.1f MP",it/1_000_000.0) }.orEmpty()
            facts.text="$format$megapixels · ${android.text.format.Formatter.formatShortFileSize(this,bytes)}"
            dimensions.text=if(receipt?.width!=null && receipt.height!=null) {
                val swap=receipt.exifOrientation in 5..8;val w=if(swap)receipt.height else receipt.width;val h=if(swap)receipt.width else receipt.height
                "$w × $h pixels"
            } else "Dimensions not recorded"
            availability.text=when(result.availability) { ImportedMediaLoader.Availability.AVAILABLE -> "Saved on this phone";ImportedMediaLoader.Availability.UNAVAILABLE -> "File unavailable on this phone";else -> "Couldn’t check this file. Close and try again." }
            if(result.bitmap==null && result.availability==ImportedMediaLoader.Availability.AVAILABLE)availability.text="Saved on this phone · Preview unavailable"
            open.isEnabled=result.availability==ImportedMediaLoader.Availability.AVAILABLE;share.isEnabled=open.isEnabled
        }
    }
    private class MediaCard(context:android.content.Context):MaterialCardView(context) {
        override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info);info.className=if(isCheckable)CheckBox::class.java.name else Button::class.java.name
            info.isCheckable=isCheckable;info.isChecked=isChecked;info.contentDescription=contentDescription;info.stateDescription=stateDescription
        }
    }
    private class SquarePreview(context: android.content.Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val side = MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY))
        }
    }

    private class Holder(
        val card: MediaCard,
        val image: ImageView,
        val placeholder: TextView,
        val name: TextView,
        val caption: TextView?,
        val marker: SonySelectionMarker,
        val videoBadge: ImageView,
    ) : RecyclerView.ViewHolder(card) {
        var request: Closeable? = null
        var binding = 0
    }

    private inner class MediaAdapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = showing.size

        override fun getItemViewType(position: Int): Int =
            if (columns() == 1) SonyGalleryLayout.Mode.LIST.ordinal else mode.ordinal

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
            val tileMode = SonyGalleryLayout.Mode.entries[type]
            val list = tileMode == SonyGalleryLayout.Mode.LIST
            val compact = tileMode == SonyGalleryLayout.Mode.COMPACT
            val card = MediaCard(this@ImportedImagesActivity).apply {
                radius = ui.dp(if (compact) 12 else 20).toFloat()
                cardElevation = 0f
                strokeWidth = 0
                checkedIcon = null
                setCardBackgroundColor(ui.tone.surface)
                isClickable = true
                isFocusable = true
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                    val gap = ui.dp(if (compact) 3 else 4)
                    setMargins(gap, gap, gap, gap)
                }
            }
            val body = LinearLayout(this@ImportedImagesActivity).apply {
                orientation = if (list) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                val inset = ui.dp(if (compact) 4 else 8)
                setPadding(inset, inset, inset, inset)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            val image = RoundedMediaImageView(this@ImportedImagesActivity, if (compact) 8f else 12f).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            val box = SquarePreview(this@ImportedImagesActivity)
            val placeholder = ui.text("FILE", 13f, secondary = true, bold = true).apply { gravity = Gravity.CENTER }
            box.addView(image, FrameLayout.LayoutParams(-1, -1))
            box.addView(placeholder, FrameLayout.LayoutParams(-1, -1))
            val videoBadge = ImageView(this@ImportedImagesActivity).apply {
                setImageResource(R.drawable.ic_play)
                setColorFilter(android.graphics.Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xB3000000.toInt())
                    cornerRadius = ui.dp(12).toFloat()
                }
                visibility = View.GONE
            }
            box.addView(videoBadge, FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.BOTTOM or Gravity.START).apply {
                marginStart = ui.dp(4)
                bottomMargin = ui.dp(4)
            })
            val marker = SonySelectionMarker(this@ImportedImagesActivity).apply { visibility = View.GONE }
            box.addView(marker, FrameLayout.LayoutParams(ui.dp(28), ui.dp(28), Gravity.TOP or Gravity.END).apply {
                marginEnd = ui.dp(4)
                topMargin = ui.dp(4)
            })
            body.addView(box, LinearLayout.LayoutParams(if (list) ui.dp(72) else -1, -2))
            val words = ui.column()
            val name = ui.text("", if (compact) 12f else 16f, bold = true).apply {
                if (compact) setSingleLine(true) else maxLines = 2
                ellipsize = if (compact) TextUtils.TruncateAt.MIDDLE else TextUtils.TruncateAt.END
            }
            val caption = if (compact) null else ui.text("", 13f, secondary = true).apply {
                maxLines = 5
                ellipsize = TextUtils.TruncateAt.END
            }
            ui.add(words, name)
            caption?.let { ui.add(words, it, 4) }
            if (list) {
                body.addView(words, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(12) })
            } else {
                ui.add(body, words, if (compact) 2 else 8)
            }
            card.addView(body)
            return Holder(card, image, placeholder, name, caption, marker, videoBadge)
        }
        private fun bindState(holder:Holder,entry:ImportHistoryEntry) {
            val id=entry.draft.id
            holder.card.isCheckable=selecting;holder.card.isChecked=id in selected;holder.marker.visibility=if(selecting)View.VISIBLE else View.GONE;holder.marker.checked=id in selected
            holder.card.isEnabled=!loading && !busy
            holder.card.strokeWidth=if(id in selected)ui.dp(2) else 0;holder.card.strokeColor=ui.tone.accent;holder.card.setCardBackgroundColor(if(id in selected)ui.tone.container else ui.tone.surface)
            holder.name.setTextColor(if(id in selected)ui.tone.onContainer else ui.tone.text)
            holder.caption?.setTextColor(if(id in selected)ui.tone.onContainer else ui.tone.secondary)
            holder.card.contentDescription="${entry.receipt?.displayName ?: entry.draft.displayName}, ${ImportedMediaLabels.destination(entry)}, ${ImportedMediaLabels.representation(entry)}, ${ImportedMediaLabels.captured(entry)}, ${ImportedMediaLabels.imported(entry)}"
            holder.card.stateDescription=if(id in unavailable)"File unavailable" else if(selecting)if(id in selected)"Selected" else "Not selected" else "Open details"
        }
        override fun onBindViewHolder(holder:Holder,position:Int,payloads:MutableList<Any>) {
            if(payloads.isNotEmpty() && payloads.all { it===selectionPayload })bindState(holder,showing[position]) else super.onBindViewHolder(holder,position,payloads)
        }
        override fun onBindViewHolder(holder:Holder,position:Int) {
            holder.request?.close();holder.request=null;val binding=++holder.binding;val entry=showing[position];val id=entry.draft.id
            holder.name.text = entry.receipt?.displayName ?: entry.draft.displayName
            holder.caption?.let { it.text = ImportedMediaLabels.tileSummary(entry) }
            holder.videoBadge.visibility = if (entry.receipt?.mime?.startsWith("video/") == true) View.VISIBLE else View.GONE
            holder.image.setImageDrawable(null);holder.placeholder.visibility=View.VISIBLE
            holder.placeholder.text=entry.receipt?.mime?.substringAfter('/')?.substringAfterLast('-')?.uppercase()?.take(6) ?: "FILE"
            bindState(holder,entry)
            holder.card.setOnClickListener { if(selecting)toggle(entry) else details(entry) }
            holder.card.setOnLongClickListener { if(!loading && !busy) { selecting=true;toggle(entry);refreshSelection() };true }
            holder.request=loader.load(entry) { result ->
                if(holder.binding!=binding)return@load
                holder.image.setImageBitmap(result.bitmap);holder.placeholder.visibility=if(result.bitmap==null)View.VISIBLE else View.GONE
                if(result.availability==ImportedMediaLoader.Availability.UNAVAILABLE) {
                    unavailable.add(id);val changed=selected.remove(id);holder.card.stateDescription="File unavailable on this phone";holder.caption?.let { it.text = "${ImportedMediaLabels.destination(entry)}\nFile unavailable" };holder.placeholder.text="Unavailable"
                    if(changed)refreshSelection()
                } else if(result.availability==ImportedMediaLoader.Availability.AVAILABLE) {
                    unavailable.remove(id);bindState(holder,entry)
                }
            }
        }
        override fun onViewRecycled(holder:Holder) { holder.binding++;holder.request?.close();holder.request=null;holder.image.setImageDrawable(null) }
    }
    override fun onDestroy() {
        generation++;cancelTask();detail?.dismiss();loader.close();recycler.adapter=null;worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy()
    }

    internal companion object {
        const val PICK_FOR_PROJECT = "app.cameraderie.PICK_FOR_PROJECT"
        const val PICKED_MEDIA = "app.cameraderie.PICKED_MEDIA"
    }
}
