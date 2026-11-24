package app.cameraderie.sony

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import app.cameraderie.ui.UiKit
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.io.Closeable
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

/** Local camera thumbnails only; empty dates have a number and no image/background. */
internal class SonyCaptureCalendar(
    private val context:Context,private val dates:Set<LocalDate>,
    private val photos:Map<LocalDate,Photo>,private val choose:(LocalDate)->Unit,
    private val counts:Map<LocalDate,Int> = emptyMap(),private val scopeLabel:String="files",
    private val complete:Boolean=true,private val countDetails:Map<LocalDate,String> = emptyMap(),initialMonth:YearMonth?=null
):Closeable {
    data class Photo(val id:String,val preview:()->ByteArray?)
    private val ui=UiKit(context)
    private val loader=SonyPreviewLoader(42)
    private val locale=context.resources.configuration.locales[0] ?: Locale.getDefault()
    private val firstDay=WeekFields.of(locale).firstDayOfWeek
    private val firstMonth=YearMonth.from(dates.minOrNull() ?: LocalDate.now())
    private val lastMonth=YearMonth.from(dates.maxOrNull() ?: LocalDate.now())
    private var month=(initialMonth ?: lastMonth).coerceIn(firstMonth,lastMonth)
    val displayedMonth get()=month
    private val scale=context.resources.configuration.fontScale
    private val numberHeight=kotlin.math.ceil(24f*scale).toInt().coerceAtLeast(24)
    private val countHeight=kotlin.math.ceil(18f*scale).toInt().coerceAtLeast(24)
    private val heading=ui.text("",UiKit.Type.section,bold=true).apply { gravity=Gravity.CENTER;isAccessibilityHeading=true }
    private val previous=MaterialButton(context,null,com.google.android.material.R.attr.materialIconButtonStyle).apply {
        setIconResource(app.cameraderie.R.drawable.ic_arrow_back);contentDescription="Previous month"
        minWidth=ui.dp(48);minHeight=ui.dp(48)
        setOnClickListener { if(month>firstMonth) { month=month.minusMonths(1);render() } }
    }
    private val next=MaterialButton(context,null,com.google.android.material.R.attr.materialIconButtonStyle).apply {
        setIconResource(app.cameraderie.R.drawable.ic_arrow_forward);contentDescription="Next month"
        minWidth=ui.dp(48);minHeight=ui.dp(48)
        setOnClickListener { if(month<lastMonth) { month=month.plusMonths(1);render() } }
    }
    private val grid=ui.column()
    val view=ui.column().apply {
        setPadding(ui.dp(8),0,ui.dp(8),ui.dp(8))
        val digits=(counts.values.maxOrNull() ?: 0).toString().length
        minimumWidth=ui.dp(kotlin.math.ceil(maxOf(48f,digits*7.5f*scale+8f)*7+16).toInt())
        val navigation=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        navigation.addView(previous,LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)))
        navigation.addView(heading,LinearLayout.LayoutParams(0,-2,1f))
        navigation.addView(next,LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)))
        ui.add(this,navigation)
        ui.add(this,ui.text("Each thumbnail previews a photo captured on that date. Counts: $scopeLabel. Dates use the camera’s clock.${if(complete) "" else " Counts are provisional while indexing."}",UiKit.Type.small,secondary=true),8)
        val weekdays=LinearLayout(context)
        for(index in 0 until 7) {
            val day=firstDay.plus(index.toLong())
            val label=ui.text(day.getDisplayName(java.time.format.TextStyle.SHORT,locale),UiKit.Type.small,secondary=true).apply {
                gravity=Gravity.CENTER;contentDescription=day.getDisplayName(java.time.format.TextStyle.FULL,locale)
            }
            weekdays.addView(label,LinearLayout.LayoutParams(0,ui.dp(kotlin.math.ceil(24f*scale).toInt().coerceAtLeast(32)),1f))
        }
        ui.add(this,weekdays);ui.add(this,grid)
    }
    init { render() }
    private fun render() {
        loader.reset();grid.removeAllViews()
        heading.text=month.format(DateTimeFormatter.ofPattern("LLLL uuuu",locale))
        previous.isEnabled=month>firstMonth;next.isEnabled=month<lastMonth
        val cells=SonyCalendarLayout.cells(month,firstDay)
        for(week in 0 until 6) {
            if((0 until 7).all { cells[week*7+it]==null }) continue
            val row=LinearLayout(context)
            for(column in 0 until 7) {
                val date=cells[week*7+column]
                val frame=FrameLayout(context).apply { minimumHeight=ui.dp(56) }
                row.addView(frame,LinearLayout.LayoutParams(0,ui.dp(numberHeight+countHeight+40),1f))
                if(date==null) { frame.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO;continue }
                frame.tag="capture-day-$date"
                val photo=photos[date]
                val selectable=date in dates
                val count=counts[date]
                val countText=count?.takeIf { it>0 }?.let { "$it $scopeLabel, ${if(complete) "final" else "provisional"} count, ${countDetails[date].orEmpty()}, " }.orEmpty()
                frame.contentDescription="${date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM uuuu",locale))}, $countText${if(photo!=null) "photos available" else if(selectable) "media available, no photo preview" else "no captured files"}"
                frame.isFocusable=selectable;frame.isClickable=selectable
                if(selectable) frame.setOnClickListener { choose(date) }
                val number=ui.text(date.dayOfMonth.toString(),16f,secondary=!selectable,bold=selectable).apply {
                    gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                frame.addView(number,FrameLayout.LayoutParams(-1,ui.dp(numberHeight)))
                if(photo!=null) {
                    val card=MaterialCardView(context).apply {
                        radius=ui.dp(8).toFloat();cardElevation=0f;strokeWidth=0
                        setCardBackgroundColor(ui.tone.surface);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    }
                    val image=app.cameraderie.ui.RoundedMediaImageView(context,8f).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
                    card.addView(image,FrameLayout.LayoutParams(-1,-1))
                    frame.addView(card,FrameLayout.LayoutParams(-1,-1).apply { setMargins(ui.dp(2),ui.dp(numberHeight),ui.dp(2),ui.dp(if(count!=null) countHeight+2 else 4)) })
                    loader.load("calendar:${photo.id}",photo.preview,256) { bitmap -> if(bitmap!=null) image.setImageBitmap(bitmap) }
                }
                if(count!=null && count>0) {
                    val amount=ui.text(count.toString(),12f,secondary=true).apply { gravity=Gravity.CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                    frame.addView(amount,FrameLayout.LayoutParams(-1,kotlin.math.ceil(countHeight*context.resources.displayMetrics.density).toInt(),Gravity.BOTTOM))
                }
                frame.accessibilityDelegate=object:View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host:View,info:android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host,info);info.className=if(selectable) Button::class.java.name else TextView::class.java.name
                    }
                }
            }
            ui.add(grid,row)
        }
    }
    override fun close() { loader.close() }
}
