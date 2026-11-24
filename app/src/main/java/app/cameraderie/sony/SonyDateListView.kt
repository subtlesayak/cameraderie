package app.cameraderie.sony

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.cameraderie.ui.RoundedMediaImageView
import app.cameraderie.ui.UiKit
import com.google.android.material.card.MaterialCardView
import java.io.Closeable
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Date rows are recycled; each image request is cancelled when its holder leaves. */
internal class SonyDateListView(context:Context,private val groups:List<SonyDateSummary.Group>,
    private val scope:SonyDateSummary.Scope,private val photos:Map<LocalDate,SonyCaptureCalendar.Photo>,
    private val complete:Boolean,private val choose:(SonyDateSummary.Group)->Unit):RecyclerView(context),Closeable {
    private val ui=UiKit(context)
    private val previews=SonyPreviewLoader()
    private val dateFormat=DateTimeFormatter.ofPattern("EEE, d MMM uuuu",resources.configuration.locales[0])
    private class Holder(val card:MaterialCardView,val image:ImageView,val heading:TextView,val count:TextView):RecyclerView.ViewHolder(card) {
        var request:Closeable?=null;var generation=0
    }
    init {
        layoutManager=LinearLayoutManager(context);itemAnimator=null
        adapter=object:RecyclerView.Adapter<Holder>() {
            init { setHasStableIds(true) }
            override fun getItemCount()=groups.size
            override fun getItemId(position:Int)=groups[position].date?.toEpochDay() ?: Long.MIN_VALUE
            override fun onCreateViewHolder(parent:ViewGroup,type:Int):Holder {
                val card=MaterialCardView(context).apply {
                    radius=ui.dp(20).toFloat();cardElevation=0f;strokeWidth=0;setCardBackgroundColor(ui.tone.surface)
                    layoutParams=RecyclerView.LayoutParams(-1,-2).apply { setMargins(ui.dp(12),ui.dp(4),ui.dp(12),ui.dp(4)) }
                    isClickable=true;isFocusable=true
                }
                val row=LinearLayout(context).apply { gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(ui.dp(12),ui.dp(12),ui.dp(12),ui.dp(12));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
                val image=RoundedMediaImageView(context,12f).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
                row.addView(image,LinearLayout.LayoutParams(ui.dp(64),ui.dp(64)).apply { marginEnd=ui.dp(12) })
                val words=ui.column();val heading=ui.text("",UiKit.Type.body,bold=true);val count=ui.text("",UiKit.Type.small,secondary=true)
                ui.add(words,heading);ui.add(words,count,4);row.addView(words,LinearLayout.LayoutParams(0,-2,1f));card.addView(row)
                return Holder(card,image,heading,count)
            }
            override fun onBindViewHolder(holder:Holder,position:Int) {
                holder.request?.close();holder.request=null;holder.generation++
                val generation=holder.generation;val group=groups[position]
                val label=group.date?.format(dateFormat) ?: "Unknown capture date"
                val n=group.counts.inScope(scope)
                val counts="$n ${scope.label} · ${group.counts.all} total\n${group.counts.photos} photos/other · ${group.counts.videos} videos"
                holder.heading.text=label;holder.count.text=counts
                holder.card.contentDescription="$label, $counts. ${if(complete) "Final counts" else "Provisional counts"}. ${if(group.date==null) "View files" else "View shoots"}"
                holder.card.setOnClickListener { choose(group) }
                holder.image.setImageDrawable(null)
                val photo=group.date?.let { photos[it] };holder.image.visibility=if(photo==null) View.GONE else View.VISIBLE
                if(photo!=null) holder.request=previews.load("date:${photo.id}",photo.preview,256) { bitmap ->
                    if(generation==holder.generation) holder.image.setImageBitmap(bitmap)
                }
            }
            override fun onViewRecycled(holder:Holder) { holder.generation++;holder.request?.close();holder.request=null;holder.image.setImageDrawable(null) }
        }
    }
    override fun close() { adapter=null;previews.close() }
}
