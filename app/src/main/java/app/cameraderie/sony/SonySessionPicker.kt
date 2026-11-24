package app.cameraderie.sony

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.*
import app.cameraderie.ui.UiKit
import app.cameraderie.ui.RoundedMediaImageView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** A date is navigation, not a whole-shoot selection. Calendar and list share
 * one identity/count universe, including files with unknown capture dates. */
internal class SonySessionPicker(private val context:Context):java.io.Closeable {
    private var datesDialog:android.app.Dialog?=null
    private var shootDialog:android.app.Dialog?=null
    private var dateContent:java.io.Closeable?=null
    private var shootPreviews:SonyPreviewLoader?=null
    private var previousScope:String?=null
    private var rememberedMonth:YearMonth?=null
    fun show(scope:String,entries:List<ShootSessions.Item>,complete:Boolean,
        choose:(ShootSessions.Session,Boolean)->Unit,clear:()->Unit,
        photos:Map<LocalDate,SonyCaptureCalendar.Photo> = emptyMap(),
        previews:Map<String,SonyCaptureCalendar.Photo> = emptyMap(),
        importable:Set<String> = entries.map { it.id }.toSet(),section:String="files",
        summary:SonyDateSummary.Summary,countScope:SonyDateSummary.Scope,viewDate:(LocalDate?)->Unit,
        preferList:Boolean=false) {
        close()
        if(previousScope!=scope) { previousScope=scope;rememberedMonth=null }
        require(summary.complete==complete)
        check(summary.counts.inScope(countScope)==entries.map { it.id }.distinct().size)
        val ui=UiKit(context);val sessions=ShootSessions().group(scope,entries,complete)
        val groups=summary.visible(countScope);val dates=groups.mapNotNull { it.date }
        val listMode=preferList || dates.isEmpty();val root=ui.column()
        val heading=ui.column().apply { setPadding(ui.dp(16),0,ui.dp(16),ui.dp(8)) }
        ui.add(heading,ui.text("${summary.counts.inScope(countScope)} ${countScope.label} · ${if(complete) "Final counts" else "Provisional counts"}",UiKit.Type.body,bold=true))
        ui.add(heading,ui.text("Library: ${summary.counts.all} files · ${summary.counts.photos} photos/other · ${summary.counts.videos} videos",UiKit.Type.small,secondary=true),4)
        if(!complete) ui.add(heading,ui.text("This is an indexing snapshot. Reopen Dates after loading finishes for final shoot selection.",UiKit.Type.small,secondary=true),4)
        val calendar=ui.action("Calendar") {
            show(scope,entries,complete,choose,clear,photos,previews,importable,section,summary,countScope,viewDate,false)
        }.apply { isCheckable=true;isChecked=!listMode;isEnabled=dates.isNotEmpty();stateDescription=if(dates.isEmpty()) "No known capture dates" else if(!listMode) "Selected view" else "Not selected" }
        val list=ui.action("Date list") {
            show(scope,entries,complete,choose,clear,photos,previews,importable,section,summary,countScope,viewDate,true)
        }.apply { isCheckable=true;isChecked=listMode;stateDescription=if(listMode) "Selected view" else "Not selected" }
        ui.row(heading,calendar,list);ui.add(root,heading)
        fun open(group:SonyDateSummary.Group) {
            datesDialog?.dismiss()
            if(group.date==null) viewDate(null)
            else showShoots(group,sessions.filter { it.date==group.date },previews,importable,section,countScope,complete,choose,clear,viewDate) {
                show(scope,entries,complete,choose,clear,photos,previews,importable,section,summary,countScope,viewDate,listMode)
            }
        }
        if(listMode) {
            if(groups.isEmpty()) ui.add(root,ui.text("No ${countScope.label} are available in this catalogue.",UiKit.Type.small,secondary=true).apply { setPadding(ui.dp(16),ui.dp(16),ui.dp(16),ui.dp(16)) })
            else {
                val rows=SonyDateListView(context,groups,countScope,photos,complete,::open);dateContent=rows
                root.addView(rows,LinearLayout.LayoutParams(-1,ui.dp((context.resources.configuration.screenHeightDp*.42f).toInt().coerceIn(120,400))))
            }
        } else {
            val calendarView=SonyCaptureCalendar(context,dates.toSet(),photos,{ date -> open(groups.single { it.date==date }) },
                summary.datedCounts(countScope),countScope.label,complete,
                groups.filter { it.date!=null }.associate { it.date!! to "${it.counts.all} total, ${it.counts.photos} photos/other, ${it.counts.videos} videos" },rememberedMonth)
            dateContent=java.io.Closeable { rememberedMonth=calendarView.displayedMonth;calendarView.close() }
            val horizontal=SonyCalendarViewport(context,calendarView.view)
            root.addView(ScrollView(context).apply { addView(horizontal) },LinearLayout.LayoutParams(-1,ui.dp((context.resources.configuration.screenHeightDp*.52f).toInt().coerceIn(180,500))))
            val unknown=summary.unknown(countScope)
            if(unknown>0) ui.add(root,ui.action("Unknown capture date · $unknown ${countScope.label}") { datesDialog?.dismiss();viewDate(null) }.apply { contentDescription="View $unknown ${countScope.label} with unknown capture dates. No dates or shoots are inferred." },4)
        }
        val dialog=MaterialAlertDialogBuilder(context).setTitle("Date & shoot").setBackgroundInsetStart(0).setBackgroundInsetEnd(0)
            .setView(root).setNegativeButton("Close",null).setNeutralButton("Clear date filter") { _,_ -> clear() }.create()
        datesDialog=dialog
        val ownedContent=dateContent
        dialog.setOnDismissListener {
            ownedContent?.close()
            if(dateContent===ownedContent) dateContent=null
            if(datesDialog===dialog) datesDialog=null
        }
        dialog.setOnShowListener { dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT) }
        SonyScreenWindows.showMedia(dialog,context)
    }
    private fun showShoots(group:SonyDateSummary.Group,sessions:List<ShootSessions.Session>,previews:Map<String,SonyCaptureCalendar.Photo>,
        importable:Set<String>,section:String,countScope:SonyDateSummary.Scope,complete:Boolean,
        choose:(ShootSessions.Session,Boolean)->Unit,clear:()->Unit,viewDate:(LocalDate?)->Unit,back:()->Unit) {
        val ui=UiKit(context);val body=ui.column().apply { setPadding(ui.dp(16),0,ui.dp(16),ui.dp(16)) }
        check(sessions.sumOf { it.itemIds.size }==group.counts.inScope(countScope))
        ui.add(body,ui.text("${group.counts.inScope(countScope)} ${countScope.label} on this date · ${group.counts.all} total\n${group.counts.photos} photos/other · ${group.counts.videos} videos",UiKit.Type.small,secondary=true))
        ui.add(body,ui.text(if(complete) "View a shoot to browse it. Select replaces the selection with that shoot’s files. A 2-hour gap starts a new shoot." else "Provisional groups while indexing. View the date now; shoot selection unlocks after the full catalogue loads.",UiKit.Type.small,secondary=true),8)
        ui.add(body,ui.action("View date · ${group.counts.inScope(countScope)} ${countScope.label}") { shootDialog?.dismiss();viewDate(group.date) },8)
        val loader=SonyPreviewLoader(42);shootPreviews=loader
        for(shoot in sessions) {
            val time=SonyShootSummary.time(shoot);val card=ui.card(body);val row=LinearLayout(context)
            val photo=shoot.itemIds.firstNotNullOfOrNull { previews[it] }
            if(photo!=null) {
                val image=RoundedMediaImageView(context,12f).apply { scaleType=ImageView.ScaleType.CENTER_CROP;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                row.addView(image,LinearLayout.LayoutParams(ui.dp(72),ui.dp(64)).apply { marginEnd=ui.dp(12) })
                loader.load("shoot:${photo.id}",photo.preview,256) { bitmap -> if(bitmap!=null) image.setImageBitmap(bitmap) }
            }
            val words=ui.column();ui.add(words,ui.text(time,18f,bold=true))
            ui.add(words,ui.text("${shoot.itemIds.size} $section${if(complete) "" else " · provisional"}",UiKit.Type.small,secondary=true),4)
            row.addView(words,LinearLayout.LayoutParams(0,-2,1f));ui.add(card,row)
            if(!shoot.hasTimes) ui.add(card,ui.text("Capture time is unavailable. View these files and select them individually.",UiKit.Type.small,secondary=true),8)
            val eligible=shoot.itemIds.count { it in importable }
            val view=ui.action(if(shoot.hasTimes) "View shoot" else "View files") { shootDialog?.dismiss();choose(shoot,false) }.apply { isEnabled=complete;contentDescription="View $time, ${shoot.itemIds.size} $section. Does not select files." }
            val select=ui.action(if(eligible>500) "Select first 500" else "Select $eligible ${if(countScope==SonyDateSummary.Scope.VIDEOS) "videos" else "files"}",true) { shootDialog?.dismiss();choose(shoot,true) }.apply {
                isEnabled=complete && shoot.selectableAsShoot && eligible>0
                contentDescription="Select ${minOf(eligible,500)} $section from $time${if(eligible>500) "; first 500 of $eligible eligible files" else ""}. Import is a separate action."
                if(!isEnabled) stateDescription=if(!complete) "Available after indexing completes" else "Select individual files because capture time is unknown"
            }
            ui.row(card,view,select)
        }
        val dialog=MaterialAlertDialogBuilder(context).setTitle(checkNotNull(group.date).format(DateTimeFormatter.ofPattern("d MMM uuuu",context.resources.configuration.locales[0])))
            .setView(ScrollView(context).apply { addView(body) }).setNegativeButton("Back to dates") { _,_ -> back() }
            .setNeutralButton("Clear date filter") { _,_ -> clear() }.create()
        shootDialog=dialog
        dialog.setOnDismissListener { loader.close();if(shootPreviews===loader) shootPreviews=null;if(shootDialog===dialog) shootDialog=null }
        SonyScreenWindows.showMedia(dialog,context)
    }
    override fun close() {
        datesDialog?.dismiss();datesDialog=null;shootDialog?.dismiss();shootDialog=null
        dateContent?.close();dateContent=null;shootPreviews?.close();shootPreviews=null
    }
}
