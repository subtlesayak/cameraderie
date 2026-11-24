package app.cameraderie.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import app.cameraderie.R

/** Native Material 3 Expressive components. Theme roles follow system light/dark appearance. */
class UiKit(private val context: Context) {
    class Tone(context: Context) {
        val background = context.getColor(R.color.gb_surface)
        val surface = context.getColor(R.color.gb_surfaceContainerLow)
        val control = context.getColor(R.color.gb_secondaryContainer)
        val text = context.getColor(R.color.gb_onSurface)
        val secondary = context.getColor(R.color.gb_onSurfaceVariant)
        val accent = context.getColor(R.color.gb_primary)
        val onAccent = context.getColor(R.color.gb_onPrimary)
        val outline = context.getColor(R.color.gb_outlineVariant)
        val container = context.getColor(R.color.gb_primaryContainer)
        val onContainer = context.getColor(R.color.gb_onPrimaryContainer)
    }
    val tone = Tone(context)
    object Type { const val title = 28f; const val display = 36f; const val section = 22f; const val body = 16f; const val small = 14f }
    fun dp(n: Int) = (context.resources.displayMetrics.density*n).toInt()
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun text(value: String, size: Float = Type.body, secondary: Boolean = false, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(if(secondary) tone.secondary else tone.text)
        typeface = Typeface.create(if(bold) "sans-serif-medium" else "sans-serif",Typeface.NORMAL)
        setLineSpacing(0f,if(size <= Type.body) 1.4f else 1.15f)
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY
    }
    fun gap(parent: LinearLayout, height: Int) { parent.addView(View(context),LinearLayout.LayoutParams(1,dp(height))) }
    fun add(parent: LinearLayout, child: View, top: Int = 0) {
        parent.addView(child,LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(top) })
    }
    fun action(label: String, primary: Boolean = false, onClick: () -> Unit): MaterialButton = MaterialButton(context,null,if(primary) com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.materialButtonTonalStyle).apply {
        text = label; textSize = Type.body; isAllCaps = false; minHeight = dp(56); minWidth = 0
        setSingleLine(false); insetTop = 0; insetBottom = 0
        setPadding(dp(20),dp(12),dp(20),dp(12))
        setOnClickListener { onClick() }
    }
    fun toggle(label: String, onChange: (Boolean) -> Unit) = MaterialSwitch(context).apply {
        text = label; textSize = Type.body; setTextColor(tone.text); minHeight = dp(64)
        setPadding(0,dp(12),0,dp(12)); switchPadding = dp(20)
        setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }
    fun card(parent: LinearLayout, heading: String? = null, prominent: Boolean = false): LinearLayout {
        val card = MaterialCardView(context).apply {
            radius = dp(if(prominent) 32 else 24).toFloat(); cardElevation = 0f
            strokeWidth = 0; setCardBackgroundColor(if(prominent) tone.container else tone.surface)
        }
        val body = column().apply { setPadding(dp(20),dp(20),dp(20),dp(20)) }
        card.addView(body); add(parent,card,16)
        if(heading != null) {
            body.addView(text(heading,Type.section,bold=true).apply { isAccessibilityHeading = true })
            gap(body,12)
        }
        return body
    }
    fun heading(parent: LinearLayout, title: String, description: String) {
        add(parent,text(title,Type.display,bold=true).apply { isAccessibilityHeading = true },16)
        add(parent,text(description,secondary=true),8)
    }
    fun badge(label: String): TextView = text(label,Type.small,bold=true).apply {
        setTextColor(tone.onContainer)
        background = GradientDrawable().apply { setColor(tone.container); cornerRadius = dp(16).toFloat() }
        setPadding(dp(12),dp(6),dp(12),dp(6))
    }
    fun choices(values: List<String>) = object : ArrayAdapter<String>(context,android.R.layout.simple_spinner_dropdown_item,values) {
        private fun style(view: View) = (view as TextView).apply {
            textSize = Type.body; setTextColor(tone.text); setSingleLine(false); ellipsize = null
            minHeight = dp(56); setPadding(dp(12),dp(12),dp(12),dp(12))
        }
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = style(super.getView(position,convertView,parent))
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = style(super.getDropDownView(position,convertView,parent))
    }
    fun row(parent: LinearLayout, first: View, second: View) {
        val row = object : LinearLayout(context) {
            private var previousLayout: Pair<Boolean,Boolean>? = null

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val bothVisible = first.visibility != GONE && second.visibility != GONE
                val available = MeasureSpec.getSize(widthMeasureSpec) - paddingStart - paddingEnd
                val horizontal = bothVisible && resources.configuration.fontScale < 1.3f &&
                    (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED || available >= dp(280))
                val layout = horizontal to bothVisible
                if (layout != previousLayout) {
                    previousLayout = layout
                    orientation = if (horizontal) HORIZONTAL else VERTICAL
                    first.layoutParams = if (horizontal) LayoutParams(0,-2,1f).apply { marginEnd=dp(4) }
                        else LayoutParams(-1,-2)
                    second.layoutParams = if (horizontal) LayoutParams(0,-2,1f).apply { marginStart=dp(4) }
                        else LayoutParams(-1,-2).apply { if(bothVisible)topMargin=dp(8) }
                }
                super.onMeasure(widthMeasureSpec,heightMeasureSpec)
            }
        }.apply { gravity=Gravity.CENTER_VERTICAL }
        row.addView(first,LinearLayout.LayoutParams(-1,-2))
        row.addView(second,LinearLayout.LayoutParams(-1,-2))
        add(parent,row,12)
    }
    fun statusRow(parent: LinearLayout, label: String): TextView {
        add(parent,text(label,Type.small,secondary=true),12)
        return text("Not connected",bold=true).also { add(parent,it,4) }
    }
    fun page(): Pair<ScrollView,LinearLayout> {
        val scroll = ScrollView(context).apply { isFillViewport = true; clipToPadding = false }
        val frame = FrameLayout(context)
        val body = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = minOf(View.MeasureSpec.getSize(widthMeasureSpec),dp(720))
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),heightMeasureSpec)
            }
        }.apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20),0,dp(20),dp(28)) }
        frame.addView(body,FrameLayout.LayoutParams(-1,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        scroll.addView(frame,ViewGroup.LayoutParams(-1,-2))
        return scroll to body
    }
}
