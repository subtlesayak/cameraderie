package app.cameraderie.sony

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Non-sensitive local preference; camera credentials are stored separately. */
internal class SonyImportPreferences(context:Context,namespace:String="media_imports") {
    private val prefs=context.getSharedPreferences(namespace,Context.MODE_PRIVATE)
    var choice:SonyImportPreference
        get()=SonyImportPreference.parse(prefs.getString("sony_photo_quality",null))
        set(value) { check(prefs.edit().putString("sony_photo_quality",value.name).commit()) }
    var allowGalleryScreenshots:Boolean
        get()=prefs.getBoolean("sony_gallery_screenshots",false)
        set(value) { if(allowGalleryScreenshots!=value) check(prefs.edit().putBoolean("sony_gallery_screenshots",value).commit()) }
    fun show(context:Context,changed:()->Unit={}) {
        val options=SonyImportPreference.entries
        var selected=options.indexOf(choice)
        MaterialAlertDialogBuilder(context).setTitle("Default photo import quality")
            .setView(app.cameraderie.ui.UiKit(context).text("Applies to camera gallery imports and Import & share. Videos stay unchanged. Unsupported RAW or JPEG choices ask before importing; no silent fallback.",14f,secondary=true).apply { val p=(24*context.resources.displayMetrics.density).toInt();setPadding(p,p/2,p,0) })
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(),selected) { _,index -> selected=index }
            .setNegativeButton("Cancel",null).setPositiveButton("Save default") { _,_ ->
                runCatching { choice=options[selected] }.onSuccess { changed() }.onFailure { android.widget.Toast.makeText(context,"The import default could not be saved. Free some phone storage and try again.",android.widget.Toast.LENGTH_LONG).show() }
            }.create().also { SonyScreenWindows.showMedia(it,context) }
    }
}
