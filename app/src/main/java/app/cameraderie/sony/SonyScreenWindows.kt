package app.cameraderie.sony

import android.app.Dialog
import android.content.Context
import android.view.Window
import android.view.WindowManager

/** Dialogs have independent windows; apply policy before the first frame. */
internal object SonyScreenWindows {
    fun apply(window:Window,secure:Boolean) {
        if(secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    fun showMedia(dialog:Dialog,context:Context) {
        apply(checkNotNull(dialog.window),SonyScreenProtection.required(SonyImportPreferences(context).allowGalleryScreenshots,true))
        dialog.show()
    }
    fun showPrivate(dialog:Dialog) {
        apply(checkNotNull(dialog.window),true);dialog.show()
    }
}
