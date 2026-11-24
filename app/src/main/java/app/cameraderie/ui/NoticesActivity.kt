package app.cameraderie.ui

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import android.view.WindowInsets
import android.widget.*
import app.cameraderie.R
import com.google.android.material.appbar.MaterialToolbar
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Bundled public notices only. No camera/network/service or permission action. */
class NoticesActivity:CameraderieActivity() {
    private val documents=listOf(
        "Resolved Android runtime libraries" to RUNTIME_LIBRARIES_ASSET,
        "Apache License 2.0" to "APACHE_2_0.txt",
        "Earlier Cameraderie MIT notice" to "CAMERADERIE_MIT_NOTICE.txt",
        "Osmosis reference license" to "OSMOSIS_REFERENCE_LICENSE.txt",
        "DJI source notice" to "DJI_SOURCE_LICENSE.txt",
        "Nordic BLE license" to "NORDIC_BLE_LICENSE.txt",
        "Kotlin standard library notices" to "KOTLIN_THIRD_PARTY_NOTICES.txt")
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private var request:Future<*>?=null
    @Volatile private var generation=0
    private var selected=0
    private lateinit var content:TextView
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val ui=UiKit(this);selected=state?.getInt("document",0)?.coerceIn(documents.indices) ?: 0
        val root=ui.column().apply { setPadding(ui.dp(16),0,ui.dp(16),0);setBackgroundColor(ui.tone.background) }
        root.setOnApplyWindowInsetsListener { view,insets ->
            val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left+ui.dp(16),bars.top,bars.right+ui.dp(16),bars.bottom);WindowInsets.CONSUMED
        }
        ui.add(root,MaterialToolbar(this).apply {
            title="Dependencies and licenses";setTitleTextColor(ui.tone.text)
            setNavigationIcon(R.drawable.ic_arrow_back);navigationContentDescription="Back to About Cameraderie";setNavigationOnClickListener { finish() }
        })
        val info=packageManager.getPackageInfo(packageName,0)
        ui.add(root,ui.text("Cameraderie ${info.versionName.orEmpty()} · build ${info.longVersionCode}",UiKit.Type.small,secondary=true),4)
        val picker=Spinner(this).apply { id=View.generateViewId();adapter=ui.choices(documents.map { it.first });setSelection(selected) }
        val label=ui.text("Document",UiKit.Type.small,secondary=true).apply { labelFor=picker.id }
        ui.add(root,label,12);ui.add(root,picker,2)
        content=ui.text("Loading notice…",UiKit.Type.small).apply { setTextIsSelectable(true);setPadding(0,ui.dp(12),0,ui.dp(16)) }
        val scroll=ScrollView(this).apply { addView(content);isFillViewport=false }
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        picker.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) { selected=position;scroll.scrollTo(0,0);load() }
            override fun onNothingSelected(parent:AdapterView<*>?) {}
        }
        setContentView(root)
    }
    override fun onResume() { super.onResume();load() }
    private fun load() {
        val token=++generation;request?.cancel(true);val file=documents[selected].second
        request=worker.submit {
            val text=runCatching {
                val source = assets.open(file).bufferedReader(Charsets.UTF_8).use { it.readText() }
                when (file) {
                    "KOTLIN_THIRD_PARTY_NOTICES.txt" -> formatHeadings(source)
                    else -> source
                }
            }.getOrElse { "This notice could not be loaded. Choose the document again." }
            main.post { if(generation==token && !isDestroyed)content.text=text }
        }
    }

    private fun formatHeadings(source: String): CharSequence = SpannableStringBuilder().apply {
        source.lineSequence().forEach { line ->
            val title = line.trimStart('#')
            if (line.startsWith('#') && title.startsWith(' ')) {
                append(title.trimStart(), StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else append(line)
            append('\n')
        }
    }

    override fun onSaveInstanceState(outState:Bundle) { outState.putInt("document",selected);super.onSaveInstanceState(outState) }
    override fun onStop() { generation++;request?.cancel(true);super.onStop() }
    override fun onDestroy() { generation++;request?.cancel(true);worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy() }
}
